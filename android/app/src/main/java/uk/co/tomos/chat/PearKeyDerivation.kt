package uk.co.tomos.chat

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest
import java.util.Arrays

data class PearConversationMaterial(
    val conversationKey: ByteArray,
    val discoveryTopic: ByteArray
)

/**
 * Derives a symmetric per-contact key and private Hyperswarm topic from the
 * two signed Pear X25519 transport identities. Domain separation prevents
 * reuse of the raw X25519 secret as a storage or discovery key.
 */
internal object PearKeyDerivation {
    private val conversationInfo = "tomchat/pear/conversation-key/v1".encodeToByteArray()
    private val saltPrefix = "tomchat/pear/pair-salt/v1".encodeToByteArray()
    private val topicPrefix = "tomchat/pear/discovery-topic/v1".encodeToByteArray()

    fun derive(
        localPrivateKey: ByteArray,
        localPublicKey: ByteArray,
        remotePublicKey: ByteArray
    ): PearConversationMaterial {
        require(localPrivateKey.size == 32 && localPublicKey.size == 32 && remotePublicKey.size == 32) {
            "Pear X25519 keys must each be 32 bytes."
        }
        val privateKey = X25519PrivateKeyParameters(localPrivateKey, 0)
        require(MessageDigest.isEqual(privateKey.generatePublicKey().encoded, localPublicKey)) {
            "Local Pear transport key pair is inconsistent."
        }

        val sharedSecret = ByteArray(32)
        try {
            X25519Agreement().apply { init(privateKey) }
                .calculateAgreement(X25519PublicKeyParameters(remotePublicKey, 0), sharedSecret, 0)
            require(sharedSecret.any { it.toInt() != 0 }) { "Pear X25519 produced an invalid all-zero shared secret." }

            val first = if (compareUnsigned(localPublicKey, remotePublicKey) <= 0) localPublicKey else remotePublicKey
            val second = if (first === localPublicKey) remotePublicKey else localPublicKey
            val salt = MessageDigest.getInstance("SHA-256").digest(saltPrefix + first + second)
            val conversationKey = ByteArray(32)
            val hkdf = HKDFBytesGenerator(SHA256Digest())
            hkdf.init(HKDFParameters(sharedSecret, salt, conversationInfo))
            hkdf.generateBytes(conversationKey, 0, conversationKey.size)
            val topic = MessageDigest.getInstance("SHA-256").digest(topicPrefix + conversationKey)
            return PearConversationMaterial(conversationKey, topic)
        } finally {
            Arrays.fill(sharedSecret, 0)
        }
    }

    private fun compareUnsigned(first: ByteArray, second: ByteArray): Int {
        for (index in first.indices) {
            val a = first[index].toInt() and 0xff
            val b = second[index].toInt() and 0xff
            if (a != b) return a.compareTo(b)
        }
        return 0
    }
}
