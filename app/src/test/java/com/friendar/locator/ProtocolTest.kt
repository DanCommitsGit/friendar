package com.friendar.locator

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun joinRoundTrips() {
        val address = byteArrayOf(0x12, 0x34)
        val decoded = Protocol.decode(Protocol.encode(Message.Join(2, address))) as Message.Join
        assertEquals(2, decoded.attempt)
        assertArrayEquals(address, decoded.uwbAddress)
    }

    @Test
    fun startRoundTripsAndFitsDefaultPayload() {
        val key = ByteArray(Protocol.SESSION_KEY_SIZE) { it.toByte() }
        val start = Message.Start(1, byteArrayOf(0x0A, 0x0B), 9, 12, -123456, key)
        val bytes = Protocol.encode(start)
        assertTrue("fits in a default 20-byte BLE payload, was ${bytes.size}", bytes.size <= 20)
        val decoded = Protocol.decode(bytes) as Message.Start
        assertEquals(1, decoded.attempt)
        assertArrayEquals(start.uwbAddress, decoded.uwbAddress)
        assertEquals(9, decoded.channel)
        assertEquals(12, decoded.preambleIndex)
        assertEquals(-123456, decoded.sessionId)
        assertArrayEquals(key, decoded.sessionKey)
    }

    @Test
    fun roleRoundTrips() {
        val decoded = Protocol.decode(Protocol.encode(Message.Role(3, false))) as Message.Role
        assertEquals(3, decoded.attempt)
        assertEquals(false, decoded.controllerIsHost)
        val other = Protocol.decode(Protocol.encode(Message.Role(0, true))) as Message.Role
        assertEquals(true, other.controllerIsHost)
    }

    @Test
    fun retryRoundTrips() {
        val decoded = Protocol.decode(Protocol.encode(Message.Retry(2))) as Message.Retry
        assertEquals(2, decoded.attempt)
    }

    @Test
    fun rssiKeepsSign() {
        val decoded = Protocol.decode(Protocol.encode(Message.Rssi(-67))) as Message.Rssi
        assertEquals(-67, decoded.dbm)
    }

    @Test
    fun noUwbRoundTrips() {
        assertEquals(Message.NoUwb, Protocol.decode(Protocol.encode(Message.NoUwb)))
        assertEquals(Message.Heartbeat, Protocol.decode(Protocol.encode(Message.Heartbeat)))
    }

    @Test
    fun malformedInputIsRejected() {
        assertNull(Protocol.decode(byteArrayOf()))
        assertNull(Protocol.decode(byteArrayOf(99)))
        assertNull(Protocol.decode(byteArrayOf(2, 0, 2, 1)))
    }
}
