package com.example.aitranslator.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

/**
 * Real microphone capture backed by [AudioRecord], producing 16-bit mono PCM.
 *
 * The read loop runs while the returned flow is collected and tears the recorder
 * down on cancellation, so callers control its lifetime purely via the
 * collecting coroutine's scope.
 */
class AudioRecordCapture : AudioCapture {

    override val sampleRateHz: Int = SAMPLE_RATE_HZ

    private val minBufferSize: Int = AudioRecord.getMinBufferSize(
        SAMPLE_RATE_HZ,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    ).coerceAtLeast(FALLBACK_BUFFER_BYTES)

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class) // isClosedForSend in the read loop
    @SuppressLint("MissingPermission") // enforced by @RequiresPermission on the public entrypoint
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun audioFrames(): Flow<AudioChunk> = callbackFlow {
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize"
        }

        val frameSamples = minBufferSize / 2 // 2 bytes per 16-bit sample
        val buffer = ShortArray(frameSamples)
        val startNanos = System.nanoTime()
        recorder.startRecording()

        try {
            while (!isClosedForSend) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                val frame = buffer.copyOf(read)
                val timestampMs = (System.nanoTime() - startNanos) / 1_000_000
                trySend(AudioChunk(frame, timestampMs))
            }
        } finally {
            recorder.stop()
            recorder.release()
        }

        awaitClose {
            // Loop exits via isClosedForSend; nothing extra to do here.
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val FALLBACK_BUFFER_BYTES = 4_096
    }
}
