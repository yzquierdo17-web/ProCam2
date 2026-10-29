package com.example.procam.ui

import android.graphics.Bitmap
import kotlin.math.abs

/** Pixel analysis on a small preview frame: zebra stripes on clipped highlights, focus-peaking on edges. */
object Analysis {
    private const val ZEBRA_LUMA = 240          // ~94% brightness
    private const val PEAK_EDGE = 45            // gradient strength that counts as "in focus"
    private const val ZEBRA_COLOR = 0xCCE53935.toInt()
    private const val PEAK_COLOR = 0xFF00E676.toInt()

    fun overlay(src: Bitmap, peaking: Boolean, zebra: Boolean): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val luma = IntArray(w * h) {
            val p = px[it]
            (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        val out = IntArray(w * h)

        if (peaking) {
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    if (luma[i] < 25) continue
                    val gx = luma[i + 1] - luma[i - 1]
                    val gy = luma[i + w] - luma[i - w]
                    if (abs(gx) + abs(gy) > PEAK_EDGE) out[i] = PEAK_COLOR
                }
            }
        }
        if (zebra) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (luma[i] >= ZEBRA_LUMA && ((x + y) / 4) % 2 == 0) out[i] = ZEBRA_COLOR
                }
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
