package uk.co.tomos.chat

import java.nio.ByteBuffer
import java.util.UUID

/** Call control and media are carried only as authenticated Noise application frames. */
enum class PeerVoiceSignal(val wireId: Int) {
    INVITE(1), ACCEPT(2), REJECT(3), HANGUP(4);

    companion object {
        fun fromWire(value: Int): PeerVoiceSignal =
            entries.firstOrNull { it.wireId == value }
                ?: throw IllegalArgumentException("Unknown voice signal.")
    }
}

sealed interface PeerVoicePayload {
    data class Signal(val signal: PeerVoiceSignal) : PeerVoicePayload
    data class Media(val pcm: ByteArray) : PeerVoicePayload
}

object PeerVoiceCallCodec {
    private const val MEDIA_MARKER = 0x20

    fun encodeSignal(signal: PeerVoiceSignal): ByteArray = byteArrayOf(signal.wireId.toByte())

    fun encodeMedia(pcm: ByteArray): ByteArray {
        VoiceAudioFormat.requireValidFrame(pcm)
        return byteArrayOf(MEDIA_MARKER.toByte()) + pcm
    }

    fun decode(payload: ByteArray): PeerVoicePayload {
        require(payload.isNotEmpty()) { "Empty voice payload." }
        val marker = payload[0].toInt() and 0xff
        return if (marker == MEDIA_MARKER) {
            require(payload.size == VoiceAudioFormat.FRAME_BYTES + 1) { "Invalid voice media frame length." }
            PeerVoicePayload.Media(payload.copyOfRange(1, payload.size))
        } else {
            require(payload.size == 1) { "Invalid voice control payload." }
            PeerVoicePayload.Signal(PeerVoiceSignal.fromWire(marker))
        }
    }
}
