package uk.co.tomos.chat

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Noise XX over a punched UDP socket, followed by reliable ordered encrypted
 * datagrams. A dedicated reader handles inbound DATA/ACK packets so the
 * conversation can send and receive concurrently without racing socket reads.
 */
class UdpNoisePeerChannel private constructor(
    private val socket: DatagramSocket,
    private val noise: NoisePeerSession,
    private val duplicateHandshake: ByteArray
) : EncryptedPeerTransport {
    private val closed = AtomicBoolean(false)
    private val sendLock = Any()
    private val outboundLock = Any()
    private val stateLock = java.lang.Object()
    private val queued = ArrayDeque<ByteArray>()
    private data class Reassembly(val messageId: Long, val fragmentCount: Int, val totalLength: Int, var nextIndex: Int, val output: ByteArrayOutputStream)
    private var reassembly: Reassembly? = null
    @Volatile private var txSequence = 0L
    private var rxSequence = 0L
    private var ackedSequence: Long? = null
    private var lastAckSequence: Long? = null
    private var lastAck: ByteArray? = null
    @Volatile private var failure: IOException? = null
    private val reader = Thread({ readLoop() }, "tomchat-udp-noise-reader").apply {
        isDaemon = true
        start()
    }

    override val isOpen: Boolean get() = !closed.get() && !socket.isClosed && socket.isConnected

    override fun send(frame: EncryptedAppFrame) {
        sendPayload(AppFrameCodec.encode(frame))
    }

    override fun receive(): EncryptedAppFrame =
        AppFrameCodec.decode(receivePayload())

    private fun sendPayload(plaintext: ByteArray) = synchronized(sendLock) {
        checkOpen()
        require(plaintext.isNotEmpty() && plaintext.size <= MAX_PLAINTEXT)
        val messageId = txSequence
        val fragmentCount = (plaintext.size + MAX_FRAGMENT_BYTES - 1) / MAX_FRAGMENT_BYTES
        require(fragmentCount <= MAX_FRAGMENTS)
        for (index in 0 until fragmentCount) {
            val start = index * MAX_FRAGMENT_BYTES
            val end = minOf(start + MAX_FRAGMENT_BYTES, plaintext.size)
            val fragment = encodeFragment(messageId, index, fragmentCount, plaintext.size, plaintext.copyOfRange(start, end))
            sendFragment(fragment)
        }
    }

    private fun sendFragment(fragment: ByteArray) {
        val sequence = txSequence
        val packet = synchronized(outboundLock) {
            val bytes = dataPacket(DATA, sequence, noise.encrypt(fragment))
            socket.send(DatagramPacket(bytes, bytes.size))
            bytes
        }
        repeat(MAX_RETRIES) {
            if (awaitAck(sequence, RETRY_MS)) {
                txSequence++
                return
            }
            checkOpen()
            synchronized(outboundLock) { socket.send(DatagramPacket(packet, packet.size)) }
        }
        throw SocketTimeoutException("The peer did not acknowledge the encrypted UDP fragment.")
    }

    private fun awaitAck(sequence: Long, waitMs: Long): Boolean {
        val deadline = System.nanoTime() + waitMs * 1_000_000L
        synchronized(stateLock) {
            while (ackedSequence != sequence && failure == null && !closed.get()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                stateLock.wait((remaining / 1_000_000L).coerceAtLeast(1L))
            }
            failure?.let { throw IOException("UDP Noise receive loop failed.", it) }
            if (ackedSequence == sequence) {
                ackedSequence = null
                return true
            }
            throw IOException("UDP Noise channel is closed.")
        }
    }

    private fun receivePayload(): ByteArray {
        val deadline = System.nanoTime() + RECEIVE_TIMEOUT_MS * 1_000_000L
        synchronized(stateLock) {
            while (queued.isEmpty() && failure == null && !closed.get()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw SocketTimeoutException("Timed out waiting for an encrypted UDP message.")
                stateLock.wait((remaining / 1_000_000L).coerceAtLeast(1L))
            }
            failure?.let { throw IOException("UDP Noise receive loop failed.", it) }
            if (queued.isEmpty()) throw IOException("UDP Noise channel is closed.")
            return queued.removeFirst()
        }
    }

    private fun readLoop() {
        try {
            while (!closed.get()) {
                val packet = receivePacket() ?: continue
                if (packet.size >= HEADER_SIZE + MIN_CIPHERTEXT &&
                    packet[0] == M0 && packet[1] == M1 && packet[2] == ACK) {
                    val input = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
                    input.position(3)
                    val sequence = input.long
                    val ciphertext = ByteArray(input.remaining()).also(input::get)
                    val plaintext = try { noise.decrypt(ciphertext) } catch (_: Exception) { continue }
                    if (sequence == txSequence && plaintext.contentEquals(ackPayload(sequence))) {
                        synchronized(stateLock) {
                            ackedSequence = sequence
                            stateLock.notifyAll()
                        }
                    }
                    continue
                }
                handleIncoming(packet)
            }
        } catch (error: Exception) {
            if (!closed.get()) {
                synchronized(stateLock) {
                    failure = if (error is IOException) error else IOException("Invalid UDP Noise packet.", error)
                    stateLock.notifyAll()
                }
                try { socket.close() } catch (_: Exception) { }
            }
        }
    }

    private fun handleIncoming(packet: ByteArray) {
        if (packet.size >= 4 && packet[0] == M0 && packet[1] == M1 &&
            packet[2] == HANDSHAKE && packet[3] == 3.toByte()) {
            if (packet.contentEquals(duplicateHandshake)) {
                val ack = handshakePacket(4, byteArrayOf())
                synchronized(outboundLock) { socket.send(DatagramPacket(ack, ack.size)) }
            }
            return
        }
        if (packet.size < HEADER_SIZE + MIN_CIPHERTEXT ||
            packet[0] != M0 || packet[1] != M1 || packet[2] != DATA) return
        val input = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        input.position(3)
        val sequence = input.long
        val ciphertext = ByteArray(input.remaining()).also(input::get)
        if (sequence == rxSequence) {
            val plaintext = try { noise.decrypt(ciphertext) } catch (_: Exception) { return }
            val completeMessage = acceptFragment(sequence, plaintext)
            val ack = synchronized(outboundLock) {
                val ackCiphertext = noise.encrypt(ackPayload(sequence))
                val encoded = dataPacket(ACK, sequence, ackCiphertext)
                socket.send(DatagramPacket(encoded, encoded.size))
                encoded
            }
            lastAckSequence = sequence
            lastAck = ack
            rxSequence++
            if (completeMessage != null) synchronized(stateLock) {
                queued.addLast(completeMessage)
                stateLock.notifyAll()
            }
        } else if (rxSequence > 0 && sequence == rxSequence - 1 && lastAckSequence == sequence) {
            lastAck?.let { cached ->
                synchronized(outboundLock) { socket.send(DatagramPacket(cached, cached.size)) }
            }
        }
    }

    private fun acceptFragment(sequence: Long, bytes: ByteArray): ByteArray? {
        require(bytes.size >= FRAGMENT_HEADER_BYTES && bytes[0] == FRAGMENT_M0 && bytes[1] == FRAGMENT_M1)
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        input.position(2)
        val messageId = input.long
        val index = input.short.toInt() and 0xffff
        val count = input.short.toInt() and 0xffff
        val totalLength = input.int
        val chunk = ByteArray(input.remaining()).also(input::get)
        require(count in 1..MAX_FRAGMENTS && index in 0 until count)
        require(totalLength in 1..MAX_PLAINTEXT)
        require(count == (totalLength + MAX_FRAGMENT_BYTES - 1) / MAX_FRAGMENT_BYTES)
        require(messageId == sequence - index)
        val expectedChunk = minOf(MAX_FRAGMENT_BYTES, totalLength - index * MAX_FRAGMENT_BYTES)
        require(chunk.size == expectedChunk)
        if (index == 0) {
            require(reassembly == null)
            reassembly = Reassembly(messageId, count, totalLength, 0, ByteArrayOutputStream(totalLength))
        }
        val state = reassembly ?: throw IOException("UDP fragment arrived without its first fragment.")
        require(state.messageId == messageId && state.fragmentCount == count &&
            state.totalLength == totalLength && state.nextIndex == index)
        state.output.write(chunk)
        state.nextIndex++
        if (state.nextIndex == state.fragmentCount) {
            val result = state.output.toByteArray()
            require(result.size == state.totalLength)
            reassembly = null
            return result
        }
        return null
    }

    private fun receivePacket(): ByteArray? {
        val bytes = ByteArray(MAX_PACKET)
        val packet = DatagramPacket(bytes, bytes.size)
        socket.soTimeout = RETRY_MS.toInt()
        return try {
            socket.receive(packet)
            packet.data.copyOf(packet.length)
        } catch (_: SocketTimeoutException) {
            null
        } catch (error: SocketException) {
            if (closed.get()) null else throw error
        }
    }

    private fun checkOpen() {
        check(isOpen) { "Encrypted UDP peer channel is closed." }
        failure?.let { throw IOException("UDP Noise receive loop failed.", it) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { noise.close() } finally {
                try { socket.close() } catch (_: Exception) { }
                synchronized(stateLock) { stateLock.notifyAll() }
            }
        }
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
        private const val MAX_PLAINTEXT = AppFrameCodec.MAX_FRAME_BYTES
        private const val MAX_FRAGMENT_BYTES = 1_000
        private const val MAX_FRAGMENTS = 32
        private const val FRAGMENT_HEADER_BYTES = 18
        private const val FRAGMENT_M0 = 0x54.toByte()
        private const val FRAGMENT_M1 = 0x46.toByte()
        private const val MAX_RETRIES = 8
        private const val RETRY_MS = 400L
        private const val RECEIVE_TIMEOUT_MS = 30_000L

        private data class Received(val bytes: ByteArray, val source: InetSocketAddress)

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

        private fun encodeFragment(messageId: Long, index: Int, count: Int, totalLength: Int, chunk: ByteArray): ByteArray =
            ByteBuffer.allocate(FRAGMENT_HEADER_BYTES + chunk.size).order(ByteOrder.BIG_ENDIAN)
                .put(FRAGMENT_M0).put(FRAGMENT_M1).putLong(messageId)
                .putShort(index.toShort()).putShort(count.toShort()).putInt(totalLength).put(chunk).array()

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
