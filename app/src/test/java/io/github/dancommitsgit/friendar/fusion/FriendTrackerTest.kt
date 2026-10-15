package io.github.dancommitsgit.friendar.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FriendTrackerTest {
    private val metersPerDegree = 111_320.0
    private val compass = NorthReference(offsetRad = 0f, reliable = true)

    /** Phone held upright with its back facing [headingDeg], clockwise from north. */
    private fun upright(headingDeg: Float): FloatArray {
        val h = Math.toRadians(headingDeg.toDouble())
        val c = cos(h).toFloat()
        val s = sin(h).toFloat()
        return floatArrayOf(c, 0f, -s, -s, 0f, -c, 0f, 1f, 0f)
    }

    private fun fix(northM: Double, timeMs: Long, accuracyM: Float = 3f) =
        Fix(lat = northM / metersPerDegree, lon = 0.0, accuracyM = accuracyM, timeMs = timeMs)

    @Test
    fun nothingKnownGivesNoPositionAndNoArrow() {
        val tracker = FriendTracker()
        val position = tracker.estimate(1_000L, range = null, north = null)
        assertNull(position.friend)
        assertFalse(position.friendGps)
        assertNull(tracker.arrow(1_000L, upright(0f)).arrowDeg)
    }

    @Test
    fun uwbAngleDrivesTheArrowOnceEnoughReadingsAgree() {
        val tracker = FriendTracker()
        val rotation = upright(0f)
        repeat(3) { tracker.addUwbReading(30f, 0f, rotation, mirror = false, distanceTrust = 1f, timeMs = 1_000L) }
        val arrow = tracker.arrow(1_000L, rotation)
        assertEquals(30f, arrow.arrowDeg!!, 0.5f)
        assertEquals(DirectionSource.Uwb, arrow.source)
        assertTrue(arrow.directionLive)
    }

    @Test
    fun mirrorNegatesTheReportedAzimuth() {
        val tracker = FriendTracker()
        val rotation = upright(0f)
        repeat(3) { tracker.addUwbReading(-30f, 0f, rotation, mirror = true, distanceTrust = 1f, timeMs = 1_000L) }
        assertEquals(30f, tracker.arrow(1_000L, rotation).arrowDeg!!, 0.5f)
    }

    @Test
    fun forgettingDirectionClearsTheArrow() {
        val tracker = FriendTracker()
        val rotation = upright(0f)
        repeat(3) { tracker.addUwbReading(30f, 0f, rotation, mirror = false, distanceTrust = 1f, timeMs = 1_000L) }
        tracker.forgetDirection()
        assertNull(tracker.arrow(1_000L, rotation).arrowDeg)
    }

    @Test
    fun rangeWithFixesAndNoBearingScalesTheGpsOffset() {
        val tracker = FriendTracker()
        tracker.onMyFix(fix(0.0, 900L), declinationDeg = 0f)
        tracker.onFriendFix(fix(100.0, 900L))
        val position = tracker.estimate(1_000L, Range(100f, 0.5f, RangeSource.Uwb), north = null)
        val friend = position.friend!!
        assertEquals(EstimateSource.RangeAndGps, friend.source)
        assertEquals(100f, friend.northM, 0.5f)
        assertTrue(position.friendGps)
    }

    @Test
    fun uwbDirectionAndCompassPlaceTheFriendFromRangeAndBearing() {
        val tracker = FriendTracker()
        val rotation = upright(0f)
        tracker.onMyFix(fix(0.0, 900L), declinationDeg = 0f)
        tracker.onFriendFix(fix(100.0, 900L))
        repeat(3) { tracker.addUwbReading(0f, 0f, rotation, mirror = false, distanceTrust = 1f, timeMs = 1_000L) }
        val position = tracker.estimate(1_000L, Range(100f, 0.5f, RangeSource.Uwb), compass)
        val friend = position.friend!!
        assertEquals(EstimateSource.RangeAndBearing, friend.source)
        assertEquals(0f, friend.eastM, 1f)
        assertEquals(100f, friend.northM, 1f)
        assertEquals("north: compass, ±20°", position.frameInfo)
    }

    @Test
    fun staleFixesAreIgnored() {
        val tracker = FriendTracker()
        tracker.onMyFix(fix(0.0, 0L), declinationDeg = 0f)
        tracker.onFriendFix(fix(100.0, 0L))
        val position = tracker.estimate(120_000L, range = null, north = null)
        assertNull(position.friend)
        assertFalse(position.friendGps)
    }

    @Test
    fun reversedAzimuthIsDetectedFromGpsAndCompass() {
        val verdict = flipVerdictFor(reportedAzimuthDeg = 40f)
        assertNotNull(verdict)
        assertTrue(verdict!!.reversed)
    }

    @Test
    fun correctAzimuthIsConfirmedFromGpsAndCompass() {
        val verdict = flipVerdictFor(reportedAzimuthDeg = -40f)
        assertNotNull(verdict)
        assertFalse(verdict!!.reversed)
    }

    /** The phone faces 40 degrees east of the friend, who is 20 m due north, so the true azimuth is -40. */
    private fun flipVerdictFor(reportedAzimuthDeg: Float): FlipVerdict? {
        val tracker = FriendTracker()
        val rotation = upright(40f)
        tracker.onMyFix(fix(0.0, 0L), declinationDeg = 0f)
        tracker.onFriendFix(fix(20.0, 0L))
        var verdict: FlipVerdict? = null
        for (step in 0 until 12) {
            val now = 100L * step
            tracker.addUwbReading(reportedAzimuthDeg, 0f, rotation, mirror = false, distanceTrust = 1f, timeMs = now)
            verdict = tracker.checkFlip(now, uwbDistanceM = 20f, north = compass, learned = null) ?: verdict
        }
        return verdict
    }

    @Test
    fun verdictMatchingWhatIsAlreadyLearnedIsNotReported() {
        val tracker = FriendTracker()
        val rotation = upright(40f)
        tracker.onMyFix(fix(0.0, 0L), declinationDeg = 0f)
        tracker.onFriendFix(fix(20.0, 0L))
        repeat(12) { step ->
            val now = 100L * step
            tracker.addUwbReading(40f, 0f, rotation, mirror = false, distanceTrust = 1f, timeMs = now)
            assertNull(tracker.checkFlip(now, uwbDistanceM = 20f, north = compass, learned = true))
        }
    }
}
