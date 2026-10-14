package io.github.dancommitsgit.friendar

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Exponential smoothing for a scalar reading. */
class Smoother(private val alpha: Float) {
    private var value: Float? = null

    fun add(sample: Float): Float {
        val prev = value
        val next = if (prev == null) sample else prev + alpha * (sample - prev)
        value = next
        return next
    }

    fun reset() {
        value = null
    }
}

/** Median of the last [size] samples, then exponential smoothing, to reject range spikes. */
class DistanceFilter(private val size: Int = 5, alpha: Float = 0.5f) {
    private val window = ArrayDeque<Float>()
    private val smoother = Smoother(alpha)

    fun add(sample: Float): Float {
        window.addLast(sample)
        if (window.size > size) window.removeFirst()
        val median = window.sorted()[window.size / 2]
        return smoother.add(median)
    }

    fun reset() {
        window.clear()
        smoother.reset()
    }
}

/**
 * Vectors use the phone frame (x right, y toward the top edge, z out of the screen)
 * or the world frame of the rotation sensor (z up). A rotation matrix `r` is the
 * row-major 3x3 matrix from SensorManager that maps phone frame to world frame.
 */
object Geometry {
    /** Unit vector toward a UWB reading, where azimuth 0 / elevation 0 is out of the back of the phone. */
    fun deviceVector(azimuthDeg: Float, elevationDeg: Float): FloatArray {
        val az = Math.toRadians(azimuthDeg.toDouble())
        val el = Math.toRadians(elevationDeg.toDouble())
        return floatArrayOf(
            (sin(az) * cos(el)).toFloat(),
            sin(el).toFloat(),
            (-cos(az) * cos(el)).toFloat(),
        )
    }

    fun toWorld(r: FloatArray, v: FloatArray) = floatArrayOf(
        r[0] * v[0] + r[1] * v[1] + r[2] * v[2],
        r[3] * v[0] + r[4] * v[1] + r[5] * v[2],
        r[6] * v[0] + r[7] * v[1] + r[8] * v[2],
    )

    fun toDevice(r: FloatArray, w: FloatArray) = floatArrayOf(
        r[0] * w[0] + r[3] * w[1] + r[6] * w[2],
        r[1] * w[0] + r[4] * w[1] + r[7] * w[2],
        r[2] * w[0] + r[5] * w[1] + r[8] * w[2],
    )

    /** Angle between a phone-frame vector and the direction out of the back of the phone. */
    fun offBoresightDeg(v: FloatArray): Float =
        Math.toDegrees(acos((-v[2]).coerceIn(-1f, 1f)).toDouble()).toFloat()

    /** Screen is within roughly 55 degrees of horizontal. */
    fun isFlat(r: FloatArray): Boolean = abs(r[8]) > 0.57f

    /**
     * Angle to draw the arrow, in degrees clockwise from the top edge of the screen.
     * Flat: the direction along the ground, like a compass needle.
     * Upright: the left/right angle from where the back of the phone points.
     * Null when the friend is straight above or below a flat phone.
     */
    fun arrowDegrees(r: FloatArray, world: FloatArray): Float? {
        if (isFlat(r)) {
            if (hypot(world[0], world[1]) < 0.12f) return null
            val d = toDevice(r, floatArrayOf(world[0], world[1], 0f))
            return Math.toDegrees(atan2(d[0], d[1]).toDouble()).toFloat()
        }
        val d = toDevice(r, world)
        return Math.toDegrees(atan2(d[0], -d[2]).toDouble()).toFloat()
    }

    /**
     * Compass bearing, degrees clockwise from north, of a world-frame direction whose
     * frame is yawed [northOffsetRad] radians from north.
     */
    fun compassBearingDeg(world: FloatArray, northOffsetRad: Float): Float {
        val c = cos(northOffsetRad)
        val s = sin(northOffsetRad)
        val east = world[0] * c - world[1] * s
        val north = world[0] * s + world[1] * c
        val bearing = Math.toDegrees(atan2(east, north).toDouble()).toFloat()
        return (if (bearing < 0f) bearing + 360f else bearing) % 360f
    }

