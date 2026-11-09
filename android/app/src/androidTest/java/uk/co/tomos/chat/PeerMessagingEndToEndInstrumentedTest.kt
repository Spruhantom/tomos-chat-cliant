package uk.co.tomos.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PeerMessagingEndToEndInstrumentedTest {
    private fun signingIdentity(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    private fun noiseIdentity(): NoiseIdentity {
        val privateKey = X25519PrivateKeyParameters(SecureRandom())
        return NoiseIdentity(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    private fun trustedContact(name: String, signing: KeyPair, noise: NoiseIdentity) =
        TrustedContact(
            displayName = name,
            encodedPublicKey = signing.public.encoded,
            fingerprint = ContactCardCodec.fingerprint(signing.public.encoded),
            noiseStaticPublicKey = noise.publicKey
        )

    @Test
    fun twoDirectPeersExchangeNoiseEncryptedTextAndDeliveryReceipt() {
        val directory = File(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "peer-messaging-e2e"
        ).apply { deleteRecursively(); mkdirs() }
        val aliceDirectory = File(directory, "alice").apply { mkdirs() }
        val bobDirectory = File(directory, "bob").apply { mkdirs() }
        val aliceSigning = signingIdentity()
        val bobSigning = signingIdentity()
        val aliceNoise = noiseIdentity()
        val bobNoise = noiseIdentity()
        val aliceContact = trustedContact("Alice", aliceSigning, aliceNoise)
        val bobContact = trustedContact("Bob", bobSigning, bobNoise)
        val alice = PeerMessagingCoordinator(aliceDirectory, aliceSigning, aliceNoise)
        val bob = PeerMessagingCoordinator(bobDirectory, bobSigning, bobNoise)
        val executor = Executors.newFixedThreadPool(2)
        var aliceSession: PeerConversationSession? = null
        var bobSession: PeerConversationSession? = null

        try {
            val port = ServerSocket(0).use { it.localPort }
            bob.startListening("127.0.0.1", port)
            val acceptFuture = executor.submit<PeerConversationSession> { bob.accept(aliceContact) }
            val connectFuture = executor.submit<PeerConversationSession> {
                alice.connect("127.0.0.1", port, bobContact)
            }
            val connectedAlice = connectFuture.get(15, TimeUnit.SECONDS)
            val connectedBob = acceptFuture.get(15, TimeUnit.SECONDS)
            aliceSession = connectedAlice
            bobSession = connectedBob

            val sent = connectedAlice.sendText("Hello from Alice over Noise XX")
            assertEquals(StoredMessageStatus.SENT, sent.status)

            val received = connectedBob.receiveEvent()
            assertTrue(received is PeerConversationEvent.MessageReceived)
            assertEquals(
                "Hello from Alice over Noise XX",
                (received as PeerConversationEvent.MessageReceived).message.body
            )

            val acknowledgement = connectedAlice.receiveEvent()
            assertTrue(acknowledgement is PeerConversationEvent.DeliveryConfirmed)
            val stored = EncryptedMessageStore(aliceDirectory).load(bobContact.fingerprint).single()
            assertEquals(StoredMessageStatus.DELIVERED, stored.status)

            val callId = java.util.UUID.randomUUID()
            connectedAlice.sendVoiceSignal(callId, PeerVoiceSignal.INVITE)
            val invite = connectedBob.receiveEvent()
            assertTrue(invite is PeerConversationEvent.VoiceSignalReceived)
            val receivedInvite = invite as PeerConversationEvent.VoiceSignalReceived
            assertEquals(PeerVoiceSignal.INVITE, receivedInvite.signal)
            assertEquals(callId, receivedInvite.callId)

            connectedBob.sendVoiceSignal(callId, PeerVoiceSignal.ACCEPT)
            val accepted = connectedAlice.receiveEvent()
            assertTrue(accepted is PeerConversationEvent.VoiceSignalReceived)
            val receivedAccept = accepted as PeerConversationEvent.VoiceSignalReceived
            assertEquals(PeerVoiceSignal.ACCEPT, receivedAccept.signal)

            val pcm = ByteArray(VoiceAudioFormat.FRAME_BYTES) { index -> (index % 127).toByte() }
            connectedAlice.sendVoiceFrame(callId, pcm)
            val voice = connectedBob.receiveEvent()
            assertTrue(voice is PeerConversationEvent.VoiceFrameReceived)
            val receivedVoice = voice as PeerConversationEvent.VoiceFrameReceived
            assertEquals(callId, receivedVoice.callId)
            assertTrue(pcm.contentEquals(receivedVoice.pcm))

            connectedBob.sendVoiceSignal(callId, PeerVoiceSignal.HANGUP)
            val hangup = connectedAlice.receiveEvent()
            assertTrue(hangup is PeerConversationEvent.VoiceSignalReceived)
            val receivedHangup = hangup as PeerConversationEvent.VoiceSignalReceived
            assertEquals(PeerVoiceSignal.HANGUP, receivedHangup.signal)
        } finally {
            aliceSession?.close()
            bobSession?.close()
            alice.close()
            bob.close()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            directory.deleteRecursively()
            aliceNoise.privateKey.fill(0)
            bobNoise.privateKey.fill(0)
        }
    }
}
