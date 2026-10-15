package io.github.dancommitsgit.friendar.fusion

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** The compass reading: how far the gyroscope frame's yaw is from magnetic north. */
class NorthReference(val offsetRad: Float, val reliable: Boolean)

/** What one UWB reading contributed to the direction filter. */
class ReadingResult(val offBoresightDeg: Float, val weight: Float, val used: Boolean)

/** The friend's estimated position and where north came from. */
class PositionEstimate(val friend: FriendEstimate?, val friendGps: Boolean, val frameInfo: String?)

/** What the arrow and its surrounding readouts should show. */
class ArrowReading(
    val arrowDeg: Float?,
    val elevationDeg: Float?,
    val directionLive: Boolean,
    val source: DirectionSource?,
    val headingDeg: Float?,
    val flat: Boolean,
)

/** The verdict on the sign of the UWB azimuth, once it differs from the one already learned. */
class FlipVerdict(val reversed: Boolean, val score: Float)

/**
 * Combines UWB angles, GPS fixes and the compass into one direction to the friend and a
 * position on the map. All times are on the elapsed-realtime clock.
 */
class FriendTracker {
    private val directionFilter = DirectionFilter()
    private val arrowSmoother = AngleSmoother()
    private val frameCalibrator = FrameCalibrator()
    private val flipDetector = FlipDetector()

    private class Reading(val azimuthDeg: Float, val elevationDeg: Float, val rotation: FloatArray, val timeMs: Long)

    /** Latest UWB angle with the phone attitude it was taken at, as reported before any flip. */
    @Volatile
    private var lastReading: Reading? = null

    /** Bearing to the friend from GPS, in the gyroscope frame; refreshed with every position estimate. */
    @Volatile
    private var gpsBearing: Bearing? = null

    /** Yaw of the gyroscope frame from true north, from the compass and from UWB-versus-GPS calibration. */
    @Volatile
    private var frame: Bearing? = null
    private var lastGoodDirectionMs = 0L
    private var friendFix: Fix? = null
    private var declinationDeg = 0f

    var myFix: Fix? = null
        private set

    fun onMyFix(fix: Fix, declinationDeg: Float) {
        myFix = fix
        this.declinationDeg = declinationDeg
    }

    fun onFriendFix(fix: Fix) {
        friendFix = fix
    }

    /**
     * Feeds a UWB angle into the direction filter. [mirror] negates the azimuth, and
     * [distanceTrust] scales its weight down when the distance readings are erratic.
     */
    fun addUwbReading(
        azimuthDeg: Float,
        elevationDeg: Float,
        rotation: FloatArray,
        mirror: Boolean,
        distanceTrust: Float,
        timeMs: Long,
    ): ReadingResult {
        lastReading = Reading(azimuthDeg, elevationDeg, rotation, timeMs)
        val signed = if (mirror) -azimuthDeg else azimuthDeg
        val local = Geometry.deviceVector(signed, elevationDeg)
        val off = Geometry.offBoresightDeg(local)
        val weight = Geometry.weight(off) * distanceTrust
        val used = directionFilter.add(Geometry.toWorld(rotation, local), weight)
        if (used) lastGoodDirectionMs = timeMs
        return ReadingResult(off, weight, used)
    }

    /** Drops the averaged direction and the smoothed arrow. */
    fun forgetDirection() {
        directionFilter.reset()
        arrowSmoother.reset()
    }

    /** Readings and calibration made with the other azimuth sign no longer describe the friend's direction. */
    fun onMirrorChanged() {
        forgetDirection()
        frameCalibrator.reset()
    }

    /** Forgets everything learned during a search. */
    fun clear() {
        forgetDirection()
        lastGoodDirectionMs = 0L
        frameCalibrator.reset()
        flipDetector.reset()
        gpsBearing = null
        frame = null
        lastReading = null
        myFix = null
        friendFix = null
    }

    /**
     * Where the gyroscope frame points relative to true north, blending the compass with
     * UWB-versus-GPS calibration.
     */
    private fun frameOffset(north: NorthReference?): Bearing? {
        val magnetic = north?.let {
            val deg = Math.toDegrees(it.offsetRad.toDouble()).toFloat() - declinationDeg
            Bearing(wrapPositive(deg), if (it.reliable) COMPASS_SIGMA_DEG else COMPASS_POOR_SIGMA_DEG)
        }
        return BearingFusion.combine(listOfNotNull(magnetic, frameCalibrator.estimate()))
    }

