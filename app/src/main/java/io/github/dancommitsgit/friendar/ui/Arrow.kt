package io.github.dancommitsgit.friendar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.sqrt

/** How far each corner is cut back, as a fraction of the arrow's width; the corner curves through the cut. */
private const val ROUNDING = 0.085f

/**
 * The arrow's outline in a [w] by [h] box: for each of the four corners (tip, right wing, notch,
 * left wing) the point where the corner's curve starts, the sharp corner it curves around, and the
 * point where it ends, as x and y pairs.
 */
internal fun arrowPoints(w: Float, h: Float): FloatArray {
    val corners = floatArrayOf(w / 2, h * 0.04f, w * 0.86f, h * 0.9f, w / 2, h * 0.7f, w * 0.14f, h * 0.9f)
    val cut = w * ROUNDING
    val points = FloatArray(24)
    for (i in 0 until 4) {
        val prev = (i + 3) % 4
        val next = (i + 1) % 4
        val x = corners[i * 2]
        val y = corners[i * 2 + 1]
        towards(x, y, corners[prev * 2], corners[prev * 2 + 1], cut, points, i * 6)
        points[i * 6 + 2] = x
        points[i * 6 + 3] = y
        towards(x, y, corners[next * 2], corners[next * 2 + 1], cut, points, i * 6 + 4)
    }
    return points
}

internal fun arrowPath(points: FloatArray, path: android.graphics.Path = android.graphics.Path()): android.graphics.Path {
    path.rewind()
    for (i in 0 until 4) {
        val o = i * 6
        if (i == 0) path.moveTo(points[o], points[o + 1]) else path.lineTo(points[o], points[o + 1])
        path.quadTo(points[o + 2], points[o + 3], points[o + 4], points[o + 5])
    }
    path.close()
    return path
}

@Composable
internal fun Arrow(angleDeg: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.rotate(angleDeg)) {
        val points = arrowPoints(size.width, size.height)
        val path = Path()
        for (i in 0 until 4) {
            val o = i * 6
            if (i == 0) path.moveTo(points[o], points[o + 1]) else path.lineTo(points[o], points[o + 1])
            path.quadraticTo(points[o + 2], points[o + 3], points[o + 4], points[o + 5])
        }
        path.close()
        drawPath(path, color)
    }
}

private fun towards(fromX: Float, fromY: Float, toX: Float, toY: Float, distance: Float, out: FloatArray, at: Int) {
    val dx = toX - fromX
    val dy = toY - fromY
    val length = sqrt(dx * dx + dy * dy)
    out[at] = fromX + dx / length * distance
    out[at + 1] = fromY + dy / length * distance
}
