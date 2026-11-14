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

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Port of the Python viewer's frame handling.
 *
 * A 256x384 YUYV frame (196608 bytes) holds two halves:
 *  - top 192 rows: 8-bit grayscale image (not used here)
 *  - bottom 192 rows: 192x256 little-endian uint16 per pixel, temperature in 1/64 K,
 *    so T[C] = raw / 64 - 273.15
 *
 * [process] runs on the USB frame thread. It writes into one of two buffers and publishes it,
 * so the UI thread can copy the newest finished image without waiting.
 */
class ThermalProcessor {

    class Readout(val min: Float, val max: Float, val spot: Float)

    companion object {
        const val W = 256
        const val IMG_H = 192
        const val FRAME_BYTES = W * IMG_H * 2 * 2   // 256x384 YUYV
        const val TEMP_OFFSET = FRAME_BYTES / 2     // temperature block starts at byte 98304

        private val GREEN = 0xFF00FF00.toInt()
        private val CYAN = 0xFF00FFFF.toInt()
        private val WHITE = 0xFFFFFFFF.toInt()
    }

    private val temps = FloatArray(W * IMG_H)
    private val buffers = arrayOf(IntArray(W * IMG_H), IntArray(W * IMG_H))
    private var writeIndex = 0

    @Volatile
    var latest: IntArray? = null
        private set

    @Volatile
    var readout: Readout? = null
        private set

    /** Incremented each time a new image is published. */
    @Volatile
    var sequence: Long = 0L
        private set

    /**
     * Decode one raw frame. Returns false for frames that are still warming up
     * (the temperature block is one constant value) or too short to be valid.
     */
    fun process(frame: ByteBuffer): Boolean {
        if (frame.capacity() < FRAME_BYTES) return false
        val bb = frame.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val n = W * IMG_H

        val firstRaw = bb.getShort(TEMP_OFFSET).toInt() and 0xFFFF
        var allSame = true
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        var loIdx = 0
        var hiIdx = 0
        for (i in 0 until n) {
            val raw = bb.getShort(TEMP_OFFSET + i * 2).toInt() and 0xFFFF
            if (raw != firstRaw) allSame = false
            val t = raw / 64f - 273.15f
            temps[i] = t
            if (t < lo) { lo = t; loIdx = i }
            if (t > hi) { hi = t; hiIdx = i }
        }
        if (allSame) return false

        val out = buffers[writeIndex]
        val span = maxOf(hi - lo, 1e-6f)
        val lut = Colormap.INFERNO
        for (i in 0 until n) {
            val idx = (((temps[i] - lo) / span) * 255f).toInt().coerceIn(0, 255)
            out[i] = lut[idx]
        }

        // markers: max (cyan), min (white), then the centre spot (green) on top
        drawCross(out, hiIdx, 4, CYAN)
        drawCross(out, loIdx, 4, WHITE)
        val spotIdx = (IMG_H / 2) * W + W / 2
        drawCross(out, spotIdx, 3, GREEN)

        readout = Readout(lo, hi, temps[spotIdx])
        latest = out
        writeIndex = 1 - writeIndex
        sequence++
        return true
    }

    private fun drawCross(px: IntArray, idx: Int, arm: Int, color: Int) {
        val cx = idx % W
        val cy = idx / W
        for (d in -arm..arm) {
            val x = cx + d
            if (x in 0 until W) px[cy * W + x] = color
            val y = cy + d
            if (y in 0 until IMG_H) px[y * W + cx] = color
        }
    }
}
