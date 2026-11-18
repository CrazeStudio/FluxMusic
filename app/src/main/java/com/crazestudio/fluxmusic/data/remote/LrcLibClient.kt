package com.crazestudio.fluxmusic.data.remote

import com.crazestudio.fluxmusic.data.model.LrcParser
import com.crazestudio.fluxmusic.data.model.Track
import com.crazestudio.fluxmusic.data.model.TrackLyrics
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import kotlin.math.abs

/** LRCLIB adapter with exact lookup, ranked search fallback and bounded network responses. */
object LrcLibClient {
    private const val BASE_URL = "https://lrclib.net"
    private const val CLIENT_UA = "FluxMusic/4.0 (https://github.com/CrazeStudio/FluxMusic)"

    private val decorationRegex = Regex(
        """\s*(\((official|lyric|lyrics|audio|video|hd|4k|visualizer|remastered|feat\.?|ft\.?)[^)]*\)|\[(official|lyric|lyrics|audio|video|hd|4k)[^]]*])""",
        RegexOption.IGNORE_CASE
    )
    private val featuredRegex = Regex("""\s+(?:feat\.?|ft\.?)\s+.*$""", RegexOption.IGNORE_CASE)

    fun fetchLyrics(track: Track): TrackLyrics? {
        val title = cleanTitle(track.safeTitle)
        val artist = cleanArtist(track.safeArtist)
        if (title.isBlank()) return null
        val durationSeconds = (track.durationMs / 1000L).takeIf { it in 10L..3600L }

        val exactQuery = buildList {
            add("track_name=" + encode(title))
            if (artist.isNotBlank() && !artist.equals("Unknown artist", true)) {
                add("artist_name=" + encode(artist))
            }
            if (track.album.isNotBlank() && !track.album.equals("YouTube Music", true)) {
                add("album_name=" + encode(track.album.trim()))
            }
            durationSeconds?.let { add("duration=" + it) }
        }.joinToString("&")
        val exact = requestObject(BASE_URL + "/api/get?" + exactQuery)
        if (exact != null && hasLyrics(exact)) return parseLyrics(track.id, exact)

        val query = listOf(title, artist.takeUnless { it.equals("Unknown artist", true) })
            .filterNotNull().filter(String::isNotBlank).joinToString(" ")
        val results = requestArray(BASE_URL + "/api/search?q=" + encode(query)) ?: return null
        val best = chooseBestMatch(results, title, artist, durationSeconds) ?: return null
        return parseLyrics(track.id, best)
    }

    private fun chooseBestMatch(
        results: JSONArray,
        expectedTitle: String,
        expectedArtist: String,
        expectedDuration: Long?
    ): JSONObject? {
        var best: JSONObject? = null
        var bestScore = Int.MIN_VALUE
        for (index in 0 until results.length()) {
            val candidate = results.optJSONObject(index) ?: continue
            if (!hasLyrics(candidate)) continue
            val title = cleanTitle(candidate.optString("trackName", ""))
            val artist = cleanArtist(candidate.optString("artistName", ""))
            var score = similarityScore(expectedTitle, title) * 3
            score += similarityScore(expectedArtist, artist) * 2
            val duration = candidate.optDouble("duration", -1.0)
            if (expectedDuration != null && duration > 0) {
                val diff = abs(duration - expectedDuration.toDouble())
                score += when {
                    diff <= 2.0 -> 40
                    diff <= 5.0 -> 25
                    diff <= 10.0 -> 10
                    diff <= 20.0 -> -10
                    else -> -35
                }
            }
            if (candidate.optString("syncedLyrics").isNotBlank()) score += 15
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best?.takeIf { bestScore >= 70 }
    }

    private fun similarityScore(first: String, second: String): Int {
        val left = normalize(first)
        val right = normalize(second)
        if (left.isBlank() || right.isBlank()) return 0
        if (left == right) return 100
        if (left.contains(right) || right.contains(left)) return 75
        val a = left.split(" ").filter(String::isNotBlank).toSet()
        val b = right.split(" ").filter(String::isNotBlank).toSet()
        if (a.isEmpty() || b.isEmpty()) return 0
        return ((a intersect b).size * 100.0 / (a union b).size).toInt()
    }

    private fun hasLyrics(obj: JSONObject): Boolean =
        obj.optString("syncedLyrics").isNotBlank() ||
            obj.optString("plainLyrics").isNotBlank() ||
            obj.optBoolean("instrumental", false)

    private fun parseLyrics(videoId: String, obj: JSONObject): TrackLyrics {
        val synced = obj.optString("syncedLyrics", "").takeIf { it.isNotBlank() && it != "null" }
        val plain = obj.optString("plainLyrics", "").takeIf { it.isNotBlank() && it != "null" }
        return TrackLyrics(
            videoId = videoId,
            syncedLines = LrcParser.parse(synced),
            plainLyrics = plain,
            rawSyncedLrc = synced,
            instrumental = obj.optBoolean("instrumental", false),
            fromCache = false
        )
    }

    private fun requestObject(url: String): JSONObject? {
        val response = HttpTransport.get(url, headers = headers(), maxBytes = 1 * 1024 * 1024)
        if (response.statusCode == 404) return null
        response.requireSuccess("LRCLIB")
        return runCatching { JSONObject(response.body) }.getOrNull()
    }

    private fun requestArray(url: String): JSONArray? {
        val response = HttpTransport.get(url, headers = headers(), maxBytes = 2 * 1024 * 1024)
        if (response.statusCode == 404) return null
        response.requireSuccess("LRCLIB search")
        return runCatching { JSONArray(response.body) }.getOrNull()
    }

    private fun headers() = mapOf(
        "User-Agent" to CLIENT_UA,
        "Accept" to "application/json"
    )

    private fun cleanTitle(raw: String): String {
        var value = raw.trim()
        value = decorationRegex.replace(value, "")
        value = featuredRegex.replace(value, "")
        if (" | " in value) value = value.substringBefore(" | ").trim()
        if (" - " in value) {
            val parts = value.split(" - ", limit = 2)
            if (parts.size == 2 && parts[1].length >= 3) value = parts[1].trim()
        }
        return value.replace(Regex("\\s+"), " ").trim()
    }

    private fun cleanArtist(raw: String): String =
        raw.replace(" - Topic", "", ignoreCase = true)
            .split(",", "&", "•")
            .firstOrNull().orEmpty().trim()

    private fun normalize(raw: String): String =
        Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
