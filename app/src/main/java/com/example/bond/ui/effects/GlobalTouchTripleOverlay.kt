// GlobalTouchRippleOverlay.kt
package com.example.bond.ui.effects

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max

private data class Ripple(
    val id: Long,
    val center: Offset,
    val progress: Animatable<Float, AnimationVector1D>, // 0f -> 1f
    val hueShift: Float, // for subtle color variety
)

@Composable
fun GlobalTouchRippleOverlay(
    modifier: Modifier = Modifier,
    durationMillis: Int = 650,
    maxRipples: Int = 10,
    baseColors: List<Color> = listOf(
        Color(0xFF8B5CF6), // purple
        Color(0xFFEC4899), // pink
        Color(0xFF3B82F6)  // blue
    ),
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    var widthPx by remember { mutableStateOf(0) }
    var heightPx by remember { mutableStateOf(0) }

    val ripples = remember { mutableStateListOf<Ripple>() }
    var nextId by remember { mutableStateOf(1L) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .onSizeChanged { size ->
                widthPx = size.width
                heightPx = size.height
            }
            // Observe all pointer downs on the whole app, without consuming them
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        event.changes.forEach { change ->
                            if (change.changedToDown()) {
                                val pos = change.position
                                val anim = Animatable(0f)
                                val id = nextId++

                                // Keep ripples bounded
                                if (ripples.size >= maxRipples && ripples.isNotEmpty()) {
                                    ripples.removeAt(0)
                                }

                                val hueShift = (id % 100) / 100f
                                val ripple = Ripple(id, pos, anim, hueShift)
                                ripples.add(ripple)

                                scope.launch {
                                    anim.animateTo(
                                        targetValue = 1f,
                                        animationSpec = tween(durationMillis, easing = FastOutSlowInEasing)
                                    )
                                    ripples.removeAll { it.id == id }
                                }
                            }
                        }
                    }
                }
            }
            .fillMaxSize()
    ) {
        // Your app content underneath
        content()

        // Draw overlay on top only when needed
        if (ripples.isNotEmpty() && widthPx > 0 && heightPx > 0) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = widthPx.toFloat()
                val h = heightPx.toFloat()

                ripples.forEach { r ->
                    val p = r.progress.value
                    // Max radius to reach farthest corner
                    val farthest = max(
                        hypot(r.center.x, r.center.y),
                        max(
                            hypot(w - r.center.x, r.center.y),
                            max(
                                hypot(r.center.x, h - r.center.y),
                                hypot(w - r.center.x, h - r.center.y)
                            )
                        )
                    )
                    val radius = 0.1f * farthest + 0.9f * farthest * p
                    val alpha = (1f - p).coerceAtLeast(0f)

                    // Slight hue offset for variety; rotate the base colors list
                    val colors = rotated(baseColors, ((r.hueShift * baseColors.size).toInt()) % baseColors.size)

                    // Soft fill
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                colors[0].copy(alpha = 0.15f * alpha),
                                colors[1].copy(alpha = 0.12f * alpha),
                                colors[2].copy(alpha = 0.08f * alpha),
                                Color.Transparent
                            ),
                            center = r.center,
                            radius = radius
                        ),
                        center = r.center,
                        radius = radius,
                        blendMode = BlendMode.SrcOver
                    )

                    // Bright shockwave ring
                    val ringAlpha = (0.35f * (1f - p)).coerceAtLeast(0f)
                    val ringWidth = with(density) { (2f + 6f * (1f - p)).dp.toPx() }
                    drawCircle(
                        color = colors[1].copy(alpha = ringAlpha),
                        center = r.center,
                        radius = radius * 0.95f,
                        style = Stroke(width = ringWidth),
                        blendMode = BlendMode.SrcOver
                    )
                }
            }
        }
    }
}

private fun rotated(list: List<Color>, offset: Int): List<Color> {
    if (list.isEmpty()) return list
    val n = ((offset % list.size) + list.size) % list.size
    return list.drop(n) + list.take(n)
}
