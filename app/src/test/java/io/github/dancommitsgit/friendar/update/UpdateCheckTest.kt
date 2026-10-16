package io.github.dancommitsgit.friendar.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test
    fun newerPatchMinorAndMajorAreNewer() {
        assertTrue(isNewer("1.2.4", "1.2.3"))
        assertTrue(isNewer("1.3.0", "1.2.9"))
        assertTrue(isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun sameOrOlderIsNotNewer() {
        assertFalse(isNewer("1.2.3", "1.2.3"))
        assertFalse(isNewer("1.2.2", "1.2.3"))
        assertFalse(isNewer("1.9.9", "2.0.0"))
    }

    @Test
    fun comparesNumbersNotText() {
        assertTrue(isNewer("1.10.0", "1.9.0"))
    }

    @Test
    fun leadingVAndMissingPartsAreAllowed() {
        assertTrue(isNewer("v1.2.0", "1.1.0"))
        assertFalse(isNewer("1.0.0", "1.0"))
        assertTrue(isNewer("1.0.1", "1.0"))
    }

    @Test
    fun unreadableVersionsAreNeverNewer() {
        assertFalse(isNewer("nightly", "1.0.0"))
        assertFalse(isNewer("1.0.0", "dev"))
        assertFalse(isNewer("1.0.0-beta", "0.9.0"))
    }
}