    /** Compass bearing the phone points at: its top edge when flat, its back when upright. */
    fun headingDeg(r: FloatArray, northOffsetRad: Float): Float {
        val forward = if (isFlat(r)) {
            floatArrayOf(r[1], r[4], r[7])
        } else {
            floatArrayOf(-r[2], -r[5], -r[8])
        }
        return compassBearingDeg(forward, northOffsetRad)
    }

    /** Elevation of a world-frame direction above the horizon, in degrees. */
    fun worldElevationDeg(world: FloatArray): Float =
        Math.toDegrees(kotlin.math.asin(world[2].coerceIn(-1f, 1f)).toDouble()).toFloat()

    /** Trust in a reading: full when the friend is in front of the phone, none beyond ~80 degrees. */
    fun weight(offBoresightDeg: Float): Float {
        val x = offBoresightDeg / 80f
        return (1f - x * x).coerceIn(0f, 1f)
    }
}

/**
 * Averages direction readings in the world frame, so the phone turning between
 * readings does not smear them. Poor readings are ignored, so the last good direction
 * is held, and a reading far from the established direction is held back until
 * several in a row agree with it.
 */
class DirectionFilter(private val decay: Float = 0.93f) {
    private val sum = FloatArray(3)
    private var weight = 0f
    private var outliers = 0

    /** Returns false when the reading was ignored. */
    fun add(world: FloatArray, w: Float): Boolean {
        if (w < MIN_WEIGHT) return false
        val norm = sqrt(world[0] * world[0] + world[1] * world[1] + world[2] * world[2])
        if (norm < 1e-3f) return false
        val unit = floatArrayOf(world[0] / norm, world[1] / norm, world[2] / norm)
        val current = direction()
        if (current != null && weight > TRUSTED_WEIGHT) {
            val cos = current[0] * unit[0] + current[1] * unit[1] + current[2] * unit[2]
            if (cos < OUTLIER_COS) {
                if (++outliers < OUTLIER_LIMIT) return false
                reset()
            }
        }
        outliers = 0
        for (i in 0..2) sum[i] = sum[i] * decay + unit[i] * w
        weight = weight * decay + w
        return true
    }

    /** Unit vector toward the friend, or null before any usable reading. */
    fun direction(): FloatArray? {
        val norm = sqrt(sum[0] * sum[0] + sum[1] * sum[1] + sum[2] * sum[2])
        if (norm < 1e-3f) return null
        return floatArrayOf(sum[0] / norm, sum[1] / norm, sum[2] / norm)
    }

    /** Roughly the number of recent good readings. */
    fun confidence(): Float = weight

    fun reset() {
        sum.fill(0f)
        weight = 0f
        outliers = 0
    }

    private companion object {
        const val MIN_WEIGHT = 0.15f
        const val TRUSTED_WEIGHT = 2f
        const val OUTLIER_COS = 0.766f // 40 degrees
        const val OUTLIER_LIMIT = 6
    }
}

/** Wraps an angle into (-180, 180]. */
fun wrapDegrees(angle: Float): Float {
    var a = angle % 360f
    if (a > 180f) a -= 360f
    if (a <= -180f) a += 360f
    return a
}

/** Wraps an angle into [0, 360). */
fun wrapPositive(angle: Float): Float {
    val a = angle % 360f
    return if (a < 0f) a + 360f else a
}

/** Low-pass filter for a displayed angle that takes the short way around and ignores tiny changes. */
class AngleSmoother(private val alpha: Float = 0.3f, private val deadbandDeg: Float = 2f) {
    private var value: Float? = null

    fun add(target: Float): Float {
        val prev = value
        if (prev == null) {
            value = target
            return target
        }
        val diff = wrapDegrees(target - prev)
        if (abs(diff) < deadbandDeg) return prev
        val next = wrapDegrees(prev + alpha * diff)
        value = next
        return next
    }

    fun reset() {
        value = null
    }
}

fun isClose(distanceMeters: Float?): Boolean = distanceMeters != null && abs(distanceMeters) < 1.5f
