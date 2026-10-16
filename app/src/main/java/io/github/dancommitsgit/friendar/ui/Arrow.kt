package io.github.dancommitsgit.friendar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

@Composable
internal fun Arrow(angleDeg: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.rotate(angleDeg)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w / 2, h * 0.04f)
            lineTo(w * 0.86f, h * 0.9f)
            lineTo(w / 2, h * 0.7f)
            lineTo(w * 0.14f, h * 0.9f)
            close()
        }
        drawPath(path, color)
    }
}
