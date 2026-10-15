package io.github.dancommitsgit.friendar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dancommitsgit.friendar.R
import io.github.dancommitsgit.friendar.finder.FinderState
import io.github.dancommitsgit.friendar.fusion.RangeSource
import io.github.dancommitsgit.friendar.fusion.DirectionSource
import io.github.dancommitsgit.friendar.fusion.EstimateSource
import io.github.dancommitsgit.friendar.fusion.isClose
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val MapFeetSteps = listOf(30, 60, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000)
private val MapMeterSteps = listOf(10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000)

private fun scaleLabel(extent: Int, metric: Boolean): String = when {
    metric && extent >= 1000 -> String.format(Locale.US, "%.1f km", extent / 1000f)
    metric -> "$extent m"
    extent >= 5280 -> String.format(Locale.US, "%.1f mi", extent / 5280f)
    else -> "$extent ft"
}

@Composable
internal fun MapView(state: FinderState, modifier: Modifier = Modifier) {
    val friend = state.friend
    val northLabel = stringResource(R.string.map_north)
    val emptyLabel = stringResource(R.string.map_no_position)
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    val me = scheme.primary
    val onMe = scheme.onPrimary
    val success = MaterialTheme.success
    val warning = MaterialTheme.warning
    Canvas(modifier = modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        val half = min(size.width, size.height) / 2 - 16.dp.toPx()
        val needed = max(
            friend?.let { hypot(it.eastM, it.northM) + it.sigmaM } ?: 0f,
            state.rangeM ?: 0f,
        ) * 1.15f
        val extentUnits: Int
        val extentM: Float
        if (state.metric) {
            extentUnits = MapMeterSteps.firstOrNull { it >= needed } ?: MapMeterSteps.last()
            extentM = extentUnits.toFloat()
        } else {
            extentUnits = MapFeetSteps.firstOrNull { it / FEET_PER_METER >= needed } ?: MapFeetSteps.last()
            extentM = extentUnits / FEET_PER_METER
        }
        val scale = half / extentM
        val center = Offset(cx, cy)

        // With a heading the map turns so the direction you face is up; without one, north is up.
        val heading = state.headingDeg
        val turn = Math.toRadians((heading ?: 0f).toDouble())
        val cosTurn = cos(turn).toFloat()
        val sinTurn = sin(turn).toFloat()

        val text = android.graphics.Paint().apply {
            isAntiAlias = true
            color = muted.toArgb()
            textSize = 12.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val ring = muted.copy(alpha = 0.35f)
        drawCircle(ring, radius = half, center = center, style = Stroke(1.dp.toPx()))
        drawCircle(ring, radius = half / 2, center = center, style = Stroke(1.dp.toPx()))
        drawContext.canvas.nativeCanvas.apply {
            val northRadius = half + 10.dp.toPx()
            drawText(northLabel, cx - sinTurn * northRadius, cy + 4.dp.toPx() - cosTurn * northRadius, text)
            drawText(scaleLabel(extentUnits, state.metric), cx, cy + half + 14.dp.toPx(), text)
            drawText(scaleLabel(extentUnits / 2, state.metric), cx, cy + half / 2 + 14.dp.toPx(), text)
        }

        val range = state.rangeM
        val close = isClose(state.distanceM)
        val tone = when {
            close -> success
            state.rangeSource == RangeSource.Uwb -> me
            else -> warning
        }
        if (range != null) {
            drawCircle(
                tone.copy(alpha = 0.6f),
                radius = range * scale,
                center = center,
                style = Stroke(
                    2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
                ),
            )
        }
        if (friend != null) {
            val right = friend.eastM * cosTurn - friend.northM * sinTurn
            val ahead = friend.northM * cosTurn + friend.eastM * sinTurn
            val p = Offset(cx + right * scale, cy - ahead * scale)
            drawCircle(tone.copy(alpha = 0.15f), radius = friend.sigmaM * scale, center = p)
            drawLine(tone.copy(alpha = 0.6f), center, p, strokeWidth = 2.dp.toPx())
            drawCircle(tone, radius = 9.dp.toPx(), center = p)
        }
        if (heading != null) {
            val len = 24.dp.toPx()
            val wing = 9.dp.toPx()
            drawPath(
                Path().apply {
                    moveTo(cx, cy - len)
                    lineTo(cx - wing, cy)
                    lineTo(cx + wing, cy)
                    close()
                },
                me.copy(alpha = 0.5f),
            )
        }
        drawCircle(me, radius = 8.dp.toPx(), center = center)
        drawCircle(onMe, radius = 3.dp.toPx(), center = center)
        if (friend == null && range == null) {
            drawContext.canvas.nativeCanvas.drawText(emptyLabel, cx, cy + 40.dp.toPx(), text)
        }
    }
}

/** A caption under the map when its position is less trustworthy; null when nothing needs saying. */
@Composable
internal fun mapNote(state: FinderState): String? {
    val friend = state.friend
    return when {
        friend?.source == EstimateSource.RangeAndBearing ->
            if (state.arrowSource == DirectionSource.Gps) stringResource(R.string.map_note_gps_direction) else null

        friend?.source == EstimateSource.RangeAndGps -> stringResource(R.string.map_note_gps_direction)
        friend?.source == EstimateSource.GpsOnly -> stringResource(
            R.string.map_note_gps_only,
            if (state.metric) "30 m" else "100 ft",
        )

        state.rangeM != null -> stringResource(R.string.map_note_ring)
        else -> stringResource(R.string.map_note_waiting)
    }
}
