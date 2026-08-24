package dev.sesyazi.app.audio

import kotlin.math.abs
import kotlin.math.sqrt

object AudioPreprocessor {
    fun normalize(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples

        var mean = 0.0
        samples.forEach { mean += it }
        mean /= samples.size

        var sumSquares = 0.0
        var peak = 0f
        samples.forEach { sample ->
            val centered = (sample - mean).toFloat()
            sumSquares += centered * centered
            peak = maxOf(peak, abs(centered))
        }
        val rms = sqrt(sumSquares / samples.size).toFloat()
        if (rms < MIN_USEFUL_RMS || peak == 0f) {
            return FloatArray(samples.size) { index -> (samples[index] - mean).toFloat() }
        }

        val targetGain = (TARGET_RMS / rms).coerceIn(MIN_GAIN, MAX_GAIN)
        val clippingSafeGain = MAX_PEAK / peak
        val gain = minOf(targetGain, clippingSafeGain)
        return FloatArray(samples.size) { index ->
            ((samples[index] - mean) * gain).toFloat().coerceIn(-MAX_PEAK, MAX_PEAK)
        }
    }

    fun padWithSilence(samples: FloatArray, paddingSamples: Int): FloatArray {
        if (paddingSamples <= 0) return samples
        return FloatArray(samples.size + paddingSamples * 2).also { padded ->
            samples.copyInto(padded, destinationOffset = paddingSamples)
        }
    }

    private const val TARGET_RMS = 0.12f
    private const val MIN_USEFUL_RMS = 0.0015f
    private const val MIN_GAIN = 0.5f
    private const val MAX_GAIN = 5f
    private const val MAX_PEAK = 0.98f
}
