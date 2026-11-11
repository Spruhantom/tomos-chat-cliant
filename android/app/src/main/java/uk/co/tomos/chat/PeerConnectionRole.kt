package uk.co.tomos.chat

/**
 * Ensures exactly one trusted peer initiates a direct handshake. Both devices
 * derive the same role from the independently verified identity fingerprints.
 */
object PeerConnectionRole {
    fun shouldInitiate(localFingerprint: String, remoteFingerprint: String): Boolean {
        require(localFingerprint.isNotBlank() && remoteFingerprint.isNotBlank())
        require(localFingerprint != remoteFingerprint) { "A device cannot connect to itself." }
        return localFingerprint < remoteFingerprint
    }
}
