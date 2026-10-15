package io.github.dancommitsgit.friendar

import kotlin.math.abs
import kotlin.math.max

/**
 * Decides whether the UWB azimuth has to be negated, by checking which sign puts the
 * friend closer to a reference bearing from GPS and compass.
 *
 * Each sample adds a capped log-likelihood ratio to a running score that fades over time,
 * so one bad GPS fix cannot flip the verdict, and the verdict can still reverse when
 * the evidence turns the other way.
 */
class FlipDetector {
    private var total = 0f

    /** Positive favors the negated azimuth, negative the azimuth as reported. */
    val score: Float
        get() = total

    /** True when negating fits better, false when the reported sign does, null while unsure. */
    var verdict: Boolean? = null
        private set

    /**
     * All bearings are in degrees in one frame. [sigmaDeg] is the uncertainty of the reference.
     * Samples where the two signs give nearly the same bearing carry no information and are skipped.
     */
    fun add(reportedBearing: Float, negatedBearing: Float, referenceBearing: Float, sigmaDeg: Float) {
        if (abs(wrapDegrees(reportedBearing - negatedBearing)) < MIN_SEPARATION_DEG) return
        val sigma = max(sigmaDeg, MIN_SIGMA_DEG)
        val reportedError = wrapDegrees(reportedBearing - referenceBearing)
        val negatedError = wrapDegrees(negatedBearing - referenceBearing)
        val ratio = (reportedError * reportedError - negatedError * negatedError) / (2f * sigma * sigma)
        total = (total * DECAY + ratio.coerceIn(-STEP_LIMIT, STEP_LIMIT)).coerceIn(-SCORE_LIMIT, SCORE_LIMIT)
        if (total >= DECIDE) verdict = true
        if (total <= -DECIDE) verdict = false
    }

    fun reset() {
        total = 0f
        verdict = null
    }

    private companion object {
        const val MIN_SEPARATION_DEG = 30f
        const val MIN_SIGMA_DEG = 10f
        const val DECAY = 0.97f
        const val STEP_LIMIT = 2f
        const val DECIDE = 8f
        const val SCORE_LIMIT = 2f * DECIDE
    }
}
