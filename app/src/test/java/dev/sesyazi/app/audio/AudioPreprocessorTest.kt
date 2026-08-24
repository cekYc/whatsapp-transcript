package dev.sesyazi.app.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioPreprocessorTest {
    @Test
    fun removesDcOffset() {
        val result = AudioPreprocessor.normalize(FloatArray(32) { 0.2f })

        assertTrue(result.all { abs(it) < 0.0001f })
    }

    @Test
    fun raisesQuietSpeechWithoutClipping() {
        val input = FloatArray(64) { index -> if (index % 2 == 0) 0.01f else -0.01f }

        val result = AudioPreprocessor.normalize(input)

        assertTrue(result.maxOf { abs(it) } > 0.01f)
        assertTrue(result.all { abs(it) <= 0.98f })
    }
}
