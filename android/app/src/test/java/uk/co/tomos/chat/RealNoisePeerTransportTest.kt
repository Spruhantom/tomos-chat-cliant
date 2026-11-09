package uk.co.tomos.chat

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Real host-JVM integration test: this loads the production Rust JNI library,
 * authenticates both signing identities, performs Noise XX, then exchanges
 * application frames over a real loopback TCP socket. It is not a crypto mock.
 */
class RealNoisePeerTransportTest {
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
    fun realNoisePeersExchangeTextReceiptsAndVoiceFramesOverLoopbackTcp() {
        val aliceSigning = signingIdentity()
        val bobSigning = signingIdentity()
        val aliceNoise = noiseIdentity()
        val bobNoise = noiseIdentity()
        val aliceContact = trustedContact("Alice", aliceSigning, aliceNoise)
        val bobContact = trustedContact("Bob", bobSigning, bobNoise)
        val executor = Executors.newSingleThreadExecutor()

        try {
            ServerSocket(0).use { listener ->
                val bobFuture = executor.submit<EncryptedPeerChannel> {
                    val socket = listener.accept()
                    try {
                        val authenticated = DirectPeerAuthenticator(bobSigning, aliceContact).authenticate(socket)
                        authenticated.establishEncryptedChannel(
                            NoisePeerSession.create(bobNoise, aliceContact, initiator = false),
                            initiator = false
                        )
                    } catch (error: Throwable) {
                        runCatching { socket.close() }
                        throw error
                    }
                }

                Socket("127.0.0.1", listener.localPort).use { socket ->
                    val authenticated = DirectPeerAuthenticator(aliceSigning, bobContact).authenticate(socket)
                    authenticated.establishEncryptedChannel(
                        NoisePeerSession.create(aliceNoise, bobContact, initiator = true),
                        initiator = true
                    ).use { alice ->
                        bobFuture.get(15, TimeUnit.SECONDS).use { bob ->
                            val messageId = UUID.randomUUID()
                            val text = "Real Noise XX message"
                            alice.send(EncryptedAppFrame(
                                EncryptedAppFrame.Type.TEXT,
                                messageId,
                                text.toByteArray(Charsets.UTF_8)
                            ))
                            val receivedText = bob.receive()
                            assertEquals(EncryptedAppFrame.Type.TEXT, receivedText.type)
                            assertEquals(messageId, receivedText.messageId)
                            assertArrayEquals(text.toByteArray(Charsets.UTF_8), receivedText.payload)

                            bob.send(EncryptedAppFrame(
                                EncryptedAppFrame.Type.DELIVERY_ACK,
                                messageId,
                                byteArrayOf()
                            ))
                            val acknowledgement = alice.receive()
                            assertEquals(EncryptedAppFrame.Type.DELIVERY_ACK, acknowledgement.type)
                            assertEquals(messageId, acknowledgement.messageId)
                            assertEquals(0, acknowledgement.payload.size)

                            val callId = UUID.randomUUID()
                            alice.send(EncryptedAppFrame(
                                EncryptedAppFrame.Type.VOICE_SIGNAL,
                                callId,
                                PeerVoiceCallCodec.encodeSignal(PeerVoiceSignal.INVITE)
                            ))
                            val invite = bob.receive()
                            assertEquals(callId, invite.messageId)
                            assertEquals(
                                PeerVoiceSignal.INVITE,
                                (PeerVoiceCallCodec.decode(invite.payload) as PeerVoicePayload.Signal).signal
                            )

                            bob.send(EncryptedAppFrame(
                                EncryptedAppFrame.Type.VOICE_SIGNAL,
                                callId,
                                PeerVoiceCallCodec.encodeSignal(PeerVoiceSignal.ACCEPT)
                            ))
                            val accepted = alice.receive()
                            assertEquals(
                                PeerVoiceSignal.ACCEPT,
                                (PeerVoiceCallCodec.decode(accepted.payload) as PeerVoicePayload.Signal).signal
                            )

                            val pcm = ByteArray(VoiceAudioFormat.FRAME_BYTES) { index -> (index % 251).toByte() }
                            alice.send(EncryptedAppFrame(
                                EncryptedAppFrame.Type.VOICE_SIGNAL,
                                callId,
                                PeerVoiceCallCodec.encodeMedia(pcm)
                            ))
                            val receivedMedia = bob.receive()
                            assertEquals(callId, receivedMedia.messageId)
                            val decodedMedia = PeerVoiceCallCodec.decode(receivedMedia.payload)
                            assertTrue(decodedMedia is PeerVoicePayload.Media)
                            assertArrayEquals(pcm, (decodedMedia as PeerVoicePayload.Media).pcm)

                            bob.send(EncryptedAppFrame(
                                EncryptedAppFrame.Type.VOICE_SIGNAL,
                                callId,
                                PeerVoiceCallCodec.encodeSignal(PeerVoiceSignal.HANGUP)
                            ))
                            val hangup = alice.receive()
                            assertEquals(
                                PeerVoiceSignal.HANGUP,
                                (PeerVoiceCallCodec.decode(hangup.payload) as PeerVoicePayload.Signal).signal
                            )
                        }
                    }
                }
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            aliceNoise.privateKey.fill(0)
            bobNoise.privateKey.fill(0)
        }
    }
}
