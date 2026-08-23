package dev.sesyazi.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingLinearResamplerTest {
    @Test
    fun downsamplesAcrossInputChunksWithoutLosingPosition() {
        val resampler = StreamingLinearResampler(
            inputRate = 48_000,
            outputRate = 16_000,
            maxOutputSamples = 20,
        )

        resampler.append(floatArrayOf(0f, 1f, 2f, 3f, 4f))
        resampler.append(floatArrayOf(5f, 6f, 7f, 8f, 9f))

        assertArrayEquals(floatArrayOf(0f, 3f, 6f, 9f), resampler.finish(), 0.0001f)
    }

    @Test
    fun preservesSamplesWhenRatesMatch() {
        val resampler = StreamingLinearResampler(16_000, 16_000, 10)
        resampler.append(floatArrayOf(-1f, 0f))
        resampler.append(floatArrayOf(0.5f, 1f))

        assertArrayEquals(floatArrayOf(-1f, 0f, 0.5f, 1f), resampler.finish(), 0f)
    }

    @Test
    fun interpolatesWhenUpsampling() {
        val resampler = StreamingLinearResampler(8_000, 16_000, 10)
        resampler.append(floatArrayOf(0f, 1f, 0f))
        val result = resampler.finish()

        assertEquals(5, result.size)
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f, 0.5f, 0f), result, 0.0001f)
    }
}