    private fun freshFixes(now: Long): Pair<Fix, Fix>? {
        val me = myFix?.takeIf { now - it.timeMs < FIX_MAX_AGE_MS } ?: return null
        val friend = friendFix?.takeIf { now - it.timeMs < FIX_MAX_AGE_MS } ?: return null
        return me to friend
    }

    /** Learns the frame's yaw from moments when UWB has a clear line and both GPS fixes are good. */
    fun calibrate(now: Long, uwbDistanceM: Float?) {
        val (me, friend) = freshFixes(now) ?: return
        val direction = directionFilter.direction() ?: return
        if (now - lastGoodDirectionMs > DIRECTION_LIVE_MS) return
        val range = uwbDistanceM ?: return
        val positionSigma = hypot(me.accuracyM, friend.accuracyM)
        if (positionSigma > CALIBRATION_MAX_GPS_ERROR_M) return
        if (range < maxOf(CALIBRATION_MIN_RANGE_M, 3f * positionSigma)) return
        val trueBearing = bearingOf(Geo.offset(me, friend))
        val gpsSigma = Math.toDegrees(atan2(positionSigma, range).toDouble()).toFloat()
        frameCalibrator.add(
            wrapPositive(bearingOf(direction) - trueBearing),
            hypot(gpsSigma, UWB_SIGMA_DEG),
        )
    }

    /**
     * Compares where each azimuth sign would put the friend with the GPS bearing, using the
     * compass alone for north so that a wrong sign cannot leak into the reference. Returns a
     * verdict only when it differs from [learned].
     */
    fun checkFlip(now: Long, uwbDistanceM: Float?, north: NorthReference?, learned: Boolean?): FlipVerdict? {
        val reading = lastReading?.takeIf { now - it.timeMs < FLIP_READING_MAX_AGE_MS } ?: return null
        val (me, friend) = freshFixes(now) ?: return null
        val range = uwbDistanceM ?: return null
        val northRad = north?.takeIf { it.reliable }?.offsetRad ?: return null
        val positionSigma = hypot(me.accuracyM, friend.accuracyM)
        if (positionSigma > CALIBRATION_MAX_GPS_ERROR_M) return null
        if (range < maxOf(CALIBRATION_MIN_RANGE_M, 3f * positionSigma)) return null

        val reported = Geometry.deviceVector(reading.azimuthDeg, reading.elevationDeg)
        if (Geometry.weight(Geometry.offBoresightDeg(reported)) < FLIP_MIN_WEIGHT) return null
        val negated = Geometry.deviceVector(-reading.azimuthDeg, reading.elevationDeg)

        val trueBearing = bearingOf(Geo.offset(me, friend))
        val frameDeg = wrapPositive(Math.toDegrees(northRad.toDouble()).toFloat() - declinationDeg)
        val gpsSigma = Math.toDegrees(atan2(positionSigma, range).toDouble()).toFloat()
        flipDetector.add(
            reportedBearing = bearingOf(Geometry.toWorld(reading.rotation, reported)),
            negatedBearing = bearingOf(Geometry.toWorld(reading.rotation, negated)),
            referenceBearing = wrapPositive(trueBearing + frameDeg),
            sigmaDeg = hypot(gpsSigma, COMPASS_SIGMA_DEG),
        )
        val verdict = flipDetector.verdict ?: return null
        if (verdict == learned) return null
        return FlipVerdict(verdict, flipDetector.score)
    }

    /** One direction from UWB and GPS, in the gyroscope frame, with the vertical angle from UWB. */
    private class Fused(val bearing: Bearing, val elevationDeg: Float, val source: DirectionSource) {
        val world: FloatArray
            get() {
                val b = Math.toRadians(bearing.deg.toDouble())
                val e = Math.toRadians(elevationDeg.toDouble())
                return floatArrayOf(
                    (sin(b) * cos(e)).toFloat(),
                    (cos(b) * cos(e)).toFloat(),
                    sin(e).toFloat(),
                )
            }
    }

    private fun fuseDirection(now: Long): Fused? {
        val direction = directionFilter.direction()
        val age = now - lastGoodDirectionMs
        val uwb = direction?.let {
            val stale = maxOf(0L, age - DIRECTION_LIVE_MS) / 1000f
            Bearing(bearingOf(it), minOf(UWB_SIGMA_DEG + stale * UWB_AGING_DEG_PER_S, 90f))
        }
        val gps = gpsBearing
        val combined = BearingFusion.combine(listOfNotNull(uwb, gps)) ?: return null
        val source = when {
            uwb != null && gps != null -> {
                val wUwb = 1f / (uwb.sigmaDeg * uwb.sigmaDeg)
                val wGps = 1f / (gps.sigmaDeg * gps.sigmaDeg)
                when {
                    wUwb >= 4f * wGps -> DirectionSource.Uwb
                    wGps >= 4f * wUwb -> DirectionSource.Gps
                    else -> DirectionSource.Both
                }
            }

            uwb != null -> DirectionSource.Uwb
            else -> DirectionSource.Gps
        }
        val elevation = if (direction != null && age < ELEVATION_HOLD_MS) {
            Geometry.worldElevationDeg(direction)
        } else {
            0f
        }
        return Fused(combined, elevation, source)
    }

