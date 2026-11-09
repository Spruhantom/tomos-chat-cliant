package uk.co.tomos.chat

import android.content.Context
import android.content.pm.PackageManager
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the WebRTC audio device module and factory for one foreground call.
 *
 * This deliberately creates host-candidate-only peer connections: no STUN,
 * TURN, SFU, hosted signalling, or relay service is configured. Call setup
 * signalling must travel over the already authenticated Noise peer channel.
 * Voice media itself must travel only through WebRTC's DTLS-SRTP transport.
 *
 * Do not run AndroidVoiceAudioEngine at the same time: WebRTC's audio device
 * module owns microphone capture and speaker playback for this call.
 */
class AndroidWebRtcMediaEngine(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val sessions = ConcurrentHashMap.newKeySet<WebRtcAudioSession>()
    private val audioDeviceModule: JavaAudioDeviceModule
    private val factory: PeerConnectionFactory

    init {
        initialiseWebRtc(appContext)
        audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
    }

    /**
     * Creates a one-to-one audio-only peer connection. It is not connected
     * until a future authenticated signalling layer exchanges SDP and ICE.
     */
    @Synchronized
    fun createAudioSession(observer: PeerConnection.Observer): WebRtcAudioSession {
        check(!closed.get()) { "WebRTC media engine is closed." }
        check(
            appContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) { "Microphone permission has not been granted." }

        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }

        val connection = checkNotNull(factory.createPeerConnection(configuration, observer)) {
            "WebRTC could not create a peer connection."
        }
        val source = try {
            factory.createAudioSource(MediaConstraints())
        } catch (error: Throwable) {
            connection.close()
            connection.dispose()
            throw error
        }
        val track = try {
            factory.createAudioTrack(LOCAL_AUDIO_TRACK_ID, source)
        } catch (error: Throwable) {
            connection.close()
            connection.dispose()
            source.dispose()
            throw error
        }
        val sender = connection.addTrack(track, listOf(LOCAL_AUDIO_STREAM_ID))
        if (sender == null) {
            connection.close()
            connection.dispose()
            track.dispose()
            source.dispose()
            error("WebRTC could not attach the local audio track.")
        }
        lateinit var session: WebRtcAudioSession
        session = WebRtcAudioSession(connection, track, source) { sessions.remove(session) }
        sessions.add(session)
        return session
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessions.toList().forEach(WebRtcAudioSession::close)
        sessions.clear()
        factory.dispose()
        audioDeviceModule.release()
    }

    private companion object {
        const val LOCAL_AUDIO_TRACK_ID = "tomchat-audio"
        const val LOCAL_AUDIO_STREAM_ID = "tomchat-audio-stream"

        @Volatile
        private var initialised = false

        @Synchronized
        fun initialiseWebRtc(context: Context) {
            if (initialised) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .createInitializationOptions()
            )
            initialised = true
        }
    }
}

/** Resources belonging to one peer connection. Close on hang-up or failure. */
class WebRtcAudioSession internal constructor(
    val peerConnection: PeerConnection,
    val localAudioTrack: AudioTrack,
    private val audioSource: AudioSource,
    private val onClosed: () -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        localAudioTrack.setEnabled(false)
        peerConnection.close()
        peerConnection.dispose()
        localAudioTrack.dispose()
        audioSource.dispose()
        onClosed()
    }
}
