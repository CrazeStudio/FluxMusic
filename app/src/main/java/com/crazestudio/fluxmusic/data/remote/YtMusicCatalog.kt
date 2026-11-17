package com.crazestudio.fluxmusic.data.remote

import com.crazestudio.fluxmusic.data.model.AudioQuality
import com.crazestudio.fluxmusic.data.model.Track
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

object YtMusicCatalog {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; TECNO KI5k) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    private const val SEARCH_ENDPOINT =
        "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
    private const val CLIENT_VERSION = "1.20261116.01.00"
    private const val SEARCH_CACHE_TTL_MS = 10 * 60 * 1000L
    private const val STREAM_CACHE_TTL_MS = 4 * 60 * 60 * 1000L

    @Volatile
    private var extractorReady = false

    private data class SearchCacheEntry(
        val time: Long = System.currentTimeMillis(),
        val tracks: List<Track>
    )

    private data class StreamCacheEntry(
        val time: Long = System.currentTimeMillis(),
        val url: String
    )

    private val searchCache = LinkedHashMap<String, SearchCacheEntry>(32, 0.75f, true)
    private val streamCache = LinkedHashMap<String, StreamCacheEntry>(64, 0.75f, true)

    fun search(query: String, languageHint: String = ""): List<Track> {
        val normalized = query.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return emptyList()

        synchronized(searchCache) {
            val cached = searchCache[normalized]
            if (cached != null && System.currentTimeMillis() - cached.time < SEARCH_CACHE_TTL_MS) {
                return cached.tracks.toList()
            }
            searchCache.remove(normalized)
        }

        val client = JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", CLIENT_VERSION)
            .put("hl", "en")
            .put("gl", "IN")
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("query", query)

        val conn = (URL(SEARCH_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Origin", "https://music.youtube.com")
            setRequestProperty("Referer", "https://music.youtube.com/")
            setRequestProperty("Accept", "application/json")
            doOutput = true
        }

        return try {
            conn.outputStream.use { out ->
                out.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val json = readStream(stream)
            if (status !in 200..299) {
                throw IOException("YouTube Music search HTTP $status")
            }
            val parsed = LinkedHashMap<String, Track>()
            collect(JSONObject(json), parsed, languageHint)
            val result = parsed.values.toList()
            synchronized(searchCache) {
                searchCache[normalized] = SearchCacheEntry(tracks = result)
                while (searchCache.size > 80) {
                    val oldestKey = searchCache.keys.firstOrNull() ?: break
                    searchCache.remove(oldestKey)
                }
            }
            result
        } finally {
            conn.disconnect()
        }
    }

    @Synchronized
    fun initializeExtractor() {
        if (extractorReady) return
        NewPipe.init(FluxDownloader())
        extractorReady = true
    }

    fun resolveAudioUrl(videoId: String, quality: AudioQuality = AudioQuality.AUTO): String {
        val cacheKey = "$videoId:${quality.name}"
        synchronized(streamCache) {
            val cached = streamCache[cacheKey]
            if (cached != null && System.currentTimeMillis() - cached.time < STREAM_CACHE_TTL_MS) {
                return cached.url
            }
            streamCache.remove(cacheKey)
        }

        initializeExtractor()
        val service = NewPipe.getService("YouTube")
        val extractor = service.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
        extractor.fetchPage()
        val streams: List<AudioStream> = extractor.audioStreams
        if (streams.isEmpty()) {
            throw IOException("No audio stream available for this track.")
        }

        val chosen = selectStream(streams, quality)
        val resolvedUrl = chosen.content
        if (resolvedUrl.isBlank()) {
            throw IOException("Audio stream URL was empty.")
        }

        synchronized(streamCache) {
            streamCache[cacheKey] = StreamCacheEntry(url = resolvedUrl)
            while (streamCache.size > 100) {
                val oldestKey = streamCache.keys.firstOrNull() ?: break
                streamCache.remove(oldestKey)
            }
        }
        return resolvedUrl
    }

    fun clearStreamCache() {
        synchronized(streamCache) { streamCache.clear() }
        synchronized(searchCache) { searchCache.clear() }
    }

    private fun selectStream(streams: List<AudioStream>, quality: AudioQuality): AudioStream {
        val sortedDesc = streams.sortedByDescending { it.averageBitrate }
        return when (quality) {
            AudioQuality.HIGH -> sortedDesc.first()
            AudioQuality.DATA_SAVER -> {
                sortedDesc.lastOrNull { it.averageBitrate >= 48 } ?: sortedDesc.last()
            }
            AudioQuality.AUTO -> {
                sortedDesc.firstOrNull { it.averageBitrate in 96..192 } ?: sortedDesc.first()
            }
        }
    }

    private fun collect(node: Any?, out: MutableMap<String, Track>, languageHint: String) {
        when (node) {
            is JSONObject -> {
                val renderer = node.optJSONObject("musicResponsiveListItemRenderer")
                if (renderer != null) {
                    parseTrack(renderer, languageHint)?.let { track ->
                        out[track.id] = track
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    collect(node.opt(keys.next()), out, languageHint)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collect(node.opt(i), out, languageHint)
                }
            }
        }
    }

    private fun parseTrack(r: JSONObject, languageHint: String): Track? {
        val id = deepString(r, "videoId")
        if (id.isBlank()) return null
        val flex = r.optJSONArray("flexColumns")
        val title = if (flex != null && flex.length() > 0) extractText(flex.optJSONObject(0)) else ""
        val rawArtist = if (flex != null && flex.length() > 1) extractText(flex.optJSONObject(1)) else ""
        val rawAlbum = if (flex != null && flex.length() > 2) extractText(flex.optJSONObject(2)) else ""
        val fixed = r.optJSONArray("fixedColumns")
        val rawDuration = if (fixed != null && fixed.length() > 0) extractText(fixed.optJSONObject(0)) else ""
        val thumb = upgradeThumbnailUrl(extractThumbnail(r))

        val artistParts = rawArtist.split("•").map { it.trim() }.filter { it.isNotEmpty() }
        val cleanArtist = artistParts.firstOrNull()?.takeIf {
            !it.equals("Song", ignoreCase = true) && !it.equals("Video", ignoreCase = true)
        } ?: artistParts.getOrNull(1) ?: rawArtist
        val cleanAlbum = rawAlbum.ifBlank {
            artistParts.lastOrNull()?.takeIf { it != cleanArtist && !it.contains(":") } ?: "YouTube Music"
        }

        return Track(
            id = id,
            title = title,
            artist = cleanArtist,
            album = cleanAlbum,
            durationMs = durationMillis(rawDuration),
            thumbnailUrl = thumb,
            languageHint = languageHint
        )
    }

    private fun upgradeThumbnailUrl(url: String): String {
        if (url.isBlank()) return ""
        return url
            .replace(Regex("=w\\d+-h\\d+[^&\\s]*"), "=w544-h544-l90-rj")
            .replace(Regex("=s\\d+[^&\\s]*"), "=s544")
    }

    private fun deepString(node: Any?, key: String): String {
        when (node) {
            is JSONObject -> {
                val direct = node.opt(key)
                if (direct is String && direct.isNotBlank()) return direct
                val keys = node.keys()
                while (keys.hasNext()) {
                    val found = deepString(node.opt(keys.next()), key)
                    if (found.isNotEmpty()) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val found = deepString(node.opt(i), key)
                    if (found.isNotEmpty()) return found
                }
            }
        }
        return ""
    }

    private fun extractText(node: Any?): String {
        when (node) {
            is JSONObject -> {
                val direct = node.opt("text")
                if (direct is String && direct.isNotBlank()) return direct
                val runs = node.optJSONArray("runs")
                if (runs != null) {
                    val sb = StringBuilder()
                    for (i in 0 until runs.length()) {
                        runs.optJSONObject(i)?.optString("text", "")?.let { sb.append(it) }
                    }
                    if (sb.isNotEmpty()) return sb.toString()
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val v = extractText(node.opt(keys.next()))
                    if (v.isNotEmpty()) return v
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val v = extractText(node.opt(i))
                    if (v.isNotEmpty()) return v
                }
            }
        }
        return ""
    }

    private fun extractThumbnail(node: Any?): String {
        when (node) {
            is JSONObject -> {
                val thumbObj = node.optJSONObject("thumbnail")
                if (thumbObj != null) {
                    val arr = thumbObj.optJSONArray("thumbnails")
                    if (arr != null && arr.length() > 0) {
                        val last = arr.optJSONObject(arr.length() - 1)
                        val url = last?.optString("url", "").orEmpty()
                        if (url.isNotEmpty()) return url
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val v = extractThumbnail(node.opt(keys.next()))
                    if (v.isNotEmpty()) return v
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val v = extractThumbnail(node.opt(i))
                    if (v.isNotEmpty()) return v
                }
            }
        }
        return ""
    }

    private fun durationMillis(value: String): Long {
        return try {
            var seconds = 0L
            for (part in value.trim().split(":")) {
                seconds = seconds * 60L + part.toLong()
            }
            seconds * 1000L
        } catch (_: Exception) {
            0L
        }
    }

    private fun readStream(input: InputStream?): String {
        if (input == null) return ""
        return input.use { src ->
            ByteArrayOutputStream().use { out ->
                val buf = ByteArray(8192)
                var read: Int
                while (src.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                }
                out.toString("UTF-8")
            }
        }
    }

    private class FluxDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val conn = (URL(request.url()).openConnection() as HttpURLConnection).apply {
                requestMethod = request.httpMethod()
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                request.headers()?.forEach { (key, values) ->
                    values?.forEach { v -> addRequestProperty(key, v) }
                }
            }
            return try {
                request.dataToSend()?.let { data ->
                    conn.doOutput = true
                    conn.outputStream.use { it.write(data) }
                }
                val status = conn.responseCode
                val input = if (status in 200..399) conn.inputStream else conn.errorStream
                val body = readStream(input)
                val responseHeaders = HashMap<String, MutableList<String>>()
                conn.headerFields.forEach { (k, v) ->
                    if (k != null && v != null) responseHeaders[k] = v.toMutableList()
                }
                Response(status, conn.responseMessage, responseHeaders, body, conn.url.toString())
            } finally {
                conn.disconnect()
            }
        }
    }
}
