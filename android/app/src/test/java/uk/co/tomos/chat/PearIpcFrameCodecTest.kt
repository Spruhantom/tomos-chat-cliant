package uk.co.tomos.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PearIpcFrameCodecTest {
    @Test
    fun decodesOneFrameAcrossFragmentedReads() {
        val body = """{"version":1,"type":"backend:ready"}""".encodeToByteArray()
        val encoded = PearIpcFrameCodec.encode(body)
        val decoder = PearIpcFrameDecoder()

        assertEquals(emptyList<ByteArray>(), decoder.push(encoded.copyOfRange(0, 2)))
        val decoded = decoder.push(encoded.copyOfRange(2, encoded.size))

        assertEquals(1, decoded.size)
        assertArrayEquals(body, decoded.single())
    }

    @Test
    fun decodesSeveralFramesFromOneRead() {
        val first = "first".encodeToByteArray()
        val second = "second".encodeToByteArray()
        val decoder = PearIpcFrameDecoder()

        val decoded = decoder.push(PearIpcFrameCodec.encode(first) + PearIpcFrameCodec.encode(second))

        assertEquals(2, decoded.size)
        assertArrayEquals(first, decoded[0])
        assertArrayEquals(second, decoded[1])
    }

    @Test
    fun rejectsInvalidAndOversizedLengths() {
        val decoder = PearIpcFrameDecoder()
        assertThrows(IllegalArgumentException::class.java) {
            decoder.push(byteArrayOf(0, 0, 0, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PearIpcFrameCodec.encode(ByteArray(PearIpcFrameCodec.MAX_FRAME_BYTES + 1))
        }
    }
}
