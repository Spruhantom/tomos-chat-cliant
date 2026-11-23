package uk.co.tomos.chat

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Conversation API over Pear's replicated per-contact Hyperbee feeds.
 * The worker only receives ciphertext; encryption and decryption stay in the
 * native host using the X25519/HKDF-derived pairwise conversation key.
 */
class PearConversationSession(
    private val backend: PearP2PBackend,
    localIdentity: PearTransportIdentity,
    private val localFingerprint: String,
    private val contact: TrustedContact,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val transportKey = requireNotNull(contact.pearTransportPublicKey) {
        "Contact needs a signed Pear V3 transport key."
    }
    private val conversationKey = PearKeyDerivation.derive(
        localIdentity.privateKey,
        localIdentity.publicKey,
        transportKey
    ).conversationKey

    init {
        require(localFingerprint.isNotBlank() && localFingerprint.length <= 128)
    }

    suspend fun sendText(text: String): StoredChatMessage {
        require(text.isNotBlank()) { "Message cannot be empty." }
        require(text.encodeToByteArray().size <= MAX_MESSAGE_BYTES) { "Message is too large." }
        val id = UUID.randomUUID()
        val createdAt = clock().coerceAtLeast(0)
        val ciphertext = PearMessageCipher.encrypt(
            conversationKey, id.toString(), localFingerprint, createdAt, text
        )
        backend.appendMessage(
            transportKey,
            id.toString(),
            ciphertext,
            localFingerprint,
            createdAt
        )
        return StoredChatMessage(
            id = id,
            peerFingerprint = contact.fingerprint,
            direction = StoredMessageDirection.OUTGOING,
            status = StoredMessageStatus.SENT,
            createdAtEpochMillis = createdAt,
            body = text
        )
    }

    suspend fun loadMessages(): List<StoredChatMessage> {
        val records = backend.listMessages(transportKey)
        val messages = mutableListOf<StoredChatMessage>()
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            val idText = record.optString("id")
            val sender = record.optString("senderFingerprint")
            val createdAt = record.optLong("createdAt", -1)
            val ciphertext = record.optString("ciphertext")
            if (createdAt < 0 || sender != localFingerprint && sender != contact.fingerprint) continue
            val id = try { UUID.fromString(idText) } catch (_: Exception) { continue }
            val body = try {
                PearMessageCipher.decrypt(conversationKey, idText, sender, createdAt, ciphertext)
            } catch (_: Exception) {
                continue
            }
            messages += StoredChatMessage(
                id = id,
                peerFingerprint = contact.fingerprint,
                direction = if (sender == localFingerprint) StoredMessageDirection.OUTGOING else StoredMessageDirection.INCOMING,
                status = if (sender == localFingerprint) StoredMessageStatus.SENT else StoredMessageStatus.RECEIVED,
                createdAtEpochMillis = createdAt,
                body = body
            )
        }
        return messages.distinctBy { it.id }.sortedWith(compareBy<StoredChatMessage> { it.createdAtEpochMillis }.thenBy { it.id })
    }

    companion object {
        private const val MAX_MESSAGE_BYTES = 16 * 1024
    }
}
