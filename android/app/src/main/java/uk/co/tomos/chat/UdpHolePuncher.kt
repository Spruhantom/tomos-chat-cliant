package uk.co.tomos.chat

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Performs only the signed UDP reachability phase. The returned socket is not
 * an authenticated messaging channel; the caller must next run a Noise
 * handshake over a datagram-aware transport before sending application data.
 */
object UdpHolePuncher {
    @Throws(IOException::class)
    fun punch(
        socket: DatagramSocket,
        remote: InetSocketAddress,
        localProbe: HolePunchProbe,
        trustedPeerPublicKey: ByteArray,
        expectedPeerDeviceId: ByteArray,
        nowSeconds: () -> Long,
        timeoutMs: Int = 3_000
    ): DatagramSocket {
        require(!socket.isClosed && !remote.isUnresolved)
        require(remote.port in 1..65535 && timeoutMs in 250..10_000)
        val encoded = localProbe.encode()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        socket.soTimeout = 250
        val buffer = ByteArray(256)
        var lastSend = 0L
        while (System.nanoTime() < deadline) {
            val now = System.nanoTime()
            if (now - lastSend >= 100_000_000L) {
                socket.send(DatagramPacket(encoded, encoded.size, remote))
                lastSend = now
            }
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                if (packet.address != remote.address || packet.port != remote.port) continue
                val probe = HolePunchProbe.decode(packet.data.copyOf(packet.length))
                if (!probe.sessionId.contentEquals(localProbe.sessionId)) continue
                if (!probe.verify(nowSeconds(), trustedPeerPublicKey, expectedPeerDeviceId)) continue
                return socket
            } catch (_: SocketTimeoutException) {
                // Continue the bounded simultaneous-open attempt.
            } catch (error: IllegalArgumentException) {
                // Ignore malformed or unauthenticated datagrams.
            }
        }
        throw IOException("Direct UDP path could not be established; peer may be behind restrictive NAT or firewall.")
    }
}
