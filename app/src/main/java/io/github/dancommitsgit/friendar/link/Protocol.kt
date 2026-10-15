package io.github.dancommitsgit.friendar.link

import io.github.dancommitsgit.friendar.finder.Role
import java.nio.ByteBuffer

/** Messages exchanged over the BLE link before and during UWB ranging. */
sealed interface Message {
    /** Assigns UWB roles for [attempt]; sent by the Bluetooth host. */
    class Role(val attempt: Int, val controllerIsHost: Boolean) : Message

    /** Sent by whichever phone is the UWB controlee, with its UWB address. */
    class Join(val attempt: Int, val uwbAddress: ByteArray) : Message

    /** Sent by the UWB controller with everything the controlee needs to start ranging. */
    class Start(
        val attempt: Int,
        val uwbAddress: ByteArray,
        val channel: Int,
        val preambleIndex: Int,
        val sessionId: Int,
        val sessionKey: ByteArray,
    ) : Message

    /** Asks the host to try the other role assignment. */
    class Retry(val attempt: Int) : Message

    /** Signal strength measured by the joiner, in dBm. */
    class Rssi(val dbm: Int) : Message

    /** Sent by whichever side has no usable UWB. */
    data object NoUwb : Message

    /** The sender's GPS position, in degrees scaled by 1e7, with its accuracy in meters. */
    class Location(val latE7: Int, val lonE7: Int, val accuracyM: Int) : Message

    /** Sent every second so the other side can tell a live link from one left over after a restart. */
    data object Heartbeat : Message
}

object Protocol {
    const val SESSION_KEY_SIZE = 8

    private const val TYPE_JOIN: Byte = 1
    private const val TYPE_START: Byte = 2
    private const val TYPE_RSSI: Byte = 3
    private const val TYPE_NO_UWB: Byte = 4
    private const val TYPE_ROLE: Byte = 5
    private const val TYPE_RETRY: Byte = 6
    private const val TYPE_LOCATION: Byte = 7
    private const val TYPE_HEARTBEAT: Byte = 8

    fun encode(message: Message): ByteArray = when (message) {
        is Message.Role -> byteArrayOf(
            TYPE_ROLE,
            message.attempt.toByte(),
            if (message.controllerIsHost) 1 else 0,
        )

        is Message.Join -> ByteBuffer.allocate(3 + message.uwbAddress.size).apply {
            put(TYPE_JOIN)
            put(message.attempt.toByte())
            put(message.uwbAddress.size.toByte())
            put(message.uwbAddress)
        }.array()

        is Message.Start -> ByteBuffer.allocate(
            3 + message.uwbAddress.size + 2 + 4 + SESSION_KEY_SIZE,
        ).apply {
            put(TYPE_START)
            put(message.attempt.toByte())
            put(message.uwbAddress.size.toByte())
            put(message.uwbAddress)
            put(message.channel.toByte())
            put(message.preambleIndex.toByte())
            putInt(message.sessionId)
            put(message.sessionKey)
        }.array()

        is Message.Retry -> byteArrayOf(TYPE_RETRY, message.attempt.toByte())
        is Message.Rssi -> byteArrayOf(TYPE_RSSI, message.dbm.toByte())
        Message.NoUwb -> byteArrayOf(TYPE_NO_UWB)
        Message.Heartbeat -> byteArrayOf(TYPE_HEARTBEAT)

        is Message.Location -> ByteBuffer.allocate(11).apply {
            put(TYPE_LOCATION)
            putInt(message.latE7)
            putInt(message.lonE7)
            putShort(message.accuracyM.coerceIn(0, 0xFFFF).toShort())
        }.array()
    }

    fun decode(bytes: ByteArray): Message? {
        if (bytes.isEmpty()) return null
        val buf = ByteBuffer.wrap(bytes)
        return try {
            when (buf.get()) {
                TYPE_ROLE -> Message.Role(buf.get().toInt() and 0xFF, buf.get().toInt() != 0)
                TYPE_JOIN -> Message.Join(buf.get().toInt() and 0xFF, buf.readSized())
                TYPE_START -> {
                    val attempt = buf.get().toInt() and 0xFF
                    val address = buf.readSized()
                    val channel = buf.get().toInt() and 0xFF
                    val preamble = buf.get().toInt() and 0xFF
                    val sessionId = buf.getInt()
                    val key = ByteArray(SESSION_KEY_SIZE).also { buf.get(it) }
                    Message.Start(attempt, address, channel, preamble, sessionId, key)
                }

                TYPE_RETRY -> Message.Retry(buf.get().toInt() and 0xFF)
                TYPE_RSSI -> Message.Rssi(buf.get().toInt())
                TYPE_NO_UWB -> Message.NoUwb
                TYPE_HEARTBEAT -> Message.Heartbeat
                TYPE_LOCATION -> Message.Location(
                    buf.getInt(),
                    buf.getInt(),
                    buf.getShort().toInt() and 0xFFFF,
                )

                else -> null
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun ByteBuffer.readSized(): ByteArray {
        val size = get().toInt() and 0xFF
        return ByteArray(size).also { get(it) }
    }
}
