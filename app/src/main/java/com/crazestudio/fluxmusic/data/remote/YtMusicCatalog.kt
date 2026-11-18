package com.crazestudio.fluxmusic.data.remote

import com.crazestudio.fluxmusic.data.model.Track
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * YouTube Music catalogue adapter.
 *
 * Search metadata is deliberately kept separate from audio extraction: catalogue
 * results are short-lived, contain no playback URLs, and can be safely cached by query.
 */
object YtMusicCatalog {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    private const val HOME_URL = "https://music.youtube.com/"
    private const val SEARCH_ENDPOINT = "https://music.youtube.com/youtubei/v1/search"
    private const val FALLBACK_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11Gjw"
    private const val FALLBACK_CLIENT_VERSION = "1.20261116.01.00"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private const val MAX_CACHE_ENTRIES = 64

    private data class Bootstrap(
        val apiKey: String,
        val clientVersion: String,
        val visitorData: String,
        val fetchedAt: Long
    )

    private data class CacheEntry(val storedAt: Long, val tracks: List<Track>)

    private val cache = LinkedHashMap<String, CacheEntry>(32, 0.75f, true)
    @Volatile private var bootstrap: Bootstrap? = null

    fun search(query: String, languageHint: String = ""): List<Track> {
        val cleaned = query.trim().replace(Regex("\\s+"), " ")
        if (cleaned.length < 2) return emptyList()
        val key = cleaned.lowercase(Locale.ROOT) + "|" + languageHint.lowercase(Locale.ROOT)
        synchronized(cache) {
            val entry = cache[key]
            if (entry != null && System.currentTimeMillis() - entry.storedAt < CACHE_TTL_MS) {
                return entry.tracks.toList()
            }
            cache.remove(key)
        }

        val session = getBootstrap()
        val client = JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", session.clientVersion)
            .put("hl", "en")
            .put("gl", "IN")
            .put("utcOffsetMinutes", 330)
        if (session.visitorData.isNotBlank()) client.put("visitorData", session.visitorData)
        val request = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("query", cleaned)

        val headers = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Origin" to "https://music.youtube.com",
            "Referer" to HOME_URL,
            "X-YouTube-Client-Name" to "67",
            "X-YouTube-Client-Version" to session.clientVersion
        )
        if (session.visitorData.isNotBlank()) headers["X-Goog-Visitor-Id"] = session.visitorData

        val endpoint = SEARCH_ENDPOINT + "?key=" + session.apiKey + "&prettyPrint=false"
        val response = HttpTransport.postJson(
            endpoint,
            request.toString(),
            headers = headers,
            connectTimeoutMs = 6_000,
            readTimeoutMs = 9_000,
            maxBytes = 4 * 1024 * 1024
        ).requireSuccess("YouTube Music search")

