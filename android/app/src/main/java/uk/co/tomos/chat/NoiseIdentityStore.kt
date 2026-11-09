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

data class NoiseIdentity(val privateKey: ByteArray, val publicKey: ByteArray)

/**
 * Persists the Noise X25519 static secret encrypted by a non-exportable
 * Android Keystore AES-GCM key. The raw secret is only returned to the caller
 * for the Rust Noise core; it is never written to disk in plaintext.
 */
class NoiseIdentityStore(filesDir: File) {
    private val file = AtomicFile(File(filesDir, "noise-identity.bin"))
    private val wrappingAlias = "tomchat.noise-identity.wrap.aes-gcm.v1"

    @Synchronized
    @Throws(IOException::class)
    fun getOrCreate(): NoiseIdentity {
        if (file.baseFile.exists()) return load()
        val privateKey = X25519PrivateKeyParameters(SecureRandom()).encoded
        val publicKey = X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded
        save(privateKey, publicKey)
        return NoiseIdentity(privateKey, publicKey)
    }

    @Synchronized
    @Throws(IOException::class)
    private fun load(): NoiseIdentity {
        try {
            val raw = file.openRead().use { it.readBytes() }
            val input = DataInputStream(ByteArrayInputStream(raw))
            require(input.readInt() == FILE_VERSION) { "Unsupported Noise identity file version." }
            val ivLength = input.readInt()
            require(ivLength == 12) { "Invalid Noise identity IV." }
            val iv = ByteArray(ivLength).also(input::readFully)
            val encrypted = ByteArray(input.available()).also(input::readFully)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingWrappingKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(byteArrayOf(FILE_VERSION.toByte()))
            val clear = DataInputStream(ByteArrayInputStream(cipher.doFinal(encrypted)))
            val privateKey = ByteArray(32).also(clear::readFully)
            val publicKey = ByteArray(32).also(clear::readFully)
            require(clear.available() == 0) { "Unexpected trailing Noise identity data." }
            val derived = X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded
            require(MessageDigestCompat.constantTimeEquals(derived, publicKey)) { "Noise identity key pair is inconsistent." }
            return NoiseIdentity(privateKey, publicKey)
        } catch (error: Exception) {
            throw IOException("Noise identity could not be decrypted or validated; existing data was not changed.", error)
        }
    }

    @Synchronized
    @Throws(IOException::class)
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
                it.writeInt(FILE_VERSION); it.writeInt(cipher.iv.size); it.write(cipher.iv); it.write(encrypted)
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
            throw IOException("Could not securely store Noise identity.", error)
        }
    }

    private fun existingWrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(wrappingAlias, null) as? SecretKey
            ?: throw IOException("Noise identity wrapping key is missing.")
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(wrappingAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(wrappingAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private companion object {
        const val FILE_VERSION = 1
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** Avoids ordinary array equality for validating key material. */
private object MessageDigestCompat {
    fun constantTimeEquals(first: ByteArray, second: ByteArray): Boolean =
        java.security.MessageDigest.isEqual(first, second)
}
