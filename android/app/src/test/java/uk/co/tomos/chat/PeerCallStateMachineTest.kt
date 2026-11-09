package uk.co.tomos.chat

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PeerCallStateMachineTest {
    private fun call(state: PeerCallState) = PeerCallSnapshot(UUID.randomUUID(), "peer-fingerprint", state)

    @Test fun outgoingCallCanBecomeActiveAndEnd() {
        val active = PeerCallStateMachine.transition(call(PeerCallState.OUTGOING), PeerCallEvent.Connected)
        assertEquals(PeerCallState.ACTIVE, active.state)
        val ended = PeerCallStateMachine.transition(active, PeerCallEvent.End(PeerCallEndReason.LOCAL_HANGUP))
        assertEquals(PeerCallState.ENDED, ended.state)
    }

    @Test fun incomingCallCanBeAcceptedOrRejected() {
        assertEquals(PeerCallState.CONNECTING,
            PeerCallStateMachine.transition(call(PeerCallState.INCOMING), PeerCallEvent.Accept).state)
        val rejected = PeerCallStateMachine.transition(call(PeerCallState.INCOMING), PeerCallEvent.Reject)
        assertEquals(PeerCallState.ENDED, rejected.state)
        assertEquals(PeerCallEndReason.REJECTED, rejected.endReason)
    }

    @Test fun networkLossCanRecover() {
        val active = call(PeerCallState.ACTIVE)
        val reconnecting = PeerCallStateMachine.transition(active, PeerCallEvent.NetworkLost)
        assertEquals(PeerCallState.RECONNECTING, reconnecting.state)
        assertEquals(PeerCallState.ACTIVE,
            PeerCallStateMachine.transition(reconnecting, PeerCallEvent.Reconnected).state)
    }

    @Test fun remoteAcceptanceMovesOutgoingCallIntoConnecting() {
        val outgoing = PeerCallStateMachine.transition(call(PeerCallState.OUTGOING), PeerCallEvent.RemoteAccepted)
        assertEquals(PeerCallState.CONNECTING, outgoing.state)
        assertEquals(PeerCallState.ACTIVE,
            PeerCallStateMachine.transition(outgoing, PeerCallEvent.Connected).state)
    }

    @Test fun remoteRejectionAndLocalHangupAreTerminal() {
        val rejected = PeerCallStateMachine.transition(call(PeerCallState.OUTGOING), PeerCallEvent.RemoteRejected)
        assertEquals(PeerCallEndReason.REJECTED, rejected.endReason)
        val hungUp = PeerCallStateMachine.transition(call(PeerCallState.ACTIVE), PeerCallEvent.LocalHangup)
        assertEquals(PeerCallEndReason.LOCAL_HANGUP, hungUp.endReason)
        assertEquals(PeerCallState.ENDED, hungUp.state)
    }

    @Test fun timeoutEndsAnUnansweredCall() {
        val timedOut = PeerCallStateMachine.transition(call(PeerCallState.OUTGOING), PeerCallEvent.Timeout)
        assertEquals(PeerCallState.ENDED, timedOut.state)
        assertEquals(PeerCallEndReason.TIMEOUT, timedOut.endReason)
    }

    @Test fun securityFailureIsTerminalAndCannotBeReopened() {
        val failed = PeerCallStateMachine.transition(call(PeerCallState.CONNECTING),
            PeerCallEvent.End(PeerCallEndReason.SECURITY_FAILURE))
        assertEquals(PeerCallState.FAILED, failed.state)
        assertThrows(IllegalArgumentException::class.java) {
            PeerCallStateMachine.transition(failed, PeerCallEvent.RemoteEnded)
        }
    }
}
