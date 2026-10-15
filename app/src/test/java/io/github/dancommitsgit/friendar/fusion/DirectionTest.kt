package io.github.dancommitsgit.friendar.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionTest {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** Phone held upright with the screen facing south, back facing north. */
    private val upright = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)

    private fun assertVector(expected: FloatArray, actual: FloatArray) {
        for (i in 0..2) assertEquals(expected[i], actual[i], 0.001f)
    }

    @Test
    fun straightAheadIsOutOfTheBack() {
        assertVector(floatArrayOf(0f, 0f, -1f), Geometry.deviceVector(0f, 0f))
    }

    @Test
    fun positiveAzimuthIsToTheRight() {
        val v = Geometry.deviceVector(90f, 0f)
        assertEquals(1f, v[0], 0.001f)
    }

    @Test
    fun positiveElevationIsTowardTheTopEdge() {
        val v = Geometry.deviceVector(0f, 90f)
        assertEquals(1f, v[1], 0.001f)
    }

    @Test
    fun worldAndDeviceConversionsAreInverse() {
        val v = floatArrayOf(0.3f, -0.5f, 0.8f)
        assertVector(v, Geometry.toDevice(upright, Geometry.toWorld(upright, v)))
    }

    @Test
    fun flatPhoneArrowFollowsGroundDirection() {
        assertEquals(0f, Geometry.arrowDegrees(identity, floatArrayOf(0f, 1f, 0f))!!, 0.01f)
        assertEquals(90f, Geometry.arrowDegrees(identity, floatArrayOf(1f, 0f, 0f))!!, 0.01f)
        assertEquals(-90f, Geometry.arrowDegrees(identity, floatArrayOf(-1f, 0f, 0f))!!, 0.01f)
    }

    @Test
    fun flatPhoneHasNoArrowWhenFriendIsDirectlyAbove() {
        assertNull(Geometry.arrowDegrees(identity, floatArrayOf(0f, 0f, 1f)))
    }

    @Test
    fun uprightPhoneArrowUsesLeftRightAngle() {
        assertTrue(!Geometry.isFlat(upright))
        assertEquals(0f, Geometry.arrowDegrees(upright, floatArrayOf(0f, 1f, 0f))!!, 0.01f)
        assertEquals(90f, Geometry.arrowDegrees(upright, floatArrayOf(1f, 0f, 0f))!!, 0.01f)
    }

    @Test
    fun arrowStaysOnFriendWhenFlatPhoneTurns() {
        // Phone rotated 90 degrees clockwise (seen from above): its top edge now faces east.
        val turned = floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
        val north = floatArrayOf(0f, 1f, 0f)
        assertEquals(-90f, Geometry.arrowDegrees(turned, north)!!, 0.01f)
    }

    @Test
    fun weightFallsOffAwayFromTheBack() {
        assertEquals(1f, Geometry.weight(0f), 0.001f)
        assertTrue(Geometry.weight(40f) < 1f)
        assertEquals(0f, Geometry.weight(90f), 0.001f)
    }

    @Test
    fun directionFilterAveragesNoisyReadings() {
        val filter = DirectionFilter()
        assertNull(filter.direction())
        repeat(20) {
            val noise = if (it % 2 == 0) 0.2f else -0.2f
            filter.add(floatArrayOf(noise, 1f, 0f), 1f)
        }
        val d = filter.direction()
        assertNotNull(d)
        assertEquals(0f, d!![0], 0.1f)
        assertTrue(filter.confidence() > 1f)
    }

    @Test
    fun poorReadingsAreIgnored() {
        val filter = DirectionFilter()
        repeat(10) { filter.add(floatArrayOf(0f, 1f, 0f), 1f) }
        val before = filter.confidence()
        assertFalse(filter.add(floatArrayOf(1f, 0f, 0f), 0.05f))
        assertEquals(before, filter.confidence(), 1e-6f)
        assertEquals(1f, filter.direction()!![1], 0.001f)
    }

    @Test
    fun lonelyOutlierIsRejectedButSustainedChangeIsFollowed() {
        val filter = DirectionFilter()
        repeat(10) { filter.add(floatArrayOf(0f, 1f, 0f), 1f) }
        assertFalse(filter.add(floatArrayOf(1f, 0f, 0f), 1f))
        assertEquals(1f, filter.direction()!![1], 0.01f)
        repeat(10) { filter.add(floatArrayOf(1f, 0f, 0f), 1f) }
        assertTrue(filter.direction()!![0] > 0.9f)
    }

    @Test
    fun angleSmootherTakesShortWayAroundAndIgnoresJitter() {
        val smoother = AngleSmoother(alpha = 0.5f, deadbandDeg = 2f)
        assertEquals(170f, smoother.add(170f), 0.001f)
        assertEquals(170f, smoother.add(171f), 0.001f)
        val next = smoother.add(-170f)
        assertTrue("moved toward 180, was $next", next > 170f || next < -179f)
    }

    @Test
    fun wrapDegreesStaysInRange() {
        assertEquals(-170f, wrapDegrees(190f), 0.001f)
        assertEquals(170f, wrapDegrees(-190f), 0.001f)
        assertEquals(180f, wrapDegrees(180f), 0.001f)
    }

    @Test
    fun distanceFilterRejectsSpike() {
        val filter = DistanceFilter()
        var last = 0f
        listOf(2.0f, 2.0f, 9.0f, 2.0f, 2.0f).forEach { last = filter.add(it) }
        assertEquals(2.0f, last, 0.2f)
    }

    @Test
    fun closeThreshold() {
        assertTrue(isClose(1.0f))
        assertTrue(!isClose(2.0f))
        assertTrue(!isClose(null))
    }
}
