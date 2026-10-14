package com.friendar.locator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class GeoTest {
    private val me = Fix(40.0, -75.0, 5f, 0L)

    @Test
    fun offsetPointsNorthAndEast() {
        val north = Geo.offset(me, Fix(40.0001, -75.0, 5f, 0L))
        assertEquals(0f, north[0], 0.01f)
        assertEquals(11.13f, north[1], 0.05f)
        val east = Geo.offset(me, Fix(40.0, -74.9999, 5f, 0L))
        assertTrue(east[0] > 8f && east[0] < 9f)
        assertEquals(0f, east[1], 0.01f)
    }

    @Test
    fun compassBearingFollowsFrameOffset() {
        val x = floatArrayOf(1f, 0f, 0f)
        assertEquals(90f, Geometry.compassBearingDeg(x, 0f), 0.01f)
        assertEquals(0f, Geometry.compassBearingDeg(x, (PI / 2).toFloat()), 0.01f)
        assertEquals(270f, Geometry.compassBearingDeg(floatArrayOf(-1f, 0f, 0f), 0f), 0.01f)
    }

    @Test
    fun rangeAndBearingWin() {
        val e = Fusion.estimate(me, Fix(40.01, -75.0, 5f, 0L), 10f, 0.5f, 90f)!!
        assertEquals(EstimateSource.RangeAndBearing, e.source)
        assertEquals(10f, e.eastM, 0.01f)
        assertEquals(0f, e.northM, 0.01f)
    }

    @Test
    fun gpsBearingIsScaledToMeasuredRange() {
        val e = Fusion.estimate(me, Fix(40.001, -75.0, 2f, 0L), 20f, 1f, null)!!
        assertEquals(EstimateSource.RangeAndGps, e.source)
        assertEquals(0f, e.eastM, 0.01f)
        assertEquals(20f, e.northM, 0.01f)
    }

    @Test
    fun uselessGpsBearingIsDropped() {
        val e = Fusion.estimate(me, Fix(40.00005, -75.0, 30f, 0L), 5f, 1f, null)
        assertNull(e)
    }

    @Test
    fun gpsAloneWhenNothingElse() {
        val e = Fusion.estimate(me, Fix(40.0001, -75.0, 5f, 0L), null, 0f, null)
        assertNotNull(e)
        assertEquals(EstimateSource.GpsOnly, e!!.source)
    }

    @Test
    fun noEstimateWithoutAnyData() {
        assertNull(Fusion.estimate(null, null, 10f, 1f, null))
    }

    @Test
    fun rangeTrendDetectsApproach() {
        val t = RangeTrend()
        listOf(30f, 29f, 27f, 25f, 22f, 20f, 18f, 16f).forEachIndexed { i, d -> t.add(i * 1000L, d) }
        assertEquals(Trend.Closer, t.trend())
    }

    @Test
    fun locationRoundTrips() {
        val decoded = Protocol.decode(
            Protocol.encode(Message.Location(400_000_000, -750_000_000, 12)),
        ) as Message.Location
        assertEquals(400_000_000, decoded.latE7)
        assertEquals(-750_000_000, decoded.lonE7)
        assertEquals(12, decoded.accuracyM)
        assertTrue(Protocol.encode(Message.Location(0, 0, 0)).size <= 20)
    }
}
