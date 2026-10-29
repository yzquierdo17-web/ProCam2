package com.example.procam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import com.example.procam.film.FilmLook
import com.example.procam.film.FilmProcessor
import kotlinx.coroutines.delay
import kotlin.math.hypot

/** Live vignette and animated grain drawn over the preview (the colour grade is applied to the preview itself). */
@Composable
fun FilmOverlay(look: FilmLook?, strength: Float, modifier: Modifier = Modifier) {
    if (look == null) return
    val tiles = remember { List(4) { FilmProcessor.noiseTile(128, it + 1).asImageBitmap() } }
    var frame by remember { mutableStateOf(0) }
    val hasGrain = look.grain > 0f
    LaunchedEffect(hasGrain) {
        while (hasGrain) {
            delay(110)
            frame = (frame + 1) % tiles.size
        }
    }
    val grainBrush = remember(frame) { ShaderBrush(ImageShader(tiles[frame], TileMode.Repeated, TileMode.Repeated)) }
    Canvas(modifier) {
        val vig = look.vignette * strength
        if (vig > 0f) {
            drawRect(
                Brush.radialGradient(
                    0f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1f to Color.Black.copy(alpha = (vig * 1.2f).coerceIn(0f, 0.85f)),
                    center = center,
                    radius = hypot(size.width, size.height) / 2f,
                )
            )
        }
        if (hasGrain) drawRect(grainBrush, alpha = (look.grain * strength * 1.6f).coerceIn(0f, 1f))
    }
}
