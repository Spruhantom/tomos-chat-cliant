package uk.co.tomos.chat

import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Typed call-control messages carried only inside an authenticated Noise frame. */
enum class PeerCallSignalKind(val wireId: Int) {
    OFFER(1), ANSWER(2), ICE_CANDIDATE(3), ACCEPT(4), REJECT(5), HANGUP(6);

    companion object {
        fun fromWire(value: Int): PeerCallSignalKind =
            entries.firstOrNull { it.wireId == value }
                ?: throw IllegalArgumentException("Unknown call signal type.")
    }
}

data class PeerIceCandidate(
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val candidate: String
) {
    init {
        require(sdpMid == null || (sdpMid.length <= MAX_MID_CHARS && '\u0000' !in sdpMid))
        require(sdpMLineIndex in 0..MAX_MEDIA_LINE_INDEX)
        require(candidate.isNotBlank() && candidate.toByteArray(Charsets.UTF_8).size <= MAX_CANDIDATE_BYTES)
        require('\u0000' !in candidate)
    }

    companion object {
        const val MAX_MID_CHARS = 64
        const val MAX_MEDIA_LINE_INDEX = 1024
        const val MAX_CANDIDATE_BYTES = 2048
    }
}

data class PeerCallSignal(
    val callId: UUID,
    val sequence: Long,
    val senderFingerprint: String,
    val kind: PeerCallSignalKind,
    val sdp: String? = null,
    val dtlsFingerprint: String? = null,
    val candidate: PeerIceCandidate? = null
) {
    init {
        require(sequence > 0)
        require(ContactIdentityFingerprint.isValid(senderFingerprint))
        when (kind) {
            PeerCallSignalKind.OFFER, PeerCallSignalKind.ANSWER -> {
                require(sdp != null && sdp.toByteArray(Charsets.UTF_8).size in 1..PeerCallSignalCodec.MAX_SDP_BYTES)
                require(dtlsFingerprint != null)
                require(SdpDtlsFingerprint.extract(sdp) == dtlsFingerprint)
                require(candidate == null)
            }
            PeerCallSignalKind.ICE_CANDIDATE -> {
                require(candidate != null && sdp == null && dtlsFingerprint == null)
            }
            PeerCallSignalKind.ACCEPT, PeerCallSignalKind.REJECT, PeerCallSignalKind.HANGUP -> {
                require(sdp == null && dtlsFingerprint == null && candidate == null)
            }
        }
    }
}

/** Contact fingerprint format emitted by ContactCardCodec (SHA-256, grouped in fours). */
object ContactIdentityFingerprint {
    private val pattern = Regex("^[0-9A-F]{4}(?: [0-9A-F]{4}){15}$")
    fun isValid(value: String): Boolean = pattern.matches(value)
}

/**
 * Strictly extracts the SHA-256 DTLS certificate fingerprint from SDP.
 * Conflicting or unsupported fingerprint attributes are rejected rather than
 * silently selecting one. WebRTC subsequently verifies the presented DTLS
 * certificate against this authenticated SDP fingerprint during handshake.
 */
object SdpDtlsFingerprint {
    private val sha256Pattern = Regex("^[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){31}$")

    fun extract(sdp: String): String {
        require(sdp.toByteArray(Charsets.UTF_8).size in 1..PeerCallSignalCodec.MAX_SDP_BYTES)
        require('\u0000' !in sdp)
        val fingerprints = sdp.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("a=fingerprint:", ignoreCase = true) }
            .map { it.substringAfter(':').trim() }
            .toList()
        require(fingerprints.isNotEmpty()) { "SDP has no DTLS fingerprint." }
        val normalized = fingerprints.map { value ->
            val parts = value.split(Regex("\\s+"), limit = 2)
            require(parts.size == 2 && parts[0].equals("sha-256", ignoreCase = true)) {
                "Only SHA-256 DTLS fingerprints are accepted."
            }
            require(sha256Pattern.matches(parts[1])) { "Malformed SHA-256 DTLS fingerprint." }
            parts[1].uppercase()
        }.distinct()
        require(normalized.size == 1) { "SDP contains conflicting DTLS fingerprints." }
        return normalized.single()
    }
}