        val found = LinkedHashMap<String, Track>()
        collect(JSONObject(response.body), found, languageHint)
        val tracks = found.values.take(80).toList()
        if (tracks.isNotEmpty()) {
            synchronized(cache) {
                cache[key] = CacheEntry(System.currentTimeMillis(), tracks)
                while (cache.size > MAX_CACHE_ENTRIES) {
                    cache.remove(cache.keys.firstOrNull() ?: break)
                }
            }
        }
        return tracks
    }

    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    private fun getBootstrap(): Bootstrap {
        val now = System.currentTimeMillis()
        bootstrap?.let { if (now - it.fetchedAt < 6 * 60 * 60 * 1000L) return it }

        val discovered = runCatching {
            val response = HttpTransport.get(
                HOME_URL,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml",
                    "Accept-Language" to "en-IN,en;q=0.9"
                ),
                connectTimeoutMs = 4_000,
                readTimeoutMs = 6_000,
                maxBytes = 3 * 1024 * 1024
            ).requireSuccess("YouTube Music bootstrap")
            val html = response.body
            val apiKey = extract(html, Regex("""["']?INNERTUBE_API_KEY["']?\s*:\s*["']([^"']+)["']"""))
                ?: FALLBACK_API_KEY
            val version = extract(html, Regex(""""clientVersion"\s*:\s*"([^"]+)""""))
                ?: FALLBACK_CLIENT_VERSION
            val visitor = extract(html, Regex(""""visitorData"\s*:\s*"([^"]+)"""")).orEmpty()
            Bootstrap(apiKey, version, visitor, now)
        }.getOrElse {
            Bootstrap(FALLBACK_API_KEY, FALLBACK_CLIENT_VERSION, "", now)
        }

        bootstrap = discovered
        return discovered
    }

    private fun collect(node: Any?, out: MutableMap<String, Track>, languageHint: String) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicResponsiveListItemRenderer")?.let { renderer ->
                    parseMusicTrack(renderer, languageHint)?.let { out.putIfAbsent(it.id, it) }
                }
                node.optJSONObject("videoRenderer")?.let { renderer ->
                    parseVideoTrack(renderer, languageHint)?.let { out.putIfAbsent(it.id, it) }
                }
                val keys = node.keys()
                while (keys.hasNext()) collect(node.opt(keys.next()), out, languageHint)
            }
            is JSONArray -> {
                for (index in 0 until node.length()) collect(node.opt(index), out, languageHint)
            }
        }
    }

    private fun parseMusicTrack(renderer: JSONObject, languageHint: String): Track? {
        val id = deepString(renderer, "videoId")
            .takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,20}")) } ?: return null
        val columns = renderer.optJSONArray("flexColumns")
        val title = columns?.optJSONObject(0)?.let(::extractText).orEmpty()
        val rawArtist = columns?.optJSONObject(1)?.let(::extractText).orEmpty()
        val rawAlbum = columns?.optJSONObject(2)?.let(::extractText).orEmpty()
        val fixed = renderer.optJSONArray("fixedColumns")
        val rawDuration = fixed?.optJSONObject(0)?.let(::extractText).orEmpty()

        val artistParts = rawArtist.split("•").map(String::trim).filter(String::isNotEmpty)
        val artist = artistParts.firstOrNull {
            !it.equals("Song", true) && !it.equals("Video", true) &&
                !it.equals("Single", true) && !it.equals("Album", true)
        } ?: "Unknown artist"
        val album = rawAlbum.ifBlank {
            artistParts.lastOrNull()?.takeIf { it != artist && !it.contains(":") } ?: "YouTube Music"
        }

        return Track(
            id = id,
            title = title.ifBlank { "Unknown title" },
            artist = artist,
            album = album,
            durationMs = parseDuration(rawDuration),
            thumbnailUrl = upgradeThumbnailUrl(extractThumbnail(renderer)),
            languageHint = languageHint
        )
    }

    private fun parseVideoTrack(renderer: JSONObject, languageHint: String): Track? {
        val id = renderer.optString("videoId", "")
            .takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,20}")) } ?: return null
        val title = extractText(renderer.opt("title"))
        val owner = extractText(renderer.opt("ownerText"))
        val duration = extractText(renderer.opt("lengthText"))
        return Track(
            id = id,
            title = title.ifBlank { "Unknown title" },
            artist = owner.ifBlank { "Unknown artist" },
            album = "YouTube",
            durationMs = parseDuration(duration),
            thumbnailUrl = upgradeThumbnailUrl(extractThumbnail(renderer)),
            languageHint = languageHint
        )
    }

    private fun extract(text: String, regex: Regex): String? =
        regex.find(text)?.groupValues?.getOrNull(1)

    private fun deepString(node: Any?, key: String): String {
        when (node) {
            is JSONObject -> {
                val value = node.opt(key)
                if (value is String && value.isNotBlank()) return value
                val keys = node.keys()
                while (keys.hasNext()) {
                    val found = deepString(node.opt(keys.next()), key)
                    if (found.isNotEmpty()) return found
                }
            }
            is JSONArray -> for (index in 0 until node.length()) {
                val found = deepString(node.opt(index), key)
                if (found.isNotEmpty()) return found
            }
        }
        return ""
    }

    private fun extractText(node: Any?): String {
        when (node) {
            is JSONObject -> {
                val direct = node.opt("text")
                if (direct is String && direct.isNotBlank()) return direct
                val simple = node.optJSONObject("simpleText")?.optString("text", "")
                if (!simple.isNullOrBlank()) return simple
                val runs = node.optJSONArray("runs")
                if (runs != null) {
                    val result = buildString {
                        for (index in 0 until runs.length()) {
                            append(runs.optJSONObject(index)?.optString("text", "").orEmpty())
                        }
                    }
                    if (result.isNotBlank()) return result
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val found = extractText(node.opt(keys.next()))
                    if (found.isNotEmpty()) return found
                }
            }
            is JSONArray -> for (index in 0 until node.length()) {
                val found = extractText(node.opt(index))
                if (found.isNotEmpty()) return found
            }
        }
        return ""
    }

    private fun extractThumbnail(node: Any?): String {
        when (node) {
            is JSONObject -> {
                val thumbnails = node.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                if (thumbnails != null && thumbnails.length() > 0) {
                    for (index in thumbnails.length() - 1 downTo 0) {
                        val url = thumbnails.optJSONObject(index)?.optString("url", "").orEmpty()
                        if (url.startsWith("https://")) return url
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val found = extractThumbnail(node.opt(keys.next()))
                    if (found.isNotEmpty()) return found
                }
            }
            is JSONArray -> for (index in node.length() - 1 downTo 0) {
                val found = extractThumbnail(node.opt(index))
                if (found.isNotEmpty()) return found
            }
        }
        return ""
    }

    private fun upgradeThumbnailUrl(url: String): String {
        if (url.isBlank()) return ""
        return url
            .replace(Regex("=w\\d+-h\\d+[^&\\s]*"), "=w544-h544-l90-rj")
            .replace(Regex("=s\\d+[^&\\s]*"), "=s544")
    }

    private fun parseDuration(value: String): Long {
        val parts = value.trim().split(":")
        if (value.isBlank() || parts.isEmpty() || parts.size > 3) return 0L
        var seconds = 0L
        parts.forEachIndexed { index, part ->
            val component = part.toLongOrNull() ?: return 0L
            if (component < 0L || (index > 0 && component >= 60L)) return 0L
            seconds = seconds * 60L + component
        }
        return seconds * 1000L
    }
}
