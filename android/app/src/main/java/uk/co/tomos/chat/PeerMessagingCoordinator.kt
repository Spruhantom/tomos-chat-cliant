package uk.co.tomos.chat

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.security.KeyPair

/**
 * Owns the user-invoked direct TCP listener/connector and turns an authenticated
 * peer socket into a Noise-protected conversation. It has no server, relay,
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
        require(port in 1..65535)
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
}
