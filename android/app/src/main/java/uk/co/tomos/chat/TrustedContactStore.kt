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
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class TrustedContact(
    val displayName: String,
    val encodedPublicKey: ByteArray,
    val fingerprint: String,
    val noiseStaticPublicKey: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean = other is TrustedContact &&
        displayName == other.displayName && encodedPublicKey.contentEquals(other.encodedPublicKey) &&
        fingerprint == other.fingerprint &&
        ((noiseStaticPublicKey == null && other.noiseStaticPublicKey == null) ||
            (noiseStaticPublicKey != null && other.noiseStaticPublicKey != null &&
                noiseStaticPublicKey.contentEquals(other.noiseStaticPublicKey)))
    override fun hashCode(): Int = 31 * displayName.hashCode() + encodedPublicKey.contentHashCode()
}

class TrustedContactStore(filesDir: File) {
    private val file = AtomicFile(File(filesDir, "trusted-contacts.bin"))
    private val alias = "tomchat.trusted-contacts.aes-gcm.v1"

    @Synchronized
    @Throws(IOException::class)
    fun load(): List<TrustedContact> {
        if (!file.baseFile.exists()) return emptyList()
        try {
            val input = DataInputStream(ByteArrayInputStream(file.openRead().use { it.readBytes() }))
            val version = input.readInt()
            require(version in LEGACY_VERSION..VERSION) { "Unsupported trusted-contact store version." }
            val ivSize = input.readInt()
            require(ivSize == 12) { "Invalid trusted-contact store header." }
            val iv = ByteArray(ivSize).also(input::readFully)
            val encrypted = ByteArray(input.available()).also(input::readFully)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(byteArrayOf(version.toByte()))
            decode(cipher.doFinal(encrypted), version)
        } catch (error: Exception) {
            throw IOException("Trusted contacts could not be decrypted; existing data was not changed.", error)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun save(contacts: List<TrustedContact>) {
        require(contacts.size <= 1000)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(byteArrayOf(VERSION.toByte()))
            val encrypted = cipher.doFinal(encodeV2(contacts))
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use {
                it.writeInt(VERSION); it.writeInt(cipher.iv.size); it.write(cipher.iv); it.write(encrypted)
            }
            val stream = file.startWrite()
            try {
                stream.write(output.toByteArray()); stream.fd.sync(); file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream); throw error
            }
        } catch (error: Exception) {
            if (error is IOException) throw error
            throw IOException("Could not encrypt trusted contacts.", error)
        }
    }

    private fun encodeV2(contacts: List<TrustedContact>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(contacts.size)
            contacts.forEach {
                require(it.displayName.isNotBlank() && it.displayName.length <= 64)
                require(it.encodedPublicKey.size in 1..512)
                require(ContactCardCodec.fingerprint(it.encodedPublicKey) == it.fingerprint)
                require(it.noiseStaticPublicKey == null || it.noiseStaticPublicKey.size == 32)
                out.writeUTF(it.displayName)
                out.writeInt(it.encodedPublicKey.size); out.write(it.encodedPublicKey)
                out.writeUTF(it.fingerprint)
                val noiseKey = it.noiseStaticPublicKey
                out.writeInt(noiseKey?.size ?: -1)
                if (noiseKey != null) out.write(noiseKey)
            }
        }
        return bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray, version: Int): List<TrustedContact> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val count = input.readInt()
        require(count in 0..1000)
        val contacts = List(count) {
            val name = input.readUTF()
            require(name.isNotBlank() && name.length <= 64)
            val size = input.readInt()
            require(size in 1..512)
            val key = ByteArray(size).also(input::readFully)
            val fingerprint = input.readUTF()
            require(ContactCardCodec.fingerprint(key) == fingerprint)
            val noiseKey = if (version >= VERSION) {
                val noiseSize = input.readInt()
                when (noiseSize) {
                    -1 -> null
                    32 -> ByteArray(32).also(input::readFully)
                    else -> throw IllegalArgumentException("Invalid trusted Noise public key length.")
                }
            } else null
            TrustedContact(name, key, fingerprint, noiseKey)
        }
        require(input.available() == 0)
        require(contacts.map { it.fingerprint }.distinct().size == contacts.size)
        return contacts
    }

    private fun existingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(alias, null) as? SecretKey ?: throw IOException("Trusted-contact encryption key is missing.")
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private companion object {
        const val LEGACY_VERSION = 1
        const val VERSION = 2
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
