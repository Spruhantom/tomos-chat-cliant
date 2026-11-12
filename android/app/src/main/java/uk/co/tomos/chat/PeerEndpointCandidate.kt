package uk.co.tomos.chat

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A signed public endpoint hint. Signature verification is deliberately
 * separate: verify signingBytes() against the contact's trusted signing key
 * before connecting. Noise X25519 keys are not signing keys.
 */
data class PeerEndpointCandidate(
    val transport: Transport,
    val issuedAtSeconds: Long,
    val expiresAtSeconds: Long,
    val deviceId: ByteArray,
    val nonce: ByteArray,
    val address: InetAddress,
    val port: Int,
    val signature: ByteArray
) {
    enum class Transport(val wireId: Int) { TCP(1), UDP(2) }

    fun signingBytes(): ByteArray {
        validateShape()
        val addressBytes = address.address
        return ByteBuffer.allocate(4 + 8 + 8 + 16 + 16 + 1 + addressBytes.size + 2)
            .order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)
            .put(VERSION.toByte())
            .put(transport.wireId.toByte())
            .putLong(issuedAtSeconds)
            .putLong(expiresAtSeconds)
            .put(deviceId)
            .put(nonce)
            .put(if (address is Inet4Address) 4 else 6)
            .put(addressBytes)
            .putShort(port.toShort())
            .array()
    }

    fun encode(): ByteArray = signingBytes() + signature

    fun validateAt(nowSeconds: Long) {
        validateShape()
        require(issuedAtSeconds <= nowSeconds + MAX_FUTURE_SKEW_SECONDS) { "Peer candidate issue time is in the future." }
        require(expiresAtSeconds > nowSeconds) { "Peer candidate has expired." }
    }

    private fun validateShape() {
        require(deviceId.size == 16 && deviceId.any { it.toInt() != 0 }) { "Invalid device identity." }
        require(nonce.size == 16 && nonce.any { it.toInt() != 0 }) { "Invalid candidate nonce." }
        require(signature.size == SIGNATURE_BYTES) { "Invalid detached signature size." }
        require(port in 1..65535) { "Invalid peer candidate port." }
        require(expiresAtSeconds > issuedAtSeconds &&
            expiresAtSeconds - issuedAtSeconds <= MAX_TTL_SECONDS) { "Invalid peer candidate lifetime." }
        require(isPublicAddress(address)) { "Peer candidate address is not globally routable." }
    }

    companion object {
        const val VERSION = 1
        const val MAX_TTL_SECONDS = 300L
        const val MAX_FUTURE_SKEW_SECONDS = 30L
        const val SIGNATURE_BYTES = 64
        private val MAGIC = byteArrayOf(0x54, 0x50)

        fun decode(encoded: ByteArray, nowSeconds: Long): PeerEndpointCandidate {
            require(encoded.size in 119..135) { "Invalid peer candidate length." }
            val input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
            require(input.get() == MAGIC[0] && input.get() == MAGIC[1]) { "Invalid peer candidate magic." }
            require((input.get().toInt() and 0xff) == VERSION) { "Unsupported peer candidate version." }
            val transportId = input.get().toInt() and 0xff
            val transport = Transport.entries.firstOrNull { it.wireId == transportId }
                ?: throw IllegalArgumentException("Unsupported peer candidate transport.")
            val issued = input.long
            val expires = input.long
            val device = ByteArray(16).also(input::get)
            val nonce = ByteArray(16).also(input::get)
            val family = input.get().toInt() and 0xff
            val addressLength = when (family) { 4 -> 4; 6 -> 16; else -> throw IllegalArgumentException("Invalid address family.") }
            require(input.remaining() == addressLength + 2 + SIGNATURE_BYTES) { "Invalid peer candidate length." }
            val addressBytes = ByteArray(addressLength).also(input::get)
            val address = InetAddress.getByAddress(addressBytes)
            val port = input.short.toInt() and 0xffff
            val signature = ByteArray(SIGNATURE_BYTES).also(input::get)
            return PeerEndpointCandidate(transport, issued, expires, device, nonce, address, port, signature)
                .also { it.validateAt(nowSeconds) }
        }

        private fun isPublicAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress ||
                address.isLinkLocalAddress || address.isSiteLocalAddress ||
                address.isMulticastAddress) return false
            val bytes = address.address
            return when (address) {
                is Inet4Address -> {
                    val a = bytes[0].toInt() and 0xff
                    val b = bytes[1].toInt() and 0xff
                    val c = bytes[2].toInt() and 0xff
                    !(a == 0 || a == 10 || a == 100 && b in 64..127 ||
                        a == 172 && b in 16..31 || a == 192 && b == 168 ||
                        a == 192 && b == 0 && (c == 0 || c == 2) ||
                        a == 192 && b == 88 && c == 99 ||
                        a == 198 && (b == 18 || b == 19) ||
                        a == 198 && b == 51 && c == 100 ||
                        a == 203 && b == 0 && c == 113 || a >= 224)
                }
                is Inet6Address -> {
                    val first = bytes[0].toInt() and 0xff
                    val documentation = (first == 0x20 && (bytes[1].toInt() and 0xff) == 0x01 &&
                        (bytes[2].toInt() and 0xff) == 0x0d && (bytes[3].toInt() and 0xff) == 0xb8)
                    first != 0xfc && first != 0xfd && !documentation
                }
                else -> false
            }
        }
    }
}
