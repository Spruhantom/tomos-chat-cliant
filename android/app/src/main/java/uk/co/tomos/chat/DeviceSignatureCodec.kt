package uk.co.tomos.chat

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Ed25519 signature adapter for device identity records.
 *
 * Public keys accepted here are either the raw 32-byte Ed25519 key used by
 * TomChat contact records or a standard X.509 SubjectPublicKeyInfo encoding.
 * Signatures use the standard 64-byte Ed25519 wire representation.
 */
internal object DeviceSignatureCodec {
    private val rawPublicKeyPrefix = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
        0x70, 0x03, 0x21, 0x00
    )

    fun sign(message: ByteArray, privateKey: PrivateKey): ByteArray {
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privateKey)
        signer.update(message)
        return signer.sign().also {
            require(it.size == 64) { "Ed25519 produced an invalid signature length." }
        }
    }

    fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != 64 || publicKey.isEmpty()) return false
        val encoded = when (publicKey.size) {
            32 -> rawPublicKeyPrefix + publicKey
            in 33..128 -> publicKey
            else -> return false
        }
        return try {
            val key = KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(encoded))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(message)
                verify(signature)
            }
        } catch (_: Exception) {
            false
        }
    }
}
