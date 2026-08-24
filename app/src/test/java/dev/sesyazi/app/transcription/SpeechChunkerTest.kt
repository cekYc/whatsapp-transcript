package dev.sesyazi.app.transcription

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechChunkerTest {
    @Test
    fun joinsSpeechSeparatedByShortPauseAndKeepsOriginalAudio() {
        val samples = FloatArray(60) { it.toFloat() }

        val chunks = SpeechChunker.contextChunks(
            samples = samples,
            speechRegions = listOf(
                SpeechRegion(10, 20),
                SpeechRegion(25, 35),
            ),
            sampleRate = 10,
            maxChunkSeconds = 10,
            joinSilenceMilliseconds = 1_000,
            edgePaddingMilliseconds = 200,
            overlapMilliseconds = 100,
        )

        assertEquals(1, chunks.size)
        assertArrayEquals(samples.copyOfRange(8, 37), chunks.single(), 0f)
    }

    @Test
    fun keepsSpeechSeparatedByLongPauseInDifferentChunks() {
        val samples = FloatArray(60) { it.toFloat() }

        val chunks = SpeechChunker.contextChunks(
            samples = samples,
            speechRegions = listOf(
                SpeechRegion(10, 20),
                SpeechRegion(35, 45),
            ),
            sampleRate = 10,
            maxChunkSeconds = 10,
            joinSilenceMilliseconds = 1_000,
            edgePaddingMilliseconds = 200,
            overlapMilliseconds = 100,
        )

        assertEquals(2, chunks.size)
        assertArrayEquals(samples.copyOfRange(8, 22), chunks[0], 0f)
        assertArrayEquals(samples.copyOfRange(33, 47), chunks[1], 0f)
    }

    @Test
    fun splitsLongSpeechWithOverlap() {
        val samples = FloatArray(100) { it.toFloat() }

        val chunks = SpeechChunker.contextChunks(
            samples = samples,
            speechRegions = listOf(SpeechRegion(0, 80)),
            sampleRate = 10,
            maxChunkSeconds = 3,
            joinSilenceMilliseconds = 1_000,
            edgePaddingMilliseconds = 0,
            overlapMilliseconds = 500,
        )

        assertEquals(3, chunks.size)
        assertArrayEquals(samples.copyOfRange(0, 30), chunks[0], 0f)
        assertArrayEquals(samples.copyOfRange(25, 55), chunks[1], 0f)
        assertArrayEquals(samples.copyOfRange(50, 80), chunks[2], 0f)
    }

    @Test
    fun fallsBackToOverlappingFullAudioWhenVadFindsNothing() {
        val samples = FloatArray(70) { it.toFloat() }

        val chunks = SpeechChunker.contextChunks(
            samples = samples,
            speechRegions = emptyList(),
            sampleRate = 10,
            maxChunkSeconds = 3,
            overlapMilliseconds = 500,
        )

        assertEquals(3, chunks.size)
        assertArrayEquals(samples.copyOfRange(0, 30), chunks[0], 0f)
        assertArrayEquals(samples.copyOfRange(25, 55), chunks[1], 0f)
        assertArrayEquals(samples.copyOfRange(50, 70), chunks[2], 0f)
    }
}
