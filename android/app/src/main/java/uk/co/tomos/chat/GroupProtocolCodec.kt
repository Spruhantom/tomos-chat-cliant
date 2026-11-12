package uk.co.tomos.chat

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Cross-platform envelope for opaque MLS protocol messages.
 *
 * This codec is not encryption or proof of membership. Its bytes must travel
 * inside an authenticated Noise channel; only OpenMLS may interpret payloads.
 */
data class GroupProtocolFrame(
    val kind: Kind,
    val groupId: UUID,
    val epoch: Long,
    val payload: ByteArray
) {
    enum class Kind(val wireId: Int) {
        PROPOSAL(1), COMMIT(2), WELCOME(3), APPLICATION(4);

        companion object {
            fun fromWire(value: Int): Kind =
                entries.firstOrNull { it.wireId == value }
                    ?: throw IllegalArgumentException("Unsupported group protocol message kind.")
        }
    }
}

object GroupProtocolCodec {
    const val VERSION = 1
    const val HEADER_SIZE = 32
    const val MAX_PAYLOAD_BYTES = 16 * 1024 - HEADER_SIZE
    const val MAX_FRAME_BYTES = HEADER_SIZE + MAX_PAYLOAD_BYTES
    private val MAGIC = byteArrayOf(0x54, 0x47)

    fun encode(frame: GroupProtocolFrame): ByteArray {
        require(frame.payload.isNotEmpty()) { "Group protocol payload cannot be empty." }
        require(frame.payload.size <= MAX_PAYLOAD_BYTES) { "Group protocol payload exceeds limit." }
        return ByteBuffer.allocate(HEADER_SIZE + frame.payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)
            .put(VERSION.toByte())
            .put(frame.kind.wireId.toByte())
            .putLong(frame.groupId.mostSignificantBits)
            .putLong(frame.groupId.leastSignificantBits)
            .putLong(frame.epoch)
            .putInt(frame.payload.size)
            .put(frame.payload)
            .array()
    }

    fun decode(encoded: ByteArray): GroupProtocolFrame {
        require(encoded.size in HEADER_SIZE..MAX_FRAME_BYTES) { "Invalid group protocol frame size." }
        val input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        require(input.get() == MAGIC[0] && input.get() == MAGIC[1]) { "Invalid group protocol magic." }
        require((input.get().toInt() and 0xff) == VERSION) { "Unsupported group protocol version." }
        val kind = GroupProtocolFrame.Kind.fromWire(input.get().toInt() and 0xff)
        val groupId = UUID(input.long, input.long)
        val epoch = input.long
        val payloadLength = input.int
        require(payloadLength in 1..MAX_PAYLOAD_BYTES) { "Invalid group protocol payload length." }
        require(payloadLength == input.remaining()) { "Truncated frame or unexpected trailing bytes." }
        val payload = ByteArray(payloadLength)
        input.get(payload)
        return GroupProtocolFrame(kind, groupId, epoch, payload)
    }
}
