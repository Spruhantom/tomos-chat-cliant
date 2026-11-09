package uk.co.tomos.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground-call PCM capture/playback only.
 *
 * This class does not encode, encrypt, signal, or transmit audio. Callers must
 * never forward captured PCM to a socket. A future media adapter must encrypt
 * authenticated media packets before transport and must not use this callback
 * as a plaintext network path.
 */
class AndroidVoiceAudioEngine(
    context: Context,
    private val onCapturedPcm: (ByteArray) -> Unit,
    private val onError: (Throwable) -> Unit = {}
) : Closeable {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val running = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)
    private val lastError = AtomicReference<Throwable?>(null)
    private val playbackLock = Any()

    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var captureThread: Thread? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var previousSpeakerphoneState: Boolean = false
    private var previousCommunicationDevice: AudioDeviceInfo? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false

    val isRunning: Boolean get() = running.get()
    val isMuted: Boolean get() = muted.get()
    val failure: Throwable? get() = lastError.get()

    @Synchronized
    fun start() {
        check(!running.get()) { "Voice audio is already running." }
        lastError.set(null)
        requestAudioFocus()

        try {
            previousAudioMode = audioManager.mode
            previousSpeakerphoneState = audioManager.isSpeakerphoneOn
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                previousCommunicationDevice = audioManager.communicationDevice
            }

            val minRecordBuffer = AudioRecord.getMinBufferSize(
                VoiceAudioFormat.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            check(minRecordBuffer > 0) { "This device cannot initialise the microphone format." }
            val bufferBytes = maxOf(minRecordBuffer, VoiceAudioFormat.FRAME_BYTES * 4)

            val input = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(VoiceAudioFormat.SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
            check(input.state == AudioRecord.STATE_INITIALIZED) {
                input.release()
                "The microphone recorder could not be initialised."
            }

            val minTrackBuffer = AudioTrack.getMinBufferSize(
                VoiceAudioFormat.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            check(minTrackBuffer > 0) {
                input.release()
                "This device cannot initialise the playback format."
            }
            val output = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(VoiceAudioFormat.SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minTrackBuffer, VoiceAudioFormat.FRAME_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            check(output.state == AudioTrack.STATE_INITIALIZED) {
                input.release()
                output.release()
                "The audio output could not be initialised."
            }

            recorder = input
            player = output
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            configureVoiceEffects(input.audioSessionId)
            output.play()
            input.startRecording()
            running.set(true)
            captureThread = Thread(::captureLoop, "TomChat-VoiceCapture").apply {
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            releaseAudioResources()
            restoreAudioRouting()
            abandonAudioFocus()
            lastError.set(error)
            throw error
        }
    }

    fun setMuted(value: Boolean) {
        muted.set(value)
    }

    /**
     * Selects the built-in speaker or earpiece. Returns false if the requested
     * route is unavailable. Wired and Bluetooth headsets remain Android-routed.
     */
    @Suppress("DEPRECATION")
    fun setSpeakerphoneEnabled(enabled: Boolean): Boolean {
        check(running.get()) { "Voice audio is not running." }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wantedType = if (enabled) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            } else {
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == wantedType }
                ?: return false
            audioManager.setCommunicationDevice(device)
        } else {
            audioManager.isSpeakerphoneOn = enabled
            true
        }
    }

    /**
     * Plays exactly one 20 ms mono PCM16 frame. The caller supplies decoded
     * audio only after a future authenticated media layer has verified it.
     */
    fun playPcmFrame(pcm: ByteArray) {
        VoiceAudioFormat.requireValidFrame(pcm)
        check(running.get()) { "Voice audio is not running." }
        val output = checkNotNull(player) { "Audio output is unavailable." }
        synchronized(playbackLock) {
            var offset = 0
            while (offset < pcm.size && running.get()) {
                val written = output.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                check(written > 0) { "Audio playback stopped unexpectedly (code $written)." }
                offset += written
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false) && recorder == null && player == null && !focusHeld) return
        try { recorder?.stop() } catch (_: IllegalStateException) { }
        captureThread?.takeIf { it !== Thread.currentThread() }?.join(STOP_JOIN_TIMEOUT_MS)
        captureThread = null
        releaseAudioResources()
        restoreAudioRouting()
        abandonAudioFocus()
    }

    override fun close() = stop()

    private fun captureLoop() {
        val input = recorder ?: return
        val buffer = ByteArray(VoiceAudioFormat.FRAME_BYTES)
        while (running.get()) {
            var offset = 0
            while (offset < buffer.size && running.get()) {
                val count = input.read(buffer, offset, buffer.size - offset, AudioRecord.READ_BLOCKING)
                if (count < 0) {
                    if (running.get()) {
                        val error = IllegalStateException("Microphone capture failed (code $count).")
                        lastError.set(error)
                        try { onError(error) } catch (_: Throwable) { }
                        stop()
                    }
                    return
                }
                if (count > 0) offset += count
            }
            if (offset == buffer.size && running.get()) {
                val frame = if (muted.get()) ByteArray(buffer.size) else buffer.copyOf()
                try {
                    onCapturedPcm(frame)
                } catch (error: Throwable) {
                    lastError.set(error)
                    try { onError(error) } catch (_: Throwable) { }
                    stop()
                    return
                }
            }
        }
    }

    private fun requestAudioFocus() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                val error = IllegalStateException("Audio focus was lost; the call audio engine stopped.")
                lastError.set(error)
                try { onError(error) } catch (_: Throwable) { }
                stop()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(listener)
                .setWillPauseWhenDucked(true)
                .build()
            focusRequest = request
            focusHeld = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            focusHeld = audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        check(focusHeld) { "Audio focus could not be acquired." }
    }

    private fun configureVoiceEffects(sessionId: Int) {
        // Effects are optional hardware/OS capabilities. A device-specific
        // effect failure must not prevent an otherwise usable audio session.
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching { AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true } }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching { NoiseSuppressor.create(sessionId)?.also { it.enabled = true } }.getOrNull()
        }
        if (AutomaticGainControl.isAvailable()) {
            automaticGainControl = runCatching { AutomaticGainControl.create(sessionId)?.also { it.enabled = true } }.getOrNull()
        }
    }

    private fun releaseAudioResources() {
        listOf(echoCanceler, noiseSuppressor, automaticGainControl).forEach { effect ->
            try { effect?.release() } catch (_: Exception) { }
        }
        echoCanceler = null
        noiseSuppressor = null
        automaticGainControl = null
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        synchronized(playbackLock) {
            try { player?.pause() } catch (_: Exception) { }
            try { player?.flush() } catch (_: Exception) { }
            try { player?.release() } catch (_: Exception) { }
            player = null
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreAudioRouting() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.setCommunicationDevice(previousCommunicationDevice)
            } else {
                audioManager.isSpeakerphoneOn = previousSpeakerphoneState
            }
            audioManager.mode = previousAudioMode
        } catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    private fun abandonAudioFocus() {
        if (!focusHeld) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let(audioManager::abandonAudioFocusRequest)
            } else {
                audioManager.abandonAudioFocus(null)
            }
        } catch (_: Exception) { }
        focusRequest = null
        focusHeld = false
    }

    companion object {
        private const val STOP_JOIN_TIMEOUT_MS = 500L
    }
}

/** Fixed PCM format for the initial Android audio-device prototype. */
object VoiceAudioFormat {
    const val SAMPLE_RATE_HZ = 48_000
    const val CHANNEL_COUNT = 1
    const val BITS_PER_SAMPLE = 16
    const val FRAME_DURATION_MS = 20
    const val SAMPLES_PER_FRAME = SAMPLE_RATE_HZ * FRAME_DURATION_MS / 1_000
    const val FRAME_BYTES = SAMPLES_PER_FRAME * CHANNEL_COUNT * (BITS_PER_SAMPLE / 8)

    fun requireValidFrame(pcm: ByteArray) {
        require(pcm.size == FRAME_BYTES) { "A voice frame must contain exactly $FRAME_BYTES PCM bytes." }
    }
}
