package com.crazestudio.fluxmusic.data.model

data class LyricLine(
    val timeMs: Long,
    val text: String
)

data class TrackLyrics(
    val videoId: String,
    val syncedLines: List<LyricLine>,
    val plainLyrics: String?,
    val rawSyncedLrc: String? = null,
    val instrumental: Boolean = false,
    val fromCache: Boolean = false
) {
    val hasSynced: Boolean get() = syncedLines.isNotEmpty()
    val hasPlain: Boolean get() = !plainLyrics.isNullOrBlank()
}

object LrcParser {
    private val TIMESTAMP_REGEX = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    fun parse(syncedLrc: String?): List<LyricLine> {
        if (syncedLrc.isNullOrBlank()) return emptyList()
        val parsed = ArrayList<LyricLine>()
        syncedLrc.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val matches = TIMESTAMP_REGEX.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            val text = TIMESTAMP_REGEX.replace(line, "").trim()
            if (text.isEmpty()) return@forEach
            for (match in matches) {
                val mins = match.groupValues[1].toLongOrNull() ?: continue
                val secs = match.groupValues[2].toLongOrNull() ?: continue
                val fracRaw = match.groupValues.getOrNull(3).orEmpty()
                val fracMs = when (fracRaw.length) {
                    0 -> 0L
                    1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
                    else -> fracRaw.take(3).toLongOrNull() ?: 0L
                }
                val totalMs = mins * 60_000L + secs * 1000L + fracMs
                parsed.add(LyricLine(timeMs = totalMs, text = text))
            }
        }
        return parsed.sortedBy { it.timeMs }
    }

    fun activeLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var low = 0
        var high = lines.lastIndex
        var best = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs + 150L) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }
}
