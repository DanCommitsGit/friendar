package io.github.dancommitsgit.friendar.finder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinderRulesTest {
    @Test
    fun explicitFlipModesIgnoreWhatWasLearned() {
        assertTrue(mirrorFor(FlipMode.Yes, auto = false))
        assertFalse(mirrorFor(FlipMode.No, auto = true))
    }

    @Test
    fun autoFlipFollowsWhatWasLearned() {
        assertTrue(mirrorFor(FlipMode.Auto, auto = true))
        assertFalse(mirrorFor(FlipMode.Auto, auto = false))
    }

    @Test
    fun autoFlipAssumesTheAzimuthIsReversedUntilLearned() {
        assertTrue(mirrorFor(FlipMode.Auto, auto = null))
    }

    @Test
    fun explicitUnitModesIgnoreTheCountry() {
        assertTrue(metricFor(UnitMode.Metric, country = "US"))
        assertFalse(metricFor(UnitMode.Imperial, country = "DE"))
    }

    @Test
    fun autoUnitsAreImperialOnlyWhereFeetAreUsed() {
        assertFalse(metricFor(UnitMode.Auto, country = "US"))
        assertFalse(metricFor(UnitMode.Auto, country = "LR"))
        assertFalse(metricFor(UnitMode.Auto, country = "MM"))
        assertTrue(metricFor(UnitMode.Auto, country = "GB"))
        assertTrue(metricFor(UnitMode.Auto, country = ""))
    }

    @Test
    fun waitingForTheLinkHasAHint() {
        assertEquals(Hint.Waiting, hintKindFor(FinderState(phase = Phase.Waiting)))
    }

    @Test
    fun bluetoothSignalWithoutAUwbDistanceHasABlockedHint() {
        val blocked = FinderState(phase = Phase.Ranging, uwbActive = true, distanceM = null, rssiDbm = -60)
        assertEquals(Hint.Blocked, hintKindFor(blocked))
    }

    @Test
    fun noHintWhileRangingNormally() {
        assertNull(hintKindFor(FinderState(phase = Phase.Ranging, uwbActive = true, distanceM = 4f, rssiDbm = -60)))
        assertNull(hintKindFor(FinderState(phase = Phase.Ranging, uwbActive = false, distanceM = null, rssiDbm = -60)))
        assertNull(hintKindFor(FinderState(phase = Phase.Ranging, uwbActive = true, distanceM = null, rssiDbm = null)))
    }

    @Test
    fun noHintWhenIdleOrFailed() {
        assertNull(hintKindFor(FinderState(phase = Phase.Idle)))
        assertNull(hintKindFor(FinderState(phase = Phase.Failed, uwbActive = true, rssiDbm = -60)))
    }
}
