package uk.co.tomos.chat

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PearTransportIdentity(val privateKey: ByteArray, val publicKey: ByteArray)

/**
 * Persists a dedicated X25519 identity for Hyperswarm. The secret is wrapped
 * by a non-exportable Android Keystore AES-GCM key and is never stored as
 * plaintext. It is intentionally separate from the retired Noise identity.
 */
class PearTransportIdentityStore(filesDir: File) {
    private val file = AtomicFile(File(filesDir, "pear-transport-identity.bin"))
    private val wrappingAlias = "tomchat.pear-transport.wrap.aes-gcm.v1"

    @Synchronized
    @Throws(IOException::class)
    fun getOrCreate(): PearTransportIdentity {
        if (file.baseFile.exists()) return load()
        val privateKey = X25519PrivateKeyParameters(SecureRandom()).encoded
        val publicKey = X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded
        save(privateKey, publicKey)
        return PearTransportIdentity(privateKey, publicKey)
    }

    @Synchronized
    private fun load(): PearTransportIdentity {
        try {
            val input = DataInputStream(ByteArrayInputStream(file.openRead().use { it.readBytes() }))
            require(input.readInt() == FILE_VERSION) { "Unsupported Pear identity file version." }
            val ivSize = input.readInt()
            require(ivSize == 12) { "Invalid Pear identity IV." }
            val iv = ByteArray(ivSize).also(input::readFully)
            val encrypted = ByteArray(input.available()).also(input::readFully)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingWrappingKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(byteArrayOf(FILE_VERSION.toByte()))
            val clear = DataInputStream(ByteArrayInputStream(cipher.doFinal(encrypted)))
            val privateKey = ByteArray(32).also(clear::readFully)
            val publicKey = ByteArray(32).also(clear::readFully)
            require(clear.available() == 0)
            val derived = X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded
            require(java.security.MessageDigest.isEqual(derived, publicKey))
            return PearTransportIdentity(privateKey, publicKey)
        } catch (error: Exception) {
            throw IOException("Pear transport identity could not be decrypted or validated; existing data was not changed.", error)
        }
    }

    @Synchronized
    private fun save(privateKey: ByteArray, publicKey: ByteArray) {
        try {
            require(privateKey.size == 32 && publicKey.size == 32)
            val clear = ByteArrayOutputStream()
            DataOutputStream(clear).use { it.write(privateKey); it.write(publicKey) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
            cipher.updateAAD(byteArrayOf(FILE_VERSION.toByte()))
            val encrypted = cipher.doFinal(clear.toByteArray())
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use {
                it.writeInt(FILE_VERSION)
                it.writeInt(cipher.iv.size)
                it.write(cipher.iv)
                it.write(encrypted)
            }
            val stream = file.startWrite()
            try {
                stream.write(output.toByteArray())
                stream.fd.sync()
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        } catch (error: Exception) {
            if (error is IOException) throw error
            throw IOException("Could not securely store Pear transport identity.", error)
        }
    }

    private fun existingWrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(wrappingAlias, null) as? SecretKey
            ?: throw IOException("Pear identity wrapping key is missing.")
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(wrappingAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(wrappingAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val FILE_VERSION = 1
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
