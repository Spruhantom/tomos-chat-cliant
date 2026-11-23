package uk.co.tomos.chat

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
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
    private val mutableEvents = MutableSharedFlow<JSONObject>(replay = 1, extraBufferCapacity = 64)
    private val frameDecoder = PearIpcFrameDecoder()
    private var readerJob: Job? = null
    private var identityConfigured = false
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
                    decodeFrames(bytes)
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

    private suspend fun decodeFrames(chunk: ByteArray) {
        val bodies = try {
            frameDecoder.push(chunk)
        } catch (error: Exception) {
            mutableEvents.emit(JSONObject()
                .put("version", PROTOCOL_VERSION)
                .put("type", "backend:protocol-error")
                .put("message", error.message ?: "Invalid worker frame"))
            return
        }
        for (body in bodies) {
            val event = try {
                JSONObject(body.decodeToString()).also {
                    require(it.optInt("version") == PROTOCOL_VERSION)
                    require(it.optString("type").isNotBlank())
                }
            } catch (error: Exception) {
                JSONObject()
                    .put("version", PROTOCOL_VERSION)
                    .put("type", "backend:protocol-error")
                    .put("message", error.message ?: "Invalid worker event")
            }
            mutableEvents.emit(event)
        }
    }

    /**
     * Configure the worker with the device's Keystore-backed transport identity
     * and only contacts whose signed cards contain a Pear transport key.
     */
    suspend fun configureIdentity(identity: PearTransportIdentity, contacts: List<TrustedContact>) {
        check(started.get()) { "Start the Pear worker before configuring its identity." }
        val trustedPeers = JSONArray()
        val topics = linkedSetOf<String>()
        contacts.forEach { contact ->
            val remoteKey = contact.pearTransportPublicKey ?: return@forEach
            val material = PearKeyDerivation.derive(identity.privateKey, identity.publicKey, remoteKey)
            trustedPeers.put(JSONObject()
                .put("transportKey", remoteKey.toHex())
                .put("conversationKey", material.conversationKey.toHex()))
            topics += material.discoveryTopic.toHex()
        }
        send("identity:configure", JSONObject()
            .put("publicKey", identity.publicKey.toHex())
            .put("secretKey", identity.privateKey.toHex())
            .put("trustedPeers", trustedPeers))
        val acknowledgement = withTimeout(10_000) {
            events.first { it.optString("type") == "identity:configured" || it.optString("type") == "backend:error" }
        }
        check(acknowledgement.optString("type") == "identity:configured") {
            acknowledgement.optString("message", "Pear identity configuration failed.")
        }
        identityConfigured = true
        topics.forEach { topic -> send("swarm:join", JSONObject().put("topic", topic)) }
    }

    suspend fun trustContact(identity: PearTransportIdentity, contact: TrustedContact) {
        check(identityConfigured) { "Configure the Pear identity before trusting contacts." }
        val remoteKey = contact.pearTransportPublicKey
            ?: throw IllegalArgumentException("Contact has no signed Pear transport identity; request a V3 contact card.")
        val material = PearKeyDerivation.derive(identity.privateKey, identity.publicKey, remoteKey)
        send("peer:trust", JSONObject()
            .put("transportKey", remoteKey.toHex())
            .put("conversationKey", material.conversationKey.toHex()))
        send("swarm:join", JSONObject().put("topic", material.discoveryTopic.toHex()))
    }

    suspend fun untrustContact(transportPublicKey: ByteArray) {
        require(transportPublicKey.size == 32)
        send("peer:untrust", JSONObject().put("transportKey", transportPublicKey.toHex()))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    suspend fun send(type: String, payload: JSONObject = JSONObject()) = withContext(Dispatchers.IO) {
        check(started.get()) { "Pear backend is not running." }
        require(type.isNotBlank() && type.length <= 80) { "Invalid worker command." }
        val message = JSONObject(payload.toString())
            .put("version", PROTOCOL_VERSION)
            .put("type", type)
        val encoded = message.toString().encodeToByteArray()
        require(encoded.size <= MAX_FRAME_BYTES) { "Worker command exceeds the IPC frame limit." }
        ipc?.write(PearIpcFrameCodec.encode(encoded)) ?: error("Pear worker IPC is unavailable.")
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
        private const val MAX_FRAME_BYTES = PearIpcFrameCodec.MAX_FRAME_BYTES
        private const val DEFAULT_MEMORY_LIMIT = 64L * 1024 * 1024
    }
}
