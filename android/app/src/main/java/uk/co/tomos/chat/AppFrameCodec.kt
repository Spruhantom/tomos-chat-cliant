package uk.co.tomos.chat

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Bounded application envelope. Callers must pass the encoded bytes to
 * NoisePeerSession.encrypt before any transport write; this class is not
 * encryption and must never be connected directly to a socket.
 */
data class EncryptedAppFrame(
    val type: Type,
    val messageId: UUID,
    val payload: ByteArray
) {
    enum class Type(val wireId: Int) {
        TEXT(1),
        GROUP_CONTROL(2),
        VOICE_SIGNAL(3),
        GROUP_VOICE_CONTROL(4),
        DELIVERY_ACK(5);

        companion object {
            fun fromWire(value: Int): Type =
                entries.firstOrNull { it.wireId == value }
                    ?: throw IllegalArgumentException("Unsupported application frame type.")
        }
    }
}

object AppFrameCodec {
    private const val VERSION = 1
    private const val HEADER_SIZE = 22 // version + type + UUID + payload length
    const val MAX_PAYLOAD_BYTES = 16 * 1024
    const val MAX_FRAME_BYTES = HEADER_SIZE + MAX_PAYLOAD_BYTES

    fun encode(frame: EncryptedAppFrame): ByteArray {
        require(frame.payload.size <= MAX_PAYLOAD_BYTES) { "Application payload exceeds limit." }
        val output = ByteArrayOutputStream(HEADER_SIZE + frame.payload.size)
        DataOutputStream(output).use { data ->
            data.writeByte(VERSION)
            data.writeByte(frame.type.wireId)
            data.writeLong(frame.messageId.mostSignificantBits)
            data.writeLong(frame.messageId.leastSignificantBits)
            data.writeInt(frame.payload.size)
            data.write(frame.payload)
        }
        return output.toByteArray()
    }

    fun decode(encoded: ByteArray): EncryptedAppFrame {
        require(encoded.size in HEADER_SIZE..MAX_FRAME_BYTES) { "Invalid application frame size." }
        val input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        val version = input.get().toInt() and 0xff
        require(version == VERSION) { "Unsupported application protocol version." }
        val type = EncryptedAppFrame.Type.fromWire(input.get().toInt() and 0xff)
        val id = UUID(input.long, input.long)
        val payloadLength = input.int
        require(payloadLength in 0..MAX_PAYLOAD_BYTES) { "Invalid application payload length." }
        require(payloadLength == input.remaining()) { "Truncated frame or unexpected trailing bytes." }
        val payload = ByteArray(payloadLength)
        input.get(payload)
        return EncryptedAppFrame(type, id, payload)
    }
}
