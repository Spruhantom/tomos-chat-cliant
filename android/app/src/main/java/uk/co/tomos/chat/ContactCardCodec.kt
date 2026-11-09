package uk.co.tomos.chat

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * A self-signed contact card. V2 additionally binds a 32-byte Noise X25519
 * static public key to the P-256 identity by including it in the signed
 * canonical fields. V1 cards remain importable but have no Noise binding.
 */
data class ContactCard(
    val displayName: String,
    val encodedPublicKey: ByteArray,
    val fingerprint: String,
    val signature: ByteArray,
    val noiseStaticPublicKey: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean =
        other is ContactCard && displayName == other.displayName &&
            encodedPublicKey.contentEquals(other.encodedPublicKey) &&
            fingerprint == other.fingerprint && signature.contentEquals(other.signature) &&
            ((noiseStaticPublicKey == null && other.noiseStaticPublicKey == null) ||
                (noiseStaticPublicKey != null && other.noiseStaticPublicKey != null &&
                    noiseStaticPublicKey.contentEquals(other.noiseStaticPublicKey)))

    override fun hashCode(): Int = 31 * displayName.hashCode() + encodedPublicKey.contentHashCode()
}

object ContactCardCodec {
    private const val PREFIX_V1 = "TOMCHAT-CONTACT-V1"
    private const val PREFIX_V2 = "TOMCHAT-CONTACT-V2"

    fun create(displayName: String, identity: KeyPair, noiseStaticPublicKey: ByteArray): ContactCard {
        require(displayName.isNotBlank() && displayName.length <= 64)
        require(noiseStaticPublicKey.size == 32) { "Noise X25519 public key must be 32 bytes." }
        val publicKey = identity.public.encoded
        val fingerprint = fingerprint(publicKey)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(identity.private)
        signer.update(canonical(PREFIX_V2, displayName, publicKey, fingerprint, noiseStaticPublicKey))
        return ContactCard(displayName, publicKey, fingerprint, signer.sign(), noiseStaticPublicKey.copyOf())
    }

    /** Creates a legacy V1 card only for migration/testing; new app cards must use V2. */
    fun createLegacyV1(displayName: String, identity: KeyPair): ContactCard {
        require(displayName.isNotBlank() && displayName.length <= 64)
        val publicKey = identity.public.encoded
        val fingerprint = fingerprint(publicKey)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(identity.private)
        signer.update(canonical(PREFIX_V1, displayName, publicKey, fingerprint, null))
        return ContactCard(displayName, publicKey, fingerprint, signer.sign())
    }

    fun encode(card: ContactCard): String {
        require(verify(card)) { "Contact card signature is invalid." }
        val noiseKey = card.noiseStaticPublicKey
        return if (noiseKey == null) {
            listOf(PREFIX_V1, b64(card.displayName.toByteArray(StandardCharsets.UTF_8)),
                b64(card.encodedPublicKey), card.fingerprint, b64(card.signature)).joinToString(".")
        } else {
            listOf(PREFIX_V2, b64(card.displayName.toByteArray(StandardCharsets.UTF_8)),
                b64(card.encodedPublicKey), card.fingerprint, b64(noiseKey), b64(card.signature)).joinToString(".")
        }
    }

    fun decode(encoded: String): ContactCard {
        require(encoded.length <= 4096) { "Contact card is too large." }
        val fields = encoded.split(".")
        val card = when {
            fields.size == 5 && fields[0] == PREFIX_V1 -> {
                ContactCard(String(unb64(fields[1]), StandardCharsets.UTF_8), unb64(fields[2]),
                    fields[3], unb64(fields[4]), null)
            }
            fields.size == 6 && fields[0] == PREFIX_V2 -> {
                ContactCard(String(unb64(fields[1]), StandardCharsets.UTF_8), unb64(fields[2]),
                    fields[3], unb64(fields[5]), unb64(fields[4]))
            }
            else -> throw IllegalArgumentException("Unsupported contact card format.")
        }
        require(card.displayName.isNotBlank() && card.displayName.length <= 64) { "Invalid contact display name." }
        require(verify(card)) { "Contact card failed fingerprint or signature verification." }
        return card
    }

    fun verify(card: ContactCard): Boolean = try {
        if (card.displayName.isBlank() || card.displayName.length > 64 ||
            fingerprint(card.encodedPublicKey) != card.fingerprint ||
            (card.noiseStaticPublicKey != null && card.noiseStaticPublicKey.size != 32)
        ) false else {
            val prefix = if (card.noiseStaticPublicKey == null) PREFIX_V1 else PREFIX_V2
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(card.encodedPublicKey))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(canonical(prefix, card.displayName, card.encodedPublicKey,
                card.fingerprint, card.noiseStaticPublicKey))
            verifier.verify(card.signature)
        }
    } catch (_: Exception) { false }

    fun fingerprint(encodedPublicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(encodedPublicKey)
            .joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")

    private fun canonical(prefix: String, name: String, key: ByteArray, fingerprint: String, noiseKey: ByteArray?): ByteArray {
        val fields = mutableListOf(prefix, b64(name.toByteArray(StandardCharsets.UTF_8)), b64(key), fingerprint)
        if (noiseKey != null) fields += b64(noiseKey)
        return fields.joinToString(".").toByteArray(StandardCharsets.UTF_8)
    }

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun unb64(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}
