package uk.co.tomos.chat

import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory
import java.util.Base64

data class ContactCard(
    val displayName: String,
    val encodedPublicKey: ByteArray,
    val fingerprint: String,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean =
        other is ContactCard && displayName == other.displayName &&
            encodedPublicKey.contentEquals(other.encodedPublicKey) &&
            fingerprint == other.fingerprint && signature.contentEquals(other.signature)

    override fun hashCode(): Int = 31 * displayName.hashCode() + encodedPublicKey.contentHashCode()
}

/** Signed, versioned peer card. Fingerprints should still be compared out of band. */
object ContactCardCodec {
    private const val PREFIX = "TOMCHAT-CONTACT-V1"

    fun create(displayName: String, identity: KeyPair): ContactCard {
        require(displayName.isNotBlank() && displayName.length <= 64)
        val publicKey = identity.public.encoded
        val fingerprint = fingerprint(publicKey)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(identity.private)
        signer.update(canonical(displayName, publicKey, fingerprint))
        return ContactCard(displayName, publicKey, fingerprint, signer.sign())
    }

    fun encode(card: ContactCard): String {
        require(verify(card)) { "Contact card signature is invalid." }
        return listOf(PREFIX, b64(card.displayName.toByteArray(StandardCharsets.UTF_8)),
            b64(card.encodedPublicKey), card.fingerprint, b64(card.signature)).joinToString(".")
    }

    fun decode(encoded: String): ContactCard {
        require(encoded.length <= 4096) { "Contact card is too large." }
        val fields = encoded.split(".")
        require(fields.size == 5 && fields[0] == PREFIX) { "Unsupported contact card format." }
        val name = String(unb64(fields[1]), StandardCharsets.UTF_8)
        val card = ContactCard(name, unb64(fields[2]), fields[3], unb64(fields[4]))
        require(name.isNotBlank() && name.length <= 64) { "Invalid contact display name." }
        require(verify(card)) { "Contact card failed fingerprint or signature verification." }
        return card
    }

    fun verify(card: ContactCard): Boolean = try {
        if (card.displayName.isBlank() || card.displayName.length > 64) return false
        if (fingerprint(card.encodedPublicKey) != card.fingerprint) return false
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(card.encodedPublicKey))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(canonical(card.displayName, card.encodedPublicKey, card.fingerprint))
        verifier.verify(card.signature)
    } catch (_: Exception) { false }

    fun fingerprint(encodedPublicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(encodedPublicKey)
            .joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")

    private fun canonical(name: String, key: ByteArray, fingerprint: String): ByteArray =
        (PREFIX + "." + b64(name.toByteArray(StandardCharsets.UTF_8)) + "." +
            b64(key) + "." + fingerprint).toByteArray(StandardCharsets.UTF_8)

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun unb64(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}
