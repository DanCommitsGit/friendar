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
    private val poorCompass = NorthReference(offsetRad = 0f, reliable = false)
    private val flat = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** Phone held upright with its back facing [headingDeg], clockwise from north. */
    private fun upright(headingDeg: Float): FloatArray {
        val h = Math.toRadians(headingDeg.toDouble())
        val c = cos(h).toFloat()
        val s = sin(h).toFloat()
        return floatArrayOf(c, 0f, -s, -s, 0f, -c, 0f, 1f, 0f)
    }

    private fun fix(northM: Double, timeMs: Long, accuracyM: Float = 3f) =
        Fix(lat = northM / metersPerDegree, lon = 0.0, accuracyM = accuracyM, timeMs = timeMs)

    private fun fixEast(eastM: Double, timeMs: Long, accuracyM: Float = 3f) =
        Fix(lat = 0.0, lon = eastM / metersPerDegree, accuracyM = accuracyM, timeMs = timeMs)

    private fun FriendTracker.readings(count: Int, azimuthDeg: Float, elevationDeg: Float = 0f, rotation: FloatArray, timeMs: Long) {
        repeat(count) {
            addUwbReading(azimuthDeg, elevationDeg, rotation, mirror = false, distanceTrust = 1f, timeMs = timeMs)
        }
    }

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
        tracker.readings(3, azimuthDeg = 30f, rotation = rotation, timeMs = 1_000L)
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
        tracker.readings(3, azimuthDeg = 30f, rotation = rotation, timeMs = 1_000L)
        tracker.forgetDirection()
        assertNull(tracker.arrow(1_000L, rotation).arrowDeg)
    }

    @Test
    fun clearForgetsDirectionFixesAndFrame() {
        val tracker = FriendTracker()
        val rotation = upright(0f)
        tracker.onMyFix(fix(0.0, 900L), declinationDeg = 0f)
        tracker.onFriendFix(fix(100.0, 900L))
        tracker.readings(3, azimuthDeg = 0f, rotation = rotation, timeMs = 1_000L)
        tracker.estimate(1_000L, Range(100f, 0.5f, RangeSource.Uwb), compass)
        tracker.clear()
        val position = tracker.estimate(1_000L, range = null, north = compass)
        assertNull(tracker.myFix)
        assertNull(position.friend)
        assertFalse(position.friendGps)
        val arrow = tracker.arrow(1_000L, rotation)
        assertNull(arrow.arrowDeg)
        assertFalse(arrow.directionLive)
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
        tracker.readings(3, azimuthDeg = 0f, rotation = rotation, timeMs = 1_000L)
        val position = tracker.estimate(1_000L, Range(100f, 0.5f, RangeSource.Uwb), compass)
        val friend = position.friend!!
        assertEquals(EstimateSource.RangeAndBearing, friend.source)
        assertEquals(0f, friend.eastM, 1f)
        assertEquals(100f, friend.northM, 1f)
        assertEquals("north: compass, ±20°", position.frameInfo)
    }

    @Test
    fun declinationShiftsTheCompassTowardTrueNorth() {
        val tracker = FriendTracker()
        tracker.onMyFix(fix(0.0, 900L), declinationDeg = 10f)
        tracker.estimate(1_000L, range = null, north = compass)
        // Magnetic north lies 10 degrees east of true north.
        assertEquals(10f, tracker.arrow(1_000L, upright(0f)).headingDeg!!, 0.5f)
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
    fun headingFollowsWhereThePhoneIsFacing() {
        val tracker = FriendTracker()
        assertNull(tracker.arrow(1_000L, upright(40f)).headingDeg)
        tracker.estimate(1_000L, range = null, north = compass)
        val standing = tracker.arrow(1_000L, upright(40f))
        assertEquals(40f, standing.headingDeg!!, 0.5f)
        assertFalse(standing.flat)
        val lying = tracker.arrow(1_000L, flat)
        assertEquals(0f, lying.headingDeg!!, 0.5f)
        assertTrue(lying.flat)
    }

    @Test
    fun directionGoesStaleAfterTwoSecondsButTheArrowStays() {
        val tracker = FriendTracker()
        val rotation = upright(0f)
        tracker.readings(3, azimuthDeg = 0f, rotation = rotation, timeMs = 1_000L)
        assertTrue(tracker.arrow(1_500L, rotation).directionLive)
        val later = tracker.arrow(3_500L, rotation)
        assertFalse(later.directionLive)
        assertNotNull(later.arrowDeg)
    }

    @Test
    fun elevationIsHeldForTenSecondsThenDropped() {
        val tracker = FriendTracker()
        val rotation = upright(0f)
        tracker.readings(3, azimuthDeg = 0f, elevationDeg = 30f, rotation = rotation, timeMs = 1_000L)
        assertEquals(30f, tracker.arrow(1_000L, rotation).elevationDeg!!, 1f)
        assertEquals(30f, tracker.arrow(5_000L, rotation).elevationDeg!!, 1f)
        assertEquals(0f, tracker.arrow(12_000L, rotation).elevationDeg!!, 1f)
    }

    /** Friend 40 m due north, the phone facing north, with UWB reading dead ahead at [uwbAtMs]. */
    private fun sourceFor(north: NorthReference, atMs: Long): DirectionSource? {
        val tracker = FriendTracker()
        val rotation = upright(0f)
        tracker.onMyFix(fix(0.0, 900L), declinationDeg = 0f)
        tracker.onFriendFix(fix(40.0, 900L))
        tracker.readings(3, azimuthDeg = 0f, rotation = rotation, timeMs = 1_000L)
        tracker.estimate(atMs, Range(40f, 0.5f, RangeSource.Uwb), north)
        return tracker.arrow(atMs, rotation).source
    }

    @Test
    fun arrowNamesBothSourcesWhenTheyAreComparablyCertain() {
        assertEquals(DirectionSource.Both, sourceFor(compass, atMs = 1_000L))
    }

    @Test
    fun arrowNamesUwbWhenGpsIsMuchLessCertain() {
        assertEquals(DirectionSource.Uwb, sourceFor(poorCompass, atMs = 1_000L))
    }

    @Test
    fun arrowFallsBackToGpsOnceUwbHasAgedOut() {
        assertEquals(DirectionSource.Gps, sourceFor(compass, atMs = 20_000L))
    }

    @Test
    fun calibrationLetsGpsPlaceTheArrowWithoutACompass() {
        val tracker = FriendTracker()
        val rotation = upright(0f)
        tracker.onMyFix(fix(0.0, 900L), declinationDeg = 0f)
        tracker.onFriendFix(fixEast(20.0, 900L))
        repeat(5) {
            tracker.readings(1, azimuthDeg = 0f, rotation = rotation, timeMs = 1_000L)
            tracker.calibrate(1_000L, uwbDistanceM = 20f)
        }
        tracker.forgetDirection()
        val position = tracker.estimate(1_000L, Range(20f, 0.5f, RangeSource.Uwb), north = null)
        assertEquals("north: UWB/GPS, ±8°", position.frameInfo)
        val arrow = tracker.arrow(1_000L, rotation)
        assertEquals(DirectionSource.Gps, arrow.source)
        assertEquals(0f, arrow.arrowDeg!!, 1f)
    }

    @Test
    fun calibrationAndCompassAreCombinedWhenTheyAgree() {
        val tracker = FriendTracker()
        val rotation = upright(0f)
        tracker.onMyFix(fix(0.0, 900L), declinationDeg = 0f)
        tracker.onFriendFix(fix(20.0, 900L))
        repeat(5) {
            tracker.readings(1, azimuthDeg = 0f, rotation = rotation, timeMs = 1_000L)
            tracker.calibrate(1_000L, uwbDistanceM = 20f)
        }
        val info = tracker.estimate(1_000L, Range(20f, 0.5f, RangeSource.Uwb), compass).frameInfo
        assertTrue(info, info!!.startsWith("north: compass + UWB/GPS"))
    }

    @Test
    fun mirrorChangeDiscardsWhatCalibrationLearned() {
        val tracker = FriendTracker()
        val rotation = upright(0f)
        tracker.onMyFix(fix(0.0, 900L), declinationDeg = 0f)
        tracker.onFriendFix(fixEast(20.0, 900L))
        repeat(5) {
            tracker.readings(1, azimuthDeg = 0f, rotation = rotation, timeMs = 1_000L)
            tracker.calibrate(1_000L, uwbDistanceM = 20f)
        }
        tracker.onMirrorChanged()
        assertNull(tracker.estimate(1_000L, range = null, north = null).frameInfo)
        assertNull(tracker.arrow(1_000L, rotation).arrowDeg)
    }

    /** Frame information left after five calibration attempts under the given conditions. */
    private fun calibratedInfo(
        accuracyM: Float = 3f,
        uwbDistanceM: Float? = 20f,
        calibrateAtMs: Long = 1_000L,
    ): String? {
        val tracker = FriendTracker()
        val rotation = upright(0f)
        tracker.onMyFix(fix(0.0, 900L, accuracyM), declinationDeg = 0f)
        tracker.onFriendFix(fixEast(20.0, 900L, accuracyM))
        repeat(5) {
            tracker.readings(1, azimuthDeg = 0f, rotation = rotation, timeMs = 1_000L)
            tracker.calibrate(calibrateAtMs, uwbDistanceM)
        }
        return tracker.estimate(1_000L, range = null, north = null).frameInfo
    }

    @Test
    fun calibrationLearnsWhenGpsIsGoodAndRangeIsLongEnough() {
        assertEquals("north: UWB/GPS, ±8°", calibratedInfo())
    }

    @Test
    fun calibrationSkipsInaccurateGps() {
        assertNull(calibratedInfo(accuracyM = 20f))
    }

    @Test
    fun calibrationSkipsRangesShortComparedWithGpsError() {
        assertNull(calibratedInfo(uwbDistanceM = 10f))
    }

    @Test
    fun calibrationSkipsWithoutAUwbDistance() {
        assertNull(calibratedInfo(uwbDistanceM = null))
    }

    @Test
    fun calibrationSkipsOnceTheDirectionHasGoneStale() {
        assertNull(calibratedInfo(calibrateAtMs = 4_000L))
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

    @Test
    fun flipIsNotJudgedWithoutAReliableCompass() {
        assertNull(flipVerdictFor(reportedAzimuthDeg = 40f, north = poorCompass))
        assertNull(flipVerdictFor(reportedAzimuthDeg = 40f, north = null))
    }

    @Test
    fun flipIsNotJudgedWithoutAUwbDistance() {
        assertNull(flipVerdictFor(reportedAzimuthDeg = 40f, uwbDistanceM = null))
    }

    @Test
    fun flipIsNotJudgedFromInaccurateGpsOrAShortRange() {
        assertNull(flipVerdictFor(reportedAzimuthDeg = 40f, accuracyM = 20f))
        assertNull(flipVerdictFor(reportedAzimuthDeg = 40f, uwbDistanceM = 10f))
    }

    @Test
    fun flipIsNotJudgedFromAReadingThatHasGoneStale() {
        assertNull(flipVerdictFor(reportedAzimuthDeg = 40f, checkDelayMs = 2_000L))
    }

    @Test
    fun flipIsNotJudgedFromAReadingFarOffTheBack() {
        assertNull(flipVerdictFor(reportedAzimuthDeg = 85f))
    }

    /** The phone faces 40 degrees east of the friend, who is 20 m due north, so the true azimuth is -40. */
    private fun flipVerdictFor(
        reportedAzimuthDeg: Float,
        north: NorthReference? = compass,
        uwbDistanceM: Float? = 20f,
        accuracyM: Float = 3f,
        checkDelayMs: Long = 0L,
    ): FlipVerdict? {
        val tracker = FriendTracker()
        val rotation = upright(40f)
        tracker.onMyFix(fix(0.0, 0L, accuracyM), declinationDeg = 0f)
        tracker.onFriendFix(fix(20.0, 0L, accuracyM))
        var verdict: FlipVerdict? = null
        for (step in 0 until 12) {
            val now = 100L * step
            tracker.addUwbReading(reportedAzimuthDeg, 0f, rotation, mirror = false, distanceTrust = 1f, timeMs = now)
            verdict = tracker.checkFlip(now + checkDelayMs, uwbDistanceM, north, learned = null) ?: verdict
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
