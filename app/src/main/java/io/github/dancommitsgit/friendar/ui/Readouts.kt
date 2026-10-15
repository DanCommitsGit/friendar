package io.github.dancommitsgit.friendar.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.dancommitsgit.friendar.R
import io.github.dancommitsgit.friendar.finder.FinderState
import io.github.dancommitsgit.friendar.finder.Phase
import io.github.dancommitsgit.friendar.fusion.RangeSource
import io.github.dancommitsgit.friendar.fusion.DirectionSource
import io.github.dancommitsgit.friendar.fusion.EstimateSource
import io.github.dancommitsgit.friendar.fusion.Trend
import io.github.dancommitsgit.friendar.fusion.isClose
import io.github.dancommitsgit.friendar.fusion.roughDistanceText
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

internal const val FEET_PER_METER = 3.28084f

/** Where the readings come from, for the debug view. */
internal fun debugLine(state: FinderState): String {
    val range = when (state.rangeSource) {
        RangeSource.Uwb -> "UWB"
        RangeSource.Wifi -> "Wi-Fi"
        RangeSource.Bluetooth -> "Bluetooth"
        null -> "-"
    }
    val direction = when (state.arrowSource) {
        DirectionSource.Uwb -> "UWB"
        DirectionSource.Gps -> "GPS"
        DirectionSource.Both -> "UWB+GPS"
        null -> "-"
    }
    val position = when (state.friend?.source) {
        EstimateSource.RangeAndBearing -> "range+bearing"
        EstimateSource.RangeAndGps -> "range+GPS"
        EstimateSource.GpsOnly -> "GPS only"
        null -> "-"
    }
    return "range: $range, direction: $direction, position: $position"
}

@Composable
internal fun statusLine(state: FinderState): String {
    val id = when (state.phase) {
        Phase.Waiting -> R.string.status_looking
        Phase.Ranging -> when {
            isClose(state.distanceM) -> R.string.status_here
            state.distanceM == null -> when (state.trend) {
                Trend.Closer -> R.string.status_closer
                Trend.Farther -> R.string.status_farther
                Trend.Steady -> R.string.status_walk
                null -> R.string.status_measuring
            }

            state.arrowDeg == null -> R.string.status_sweep
            state.arrowSource == DirectionSource.Gps -> R.string.status_gps
            !state.directionLive && state.flat -> R.string.status_tilt
            !state.directionLive -> R.string.status_aim
            else -> R.string.status_follow
        }

        Phase.Failed -> return state.message ?: stringResource(R.string.status_failed)
        Phase.Idle -> return ""
    }
    return stringResource(id)
}

private fun formatDistance(meters: Float, metric: Boolean): String {
    val value = if (metric) meters else meters * FEET_PER_METER
    val unit = if (metric) "m" else "ft"
    return if (value < 10f) {
        String.format(Locale.US, "%.1f %s", value, unit)
    } else {
        "${value.roundToInt()} $unit"
    }
}

internal fun distanceText(state: FinderState): String {
    val meters = state.distanceM
    if (meters != null) return formatDistance(meters, state.metric)
    val range = state.rangeM ?: return "—"
    return when (state.rangeSource) {
        RangeSource.Wifi -> if (state.metric) {
            "~${max(2, range.roundToInt())} m"
        } else {
            "~${max(5, (range * FEET_PER_METER / 5f).roundToInt() * 5)} ft"
        }

        RangeSource.Bluetooth -> roughDistanceText(range, state.metric)
        else -> "—"
    }
}

/** The same distance in the other unit; only precise readings are shown both ways. */
internal fun swapDistanceText(state: FinderState): String? =
    state.distanceM?.let { formatDistance(it, !state.metric) }

/** For readings that are not precise, a line that says how rough they are. */
@Composable
internal fun sourceLine(state: FinderState): String? {
    if (state.phase != Phase.Ranging || state.distanceM != null) return null
    return when (state.rangeSource) {
        RangeSource.Wifi -> stringResource(R.string.via_wifi)
        RangeSource.Bluetooth -> stringResource(R.string.via_bluetooth)
        else -> null
    }
}

@Composable
internal fun levelLine(state: FinderState): String? {
    if (state.phase != Phase.Ranging || state.distanceM == null) return null
    val elevation = state.elevationDeg ?: return null
    return when {
        elevation > 25f -> stringResource(R.string.above_you)
        elevation < -25f -> stringResource(R.string.below_you)
        else -> null
    }
}
