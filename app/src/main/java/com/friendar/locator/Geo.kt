package com.friendar.locator

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A position fix; [timeMs] is on the elapsed-realtime clock of the phone that holds it. */
data class Fix(val lat: Double, val lon: Double, val accuracyM: Float, val timeMs: Long)

enum class EstimateSource { RangeAndBearing, RangeAndGps, GpsOnly }

/** Friend's position in meters east and north of this phone. */
data class FriendEstimate(
    val eastM: Float,
    val northM: Float,
    val sigmaM: Float,
    val source: EstimateSource,
)

object Geo {
    private const val METERS_PER_DEGREE = 111_320.0

    /** East and north offset in meters of [to] from [from]; fine for distances of a few kilometers. */
    fun offset(from: Fix, to: Fix): FloatArray {
        val north = (to.lat - from.lat) * METERS_PER_DEGREE
        val east = (to.lon - from.lon) * METERS_PER_DEGREE * cos(Math.toRadians(from.lat))
        return floatArrayOf(east.toFloat(), north.toFloat())
    }
}

object Fusion {
    /**
     * Best guess of where the friend is relative to this phone.
     * A measured distance with a true bearing wins; otherwise the GPS bearing is
     * combined with the measured distance, or GPS alone is used. Returns null when the
     * GPS bearing would be meaningless because its error exceeds the measured distance.
     */
    fun estimate(
        me: Fix?,
        friend: Fix?,
        rangeM: Float?,
        rangeSigmaM: Float,
        bearingDeg: Float?,
        bearingSigmaDeg: Float = 15f,
    ): FriendEstimate? {
        if (rangeM != null && bearingDeg != null) {
            val rad = Math.toRadians(bearingDeg.toDouble())
            val tangential = rangeM * sin(Math.toRadians(bearingSigmaDeg.toDouble())).toFloat()
            return FriendEstimate(
                eastM = (rangeM * sin(rad)).toFloat(),
                northM = (rangeM * cos(rad)).toFloat(),
                sigmaM = hypot(rangeSigmaM, tangential),
                source = EstimateSource.RangeAndBearing,
            )
        }
        if (me == null || friend == null) return null
        val o = Geo.offset(me, friend)
        val gpsSigma = hypot(me.accuracyM, friend.accuracyM)
        if (rangeM == null) return FriendEstimate(o[0], o[1], gpsSigma, EstimateSource.GpsOnly)
        val length = hypot(o[0], o[1])
        if (length < MIN_GPS_BASELINE_M || gpsSigma > rangeM) return null
        val k = rangeM / length
        return FriendEstimate(o[0] * k, o[1] * k, gpsSigma, EstimateSource.RangeAndGps)
    }

    private const val MIN_GPS_BASELINE_M = 0.5f
}
