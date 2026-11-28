package uk.co.tomos.chat

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * A self-signed contact card. V3 binds both the legacy Noise key (during
 * migration) and the new Pear/Hyperswarm transport key to the P-256 identity.
 * V1/V2 remain readable but are not eligible for Pear peer authorization.
 */
data class ContactCard(
    val displayName: String,
    val encodedPublicKey: ByteArray,
    val fingerprint: String,
    val signature: ByteArray,
    val noiseStaticPublicKey: ByteArray? = null,
    val pearTransportPublicKey: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean =
        other is ContactCard && displayName == other.displayName &&
            encodedPublicKey.contentEquals(other.encodedPublicKey) &&
            fingerprint == other.fingerprint && signature.contentEquals(other.signature) &&
            sameBytes(noiseStaticPublicKey, other.noiseStaticPublicKey) &&
            sameBytes(pearTransportPublicKey, other.pearTransportPublicKey)

    override fun hashCode(): Int = 31 * displayName.hashCode() + encodedPublicKey.contentHashCode()

    private companion object {
        fun sameBytes(first: ByteArray?, second: ByteArray?): Boolean =
            if (first == null || second == null) first == null && second == null
            else first.contentEquals(second)
    }
}

object ContactCardCodec {
    private const val PREFIX_V1 = "TOMCHAT-CONTACT-V1"
    private const val PREFIX_V2 = "TOMCHAT-CONTACT-V2"
    private const val PREFIX_V3 = "TOMCHAT-CONTACT-V3"

    fun create(displayName: String, identity: KeyPair, noiseStaticPublicKey: ByteArray): ContactCard {
        require(displayName.isNotBlank() && displayName.length <= 64)
        require(noiseStaticPublicKey.size == 32) { "Noise X25519 public key must be 32 bytes." }
        val publicKey = identity.public.encoded
        val fingerprint = fingerprint(publicKey)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(identity.private)
        signer.update(canonical(PREFIX_V2, displayName, publicKey, fingerprint, noiseStaticPublicKey, null))
        return ContactCard(displayName, publicKey, fingerprint, signer.sign(), noiseStaticPublicKey.copyOf())
    }

    /** New Pear card: both transport keys are covered by the device identity signature. */
    fun createPear(
        displayName: String,
        identity: KeyPair,
        noiseStaticPublicKey: ByteArray,
        pearTransportPublicKey: ByteArray
    ): ContactCard {
        require(displayName.isNotBlank() && displayName.length <= 64)
        require(noiseStaticPublicKey.size == 32) { "Noise X25519 public key must be 32 bytes." }
        require(pearTransportPublicKey.size == 32) { "Pear X25519 public key must be 32 bytes." }
        val publicKey = identity.public.encoded
        val fingerprint = fingerprint(publicKey)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(identity.private)
        signer.update(canonical(PREFIX_V3, displayName, publicKey, fingerprint, noiseStaticPublicKey, pearTransportPublicKey))
        return ContactCard(
            displayName, publicKey, fingerprint, signer.sign(),
            noiseStaticPublicKey.copyOf(), pearTransportPublicKey.copyOf()
        )
    }

    /** Creates a legacy V1 card only for migration/testing; new app cards must use V3. */
    fun createLegacyV1(displayName: String, identity: KeyPair): ContactCard {
        require(displayName.isNotBlank() && displayName.length <= 64)
        val publicKey = identity.public.encoded
        val fingerprint = fingerprint(publicKey)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(identity.private)
        signer.update(canonical(PREFIX_V1, displayName, publicKey, fingerprint, null, null))
        return ContactCard(displayName, publicKey, fingerprint, signer.sign())
    }

    fun encode(card: ContactCard): String {
        require(verify(card)) { "Contact card signature is invalid." }
        val noiseKey = card.noiseStaticPublicKey
        val pearKey = card.pearTransportPublicKey
        return when {
            pearKey != null -> listOf(
                PREFIX_V3, b64(card.displayName.toByteArray(StandardCharsets.UTF_8)),
                b64(card.encodedPublicKey), card.fingerprint, b64(requireNotNull(noiseKey)),
                b64(pearKey), b64(card.signature)
            ).joinToString(".")
            noiseKey != null -> listOf(
                PREFIX_V2, b64(card.displayName.toByteArray(StandardCharsets.UTF_8)),
                b64(card.encodedPublicKey), card.fingerprint, b64(noiseKey), b64(card.signature)
            ).joinToString(".")
            else -> listOf(
                PREFIX_V1, b64(card.displayName.toByteArray(StandardCharsets.UTF_8)),
                b64(card.encodedPublicKey), card.fingerprint, b64(card.signature)
            ).joinToString(".")
        }
    }

    fun decode(encoded: String): ContactCard {
        require(encoded.length <= 4096) { "Contact card is too large." }
        val fields = encoded.split(".")
        val card = when {
            fields.size == 5 && fields[0] == PREFIX_V1 -> ContactCard(
                String(unb64(fields[1]), StandardCharsets.UTF_8), unb64(fields[2]),
                fields[3], unb64(fields[4]), null, null
            )
            fields.size == 6 && fields[0] == PREFIX_V2 -> ContactCard(
                String(unb64(fields[1]), StandardCharsets.UTF_8), unb64(fields[2]),
                fields[3], unb64(fields[5]), unb64(fields[4]), null
            )
            fields.size == 7 && fields[0] == PREFIX_V3 -> ContactCard(
                String(unb64(fields[1]), StandardCharsets.UTF_8), unb64(fields[2]),
                fields[3], unb64(fields[6]), unb64(fields[4]), unb64(fields[5])
            )
            else -> throw IllegalArgumentException("Unsupported contact card format.")
        }
        require(card.displayName.isNotBlank() && card.displayName.length <= 64) { "Invalid contact display name." }
        require(verify(card)) { "Contact card failed fingerprint or signature verification." }
        return card
    }

    fun verify(card: ContactCard): Boolean = try {
        if (card.displayName.isBlank() || card.displayName.length > 64 ||
            fingerprint(card.encodedPublicKey) != card.fingerprint ||
            (card.noiseStaticPublicKey != null && card.noiseStaticPublicKey.size != 32) ||
            (card.pearTransportPublicKey != null && card.pearTransportPublicKey.size != 32) ||
            (card.pearTransportPublicKey != null && card.noiseStaticPublicKey == null)
        ) false else {
            val prefix = when {
                card.pearTransportPublicKey != null -> PREFIX_V3
                card.noiseStaticPublicKey != null -> PREFIX_V2
                else -> PREFIX_V1
            }
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(card.encodedPublicKey))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(canonical(
                prefix, card.displayName, card.encodedPublicKey, card.fingerprint,
                card.noiseStaticPublicKey, card.pearTransportPublicKey
            ))
            verifier.verify(card.signature)
        }
    } catch (_: Exception) { false }

    fun fingerprint(encodedPublicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(encodedPublicKey)
            .joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")

    private fun canonical(
        prefix: String,
        name: String,
        key: ByteArray,
        fingerprint: String,
        noiseKey: ByteArray?,
        pearKey: ByteArray?
    ): ByteArray {
        val fields = mutableListOf(prefix, b64(name.toByteArray(StandardCharsets.UTF_8)), b64(key), fingerprint)
        if (noiseKey != null) fields += b64(noiseKey)
        if (pearKey != null) fields += b64(pearKey)
        return fields.joinToString(".").toByteArray(StandardCharsets.UTF_8)
    }

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun unb64(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}
