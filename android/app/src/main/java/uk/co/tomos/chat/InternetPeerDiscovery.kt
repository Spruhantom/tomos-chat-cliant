package uk.co.tomos.chat

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Rendezvous query sent only inside an authenticated peer-to-peer channel. */
data class RendezvousQuery(
    val targetDeviceId: ByteArray,
    val requesterDeviceId: ByteArray,
    val nonce: ByteArray,
    val issuedAtSeconds: Long
) {
    fun encode(): ByteArray {
        require(targetDeviceId.size == 16 && targetDeviceId.any { it.toInt() != 0 })
        require(requesterDeviceId.size == 16 && requesterDeviceId.any { it.toInt() != 0 })
        require(nonce.size == 16 && nonce.any { it.toInt() != 0 })
        return ByteBuffer.allocate(59).order(ByteOrder.BIG_ENDIAN)
            .put(byteArrayOf(0x54, 0x52, 2))
            .put(targetDeviceId).put(requesterDeviceId).put(nonce)
            .putLong(issuedAtSeconds).array()
    }
}

/** Signed UDP reachability probe. It is not an application data channel. */
data class HolePunchProbe(
    val sessionId: ByteArray,
    val nonce: ByteArray,
    val deviceId: ByteArray,
    val sentAtSeconds: Long,
    val signature: ByteArray
) {
    fun signingBytes(): ByteArray {
        require(sessionId.size == 16 && sessionId.any { it.toInt() != 0 })
        require(nonce.size == 16 && nonce.any { it.toInt() != 0 })
        require(deviceId.size == 16 && deviceId.any { it.toInt() != 0 })
        return ByteBuffer.allocate(59).order(ByteOrder.BIG_ENDIAN)
            .put(byteArrayOf(0x54, 0x48, 1))
            .put(sessionId).put(nonce).put(deviceId).putLong(sentAtSeconds).array()
    }

    fun sign(privateKey: java.security.PrivateKey): HolePunchProbe =
        copy(signature = DeviceSignatureCodec.sign(signingBytes(), privateKey))

    fun verify(nowSeconds: Long, trustedPublicKey: ByteArray, expectedDeviceId: ByteArray): Boolean =
        expectedDeviceId.size == 16 && deviceId.contentEquals(expectedDeviceId) &&
            sentAtSeconds <= nowSeconds + MAX_CLOCK_SKEW_SECONDS &&
            nowSeconds - sentAtSeconds <= MAX_RECORD_AGE_SECONDS &&
            DeviceSignatureCodec.verify(signingBytes(), signature, trustedPublicKey)

    fun encode(): ByteArray {
        require(signature.size == 64)
        return signingBytes() + signature
    }

    companion object {
        const val MAX_CLOCK_SKEW_SECONDS = 30L
        const val MAX_RECORD_AGE_SECONDS = 300L
        fun decode(bytes: ByteArray): HolePunchProbe {
            require(bytes.size == 123)
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            require(input.get() == 0x54.toByte() && input.get() == 0x48.toByte() && input.get() == 1.toByte())
            val session = ByteArray(16).also(input::get)
            val nonce = ByteArray(16).also(input::get)
            val device = ByteArray(16).also(input::get)
            val timestamp = input.long
            val signature = ByteArray(64).also(input::get)
            return HolePunchProbe(session, nonce, device, timestamp, signature)
        }
    }
}

/** IPv6-first ordering shared with the Rust route selector. */
object PeerRoutePolicy {
    fun prioritise(candidates: List<PeerEndpointCandidate>): List<PeerEndpointCandidate> =
        candidates.sortedWith(compareBy<PeerEndpointCandidate> {
            if (it.address is java.net.Inet6Address) 0 else 1
        }.thenByDescending { it.issuedAtSeconds })

    fun reconnectDelayMillis(attempt: Int, jitterSeed: Long): Long {
        val exponent = attempt.coerceIn(0, 6)
        val base = (1L shl exponent).coerceAtMost(60L)
        val jitter = ((jitterSeed xor (attempt.toLong() * -7046029254386353131L)) and Long.MAX_VALUE) % (base / 4 + 1)
        return ((base + jitter).coerceAtMost(75L)) * 1_000L
    }
}

/**
 * Explicitly opt-in NAT-PMP mapping. The caller supplies the selected gateway;
 * this class never scans for gateways or creates mappings automatically.
 */
class NatPmpClient(
    private val gateway: Inet4Address,
    private val timeoutMs: Int = 1_500
) {
    init {
        require(!gateway.isAnyLocalAddress && !gateway.isMulticastAddress)
        require(timeoutMs in 100..5_000)
    }

    fun publicAddress(): Inet4Address {
        val response = exchange(byteArrayOf(0, 0), 0)
        require(response.size == 12)
        return InetAddress.getByAddress(response.copyOfRange(8, 12)) as Inet4Address
    }

    fun mapUdp(privatePort: Int, suggestedPublicPort: Int, leaseSeconds: Long): Pair<Int, Long> {
        require(privatePort in 1..65535 && suggestedPublicPort in 0..65535)
        require(leaseSeconds in 1..MAX_LEASE_SECONDS)
        val request = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .put(0).put(1).putShort(0).putShort(privatePort.toShort())
            .putShort(suggestedPublicPort.toShort()).putInt(leaseSeconds.toInt()).array()
        val response = exchange(request, 1)
        require(response.size == 16)
        val buffer = ByteBuffer.wrap(response).order(ByteOrder.BIG_ENDIAN)
        buffer.position(10)
        return (buffer.short.toInt() and 0xffff) to (buffer.int.toLong() and 0xffff_ffffL)
    }

    fun deleteUdp(privatePort: Int, publicPort: Int) {
        require(privatePort in 1..65535 && publicPort in 1..65535)
        val request = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .put(0).put(1).putShort(0).putShort(privatePort.toShort())
            .putShort(publicPort.toShort()).putInt(0).array()
        exchange(request, 1)
    }

    private fun exchange(request: ByteArray, opcode: Int): ByteArray {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val target = InetSocketAddress(gateway, NAT_PMP_PORT)
            socket.send(DatagramPacket(request, request.size, target))
            val buffer = ByteArray(64)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            require(packet.address == gateway && packet.port == NAT_PMP_PORT)
            require(packet.length >= 8 && buffer[0] == 0.toByte() &&
                (buffer[1].toInt() and 0xff) == (opcode or 0x80))
            val result = ((buffer[2].toInt() and 0xff) shl 8) or (buffer[3].toInt() and 0xff)
            require(result == 0) { "NAT-PMP gateway rejected the request (code $result)." }
            return buffer.copyOf(packet.length)
        }
    }

    companion object {
        const val NAT_PMP_PORT = 5351
        const val MAX_LEASE_SECONDS = 600L
    }
}
