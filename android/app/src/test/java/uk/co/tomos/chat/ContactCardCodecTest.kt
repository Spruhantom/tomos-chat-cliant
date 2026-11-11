package uk.co.tomos.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.spec.ECGenParameterSpec

class ContactCardCodecTest {
    private fun identity() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun noiseKey(seed: Int = 7) = ByteArray(32) { (seed + it).toByte() }

    @Test fun signedV2ContactCardRoundTripsIdentityAndNoiseBinding() {
        val card = ContactCardCodec.create("Tom's phone", identity(), noiseKey())
        val decoded = ContactCardCodec.decode(ContactCardCodec.encode(card))
        assertEquals(card.displayName, decoded.displayName)
        assertEquals(card.fingerprint, decoded.fingerprint)
        assertArrayEquals(card.noiseStaticPublicKey, decoded.noiseStaticPublicKey)
        assertTrue(ContactCardCodec.verify(decoded))
    }

    @Test fun signatureCoversNoiseStaticPublicKey() {
        val card = ContactCardCodec.create("Alice", identity(), noiseKey())
        assertFalse(ContactCardCodec.verify(card.copy(noiseStaticPublicKey = noiseKey(19))))
    }


    @Test fun realX25519PublicKeyCanBeBoundToP256Identity() {
        val noisePrivate = X25519PrivateKeyParameters(SecureRandom())
        val noisePublic = noisePrivate.generatePublicKey().encoded
        val card = ContactCardCodec.create("Peer device", identity(), noisePublic)
        val decoded = ContactCardCodec.decode(ContactCardCodec.encode(card))
        assertArrayEquals(noisePublic, decoded.noiseStaticPublicKey)
        assertTrue(ContactCardCodec.verify(decoded))
    }

    @Test fun rejectsMissingOrWrongLengthNoiseKeyOnCreation() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ContactCardCodec.create("Alice", identity(), ByteArray(31))
        }
    }

    @Test fun rejectsModifiedDisplayName() {
        val card = ContactCardCodec.create("Alice", identity(), noiseKey())
        assertFalse(ContactCardCodec.verify(card.copy(displayName = "Mallory")))
    }

    @Test fun rejectsModifiedIdentityPublicKey() {
        val first = ContactCardCodec.create("Alice", identity(), noiseKey())
        val second = ContactCardCodec.create("Bob", identity(), noiseKey(50))
        assertFalse(ContactCardCodec.verify(first.copy(encodedPublicKey = second.encodedPublicKey)))
    }

    @Test fun legacyV1CardRemainsReadableButHasNoNoiseBinding() {
        val legacy = ContactCardCodec.createLegacyV1("Old phone", identity())
        val decoded = ContactCardCodec.decode(ContactCardCodec.encode(legacy))
        assertNull(decoded.noiseStaticPublicKey)
        assertTrue(ContactCardCodec.verify(decoded))
    }

    @Test fun rejectsUnsupportedCardVersion() {
        val card = ContactCardCodec.encode(ContactCardCodec.create("Alice", identity(), noiseKey()))
        val modified = card.replaceFirst("TOMCHAT-CONTACT-V2", "TOMCHAT-CONTACT-V3")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ContactCardCodec.decode(modified) }
    }

    @Test fun rejectsOversizedInput() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ContactCardCodec.decode("x".repeat(4097)) }
    }

}