object PeerCallSignalCodec {
    private const val VERSION = 1
    const val MAX_SDP_BYTES = 12 * 1024
    private const val MAX_IDENTITY_BYTES = 128
    private const val MAX_TOTAL_BYTES = AppFrameCodec.MAX_PAYLOAD_BYTES

    fun encode(signal: PeerCallSignal): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeByte(VERSION)
            data.writeByte(signal.kind.wireId)
            data.writeLong(signal.callId.mostSignificantBits)
            data.writeLong(signal.callId.leastSignificantBits)
            data.writeLong(signal.sequence)
            writeString(data, signal.senderFingerprint, MAX_IDENTITY_BYTES)
            when (signal.kind) {
                PeerCallSignalKind.OFFER, PeerCallSignalKind.ANSWER -> {
                    writeString(data, checkNotNull(signal.sdp), MAX_SDP_BYTES)
                    writeString(data, checkNotNull(signal.dtlsFingerprint), 95)
                }
                PeerCallSignalKind.ICE_CANDIDATE -> {
                    val candidate = checkNotNull(signal.candidate)
                    data.writeBoolean(candidate.sdpMid != null)
                    candidate.sdpMid?.let { writeString(data, it, PeerIceCandidate.MAX_MID_CHARS * 4) }
                    data.writeInt(candidate.sdpMLineIndex)
                    writeString(data, candidate.candidate, PeerIceCandidate.MAX_CANDIDATE_BYTES)
                }
                PeerCallSignalKind.ACCEPT, PeerCallSignalKind.REJECT, PeerCallSignalKind.HANGUP -> Unit
            }
        }
        return output.toByteArray().also { require(it.size <= MAX_TOTAL_BYTES) }
    }

    fun decode(encoded: ByteArray): PeerCallSignal {
        require(encoded.size in MIN_ENCODED_BYTES..MAX_TOTAL_BYTES)
        val input = DataInputStream(ByteArrayInputStream(encoded))
        require(input.readUnsignedByte() == VERSION) { "Unsupported call signal version." }
        val kind = PeerCallSignalKind.fromWire(input.readUnsignedByte())
        val callId = UUID(input.readLong(), input.readLong())
        val sequence = input.readLong()
        val sender = readString(input, MAX_IDENTITY_BYTES)
        val signal = when (kind) {
            PeerCallSignalKind.OFFER, PeerCallSignalKind.ANSWER -> {
                val sdp = readString(input, MAX_SDP_BYTES)
                val fingerprint = readString(input, 95)
                PeerCallSignal(callId, sequence, sender, kind, sdp, fingerprint)
            }
            PeerCallSignalKind.ICE_CANDIDATE -> {
                val mid = if (input.readBoolean()) readString(input, PeerIceCandidate.MAX_MID_CHARS * 4) else null
                val line = input.readInt()
                val candidate = readString(input, PeerIceCandidate.MAX_CANDIDATE_BYTES)
                PeerCallSignal(callId, sequence, sender, kind, candidate = PeerIceCandidate(mid, line, candidate))
            }
            PeerCallSignalKind.ACCEPT, PeerCallSignalKind.REJECT, PeerCallSignalKind.HANGUP ->
                PeerCallSignal(callId, sequence, sender, kind)
        }
        require(input.available() == 0) { "Trailing bytes in call signal." }
        return signal
    }

    private fun writeString(output: DataOutputStream, value: String, maxBytes: Int) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..maxBytes && '\u0000' !in value)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream, maxBytes: Int): String {
        val length = input.readInt()
        require(length in 1..maxBytes)
        val bytes = ByteArray(length).also(input::readFully)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().also { require('\u0000' !in it) }
    }

    private const val MIN_ENCODED_BYTES = 34
}

/** Narrow transport seam allows replay/identity checks to be tested without sockets. */
interface PeerCallSignalTransport {
    val authenticatedPeerFingerprint: String
    fun send(frame: EncryptedAppFrame)
}

class NoisePeerCallSignalTransport(private val channel: EncryptedPeerChannel) : PeerCallSignalTransport {
    override val authenticatedPeerFingerprint: String get() = channel.authenticatedPeerFingerprint
    override fun send(frame: EncryptedAppFrame) = channel.send(frame)
}

