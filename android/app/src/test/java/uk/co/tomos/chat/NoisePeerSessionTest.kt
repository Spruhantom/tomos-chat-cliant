package uk.co.tomos.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NoisePeerSessionTest {
    private class FakeBackend : NoiseSessionBackend {
        var finished = false
        var closedHandles = mutableListOf<Long>()
        var lastHandle: Long? = null

        override fun writeHandshake(handle: Long, payload: ByteArray): ByteArray {
            lastHandle = handle
            return payload.reversedArray()
        }

        override fun readHandshake(handle: Long, message: ByteArray): ByteArray {
            lastHandle = handle
            return message.reversedArray()
        }

        override fun finishHandshake(handle: Long) {
            lastHandle = handle
            finished = true
        }

        override fun encrypt(handle: Long, plaintext: ByteArray): ByteArray {
            lastHandle = handle
            return plaintext.reversedArray()
        }

        override fun decrypt(handle: Long, ciphertext: ByteArray): ByteArray {
            lastHandle = handle
            return ciphertext.reversedArray()
        }

        override fun closeSession(handle: Long) {
            closedHandles += handle
        }
    }

    @Test
    fun encryptionIsBlockedUntilHandshakeFinishes() {
        val backend = FakeBackend()
        val session = NoisePeerSession(41L, backend)

        assertThrows(IllegalStateException::class.java) { session.encrypt(byteArrayOf(1, 2)) }
        assertThrows(IllegalStateException::class.java) { session.decrypt(byteArrayOf(2, 1)) }

        session.finishHandshake()
        assertTrue(backend.finished)
        assertArrayEquals(byteArrayOf(2, 1), session.encrypt(byteArrayOf(1, 2)))
        assertArrayEquals(byteArrayOf(1, 2), session.decrypt(byteArrayOf(2, 1)))
        assertEquals(41L, backend.lastHandle)
    }

    @Test
    fun handshakeCallsAreForwardedToOwnedHandle() {
        val backend = FakeBackend()
        val session = NoisePeerSession(7L, backend)

        assertArrayEquals(byteArrayOf(3, 2, 1), session.writeHandshake(byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(3, 2, 1), session.readHandshake(byteArrayOf(1, 2, 3)))
        assertEquals(7L, backend.lastHandle)
    }

    @Test
    fun closeIsIdempotentAndDisablesFurtherOperations() {
        val backend = FakeBackend()
        val session = NoisePeerSession(99L, backend)
        session.finishHandshake()

        session.close()
        session.close()

        assertEquals(listOf(99L), backend.closedHandles)
        assertThrows(IllegalStateException::class.java) { session.writeHandshake() }
        assertThrows(IllegalStateException::class.java) { session.finishHandshake() }
        assertThrows(IllegalStateException::class.java) { session.encrypt(byteArrayOf(1)) }
    }

    @Test
    fun closeBeforeHandshakeDoesNotEnableTransport() {
        val backend = FakeBackend()
        val session = NoisePeerSession(5L, backend)

        session.close()

        assertFalse(backend.finished)
        assertTrue(backend.closedHandles.contains(5L))
        assertThrows(IllegalStateException::class.java) { session.decrypt(byteArrayOf(1)) }
    }
}
