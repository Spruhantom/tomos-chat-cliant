package uk.co.tomos.chat

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application channel available only after direct identity authentication and
 * a successful pinned-key Noise handshake. The socket carries ciphertext only
 * after establishment; frame contents are decoded only after Noise decrypts.
 */
class EncryptedPeerChannel internal constructor(
    private val socket: PeerTransportSocket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val noise: NoisePeerSession,
    /** Identity fingerprint authenticated before the Noise session was established. */
    val authenticatedPeerFingerprint: String
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    private val readLock = Any()

    val isOpen: Boolean get() = !closed.get() && !socket.isClosed && socket.isConnected

    @Throws(IOException::class)
    fun send(frame: EncryptedAppFrame) {
        check(isOpen) { "Encrypted peer channel is closed." }
        synchronized(writeLock) {
            check(isOpen) { "Encrypted peer channel is closed." }
            try {
                // Noise transport counters are ordered. Keep encryption and the
                // corresponding socket write in one critical section so two
                // concurrent callers cannot put ciphertext on the wire out of order.
                val plaintext = AppFrameCodec.encode(frame)
                val ciphertext = noise.encrypt(plaintext)
                if (ciphertext.isEmpty() || ciphertext.size > MAX_CIPHERTEXT_BYTES) throw IOException("Encrypted frame exceeds limit.")
                output.writeInt(ciphertext.size)
                output.write(ciphertext)
                output.flush()
            } catch (error: Exception) {
                close()
                if (error is IOException) throw error
                throw IOException("Encrypted peer write failed.", error)
            }
        }
    }

    @Throws(IOException::class)
    fun receive(): EncryptedAppFrame = synchronized(readLock) {
        check(isOpen) { "Encrypted peer channel is closed." }
        try {
            val length = input.readInt()
            if (length !in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES) throw IOException("Encrypted frame length is invalid.")
            val ciphertext = ByteArray(length).also(input::readFully)
            val plaintext = noise.decrypt(ciphertext)
            AppFrameCodec.decode(plaintext)
        } catch (error: Exception) {
            close()
            if (error is IOException) throw error
            throw IOException("Encrypted peer frame was rejected.", error)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { noise.close() } finally { try { socket.close() } catch (_: Exception) { } }
        }
    }

    private companion object {
        const val MIN_CIPHERTEXT_BYTES = 16
        const val MAX_CIPHERTEXT_BYTES = AppFrameCodec.MAX_FRAME_BYTES + 16
    }
}