    /** Recomputes the GPS bearing, the frame and the friend's position from the latest inputs. */
    fun estimate(now: Long, range: Range?, north: NorthReference?): PositionEstimate {
        val fixes = freshFixes(now)
        val frameNow = frameOffset(north)
        frame = frameNow

        // GPS bearing to the friend, moved into the gyroscope frame.
        val gpsDirection = if (fixes != null && frameNow != null) {
            val (me, friend) = fixes
            val offset = Geo.offset(me, friend)
            val length = hypot(offset[0], offset[1])
            val positionSigma = hypot(me.accuracyM, friend.accuracyM)
            if (length < MIN_GPS_BASELINE_M) {
                null
            } else {
                val trueBearing = bearingOf(offset)
                val reference = range?.meters ?: length
                val sigma = Math.toDegrees(atan2(positionSigma, reference).toDouble()).toFloat()
                    .coerceIn(MIN_GPS_SIGMA_DEG, MAX_GPS_SIGMA_DEG)
                Bearing(wrapPositive(trueBearing + frameNow.deg), hypot(sigma, frameNow.sigmaDeg))
            }
        } else {
            null
        }
        gpsBearing = gpsDirection

        val fused = fuseDirection(now)
        val trueBearing = if (fused != null && frameNow != null) {
            wrapPositive(fused.bearing.deg - frameNow.deg)
        } else {
            null
        }
        val bearingSigma = fused?.let { hypot(it.bearing.sigmaDeg, frameNow?.sigmaDeg ?: 0f) } ?: 0f
        val friend = Fusion.estimate(
            me = fixes?.first,
            friend = fixes?.second,
            rangeM = range?.meters,
            rangeSigmaM = range?.sigmaM ?: 0f,
            bearingDeg = trueBearing,
            bearingSigmaDeg = bearingSigma,
        )
        val calibrated = frameCalibrator.estimate()
        val info = frameNow?.let {
            val how = when {
                calibrated != null && north != null -> "compass + UWB/GPS"
                calibrated != null -> "UWB/GPS"
                else -> "compass"
            }
            "north: $how, ±%.0f°".format(Locale.US, it.sigmaDeg)
        }
        return PositionEstimate(friend, fixes != null, info)
    }

    /** The arrow for the phone's current attitude [rotation], from the latest direction. */
    fun arrow(now: Long, rotation: FloatArray): ArrowReading {
        val fused = fuseDirection(now)
        val world = fused?.world
        val raw = world?.let { Geometry.arrowDegrees(rotation, it) }
        val arrow = if (raw != null) {
            arrowSmoother.add(raw)
        } else {
            arrowSmoother.reset()
            null
        }
        val elevation = world?.let { Geometry.worldElevationDeg(it) }
        val live = directionFilter.confidence() > LIVE_CONFIDENCE &&
            now - lastGoodDirectionMs < DIRECTION_LIVE_MS
        val heading = frame?.let {
            wrapPositive(Geometry.headingDeg(rotation, Math.toRadians(it.deg.toDouble()).toFloat()))
        }
        return ArrowReading(arrow, elevation, live, fused?.source, heading, Geometry.isFlat(rotation))
    }

    private companion object {
        const val UWB_SIGMA_DEG = 12f
        const val UWB_AGING_DEG_PER_S = 2f
        const val ELEVATION_HOLD_MS = 10_000L
        const val COMPASS_SIGMA_DEG = 20f
        const val COMPASS_POOR_SIGMA_DEG = 40f
        const val MIN_GPS_BASELINE_M = 0.5f
        const val MIN_GPS_SIGMA_DEG = 5f
        const val MAX_GPS_SIGMA_DEG = 120f
        const val CALIBRATION_MAX_GPS_ERROR_M = 15f
        const val CALIBRATION_MIN_RANGE_M = 8f
        const val FLIP_READING_MAX_AGE_MS = 1_500L
        const val FLIP_MIN_WEIGHT = 0.2f
        const val FIX_MAX_AGE_MS = 60_000L
        const val DIRECTION_LIVE_MS = 2_000L
        const val LIVE_CONFIDENCE = 1.0f
    }
}
