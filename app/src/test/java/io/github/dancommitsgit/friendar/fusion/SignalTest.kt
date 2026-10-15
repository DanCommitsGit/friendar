package io.github.dancommitsgit.friendar.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalTest {
    private fun tracker(vararg dbm: Int): SignalTracker {
        val t = SignalTracker()
        dbm.forEachIndexed { i, v -> t.add(i * 1000L, v) }
        return t
    }

    @Test
    fun medianRejectsSingleSpike() {
        val t = tracker(-70, -70, -40, -70, -70)
        assertEquals(-70f, t.smoothedDbm()!!, 0.001f)
    }

    @Test
    fun trendNeedsHistory() {
        assertNull(tracker(-70, -68, -66).trend())
    }

    @Test
    fun risingSignalIsCloser() {
        val t = tracker(-85, -84, -82, -80, -77, -74, -72, -70)
        assertEquals(Trend.Closer, t.trend())
    }

    @Test
    fun fallingSignalIsFarther() {
        val t = tracker(-60, -62, -65, -68, -72, -75, -78, -80)
        assertEquals(Trend.Farther, t.trend())
    }

    @Test
    fun flatSignalIsSteady() {
        val t = tracker(-70, -71, -69, -70, -70, -71, -69, -70)
        assertEquals(Trend.Steady, t.trend())
    }

    @Test
    fun weakerSignalMeansFarther() {
        val near = SignalTracker.estimateMeters(-55f)
        val far = SignalTracker.estimateMeters(-85f)
        assertEquals(1f, near, 0.05f)
        assertTrue(far > 8f && far < 20f)
    }

    @Test
    fun rangeTrendDetectsApproach() {
        val t = RangeTrend()
        listOf(30f, 29f, 27f, 25f, 22f, 20f, 18f, 16f).forEachIndexed { i, d -> t.add(i * 1000L, d) }
        assertEquals(Trend.Closer, t.trend())
    }

    @Test
    fun distanceBands() {
        assertEquals("Under 10 ft", roughDistanceText(2f, metric = false))
        assertEquals("25–50 ft", roughDistanceText(10f, metric = false))
        assertEquals("100+ ft", roughDistanceText(40f, metric = false))
    }

    @Test
    fun metricDistanceBands() {
        assertEquals("Under 3 m", roughDistanceText(2f, metric = true))
        assertEquals("8–15 m", roughDistanceText(10f, metric = true))
        assertEquals("30+ m", roughDistanceText(40f, metric = true))
    }
}
