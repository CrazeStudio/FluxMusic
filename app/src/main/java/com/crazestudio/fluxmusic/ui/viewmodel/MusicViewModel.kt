package com.crazestudio.fluxmusic.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crazestudio.fluxmusic.data.local.db.PlaylistSummary
import com.crazestudio.fluxmusic.data.local.prefs.UserPreferences
import com.crazestudio.fluxmusic.data.model.AudioQuality
import com.crazestudio.fluxmusic.data.model.EqualizerPreset
import com.crazestudio.fluxmusic.data.model.ThemeMode
import com.crazestudio.fluxmusic.data.model.Track
import com.crazestudio.fluxmusic.data.repository.MusicRepository
import com.crazestudio.fluxmusic.playback.PlaybackController
import com.crazestudio.fluxmusic.playback.PlaybackUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoveryUiState(
    val selectedLanguage: String = "All",
    val isLoading: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val error: String? = null
)

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<Track> = emptyList(),
    val error: String? = null
)

class MusicViewModel(
    private val repository: MusicRepository,
    val playback: PlaybackController
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = repository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

    val playbackState: StateFlow<PlaybackUiState> = playback.state

    val cachedTracks: StateFlow<List<Track>> = repository.cachedTracks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteTracks: StateFlow<List<Track>> = repository.favoriteTracks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteIds: StateFlow<Set<String>> = favoriteTracks
        .map { list -> list.mapTo(HashSet()) { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val recentTracks: StateFlow<List<Track>> = repository.recentTracks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists: StateFlow<List<PlaylistSummary>> = repository.playlistSummaries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val searchHistory: StateFlow<List<String>> = repository.searchHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cachedTrackCount: StateFlow<Int> = repository.cachedTrackCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _discoveryState = MutableStateFlow(DiscoveryUiState())
    val discoveryState: StateFlow<DiscoveryUiState> = _discoveryState.asStateFlow()

    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private var searchJob: Job? = null

    init {
        playback.connect()
        loadDiscovery("All")
    }

    fun selectLanguage(language: String) {
        if (_discoveryState.value.selectedLanguage == language && _discoveryState.value.tracks.isNotEmpty()) return
        loadDiscovery(language)
    }

    fun loadDiscovery(language: String = _discoveryState.value.selectedLanguage) {
        viewModelScope.launch {
            _discoveryState.update { it.copy(selectedLanguage = language, isLoading = true, error = null) }
            if (preferences.value.offlineCacheMode) {
                val local = repository.searchLocalCache(if (language == "All") "" else language)
                _discoveryState.update {
                    it.copy(isLoading = false, tracks = local, error = null)
                }
                return@launch
            }
            val result = repository.discoverByLanguage(language)
            result.onSuccess { list ->
                _discoveryState.update { it.copy(isLoading = false, tracks = list, error = null) }
            }.onFailure { err ->
                val fallback = repository.searchLocalCache(if (language == "All") "" else language)
                _discoveryState.update {
                    it.copy(
                        isLoading = false,
                        tracks = fallback,
                        error = if (fallback.isEmpty()) err.message ?: "YouTube Music is unavailable." else null
                    )
                }
            }
        }
    }

    fun updateSearchQuery(newQuery: String, immediate: Boolean = false) {
        _searchState.update { it.copy(query = newQuery) }
        searchJob?.cancel()
        val trimmed = newQuery.trim()
        if (trimmed.length < 2) {
            _searchState.update { it.copy(isSearching = false, results = emptyList(), error = null) }
            return
        }
        searchJob = viewModelScope.launch {
            if (!immediate) delay(360L)
            _searchState.update { it.copy(isSearching = true, error = null) }
            val result = repository.searchOnlineAndCache(trimmed)
            result.onSuccess { list ->
                _searchState.update { it.copy(isSearching = false, results = list, error = null) }
            }.onFailure { err ->
                val localFallback = repository.searchLocalCache(trimmed)
                _searchState.update {
                    it.copy(
                        isSearching = false,
                        results = localFallback,
                        error = if (localFallback.isEmpty()) err.message ?: "Search unavailable." else null
                    )
                }
            }
        }
    }

    fun submitSearch(query: String = _searchState.value.query) {
        val cleaned = query.trim()
        if (cleaned.length < 2) return
        viewModelScope.launch {
            repository.addSearchHistory(cleaned)
        }
        updateSearchQuery(cleaned, immediate = true)
    }

    fun deleteSearchQuery(query: String) {
        viewModelScope.launch { repository.deleteSearchQuery(query) }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            repository.clearSearchHistory()
            _snackbarMessage.value = "Search history cleared"
        }
    }

    fun clearRecentHistory() {
        viewModelScope.launch {
            repository.clearRecentHistory()
            _snackbarMessage.value = "Listening history cleared"
        }
    }

    fun clearCachedMetadata() {
        viewModelScope.launch {
            repository.clearCachedMetadata()
            _snackbarMessage.value = "Cleared unpinned metadata & stream cache"
        }
    }

    fun playTracks(tracks: List<Track>, startIndex: Int, sourceLabel: String) {
        if (tracks.isEmpty()) return
        if (preferences.value.offlineCacheMode) {
            _snackbarMessage.value = "Offline Cache Mode is active — disable it in Settings to stream."
            return
        }
        viewModelScope.launch {
            tracks.forEach { repository.cacheTrack(it) }
        }
        playback.playQueue(tracks, startIndex, sourceLabel)
    }

    fun playNext(track: Track) {
        viewModelScope.launch { repository.cacheTrack(track) }
        playback.playNext(track)
        _snackbarMessage.value = "Playing \"${track.safeTitle}\" next"
    }

    fun addToQueue(track: Track) {
        viewModelScope.launch { repository.cacheTrack(track) }
        playback.addToQueue(track)
        _snackbarMessage.value = "Added \"${track.safeTitle}\" to queue"
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            val nowFav = repository.toggleFavorite(track)
            _snackbarMessage.value = if (nowFav) "Saved to Liked Songs" else "Removed from Liked Songs"
        }
    }

    fun createPlaylist(name: String, initialTrack: Track? = null) {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return
        viewModelScope.launch {
            repository.createPlaylist(cleaned, initialTrack)
            _snackbarMessage.value = if (initialTrack != null) {
                "Created \"$cleaned\" and added track"
            } else {
                "Created playlist \"$cleaned\""
            }
        }
    }

    fun renamePlaylist(playlistId: Long, newName: String) {
        viewModelScope.launch {
            repository.renamePlaylist(playlistId, newName)
            _snackbarMessage.value = "Playlist renamed"
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
            _snackbarMessage.value = "Playlist deleted"
        }
    }

    fun addTrackToPlaylist(playlistId: Long, playlistName: String, track: Track) {
        viewModelScope.launch {
            repository.addTrackToPlaylist(playlistId, track)
            _snackbarMessage.value = "Added to \"$playlistName\""
        }
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: String) {
        viewModelScope.launch {
            repository.removeTrackFromPlaylist(playlistId, trackId)
            _snackbarMessage.value = "Removed from playlist"
        }
    }

    fun observePlaylistName(playlistId: Long): Flow<String> =
        repository.observePlaylist(playlistId).map { it?.name ?: "Playlist" }

    fun observePlaylistTracks(playlistId: Long): Flow<List<Track>> =
        repository.observePlaylistTracks(playlistId)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun setOfflineCacheMode(enabled: Boolean) {
        viewModelScope.launch {
            repository.setOfflineCacheMode(enabled)
            if (!enabled) {
                loadDiscovery(_discoveryState.value.selectedLanguage)
            }
            _snackbarMessage.value = if (enabled) {
                "Offline Cache Mode enabled · Network paused"
            } else {
                "Online streaming re-enabled"
            }
        }
    }

    fun setAudioQuality(quality: AudioQuality) {
        viewModelScope.launch { repository.setAudioQuality(quality) }
    }

    fun setEqEnabled(enabled: Boolean) = playback.setEqEnabled(enabled)
    fun setEqBand(index: Int, levelMb: Int) = playback.setEqBand(index, levelMb)
    fun applyEqPreset(preset: EqualizerPreset) = playback.applyEqPreset(preset)
    fun setBassBoost(strength: Int) = playback.setBassBoost(strength)
    fun setPlaybackSpeed(speed: Float) = playback.setPlaybackSpeed(speed)
    fun setSleepTimer(minutes: Int, endOfTrack: Boolean = false) {
        playback.setSleepTimer(minutes, endOfTrack)
        _snackbarMessage.value = when {
            endOfTrack -> "Sleep timer set for end of current track"
            minutes > 0 -> "Sleep timer set for $minutes min"
            else -> "Sleep timer cancelled"
        }
    }

    fun consumeSnackbar() {
        _snackbarMessage.value = null
    }

    override fun onCleared() {
        playback.release()
        super.onCleared()
    }

    class Factory(
        private val repository: MusicRepository,
        private val playbackController: PlaybackController
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MusicViewModel(repository, playbackController) as T
        }
    }
}
