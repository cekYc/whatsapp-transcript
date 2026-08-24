package dev.sesyazi.app.transcription

data class SpeechRegion(
    val startSample: Int,
    val endSampleExclusive: Int,
)

object SpeechChunker {
    fun contextChunks(
        samples: FloatArray,
        speechRegions: List<SpeechRegion>,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        maxChunkSeconds: Int = DEFAULT_MAX_CHUNK_SECONDS,
        joinSilenceMilliseconds: Int = DEFAULT_JOIN_SILENCE_MILLISECONDS,
        edgePaddingMilliseconds: Int = DEFAULT_EDGE_PADDING_MILLISECONDS,
        overlapMilliseconds: Int = DEFAULT_OVERLAP_MILLISECONDS,
    ): List<FloatArray> {
        if (samples.isEmpty()) return emptyList()

        val maxChunkSamples = maxChunkSeconds * sampleRate
        val joinSilenceSamples = joinSilenceMilliseconds * sampleRate / 1_000
        val edgePaddingSamples = edgePaddingMilliseconds * sampleRate / 1_000
        val overlapSamples = overlapMilliseconds * sampleRate / 1_000
        require(maxChunkSamples > overlapSamples)

        val regions = speechRegions
            .mapNotNull { region ->
                val start = region.startSample.coerceIn(0, samples.size)
                val end = region.endSampleExclusive.coerceIn(start, samples.size)
                if (end > start) SpeechRegion(start, end) else null
            }
            .sortedBy(SpeechRegion::startSample)

        if (regions.isEmpty()) {
            return fixedChunks(samples, maxChunkSamples, overlapSamples)
        }

        val groupedRanges = mutableListOf<SpeechRegion>()
        var groupStart = regions.first().startSample
        var groupEnd = regions.first().endSampleExclusive

        regions.drop(1).forEach { region ->
            val gap = (region.startSample - groupEnd).coerceAtLeast(0)
            val proposedEnd = maxOf(groupEnd, region.endSampleExclusive)
            val paddedStart = (groupStart - edgePaddingSamples).coerceAtLeast(0)
            val paddedEnd = (proposedEnd + edgePaddingSamples).coerceAtMost(samples.size)
            val fitsContextWindow = paddedEnd - paddedStart <= maxChunkSamples

            if (gap <= joinSilenceSamples && fitsContextWindow) {
                groupEnd = proposedEnd
            } else {
                groupedRanges += SpeechRegion(groupStart, groupEnd)
                groupStart = region.startSample
                groupEnd = region.endSampleExclusive
            }
        }
        groupedRanges += SpeechRegion(groupStart, groupEnd)

        return groupedRanges.flatMap { group ->
            val paddedStart = (group.startSample - edgePaddingSamples).coerceAtLeast(0)
            val paddedEnd = (group.endSampleExclusive + edgePaddingSamples)
                .coerceAtMost(samples.size)
            chunksFromRange(
                samples = samples,
                start = paddedStart,
                end = paddedEnd,
                maxChunkSamples = maxChunkSamples,
                overlapSamples = overlapSamples,
            )
        }
    }

    private fun fixedChunks(
        samples: FloatArray,
        maxChunkSamples: Int,
        overlapSamples: Int,
    ): List<FloatArray> = chunksFromRange(
        samples = samples,
        start = 0,
        end = samples.size,
        maxChunkSamples = maxChunkSamples,
        overlapSamples = overlapSamples,
    )

    private fun chunksFromRange(
        samples: FloatArray,
        start: Int,
        end: Int,
        maxChunkSamples: Int,
        overlapSamples: Int,
    ): List<FloatArray> {
        if (end <= start) return emptyList()
        if (end - start <= maxChunkSamples) return listOf(samples.copyOfRange(start, end))

        val chunks = mutableListOf<FloatArray>()
        var chunkStart = start
        while (chunkStart < end) {
            val chunkEnd = minOf(chunkStart + maxChunkSamples, end)
            chunks += samples.copyOfRange(chunkStart, chunkEnd)
            if (chunkEnd == end) break
            chunkStart = chunkEnd - overlapSamples
        }
        return chunks
    }

    private const val DEFAULT_SAMPLE_RATE = 16_000
    private const val DEFAULT_MAX_CHUNK_SECONDS = 28
    private const val DEFAULT_JOIN_SILENCE_MILLISECONDS = 1_200
    private const val DEFAULT_EDGE_PADDING_MILLISECONDS = 500
    private const val DEFAULT_OVERLAP_MILLISECONDS = 750
}
