package uk.co.tomos.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class GroupProtocolCodecTest {
    private fun sample() = GroupProtocolFrame(
        kind = GroupProtocolFrame.Kind.APPLICATION,
        groupId = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
        epoch = 7,
        payload = byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())
    )

    @Test fun roundTripsGroupApplicationFrame() {
        val original = sample()
        val decoded = GroupProtocolCodec.decode(GroupProtocolCodec.encode(original))
        assertEquals(original.kind, decoded.kind)
        assertEquals(original.groupId, decoded.groupId)
        assertEquals(original.epoch, decoded.epoch)
        assertArrayEquals(original.payload, decoded.payload)
    }

    @Test fun matchesRustGoldenWireVector() {
        val expected = byteArrayOf(
            0x54, 0x47, 0x01, 0x04,
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
            0x88.toByte(), 0x99.toByte(), 0xaa.toByte(), 0xbb.toByte(),
            0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte(),
            0, 0, 0, 0, 0, 0, 0, 7,
            0, 0, 0, 4, 0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()
        )
        assertArrayEquals(expected, GroupProtocolCodec.encode(sample()))
        assertEquals(sample().kind, GroupProtocolCodec.decode(expected).kind)
    }

    @Test fun rejectsBadMagicVersionAndKind() {
        val valid = GroupProtocolCodec.encode(sample())
        assertThrows(IllegalArgumentException::class.java) {
            GroupProtocolCodec.decode(valid.copyOf().also { it[0] = 0 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupProtocolCodec.decode(valid.copyOf().also { it[2] = 2 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupProtocolCodec.decode(valid.copyOf().also { it[3] = 99 })
        }
    }

    @Test fun groupEnvelopeFitsInsideEncryptedApplicationControlFrame() {
        val group = sample()
        val payload = GroupProtocolCodec.encode(group)
        val outer = EncryptedAppFrame(
            EncryptedAppFrame.Type.GROUP_CONTROL,
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
            payload
        )
        val decodedOuter = AppFrameCodec.decode(AppFrameCodec.encode(outer))
        assertEquals(EncryptedAppFrame.Type.GROUP_CONTROL, decodedOuter.type)
        val decodedGroup = GroupProtocolCodec.decode(decodedOuter.payload)
        assertEquals(group.groupId, decodedGroup.groupId)
        assertEquals(group.epoch, decodedGroup.epoch)
        assertArrayEquals(group.payload, decodedGroup.payload)
    }

    @Test fun rejectsEmptyOversizedAndMismatchedPayloads() {
        assertThrows(IllegalArgumentException::class.java) {
            GroupProtocolCodec.encode(sample().copy(payload = byteArrayOf()))
        }
        val valid = GroupProtocolCodec.encode(sample())
        val badLength = valid.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(28, 9)
        }
        assertThrows(IllegalArgumentException::class.java) { GroupProtocolCodec.decode(badLength) }
        assertThrows(IllegalArgumentException::class.java) {
            GroupProtocolCodec.decode(ByteArray(GroupProtocolCodec.MAX_FRAME_BYTES + 1))
        }
    }
}
