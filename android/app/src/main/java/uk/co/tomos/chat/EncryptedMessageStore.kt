package uk.co.tomos.chat

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val MAX_STORED_MESSAGE_BODY_BYTES = 16 * 1024

enum class StoredMessageDirection { INCOMING, OUTGOING }
enum class StoredMessageStatus { RECEIVED, QUEUED, SENT, FAILED, DELIVERED }

data class StoredChatMessage(
    val id: UUID,
    val peerFingerprint: String,
    val direction: StoredMessageDirection,
    val status: StoredMessageStatus,
    val createdAtEpochMillis: Long,
    val body: String
) {
    init {
        require(peerFingerprint.isNotBlank() && peerFingerprint.length <= 128)
        require(body.isNotBlank() && body.toByteArray(Charsets.UTF_8).size <= MAX_STORED_MESSAGE_BODY_BYTES)
        require(createdAtEpochMillis >= 0)
    }
}

/**
 * Device-local encrypted conversation history. The complete serialized snapshot
 * is protected by Android Keystore AES-256-GCM and committed atomically.
 * This is local-at-rest protection, not peer-to-peer delivery or a backup.
 */
class EncryptedMessageStore(filesDir: File) {
    private val file = AtomicFile(File(filesDir, FILE_NAME))
    private val lock = Any()

    @Throws(IOException::class)
    fun load(peerFingerprint: String): List<StoredChatMessage> = synchronized(lock) {
        require(peerFingerprint.isNotBlank() && peerFingerprint.length <= 128)
        if (!file.baseFile.exists()) return@synchronized emptyList()
        val records = decryptSnapshot()
        records.filter { it.peerFingerprint == peerFingerprint }.sortedBy { it.createdAtEpochMillis }
    }

    @Throws(IOException::class)
    fun append(message: StoredChatMessage): Boolean = synchronized(lock) {
        val records = if (file.baseFile.exists()) decryptSnapshot().toMutableList() else mutableListOf()
        val existing = records.firstOrNull { it.id == message.id }
        if (existing != null) {
            if (existing.peerFingerprint != message.peerFingerprint ||
                existing.direction != message.direction || existing.body != message.body) {
                throw IOException("A message identifier was reused for different conversation content.")
            }
            return@synchronized false
        }
        records += message
        val bounded = records.sortedBy { it.createdAtEpochMillis }.takeLast(MAX_MESSAGES)
        writeSnapshot(bounded)
        true
    }

    @Throws(IOException::class)
    fun containsMessage(peerFingerprint: String, messageId: UUID): Boolean = synchronized(lock) {
        if (!file.baseFile.exists()) return@synchronized false
        decryptSnapshot().any { it.peerFingerprint == peerFingerprint && it.id == messageId }
    }

    /**
     * Applies an outgoing delivery transition only to a message belonging to
     * this peer. An authenticated peer cannot acknowledge another contact's
     * message by guessing or replaying a UUID.
     */
    @Throws(IOException::class)
    fun updateOutgoingStatus(peerFingerprint: String, messageId: UUID, status: StoredMessageStatus): Boolean = synchronized(lock) {
        require(status in setOf(StoredMessageStatus.SENT, StoredMessageStatus.DELIVERED, StoredMessageStatus.FAILED))
        if (!file.baseFile.exists()) return@synchronized false
        val records = decryptSnapshot().toMutableList()
        val index = records.indexOfFirst {
            it.id == messageId && it.peerFingerprint == peerFingerprint && it.direction == StoredMessageDirection.OUTGOING
        }
        if (index < 0) return@synchronized false
        val current = records[index].status
        val allowed = when (current) {
            StoredMessageStatus.QUEUED -> status == StoredMessageStatus.SENT || status == StoredMessageStatus.FAILED
            StoredMessageStatus.SENT -> status == StoredMessageStatus.DELIVERED || status == StoredMessageStatus.FAILED
            StoredMessageStatus.DELIVERED -> status == StoredMessageStatus.DELIVERED
            StoredMessageStatus.FAILED -> status == StoredMessageStatus.FAILED
            StoredMessageStatus.RECEIVED -> false
        }
        if (!allowed) return@synchronized false
        records[index] = records[index].copy(status = status)
        writeSnapshot(records)
        true
    }

