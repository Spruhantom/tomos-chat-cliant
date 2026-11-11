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

    @Test fun securityFailureIsTerminalAndCannotBeReopened() {
        val failed = PeerCallStateMachine.transition(call(PeerCallState.CONNECTING),
            PeerCallEvent.End(PeerCallEndReason.SECURITY_FAILURE))
        assertEquals(PeerCallState.FAILED, failed.state)
        assertThrows(IllegalArgumentException::class.java) {
            PeerCallStateMachine.transition(failed, PeerCallEvent.RemoteEnded)
        }
    }
}
