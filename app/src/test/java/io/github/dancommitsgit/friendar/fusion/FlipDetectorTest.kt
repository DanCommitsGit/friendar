package io.github.dancommitsgit.friendar.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlipDetectorTest {
    /** The friend is at [truth]; the phone reports an azimuth that puts them at [reported] and its mirror at [negated]. */
    private fun FlipDetector.sample(reported: Float, negated: Float, truth: Float, sigma: Float = 20f) =
        add(reported, negated, truth, sigma)

    @Test
    fun undecidedWithoutSamples() {
        assertNull(FlipDetector().verdict)
    }

    @Test
    fun decidesNegatedWhenNegatedMatchesGps() {
        val d = FlipDetector()
        repeat(6) { d.sample(reported = 100f, negated = 20f, truth = 25f) }
        assertEquals(true, d.verdict)
    }

    @Test
    fun decidesReportedWhenReportedMatchesGps() {
        val d = FlipDetector()
        repeat(6) { d.sample(reported = 20f, negated = 100f, truth = 25f) }
        assertEquals(false, d.verdict)
    }

    @Test
    fun straightAheadCarriesNoInformation() {
        val d = FlipDetector()
        repeat(50) { d.sample(reported = 10f, negated = 350f, truth = 0f) }
        assertNull(d.verdict)
    }

    @Test
    fun singleBadFixDoesNotDecide() {
        val d = FlipDetector()
        d.sample(reported = 100f, negated = 20f, truth = 25f)
        d.sample(reported = 100f, negated = 20f, truth = 25f)
        assertNull(d.verdict)
    }

    @Test
    fun wrapsAcrossNorth() {
        val d = FlipDetector()
        repeat(6) { d.sample(reported = 80f, negated = 320f, truth = 330f) }
        assertEquals(true, d.verdict)
    }

    @Test
    fun reversesWhenEvidenceTurns() {
        val d = FlipDetector()
        repeat(8) { d.sample(reported = 100f, negated = 20f, truth = 25f) }
        assertEquals(true, d.verdict)
        repeat(20) { d.sample(reported = 20f, negated = 100f, truth = 25f) }
        assertEquals(false, d.verdict)
    }

    @Test
    fun resetForgetsEverything() {
        val d = FlipDetector()
        repeat(8) { d.sample(reported = 100f, negated = 20f, truth = 25f) }
        d.reset()
        assertNull(d.verdict)
        assertEquals(0f, d.score, 0f)
    }
}
