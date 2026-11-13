package uk.co.tomos.chat

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.DatagramSocket
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UdpNoisePeerChannelTest {
    private data class Device(val noise: NoiseIdentity, val trustedContact: TrustedContact)

    private fun device(): Device {
        val signingPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val privateKey = X25519PrivateKeyParameters(SecureRandom()).encoded
        val publicKey = X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded
        return Device(
            NoiseIdentity(privateKey, publicKey),
            TrustedContact(
                "test peer",
                signingPair.public.encoded,
                ContactCardCodec.fingerprint(signingPair.public.encoded),
                publicKey
            )
        )
    }

    private fun trustedCopy(device: Device, remoteNoiseKey: ByteArray): TrustedContact =
        device.trustedContact.copy(noiseStaticPublicKey = remoteNoiseKey)

    @Test fun udpNoiseHandshakeAndFragmentedApplicationFramesWorkBothWays() {
        val alice = device()
        val bob = device()
        val aliceTrustsBob = trustedCopy(bob, bob.noise.publicKey)
        val bobTrustsAlice = trustedCopy(alice, alice.noise.publicKey)
        val responderSocket = DatagramSocket(0)
        val responderAddress = java.net.InetSocketAddress("127.0.0.1", responderSocket.localPort)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val responder = executor.submit {
                UdpNoisePeerChannel.accept(responderSocket, bob.noise, bobTrustsAlice, 10_000).use { channel ->
                    assertEquals(EncryptedAppFrame.Type.TEXT, channel.receive().type)
                    channel.send(EncryptedAppFrame(EncryptedAppFrame.Type.TEXT,
                        java.util.UUID.randomUUID(), "reply".toByteArray()))
                    val large = channel.receive()
                    assertArrayEquals(ByteArray(5_000) { 0x42 }, large.payload)
                }
            }
            DatagramSocket(0).use { initiatorSocket ->
                UdpNoisePeerChannel.connect(initiatorSocket, responderAddress, alice.noise, aliceTrustsBob).use { channel ->
                    channel.send(EncryptedAppFrame(EncryptedAppFrame.Type.TEXT,
                        java.util.UUID.randomUUID(), "hello".toByteArray()))
                    assertEquals("reply", String(channel.receive().payload))
                    channel.send(EncryptedAppFrame(EncryptedAppFrame.Type.TEXT,
                        java.util.UUID.randomUUID(), ByteArray(5_000) { 0x42 }))
                }
            }
            responder.get(15, TimeUnit.SECONDS)
        } finally {
            responderSocket.close()
            executor.shutdownNow()
        }
    }
}
