package uk.co.tomos.chat

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.security.KeyPair
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mutual proof-of-key-possession for a direct TCP peer.
 *
 * This authenticates the peer but deliberately does not expose application
 * streams. A reviewed E2EE session protocol must be established before data
 * can be exchanged. Do not send messages over this socket yet.
 */
class DirectPeerAuthenticator(
    private val localIdentity: KeyPair,
    private val expectedPeer: TrustedContact,
    private val random: SecureRandom = SecureRandom()
) {
    fun authenticate(socket: Socket): AuthenticatedPeerConnection =
        authenticate(TcpPeerTransportSocket(socket))

    fun authenticate(socket: PeerTransportSocket): AuthenticatedPeerConnection {
        socket.readTimeoutMs = HANDSHAKE_TIMEOUT_MS
        socket.tcpNoDelay = true
        val input = DataInputStream(socket.inputStream)
        val output = DataOutputStream(socket.outputStream)

        val localNonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        output.writeInt(PROTOCOL_MAGIC)
        output.writeByte(PROTOCOL_VERSION)
        output.write(localNonce)
        output.flush()

        val magic = input.readInt()
        val version = input.readUnsignedByte()
        if (magic != PROTOCOL_MAGIC || version != PROTOCOL_VERSION) {
            throw IOException("Unsupported TomChat peer handshake.")
        }
        val remoteNonce = ByteArray(NONCE_SIZE).also(input::readFully)

        val localFingerprint = ContactCardCodec.fingerprint(localIdentity.public.encoded)
        val localSignature = sign(localIdentity, transcript(localNonce, remoteNonce, localFingerprint))
        output.writeUTF(localFingerprint)
        output.writeInt(localSignature.size)
        output.write(localSignature)
        output.flush()

        val remoteFingerprint = input.readUTF()
        if (remoteFingerprint != expectedPeer.fingerprint) {
            throw IOException("Peer identity does not match the trusted contact.")
        }
        val signatureLength = input.readInt()
        if (signatureLength !in 1..MAX_SIGNATURE_SIZE) throw IOException("Invalid peer proof size.")
        val remoteSignature = ByteArray(signatureLength).also(input::readFully)
        val peerProofValid = verify(
            expectedPeer.encodedPublicKey,
            remoteSignature,
            transcript(remoteNonce, localNonce, remoteFingerprint)
        )
        // Both sides acknowledge the result so a peer cannot mistake one-sided
        // authentication for a mutually authenticated connection.
        output.writeByte(if (peerProofValid) AUTH_OK else AUTH_FAILED)
        output.flush()
        val remoteStatus = input.readUnsignedByte()
        if (!peerProofValid || remoteStatus != AUTH_OK) {
            throw IOException("Mutual peer identity verification failed.")
        }

        // This socket remains private to this object; no plaintext data API is exposed.
        return AuthenticatedPeerConnection(socket, expectedPeer.fingerprint)
    }

    private fun sign(identity: KeyPair, message: ByteArray): ByteArray {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(identity.private, random)
        signer.update(message)
        return signer.sign()
    }

    private fun transcript(first: ByteArray, second: ByteArray, fingerprint: String): ByteArray =
        DOMAIN + first + second + fingerprint.toByteArray(Charsets.US_ASCII)

    private fun verify(publicKeyBytes: ByteArray, signatureBytes: ByteArray, message: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyBytes))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(message)
        verifier.verify(signatureBytes)
    } catch (_: Exception) { false }

    companion object {
        private const val PROTOCOL_MAGIC = 0x544F4D43
        private const val PROTOCOL_VERSION = 1
        private const val NONCE_SIZE = 32
        private const val MAX_SIGNATURE_SIZE = 144
        private const val AUTH_OK = 1
        private const val AUTH_FAILED = 0
        private const val HANDSHAKE_TIMEOUT_MS = 8_000
        private val DOMAIN = "TOMCHAT-DIRECT-PEER-PROOF-V1".toByteArray(Charsets.US_ASCII)
    }
}

class AuthenticatedPeerConnection internal constructor(
    private val socket: PeerTransportSocket,
    val peerFingerprint: String
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    val isConnected: Boolean get() = !closed.get() && socket.isConnected && !socket.isClosed

    /** Complete Noise XX on this authenticated socket before application data is allowed. */
    @Synchronized
    @Throws(IOException::class)
    fun establishEncryptedChannel(session: NoisePeerSession, initiator: Boolean, handshakeTimeoutMs: Int = 8_000): EncryptedPeerChannel {
        check(!closed.get() && isConnected) { "Peer connection is closed." }
        require(handshakeTimeoutMs in 1..30_000)
        val input = DataInputStream(socket.inputStream)
        val output = DataOutputStream(socket.outputStream)
        try {
            socket.readTimeoutMs = handshakeTimeoutMs
            if (initiator) {
                writeBounded(output, session.writeHandshake(), MAX_NOISE_HANDSHAKE_BYTES)
                session.readHandshake(readBounded(input, MAX_NOISE_HANDSHAKE_BYTES))
                writeBounded(output, session.writeHandshake(), MAX_NOISE_HANDSHAKE_BYTES)
            } else {
                session.readHandshake(readBounded(input, MAX_NOISE_HANDSHAKE_BYTES))
                writeBounded(output, session.writeHandshake(), MAX_NOISE_HANDSHAKE_BYTES)
                session.readHandshake(readBounded(input, MAX_NOISE_HANDSHAKE_BYTES))
            }
            session.finishHandshake()
            socket.readTimeoutMs = 0
            return EncryptedPeerChannel(socket, input, output, session)
        } catch (error: Exception) {
            try { session.close() } catch (_: Exception) { }
            close()
            if (error is IOException) throw error
            throw IOException("End-to-end encrypted peer handshake failed.", error)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) socket.close()
    }

    companion object {
        private const val MAX_NOISE_HANDSHAKE_BYTES = 1024
        internal fun writeBounded(output: DataOutputStream, bytes: ByteArray, max: Int) {
            require(bytes.isNotEmpty() && bytes.size <= max) { "Invalid peer frame size." }
            output.writeInt(bytes.size); output.write(bytes); output.flush()
        }
        internal fun readBounded(input: DataInputStream, max: Int): ByteArray {
            val size = input.readInt()
            if (size !in 1..max) throw IOException("Peer frame length is invalid.")
            return ByteArray(size).also(input::readFully)
        }
    }
}
