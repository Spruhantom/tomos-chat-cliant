package uk.co.tomos.chat

import java.security.KeyPairGenerator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSignatureCodecTest {
    @Test
    fun verifiesRawEd25519PublicKeysAndRejectsTampering() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val message = "TomChat rendezvous record".encodeToByteArray()
        val signature = DeviceSignatureCodec.sign(message, pair.private)
        val rawPublicKey = pair.public.encoded.takeLast(32).toByteArray()

        assertTrue(DeviceSignatureCodec.verify(message, signature, rawPublicKey))
        assertFalse(DeviceSignatureCodec.verify("tampered".encodeToByteArray(), signature, rawPublicKey))
        assertFalse(DeviceSignatureCodec.verify(message, signature.copyOf(63), rawPublicKey))
        assertFalse(DeviceSignatureCodec.verify(message, signature, ByteArray(31)))
    }
}
