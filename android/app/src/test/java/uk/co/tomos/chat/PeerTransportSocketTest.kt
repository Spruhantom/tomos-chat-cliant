package uk.co.tomos.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PeerTransportSocketTest {
    @Test
    fun tcpAdapterExposesStreamsAndTimeoutControls() {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { server ->
            val accepted = executor.submit<Socket> {
                server.accept()
            }
            val raw = Socket("127.0.0.1", server.localPort)
            val peer = accepted.get(5, TimeUnit.SECONDS)
            val transport = TcpPeerTransportSocket(raw)
            try {
                transport.readTimeoutMs = 1_500
                transport.tcpNoDelay = true
                assertTrue(transport.isConnected)
                assertFalse(transport.isClosed)
                transport.outputStream.write(byteArrayOf(0x54, 0x43))
                transport.outputStream.flush()
                assertTrue(peer.getInputStream().read() == 0x54)
                assertTrue(peer.getInputStream().read() == 0x43)
            } finally {
                transport.close()
                peer.close()
            }
        }
        executor.shutdownNow()
    }
}
