package com.example.aitranslator.audio

import kotlin.math.sqrt

/**
 * A single frame of captured 16-bit PCM audio.
 *
 * [pcm] holds signed mono samples; [timestampMs] is the capture time relative to
 * the start of the recording session.
 */
class AudioChunk(
    val pcm: ShortArray,
    val timestampMs: Long,
) {
    /**
     * Normalized RMS loudness in 0f..1f, suitable for driving a VU meter.
     * Computed lazily so consumers that don't need it pay nothing.
     */
    val amplitude: Float by lazy {
        if (pcm.isEmpty()) return@lazy 0f
        var sumSquares = 0.0
        for (sample in pcm) {
            val normalized = sample / Short.MAX_VALUE.toDouble()
            sumSquares += normalized * normalized
        }
        sqrt(sumSquares / pcm.size).toFloat().coerceIn(0f, 1f)
    }
}
