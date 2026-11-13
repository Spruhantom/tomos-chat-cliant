package uk.co.tomos.chat

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.security.KeyPair

/**
 * Owns the Wi-Fi TCP and Bluetooth RFCOMM connectors used by automatic peer discovery and turns
 * an authenticated peer transport into a Noise-protected conversation. It has no server, relay,
 * directory, rendezvous or push-service dependency.
 */
class PeerMessagingCoordinator(
    filesDir: java.io.File,
    private val localSigningIdentity: KeyPair,
    private val localNoiseIdentity: NoiseIdentity
) : AutoCloseable {
    private val history = EncryptedMessageStore(filesDir)
    private val listenerRef = AtomicReference<DirectPeerListener?>(null)
    private val sessionRef = AtomicReference<PeerConversationSession?>(null)

    @Synchronized
    @Throws(IOException::class)
    fun startListening(bindAddress: String, port: Int): Int {
        require(port in 0..65535)
        val address = InetAddress.getByName(bindAddress)
        require(address.isAnyLocalAddress || address.isSiteLocalAddress || address.isLoopbackAddress) {
            "The listener must bind to a local network interface."
        }
        listenerRef.getAndSet(null)?.close()
        val listener = DirectPeerListener(InetSocketAddress(address, port))
        try {
            listener.start()
            listenerRef.set(listener)
            return listener.localPort
        } catch (error: Exception) {
            listener.close()
            if (error is IOException) throw error
            throw IOException("Could not start the direct peer listener.", error)
        }
    }

    @Synchronized
    fun stopListening() { listenerRef.getAndSet(null)?.close() }

    @Throws(IOException::class)
    fun accept(contact: TrustedContact): PeerConversationSession {
        val listener = listenerRef.get() ?: throw IOException("Start the direct listener before accepting a peer.")
        val authenticated = listener.acceptAuthenticated(DirectPeerAuthenticator(localSigningIdentity, contact))
        return establish(authenticated, contact, initiator = false)
    }

    @Throws(IOException::class)
    fun connect(address: String, port: Int, contact: TrustedContact): PeerConversationSession {
        val authenticated = DirectPeerConnector.connect(
            address,
            port,
            DirectPeerAuthenticator(localSigningIdentity, contact)
        )
        return establish(authenticated, contact, initiator = true)
    }

    /**
     * Connect over a signed UDP endpoint after simultaneous-open punching.
     * Both the signed endpoint candidate and the probe are checked against the
     * trusted contact before the pinned Noise XX session carries app frames.
     */
    @Throws(IOException::class)
    fun connectUdp(
        socket: DatagramSocket,
        candidate: PeerEndpointCandidate,
        localProbe: HolePunchProbe,
        nowSeconds: Long,
        contact: TrustedContact
    ): PeerConversationSession {
        validateUdpCandidate(candidate, nowSeconds, contact)
        val channel = UdpNoisePeerChannel.connectAfterPunch(
            socket,
            InetSocketAddress(candidate.address, candidate.port),
            localProbe,
            contact.encodedPublicKey,
            DeviceSignatureCodec.deviceId(contact.encodedPublicKey),
            { nowSeconds },
            localNoiseIdentity,
            contact
        )
        return publishSession(contact, channel)
    }

    /** Responder counterpart for a peer's signed UDP endpoint candidate. */
    @Throws(IOException::class)
    fun acceptUdp(
        socket: DatagramSocket,
        candidate: PeerEndpointCandidate,
        localProbe: HolePunchProbe,
        nowSeconds: Long,
        contact: TrustedContact,
        timeoutMs: Int = 30_000
    ): PeerConversationSession {
        validateUdpCandidate(candidate, nowSeconds, contact)
        val channel = UdpNoisePeerChannel.acceptAfterPunch(
            socket,
            InetSocketAddress(candidate.address, candidate.port),
            localProbe,
            contact.encodedPublicKey,
            DeviceSignatureCodec.deviceId(contact.encodedPublicKey),
            { nowSeconds },
            localNoiseIdentity,
            contact,
            timeoutMs
        )
        return publishSession(contact, channel)
    }

    private fun validateUdpCandidate(
        candidate: PeerEndpointCandidate,
        nowSeconds: Long,
        contact: TrustedContact
    ) {
        candidate.validateAt(nowSeconds)
        require(candidate.transport == PeerEndpointCandidate.Transport.UDP) {
            "The peer candidate is not a UDP endpoint."
        }
        require(candidate.verifySignature(
            contact.encodedPublicKey,
            DeviceSignatureCodec.deviceId(contact.encodedPublicKey)
        )) { "The UDP endpoint signature does not match the trusted contact." }
    }

    private fun publishSession(
        contact: TrustedContact,
        channel: EncryptedPeerTransport
    ): PeerConversationSession {
        val session = PeerConversationSession(contact.fingerprint, channel, history)
        sessionRef.getAndSet(session)?.close()
        return session
    }

    /** Accept an RFCOMM connection and run the exact same identity + Noise protocol as TCP. */
    @Throws(IOException::class)
    @SuppressLint("MissingPermission")
    fun acceptBluetooth(server: BluetoothServerSocket, contact: TrustedContact): PeerConversationSession {
        val socket = try {
            server.accept(BLUETOOTH_ACCEPT_TIMEOUT_MS)
        } catch (error: Exception) {
            if (error is IOException) throw error
            throw IOException("Could not accept the direct Bluetooth connection.", error)
        }
        return establishBluetooth(socket, contact, initiator = false)
    }

    /** Connect to a previously discovered Bluetooth peer without a relay. */
    @Throws(IOException::class)
    @SuppressLint("MissingPermission")
    fun connectBluetooth(device: BluetoothDevice, contact: TrustedContact): PeerConversationSession {
        val socket = try {
            device.createRfcommSocketToServiceRecord(BLUETOOTH_SERVICE_UUID)
        } catch (error: Exception) {
            if (error is IOException) throw error
            throw IOException("Could not create the direct Bluetooth socket.", error)
        }
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        val timeout = watchdog.schedule(
            { try { socket.close() } catch (_: Exception) { } },
            BLUETOOTH_CONNECT_TIMEOUT_MS.toLong(),
            TimeUnit.MILLISECONDS
        )
        try {
            socket.connect()
        } catch (error: Exception) {
            try { socket.close() } catch (_: Exception) { }
            if (error is IOException) throw error
            throw IOException("Could not connect directly over Bluetooth.", error)
        } finally {
            timeout.cancel(true)
            watchdog.shutdownNow()
        }
        return establishBluetooth(socket, contact, initiator = true)
    }

    private fun establishBluetooth(
        socket: BluetoothSocket,
        contact: TrustedContact,
        initiator: Boolean
    ): PeerConversationSession {
        val transport = BluetoothPeerTransportSocket(socket)
        val authenticated = try {
            DirectPeerAuthenticator(localSigningIdentity, contact).authenticate(transport)
        } catch (error: Exception) {
            try { transport.close() } catch (_: Exception) { }
            try { socket.close() } catch (_: Exception) { }
            if (error is IOException) throw error
            throw IOException("Bluetooth peer authentication failed.", error)
        }
        return establish(authenticated, contact, initiator)
    }

    @Throws(IOException::class)
    fun loadHistory(peerFingerprint: String): List<StoredChatMessage> = history.load(peerFingerprint)

    private fun establish(
        authenticated: AuthenticatedPeerConnection,
        contact: TrustedContact,
        initiator: Boolean
    ): PeerConversationSession {
        try {
            val noise = NoisePeerSession.create(localNoiseIdentity, contact, initiator)
            val channel = authenticated.establishEncryptedChannel(noise, initiator)
            val session = PeerConversationSession(contact.fingerprint, channel, history)
            sessionRef.getAndSet(session)?.close()
            return session
        } catch (error: Exception) {
            authenticated.close()
            if (error is IOException) throw error
            throw IOException("Could not establish the encrypted conversation.", error)
        }
    }

    @Synchronized
    override fun close() {
        sessionRef.getAndSet(null)?.close()
        listenerRef.getAndSet(null)?.close()
    }

    companion object {
        private const val BLUETOOTH_ACCEPT_TIMEOUT_MS = 60_000
        private const val BLUETOOTH_CONNECT_TIMEOUT_MS = 20_000
        val BLUETOOTH_SERVICE_UUID: java.util.UUID = java.util.UUID.fromString("5b8f1000-7a2d-4d6c-a9a8-544f4d434841")
    }
}
