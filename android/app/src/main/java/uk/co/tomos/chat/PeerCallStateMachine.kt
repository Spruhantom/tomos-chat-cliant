package uk.co.tomos.chat

import java.util.UUID

/** Signalling state only. Media readiness is reported only by WebRTC callbacks. */
enum class PeerCallState {
    IDLE, OUTGOING, INCOMING, CONNECTING, ACTIVE, RECONNECTING, ENDED, FAILED
}

enum class PeerCallEndReason { LOCAL_HANGUP, REMOTE_HANGUP, REJECTED, TIMEOUT, NETWORK_LOST, SECURITY_FAILURE }

data class PeerCallSnapshot(
    val callId: UUID,
    val peerFingerprint: String,
    val state: PeerCallState,
    val endReason: PeerCallEndReason? = null
) {
    init {
        require(ContactIdentityFingerprint.isValid(peerFingerprint))
        require((state == PeerCallState.ENDED || state == PeerCallState.FAILED) == (endReason != null))
    }
}

sealed interface PeerCallEvent {
    data object Accept : PeerCallEvent
    data object Reject : PeerCallEvent
    data object RemoteAccepted : PeerCallEvent
    data object RemoteRejected : PeerCallEvent
    data object Connected : PeerCallEvent
    data object NetworkLost : PeerCallEvent
    data object Reconnected : PeerCallEvent
    data object Timeout : PeerCallEvent
    data object LocalHangup : PeerCallEvent
    data class End(val reason: PeerCallEndReason) : PeerCallEvent
    data object RemoteEnded : PeerCallEvent
}

object PeerCallStateMachine {
    fun outgoing(callId: UUID, peerFingerprint: String): PeerCallSnapshot =
        PeerCallSnapshot(callId, peerFingerprint, PeerCallState.OUTGOING)

    fun incoming(callId: UUID, peerFingerprint: String): PeerCallSnapshot =
        PeerCallSnapshot(callId, peerFingerprint, PeerCallState.INCOMING)

    fun transition(current: PeerCallSnapshot, event: PeerCallEvent): PeerCallSnapshot {
        require(current.state !in setOf(PeerCallState.ENDED, PeerCallState.FAILED)) {
            "A completed call cannot transition; create a new call session."
        }
        return when (current.state to event) {
            PeerCallState.OUTGOING to PeerCallEvent.RemoteAccepted,
            PeerCallState.INCOMING to PeerCallEvent.Accept -> current.copy(state = PeerCallState.CONNECTING)
            PeerCallState.OUTGOING to PeerCallEvent.RemoteRejected,
            PeerCallState.INCOMING to PeerCallEvent.Reject ->
                finish(current, PeerCallState.ENDED, PeerCallEndReason.REJECTED)
            PeerCallState.OUTGOING to PeerCallEvent.Connected,
            PeerCallState.CONNECTING to PeerCallEvent.Connected -> current.copy(state = PeerCallState.ACTIVE)
            PeerCallState.ACTIVE to PeerCallEvent.NetworkLost -> current.copy(state = PeerCallState.RECONNECTING)
            PeerCallState.RECONNECTING to PeerCallEvent.Reconnected -> current.copy(state = PeerCallState.ACTIVE)
            else -> when (event) {
                PeerCallEvent.LocalHangup -> finish(current, PeerCallState.ENDED, PeerCallEndReason.LOCAL_HANGUP)
                PeerCallEvent.RemoteEnded -> finish(current, PeerCallState.ENDED, PeerCallEndReason.REMOTE_HANGUP)
                PeerCallEvent.Timeout -> finish(current, PeerCallState.ENDED, PeerCallEndReason.TIMEOUT)
                is PeerCallEvent.End -> finish(
                    current,
                    if (event.reason == PeerCallEndReason.SECURITY_FAILURE) PeerCallState.FAILED else PeerCallState.ENDED,
                    event.reason
                )
                else -> throw IllegalStateException("Invalid call transition: ${current.state} + $event")
            }
        }
    }

    private fun finish(current: PeerCallSnapshot, state: PeerCallState, reason: PeerCallEndReason) =
        current.copy(state = state, endReason = reason)
}
