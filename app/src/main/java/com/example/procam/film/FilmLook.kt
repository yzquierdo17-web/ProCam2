package com.example.procam.film

import android.graphics.ColorMatrix

/**
 * A film look: a colour grade (saturation, contrast, tint, faded blacks) plus optional vignette and grain.
 * These are original looks inspired by classic film characters, not copies of any specific stock.
 */
data class FilmLook(
    val id: String,
    val label: String,
    val blurb: String,
    val sat: Float = 1f,
    val contrast: Float = 1f,
    val brightness: Float = 0f,     // -1..1
    val r: Float = 1f,              // per-channel gain (tint)
    val g: Float = 1f,
    val b: Float = 1f,
    val lift: Float = 0f,           // raises the blacks (matte / faded look), 0..0.2
    val vignette: Float = 0f,       // 0..1
    val grain: Float = 0f,          // 0..1
    val mono: Boolean = false,
) {
    /** 4x5 colour matrix for this look, blended with "no change" by [strength] (0..1). */
    fun colorMatrix(strength: Float): ColorMatrix {
        val m = ColorMatrix()
        m.setSaturation(if (mono) 0f else sat)
        m.postConcat(ColorMatrix(floatArrayOf(
            r, 0f, 0f, 0f, 0f,
            0f, g, 0f, 0f, 0f,
            0f, 0f, b, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )))
        val t = 128f * (1f - contrast) + brightness * 255f
        m.postConcat(ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, t,
            0f, contrast, 0f, 0f, t,
            0f, 0f, contrast, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )))
        if (lift > 0f) {
            val o = lift * 255f
            m.postConcat(ColorMatrix(floatArrayOf(
                1f - lift, 0f, 0f, 0f, o,
                0f, 1f - lift, 0f, 0f, o,
                0f, 0f, 1f - lift, 0f, o,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        val s = strength.coerceIn(0f, 1f)
        val a = m.array
        return ColorMatrix(FloatArray(20) { identity[it] + (a[it] - identity[it]) * s })
    }
}

object Films {
    val all: List<FilmLook> = listOf(
        FilmLook(
            "warm", "Warm Portrait", "Soft, warm skin tones with gentle contrast and a touch of grain.",
            sat = 0.92f, contrast = 0.95f, brightness = 0.02f, r = 1.05f, b = 0.93f,
            lift = 0.03f, vignette = 0.15f, grain = 0.15f,
        ),
        FilmLook(
            "slide", "Vivid Slide", "Saturated colour and punchy contrast, like colour slide film.",
            sat = 1.35f, contrast = 1.2f, vignette = 0.2f, grain = 0.05f,
        ),
        FilmLook(
            "cine", "Cine Teal", "Cool shadows, slightly muted colour, a modern cinema grade.",
            sat = 0.9f, contrast = 1.15f, r = 0.95f, g = 1.02f, b = 1.08f,
            lift = 0.04f, vignette = 0.3f, grain = 0.12f,
        ),
        FilmLook(
            "golden", "Golden Hour", "Warm amber glow with rich colour.",
            sat = 1.1f, contrast = 1.05f, r = 1.12f, g = 1.02f, b = 0.85f,
            vignette = 0.2f, grain = 0.1f,
        ),
        FilmLook(
            "matte", "Faded Matte", "Lifted blacks and softened colour for a faded print look.",
            sat = 0.8f, contrast = 0.88f, brightness = 0.02f, r = 1.02f, b = 0.98f,
            lift = 0.1f, vignette = 0.1f, grain = 0.2f,
        ),
        FilmLook(
            "retro70", "Vintage 70s", "Warm, yellow-leaning colour with faded blacks and heavy grain.",
            sat = 0.85f, contrast = 1.05f, r = 1.1f, b = 0.82f,
            lift = 0.06f, vignette = 0.35f, grain = 0.3f,
        ),
        FilmLook(
            "cross", "Cross Process", "High contrast with a colour shift toward green and yellow.",
            sat = 1.25f, contrast = 1.3f, r = 1.1f, g = 1.05f, b = 0.85f,
            vignette = 0.2f, grain = 0.15f,
        ),
        FilmLook(
            "noir", "Noir", "Deep black and white with strong contrast and heavy grain.",
            contrast = 1.35f, brightness = -0.02f, lift = 0.02f, vignette = 0.35f, grain = 0.35f, mono = true,
        ),
        FilmLook(
            "silver", "Silver 400", "Soft black and white with classic grain.",
            contrast = 1.1f, lift = 0.04f, vignette = 0.1f, grain = 0.45f, mono = true,
        ),
    )

    fun byId(id: String?): FilmLook? = id?.let { i -> all.firstOrNull { it.id == i } }
}
