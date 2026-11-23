package uk.co.tomos.chat

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom

class PearKeyDerivationTest {
    @Test
    fun bothPeersDeriveIdenticalConversationAndDiscoveryKeys() {
        val alicePrivate = X25519PrivateKeyParameters(SecureRandom())
        val bobPrivate = X25519PrivateKeyParameters(SecureRandom())
        val alicePublic = alicePrivate.generatePublicKey().encoded
        val bobPublic = bobPrivate.generatePublicKey().encoded

        val alice = PearKeyDerivation.derive(alicePrivate.encoded, alicePublic, bobPublic)
        val bob = PearKeyDerivation.derive(bobPrivate.encoded, bobPublic, alicePublic)

        assertArrayEquals(alice.conversationKey, bob.conversationKey)
        assertArrayEquals(alice.discoveryTopic, bob.discoveryTopic)
    }

    @Test
    fun rejectsMismatchedLocalIdentity() {
        val first = X25519PrivateKeyParameters(SecureRandom())
        val second = X25519PrivateKeyParameters(SecureRandom())
        val remote = X25519PrivateKeyParameters(SecureRandom())
        assertThrows(IllegalArgumentException::class.java) {
            PearKeyDerivation.derive(first.encoded, second.generatePublicKey().encoded, remote.generatePublicKey().encoded)
        }
    }

    @Test
    fun rejectsIncorrectKeyLengths() {
        assertThrows(IllegalArgumentException::class.java) {
            PearKeyDerivation.derive(ByteArray(31), ByteArray(32), ByteArray(32))
        }
    }
}
