package uk.co.tomos.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class DeviceSignatureCodecTest {
    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    @Test fun convertsDerEcdsaToSharedFixedSignatureAndBack() {
        val pair = keyPair()
        val message = "TomChat signed peer record".toByteArray()
        val signature = DeviceSignatureCodec.sign(message, pair.private)
        assertEquals(64, signature.size)
        assertTrue(DeviceSignatureCodec.verify(message, signature, pair.public.encoded))
        assertFalse(DeviceSignatureCodec.verify(message + byteArrayOf(1), signature, pair.public.encoded))
        assertEquals(65, DeviceSignatureCodec.rawPublicKey(pair.public).size)
        assertEquals(4, DeviceSignatureCodec.rawPublicKey(pair.public)[0].toInt())
    }

    @Test fun statusManifestSignatureUsesDeviceIdentity() {
        val pair = keyPair()
        val unsigned = StatusManifest(
            ByteArray(16) { 1 }, ByteArray(16) { 2 }, 1_000, 87_400,
            StatusManifest.Audience.SELECTED_CONTACTS, ByteArray(32) { 3 },
            StatusManifest.ContentType.TEXT, 128, ByteArray(32) { 4 }, ByteArray(64)
        )
        val signed = unsigned.sign(pair.private)
        assertTrue(signed.verifySignature(pair.public.encoded))
        assertFalse(signed.copy(ciphertextLength = 129).verifySignature(pair.public.encoded))
    }
}
