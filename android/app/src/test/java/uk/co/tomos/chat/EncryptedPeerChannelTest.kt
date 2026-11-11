package uk.co.tomos.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EncryptedPeerChannelTest {
    /** Test double only: this verifies channel wiring, not cryptography. */
    private class TestBackend : NoiseSessionBackend {
        override fun writeHandshake(handle: Long, payload: ByteArray) = byteArrayOf(0x41)
        override fun readHandshake(handle: Long, message: ByteArray): ByteArray {
            require(message.contentEquals(byteArrayOf(0x41)))
            return byteArrayOf()
        }
        override fun finishHandshake(handle: Long) = Unit
        override fun encrypt(handle: Long, plaintext: ByteArray) = plaintext.reversedArray()
        override fun decrypt(handle: Long, ciphertext: ByteArray) = ciphertext.reversedArray()
        override fun closeSession(handle: Long) = Unit
    }

    @Test fun onlyDecodedFramesAreExchangedAfterBothNoiseWrappersFinish() {
        ServerSocket(0).use { listener ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val serverFuture = executor.submit<EncryptedPeerChannel> {
                    val accepted = listener.accept()
                    val peer = AuthenticatedPeerConnection(TcpPeerTransportSocket(accepted), "client")
                    peer.establishEncryptedChannel(NoisePeerSession(2L, TestBackend()), false)
                }
                Socket("127.0.0.1", listener.localPort).use { socket ->
                    val client = AuthenticatedPeerConnection(TcpPeerTransportSocket(socket), "server")
                    client.establishEncryptedChannel(NoisePeerSession(1L, TestBackend()), true).use { channel ->
                        val serverChannel = serverFuture.get(5, TimeUnit.SECONDS)
                        try {
                            val sent = EncryptedAppFrame(
                                EncryptedAppFrame.Type.TEXT, UUID.randomUUID(), "hello".toByteArray()
                            )
                            channel.send(sent)
                            val received = serverChannel.receive()
                            assertEquals(sent.type, received.type)
                            assertEquals(sent.messageId, received.messageId)
                            assertArrayEquals(sent.payload, received.payload)
                        } finally {
                            serverChannel.close()
                        }
                    }
                }
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