/** One-call signalling adapter. The owner supplies frames from its single channel receive loop. */
class PeerCallSignalling(
    private val transport: PeerCallSignalTransport,
    private val localIdentityFingerprint: String,
    val callId: UUID
) {
    private var outboundSequence = 0L
    private var inboundSequence = 0L

    init {
        require(ContactIdentityFingerprint.isValid(localIdentityFingerprint))
        require(ContactIdentityFingerprint.isValid(transport.authenticatedPeerFingerprint))
        require(localIdentityFingerprint != transport.authenticatedPeerFingerprint) {
            "A call cannot target the local identity."
        }
    }

    @Synchronized
    fun sendOffer(sdp: String) = send(PeerCallSignalKind.OFFER, sdp = sdp)

    @Synchronized
    fun sendAnswer(sdp: String) = send(PeerCallSignalKind.ANSWER, sdp = sdp)

    @Synchronized
    fun sendIceCandidate(candidate: PeerIceCandidate) = send(PeerCallSignalKind.ICE_CANDIDATE, candidate = candidate)

    @Synchronized
    fun sendAccept() = send(PeerCallSignalKind.ACCEPT)

    @Synchronized
    fun sendReject() = send(PeerCallSignalKind.REJECT)

    @Synchronized
    fun sendHangup() = send(PeerCallSignalKind.HANGUP)

    @Synchronized
    private fun send(
        kind: PeerCallSignalKind,
        sdp: String? = null,
        candidate: PeerIceCandidate? = null
    ) {
        check(outboundSequence < Long.MAX_VALUE) { "Call signalling sequence exhausted." }
        val fingerprint = sdp?.let(SdpDtlsFingerprint::extract)
        val signal = PeerCallSignal(callId, ++outboundSequence, localIdentityFingerprint, kind, sdp, fingerprint, candidate)
        val payload = PeerCallSignalCodec.encode(signal)
        transport.send(EncryptedAppFrame(EncryptedAppFrame.Type.VOICE_SIGNAL, UUID.randomUUID(), payload))
    }

    /** Rejects cross-call, spoofed-identity, duplicate and out-of-order signals. */
    @Synchronized
    fun receive(frame: EncryptedAppFrame): PeerCallSignal {
        require(frame.type == EncryptedAppFrame.Type.VOICE_SIGNAL) { "Unexpected application frame type." }
        val signal = PeerCallSignalCodec.decode(frame.payload)
        require(signal.callId == callId) { "Call signal belongs to a different call." }
        require(signal.senderFingerprint == transport.authenticatedPeerFingerprint) {
            "Call signal identity does not match the authenticated Noise peer."
        }
        require(signal.sequence > inboundSequence) { "Replayed or out-of-order call signal." }
        inboundSequence = signal.sequence
        return signal
    }

    /**
     * Apply only an identity-checked offer/answer. WebRTC verifies the remote
     * certificate against the SHA-256 fingerprint in this authenticated SDP
     * as part of its DTLS handshake; do not rewrite or replace the SDP.
     */
    fun applyRemoteDescription(
        connection: PeerConnection,
        frame: EncryptedAppFrame,
        observer: SdpObserver
    ): PeerCallSignal {
        val signal = receive(frame)
        require(signal.kind == PeerCallSignalKind.OFFER || signal.kind == PeerCallSignalKind.ANSWER)
        // The signal can only reach this method after Noise-channel identity,
        // call-ID and sequence validation. Do not accept caller-constructed SDP.
        val type = if (signal.kind == PeerCallSignalKind.OFFER) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        connection.setRemoteDescription(observer, SessionDescription(type, checkNotNull(signal.sdp)))
        return signal
    }

    /** Candidate frames are authenticated and replay-checked before WebRTC sees them. */
    fun addRemoteCandidate(connection: PeerConnection, frame: EncryptedAppFrame): PeerCallSignal {
        val signal = receive(frame)
        require(signal.kind == PeerCallSignalKind.ICE_CANDIDATE)
        val candidate = checkNotNull(signal.candidate)
        check(connection.addIceCandidate(IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate))) {
            "WebRTC rejected the authenticated ICE candidate."
        }
        return signal
    }
}
