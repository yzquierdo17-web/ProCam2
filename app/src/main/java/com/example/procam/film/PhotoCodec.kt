package com.example.procam.film

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.example.procam.camera.PhotoFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Converts a captured JPEG or HEIC into another format, optionally applying a film look on the way.
 *
 * PNG and WebP carry no reliable rotation tag, so for those the pixels are rotated upright.
 * JPEG keeps the sensor orientation and the caller copies the EXIF tags across.
 * HEIC cannot be produced here (Android has no HEIC encoder for bitmaps), so it is never a target.
 */
object PhotoCodec {

    fun transcode(bytes: ByteArray, look: FilmLook?, strength: Float, target: PhotoFormat): ByteArray? {
        if (target == PhotoFormat.HEIC) return null
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        try {
            if (look != null) FilmProcessor.processInPlace(bmp, look, strength)

            if (target == PhotoFormat.PNG || target == PhotoFormat.WEBP) {
                val rot = rotationDegrees(bytes)
                if (rot != 0) {
                    val m = Matrix().apply { postRotate(rot.toFloat()) }
                    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    if (rotated !== bmp) {
                        bmp.recycle()
                        bmp = rotated
                    }
                }
            }

            val out = ByteArrayOutputStream(bytes.size + (bytes.size shr 1))
            val ok = when (target) {
                PhotoFormat.JPEG -> bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                PhotoFormat.PNG -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                PhotoFormat.WEBP -> bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 95, out)
                PhotoFormat.HEIC -> false
            }
            return if (ok) out.toByteArray() else null
        } finally {
            bmp.recycle()
        }
    }

    private fun rotationDegrees(bytes: ByteArray): Int =
        runCatching { ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees }.getOrDefault(0)
}
