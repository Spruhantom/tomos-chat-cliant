package uk.co.tomos.chat

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Identity-signed short-lived DHT value containing only signed endpoint hints. */
data class RendezvousRecord(
    val deviceId: ByteArray,
    val sequence: Long,
    val issuedAtSeconds: Long,
    val expiresAtSeconds: Long,
    val candidates: List<ByteArray>,
    val signature: ByteArray
) {
    fun signingBytes(): ByteArray {
        require(deviceId.size == 16 && deviceId.any { it.toInt() != 0 })
        require(candidates.isNotEmpty() && candidates.size <= MAX_CANDIDATES)
        require(expiresAtSeconds > issuedAtSeconds &&
            expiresAtSeconds - issuedAtSeconds <= MAX_TTL_SECONDS)
        require(candidates.all { it.size in 119..135 })
        val size = 2 + 1 + 16 + 8 + 8 + 8 + 1 + candidates.sumOf { 2 + it.size }
        val out = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
            .put(byteArrayOf(0x54, 0x52, 1))
            .put(deviceId).putLong(sequence).putLong(issuedAtSeconds)
            .putLong(expiresAtSeconds).put(candidates.size.toByte())
        candidates.forEach { out.putShort(it.size.toShort()).put(it) }
        return out.array()
    }

    fun sign(privateKey: java.security.PrivateKey): RendezvousRecord =
        copy(signature = DeviceSignatureCodec.sign(signingBytes(), privateKey))

    fun verify(
        nowSeconds: Long,
        trustedPublicKey: ByteArray,
        expectedDeviceId: ByteArray
    ): Boolean {
        if (expectedDeviceId.size != 16 || !deviceId.contentEquals(expectedDeviceId)) return false
        if (issuedAtSeconds > nowSeconds + MAX_CLOCK_SKEW_SECONDS || expiresAtSeconds <= nowSeconds) return false
        if (signature.size != 64) return false
        return try {
            candidates.forEach { PeerEndpointCandidate.decode(it, nowSeconds) }
            DeviceSignatureCodec.verify(signingBytes(), signature, trustedPublicKey) &&
                candidates.all {
                    PeerEndpointCandidate.decode(it, nowSeconds).verifySignature(
                        trustedPublicKey, expectedDeviceId
                    )
                }
        } catch (_: Exception) { false }
    }

    fun encode(): ByteArray {
        require(signature.size == 64)
        return signingBytes() + signature
    }

    companion object {
        const val MAX_CANDIDATES = 8
        const val MAX_TTL_SECONDS = 300L
        const val MAX_CLOCK_SKEW_SECONDS = 30L

        fun decode(bytes: ByteArray): RendezvousRecord {
            require(bytes.size in 110..1196)
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            require(input.get() == 0x54.toByte() && input.get() == 0x52.toByte() && input.get() == 1.toByte())
            val deviceId = ByteArray(16).also(input::get)
            val sequence = input.long
            val issued = input.long
            val expires = input.long
            val count = input.get().toInt() and 0xff
            require(count in 1..MAX_CANDIDATES)
            val candidates = ArrayList<ByteArray>(count)
            repeat(count) {
                require(input.remaining() >= 2 + 64)
                val length = input.short.toInt() and 0xffff
                require(length in 119..135 && input.remaining() >= length + 64)
                candidates += ByteArray(length).also(input::get)
            }
            require(input.remaining() == 64)
            val signature = ByteArray(64).also(input::get)
            return RendezvousRecord(deviceId, sequence, issued, expires, candidates, signature)
                .also { it.signingBytes() }
        }
    }
}
