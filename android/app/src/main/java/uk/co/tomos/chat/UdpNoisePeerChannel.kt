package uk.co.tomos.chat

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/**
 * Noise XX over a punched UDP socket, followed by stop-and-wait encrypted
 * datagrams. Calls are serialized; ACKs are also Noise-encrypted.
 */
class UdpNoisePeerChannel private constructor(
    private val socket: DatagramSocket,
    private val noise: NoisePeerSession,
    private val duplicateHandshake: ByteArray
) : EncryptedPeerTransport {
    private var txSequence = 0L
    private var rxSequence = 0L
    private var lastAckSequence: Long? = null
    private var lastAck: ByteArray? = null
    private val queued = ArrayDeque<ByteArray>()

    override val isOpen: Boolean get() = !socket.isClosed && socket.isConnected

    override fun send(frame: EncryptedAppFrame) = sendEncryptedPayload(AppFrameCodec.encode(frame))

    override fun receive(): EncryptedAppFrame = AppFrameCodec.decode(receiveEncryptedPayload())

    @Synchronized
    @Throws(IOException::class)
    private fun sendEncryptedPayload(plaintext: ByteArray) {
        require(plaintext.size <= MAX_PLAINTEXT)
        val packet = dataPacket(DATA, txSequence, noise.encrypt(plaintext))
        repeat(MAX_RETRIES) {
            socket.send(DatagramPacket(packet, packet.size))
            val deadline = System.nanoTime() + RETRY_MS * 1_000_000L
            while (System.nanoTime() < deadline) {
                val incoming = receivePacket() ?: break
                if (handle(incoming)) continue
                if (incoming.size >= HEADER_SIZE + MIN_CIPHERTEXT &&
                    incoming[0] == M0 && incoming[1] == M1 && incoming[2] == ACK) {
                    val input = ByteBuffer.wrap(incoming).order(ByteOrder.BIG_ENDIAN)
                    input.position(3)
                    val sequence = input.long
                    val cipher = ByteArray(input.remaining()).also(input::get)
                    val ack = noise.decrypt(cipher)
                    if (sequence == txSequence && ack.contentEquals(ackPayload(sequence))) {
                        txSequence++
                        return
                    }
                }
            }
        }
        throw SocketTimeoutException("The peer did not acknowledge the encrypted UDP message.")
    }

    @Synchronized
    @Throws(IOException::class)
    private fun receiveEncryptedPayload(): ByteArray {
        if (queued.isNotEmpty()) return queued.removeFirst()
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            val packet = receivePacket() ?: continue
            if (handle(packet) && queued.isNotEmpty()) return queued.removeFirst()
        }
        throw SocketTimeoutException("Timed out waiting for an encrypted UDP message.")
    }

    private fun handle(packet: ByteArray): Boolean {
        if (packet.size >= 4 && packet[0] == M0 && packet[1] == M1 &&
            packet[2] == HANDSHAKE && packet[3] == 3.toByte()) {
            if (packet.contentEquals(duplicateHandshake)) {
                val ack = handshakePacket(4, byteArrayOf())
                socket.send(DatagramPacket(ack, ack.size))
            }
            return true
        }
        if (packet.size < HEADER_SIZE + MIN_CIPHERTEXT ||
            packet[0] != M0 || packet[1] != M1 || packet[2] != DATA) return false
        val input = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        input.position(3)
        val sequence = input.long
        val cipher = ByteArray(input.remaining()).also(input::get)
        if (sequence == rxSequence) {
            val plaintext = noise.decrypt(cipher)
            val ackCipher = noise.encrypt(ackPayload(sequence))
            val ack = dataPacket(ACK, sequence, ackCipher)
            socket.send(DatagramPacket(ack, ack.size))
            lastAckSequence = sequence
            lastAck = ack
            rxSequence++
            queued.addLast(plaintext)
            return true
        }
        if (rxSequence > 0 && sequence == rxSequence - 1 && lastAckSequence == sequence) {
            lastAck?.let { socket.send(DatagramPacket(it, it.size)) }
        }
        return true
    }

    private fun receivePacket(): ByteArray? {
        val bytes = ByteArray(MAX_PACKET)
        val packet = DatagramPacket(bytes, bytes.size)
        socket.soTimeout = RETRY_MS.toInt()
        return try {
            socket.receive(packet)
            packet.data.copyOf(packet.length)
        } catch (_: SocketTimeoutException) { null }
    }

    override fun close() {
        noise.close()
        socket.close()
    }

    companion object {
        private const val M0 = 0x54.toByte()
        private const val M1 = 0x4e.toByte()
        private const val HANDSHAKE = 1.toByte()
        private const val DATA = 2.toByte()
        private const val ACK = 3.toByte()
        private const val HEADER_SIZE = 11
        private const val MIN_CIPHERTEXT = 16
        private const val MAX_PACKET = 65_507
        private const val MAX_PLAINTEXT = 60_000
        private const val MAX_RETRIES = 8
        private const val RETRY_MS = 400L

        private data class Received(val bytes: ByteArray, val source: InetSocketAddress)

        /** Run signed simultaneous-open probes, then authenticate Noise on that same UDP path. */
        @Throws(IOException::class)
        fun connectAfterPunch(
            socket: DatagramSocket,
            remote: InetSocketAddress,
            localProbe: HolePunchProbe,
            trustedPeerSigningKey: ByteArray,
            expectedPeerDeviceId: ByteArray,
            nowSeconds: () -> Long,
            localIdentity: NoiseIdentity,
            trustedPeer: TrustedContact
        ): UdpNoisePeerChannel {
            UdpHolePuncher.punch(socket, remote, localProbe, trustedPeerSigningKey,
                expectedPeerDeviceId, nowSeconds)
            return connect(socket, remote, localIdentity, trustedPeer)
        }

        /** Responder side: validate the peer's signed probe before accepting Noise XX. */
        @Throws(IOException::class)
        fun acceptAfterPunch(
            socket: DatagramSocket,
            remote: InetSocketAddress,
            localProbe: HolePunchProbe,
            trustedPeerSigningKey: ByteArray,
            expectedPeerDeviceId: ByteArray,
            nowSeconds: () -> Long,
            localIdentity: NoiseIdentity,
            trustedPeer: TrustedContact,
            timeoutMs: Int = 30_000
        ): UdpNoisePeerChannel {
            UdpHolePuncher.punch(socket, remote, localProbe, trustedPeerSigningKey,
                expectedPeerDeviceId, nowSeconds, timeoutMs.coerceIn(250, 10_000))
            return accept(socket, localIdentity, trustedPeer, timeoutMs)
        }

        @Throws(IOException::class)
        fun connect(
            socket: DatagramSocket,
            remote: InetSocketAddress,
            localIdentity: NoiseIdentity,
            trustedPeer: TrustedContact
        ): UdpNoisePeerChannel {
            require(!socket.isClosed && !remote.isUnresolved)
            socket.connect(remote)
            val noise = NoisePeerSession.create(localIdentity, trustedPeer, initiator = true)
            try {
                val m1 = handshakePacket(1, noise.writeHandshake())
                var m2: ByteArray? = null
                for (attempt in 0 until MAX_RETRIES) {
                    socket.send(DatagramPacket(m1, m1.size))
                    val response = receiveHandshake(socket) ?: continue
                    if (isHandshake(response.bytes, 2)) {
                        m2 = response.bytes.copyOfRange(4, response.bytes.size)
                        break
                    }
                }
                noise.readHandshake(m2 ?: throw SocketTimeoutException("No response to Noise message one."))
                val m3 = handshakePacket(3, noise.writeHandshake())
                noise.finishHandshake()
                var confirmed = false
                for (attempt in 0 until MAX_RETRIES) {
                    socket.send(DatagramPacket(m3, m3.size))
                    val response = receiveHandshake(socket) ?: continue
                    if (response.bytes.contentEquals(handshakePacket(4, byteArrayOf()))) {
                        confirmed = true
                        break
                    }
                }
                if (!confirmed) throw SocketTimeoutException("No confirmation for the Noise handshake.")
                return UdpNoisePeerChannel(socket, noise, m3)
            } catch (error: Exception) {
                noise.close()
                socket.close()
                if (error is IOException) throw error
                throw IOException("UDP Noise handshake failed.", error)
            }
        }

        @Throws(IOException::class)
        fun accept(
            socket: DatagramSocket,
            localIdentity: NoiseIdentity,
            trustedPeer: TrustedContact,
            timeoutMs: Int = 30_000
        ): UdpNoisePeerChannel {
            require(!socket.isClosed && timeoutMs in 500..60_000)
            val noise = NoisePeerSession.create(localIdentity, trustedPeer, initiator = false)
            try {
                val deadline = System.nanoTime() + timeoutMs * 1_000_000L
                var remotePinned = false
                var firstMessage: ByteArray? = null
                var secondMessage: ByteArray? = null
                var thirdMessage: ByteArray? = null
                while (System.nanoTime() < deadline) {
                    val response = receiveHandshake(socket) ?: continue
                    val packet = response.bytes
                    if (isHandshake(packet, 1)) {
                        if (!remotePinned) {
                            socket.connect(response.source)
                            remotePinned = true
                        }
                        if (firstMessage?.contentEquals(packet) == true) {
                            secondMessage?.let { socket.send(DatagramPacket(it, it.size)) }
                            continue
                        }
                        firstMessage = packet
                        noise.readHandshake(packet.copyOfRange(4, packet.size))
                        val reply = handshakePacket(2, noise.writeHandshake())
                        secondMessage = reply
                        socket.send(DatagramPacket(reply, reply.size))
                    } else if (isHandshake(packet, 3) && secondMessage != null) {
                        if (thirdMessage?.contentEquals(packet) == true) {
                            val ack = handshakePacket(4, byteArrayOf())
                            socket.send(DatagramPacket(ack, ack.size))
                            continue
                        }
                        thirdMessage = packet
                        noise.readHandshake(packet.copyOfRange(4, packet.size))
                        noise.finishHandshake()
                        val ack = handshakePacket(4, byteArrayOf())
                        socket.send(DatagramPacket(ack, ack.size))
                        return UdpNoisePeerChannel(socket, noise, packet)
                    }
                }
                throw SocketTimeoutException("Timed out waiting for the Noise handshake.")
            } catch (error: Exception) {
                noise.close()
                socket.close()
                if (error is IOException) throw error
                throw IOException("UDP Noise handshake failed.", error)
            }
        }

        private fun receiveHandshake(socket: DatagramSocket): Received? {
            val bytes = ByteArray(MAX_PACKET)
            val packet = DatagramPacket(bytes, bytes.size)
            socket.soTimeout = RETRY_MS.toInt()
            return try {
                socket.receive(packet)
                Received(packet.data.copyOf(packet.length), InetSocketAddress(packet.address, packet.port))
            } catch (_: SocketTimeoutException) { null }
        }

        private fun isHandshake(packet: ByteArray, phase: Int): Boolean =
            packet.size >= 4 && packet[0] == M0 && packet[1] == M1 &&
                packet[2] == HANDSHAKE && packet[3].toInt() == phase

        private fun handshakePacket(phase: Int, payload: ByteArray): ByteArray =
            byteArrayOf(M0, M1, HANDSHAKE, phase.toByte()) + payload

        private fun dataPacket(kind: Byte, sequence: Long, ciphertext: ByteArray): ByteArray =
            ByteBuffer.allocate(HEADER_SIZE + ciphertext.size).order(ByteOrder.BIG_ENDIAN)
                .put(M0).put(M1).put(kind).putLong(sequence).put(ciphertext).array()

        private fun ackPayload(sequence: Long): ByteArray =
            ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
                .put(0x54.toByte()).put(0x41.toByte()).putLong(sequence).array()
    }
}
