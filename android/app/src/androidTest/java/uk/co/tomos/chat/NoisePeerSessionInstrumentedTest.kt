package uk.co.tomos.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

@RunWith(AndroidJUnit4::class)
class NoisePeerSessionInstrumentedTest {
    private fun identity(): NoiseIdentity {
        val privateKey = X25519PrivateKeyParameters(SecureRandom())
        return NoiseIdentity(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    private fun contact(identity: NoiseIdentity) = TrustedContact(
        displayName = "Peer",
        encodedPublicKey = byteArrayOf(1),
        fingerprint = ContactCardCodec.fingerprint(byteArrayOf(1)),
        noiseStaticPublicKey = identity.publicKey
    )

    @Test
    fun jniNoiseXXHandshakePinsKeysAndEncryptsBothDirections() {
        val alice = identity()
        val bob = identity()
        val alicePeer = contact(bob)
        val bobPeer = contact(alice)
        val initiator = NoisePeerSession.create(alice, alicePeer, initiator = true)
        val responder = NoisePeerSession.create(bob, bobPeer, initiator = false)
        try {
            val messageOne = initiator.writeHandshake()
            responder.readHandshake(messageOne)
            val messageTwo = responder.writeHandshake()
            initiator.readHandshake(messageTwo)
            val messageThree = initiator.writeHandshake()
            responder.readHandshake(messageThree)
            initiator.finishHandshake()
            responder.finishHandshake()

            val plaintext = "TomChat encrypted over the direct peer link".toByteArray()
            val ciphertext = initiator.encrypt(plaintext)
            assertNotEquals(plaintext.toList(), ciphertext.toList())
            assertArrayEquals(plaintext, responder.decrypt(ciphertext))

            val reply = "Authenticated reply".toByteArray()
            assertArrayEquals(reply, initiator.decrypt(responder.encrypt(reply)))
        } finally {
            initiator.close()
            responder.close()
            alice.privateKey.fill(0)
            bob.privateKey.fill(0)
        }
    }

    @Test
    fun rejectsWrongPinnedPeerNoiseKey() {
        val alice = identity()
        val bob = identity()
        val wrongBob = identity()
        val initiator = NoisePeerSession.create(alice, contact(wrongBob), initiator = true)
        val responder = NoisePeerSession.create(bob, contact(alice), initiator = false)
        try {
            responder.readHandshake(initiator.writeHandshake())
            initiator.readHandshake(responder.writeHandshake())
            responder.readHandshake(initiator.writeHandshake())
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                initiator.finishHandshake()
            }
        } finally {
            initiator.close()
            responder.close()
            alice.privateKey.fill(0)
            bob.privateKey.fill(0)
            wrongBob.privateKey.fill(0)
        }
    }
}
