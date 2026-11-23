package uk.co.tomos.chat

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import to.holepunch.bare.kit.Ipc
import to.holepunch.bare.kit.Worklet
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android host for TomChat's shared Pear/Bare P2P worker.
 *
 * This class deliberately exposes only versioned worker messages. Identity
 * provisioning is a separate security boundary and must happen only after the
 * signed Pear transport identity migration is implemented.
 */
class PearP2PBackend(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : Closeable {
    private val appContext = context.applicationContext
    private val started = AtomicBoolean(false)
    private var worklet: Worklet? = null
    private var ipc: Ipc? = null
    private val mutableEvents = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    private var readerJob: Job? = null
    val events: SharedFlow<JSONObject> = mutableEvents

    suspend fun start() = withContext(Dispatchers.IO) {
        check(started.compareAndSet(false, true)) { "Pear backend has already been started." }
        try {
            val runtime = Worklet(appContext, Worklet.Options(memoryLimit = DEFAULT_MEMORY_LIMIT))
            val channel = Ipc(runtime)
            worklet = runtime
            ipc = channel
            runtime.start(WORKER_BUNDLE)

            readerJob = scope.launch {
                channel.data.collect { bytes ->
                    val event = try {
                        JSONObject(bytes.decodeToString())
                    } catch (error: Exception) {
                        JSONObject()
                            .put("version", PROTOCOL_VERSION)
                            .put("type", "backend:protocol-error")
                            .put("message", error.message ?: "Invalid worker event")
                    }
                    mutableEvents.emit(event)
                }
            }
        } catch (error: Exception) {
            started.set(false)
            runCatching { ipc?.close() }
            runCatching { worklet?.close() }
            ipc = null
            worklet = null
            throw IllegalStateException("Could not start the Pear/Bare P2P worker.", error)
        }
    }

    suspend fun send(type: String, payload: JSONObject = JSONObject()) = withContext(Dispatchers.IO) {
        check(started.get()) { "Pear backend is not running." }
        require(type.isNotBlank() && type.length <= 80) { "Invalid worker command." }
        val message = JSONObject(payload.toString())
            .put("version", PROTOCOL_VERSION)
            .put("type", type)
        val encoded = message.toString().encodeToByteArray()
        require(encoded.size <= MAX_FRAME_BYTES) { "Worker command exceeds the IPC frame limit." }
        ipc?.write(encoded) ?: error("Pear worker IPC is unavailable.")
    }

    override fun close() {
        if (!started.compareAndSet(true, false)) return
        readerJob?.cancel()
        readerJob = null
        runCatching { ipc?.close() }
        runCatching { worklet?.close() }
        ipc = null
        worklet = null
    }

    companion object {
        private const val WORKER_BUNDLE = "pear-worker.bundle.mjs"
        private const val PROTOCOL_VERSION = 1
        private const val MAX_FRAME_BYTES = 64 * 1024
        private const val DEFAULT_MEMORY_LIMIT = 64L * 1024 * 1024
    }
}
