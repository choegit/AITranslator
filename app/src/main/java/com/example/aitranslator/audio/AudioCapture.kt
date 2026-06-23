package com.example.aitranslator.audio

import kotlinx.coroutines.flow.Flow

/**
 * Source of microphone audio. Implementations emit a cold [Flow] of
 * [AudioChunk]s; capture starts when collection begins and stops (releasing the
 * recorder) when collection is cancelled.
 *
 * Defined as an interface so the real [AudioRecord]-backed capture can be
 * swapped for a fake source in tests.
 */
interface AudioCapture {
    /** Sample rate, in Hz, of the emitted PCM. */
    val sampleRateHz: Int

    fun audioFrames(): Flow<AudioChunk>
}
