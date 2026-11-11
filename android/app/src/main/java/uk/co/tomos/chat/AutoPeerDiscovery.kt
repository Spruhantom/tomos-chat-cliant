package uk.co.tomos.chat

import android.content.Context
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LAN-only automatic peer discovery. NSD is used solely to find a trusted
 * contact on the same local network; the advertised fingerprint is a lookup
 * hint, never an authentication credential. The existing pinned identity proof
 * and Noise XX handshake remain mandatory before a session is returned.
 *
 * No relay, directory, rendezvous server, or cloud endpoint is contacted.
 */
class AutoPeerDiscovery(
    context: Context,
    private val coordinator: PeerMessagingCoordinator,
    private val localFingerprint: String,
    private val contact: TrustedContact,
    private val onStatus: (String) -> Unit,
    private val onConnected: (PeerConversationSession) -> Unit,
    private val onError: (Throwable) -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    private val closed = AtomicBoolean(false)
    private val completed = AtomicBoolean(false)
    private val resolving = AtomicBoolean(false)
    private var timeout: ScheduledFuture<*>? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val serviceType = "_tomchat._tcp."

    fun start() {
        check(!closed.get()) { "Discovery has been closed." }
        if (localFingerprint == contact.fingerprint) {
            fail(IOException("A device cannot connect to itself."))
            return
        }
        try {
            // Android filters multicast packets for background Wi-Fi clients on
            // many devices. Hold this only for the bounded discovery window.
            multicastLock = wifiManager.createMulticastLock("TomChatPeerDiscovery").apply {
                setReferenceCounted(false)
                acquire()
            }
            val port = coordinator.startListening("0.0.0.0", 0)
            val service = NsdServiceInfo().apply {
                serviceName = "TomChat"
                this.serviceType = this@AutoPeerDiscovery.serviceType
                this.port = port
                setAttribute("fp", localFingerprint)
                setAttribute("protocol", "1")
            }
            val registerListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    if (!closed.get()) onStatus("Visible to trusted devices on this Wi-Fi network")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    fail(IOException("Could not advertise TomChat on the local network (NSD $errorCode)."))
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    if (!closed.get()) onStatus("Local discovery advertisement stopped")
                }
            }
            registration = registerListener
            nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, registerListener)

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {
                    if (!closed.get()) onStatus("Finding trusted device on this Wi-Fi network…")
                }
                override fun onServiceFound(found: NsdServiceInfo) {
                    if (closed.get() || found.serviceType.trimEnd('.') != serviceType.trimEnd('.')) return
                    resolve(found)
                }
                override fun onServiceLost(lost: NsdServiceInfo) = Unit
                override fun onDiscoveryStopped(type: String) = Unit
                override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                    fail(IOException("Local device discovery failed (NSD $errorCode)."))
                }
                override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
                    if (!closed.get()) onStatus("Local discovery could not stop cleanly")
                }
            }
            discovery = listener
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            timeout = executor.schedule(
                { fail(IOException("No trusted device was found on this Wi-Fi network.")) },
                45,
                TimeUnit.SECONDS
            )

            // The lexicographically smaller identity initiates. This prevents
            // both devices opening competing simultaneous Noise sessions.
            if (!PeerConnectionRole.shouldInitiate(localFingerprint, contact.fingerprint)) {
                executor.execute {
                    try {
                        val session = coordinator.accept(contact)
                        publishConnected(session)
                    } catch (error: Exception) {
                        if (!closed.get() && !completed.get()) fail(error)
                    }
                }
            }
        } catch (error: Exception) {
            fail(error)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(found: NsdServiceInfo) {
        if (closed.get() || completed.get() || !resolving.compareAndSet(false, true)) return
        nsd.resolveService(found, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                resolving.set(false)
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                resolving.set(false)
                if (closed.get() || completed.get()) return
                val advertisedFingerprint = info.attributes["fp"]
                    ?.toString(Charsets.US_ASCII)
                if (advertisedFingerprint == localFingerprint) return
                if (advertisedFingerprint != contact.fingerprint) return
                if (!PeerConnectionRole.shouldInitiate(localFingerprint, contact.fingerprint)) return
                val host = info.host?.hostAddress ?: return
                val port = info.port
                if (port !in 1..65535) return
                executor.execute {
                    try {
                        val session = coordinator.connect(host, port, contact)
                        publishConnected(session)
                    } catch (error: Exception) {
                        if (!closed.get() && !completed.get()) fail(error)
                    }
                }
            }
        })
    }

    private fun publishConnected(session: PeerConversationSession) {
        if (closed.get() || !completed.compareAndSet(false, true)) {
            session.close()
            return
        }
        onStatus("Encrypted direct connection established over Wi-Fi")
        onConnected(session)
        closeDiscoveryOnly()
    }

    private fun fail(error: Throwable) {
        if (!closed.get() && completed.compareAndSet(false, true)) {
            onError(error)
            closeDiscoveryOnly()
        }
    }

    private fun closeDiscoveryOnly() {
        try { discovery?.let { nsd.stopServiceDiscovery(it) } } catch (_: Exception) { }
        try { registration?.let { nsd.unregisterService(it) } } catch (_: Exception) { }
        timeout?.cancel(true)
        coordinator.stopListening()
        try { multicastLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        multicastLock = null
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            closeDiscoveryOnly()
            executor.shutdownNow()
        }
    }
}
