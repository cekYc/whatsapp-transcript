package dev.sesyazi.app.transcription

import java.util.Locale

object TranscriptMerger {
    fun merge(existing: String, next: String, maxOverlapWords: Int = 16): String {
        val left = existing.trim()
        val right = next.trim()
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left

        val leftWords = left.split(WHITESPACE)
        val rightWords = right.split(WHITESPACE)
        val maxOverlap = minOf(maxOverlapWords, leftWords.size, rightWords.size)

        for (overlap in maxOverlap downTo 1) {
            val leftStart = leftWords.size - overlap
            val matches = (0 until overlap).all { offset ->
                normalize(leftWords[leftStart + offset]) == normalize(rightWords[offset])
            }
            if (matches) {
                return (leftWords + rightWords.drop(overlap)).joinToString(" ")
            }
        }

        return "$left $right"
    }

    private fun normalize(word: String): String = word
        .trim { character -> !character.isLetterOrDigit() }
        .lowercase(TURKISH_LOCALE)

    private val WHITESPACE = Regex("\\s+")
    private val TURKISH_LOCALE = Locale.forLanguageTag("tr-TR")
}