    @Throws(IOException::class)
    fun updateStatus(messageId: UUID, status: StoredMessageStatus) = synchronized(lock) {
        if (!file.baseFile.exists()) return@synchronized
        val records = decryptSnapshot().map {
            if (it.id == messageId) it.copy(status = status) else it
        }
        writeSnapshot(records)
    }

    private fun decryptSnapshot(): List<StoredChatMessage> {
        return try {
            val raw = file.openRead().use { it.readBytes() }
            val input = DataInputStream(ByteArrayInputStream(raw))
            require(input.readInt() == FILE_VERSION) { "Unsupported message history version." }
            val ivLength = input.readInt()
            require(ivLength == GCM_IV_BYTES) { "Invalid message history IV." }
            val iv = ByteArray(ivLength).also(input::readFully)
            val encrypted = ByteArray(input.available()).also(input::readFully)
            require(encrypted.size in 16..MAX_CIPHERTEXT_BYTES) { "Invalid message history ciphertext size." }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(byteArrayOf(FILE_VERSION.toByte()))
            decode(cipher.doFinal(encrypted))
        } catch (error: Exception) {
            throw IOException("Encrypted message history could not be authenticated or decoded.", error)
        }
    }

    private fun writeSnapshot(records: List<StoredChatMessage>): Unit {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(byteArrayOf(FILE_VERSION.toByte()))
            val encrypted = cipher.doFinal(encode(records))
            require(encrypted.size <= MAX_CIPHERTEXT_BYTES) { "Message history exceeds storage limit." }
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use {
                it.writeInt(FILE_VERSION)
                it.writeInt(cipher.iv.size)
                it.write(cipher.iv)
                it.write(encrypted)
            }
            val stream = file.startWrite()
            try {
                stream.write(bytes.toByteArray())
                stream.fd.sync()
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        } catch (error: Exception) {
            if (error is IOException) throw error
            throw IOException("Could not securely save message history.", error)
        }
        return
    }

    private fun encode(records: List<StoredChatMessage>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(records.size)
            records.forEach { message ->
                out.writeLong(message.id.mostSignificantBits)
                out.writeLong(message.id.leastSignificantBits)
                out.writeUTF(message.peerFingerprint)
                out.writeByte(message.direction.ordinal)
                out.writeByte(message.status.ordinal)
                out.writeLong(message.createdAtEpochMillis)
                val body = message.body.toByteArray(Charsets.UTF_8)
                require(body.size <= MAX_STORED_MESSAGE_BODY_BYTES)
                out.writeInt(body.size)
                out.write(body)
            }
        }
        return bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): List<StoredChatMessage> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val count = input.readInt()
        require(count in 0..MAX_MESSAGES) { "Invalid message history record count." }
        val records = List(count) {
            val id = UUID(input.readLong(), input.readLong())
            val peer = input.readUTF()
            val direction = StoredMessageDirection.entries.getOrNull(input.readUnsignedByte())
                ?: throw IOException("Invalid message direction.")
            val status = StoredMessageStatus.entries.getOrNull(input.readUnsignedByte())
                ?: throw IOException("Invalid message status.")
            val timestamp = input.readLong()
            val length = input.readInt()
            require(length in 1..MAX_STORED_MESSAGE_BODY_BYTES && length <= input.available()) { "Invalid stored message length." }
            val body = ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
            StoredChatMessage(id, peer, direction, status, timestamp, body)
        }
        require(input.available() == 0) { "Unexpected trailing message history data." }
        return records
    }

    private fun existingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IOException("Message history encryption key is missing.")
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private companion object {
        const val FILE_NAME = "encrypted-message-history.bin"
        const val KEY_ALIAS = "tomchat.message-history.aes-gcm.v1"
        const val FILE_VERSION = 1
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val MAX_BODY_BYTES = 16 * 1024
        const val MAX_MESSAGES = 500
        const val MAX_CIPHERTEXT_BYTES = 8 * 1024 * 1024
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
