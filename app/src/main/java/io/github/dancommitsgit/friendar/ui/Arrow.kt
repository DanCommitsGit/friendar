package io.github.dancommitsgit.friendar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.sqrt

/** How far each corner is cut back, as a fraction of the arrow's width; the corner curves through the cut. */
private const val ROUNDING = 0.085f

@Composable
internal fun Arrow(angleDeg: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.rotate(angleDeg)) {
        val w = size.width
        val h = size.height
        val corners = listOf(
            Offset(w / 2, h * 0.04f),
            Offset(w * 0.86f, h * 0.9f),
            Offset(w / 2, h * 0.7f),
            Offset(w * 0.14f, h * 0.9f),
        )
        val cut = w * ROUNDING
        val path = Path()
        corners.forEachIndexed { i, corner ->
            val before = towards(corner, corners[(i + corners.size - 1) % corners.size], cut)
            val after = towards(corner, corners[(i + 1) % corners.size], cut)
            if (i == 0) path.moveTo(before.x, before.y) else path.lineTo(before.x, before.y)
            path.quadraticTo(corner.x, corner.y, after.x, after.y)
        }
        path.close()
        drawPath(path, color)
    }
}

private fun towards(from: Offset, to: Offset, distance: Float): Offset {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = sqrt(dx * dx + dy * dy)
    return Offset(from.x + dx / length * distance, from.y + dy / length * distance)
}
