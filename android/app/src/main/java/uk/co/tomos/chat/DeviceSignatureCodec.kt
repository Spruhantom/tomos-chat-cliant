package uk.co.tomos.chat

import java.math.BigInteger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

/** Adapts Android's SHA256withECDSA DER signatures to the shared 64-byte
 * P-256 r||s wire signature used by the Rust ring verifier.
 */
object DeviceSignatureCodec {
    private const val COMPONENT_BYTES = 32

    fun sign(message: ByteArray, privateKey: PrivateKey): ByteArray {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(message)
        return derToFixed(signer.sign())
    }

    fun verify(message: ByteArray, fixedSignature: ByteArray, encodedPublicKey: ByteArray): Boolean =
        try {
            require(fixedSignature.size == 64)
            val publicKey = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(encodedPublicKey))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(message)
            verifier.verify(fixedToDer(fixedSignature))
        } catch (_: Exception) { false }

    /** SEC1 uncompressed P-256 point for the Rust ring verifier. */
    fun rawPublicKey(publicKey: PublicKey): ByteArray {
        val ec = publicKey as? ECPublicKey ?: throw IllegalArgumentException("Expected a P-256 EC public key.")
        val x = fixed32(ec.w.affineX)
        val y = fixed32(ec.w.affineY)
        return byteArrayOf(0x04) + x + y
    }

    private fun derToFixed(der: ByteArray): ByteArray {
        require(der.size in 8..72 && der[0] == 0x30.toByte())
        var offset = 2
        require((der[1].toInt() and 0xff) == der.size - 2)
        require(der[offset++] == 0x02.toByte())
        val rLength = der[offset++].toInt() and 0xff
        require(rLength in 1..33 && offset + rLength < der.size)
        val r = der.copyOfRange(offset, offset + rLength)
        offset += rLength
        require(der[offset++] == 0x02.toByte())
        val sLength = der[offset++].toInt() and 0xff
        require(sLength in 1..33 && offset + sLength == der.size)
        val s = der.copyOfRange(offset, offset + sLength)
        return fixed32(r) + fixed32(s)
    }

    private fun fixedToDer(raw: ByteArray): ByteArray {
        require(raw.size == 64)
        val r = derInteger(raw.copyOfRange(0, 32))
        val s = derInteger(raw.copyOfRange(32, 64))
        val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun derInteger(value: ByteArray): ByteArray {
        var first = 0
        while (first < value.size - 1 && value[first] == 0.toByte()) first++
        val trimmed = value.copyOfRange(first, value.size)
        return if ((trimmed[0].toInt() and 0x80) != 0) byteArrayOf(0) + trimmed else trimmed
    }

    private fun fixed32(value: BigInteger): ByteArray = fixed32(value.toByteArray())

    private fun fixed32(value: ByteArray): ByteArray {
        var start = 0
        while (start < value.size - 1 && value[start] == 0.toByte()) start++
        val normalized = value.copyOfRange(start, value.size)
        require(normalized.size <= COMPONENT_BYTES)
        return ByteArray(COMPONENT_BYTES - normalized.size) + normalized
    }
}
