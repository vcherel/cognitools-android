package com.example.myapp.workout

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.LocalToolAccent
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

internal val Gold = Color(0xFFFFB300)

/** The pop shown over the screen when a set is logged; [id] restarts it. */
internal data class Burst(val id: Long, val label: String, val gold: Boolean)

/** Sparks flying out from the buttons and the label rising over them, once. */
@Composable
internal fun BurstOverlay(burst: Burst) {
    val time = remember { Animatable(0f) }
    LaunchedEffect(Unit) { time.animateTo(1f, tween(if (burst.gold) 1400 else 900, easing = LinearEasing)) }
    if (time.value >= 1f) return
    val accent = LocalToolAccent.current!!.color
    val colors = if (burst.gold) listOf(Gold, Color(0xFFFFD54F), Color(0xFFFF8F00)) else listOf(accent, Gold, accent)
    val sparks = remember {
        val random = Random(burst.id)
        List(if (burst.gold) 36 else 20) {
            Triple(random.nextFloat() * 2 * Math.PI.toFloat(), 0.5f + random.nextFloat() * 0.7f, colors[it % colors.size])
        }
    }
    val t = time.value
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val reach = maxWidth.value * 0.45f
        Canvas(Modifier.fillMaxSize()) {
            val origin = Offset(size.width / 2, size.height - 80.dp.toPx())
            val spread = reach * density
            sparks.forEach { (angle, speed, color) ->
                val distance = spread * speed * FastOutSlowInEasing.transform(t)
                val fall = t * t * 120.dp.toPx()
                val center = Offset(origin.x + cos(angle) * distance, origin.y + sin(angle) * distance * 0.7f + fall)
                drawCircle(color.copy(alpha = 1f - t), radius = (1f - t * 0.5f) * 5.dp.toPx(), center = center)
            }
        }
        val rise = FastOutSlowInEasing.transform((t * 1.6f).coerceAtMost(1f))
        Text(
            burst.label,
            fontSize = if (burst.gold) 40.sp else 32.sp,
            fontWeight = FontWeight.Black,
            color = if (burst.gold) Gold else accent,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 130.dp)
                .offset(y = (-90 * rise).dp)
                .scale(0.6f + 0.6f * rise)
                .alpha(if (t < 0.6f) 1f else (1f - t) / 0.4f)
        )
    }
}

private class Piece(val x: Float, val delay: Float, val speed: Float, val spin: Float, val color: Color, val w: Float, val h: Float)

private val CONFETTI_COLORS = listOf(
    Color(0xFFFFB300), Color(0xFF2E9E6A), Color(0xFFC94444), Color(0xFF3F74D6), Color(0xFFE58A2B)
)

/** A burst of falling confetti, once, when the victory screen opens. */
@Composable
internal fun Confetti() {
    val pieces = remember {
        val random = Random(System.nanoTime())
        List(60) {
            Piece(
                x = random.nextFloat(),
                delay = random.nextFloat() * 0.3f,
                speed = 0.6f + random.nextFloat() * 0.6f,
                spin = random.nextFloat() * 720f - 360f,
                color = CONFETTI_COLORS[random.nextInt(CONFETTI_COLORS.size)],
                w = 6f + random.nextFloat() * 6f,
                h = 4f + random.nextFloat() * 8f
            )
        }
    }
    val time = remember { Animatable(0f) }
    LaunchedEffect(Unit) { time.animateTo(1f, tween(3200, easing = LinearEasing)) }
    if (time.value >= 1f) return
    Canvas(Modifier.fillMaxSize()) {
        pieces.forEach { p ->
            val t = ((time.value - p.delay) / (1f - p.delay)).coerceIn(0f, 1f)
            if (t <= 0f || t >= 1f) return@forEach
            val y = -20f + t * p.speed * size.height * 1.3f
            val x = p.x * size.width + sin(t * 12f + p.spin) * 18f
            val w = p.w * density
            val h = p.h * density
            rotate(p.spin * t, Offset(x, y)) {
                drawRect(p.color.copy(alpha = 1f - t * 0.6f), Offset(x - w / 2, y - h / 2), Size(w, h))
            }
        }
    }
}
