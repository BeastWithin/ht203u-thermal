# HT-203U thermal viewer

A small Python viewer for the **HTi HT-203U** USB thermal camera. The camera's
official software is Android-only, so this script reads the USB video stream
directly and shows real temperatures on a PC.

Features:

- Live colour-mapped thermal view with per-pixel temperatures
- Min and max markers, plus a green centre cross with its temperature shown
- Save one frame as a PNG or a CSV of temperatures
- Virtual webcam output (Linux): the thermal view appears as a normal camera in
  browsers, Zoom, OBS and similar apps

## Requirements

- Python 3.10 or newer
- [ffmpeg](https://ffmpeg.org/) on your `PATH`
- Python packages: `numpy`, `matplotlib`

```
pip install numpy matplotlib
```

## Usage

```
python3 thermal.py                                  # live window
python3 thermal.py --cmap inferno --vmin 20 --vmax 40   # fixed colour scale
python3 thermal.py --save-png shot.png              # save one frame as an image
python3 thermal.py --csv frame.csv                  # save one frame's temperatures
python3 thermal.py --virtual-cam /dev/video9        # headless virtual webcam (Linux)
```

Options:

| Option | Meaning |
| --- | --- |
| `--device` | Camera to use. Linux: `/dev/videoN` (default `/dev/video0`). Windows: camera name. macOS: camera index (default `0`). |
| `--cmap` | Matplotlib colour map (default `inferno`) |
| `--vmin`, `--vmax` | Fixed colour scale in °C. By default the scale follows each frame. |
| `--save-png FILE` | Save one valid frame and exit |
| `--csv FILE` | Save one frame's temperatures as CSV and exit |
| `--virtual-cam DEVICE` | Stream to a v4l2loopback device instead of opening a window |
| `--scale N` | Upscale factor for the virtual camera (default 2, giving 512×384) |
| `--fps N` | Virtual camera frame rate (default 25) |

## Virtual webcam (Linux)

The virtual webcam uses the `v4l2loopback` kernel module. Load it once per boot:

```
sudo modprobe v4l2loopback devices=1 video_nr=9 card_label="HT-203U Thermal" exclusive_caps=1
```

`exclusive_caps=1` makes browsers and video apps recognise the device as a real camera.

Then start the stream:

```
python3 thermal.py --virtual-cam /dev/video9
```

Pick **HT-203U Thermal** as the camera in your app. Stop the stream with Ctrl+C.

If the camera is unplugged while streaming, the virtual camera keeps showing the
last frame until the script is restarted.

## How it works

The camera streams 256×384 YUYV frames. The frame has two halves:

- **Top 192 rows:** an 8-bit grayscale thermal image. The script does not use it.
- **Bottom 192 rows:** 192×256 little-endian `uint16` values, one per pixel,
  in units of 1/64 kelvin. The temperature in °C is `raw / 64 − 273.15`.

The first ~70 frames after the camera starts are a uniform value while the
sensor warms up. The script skips these.

A background thread always keeps only the newest frame, so the display never
falls behind the camera.

## Accuracy

Pixel values are converted with `raw / 64 − 273.15`, the usual convention for
cameras of this type. Readings in a room look plausible (roughly 22–35 °C), but
**the absolute scale has not been checked against a reference**. To check it,
point the camera at ice water (should read about 0 °C) or at skin (about 33–35 °C).
Treat the numbers as relative until you have done this.

## Platform support

| Platform | Status |
| --- | --- |
| Linux | Tested, including the virtual webcam |
| Windows | Capture path written (DirectShow via ffmpeg), not tested |
| macOS | Capture path written (AVFoundation via ffmpeg), not tested |

Reports from Windows and macOS users are welcome.

## Android

The Android viewer is not written yet. The camera is a standard USB video class
device, so a UVC library such as UVCCamera should be able to read it.

## Licence

This project is free software, licensed under the GNU General Public License v3.0
or later. See [LICENSE](LICENSE) for the full text.
