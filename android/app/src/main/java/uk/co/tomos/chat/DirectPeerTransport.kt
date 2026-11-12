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
    /** Connect to a signed public endpoint hint. The endpoint signature and
     * device ID are checked against the trusted contact before opening a socket;
     * DirectPeerAuthenticator still performs the pinned identity/Noise checks.
     */
    @Throws(IOException::class)
    fun connectCandidate(
        candidate: PeerEndpointCandidate,
        nowSeconds: Long,
        trustedContact: TrustedContact,
        authenticator: DirectPeerAuthenticator,
        timeoutMs: Int = 8_000
    ): AuthenticatedPeerConnection {
        try {
            candidate.validateAt(nowSeconds)
            require(candidate.transport == PeerEndpointCandidate.Transport.TCP) {
                "This connector currently supports TCP candidates only."
            }
            require(candidate.verifySignature(trustedContact.encodedPublicKey, DeviceSignatureCodec.deviceId(trustedContact.encodedPublicKey))) {
                "Peer endpoint signature or trusted device binding is invalid."
            }
            return connect(candidate.address.hostAddress ?: throw IOException("Peer address is unavailable."),
                candidate.port, authenticator, timeoutMs)
        } catch (error: Exception) {
            if (error is IOException) throw error
            throw IOException("Signed peer endpoint candidate was rejected.", error)
        }
    }

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
