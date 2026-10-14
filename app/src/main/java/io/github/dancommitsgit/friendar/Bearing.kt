package io.github.dancommitsgit.friendar

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A direction in degrees clockwise from a frame's +y axis, with its 1-sigma uncertainty. */
data class Bearing(val deg: Float, val sigmaDeg: Float)

/** Which measurements an arrow direction came from. */
enum class DirectionSource { Uwb, Gps, Both }

/** Combines independent direction estimates, trusting each in proportion to 1 / sigma squared. */
object BearingFusion {
    private const val MIN_SIGMA_DEG = 3f
    private const val DISAGREEMENT = 2.5f

    /**
     * Circular weighted mean. A sample that disagrees with the most certain one by more
     * than a few combined sigmas is left out rather than averaged in.
     */
    fun combine(samples: List<Bearing>): Bearing? {
        if (samples.isEmpty()) return null
        val best = samples.minBy { it.sigmaDeg }
        var sx = 0.0
        var sy = 0.0
        var sw = 0.0
        for (s in samples) {
            val consistent = s === best ||
                abs(wrapDegrees(s.deg - best.deg)) <= DISAGREEMENT * hypot(s.sigmaDeg, best.sigmaDeg)
            if (!consistent) continue
            val sigma = max(s.sigmaDeg, MIN_SIGMA_DEG)
            val w = 1.0 / (sigma * sigma)
            val rad = Math.toRadians(s.deg.toDouble())
            sx += w * sin(rad)
            sy += w * cos(rad)
            sw += w
        }
        val deg = Math.toDegrees(atan2(sx, sy)).toFloat()
        return Bearing(wrapPositive(deg), max(MIN_SIGMA_DEG, (1.0 / sqrt(sw)).toFloat()))
    }
}

/**
 * Learns how the phone's gyroscope frame is yawed relative to true north by comparing
 * bearings to the friend measured in that frame (UWB) with bearings from GPS.
 * The offset is defined so that true bearing = frame bearing - offset.
 * Older samples fade, since the frame drifts slowly.
 */
class FrameCalibrator(private val decay: Float = 0.995f) {
    private var sx = 0.0
    private var sy = 0.0
    private var sw = 0.0

    fun add(offsetDeg: Float, sigmaDeg: Float) {
        val sigma = max(sigmaDeg, MIN_SAMPLE_SIGMA_DEG)
        val w = 1.0 / (sigma * sigma)
        val rad = Math.toRadians(offsetDeg.toDouble())
        sx = sx * decay + w * sin(rad)
        sy = sy * decay + w * cos(rad)
        sw = sw * decay + w
    }

    /** Null until a few usable samples exist. The sigma is floored because GPS errors are correlated over time. */
    fun estimate(): Bearing? {
        if (sw < MIN_WEIGHT) return null
        val deg = Math.toDegrees(atan2(sx, sy)).toFloat()
        return Bearing(wrapPositive(deg), max(SIGMA_FLOOR_DEG, (1.0 / sqrt(sw)).toFloat()))
    }

    fun reset() {
        sx = 0.0
        sy = 0.0
        sw = 0.0
    }

    private companion object {
        const val MIN_SAMPLE_SIGMA_DEG = 5f
        const val MIN_WEIGHT = 0.005
        const val SIGMA_FLOOR_DEG = 8f
    }
}

/** Bearing of a frame-space vector, degrees clockwise from the frame's +y axis. */
fun bearingOf(world: FloatArray): Float =
    wrapPositive(Math.toDegrees(atan2(world[0], world[1]).toDouble()).toFloat())
