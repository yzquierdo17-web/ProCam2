package com.example.procam.film

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.random.Random

/** Applies a [FilmLook] to a full-resolution JPEG. Works in place, in bands, to keep memory use down. */
object FilmProcessor {

    /** Decodes [bytes], applies the look and returns a re-encoded JPEG, or null if it can't be decoded. */
    fun processJpeg(bytes: ByteArray, look: FilmLook, strength: Float): ByteArray? {
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        try {
            processInPlace(bmp, look, strength)
            val out = ByteArrayOutputStream(bytes.size + (bytes.size shr 2))
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
            return out.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    fun processInPlace(bmp: Bitmap, look: FilmLook, strength: Float) {
        val m = look.colorMatrix(strength).array
        val w = bmp.width
        val h = bmp.height
        val vig = look.vignette * strength
        val grainAmp = look.grain * strength * 38f
        val grainSize = maxOf(1, (w / 1400f).roundToInt())
        val rnd = Random(7)
        val noise = FloatArray(256 * 256) { rnd.nextFloat() * 2f - 1f }
        val cx = w / 2f
        val cy = h / 2f
        val maxR2 = cx * cx + cy * cy
        val band = 64
        val row = IntArray(w * band)

        var y = 0
        while (y < h) {
            val n = minOf(band, h - y)
            bmp.getPixels(row, 0, w, 0, y, w, n)
            for (j in 0 until n) {
                val yy = y + j
                val dy = yy - cy
                val ny = ((yy / grainSize) and 255) shl 8
                for (x in 0 until w) {
                    val i = j * w + x
                    val p = row[i]
                    val r = ((p shr 16) and 255).toFloat()
                    val g = ((p shr 8) and 255).toFloat()
                    val b = (p and 255).toFloat()
                    var nr = m[0] * r + m[1] * g + m[2] * b + m[4]
                    var ng = m[5] * r + m[6] * g + m[7] * b + m[9]
                    var nb = m[10] * r + m[11] * g + m[12] * b + m[14]
                    if (vig > 0f) {
                        val dx = x - cx
                        val t = ((dx * dx + dy * dy) / maxR2 - 0.3f) / 0.7f
                        if (t > 0f) {
                            val f = (1f - vig * 1.2f * minOf(t, 1f)).coerceAtLeast(0f)
                            nr *= f; ng *= f; nb *= f
                        }
                    }
                    if (grainAmp > 0f) {
                        val gn = noise[ny or ((x / grainSize) and 255)] * grainAmp
                        nr += gn; ng += gn; nb += gn
                    }
                    row[i] = (0xFF shl 24) or (clamp(nr) shl 16) or (clamp(ng) shl 8) or clamp(nb)
                }
            }
            bmp.setPixels(row, 0, w, 0, y, w, n)
            y += n
        }
    }

    private fun clamp(v: Float): Int = if (v < 0f) 0 else if (v > 255f) 255 else v.toInt()

    /** Small tile of semi-transparent grey noise, used for the live grain overlay. */
    fun noiseTile(size: Int, seed: Int): Bitmap {
        val rnd = Random(seed)
        val px = IntArray(size * size) {
            val g = rnd.nextInt(256)
            Color.argb(90, g, g, g)
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}
