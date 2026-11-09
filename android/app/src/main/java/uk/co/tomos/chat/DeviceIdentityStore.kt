package uk.co.tomos.chat

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec

/** Device-held P-256 signing identity. Private key material never leaves Android Keystore. */
class DeviceIdentityStore {
    private val alias = "tomchat.device-identity.ecdsa-p256.v1"

    @Synchronized
    @Throws(IOException::class)
    fun getOrCreate(): KeyPair {
        try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            if (existing != null) return KeyPair(existing.certificate.publicKey, existing.privateKey)
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            return generator.generateKeyPair()
        } catch (error: Exception) {
            throw IOException("Could not access the device identity in Android Keystore.", error)
        }
    }
}
