package uk.co.tomos.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RealNoisePeerChannelInstrumentedTest {
    private fun identity(): NoiseIdentity {
        val privateKey = X25519PrivateKeyParameters(SecureRandom())
        return NoiseIdentity(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    private fun contact(identity: NoiseIdentity) = TrustedContact(
        displayName = "Instrumented peer",
        encodedPublicKey = byteArrayOf(1),
        fingerprint = ContactCardCodec.fingerprint(byteArrayOf(1)),
        noiseStaticPublicKey = identity.publicKey
    )

    @Test
    fun realJniNoiseXXRunsOverLoopbackTcpAndExchangesBothDirections() {
        val alice = identity()
        val bob = identity()
        val server = ServerSocket(0)
        val clientSocket = Socket("127.0.0.1", server.localPort)
        val serverSocket = server.accept()
        server.close()
        clientSocket.soTimeout = 8_000
        serverSocket.soTimeout = 8_000

        val aliceConnection = AuthenticatedPeerConnection(clientSocket, "bob")
        val bobConnection = AuthenticatedPeerConnection(serverSocket, "alice")
        val aliceNoise = NoisePeerSession.create(alice, contact(bob), initiator = true)
        val bobNoise = NoisePeerSession.create(bob, contact(alice), initiator = false)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val aliceFuture = pool.submit<EncryptedPeerChannel> {
                aliceConnection.establishEncryptedChannel(aliceNoise, initiator = true)
            }
            val bobFuture = pool.submit<EncryptedPeerChannel> {
                bobConnection.establishEncryptedChannel(bobNoise, initiator = false)
            }
            val aliceChannel = aliceFuture.get(10, TimeUnit.SECONDS)
            val bobChannel = bobFuture.get(10, TimeUnit.SECONDS)
            try {
                val fromAlice = EncryptedAppFrame(EncryptedAppFrame.Type.TEXT, UUID.randomUUID(), "hello Bob".toByteArray())
                aliceChannel.send(fromAlice)
                val receivedByBob = bobChannel.receive()
                assertEquals(fromAlice.messageId, receivedByBob.messageId)
                assertArrayEquals(fromAlice.payload, receivedByBob.payload)

                val fromBob = EncryptedAppFrame(EncryptedAppFrame.Type.TEXT, UUID.randomUUID(), "hello Alice".toByteArray())
                bobChannel.send(fromBob)
                val receivedByAlice = aliceChannel.receive()
                assertEquals(fromBob.messageId, receivedByAlice.messageId)
                assertArrayEquals(fromBob.payload, receivedByAlice.payload)
            } finally {
                aliceChannel.close()
                bobChannel.close()
            }
        } finally {
            pool.shutdownNow()
            aliceConnection.close()
            bobConnection.close()
            alice.privateKey.fill(0)
            bob.privateKey.fill(0)
        }
    }
}
