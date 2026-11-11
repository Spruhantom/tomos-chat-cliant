package uk.co.tomos.chat

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct TCP endpoint used by automatic local peer discovery. It binds only to the selected local
 * interface and never registers with a directory, relay or rendezvous host.
 */
class DirectPeerListener(
    private val bindAddress: InetSocketAddress,
    private val backlog: Int = 1
) : AutoCloseable {
    private val server = ServerSocket()
    private val closed = AtomicBoolean(false)

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        check(!server.isBound) { "Listener has already started." }
        require(backlog in 1..8)
        server.reuseAddress = false
        server.bind(bindAddress, backlog)
        server.soTimeout = ACCEPT_TIMEOUT_MS
    }

    @Throws(IOException::class)
    fun acceptAuthenticated(
        authenticator: DirectPeerAuthenticator
    ): AuthenticatedPeerConnection {
        check(server.isBound && !closed.get()) { "Listener is not active." }
        val socket = server.accept()
        return try {
            authenticator.authenticate(socket)
        } catch (error: Exception) {
            try { socket.close() } catch (_: Exception) { }
            if (error is IOException) throw error
            throw IOException("Peer authentication failed.", error)
        }
    }

    val localPort: Int get() = server.localPort

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { server.close() } catch (_: Exception) { }
        }
    }

    companion object { private const val ACCEPT_TIMEOUT_MS = 60_000 }
}

object DirectPeerConnector {
    @Throws(IOException::class)
    fun connect(
        address: String,
        port: Int,
        authenticator: DirectPeerAuthenticator,
        timeoutMs: Int = 8_000
    ): AuthenticatedPeerConnection {
        require(address.isNotBlank() && address.length <= 253)
        require(port in 1..65535)
        require(timeoutMs in 1..30_000)
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(address, port), timeoutMs)
            return authenticator.authenticate(socket)
        } catch (error: Exception) {
            try { socket.close() } catch (_: Exception) { }
            if (error is IOException) throw error
            throw IOException("Could not establish a direct peer connection.", error)
        }
    }
}
