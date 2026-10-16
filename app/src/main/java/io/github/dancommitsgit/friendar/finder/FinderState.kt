package io.github.dancommitsgit.friendar.finder

import io.github.dancommitsgit.friendar.fusion.DirectionSource
import io.github.dancommitsgit.friendar.fusion.FriendEstimate
import io.github.dancommitsgit.friendar.fusion.RangeSource
import io.github.dancommitsgit.friendar.fusion.Trend

enum class Role { Host, Joiner }

enum class Phase { Idle, Waiting, Ranging, Failed }

enum class FlipMode { Yes, No, Auto }

enum class ThemeMode { System, Light, Dark }

data class FinderState(
    /** Which side of the Bluetooth link this phone ended up on; null until linked. */
    val role: Role? = null,
    val phase: Phase = Phase.Idle,
    /** Precise UWB distance; null while UWB has no current measurement. */
    val distanceM: Float? = null,
    /** Degrees clockwise from the top of the screen, null until a direction is known. */
    val arrowDeg: Float? = null,
    /** Friend's height relative to the horizon, in degrees. */
    val elevationDeg: Float? = null,
    /** True while recent UWB readings are good enough to trust the direction. */
    val directionLive: Boolean = false,
    /** True when the phone is held flat and the arrow works like a compass needle. */
    val flat: Boolean = false,
    /** Smoothed Bluetooth signal strength. */
    val rssiDbm: Int? = null,
    /** Rough distance from Bluetooth signal strength; works through walls. */
    val signalDistanceM: Float? = null,
    /** Wi-Fi round-trip-time distance; works through walls, good to a few meters. */
    val awareDistanceM: Float? = null,
    val trend: Trend? = null,
    /** Best available distance, with where it came from; null when nothing is measured. */
    val rangeM: Float? = null,
    val rangeSource: RangeSource? = null,
    val myAccuracyM: Float? = null,
    /** True while the friend's shared GPS position is recent. */
    val friendGps: Boolean = false,
    /** Friend's estimated position in meters east and north of this phone. */
    val friend: FriendEstimate? = null,
    /** Direction this phone faces, degrees clockwise from true north; null without a compass reference. */
    val headingDeg: Float? = null,
    /** Which measurements the arrow direction comes from. */
    val arrowSource: DirectionSource? = null,
    /** How north was found and how far to trust it, for the details screen. */
    val frameInfo: String? = null,
    val uwbActive: Boolean = false,
    /** Why a failure or fallback happened. */
    val message: String? = null,
    /** Suggestion shown when a step is taking unusually long. */
    val hint: String? = null,
    val flipMode: FlipMode = FlipMode.Auto,
    /** What Auto resolves to: true when negating the UWB azimuth fits GPS and compass; null until learned. */
    val flipAuto: Boolean? = null,
    /** Whether the UWB azimuth is negated right now, however that was decided. */
    val mirror: Boolean = true,
    /** Show distances in meters instead of feet. */
    val metric: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.System,
    /** Keep searching while the app is not in front. */
    val background: Boolean = true,
    /** Show measurement sources and raw readings. */
    val debug: Boolean = false,
    val rawReading: String? = null,
    val log: List<String> = emptyList(),
)
