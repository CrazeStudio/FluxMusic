package com.crazestudio.fluxmusic.data.repository

import com.crazestudio.fluxmusic.data.local.db.HistoryDao
import com.crazestudio.fluxmusic.data.local.db.LyricsCacheEntity
import com.crazestudio.fluxmusic.data.local.db.LyricsDao
import com.crazestudio.fluxmusic.data.local.db.PlaylistDao
import com.crazestudio.fluxmusic.data.local.db.PlaylistEntity
import com.crazestudio.fluxmusic.data.local.db.PlaylistSummary
import com.crazestudio.fluxmusic.data.local.db.RecentPlayEntity
import com.crazestudio.fluxmusic.data.local.db.SearchHistoryEntity
import com.crazestudio.fluxmusic.data.local.db.TrackDao
import com.crazestudio.fluxmusic.data.local.db.TrackEntity
import com.crazestudio.fluxmusic.data.local.prefs.UserPreferences
import com.crazestudio.fluxmusic.data.local.prefs.UserPreferencesRepository
import com.crazestudio.fluxmusic.data.model.AudioQuality
import com.crazestudio.fluxmusic.data.model.EqualizerPreset
import com.crazestudio.fluxmusic.data.model.LrcParser
import com.crazestudio.fluxmusic.data.model.ThemeMode
import com.crazestudio.fluxmusic.data.model.Track
import com.crazestudio.fluxmusic.data.model.TrackLyrics
import com.crazestudio.fluxmusic.data.remote.AudioStreamResolver
import com.crazestudio.fluxmusic.data.remote.LrcLibClient
import com.crazestudio.fluxmusic.data.remote.YtMusicCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Repository boundary for the FluxMusic data backend.
 *
 * The repository owns local persistence, offline policy, cache freshness and remote
 * orchestration. Screens/ViewModels never call a network client or Room DAO directly.
 */
