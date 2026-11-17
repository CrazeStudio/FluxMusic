package com.crazestudio.fluxmusic.data.remote

import com.crazestudio.fluxmusic.data.model.LrcParser
import com.crazestudio.fluxmusic.data.model.Track
import com.crazestudio.fluxmusic.data.model.TrackLyrics
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.abs

object LrcLibClient {
    private const val BASE_URL = "https://lrclib.net"
    private const val CLIENT_UA = "FluxMusic/3.0 (https://github.com/CrazeStudio/FluxMusic)"

    private val DECORATION_REGEX = Regex(
        "\\s*(\\((official|lyric|lyrics|audio|video|hd|4k|visualizer|remastered|feat\\.?|ft\\.?)[^)]*\\)|\\[(official|lyric|lyrics|audio|video|hd|4k)[^]]*])",
        RegexOption.IGNORE_CASE
    )
    private val FEAT_SPLIT_REGEX = Regex("\\s+(?:feat\\.?|ft\\.?)\\s+.*$", RegexOption.IGNORE_CASE)

    fun fetchLyrics(track: Track): TrackLyrics? {
        val cleanTitle = cleanTrackTitle(track.safeTitle)
        val cleanArtist = cleanArtistName(track.safeArtist)
        val durationSec = (track.durationMs / 1000L).takeIf { it in 10..3600 }

        if (cleanTitle.isBlank()) return null

        val exactUrl = buildString {
            append("$BASE_URL/api/get?track_name=")
            append(encode(cleanTitle))
            if (cleanArtist.isNotBlank() && !cleanArtist.equals("Unknown artist", ignoreCase = true)) {
                append("&artist_name=").append(encode(cleanArtist))
            }
            if (track.album.isNotBlank() && !track.album.equals("YouTube Music", ignoreCase = true)) {
                append("&album_name=").append(encode(track.album.trim()))
            }
            if (durationSec != null) {
                append("&duration=").append(durationSec)
            }
        }

        val exactResult = requestJsonObject(exactUrl)
        if (exactResult != null) {
            return parseLyricsObject(track.id, exactResult)
        }

        val searchQuery = buildString {
            append(cleanTitle)
            if (cleanArtist.isNotBlank() && !cleanArtist.equals("Unknown artist", ignoreCase = true)) {
                append(' ').append(cleanArtist)
            }
        }
        val searchUrl = "$BASE_URL/api/search?q=${encode(searchQuery)}"
        val candidates = requestJsonArray(searchUrl) ?: return null
        val bestObj = pickBestMatch(candidates, durationSec) ?: return null
        return parseLyricsObject(track.id, bestObj)
    }

    private fun pickBestMatch(array: JSONArray, targetDurationSec: Long?): JSONObject? {
        var best: JSONObject? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val synced = obj.optString("syncedLyrics", "").takeIf { it != "null" }.orEmpty()
            val plain = obj.optString("plainLyrics", "").takeIf { it != "null" }.orEmpty()
            val instrumental = obj.optBoolean("instrumental", false)
            if (synced.isBlank() && plain.isBlank() && !instrumental) continue

            var score = 0
            if (synced.isNotBlank()) score += 50
            if (plain.isNotBlank()) score += 20
            val dur = obj.optDouble("duration", -1.0)
            if (targetDurationSec != null && dur > 0) {
                val diff = abs(dur - targetDurationSec.toDouble())
                score += when {
                    diff <= 3.0 -> 40
                    diff <= 8.0 -> 20
                    diff <= 20.0 -> 5
                    else -> -25
                }
            }
            if (score > bestScore) {
                bestScore = score
                best = obj
            }
        }
        return best
    }

    private fun parseLyricsObject(videoId: String, obj: JSONObject): TrackLyrics {
        val syncedRaw = obj.optString("syncedLyrics", "").takeIf { it.isNotBlank() && it != "null" }
        val plainRaw = obj.optString("plainLyrics", "").takeIf { it.isNotBlank() && it != "null" }
        val instrumental = obj.optBoolean("instrumental", false)
        return TrackLyrics(
            videoId = videoId,
            syncedLines = LrcParser.parse(syncedRaw),
            plainLyrics = plainRaw,
            rawSyncedLrc = syncedRaw,
            instrumental = instrumental,
            fromCache = false
        )
    }

    private fun cleanTrackTitle(raw: String): String {
        var cleaned = raw.trim()
        cleaned = DECORATION_REGEX.replace(cleaned, "")
        cleaned = FEAT_SPLIT_REGEX.replace(cleaned, "")
        if (cleaned.contains(" | ")) {
            cleaned = cleaned.substringBefore(" | ").trim()
        }
        if (cleaned.contains(" - ")) {
            val parts = cleaned.split(" - ", limit = 2)
            if (parts.size == 2 && parts[1].length >= 3) {
                cleaned = parts[1].trim()
            }
        }
        return cleaned.replace(Regex("\\s+"), " ").trim()
    }

    private fun cleanArtistName(raw: String): String {
        return raw
            .replace(" - Topic", "", ignoreCase = true)
            .split(",", "&", "•")
            .firstOrNull()
            .orEmpty()
            .trim()
    }

    private fun requestJsonObject(urlString: String): JSONObject? {
        val body = executeGet(urlString) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun requestJsonArray(urlString: String): JSONArray? {
        val body = executeGet(urlString) ?: return null
        return runCatching { JSONArray(body) }.getOrNull()
    }

    private fun executeGet(urlString: String): String? {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7000
            readTimeout = 10000
            setRequestProperty("User-Agent", CLIENT_UA)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            when (code) {
                in 200..299 -> readAll(conn.inputStream)
                404 -> null
                429 -> throw IOException("LRCLIB rate limit reached. Try again in a moment.")
                else -> throw IOException("LRCLIB HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun readAll(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.use { input ->
            ByteArrayOutputStream().use { out ->
                val buf = ByteArray(4096)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                }
                out.toString("UTF-8")
            }
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
