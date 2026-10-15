package io.github.dancommitsgit.friendar.finder

import java.util.Locale

private val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")

/** Both test phones report the azimuth with the wrong sign, so that is the guess before anything is learned. */
internal fun mirrorFor(mode: FlipMode, auto: Boolean?) = when (mode) {
    FlipMode.Yes -> true
    FlipMode.No -> false
    FlipMode.Auto -> auto ?: true
}

internal fun metricFor(mode: UnitMode, country: String = Locale.getDefault().country) = when (mode) {
    UnitMode.Metric -> true
    UnitMode.Imperial -> false
    UnitMode.Auto -> country !in IMPERIAL_COUNTRIES
}

/** A suggestion for a step that is taking unusually long. */
internal enum class Hint { Waiting, Blocked }

internal fun hintKindFor(s: FinderState): Hint? = when {
    s.phase == Phase.Waiting -> Hint.Waiting

    s.phase == Phase.Ranging && s.uwbActive && s.distanceM == null && s.rssiDbm != null -> Hint.Blocked

    else -> null
}
