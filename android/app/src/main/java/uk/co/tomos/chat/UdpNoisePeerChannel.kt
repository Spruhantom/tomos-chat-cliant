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
 * Reliable ordered encrypted datagrams over a punched UDP socket.
 *
 * Noise XX authenticates both devices and pins the contact's signed Noise
 * static key. Application records and acknowledgements are Noise-encrypted.
 * Stop-and-wait per direction prevents UDP loss/reordering from advancing
 * Noise transport counters out of sequence. Call methods from one serialized
 * networking coroutine/task; do not use this as a concurrent socket API.
 */
class UdpNoisePeerChannel private constructor(
    private val socket: DatagramSocket,
    private val noise: NoisePeerSession,
    private val timeoutMs: Int,
    private val duplicateHandshake: ByteArray?
) : Closeable {
    private var sendSequence = 0L
    private var receiveSequence = 0L
    private var lastAckSequence: Long? = null
    private var lastAckPacket: ByteArray? = null
    private val queued = ArrayDeque<ByteArray>()

    @Synchronized
    @Throws(IOException::class)
    fun send(plaintext: ByteArray) {
        require(plaintext.size <= MAX_PLAINTEXT)
        val ciphertext = noise.encrypt(plaintext)
        val packet = dataPacket(DATA, sendSequence, ciphertext)
        repeat(MAX_RETRIES) {
            socket.send(DatagramPacket(packet, packet.size))
            val deadline = System.nanoTime() + RETRY_INTERVAL_MS * 1_000_000L
            while (System.nanoTime() < deadline) {
                val incoming = receivePacketOrNull() ?: break
                if (handleIncoming(incoming)) continue
                if (incoming.size >= HEADER_SIZE + MIN_CIPHERTEXT &&
                    incoming[0] == MAGIC_0 && incoming[1] == MAGIC_1 && incoming[2] == ACK) {
                    val input = ByteBuffer.wrap(incoming).order(ByteOrder.BIG_ENDIAN)
                    input.position(3)
                    val sequence = input.long
                    val ack = ByteArray(input.remaining()).also(input::get)
                    val decoded = noise.decrypt(ack)
                    if (sequence == sendSequence && decoded.contentEquals(ackPayload(sequence))) {
                        sendSequence++
                        return
                    }
                }
            }
        }
        throw IOException("UDP peer did not acknowledge the encrypted message.")
    }

    @Synchronized
    @Throws(IOException::class)
    fun receive(): ByteArray {
        if (queued.isNotEmpty()) return queued.removeFirst()
        while (true) {
            val packet = receivePacketOrNull()
                ?: throw SocketTimeoutException("Timed out waiting for an encrypted UDP message.")
            if (handleIncoming(packet) && queued.isNotEmpty()) return queued.removeFirst()
        }
    }

    private fun handleIncoming(packet: ByteArray): Boolean {
        if (packet.size >= 4 && packet[0] == MAGIC_0 && packet[1] == MAGIC_1 &&
            packet[2] == HANDSHAKE && packet[3] == 3) {
            if (duplicateHandshake?.contentEquals(packet) == true) {
                val ack = handshakePacket(4, byteArrayOf())
                socket.send(DatagramPacket(ack, ack.size))
            }
            return true
        }
        if (packet.size < HEADER_SIZE + MIN_CIPHERTEXT ||
            packet[0] != MAGIC_0 || packet[1] != MAGIC_1 || packet[2] != DATA) return false

        val input = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        input.position(3)
        val sequence = input.long
        val ciphertext = ByteArray(input.remaining()).also(input::get)
        if (sequence == receiveSequence) {
            val plaintext = noise.decrypt(ciphertext)
            val ackCiphertext = noise.encrypt(ackPayload(sequence))
            val ack = dataPacket(ACK, sequence, ackCiphertext)
            socket.send(DatagramPacket(ack, ack.size))
            lastAckSequence = sequence
            lastAckPacket = ack
            receiveSequence++
            queued.addLast(plaintext)
            return true
        }
        if (receiveSequence > 0 && sequence == receiveSequence - 1 &&
            lastAckSequence == sequence) {
            lastAckPacket?.let { socket.send(DatagramPacket(it, it.size)) }
        }
        return true
    }

    private fun receivePacketOrNull(): ByteArray? {
        socket.soTimeout = RETRY_INTERVAL_MS.toInt()
        val buffer = ByteArray(MAX_PACKET)
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            packet.data.copyOf(packet.length)
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    override fun close() {
        noise.close()
        socket.close()
    }

    companion object {
        private const val MAGIC_0 = 0x54.toByte()
        private const val MAGIC_1 = 0x4e.toByte()
        private const val HANDSHAKE = 1.toByte()
        private const val DATA = 2.toByte()
        private const val ACK = 3.toByte()
        private const val HEADER_SIZE = 11
        private const val MIN_CIPHERTEXT = 16
        private const val MAX_PACKET = 65_507
        private const val MAX_PLAINTEXT = 60_000
        private const val MAX_RETRIES = 8
        private const val RETRY_INTERVAL_MS = 400L

        @Throws(IOException::class)
        fun connect(
            socket: DatagramSocket,
            remote: InetSocketAddress,
            localIdentity: NoiseIdentity,
            trustedPeer: TrustedContact,
            timeoutMs: Int = 5_000
        ): UdpNoisePeerChannel {
            require(!socket.isClosed && !remote.isUnresolved && timeoutMs in 500..30_000)
            socket.connect(remote)
            socket.soTimeout = RETRY_INTERVAL_MS.toInt()
            val noise = NoisePeerSession.create(localIdentity, trustedPeer, initiator = true)
            try {
                val m1 = handshakePacket(1, noise.writeHandshake())
                var m2: ByteArray? = null
                repeat(MAX_RETRIES) {
                    socket.send(DatagramPacket(m1, m1.size))
                    val packet = receiveHandshake(socket, timeoutMs)
                    if (packet != null && isHandshake(packet, 2)) {
                        m2 = packet.copyOfRange(4, packet.size)
                        return@repeat
                    }
                }
                val messageTwo = m2 ?: throw SocketTimeoutException("Peer did not answer the Noise handshake.")
                noise.readHandshake(messageTwo)
                val m3Bytes = noise.writeHandshake()
                noise.finishHandshake()
                val m3 = handshakePacket(3, m3Bytes)
                var confirmed = false
                repeat(MAX_RETRIES) {
                    if (confirmed) return@repeat
                    socket.send(DatagramPacket(m3, m3.size))
                    val packet = receiveHandshake(socket, timeoutMs)
                    if (packet != null && packet.contentEquals(handshakePacket(4, byteArrayOf()))) confirmed = true
                }
                if (!confirmed) throw SocketTimeoutException("Peer did not confirm the Noise handshake.")
                return UdpNoisePeerChannel(socket, noise, timeoutMs, null)
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
            socket.soTimeout = RETRY_INTERVAL_MS.toInt()
            val noise = NoisePeerSession.create(localIdentity, trustedPeer, initiator = false)
            try {
                val deadline = System.nanoTime() + timeoutMs * 1_000_000L
                var m1: ByteArray? = null
                var m2: ByteArray? = null
                var m3: ByteArray? = null
                var remoteConnected = false
                while (System.nanoTime() < deadline) {
                    val packet = receiveHandshake(socket, timeoutMs) ?: continue
                    if (isHandshake(packet, 1)) {
                        if (!remoteConnected) {
                            val address = lastPacketAddress
                                ?: throw IOException("Could not identify the UDP peer address.")
                            socket.connect(address)
                            remoteConnected = true
                        }
                        if (m1?.contentEquals(packet) == true) {
                            m2?.let { socket.send(DatagramPacket(it, it.size)) }
                            continue
                        }
                        m1 = packet
                        noise.readHandshake(packet.copyOfRange(4, packet.size))
                        val response = handshakePacket(2, noise.writeHandshake())
                        m2 = response
                        socket.send(DatagramPacket(response, response.size))
                    } else if (isHandshake(packet, 3) && m2 != null) {
                        if (m3?.contentEquals(packet) == true) {
                            val ack = handshakePacket(4, byteArrayOf())
                            socket.send(DatagramPacket(ack, ack.size))
                            continue
                        }
                        m3 = packet
                        noise.readHandshake(packet.copyOfRange(4, packet.size))
                        noise.finishHandshake()
                        val ack = handshakePacket(4, byteArrayOf())
                        socket.send(DatagramPacket(ack, ack.size))
                        return UdpNoisePeerChannel(socket, noise, timeoutMs, packet)
                    }
                }
                throw SocketTimeoutException("Timed out waiting for the UDP Noise handshake.")
            } catch (error: Exception) {
                noise.close()
                socket.close()
                if (error is IOException) throw error
                throw IOException("UDP Noise handshake failed.", error)
            }
        }

        // Set by the receive helper so the unconnected responder can pin its
        // first packet source before switching the DatagramSocket to connected.
        private var lastPacketAddress: InetSocketAddress? = null

        private fun receiveHandshake(socket: DatagramSocket, timeoutMs: Int): ByteArray? {
            val buffer = ByteArray(MAX_PACKET)
            val packet = DatagramPacket(buffer, buffer.size)
            return try {
                socket.soTimeout = minOf(RETRY_INTERVAL_MS.toInt(), timeoutMs)
                socket.receive(packet)
                lastPacketAddress = InetSocketAddress(packet.address, packet.port)
                packet.data.copyOf(packet.length)
            } catch (_: SocketTimeoutException) {
                null
            }
        }

        private fun isHandshake(packet: ByteArray, phase: Int): Boolean =
            packet.size >= 4 && packet[0] == MAGIC_0 && packet[1] == MAGIC_1 &&
                packet[2] == HANDSHAKE && packet[3].toInt() == phase

        private fun handshakePacket(phase: Int, payload: ByteArray): ByteArray =
            byteArrayOf(MAGIC_0, MAGIC_1, HANDSHAKE, phase.toByte()) + payload

        private fun dataPacket(kind: Byte, sequence: Long, ciphertext: ByteArray): ByteArray =
            ByteBuffer.allocate(HEADER_SIZE + ciphertext.size).order(ByteOrder.BIG_ENDIAN)
                .put(MAGIC_0).put(MAGIC_1).put(kind).putLong(sequence).put(ciphertext).array()

        private fun ackPayload(sequence: Long): ByteArray =
            ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN).put(0x54.toByte())
                .put(0x41.toByte()).putLong(sequence).array()
    }
}
