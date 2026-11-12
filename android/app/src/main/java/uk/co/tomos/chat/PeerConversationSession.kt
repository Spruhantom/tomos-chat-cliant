package uk.co.tomos.chat

import java.io.IOException
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

enum class PeerConnectionStatus { ENCRYPTING, CONNECTED, DISCONNECTED, FAILED }

sealed interface PeerConversationEvent {
    data class MessageReceived(val message: StoredChatMessage) : PeerConversationEvent
    data class DeliveryConfirmed(val messageId: UUID) : PeerConversationEvent
    data class UnmatchedDeliveryAcknowledgement(val messageId: UUID) : PeerConversationEvent
    data object DuplicateMessage : PeerConversationEvent
    data class VoiceSignalReceived(val callId: UUID, val signal: PeerVoiceSignal) : PeerConversationEvent
    data class VoiceFrameReceived(val callId: UUID, val pcm: ByteArray) : PeerConversationEvent
    data class GroupProtocolReceived(val frame: GroupProtocolFrame) : PeerConversationEvent
}

/**
 * A conversation-scoped adapter over an already authenticated Noise channel.
 * All application frames go through EncryptedPeerChannel; callers should run
 * blocking receive() on Dispatchers.IO and publish returned messages to UI.
 */
class PeerConversationSession(
    private val peerFingerprint: String,
    private val channel: EncryptedPeerChannel,
    private val history: EncryptedMessageStore,
    private val clock: () -> Long = System::currentTimeMillis
) : AutoCloseable {
    private val statusRef = AtomicReference(PeerConnectionStatus.CONNECTED)
    private val sendStateLock = Any()

    init {
        require(peerFingerprint.isNotBlank() && peerFingerprint.length <= 128)
        check(channel.isOpen) { "Conversation requires an established encrypted channel." }
    }

    val status: PeerConnectionStatus
        get() = if (statusRef.get() == PeerConnectionStatus.CONNECTED && !channel.isOpen) {
            statusRef.compareAndSet(PeerConnectionStatus.CONNECTED, PeerConnectionStatus.DISCONNECTED)
            statusRef.get()
        } else statusRef.get()

    fun sendText(text: String): StoredChatMessage {
        check(status == PeerConnectionStatus.CONNECTED) {
            "Messages cannot be sent until the encrypted peer connection is established."
        }
        require(text.isNotBlank()) { "Message cannot be empty." }
        require(text.toByteArray(Charsets.UTF_8).size <= AppFrameCodec.MAX_PAYLOAD_BYTES)
        val message = StoredChatMessage(
            id = UUID.randomUUID(),
            peerFingerprint = peerFingerprint,
            direction = StoredMessageDirection.OUTGOING,
            status = StoredMessageStatus.QUEUED,
            createdAtEpochMillis = clock(),
            body = text
        )
        return synchronized(sendStateLock) {
            history.append(message)
            try {
                channel.send(EncryptedAppFrame(EncryptedAppFrame.Type.TEXT, message.id, text.toByteArray(Charsets.UTF_8)))
                history.updateOutgoingStatus(peerFingerprint, message.id, StoredMessageStatus.SENT)
                message.copy(status = StoredMessageStatus.SENT)
            } catch (error: Exception) {
                statusRef.set(PeerConnectionStatus.FAILED)
                try { history.updateOutgoingStatus(peerFingerprint, message.id, StoredMessageStatus.FAILED) } catch (_: IOException) { }
                throw error
            }
        }
    }

    /** Sends an opaque MLS protocol envelope inside the established Noise channel.
     * This does not itself authenticate MLS membership or decrypt group content.
     */
    fun sendGroupProtocolFrame(frame: GroupProtocolFrame) {
        check(status == PeerConnectionStatus.CONNECTED) { "Encrypted peer connection is not active." }
        channel.send(EncryptedAppFrame(
            EncryptedAppFrame.Type.GROUP_CONTROL,
            UUID.randomUUID(),
            GroupProtocolCodec.encode(frame)
        ))
    }

    /** Sends call control inside the established Noise channel. */
    fun sendVoiceSignal(callId: UUID, signal: PeerVoiceSignal) {
        check(status == PeerConnectionStatus.CONNECTED) { "Encrypted peer connection is not active." }
        channel.send(EncryptedAppFrame(EncryptedAppFrame.Type.VOICE_SIGNAL, callId, PeerVoiceCallCodec.encodeSignal(signal)))
    }

    /** Sends one 20 ms PCM frame; EncryptedPeerChannel encrypts it before socket I/O. */
    fun sendVoiceFrame(callId: UUID, pcm: ByteArray) {
        check(status == PeerConnectionStatus.CONNECTED) { "Encrypted peer connection is not active." }
        channel.send(EncryptedAppFrame(EncryptedAppFrame.Type.VOICE_SIGNAL, callId, PeerVoiceCallCodec.encodeMedia(pcm)))
    }

    /**
     * Processes one authenticated application frame. Delivery acknowledgements
     * are themselves sent inside the established Noise channel; no plaintext
     * application frame is ever written to the socket.
     */
    @Throws(IOException::class)
    fun receiveEvent(): PeerConversationEvent {
        check(status == PeerConnectionStatus.CONNECTED) { "Encrypted peer connection is not active." }
        return try {
            val frame = channel.receive()
            when (frame.type) {
                EncryptedAppFrame.Type.DELIVERY_ACK -> {
                    require(frame.payload.isEmpty()) { "Delivery acknowledgement must not contain a payload." }
                    synchronized(sendStateLock) {
                        if (history.updateOutgoingStatus(peerFingerprint, frame.messageId, StoredMessageStatus.DELIVERED)) {
                            PeerConversationEvent.DeliveryConfirmed(frame.messageId)
                        } else {
                            PeerConversationEvent.UnmatchedDeliveryAcknowledgement(frame.messageId)
                        }
                    }
                }
                EncryptedAppFrame.Type.GROUP_CONTROL -> PeerConversationEvent.GroupProtocolReceived(
                    GroupProtocolCodec.decode(frame.payload)
                )
                EncryptedAppFrame.Type.VOICE_SIGNAL -> when (val voice = PeerVoiceCallCodec.decode(frame.payload)) {
                    is PeerVoicePayload.Signal -> PeerConversationEvent.VoiceSignalReceived(frame.messageId, voice.signal)
                    is PeerVoicePayload.Media -> PeerConversationEvent.VoiceFrameReceived(frame.messageId, voice.pcm)
                }
                EncryptedAppFrame.Type.TEXT -> {
                    val decoder = Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    val body = decoder.decode(java.nio.ByteBuffer.wrap(frame.payload)).toString()
                    require(body.isNotBlank()) { "Received empty text message." }
                    val message = StoredChatMessage(
                        id = frame.messageId,
                        peerFingerprint = peerFingerprint,
                        direction = StoredMessageDirection.INCOMING,
                        status = StoredMessageStatus.RECEIVED,
                        createdAtEpochMillis = clock(),
                        body = body
                    )
                    val isNew = history.append(message)
                    // A duplicate authenticated message ID is not shown twice, but
                    // is acknowledged again so a sender can recover after a lost ACK.
                    channel.send(EncryptedAppFrame(EncryptedAppFrame.Type.DELIVERY_ACK, frame.messageId, byteArrayOf()))
                    if (isNew) PeerConversationEvent.MessageReceived(message)
                    else PeerConversationEvent.DuplicateMessage
                }
                else -> throw IOException("Unexpected application frame type in a text conversation.")
            }
        } catch (error: Exception) {
            statusRef.set(PeerConnectionStatus.FAILED)
            if (error is IOException) throw error
            throw IOException("Encrypted conversation frame was rejected.", error)
        }
    }

    /** Compatibility helper for callers that expect a text-only receive. */
    @Throws(IOException::class)
    fun receiveText(): StoredChatMessage = when (val event = receiveEvent()) {
        is PeerConversationEvent.MessageReceived -> event.message
        is PeerConversationEvent.DeliveryConfirmed ->
            throw IOException("A delivery acknowledgement was received while waiting for a text message.")
        is PeerConversationEvent.UnmatchedDeliveryAcknowledgement ->
            throw IOException("An unrecognised delivery acknowledgement was received.")
        PeerConversationEvent.DuplicateMessage ->
            throw IOException("A duplicate message was received and acknowledged.")
        is PeerConversationEvent.VoiceSignalReceived,
        is PeerConversationEvent.VoiceFrameReceived,
        is PeerConversationEvent.GroupProtocolReceived ->
            throw IOException("A voice frame was received while waiting for a text message.")
    }

    override fun close() {
        statusRef.set(PeerConnectionStatus.DISCONNECTED)
        channel.close()
    }
}
