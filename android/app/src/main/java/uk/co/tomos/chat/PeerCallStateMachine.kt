package uk.co.tomos.chat

import java.util.UUID

/** Signalling state only. This does not create sockets or carry voice media. */
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
        require(peerFingerprint.isNotBlank() && peerFingerprint.length <= 128)
        require((state == PeerCallState.ENDED || state == PeerCallState.FAILED) == (endReason != null))
    }
}

sealed interface PeerCallEvent {
    data object Accept : PeerCallEvent
    data object Reject : PeerCallEvent
    data object Connected : PeerCallEvent
    data object NetworkLost : PeerCallEvent
    data object Reconnected : PeerCallEvent
    data class End(val reason: PeerCallEndReason) : PeerCallEvent
    data object RemoteEnded : PeerCallEvent
}

object PeerCallStateMachine {
    fun transition(current: PeerCallSnapshot, event: PeerCallEvent): PeerCallSnapshot {
        require(current.state !in setOf(PeerCallState.ENDED, PeerCallState.FAILED)) {
            "A completed call cannot transition; create a new call session."
        }
        val next = when (current.state to event) {
            PeerCallState.OUTGOING to PeerCallEvent.Connected -> PeerCallState.ACTIVE
            PeerCallState.INCOMING to PeerCallEvent.Accept -> PeerCallState.CONNECTING
            PeerCallState.INCOMING to PeerCallEvent.Reject -> return current.copy(state = PeerCallState.ENDED, endReason = PeerCallEndReason.REJECTED)
            PeerCallState.CONNECTING to PeerCallEvent.Connected -> PeerCallState.ACTIVE
            PeerCallState.ACTIVE to PeerCallEvent.NetworkLost -> PeerCallState.RECONNECTING
            PeerCallState.RECONNECTING to PeerCallEvent.Reconnected -> PeerCallState.ACTIVE
            else -> when (event) {
                is PeerCallEvent.End -> return current.copy(state = if (event.reason == PeerCallEndReason.SECURITY_FAILURE) PeerCallState.FAILED else PeerCallState.ENDED, endReason = event.reason)
                PeerCallEvent.RemoteEnded -> return current.copy(state = PeerCallState.ENDED, endReason = PeerCallEndReason.REMOTE_HANGUP)
                else -> throw IllegalStateException("Invalid call transition: ${current.state} + $event")
            }
        }
        return current.copy(state = next, endReason = null)
    }
}
