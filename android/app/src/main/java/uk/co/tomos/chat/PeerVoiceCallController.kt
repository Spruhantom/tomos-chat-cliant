package uk.co.tomos.chat

import android.content.Context
import java.io.Closeable
import java.util.UUID

/**
 * Owns one-to-one call state and Android audio for an already Noise-authenticated
 * conversation. No audio is sent before the remote peer accepts the call.
 */
class PeerVoiceCallController(
    context: Context,
    private val conversation: PeerConversationSession,
    private val peerFingerprint: String,
    private val onSnapshotChanged: (PeerCallSnapshot?) -> Unit = {},
    private val onError: (Throwable) -> Unit = {}
) : Closeable {
    private val appContext = context.applicationContext

    @Volatile
    var snapshot: PeerCallSnapshot? = null
        private set

    @Volatile
    private var audio: AndroidVoiceAudioEngine? = null

    @Synchronized
    fun startOutgoing(): UUID {
        check(conversation.status == PeerConnectionStatus.CONNECTED) { "Connect securely before calling." }
        check(snapshot == null || snapshot?.state in setOf(PeerCallState.ENDED, PeerCallState.FAILED)) {
            "A call is already in progress."
        }
        val callId = UUID.randomUUID()
        publish(PeerCallSnapshot(callId, peerFingerprint, PeerCallState.OUTGOING))
        try {
            conversation.sendVoiceSignal(callId, PeerVoiceSignal.INVITE)
        } catch (error: Throwable) {
            finish(PeerCallEndReason.NETWORK_LOST, failed = true)
            throw error
        }
        return callId
    }

    @Synchronized
    fun acceptIncoming() {
        val current = requireNotNull(snapshot) { "There is no incoming call." }
        check(current.state == PeerCallState.INCOMING) { "There is no incoming call to accept." }
        publish(PeerCallStateMachine.transition(current, PeerCallEvent.Accept))
        try {
            startAudio(current.callId)
            conversation.sendVoiceSignal(current.callId, PeerVoiceSignal.ACCEPT)
            publish(PeerCallStateMachine.transition(requireNotNull(snapshot), PeerCallEvent.Connected))
        } catch (error: Throwable) {
            finish(PeerCallEndReason.SECURITY_FAILURE, failed = true)
            throw error
        }
    }

    @Synchronized
    fun rejectIncoming() {
        val current = requireNotNull(snapshot) { "There is no incoming call." }
        check(current.state == PeerCallState.INCOMING)
        conversation.sendVoiceSignal(current.callId, PeerVoiceSignal.REJECT)
        publish(PeerCallStateMachine.transition(current, PeerCallEvent.Reject))
    }

    @Synchronized
    fun hangUp() {
        val current = snapshot ?: return
        if (current.state in setOf(PeerCallState.ENDED, PeerCallState.FAILED)) return
        runCatching { conversation.sendVoiceSignal(current.callId, PeerVoiceSignal.HANGUP) }
        finish(PeerCallEndReason.LOCAL_HANGUP)
    }

    fun setMuted(muted: Boolean) {
        check(snapshot?.state == PeerCallState.ACTIVE) { "There is no active call." }
        checkNotNull(audio).setMuted(muted)
    }

    fun setSpeakerphoneEnabled(enabled: Boolean): Boolean {
        check(snapshot?.state == PeerCallState.ACTIVE) { "There is no active call." }
        return checkNotNull(audio).setSpeakerphoneEnabled(enabled)
    }

    /** Called by the single conversation receive loop after Noise authentication/decryption. */
    @Synchronized
    fun handle(event: PeerConversationEvent) {
        when (event) {
            is PeerConversationEvent.VoiceSignalReceived -> handleSignal(event.callId, event.signal)
            is PeerConversationEvent.VoiceFrameReceived -> {
                val current = snapshot ?: return
                if (current.callId != event.callId || current.state != PeerCallState.ACTIVE) return
                try {
                    checkNotNull(audio).playPcmFrame(event.pcm)
                } catch (error: Throwable) {
                    onError(error)
                    finish(PeerCallEndReason.NETWORK_LOST, failed = true)
                }
            }
            else -> Unit
        }
    }

    @Synchronized
    private fun handleSignal(callId: UUID, signal: PeerVoiceSignal) {
        val current = snapshot
        when (signal) {
            PeerVoiceSignal.INVITE -> {
                if (current == null || current.state in setOf(PeerCallState.ENDED, PeerCallState.FAILED)) {
                    publish(PeerCallSnapshot(callId, peerFingerprint, PeerCallState.INCOMING))
                } else {
                    runCatching { conversation.sendVoiceSignal(callId, PeerVoiceSignal.REJECT) }
                }
            }
            PeerVoiceSignal.ACCEPT -> {
                if (current?.callId == callId && current.state == PeerCallState.OUTGOING) {
                    try {
                        startAudio(callId)
                        publish(PeerCallStateMachine.transition(current, PeerCallEvent.Connected))
                    } catch (error: Throwable) {
                        onError(error)
                        finish(PeerCallEndReason.SECURITY_FAILURE, failed = true)
                    }
                }
            }
            PeerVoiceSignal.REJECT -> {
                if (current?.callId == callId && current.state !in setOf(PeerCallState.ENDED, PeerCallState.FAILED)) {
                    finish(PeerCallEndReason.REJECTED)
                }
            }
            PeerVoiceSignal.HANGUP -> {
                if (current?.callId == callId && current.state !in setOf(PeerCallState.ENDED, PeerCallState.FAILED)) {
                    finish(PeerCallEndReason.REMOTE_HANGUP)
                }
            }
        }
    }

    private fun startAudio(callId: UUID) {
        check(audio == null) { "Call audio is already running." }
        val engine = AndroidVoiceAudioEngine(
            appContext,
            onCapturedPcm = { pcm ->
                val current = snapshot
                if (current?.callId == callId && current.state == PeerCallState.ACTIVE) {
                    conversation.sendVoiceFrame(callId, pcm)
                }
            },
            onError = { error ->
                onError(error)
                finish(PeerCallEndReason.NETWORK_LOST, failed = true)
            }
        )
        engine.start()
        audio = engine
    }

    @Synchronized
    private fun finish(reason: PeerCallEndReason, failed: Boolean = false) {
        val current = snapshot ?: return
        if (current.state in setOf(PeerCallState.ENDED, PeerCallState.FAILED)) return
        runCatching { audio?.close() }
        audio = null
        val next = PeerCallStateMachine.transition(current, PeerCallEvent.End(reason))
        publish(if (failed && next.state != PeerCallState.FAILED) next.copy(state = PeerCallState.FAILED) else next)
    }

    private fun publish(value: PeerCallSnapshot) {
        snapshot = value
        onSnapshotChanged(value)
    }

    override fun close() {
        hangUp()
        runCatching { audio?.close() }
        audio = null
    }
}
