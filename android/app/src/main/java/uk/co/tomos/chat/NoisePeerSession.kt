package uk.co.tomos.chat

/**
 * JNI boundary for the Rust Snow/Noise core.
 *
 * No socket or application data is exposed here. Callers must exchange the
 * bounded handshake messages over an already-authenticated direct transport,
 * then call finishHandshake before encrypt/decrypt can be used.
 */
internal object NoiseNative {
    init {
        System.loadLibrary("tomchat_noise")
    }

    external fun nativeCreateSession(
        initiator: Boolean,
        localPrivateKey: ByteArray,
        expectedPeerStaticPublicKey: ByteArray
    ): Long

    external fun nativeWriteHandshake(handle: Long, payload: ByteArray): ByteArray
    external fun nativeReadHandshake(handle: Long, message: ByteArray): ByteArray
    external fun nativeFinishHandshake(handle: Long)
    external fun nativeEncrypt(handle: Long, plaintext: ByteArray): ByteArray
    external fun nativeDecrypt(handle: Long, ciphertext: ByteArray): ByteArray
    external fun nativeCloseSession(handle: Long)
}

/** Narrow seam so the Kotlin lifecycle/guard logic can be tested on the JVM. */
internal interface NoiseSessionBackend {
    fun writeHandshake(handle: Long, payload: ByteArray): ByteArray
    fun readHandshake(handle: Long, message: ByteArray): ByteArray
    fun finishHandshake(handle: Long)
    fun encrypt(handle: Long, plaintext: ByteArray): ByteArray
    fun decrypt(handle: Long, ciphertext: ByteArray): ByteArray
    fun closeSession(handle: Long)
}

private object JniNoiseSessionBackend : NoiseSessionBackend {
    override fun writeHandshake(handle: Long, payload: ByteArray) =
        NoiseNative.nativeWriteHandshake(handle, payload)

    override fun readHandshake(handle: Long, message: ByteArray) =
        NoiseNative.nativeReadHandshake(handle, message)

    override fun finishHandshake(handle: Long) = NoiseNative.nativeFinishHandshake(handle)
    override fun encrypt(handle: Long, plaintext: ByteArray) =
        NoiseNative.nativeEncrypt(handle, plaintext)

    override fun decrypt(handle: Long, ciphertext: ByteArray) =
        NoiseNative.nativeDecrypt(handle, ciphertext)

    override fun closeSession(handle: Long) = NoiseNative.nativeCloseSession(handle)
}

/**
 * Owns one Rust-side Noise session. This wrapper deliberately provides no
 * transport integration; the app cannot accidentally send plaintext through
 * the legacy identity-proof socket.
 */
class NoisePeerSession internal constructor(
    private var nativeHandle: Long,
    private val backend: NoiseSessionBackend
) : AutoCloseable {
    @Synchronized
    fun writeHandshake(payload: ByteArray = byteArrayOf()): ByteArray =
        backend.writeHandshake(requireHandle(), payload)

    @Synchronized
    fun readHandshake(message: ByteArray): ByteArray =
        backend.readHandshake(requireHandle(), message)

    @Synchronized
    fun finishHandshake() {
        backend.finishHandshake(requireHandle())
        ready = true
    }

    @Volatile
    private var ready = false

    @Synchronized
    fun encrypt(plaintext: ByteArray): ByteArray {
        check(ready) { "Noise handshake is not complete." }
        return backend.encrypt(requireHandle(), plaintext)
    }

    @Synchronized
    fun decrypt(ciphertext: ByteArray): ByteArray {
        check(ready) { "Noise handshake is not complete." }
        return backend.decrypt(requireHandle(), ciphertext)
    }

    @Synchronized
    private fun requireHandle(): Long =
        nativeHandle.takeIf { it != 0L } ?: error("Noise session is closed.")

    @Synchronized
    override fun close() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeHandle = 0L
            ready = false
            backend.closeSession(handle)
        }
    }

    companion object {
        fun create(
            localIdentity: NoiseIdentity,
            trustedPeer: TrustedContact,
            initiator: Boolean
        ): NoisePeerSession {
            val peerKey = trustedPeer.noiseStaticPublicKey
                ?: throw IllegalArgumentException("Trusted contact has no signed Noise key binding.")
            require(peerKey.size == 32) { "Trusted peer Noise key must be 32 bytes." }
            require(localIdentity.privateKey.size == 32 && localIdentity.publicKey.size == 32) {
                "Local Noise identity is invalid."
            }
            val privateCopy = localIdentity.privateKey.copyOf()
            return try {
                val handle = NoiseNative.nativeCreateSession(initiator, privateCopy, peerKey.copyOf())
                check(handle != 0L) { "Rust Noise core did not create a session." }
                NoisePeerSession(handle, JniNoiseSessionBackend)
            } finally {
                privateCopy.fill(0)
            }
        }
    }
}
