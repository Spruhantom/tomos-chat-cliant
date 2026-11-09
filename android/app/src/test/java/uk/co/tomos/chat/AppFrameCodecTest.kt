package uk.co.tomos.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class AppFrameCodecTest {
    @Test fun roundTripsTextFrame() {
        val id = UUID.fromString("9d5a4f2a-0e54-4f77-8f5b-8f39c0f5a0a1")
        val payload = "hello securely".toByteArray()
        val decoded = AppFrameCodec.decode(AppFrameCodec.encode(
            EncryptedAppFrame(EncryptedAppFrame.Type.TEXT, id, payload)
        ))
        assertEquals(EncryptedAppFrame.Type.TEXT, decoded.type)
        assertEquals(id, decoded.messageId)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test fun rejectsOversizedPayloadOnEncode() {
        assertThrows(IllegalArgumentException::class.java) {
            AppFrameCodec.encode(EncryptedAppFrame(
                EncryptedAppFrame.Type.TEXT, UUID.randomUUID(),
                ByteArray(AppFrameCodec.MAX_PAYLOAD_BYTES + 1)
            ))
        }
    }

    @Test fun rejectsTruncatedAndTrailingData() {
        val frame = AppFrameCodec.encode(EncryptedAppFrame(
            EncryptedAppFrame.Type.TEXT, UUID.randomUUID(), byteArrayOf(1, 2, 3)
        ))
        assertThrows(IllegalArgumentException::class.java) {
            AppFrameCodec.decode(frame.copyOf(frame.size - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppFrameCodec.decode(frame + byteArrayOf(0))
        }
    }

    @Test fun rejectsUnsupportedVersionAndType() {
        val base = AppFrameCodec.encode(EncryptedAppFrame(
            EncryptedAppFrame.Type.TEXT, UUID.randomUUID(), byteArrayOf()
        ))
        val badVersion = base.copyOf().also { it[0] = 2 }
        val badType = base.copyOf().also { it[1] = 99 }
        assertThrows(IllegalArgumentException::class.java) { AppFrameCodec.decode(badVersion) }
        assertThrows(IllegalArgumentException::class.java) { AppFrameCodec.decode(badType) }
    }

    @Test fun rejectsNegativeAndMismatchedLengths() {
        val frame = AppFrameCodec.encode(EncryptedAppFrame(
            EncryptedAppFrame.Type.TEXT, UUID.randomUUID(), byteArrayOf(7)
        ))
        val negative = frame.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(18, -1)
        }
        val mismatch = frame.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(18, 2)
        }
        assertThrows(IllegalArgumentException::class.java) { AppFrameCodec.decode(negative) }
        assertThrows(IllegalArgumentException::class.java) { AppFrameCodec.decode(mismatch) }
    }
}
