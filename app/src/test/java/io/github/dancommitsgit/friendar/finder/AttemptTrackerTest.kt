package io.github.dancommitsgit.friendar.finder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttemptTrackerTest {
    @Test
    fun hostAlternatesTheControllerEachAttemptUntilOneWorks() {
        val tracker = AttemptTracker()
        assertTrue(tracker.begin(0))
        assertFalse(tracker.begin(1))
        assertTrue(tracker.begin(2))
    }

    @Test
    fun attemptNumbersWrapToOneByte() {
        val tracker = AttemptTracker()
        tracker.begin(256)
        assertEquals(0, tracker.attempt)
        tracker.begin(257)
        assertEquals(1, tracker.attempt)
        assertTrue(tracker.isCurrent(1))
    }

    @Test
    fun hostKeepsTheAssignmentThatRanged() {
        val tracker = AttemptTracker()
        tracker.begin(1)
        tracker.applyRole(controller = false)
        assertTrue(tracker.measured(asHost = true))
        assertFalse(tracker.begin(2))
        assertFalse(tracker.begin(3))
    }

    @Test
    fun resetForgetsTheWorkingAssignmentAndStartsOver() {
        val tracker = AttemptTracker()
        tracker.begin(1)
        tracker.applyRole(controller = false)
        tracker.measured(asHost = true)
        tracker.reset()
        assertEquals(0, tracker.attempt)
        assertFalse(tracker.rangingStarted)
        assertTrue(tracker.begin(0))
        assertTrue(tracker.begin(2))
        assertFalse(tracker.begin(1))
    }

    @Test
    fun roleIsTakenOnceAndThenIgnoredWhenRepeated() {
        val tracker = AttemptTracker()
        assertEquals(true, tracker.adoptRole(forAttempt = 0, controllerIsHost = false))
        assertNull(tracker.adoptRole(forAttempt = 0, controllerIsHost = false))
        assertEquals(false, tracker.adoptRole(forAttempt = 1, controllerIsHost = true))
        assertEquals(1, tracker.attempt)
    }

    @Test
    fun joinIsAcceptedOnlyByTheControllerForTheCurrentAttempt() {
        val tracker = AttemptTracker()
        tracker.adoptRole(forAttempt = 3, controllerIsHost = false)
        tracker.applyRole(controller = true)
        assertTrue(tracker.acceptsJoin(3))
        assertFalse(tracker.acceptsJoin(2))
        assertFalse(tracker.acceptsStart(3))
    }

    @Test
    fun startIsAcceptedOnlyByTheControleeForTheCurrentAttempt() {
        val tracker = AttemptTracker()
        tracker.adoptRole(forAttempt = 3, controllerIsHost = true)
        tracker.applyRole(controller = false)
        assertTrue(tracker.acceptsStart(3))
        assertFalse(tracker.acceptsStart(4))
        assertFalse(tracker.acceptsJoin(3))
    }

    @Test
    fun roleResendStopsOnceRangingStartsOrTheAttemptMovesOn() {
        val tracker = AttemptTracker()
        tracker.begin(0)
        assertTrue(tracker.roleResendDue(0))
        tracker.begin(1)
        assertFalse(tracker.roleResendDue(0))
        assertTrue(tracker.roleResendDue(1))
        tracker.measured(asHost = true)
        assertFalse(tracker.roleResendDue(1))
    }

    @Test
    fun applyingARoleRestartsTheWaitForMeasurements() {
        val tracker = AttemptTracker()
        tracker.applyRole(controller = true)
        tracker.measured(asHost = true)
        assertTrue(tracker.rangingStarted)
        tracker.applyRole(controller = false)
        assertFalse(tracker.rangingStarted)
        assertFalse(tracker.iAmController)
    }

    @Test
    fun joinerAsksTheHostToRetryInsteadOfMovingOn() {
        val tracker = AttemptTracker()
        assertEquals(FailureAction.AskHostToRetry, tracker.failed(asJoiner = true))
        assertEquals(FailureAction.AskHostToRetry, tracker.failed(asJoiner = true))
    }

    @Test
    fun hostWarnsTheUserFromTheSecondFailureOn() {
        val tracker = AttemptTracker()
        assertEquals(FailureAction.TryNext, tracker.failed(asJoiner = false))
        assertEquals(FailureAction.TryNextAndWarn, tracker.failed(asJoiner = false))
        assertEquals(FailureAction.TryNextAndWarn, tracker.failed(asJoiner = false))
    }

    @Test
    fun aMeasurementClearsTheFailureCount() {
        val tracker = AttemptTracker()
        tracker.failed(asJoiner = false)
        tracker.measured(asHost = true)
        tracker.applyRole(controller = true)
        assertEquals(FailureAction.TryNext, tracker.failed(asJoiner = false))
    }

    @Test
    fun failuresAfterRangingStartedAreIgnored() {
        val tracker = AttemptTracker()
        tracker.measured(asHost = true)
        assertEquals(FailureAction.Ignore, tracker.failed(asJoiner = false))
        assertEquals(FailureAction.Ignore, tracker.failed(asJoiner = true))
    }

    @Test
    fun onlyTheFirstMeasurementOfAnAttemptCounts() {
        val tracker = AttemptTracker()
        assertTrue(tracker.measured(asHost = true))
        assertFalse(tracker.measured(asHost = true))
    }

    @Test
    fun staleValuesAreDroppedAfterThreeSeconds() {
        val fresh = staleActions(3_000L, hasDistance = true, isHost = false)
        assertFalse(fresh.dropDistance)
        val stale = staleActions(3_001L, hasDistance = true, isHost = false)
        assertTrue(stale.dropDistance)
        assertFalse(staleActions(10_000L, hasDistance = false, isHost = false).dropDistance)
    }

    @Test
    fun directionIsForgottenAfterThirtySeconds() {
        assertFalse(staleActions(30_000L, hasDistance = false, isHost = false).forgetDirection)
        assertTrue(staleActions(30_001L, hasDistance = false, isHost = false).forgetDirection)
    }

    @Test
    fun onlyTheHostRestartsAfterTwentySeconds() {
        assertFalse(staleActions(20_000L, hasDistance = false, isHost = true).restart)
        assertTrue(staleActions(20_001L, hasDistance = false, isHost = true).restart)
        assertFalse(staleActions(60_000L, hasDistance = false, isHost = false).restart)
    }
}
