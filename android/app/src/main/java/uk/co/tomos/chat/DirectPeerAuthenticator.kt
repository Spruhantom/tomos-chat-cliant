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
    fun authenticate(socket: Socket): AuthenticatedPeerConnection {
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        socket.tcpNoDelay = true
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())

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
        if (!verify(expectedPeer.encodedPublicKey, remoteSignature, transcript(remoteNonce, localNonce, remoteFingerprint))) {
            throw IOException("Peer failed identity proof verification.")
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
        private const val HANDSHAKE_TIMEOUT_MS = 8_000
        private val DOMAIN = "TOMCHAT-DIRECT-PEER-PROOF-V1".toByteArray(Charsets.US_ASCII)
    }
}

class AuthenticatedPeerConnection internal constructor(
    private val socket: Socket,
    val peerFingerprint: String
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    val isConnected: Boolean get() = !closed.get() && socket.isConnected && !socket.isClosed

    /** Close the authenticated transport. No application data methods exist until E2EE is integrated. */
    override fun close() {
        if (closed.compareAndSet(false, true)) socket.close()
    }
}
