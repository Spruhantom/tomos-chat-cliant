package uk.co.tomos.chat

import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal full-duplex transport contract shared by TCP and Bluetooth RFCOMM.
 * Security remains above this interface: every transport must pass the same
 * pinned identity proof and Noise XX handshake before application frames flow.
 */
interface PeerTransportSocket : AutoCloseable {
    val inputStream: InputStream
    val outputStream: OutputStream
    val isConnected: Boolean
    val isClosed: Boolean
    var readTimeoutMs: Int
    var tcpNoDelay: Boolean
}

class TcpPeerTransportSocket(private val socket: Socket) : PeerTransportSocket {
    override val inputStream: InputStream get() = socket.getInputStream()
    override val outputStream: OutputStream get() = socket.getOutputStream()
    override val isConnected: Boolean get() = socket.isConnected
    override val isClosed: Boolean get() = socket.isClosed
    override var readTimeoutMs: Int
        get() = socket.soTimeout
        set(value) { socket.soTimeout = value }
    override var tcpNoDelay: Boolean
        get() = socket.tcpNoDelay
        set(value) { socket.tcpNoDelay = value }
    override fun close() = socket.close()
}

/**
 * RFCOMM adapter. BluetoothSocket has no SO_TIMEOUT equivalent, so a bounded
 * handshake timeout closes the socket via a watchdog. Setting zero disables it.
 */
class BluetoothPeerTransportSocket(private val socket: BluetoothSocket) : PeerTransportSocket {
    private val closed = AtomicBoolean(false)
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var timeoutTask: ScheduledFuture<*>? = null

    override val inputStream: InputStream get() = socket.inputStream
    override val outputStream: OutputStream get() = socket.outputStream
    override val isConnected: Boolean get() = !closed.get() && socket.isConnected
    override val isClosed: Boolean get() = closed.get() || !socket.isConnected

    override var readTimeoutMs: Int
        get() = 0
        set(value) {
            require(value >= 0)
            timeoutTask?.cancel(true)
            timeoutTask = null
            if (value > 0 && !closed.get()) {
                timeoutTask = watchdog.schedule({
                    try { close() } catch (_: Exception) { }
                }, value.toLong(), TimeUnit.MILLISECONDS)
            }
        }

    override var tcpNoDelay: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") value) = Unit

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            timeoutTask?.cancel(true)
            watchdog.shutdownNow()
            try { socket.close() } catch (error: IOException) { throw error }
        }
    }
}
