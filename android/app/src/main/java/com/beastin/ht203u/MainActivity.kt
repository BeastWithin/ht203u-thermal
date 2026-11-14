/*
 * Copyright (C) 2026 Beastin
 *
 * This file is part of ht203u-thermal.
 *
 * ht203u-thermal is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. It is distributed WITHOUT ANY WARRANTY; see the LICENSE file.
 */
package com.beastin.ht203u

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.TextView
import com.jiangdg.usb.USBMonitor
import com.jiangdg.uvc.IFrameCallback
import com.jiangdg.uvc.UVCCamera

class MainActivity : Activity() {

    companion object {
        private const val VENDOR_ID = 0x0bda      // Realtek
        private const val PRODUCT_ID = 0x5830     // HT-203U
        private const val REFRESH_MS = 33L        // about 30 fps on screen
        private const val CAMERA_REQUEST = 42
    }

    private lateinit var monitor: USBMonitor
    private lateinit var thermalView: ImageView
    private lateinit var statusView: TextView
    private val processor = ThermalProcessor()
    private val bitmap = Bitmap.createBitmap(ThermalProcessor.W, ThermalProcessor.IMG_H, Bitmap.Config.ARGB_8888)
    private val handler = Handler(Looper.getMainLooper())
    private var camera: UVCCamera? = null
    // off-screen surface: the library's native frame thread only starts with a preview surface
    private var previewSurface: SurfaceTexture? = null
    private var shownSequence = -1L

    private val refresh = object : Runnable {
        override fun run() {
            val seq = processor.sequence
            if (seq != shownSequence) {
                shownSequence = seq
                processor.latest?.let { px ->
                    bitmap.setPixels(px, 0, ThermalProcessor.W, 0, 0, ThermalProcessor.W, ThermalProcessor.IMG_H)
                    thermalView.invalidate()
                }
                processor.readout?.let { r ->
                    statusView.text = String.format(
                        "min %.1f   max %.1f   spot %.1f °C", r.min, r.max, r.spot
                    )
                }
            }
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val usbListener = object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice?) {
            if (device != null && isThermal(device)) handler.post { requestUsb(device) }
        }

        override fun onConnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?, createNew: Boolean) {
            if (device != null && ctrlBlock != null && isThermal(device)) openCamera(ctrlBlock)
        }

        override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            closeCamera()
            status("Camera disconnected")
        }

        override fun onDetach(device: UsbDevice?) {
            if (device != null && isThermal(device)) status("Camera unplugged")
        }

        override fun onCancel(device: UsbDevice?) {
            status("USB permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        thermalView = findViewById(R.id.thermalView)
        statusView = findViewById(R.id.statusView)
        thermalView.setImageBitmap(bitmap)
        monitor = USBMonitor(this, usbListener)
    }

    override fun onStart() {
        super.onStart()
        monitor.register()
        if (hasCameraPermission()) {
            requestUsbForThermalCameras()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestUsbForThermalCameras()
            } else {
                status("Camera permission is needed for USB video")
            }
        }
    }

    private fun hasCameraPermission() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestUsbForThermalCameras() {
        monitor.deviceList.filter { isThermal(it) }.forEach { requestUsb(it) }
    }

    private fun requestUsb(device: UsbDevice) {
        monitor.requestPermission(device)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onStop() {
        closeCamera()
        monitor.unregister()
        super.onStop()
    }

    override fun onDestroy() {
        monitor.destroy()
        super.onDestroy()
    }

    private fun isThermal(device: UsbDevice) =
        device.vendorId == VENDOR_ID && device.productId == PRODUCT_ID

    private fun openCamera(ctrlBlock: USBMonitor.UsbControlBlock) {
        closeCamera()
        try {
            val cam = UVCCamera()
            cam.open(ctrlBlock)
            // 256x384 YUYV, 1 to 25 fps, same as the Python viewer
            cam.setPreviewSize(
                ThermalProcessor.W, ThermalProcessor.IMG_H * 2,
                1, 25, UVCCamera.FRAME_FORMAT_YUYV, UVCCamera.DEFAULT_BANDWIDTH
            )
            // raw frames, no preview surface needed
            cam.setFrameCallback(IFrameCallback { frame -> processor.process(frame) }, UVCCamera.PIXEL_FORMAT_RAW)
            val surface = SurfaceTexture(0)
            previewSurface = surface
            cam.setPreviewTexture(surface)
            cam.startPreview()
            camera = cam
            status("Streaming")
        } catch (e: Exception) {
            status("Could not open camera: ${e.message}")
        }
    }

    private fun closeCamera() {
        camera?.let {
            try {
                it.stopPreview()
                it.close()
                it.destroy()
            } catch (_: Exception) {
            }
        }
        camera = null
        previewSurface?.release()
        previewSurface = null
    }

    /** USB callbacks run on a background thread, so hop to the UI thread before touching views. */
    private fun status(text: String) {
        handler.post { statusView.text = text }
    }
}
