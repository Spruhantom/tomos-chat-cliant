package uk.co.tomos.chat

import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** End-to-end AES-256-GCM envelope for ciphertext-only Pear replication records. */
internal object PearMessageCipher {
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun encrypt(
        conversationKey: ByteArray,
        messageId: String,
        senderFingerprint: String,
        createdAt: Long,
        plaintext: String
    ): String {
        require(conversationKey.size == 32)
        require(messageId.isNotBlank() && messageId.length <= 160)
        require(senderFingerprint.isNotBlank() && senderFingerprint.length <= 128)
        require(createdAt >= 0)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(conversationKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData(messageId, senderFingerprint, createdAt))
        val encrypted = cipher.doFinal(plaintext.encodeToByteArray())
        return Base64.getEncoder().encodeToString(nonce + encrypted)
    }

    fun decrypt(
        conversationKey: ByteArray,
        messageId: String,
        senderFingerprint: String,
        createdAt: Long,
        encodedCiphertext: String
    ): String {
        require(conversationKey.size == 32)
        val record = Base64.getDecoder().decode(encodedCiphertext)
        require(record.size > NONCE_BYTES + TAG_BITS / 8) { "Pear ciphertext is truncated." }
        val nonce = record.copyOfRange(0, NONCE_BYTES)
        val encrypted = record.copyOfRange(NONCE_BYTES, record.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(conversationKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData(messageId, senderFingerprint, createdAt))
        val plaintext = cipher.doFinal(encrypted)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(java.nio.ByteBuffer.wrap(plaintext)).toString()
    }

    private fun associatedData(messageId: String, senderFingerprint: String, createdAt: Long): ByteArray =
        "tomchat/pear/message/v1|$messageId|$senderFingerprint|$createdAt".encodeToByteArray()
}
