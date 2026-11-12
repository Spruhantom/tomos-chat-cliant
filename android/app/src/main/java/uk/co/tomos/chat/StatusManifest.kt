package uk.co.tomos.chat

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Signed status metadata; the separately encrypted payload is transferred
 * directly to selected recipients. This codec is not payload encryption.
 */
data class StatusManifest(
    val statusId: ByteArray,
    val authorDeviceId: ByteArray,
    val createdAtSeconds: Long,
    val expiresAtSeconds: Long,
    val audience: Audience,
    val audienceDigest: ByteArray,
    val contentType: ContentType,
    val ciphertextLength: Int,
    val ciphertextDigest: ByteArray,
    val signature: ByteArray
) {
    enum class Audience(val wireId: Int) {
        ALL_TRUSTED_CONTACTS(1), SELECTED_CONTACTS(2), GROUP(3)
    }
    enum class ContentType(val wireId: Int) { TEXT(1), PHOTO(2) }

    fun signingBytes(): ByteArray {
        validateShape()
        return ByteBuffer.allocate(SIGNED_LENGTH).order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC).put(VERSION.toByte()).put(audience.wireId.toByte())
            .put(statusId).put(authorDeviceId)
            .putLong(createdAtSeconds).putLong(expiresAtSeconds)
            .put(audienceDigest).put(contentType.wireId.toByte())
            .putInt(ciphertextLength).put(ciphertextDigest).array()
    }


    fun sign(privateKey: java.security.PrivateKey): StatusManifest =
        copy(signature = DeviceSignatureCodec.sign(signingBytes(), privateKey))

    fun verifySignature(encodedTrustedPublicKey: ByteArray, expectedAuthorDeviceId: ByteArray): Boolean =
        expectedAuthorDeviceId.size == 16 && authorDeviceId.contentEquals(expectedAuthorDeviceId) &&
            DeviceSignatureCodec.verify(signingBytes(), signature, encodedTrustedPublicKey)

    fun encode(): ByteArray = signingBytes() + signature

    fun validateAt(nowSeconds: Long) {
        validateShape()
        require(createdAtSeconds <= nowSeconds + MAX_FUTURE_SKEW_SECONDS) {
            "Status creation time is too far in the future."
        }
        require(expiresAtSeconds > nowSeconds) { "Status has expired." }
    }

    private fun validateShape() {
        require(statusId.size == 16 && statusId.any { it.toInt() != 0 }) { "Invalid status ID." }
        require(authorDeviceId.size == 16 && authorDeviceId.any { it.toInt() != 0 }) { "Invalid author device ID." }
        require(createdAtSeconds >= 0 && expiresAtSeconds > createdAtSeconds &&\n            expiresAtSeconds - createdAtSeconds == STATUS_TTL_SECONDS) {
            "Status must expire exactly 24 hours after creation."
        }
        require(audienceDigest.size == 32) { "Invalid audience digest." }
        val zeroAudience = audienceDigest.all { it.toInt() == 0 }
        require((audience == Audience.ALL_TRUSTED_CONTACTS) == zeroAudience) {
            "Status audience digest is inconsistent."
        }
        require(ciphertextLength in 1..MAX_CIPHERTEXT_BYTES) { "Invalid encrypted status size." }
        require(ciphertextDigest.size == 32 && ciphertextDigest.any { it.toInt() != 0 }) {
            "Invalid encrypted status digest."
        }
        require(signature.size == SIGNATURE_BYTES) { "Invalid status signature size." }
    }

    companion object {
        const val VERSION = 1
        const val STATUS_TTL_SECONDS = 86_400L
        const val MAX_FUTURE_SKEW_SECONDS = 30L
        const val MAX_CIPHERTEXT_BYTES = 10 * 1024 * 1024
        const val SIGNATURE_BYTES = 64
        const val SIGNED_LENGTH = 121
        const val MANIFEST_LENGTH = SIGNED_LENGTH + SIGNATURE_BYTES
        private val MAGIC = byteArrayOf(0x54, 0x53)

        fun decode(encoded: ByteArray, nowSeconds: Long): StatusManifest {
            require(encoded.size == MANIFEST_LENGTH) { "Invalid status manifest length." }
            val input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
            require(input.get() == MAGIC[0] && input.get() == MAGIC[1]) { "Invalid status manifest magic." }
            require((input.get().toInt() and 0xff) == VERSION) { "Unsupported status manifest version." }
            val audienceId = input.get().toInt() and 0xff
            val audience = Audience.entries.firstOrNull { it.wireId == audienceId }
                ?: throw IllegalArgumentException("Invalid status audience.")
            val statusId = ByteArray(16).also(input::get)
            val author = ByteArray(16).also(input::get)
            val created = input.long
            val expires = input.long
            val audienceDigest = ByteArray(32).also(input::get)
            val contentId = input.get().toInt() and 0xff
            val contentType = ContentType.entries.firstOrNull { it.wireId == contentId }
                ?: throw IllegalArgumentException("Invalid status content type.")
            val ciphertextLength = input.int
            val ciphertextDigest = ByteArray(32).also(input::get)
            val signature = ByteArray(SIGNATURE_BYTES).also(input::get)
            return StatusManifest(statusId, author, created, expires, audience,
                audienceDigest, contentType, ciphertextLength, ciphertextDigest, signature)
                .also { it.validateAt(nowSeconds) }
        }
    }
}
