package io.github.dancommitsgit.friendar.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BearingTest {
    @Test
    fun certainSourceDominates() {
        val b = BearingFusion.combine(listOf(Bearing(10f, 10f), Bearing(40f, 40f)))!!
        assertTrue("was ${b.deg}", b.deg in 10f..20f)
        assertTrue(b.sigmaDeg < 10f)
    }

    @Test
    fun equalSourcesAverageAcrossNorth() {
        val b = BearingFusion.combine(listOf(Bearing(350f, 10f), Bearing(10f, 10f)))!!
        assertTrue("was ${b.deg}", b.deg < 1f || b.deg > 359f)
    }

    @Test
    fun contradictingWeakSourceIsIgnored() {
        val b = BearingFusion.combine(listOf(Bearing(0f, 10f), Bearing(180f, 30f)))!!
        assertEquals(0f, b.deg, 0.5f)
        assertEquals(10f, b.sigmaDeg, 0.5f)
    }

    @Test
    fun emptyInputGivesNothing() {
        assertNull(BearingFusion.combine(emptyList()))
    }

    @Test
    fun calibratorNeedsSamples() {
        assertNull(FrameCalibrator().estimate())
    }

    @Test
    fun calibratorRecoversConstantOffsetFromNoisySamples() {
        val calibrator = FrameCalibrator()
        val noise = floatArrayOf(-12f, 9f, -5f, 14f, -8f, 3f, 11f, -10f)
        repeat(40) { calibrator.add(70f + noise[it % noise.size], 15f) }
        val e = calibrator.estimate()!!
        assertEquals(70f, e.deg, 3f)
        assertTrue(e.sigmaDeg >= 8f)
    }

    @Test
    fun calibratorHandlesWrapAround() {
        val calibrator = FrameCalibrator()
        repeat(10) { calibrator.add(if (it % 2 == 0) 355f else 5f, 10f) }
        val e = calibrator.estimate()!!
        assertTrue("was ${e.deg}", e.deg < 2f || e.deg > 358f)
    }

    @Test
    fun frameOffsetConventionMatchesCompassBearing() {
        // A friend due east of us, seen along the +y axis of a frame yawed 90 degrees.
        val world = floatArrayOf(0f, 1f, 0f)
        val gameBearing = bearingOf(world)
        val trueBearing = 90f
        val offset = wrapPositive(gameBearing - trueBearing)
        val back = Geometry.compassBearingDeg(world, Math.toRadians(offset.toDouble()).toFloat())
        assertEquals(trueBearing, back, 0.01f)
    }
}
