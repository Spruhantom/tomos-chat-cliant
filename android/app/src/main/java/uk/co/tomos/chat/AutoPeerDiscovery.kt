package uk.co.tomos.chat

import android.content.Context
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

/**
 * Automatic direct peer discovery. Wi-Fi NSD is tried first, then nearby
 * Bluetooth discovery is used as a fallback. Advertised fingerprint hints are
 * never authentication credentials. The existing pinned identity proof
 * and Noise XX handshake remain mandatory before a session is returned.
 *
 * No relay, directory, rendezvous server, or cloud endpoint is contacted.
 */
class AutoPeerDiscovery(
    context: Context,
    private val coordinator: PeerMessagingCoordinator,
    private val localFingerprint: String,
    private val contact: TrustedContact,
    private val enableBluetoothFallback: Boolean = true,
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
    private val bluetoothStarted = AtomicBoolean(false)
    private val resolvingServices = ConcurrentHashMap.newKeySet<String>()
    private var timeout: ScheduledFuture<*>? = null
    private var bluetoothDiscovery: BluetoothPeerDiscovery? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val serviceType = "_tomchat._tcp."
    private val localServiceName = "TC-" + localFingerprint.filter(Char::isLetterOrDigit).take(12)

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
                serviceName = localServiceName
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
                    if (closed.get() || found.serviceName == localServiceName || found.serviceType.trimEnd('.') != serviceType.trimEnd('.')) return
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
                15,
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
                        if (!closed.get() && !completed.get() && !bluetoothStarted.get()) fail(error)
                    }
                }
            }
        } catch (error: Exception) {
            fail(error)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(found: NsdServiceInfo) {
        val serviceKey = found.serviceName + "|" + found.serviceType
        if (closed.get() || completed.get() || !resolvingServices.add(serviceKey)) return
        nsd.resolveService(found, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                resolvingServices.remove(serviceKey)
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                resolvingServices.remove(serviceKey)
                if (closed.get() || completed.get()) return
                val advertisedProtocol = info.attributes["protocol"]?.toString(Charsets.US_ASCII)
                if (advertisedProtocol != "1") return
                val advertisedFingerprint = info.attributes["fp"]?.toString(Charsets.US_ASCII)
                if (advertisedFingerprint == localFingerprint) return
                if (advertisedFingerprint != contact.fingerprint) return
                if (!PeerConnectionRole.shouldInitiate(localFingerprint, contact.fingerprint)) return
                val port = info.port
                if (port !in 1..65535) return
                val resolvedHosts = if (Build.VERSION.SDK_INT >= 34) {
                    info.hostAddresses.map { it.hostAddress }
                } else {
                    emptyList()
                }
                // Keep the legacy single-host result as a compatibility fallback
                // for devices whose NSD implementation returns no address list.
                val hosts = (resolvedHosts + listOfNotNull(info.host?.hostAddress)).distinct()
                if (hosts.isEmpty()) return
                executor.execute {
                    var lastError: Exception? = null
                    for (host in hosts) {
                        if (closed.get() || completed.get()) return@execute
                        try {
                            val session = coordinator.connect(host, port, contact)
                            publishConnected(session)
                            return@execute
                        } catch (error: Exception) {
                            lastError = error
                        }
                    }
                    if (!closed.get() && !completed.get()) {
                        fail(lastError ?: IOException("No usable local address was found for this trusted peer."))
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
        onStatus(if (bluetoothStarted.get()) "Encrypted direct connection established over Bluetooth" else "Encrypted direct connection established over Wi-Fi")
        onConnected(session)
        closeDiscoveryOnly()
        bluetoothDiscovery?.close()
    }

    private fun fail(error: Throwable) {
        if (closed.get() || completed.get()) return
        if (!enableBluetoothFallback) {
            failFinal(IOException("Wi-Fi discovery failed. Bluetooth Nearby devices permission was not granted."))
            return
        }
        if (bluetoothStarted.compareAndSet(false, true)) {
            closeDiscoveryOnly()
            onStatus("Wi-Fi route unavailable; trying direct Bluetooth…")
            try {
                val bluetooth = BluetoothPeerDiscovery(
                    context = appContext,
                    localFingerprint = localFingerprint,
                    trustedFingerprint = contact.fingerprint,
                    onPeerFound = { device ->
                        if (PeerConnectionRole.shouldInitiate(localFingerprint, contact.fingerprint)) {
                            executor.execute {
                                try {
                                    publishConnected(coordinator.connectBluetooth(device, contact))
                                } catch (bluetoothError: Exception) {
                                    failFinal(bluetoothError)
                                }
                            }
                        }
                    },
                    onStatus = onStatus,
                    onError = { failFinal(it) }
                )
                bluetoothDiscovery = bluetooth
                bluetooth.start()
                if (completed.get()) return
                if (!PeerConnectionRole.shouldInitiate(localFingerprint, contact.fingerprint)) {
                    val server = bluetooth.acceptingSocket
                        ?: throw IOException("Bluetooth listener could not be started.")
                    executor.execute {
                        try {
                            publishConnected(coordinator.acceptBluetooth(server, contact))
                        } catch (bluetoothError: Exception) {
                            failFinal(bluetoothError)
                        }
                    }
                }
                timeout = executor.schedule(
                    { failFinal(IOException("No direct Wi-Fi or Bluetooth route to this trusted device was found.")) },
                    60,
                    TimeUnit.SECONDS
                )
            } catch (bluetoothError: Exception) {
                failFinal(IOException("Wi-Fi discovery failed and Bluetooth fallback could not start.", bluetoothError))
            }
        } else {
            failFinal(error)
        }
    }

    private fun failFinal(error: Throwable) {
        if (!closed.get() && completed.compareAndSet(false, true)) {
            onError(error)
            closeDiscoveryOnly()
            bluetoothDiscovery?.close()
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
            bluetoothDiscovery?.close()
            executor.shutdownNow()
        }
    }
}
