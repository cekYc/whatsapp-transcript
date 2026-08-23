package dev.sesyazi.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptMergerTest {
    @Test
    fun removesRepeatedWordsAtChunkBoundary() {
        val result = TranscriptMerger.merge(
            "Merhaba bugün toplantıda yeni tasarımı konuştuk.",
            "yeni tasarımı konuştuk. Yarın tekrar bakacağız.",
        )

        assertEquals(
            "Merhaba bugün toplantıda yeni tasarımı konuştuk. Yarın tekrar bakacağız.",
            result,
        )
    }

    @Test
    fun comparesOverlapWithoutCaseOrPunctuation() {
        val result = TranscriptMerger.merge(
            "Bunu yarın konuşalım!",
            "YARIN konuşalım, saat üçte.",
        )

        assertEquals("Bunu yarın konuşalım! saat üçte.", result)
    }

    @Test
    fun keepsBothPartsWhenThereIsNoOverlap() {
        assertEquals(
            "Birinci bölüm. İkinci bölüm.",
            TranscriptMerger.merge("Birinci bölüm.", "İkinci bölüm."),
        )
    }
}
