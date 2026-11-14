#!/usr/bin/env python3
"""
HTi HT-203U (USB UVC, 256x384 YUYV) thermal viewer for Linux.

Frame layout (discovered from the raw stream):
  * 256x384 YUYV frame = 196608 bytes
  * top 192 rows   : 8-bit grayscale thermal image (Y channel), chroma is constant 128
  * bottom 192 rows: 192x256 little-endian uint16 per pixel, temperature in
                     units of 1/64 Kelvin  ->  T[C] = raw / 64 - 273.15
  * the first ~70 frames after start are a uniform 32768 (sensor/shutter warm-up); skipped.

Usage:
  python3 thermal.py                  # live window (matplotlib)
  python3 thermal.py --device /dev/video0 --cmap inferno
  python3 thermal.py --save-png shot.png   # grab one valid frame and exit
  python3 thermal.py --csv frame.csv       # dump one frame's temperatures as CSV
  python3 thermal.py --virtual-cam /dev/video9   # headless: colour thermal feed as a webcam (Linux)
"""
import argparse
import subprocess
import sys
import threading
import time

import numpy as np

W, H = 256, 384
IMG_H = 192
FRAME_BYTES = W * H * 2


def temperatures(frame_bytes: bytes) -> np.ndarray:
    """Return a 192x256 float32 array of temperatures in degrees C."""
    raw = np.frombuffer(frame_bytes, dtype=np.uint8)[FRAME_BYTES // 2:]
    raw16 = raw.view("<u2").reshape(IMG_H, W)
    return raw16.astype(np.float32) / 64.0 - 273.15


def is_valid(frame_bytes: bytes) -> bool:
    """A frame whose temperature block is one constant value is still warming up."""
    raw = np.frombuffer(frame_bytes, dtype=np.uint8)[FRAME_BYTES // 2:]
    raw16 = raw.view("<u2")
    return raw16.min() != raw16.max()


def ffmpeg_input_args(device: str | None, fps: int) -> list[str]:
    """
    ffmpeg capture arguments for the current OS.
      Linux   : v4l2   (device like /dev/video0)          -- tested
      Windows : dshow  (device = camera name, see below)  -- untested
      macOS   : avfoundation (device = camera index)      -- untested
    """
    common = ["-video_size", f"{W}x{H}", "-framerate", str(fps)]
    if sys.platform.startswith("linux"):
        return ["-f", "v4l2", "-input_format", "yuyv422", *common,
                "-i", device or "/dev/video0"]
    if sys.platform == "win32":
        # list names with: ffmpeg -list_devices true -f dshow -i dummy
        if not device:
            raise SystemExit("Windows: pass --device \"<camera name>\" (see ffmpeg -list_devices)")
        return ["-f", "dshow", "-pixel_format", "yuyv422", *common,
                "-i", f"video={device}"]
    if sys.platform == "darwin":
        # list indices with: ffmpeg -f avfoundation -list_devices true -i ""
        return ["-f", "avfoundation", "-pixel_format", "yuyv422", *common,
                "-i", device or "0"]
    raise SystemExit(f"unsupported platform: {sys.platform}")


def frames(device: str | None, fps: int = 25):
    """Yield raw 256x384 YUYV frames from the camera via ffmpeg."""
    cmd = ["ffmpeg", "-loglevel", "error",
           "-fflags", "nobuffer", "-flags", "low_delay",  # don't queue frames
           *ffmpeg_input_args(device, fps),
           "-f", "rawvideo", "-pix_fmt", "yuyv422", "-"]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE)
    try:
        while True:
            buf = proc.stdout.read(FRAME_BYTES)
            if len(buf) < FRAME_BYTES:
                break
            yield buf
    finally:
        proc.kill()


def first_valid_frame(device: str | None) -> bytes:
    for buf in frames(device):
        if is_valid(buf):
            return buf
    raise RuntimeError("no valid frame received")


class LatestFrame:
    """
    Reads the camera in a background thread and keeps only the newest valid frame.
    Without this, frames pile up in the pipe whenever drawing is slower than the
    camera, and the picture falls further and further behind real time.
    """

    def __init__(self, device: str | None):
        self._device = device
        self._frame = None
        self._lock = threading.Lock()
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        for buf in frames(self._device):
            if is_valid(buf):
                with self._lock:
                    self._frame = buf

    def get(self) -> bytes | None:
        """Return the newest frame not yet shown, or None if nothing new."""
        with self._lock:
            buf, self._frame = self._frame, None
        return buf


def render_rgb(t: np.ndarray, cmap, vmin, vmax, scale: int) -> np.ndarray:
    """Colour-map a temperature frame to an RGB image with a green centre cross."""
    lo = float(t.min()) if vmin is None else vmin
    hi = float(t.max()) if vmax is None else vmax
    norm = np.clip((t - lo) / max(hi - lo, 1e-6), 0.0, 1.0)
    rgb = (cmap(norm)[..., :3] * 255).astype(np.uint8)
    if scale > 1:
        rgb = np.repeat(np.repeat(rgb, scale, axis=0), scale, axis=1)
    cy = (IMG_H // 2) * scale + scale // 2
    cx = (W // 2) * scale + scale // 2
    arm = 3 * scale
    rgb[cy, max(cx - arm, 0):cx + arm + 1] = (0, 255, 0)
    rgb[max(cy - arm, 0):cy + arm + 1, cx] = (0, 255, 0)
    return rgb


def run_virtual_cam(args) -> int:
    """
    Headless: push the colour-mapped thermal image into a v4l2loopback device, so
    any app (browser, Zoom, OBS...) sees it as an ordinary webcam.
    The frame is re-sent at a steady rate even when the camera pauses, so
    consumers don't see the device stall.
    """
    import matplotlib

    cmap = matplotlib.colormaps[args.cmap]
    out_w, out_h = W * args.scale, IMG_H * args.scale
    cmd = [
        "ffmpeg", "-loglevel", "error",
        "-f", "rawvideo", "-pix_fmt", "rgb24",
        "-s", f"{out_w}x{out_h}", "-r", str(args.fps), "-i", "-",
        "-f", "v4l2", "-pix_fmt", "yuyv422", args.virtual_cam,
    ]
    sink = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    reader = LatestFrame(args.device)
    period = 1.0 / args.fps
    last = None
    print(f"streaming to {args.virtual_cam} at {out_w}x{out_h} {args.fps} fps (Ctrl+C to stop)")
    try:
        while True:
            start = time.monotonic()
            buf = reader.get()
            if buf is not None:
                last = temperatures(buf)
            if last is not None:
                frame = render_rgb(last, cmap, args.vmin, args.vmax, args.scale)
                sink.stdin.write(frame.tobytes())
            time.sleep(max(0.0, period - (time.monotonic() - start)))
    except (BrokenPipeError, KeyboardInterrupt):
        pass
    finally:
        try:
            sink.stdin.close()
        except OSError:
            pass
        sink.wait()
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", default=None,
                    help="Linux: /dev/videoN (default /dev/video0); "
                         "Windows: camera name; macOS: avfoundation index (default 0)")
    ap.add_argument("--cmap", default="inferno")
    ap.add_argument("--save-png", metavar="FILE")
    ap.add_argument("--csv", metavar="FILE")
    ap.add_argument("--vmin", type=float, help="fixed colour scale min (C)")
    ap.add_argument("--vmax", type=float, help="fixed colour scale max (C)")
    ap.add_argument("--virtual-cam", metavar="DEVICE",
                    help="Linux: write colour frames to a v4l2loopback device, e.g. /dev/video9")
    ap.add_argument("--scale", type=int, default=2,
                    help="virtual camera upscale factor (default 2 -> 512x384)")
    ap.add_argument("--fps", type=int, default=25, help="virtual camera frame rate")
    args = ap.parse_args()

    if args.virtual_cam:
        return run_virtual_cam(args)

    if args.save_png or args.csv:
        t = temperatures(first_valid_frame(args.device))
        print(f"min {t.min():.2f} C  max {t.max():.2f} C  mean {t.mean():.2f} C")
        if args.csv:
            np.savetxt(args.csv, t, delimiter=",", fmt="%.2f")
            print("wrote", args.csv)
        if args.save_png:
            import matplotlib.pyplot as plt
            plt.imsave(args.save_png, t, cmap=args.cmap,
                       vmin=args.vmin if args.vmin is not None else t.min(),
                       vmax=args.vmax if args.vmax is not None else t.max())
            print("wrote", args.save_png)
        return 0

    import matplotlib.pyplot as plt
    from matplotlib.animation import FuncAnimation

    fig, ax = plt.subplots(figsize=(7.5, 6))
    fig.canvas.manager.set_window_title("HT-203U thermal")
    first = temperatures(first_valid_frame(args.device))
    im = ax.imshow(first, cmap=args.cmap, interpolation="bilinear",
                   vmin=args.vmin, vmax=args.vmax)
    cb = fig.colorbar(im, ax=ax, label="°C")
    ax.set_axis_off()
    title = ax.set_title("")
    mark_max = ax.plot([], [], "c+", ms=12)[0]
    mark_min = ax.plot([], [], "w+", ms=12)[0]
    # small bright-green crosshair at the centre pixel: its readout is the "spot" temperature
    cy, cx = IMG_H // 2, W // 2
    ax.plot([cx], [cy], "+", color="#00ff00", ms=5, mew=1.5)
    reader = LatestFrame(args.device)

    def update(_):
        buf = reader.get()
        if buf is None:  # no new frame since last tick
            return im, title
        t = temperatures(buf)
        im.set_data(t)
        if args.vmin is None or args.vmax is None:
            im.set_clim(t.min(), t.max())
        iy, ix = np.unravel_index(t.argmax(), t.shape)
        mark_max.set_data([ix], [iy])
        iy2, ix2 = np.unravel_index(t.argmin(), t.shape)
        mark_min.set_data([ix2], [iy2])
        title.set_text(f"min {t.min():.1f}  max {t.max():.1f}  "
                       f"spot {t[cy, cx]:.1f} °C")
        return im, title

    # keep a reference so the animation is not garbage-collected
    anim = FuncAnimation(fig, update, interval=30, cache_frame_data=False)  # noqa: F841
    plt.show()
    return 0


if __name__ == "__main__":
    sys.exit(main())
