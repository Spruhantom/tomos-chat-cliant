package uk.co.tomos.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DirectPeerAuthenticatorTest {
    private fun identity(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun contact(name: String, key: KeyPair): TrustedContact =
        TrustedContact(name, key.public.encoded, ContactCardCodec.fingerprint(key.public.encoded))

    @Test fun mutuallyAuthenticatesTrustedPeersOverLoopback() {
        val alice = identity()
        val bob = identity()
        val aliceContact = contact("Alice", alice)
        val bobContact = contact("Bob", bob)
        val executor = Executors.newSingleThreadExecutor()
        DirectPeerListener(InetSocketAddress("127.0.0.1", 0)).use { listener ->
            listener.start()
            val accepted = executor.submit<AuthenticatedPeerConnection> {
                listener.acceptAuthenticated(DirectPeerAuthenticator(bob, aliceContact))
            }
            val outgoing = DirectPeerConnector.connect(
                "127.0.0.1", listener.localPort, DirectPeerAuthenticator(alice, bobContact)
            )
            val incoming = accepted.get(5, TimeUnit.SECONDS)
            assertEquals(bobContact.fingerprint, outgoing.peerFingerprint)
            assertEquals(aliceContact.fingerprint, incoming.peerFingerprint)
            assertTrue(outgoing.isConnected)
            assertTrue(incoming.isConnected)
            outgoing.close()
            incoming.close()
            assertFalse(outgoing.isConnected)
        }
        executor.shutdownNow()
    }

    @Test fun rejectsUnexpectedTrustedIdentity() {
        val alice = identity()
        val bob = identity()
        val mallory = identity()
        val executor = Executors.newSingleThreadExecutor()
        DirectPeerListener(InetSocketAddress("127.0.0.1", 0)).use { listener ->
            listener.start()
            val accepted = executor.submit<AuthenticatedPeerConnection> {
                listener.acceptAuthenticated(DirectPeerAuthenticator(bob, contact("Alice", mallory)))
            }
            org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                DirectPeerConnector.connect("127.0.0.1", listener.localPort, DirectPeerAuthenticator(alice, contact("Bob", bob)))
            }
            org.junit.Assert.assertThrows(java.util.concurrent.ExecutionException::class.java) {
                accepted.get(5, TimeUnit.SECONDS)
            }
        }
        executor.shutdownNow()
    }
}
