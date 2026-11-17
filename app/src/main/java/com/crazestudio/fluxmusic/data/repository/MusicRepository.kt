package com.crazestudio.fluxmusic.data.repository

import com.crazestudio.fluxmusic.data.local.db.HistoryDao
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
import com.crazestudio.fluxmusic.data.model.ThemeMode
import com.crazestudio.fluxmusic.data.model.Track
import com.crazestudio.fluxmusic.data.remote.YtMusicCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class MusicRepository(
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val historyDao: HistoryDao,
    private val prefsRepo: UserPreferencesRepository
) {
    val preferences: Flow<UserPreferences> = prefsRepo.preferencesFlow

    val cachedTracks: Flow<List<Track>> = trackDao.observeCachedTracks()
        .map { list -> list.map { it.toDomain() } }

    val favoriteTracks: Flow<List<Track>> = trackDao.observeFavoriteTracks()
        .map { list -> list.map { it.toDomain() } }

    val recentTracks: Flow<List<Track>> = historyDao.observeRecentTracks()
        .map { list -> list.map { it.toDomain() } }

    val playlistSummaries: Flow<List<PlaylistSummary>> = playlistDao.observePlaylistSummaries()

    val searchHistory: Flow<List<String>> = historyDao.observeSearchHistory()
        .map { list -> list.map { it.query } }

    val cachedTrackCount: Flow<Int> = trackDao.observeCachedCount()

    fun observePlaylist(playlistId: Long): Flow<PlaylistEntity?> = playlistDao.observePlaylist(playlistId)

    fun observePlaylistTracks(playlistId: Long): Flow<List<Track>> =
        playlistDao.observePlaylistTracks(playlistId).map { list -> list.map { it.toDomain() } }

    suspend fun searchLocalCache(query: String): List<Track> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            trackDao.observeCachedTracks().first().map { it.toDomain() }
        } else {
            trackDao.searchCachedTracks(trimmed).first().map { it.toDomain() }
        }
    }

    suspend fun discoverByLanguage(language: String): Result<List<Track>> = withContext(Dispatchers.IO) {
        val query = when (language) {
            "Hindi" -> "popular Hindi songs"
            "Bengali" -> "popular Bengali songs"
            "English" -> "popular English hits"
            "Tamil" -> "popular Tamil songs"
            "Punjabi" -> "popular Punjabi songs"
            else -> "popular Hindi and indie songs"
        }
        searchOnlineAndCache(query, languageHint = if (language == "All") "" else language)
    }

    suspend fun searchOnlineAndCache(query: String, languageHint: String = ""): Result<List<Track>> =
        withContext(Dispatchers.IO) {
            val prefs = prefsRepo.preferencesFlow.first()
            if (prefs.offlineCacheMode) {
                return@withContext Result.success(searchLocalCache(query))
            }
            runCatching {
                val found = YtMusicCatalog.search(query, languageHint)
                cacheTracksPreservingFavorites(found)
                val existingMap = trackDao.getTracksByIds(found.map { it.id }).associateBy { it.id }
                found.map { track ->
                    existingMap[track.id]?.toDomain() ?: track
                }
            }
        }

    suspend fun cacheTrack(track: Track) = withContext(Dispatchers.IO) {
        cacheTracksPreservingFavorites(listOf(track))
    }

    private suspend fun cacheTracksPreservingFavorites(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val existing = trackDao.getTracksByIds(tracks.map { it.id }).associateBy { it.id }
        val entities = tracks.map { t ->
            val old = existing[t.id]
            TrackEntity.fromDomain(
                track = t.copy(
                    languageHint = t.languageHint.ifBlank { old?.languageHint.orEmpty() }
                ),
                existingFavorite = old?.isFavorite ?: t.isFavorite,
                favoritedAt = old?.favoritedAt ?: 0L
            )
        }
        trackDao.upsertTracks(entities)
    }

    suspend fun toggleFavorite(track: Track): Boolean = withContext(Dispatchers.IO) {
        val existing = trackDao.getTrackById(track.id)
        val nextFav = !(existing?.isFavorite ?: track.isFavorite)
        val now = if (nextFav) System.currentTimeMillis() else 0L
        if (existing == null) {
            trackDao.upsertTrack(TrackEntity.fromDomain(track, existingFavorite = nextFav, favoritedAt = now))
        } else {
            trackDao.setFavorite(track.id, nextFav, now)
        }
        nextFav
    }

    suspend fun recordRecentPlay(track: Track) = withContext(Dispatchers.IO) {
        cacheTracksPreservingFavorites(listOf(track))
        historyDao.upsertRecentPlay(RecentPlayEntity(trackId = track.id, playedAt = System.currentTimeMillis()))
    }

    suspend fun addSearchHistory(query: String) = withContext(Dispatchers.IO) {
        val cleaned = query.trim()
        if (cleaned.isNotEmpty()) {
            historyDao.upsertSearchQuery(SearchHistoryEntity(query = cleaned, searchedAt = System.currentTimeMillis()))
        }
    }

    suspend fun deleteSearchQuery(query: String) = withContext(Dispatchers.IO) {
        historyDao.deleteSearchQuery(query)
    }

    suspend fun clearSearchHistory() = withContext(Dispatchers.IO) {
        historyDao.clearSearchHistory()
    }

    suspend fun clearRecentHistory() = withContext(Dispatchers.IO) {
        historyDao.clearRecentPlays()
    }

    suspend fun clearCachedMetadata() = withContext(Dispatchers.IO) {
        trackDao.clearUncachedOrphans()
        YtMusicCatalog.clearStreamCache()
    }

    suspend fun createPlaylist(name: String, initialTrack: Track? = null): Long = withContext(Dispatchers.IO) {
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
        if (cleaned.isNotEmpty()) {
            playlistDao.renamePlaylist(playlistId, cleaned)
        }
    }

    suspend fun deletePlaylist(playlistId: Long) = withContext(Dispatchers.IO) {
        playlistDao.deletePlaylist(playlistId)
    }

    suspend fun addTrackToPlaylist(playlistId: Long, track: Track) = withContext(Dispatchers.IO) {
        cacheTracksPreservingFavorites(listOf(track))
        playlistDao.addTrackToPlaylist(playlistId, track.id)
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: String) = withContext(Dispatchers.IO) {
        playlistDao.removeTrackFromPlaylist(playlistId, trackId)
    }

    suspend fun setThemeMode(mode: ThemeMode) = prefsRepo.setThemeMode(mode)
    suspend fun setOfflineCacheMode(enabled: Boolean) = prefsRepo.setOfflineCacheMode(enabled)
    suspend fun setAudioQuality(quality: AudioQuality) = prefsRepo.setAudioQuality(quality)
    suspend fun setPlaybackSpeed(speed: Float) = prefsRepo.setPlaybackSpeed(speed)
    suspend fun setEqualizer(enabled: Boolean, preset: EqualizerPreset, bands: List<Int>, bassBoost: Int) =
        prefsRepo.setEqualizer(enabled, preset, bands, bassBoost)
}
