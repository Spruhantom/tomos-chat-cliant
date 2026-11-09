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

/**
 * Encrypted, device-local account profile. Raw date of birth is intentionally
 * not part of this model or the on-disk format.
 */
data class AccountProfile(
    val displayName: String,
    val ageBand: AgeBand,
    val ageAssurance: AgeAssuranceStatus,
    val guardianConsent: GuardianConsentStatus
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 64) { "Display name must be 1–64 characters." }
        require(ageBand != AgeBand.UNDER_FIVE) { "Under-five accounts require guardian-led setup." }
        require(
            ageBand != AgeBand.ADULT_18_PLUS || guardianConsent == GuardianConsentStatus.NOT_REQUIRED
        ) { "Adult accounts cannot have guardian consent states." }
        require(
            ageBand == AgeBand.ADULT_18_PLUS || guardianConsent != GuardianConsentStatus.NOT_REQUIRED
        ) { "Minor accounts require an explicit guardian-consent state." }
    }

    fun safetyState() = AccountSafetyState(ageBand, ageAssurance, guardianConsent)
}

class AccountProfileStore(filesDir: File) {
    private val file = AtomicFile(File(filesDir, "account-profile.bin"))
    private val keyAlias = "tomchat.account-profile.aes-gcm.v1"

    @Synchronized
    @Throws(IOException::class)
    fun load(): AccountProfile? {
        if (!file.baseFile.exists()) return null
        try {
            val bytes = file.openRead().use { it.readBytes() }
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val version = input.readInt()
            if (version != FORMAT_VERSION) throw IOException("Unsupported account profile format.")
            val ivLength = input.readInt()
            if (ivLength != GCM_IV_LENGTH) throw IOException("Invalid encrypted profile header.")
            val iv = ByteArray(ivLength).also(input::readFully)
            val encrypted = ByteArray(input.available()).also(input::readFully)
            if (encrypted.isEmpty()) throw IOException("Encrypted profile is empty.")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(FORMAT_VERSION_AAD)
            val plain = cipher.doFinal(encrypted)
            return AccountProfileCodec.decode(plain)
        } catch (e: Exception) {
            if (e is IOException) throw e
            throw IOException("The saved account profile could not be decrypted. It has not been overwritten.", e)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun save(profile: AccountProfile) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(FORMAT_VERSION_AAD)
            val encrypted = cipher.doFinal(AccountProfileCodec.encode(profile))
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use { data ->
                data.writeInt(FORMAT_VERSION)
                val iv = cipher.iv
                data.writeInt(iv.size)
                data.write(iv)
                data.write(encrypted)
            }
            val stream = file.startWrite()
            try {
                stream.write(output.toByteArray())
                stream.fd.sync()
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
        } catch (e: Exception) {
            if (e is IOException) throw e
            throw IOException("Could not encrypt or save the account profile.", e)
        }
    }

    private fun getExistingKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return keyStore.getKey(keyAlias, null) as? SecretKey
            ?: throw IOException("The account encryption key is missing. The saved profile was not changed.")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val FORMAT_VERSION_AAD = byteArrayOf(FORMAT_VERSION.toByte())
    }
}

internal object AccountProfileCodec {
    fun encode(profile: AccountProfile): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeUTF(profile.displayName)
            out.writeUTF(profile.ageBand.name)
            out.writeUTF(profile.ageAssurance.name)
            out.writeUTF(profile.guardianConsent.name)
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): AccountProfile {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val profile = AccountProfile(
            displayName = input.readUTF(),
            ageBand = AgeBand.valueOf(input.readUTF()),
            ageAssurance = AgeAssuranceStatus.valueOf(input.readUTF()),
            guardianConsent = GuardianConsentStatus.valueOf(input.readUTF())
        )
        require(input.available() == 0) { "Unexpected trailing account profile data." }
        return profile
    }
}
