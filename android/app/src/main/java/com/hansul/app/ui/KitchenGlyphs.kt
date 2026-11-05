package com.hansul.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

internal enum class KitchenGlyph { BACK, FORWARD, SETTINGS, MIC, CHAT, LIST, CHECK, LINK, CLOSE }

/** Small code-native outline icons; accessibility labels belong to the surrounding controls. */
@Composable
internal fun KitchenGlyphIcon(
    glyph: KitchenGlyph,
    modifier: Modifier = Modifier.size(22.dp),
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        val stroke = 1.8f * unit
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, point(x1, y1), point(x2, y2), stroke, StrokeCap.Round)
        when (glyph) {
            KitchenGlyph.BACK -> { line(14f, 5f, 7f, 12f); line(7f, 12f, 14f, 19f) }
            KitchenGlyph.FORWARD -> { line(9f, 5f, 16f, 12f); line(16f, 12f, 9f, 19f) }
            KitchenGlyph.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            KitchenGlyph.CHECK -> { line(4f, 12f, 9f, 17f); line(9f, 17f, 20f, 6f) }
            KitchenGlyph.LIST -> (6..18 step 6).forEach { y ->
                drawCircle(tint, unit, point(4f, y.toFloat()))
                line(8f, y.toFloat(), 21f, y.toFloat())
            }
            KitchenGlyph.SETTINGS -> {
                drawCircle(tint, 6f * unit, point(12f, 12f), style = Stroke(stroke))
                drawCircle(tint, 2.3f * unit, point(12f, 12f), style = Stroke(stroke))
                repeat(8) { index ->
                    val angle = index * Math.PI / 4
                    line(12f + 7f * cos(angle).toFloat(), 12f + 7f * sin(angle).toFloat(),
                        12f + 9f * cos(angle).toFloat(), 12f + 9f * sin(angle).toFloat())
                }
            }
            KitchenGlyph.MIC -> {
                drawRoundRect(tint, point(9f, 3f), Size(6f * unit, 11f * unit),
                    androidx.compose.ui.geometry.CornerRadius(3f * unit), style = Stroke(stroke))
                drawArc(tint, 0f, 180f, false, point(5f, 7f), Size(14f * unit, 11f * unit), style = Stroke(stroke))
                line(12f, 18f, 12f, 22f); line(8f, 22f, 16f, 22f)
            }
            KitchenGlyph.CHAT -> {
                val bubble = Path().apply {
                    moveTo(6f * unit, 4f * unit); lineTo(18f * unit, 4f * unit)
                    quadraticTo(21f * unit, 4f * unit, 21f * unit, 7f * unit)
                    lineTo(21f * unit, 15f * unit); quadraticTo(21f * unit, 18f * unit, 18f * unit, 18f * unit)
                    lineTo(10f * unit, 18f * unit); lineTo(5f * unit, 22f * unit); lineTo(5f * unit, 18f * unit)
                    quadraticTo(3f * unit, 18f * unit, 3f * unit, 15f * unit)
                    lineTo(3f * unit, 7f * unit); quadraticTo(3f * unit, 4f * unit, 6f * unit, 4f * unit)
                    close()
                }
                drawPath(bubble, tint, style = Stroke(stroke))
                line(7f, 9f, 17f, 9f); line(7f, 13f, 14f, 13f)
            }
            KitchenGlyph.LINK -> {
                drawArc(tint, 140f, 260f, false, point(2f, 8f), Size(13f * unit, 9f * unit), style = Stroke(stroke))
                drawArc(tint, -40f, 260f, false, point(9f, 5f), Size(13f * unit, 9f * unit), style = Stroke(stroke))
                line(8f, 12f, 16f, 10f)
            }
        }
    }
}