class MusicRepository(
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val historyDao: HistoryDao,
    private val lyricsDao: LyricsDao,
    private val prefsRepo: UserPreferencesRepository
) {
    companion object {
        private const val LYRICS_CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1000L
    }

    val preferences: Flow<UserPreferences> = prefsRepo.preferencesFlow

    val cachedTracks: Flow<List<Track>> = trackDao.observeCachedTracks()
        .map { rows -> rows.map(TrackEntity::toDomain) }

    val favoriteTracks: Flow<List<Track>> = trackDao.observeFavoriteTracks()
        .map { rows -> rows.map(TrackEntity::toDomain) }

    val recentTracks: Flow<List<Track>> = historyDao.observeRecentTracks()
        .map { rows -> rows.map(TrackEntity::toDomain) }

    val playlistSummaries: Flow<List<PlaylistSummary>> = playlistDao.observePlaylistSummaries()

    val searchHistory: Flow<List<String>> = historyDao.observeSearchHistory()
        .map { rows -> rows.map { it.query } }

    val cachedTrackCount: Flow<Int> = trackDao.observeCachedCount()

    fun observePlaylist(playlistId: Long): Flow<PlaylistEntity?> =
        playlistDao.observePlaylist(playlistId)

    fun observePlaylistTracks(playlistId: Long): Flow<List<Track>> =
        playlistDao.observePlaylistTracks(playlistId).map { rows -> rows.map(TrackEntity::toDomain) }

    suspend fun searchLocalCache(query: String): List<Track> = withContext(Dispatchers.IO) {
        val cleaned = query.trim()
        val rows = if (cleaned.isEmpty()) {
            trackDao.observeCachedTracks().first()
        } else {
            trackDao.searchCachedTracks(cleaned).first()
        }
        rows.map(TrackEntity::toDomain)
    }

    suspend fun discoverByLanguage(language: String): Result<List<Track>> =
        withContext(Dispatchers.IO) {
            val query = when (language) {
                "Hindi" -> "popular Hindi songs"
                "Bengali" -> "popular Bengali songs"
                "English" -> "popular English hits"
                "Tamil" -> "popular Tamil songs"
                "Punjabi" -> "popular Punjabi songs"
                else -> "popular Hindi and indie songs"
            }
            searchOnlineAndCache(query, languageHint = language.takeUnless { it == "All" }.orEmpty())
        }

    suspend fun searchOnlineAndCache(
        query: String,
        languageHint: String = ""
    ): Result<List<Track>> = withContext(Dispatchers.IO) {
        val cleaned = query.trim()
        if (cleaned.length < 2) return@withContext Result.success(emptyList())

        val prefs = prefsRepo.preferencesFlow.first()
        if (prefs.offlineCacheMode) {
            return@withContext Result.success(searchLocalCache(cleaned))
        }

        runCatching {
            val remote = YtMusicCatalog.search(cleaned, languageHint)
            if (remote.isEmpty()) {
                return@runCatching searchLocalCache(cleaned)
            }

            cacheTracksPreservingFavorites(remote)
            AudioStreamResolver.prefetchAudioUrls(
                remote.take(2).map { it.id },
                prefs.audioQuality
            )

            val saved = trackDao.getTracksByIds(remote.map { it.id }).associateBy { it.id }
            remote.map { track ->
                val local = saved[track.id]
                if (local == null) track else track.copy(
                    isFavorite = local.isFavorite,
                    languageHint = track.languageHint.ifBlank { local.languageHint }
                )
            }
        }
    }

    suspend fun getLyrics(
        track: Track,
        forceRefresh: Boolean = false
    ): Result<TrackLyrics?> = withContext(Dispatchers.IO) {
        val cached = lyricsDao.getLyrics(track.id)
        val cachedResult = cached?.let(::toCachedLyrics)
        val cacheFresh = cached != null &&
            System.currentTimeMillis() - cached.fetchedAt < LYRICS_CACHE_TTL_MS

        if (!forceRefresh && cacheFresh) {
            return@withContext Result.success(cachedResult)
        }

        val prefs = prefsRepo.preferencesFlow.first()
        if (prefs.offlineCacheMode) {
            return@withContext Result.success(cachedResult)
        }

        runCatching {
            val fetched = LrcLibClient.fetchLyrics(track)
            if (fetched != null) {
                lyricsDao.upsertLyrics(
                    LyricsCacheEntity(
                        videoId = track.id,
                        trackName = track.safeTitle,
                        artistName = track.safeArtist,
                        syncedLyrics = fetched.rawSyncedLrc,
                        plainLyrics = fetched.plainLyrics,
                        instrumental = fetched.instrumental,
                        fetchedAt = System.currentTimeMillis()
                    )
                )
            }
            fetched
        }.recoverCatching { error ->
            // A stale local lyric is better than a blank player when LRCLIB is offline.
            if (cachedResult != null) cachedResult else throw error
        }
    }

    private fun toCachedLyrics(row: LyricsCacheEntity): TrackLyrics = TrackLyrics(
        videoId = row.videoId,
        syncedLines = LrcParser.parse(row.syncedLyrics),
        plainLyrics = row.plainLyrics,
        rawSyncedLrc = row.syncedLyrics,
        instrumental = row.instrumental,
        fromCache = true
    )

    suspend fun cacheTrack(track: Track) = withContext(Dispatchers.IO) {
        cacheTracksPreservingFavorites(listOf(track))
    }

    suspend fun cacheTracks(tracks: List<Track>) = withContext(Dispatchers.IO) {
        cacheTracksPreservingFavorites(tracks)
    }

    private suspend fun cacheTracksPreservingFavorites(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val ids = tracks.map { it.id }.distinct()
        val existing = trackDao.getTracksByIds(ids).associateBy { it.id }
        val entities = tracks.distinctBy { it.id }.map { track ->
            val old = existing[track.id]
            TrackEntity.fromDomain(
                track = track.copy(
                    languageHint = track.languageHint.ifBlank { old?.languageHint.orEmpty() }
                ),
                existingFavorite = old?.isFavorite ?: track.isFavorite,
                favoritedAt = old?.favoritedAt ?: 0L
            )
        }
        trackDao.upsertTracks(entities)
    }

    suspend fun toggleFavorite(track: Track): Boolean = withContext(Dispatchers.IO) {
        val existing = trackDao.getTrackById(track.id)
        val nextFavorite = !(existing?.isFavorite ?: track.isFavorite)
        val timestamp = if (nextFavorite) System.currentTimeMillis() else 0L
        if (existing == null) {
            trackDao.upsertTrack(
                TrackEntity.fromDomain(track, existingFavorite = true, favoritedAt = timestamp)
            )
        } else {
            trackDao.setFavorite(track.id, nextFavorite, timestamp)
        }
        nextFavorite
    }

    suspend fun recordRecentPlay(track: Track) = withContext(Dispatchers.IO) {
        cacheTracksPreservingFavorites(listOf(track))
        historyDao.upsertRecentPlay(
            RecentPlayEntity(trackId = track.id, playedAt = System.currentTimeMillis())
        )
    }

    suspend fun addSearchHistory(query: String) = withContext(Dispatchers.IO) {
        val cleaned = query.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isNotEmpty()) {
            historyDao.upsertSearchQuery(
                SearchHistoryEntity(query = cleaned, searchedAt = System.currentTimeMillis())
            )
        }
    }

    suspend fun deleteSearchQuery(query: String) = withContext(Dispatchers.IO) {
        historyDao.deleteSearchQuery(query.trim())
    }

    suspend fun clearSearchHistory() = withContext(Dispatchers.IO) {
        historyDao.clearSearchHistory()
    }

    suspend fun clearRecentHistory() = withContext(Dispatchers.IO) {
        historyDao.clearRecentPlays()
    }

    suspend fun clearCachedMetadata() = withContext(Dispatchers.IO) {
        trackDao.clearUncachedOrphans()
        lyricsDao.clearAll()
        YtMusicCatalog.clearCache()
        AudioStreamResolver.clearStreamCache()
    }

    suspend fun createPlaylist(name: String, initialTrack: Track? = null): Long =
        withContext(Dispatchers.IO) {
            val cleaned = name.trim()
            require(cleaned.isNotEmpty()) { "Playlist name cannot be empty" }
            val playlistId = playlistDao.insertPlaylist(PlaylistEntity(name = cleaned))
            if (initialTrack != null) {
                cacheTracksPreservingFavorites(listOf(initialTrack))
                playlistDao.addTrackToPlaylist(playlistId, initialTrack.id)
            }
            playlistId
        }

    suspend fun renamePlaylist(playlistId: Long, newName: String) = withContext(Dispatchers.IO) {
        val cleaned = newName.trim()
        require(cleaned.isNotEmpty()) { "Playlist name cannot be empty" }
        playlistDao.renamePlaylist(playlistId, cleaned)
    }

    suspend fun deletePlaylist(playlistId: Long) = withContext(Dispatchers.IO) {
        playlistDao.deletePlaylist(playlistId)
    }

    suspend fun addTrackToPlaylist(playlistId: Long, track: Track) = withContext(Dispatchers.IO) {
        cacheTracksPreservingFavorites(listOf(track))
        playlistDao.addTrackToPlaylist(playlistId, track.id)
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: String) =
        withContext(Dispatchers.IO) {
            playlistDao.removeTrackFromPlaylist(playlistId, trackId)
        }

    suspend fun setThemeMode(mode: ThemeMode) = prefsRepo.setThemeMode(mode)
    suspend fun setOfflineCacheMode(enabled: Boolean) = prefsRepo.setOfflineCacheMode(enabled)
    suspend fun setAudioQuality(quality: AudioQuality) = prefsRepo.setAudioQuality(quality)
    suspend fun setPlaybackSpeed(speed: Float) = prefsRepo.setPlaybackSpeed(speed)

    suspend fun setEqualizer(
        enabled: Boolean,
        preset: EqualizerPreset,
        bands: List<Int>,
        bassBoost: Int
    ) = prefsRepo.setEqualizer(enabled, preset, bands, bassBoost)
}
