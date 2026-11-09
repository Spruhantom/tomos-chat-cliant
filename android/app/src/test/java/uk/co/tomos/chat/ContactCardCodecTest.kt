package uk.co.tomos.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class ContactCardCodecTest {
    private fun identity() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    @Test fun signedContactCardRoundTrips() {
        val card = ContactCardCodec.create("Tom's phone", identity())
        val decoded = ContactCardCodec.decode(ContactCardCodec.encode(card))
        assertEquals(card.displayName, decoded.displayName)
        assertEquals(card.fingerprint, decoded.fingerprint)
        assertTrue(ContactCardCodec.verify(decoded))
    }

    @Test fun rejectsModifiedDisplayName() {
        val card = ContactCardCodec.create("Alice", identity())
        assertFalse(ContactCardCodec.verify(card.copy(displayName = "Mallory")))
    }

    @Test fun rejectsModifiedPublicKey() {
        val first = ContactCardCodec.create("Alice", identity())
        val second = ContactCardCodec.create("Bob", identity())
        assertFalse(ContactCardCodec.verify(first.copy(encodedPublicKey = second.encodedPublicKey)))
    }

    @Test fun rejectsUnsupportedCardVersion() {
        val card = ContactCardCodec.encode(ContactCardCodec.create("Alice", identity()))
        val modified = card.replaceFirst("TOMCHAT-CONTACT-V1", "TOMCHAT-CONTACT-V2")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ContactCardCodec.decode(modified) }
    }

    @Test fun rejectsOversizedInput() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ContactCardCodec.decode("x".repeat(4097)) }
    }
}
