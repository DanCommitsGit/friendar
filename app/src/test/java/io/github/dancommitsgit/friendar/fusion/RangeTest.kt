package io.github.dancommitsgit.friendar.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RangeTest {
    @Test
    fun uwbWinsOverEveryOtherSource() {
        val range = bestRange(uwbM = 3f, wifiM = 5f, wifiStdM = 1f, signalM = 12f)!!
        assertEquals(RangeSource.Uwb, range.source)
        assertEquals(3f, range.meters, 0f)
    }

    @Test
    fun wifiIsNextAndItsSigmaHasAFloor() {
        val range = bestRange(uwbM = null, wifiM = 5f, wifiStdM = 0.3f, signalM = 12f)!!
        assertEquals(RangeSource.Wifi, range.source)
        assertEquals(2f, range.sigmaM, 0f)
    }

    @Test
    fun wifiSigmaFollowsLargerReportedDeviation() {
        val range = bestRange(uwbM = null, wifiM = 5f, wifiStdM = 3.5f, signalM = null)!!
        assertEquals(3.5f, range.sigmaM, 0f)
    }

    @Test
    fun signalStrengthIsTheLastResortWithHalfItsDistanceAsSigma() {
        val range = bestRange(uwbM = null, wifiM = null, wifiStdM = 0f, signalM = 12f)!!
        assertEquals(RangeSource.Bluetooth, range.source)
        assertEquals(6f, range.sigmaM, 0f)
    }

    @Test
    fun nothingMeasuredGivesNoRange() {
        assertNull(bestRange(uwbM = null, wifiM = null, wifiStdM = 0f, signalM = null))
    }
}
