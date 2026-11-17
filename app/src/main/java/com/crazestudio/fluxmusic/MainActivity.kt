package com.crazestudio.fluxmusic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.crazestudio.fluxmusic.data.model.ThemeMode
import com.crazestudio.fluxmusic.data.model.Track
import com.crazestudio.fluxmusic.playback.PlaybackController
import com.crazestudio.fluxmusic.ui.components.EqualizerBottomSheet
import com.crazestudio.fluxmusic.ui.components.MiniPlayerBar
import com.crazestudio.fluxmusic.ui.components.NowPlayingOverlay
import com.crazestudio.fluxmusic.ui.components.PlaybackSpeedBottomSheet
import com.crazestudio.fluxmusic.ui.components.PlaylistNameDialog
import com.crazestudio.fluxmusic.ui.components.PlaylistPickerDialog
import com.crazestudio.fluxmusic.ui.components.QueueBottomSheet
import com.crazestudio.fluxmusic.ui.components.SleepTimerBottomSheet
import com.crazestudio.fluxmusic.ui.navigation.HomeRoute
import com.crazestudio.fluxmusic.ui.navigation.LibraryRoute
import com.crazestudio.fluxmusic.ui.navigation.PlaylistDetailRoute
import com.crazestudio.fluxmusic.ui.navigation.PlaylistsRoute
import com.crazestudio.fluxmusic.ui.navigation.SearchRoute
import com.crazestudio.fluxmusic.ui.navigation.SettingsRoute
import com.crazestudio.fluxmusic.ui.screens.HomeScreen
import com.crazestudio.fluxmusic.ui.screens.LibraryScreen
import com.crazestudio.fluxmusic.ui.screens.PlaylistDetailScreen
import com.crazestudio.fluxmusic.ui.screens.PlaylistsScreen
import com.crazestudio.fluxmusic.ui.screens.SearchScreen
import com.crazestudio.fluxmusic.ui.screens.SettingsScreen
import com.crazestudio.fluxmusic.ui.theme.FluxMusicTheme
import com.crazestudio.fluxmusic.ui.viewmodel.MusicViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private data class TopLevelTab(
    val route: Any,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val TopTabs = listOf(
    TopLevelTab(HomeRoute, "Home", Icons.Rounded.Home, Icons.Outlined.Home),
    TopLevelTab(SearchRoute, "Search", Icons.Rounded.Search, Icons.Outlined.Search),
    TopLevelTab(LibraryRoute, "Library", Icons.Rounded.LibraryMusic, Icons.Outlined.LibraryMusic),
    TopLevelTab(PlaylistsRoute, "Playlists", Icons.AutoMirrored.Rounded.QueueMusic, Icons.AutoMirrored.Outlined.QueueMusic),
    TopLevelTab(SettingsRoute, "Settings", Icons.Rounded.Settings, Icons.Outlined.Settings)
)

class MainActivity : ComponentActivity() {

    private val viewModel: MusicViewModel by viewModels {
        val app = application as FluxApplication
        val playbackController = PlaybackController(app) { startedTrack ->
            CoroutineScope(Dispatchers.IO).launch {
                app.repository.recordRecentPlay(startedTrack)
            }
        }
        MusicViewModel.Factory(app.repository, playbackController)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preferences by viewModel.preferences.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val useDark = when (preferences.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            androidx.compose.runtime.DisposableEffect(useDark) {
                enableEdgeToEdge(
                    statusBarStyle = if (useDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (useDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    }
                )
                onDispose {}
            }

            FluxMusicTheme(themeMode = preferences.themeMode) {
                FluxMusicApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun FluxMusicApp(viewModel: MusicViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val context = LocalContext.current

    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val discoveryState by viewModel.discoveryState.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val cachedTracks by viewModel.cachedTracks.collectAsStateWithLifecycle()
    val favoriteTracks by viewModel.favoriteTracks.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val recentTracks by viewModel.recentTracks.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val cachedTrackCount by viewModel.cachedTrackCount.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    var showPlayerOverlay by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showEqualizerSheet by remember { mutableStateOf(false) }
    var showSleepSheet by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var playlistPickerTrack by remember { mutableStateOf<Track?>(null) }
    var newPlaylistInitialTrack by remember { mutableStateOf<Track?>(null) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var askedNotificationPermission by remember { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    val playTracksWithPermission: (List<Track>, Int, String) -> Unit = { list, index, label ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !askedNotificationPermission &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotificationPermission = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.playTracks(list, index, label)
        if (!preferences.offlineCacheMode) {
            showPlayerOverlay = true
        }
    }

    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeSnackbar()
    }

    val navigateTab: (Any) -> Unit = { route ->
        val options: androidx.navigation.NavOptionsBuilder.() -> Unit = {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
        when (route) {
            HomeRoute -> navController.navigate(HomeRoute, options)
            SearchRoute -> navController.navigate(SearchRoute, options)
            LibraryRoute -> navController.navigate(LibraryRoute, options)
            PlaylistsRoute -> navController.navigate(PlaylistsRoute, options)
            SettingsRoute -> navController.navigate(SettingsRoute, options)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavigationSuiteScaffold(
            navigationSuiteColors = NavigationSuiteDefaults.colors(
                navigationBarContainerColor = MaterialTheme.colorScheme.surface,
                navigationRailContainerColor = MaterialTheme.colorScheme.surface
            ),
            navigationSuiteItems = {
                TopTabs.forEach { tab ->
                    val selected = when (tab.route) {
                        HomeRoute -> currentDestination?.hasRoute<HomeRoute>() == true
                        SearchRoute -> currentDestination?.hasRoute<SearchRoute>() == true
                        LibraryRoute -> currentDestination?.hasRoute<LibraryRoute>() == true
                        PlaylistsRoute -> currentDestination?.hasRoute<PlaylistsRoute>() == true ||
                            currentDestination?.hasRoute<PlaylistDetailRoute>() == true
                        SettingsRoute -> currentDestination?.hasRoute<SettingsRoute>() == true
                        else -> false
                    }
                    item(
                        selected = selected,
                        onClick = { navigateTab(tab.route) },
                        icon = {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.label
                            )
                        },
                        label = { Text(tab.label) }
                    )
                }
            }
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                bottomBar = {
                    MiniPlayerBar(
                        playbackState = playbackState,
                        onExpand = { showPlayerOverlay = true },
                        onTogglePlayPause = { viewModel.playback.togglePlayPause() },
                        onSkipNext = { viewModel.playback.skipNext() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = HomeRoute,
                        modifier = Modifier.weight(1f)
                    ) {
                        composable<HomeRoute> {
                            HomeScreen(
                                discoveryState = discoveryState,
                                recentTracks = recentTracks,
                                favoriteIds = favoriteIds,
                                activeTrackId = playbackState.activeTrack?.id,
                                offlineCacheMode = preferences.offlineCacheMode,
                                onSelectLanguage = viewModel::selectLanguage,
                                onRetryDiscovery = { viewModel.loadDiscovery() },
                                onOpenSearch = { q ->
                                    if (q.isNotBlank()) viewModel.updateSearchQuery(q, immediate = true)
                                    navigateTab(SearchRoute)
                                },
                                onOpenLibrary = { navigateTab(LibraryRoute) },
                                onOpenSettings = { navigateTab(SettingsRoute) },
                                onPlayTracks = playTracksWithPermission,
                                onToggleFavorite = viewModel::toggleFavorite,
                                onPlayNext = viewModel::playNext,
                                onAddToQueue = viewModel::addToQueue,
                                onAddToPlaylist = { playlistPickerTrack = it }
                            )
                        }

                        composable<SearchRoute> {
                            SearchScreen(
                                searchState = searchState,
                                searchHistory = searchHistory,
                                cachedTracks = cachedTracks,
                                favoriteIds = favoriteIds,
                                activeTrackId = playbackState.activeTrack?.id,
                                offlineCacheMode = preferences.offlineCacheMode,
                                onQueryChange = { viewModel.updateSearchQuery(it) },
                                onSubmitSearch = { viewModel.submitSearch(it) },
                                onDeleteSearchQuery = viewModel::deleteSearchQuery,
                                onClearSearchHistory = viewModel::clearSearchHistory,
                                onPlayTracks = playTracksWithPermission,
                                onToggleFavorite = viewModel::toggleFavorite,
                                onPlayNext = viewModel::playNext,
                                onAddToQueue = viewModel::addToQueue,
                                onAddToPlaylist = { playlistPickerTrack = it }
                            )
                        }

                        composable<LibraryRoute> {
                            LibraryScreen(
                                favoriteTracks = favoriteTracks,
                                cachedTracks = cachedTracks,
                                favoriteIds = favoriteIds,
                                activeTrackId = playbackState.activeTrack?.id,
                                onOpenLikedSongs = {
                                    navController.navigate(PlaylistDetailRoute(isLikedSongs = true))
                                },
                                onOpenOfflineCache = {
                                    navController.navigate(PlaylistDetailRoute(isOfflineCache = true))
                                },
                                onPlayTracks = playTracksWithPermission,
                                onToggleFavorite = viewModel::toggleFavorite,
                                onPlayNext = viewModel::playNext,
                                onAddToQueue = viewModel::addToQueue,
                                onAddToPlaylist = { playlistPickerTrack = it }
                            )
                        }

                        composable<PlaylistsRoute> {
                            PlaylistsScreen(
                                likedCount = favoriteTracks.size,
                                playlists = playlists,
                                onOpenLikedSongs = {
                                    navController.navigate(PlaylistDetailRoute(isLikedSongs = true))
                                },
                                onOpenPlaylist = { playlistId ->
                                    navController.navigate(PlaylistDetailRoute(playlistId = playlistId))
                                },
                                onCreatePlaylist = { name -> viewModel.createPlaylist(name) },
                                onRenamePlaylist = viewModel::renamePlaylist,
                                onDeletePlaylist = viewModel::deletePlaylist
                            )
                        }

                        composable<PlaylistDetailRoute> { entry ->
                            val args = entry.toRoute<PlaylistDetailRoute>()
                            val title: String
                            val list: List<Track>
                            val canRemove: Boolean
                            if (args.isLikedSongs) {
                                title = "Liked Songs"
                                list = favoriteTracks
                                canRemove = false
                            } else if (args.isOfflineCache) {
                                title = "Metadata Cache"
                                list = cachedTracks
                                canRemove = false
                            } else {
                                val plName by viewModel.observePlaylistName(args.playlistId)
                                    .collectAsStateWithLifecycle("Playlist")
                                val plTracks by viewModel.observePlaylistTracks(args.playlistId)
                                    .collectAsStateWithLifecycle(emptyList())
                                title = plName
                                list = plTracks
                                canRemove = true
                            }

                            PlaylistDetailScreen(
                                title = title,
                                tracks = list,
                                favoriteIds = favoriteIds,
                                activeTrackId = playbackState.activeTrack?.id,
                                canRemoveTracks = canRemove,
                                onBack = { navController.popBackStack() },
                                onPlayTracks = playTracksWithPermission,
                                onToggleFavorite = viewModel::toggleFavorite,
                                onPlayNext = viewModel::playNext,
                                onAddToQueue = viewModel::addToQueue,
                                onAddToPlaylist = { playlistPickerTrack = it },
                                onRemoveFromPlaylist = { track ->
                                    if (args.playlistId > 0L) {
                                        viewModel.removeTrackFromPlaylist(args.playlistId, track.id)
                                    }
                                }
                            )
                        }

                        composable<SettingsRoute> {
                            SettingsScreen(
                                preferences = preferences,
                                cachedTrackCount = cachedTrackCount,
                                onSetThemeMode = viewModel::setThemeMode,
                                onSetOfflineCacheMode = viewModel::setOfflineCacheMode,
                                onSetAudioQuality = viewModel::setAudioQuality,
                                onOpenEqualizer = { showEqualizerSheet = true },
                                onClearSearchHistory = viewModel::clearSearchHistory,
                                onClearRecentHistory = viewModel::clearRecentHistory,
                                onClearCachedMetadata = viewModel::clearCachedMetadata
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showPlayerOverlay,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            val activeId = playbackState.activeTrack?.id
            NowPlayingOverlay(
                playbackState = playbackState,
                lyricsState = lyricsState,
                offlineCacheMode = preferences.offlineCacheMode,
                isFavorite = activeId != null && activeId in favoriteIds,
                onDismiss = { showPlayerOverlay = false },
                onToggleFavorite = viewModel::toggleFavorite,
                onAddToPlaylist = { playlistPickerTrack = it },
                onLoadLyrics = viewModel::loadLyricsForTrack,
                onSeekTo = { viewModel.playback.seekTo(it) },
                onTogglePlayPause = { viewModel.playback.togglePlayPause() },
                onSkipPrevious = { viewModel.playback.skipPrevious() },
                onSkipNext = { viewModel.playback.skipNext() },
                onToggleShuffle = { viewModel.playback.toggleShuffle() },
                onCycleRepeat = { viewModel.playback.cycleRepeatMode() },
                onOpenQueue = { showQueueSheet = true },
                onOpenEqualizer = { showEqualizerSheet = true },
                onOpenSleepTimer = { showSleepSheet = true },
                onOpenSpeed = { showSpeedSheet = true },
                onRetry = { viewModel.playback.retryCurrent() }
            )
        }
    }

    if (showQueueSheet) {
        QueueBottomSheet(
            queue = playbackState.queue,
            currentIndex = playbackState.currentIndex,
            onJumpTo = { viewModel.playback.jumpToQueueIndex(it) },
            onMoveItem = { from, to -> viewModel.playback.moveQueueItem(from, to) },
            onRemoveItem = { viewModel.playback.removeQueueItem(it) },
            onClearUpcoming = { viewModel.playback.clearUpcomingQueue() },
            onDismiss = { showQueueSheet = false }
        )
    }

    if (showEqualizerSheet) {
        EqualizerBottomSheet(
            eqState = playbackState.equalizer,
            onToggleEnabled = viewModel::setEqEnabled,
            onSelectPreset = viewModel::applyEqPreset,
            onChangeBand = viewModel::setEqBand,
            onChangeBassBoost = viewModel::setBassBoost,
            onDismiss = { showEqualizerSheet = false }
        )
    }

    if (showSleepSheet) {
        SleepTimerBottomSheet(
            remainingSec = playbackState.sleepRemainingSec,
            endOfTrack = playbackState.sleepEndOfTrack,
            onSetTimer = viewModel::setSleepTimer,
            onDismiss = { showSleepSheet = false }
        )
    }

    if (showSpeedSheet) {
        PlaybackSpeedBottomSheet(
            currentSpeed = playbackState.playbackSpeed,
            onSelectSpeed = viewModel::setPlaybackSpeed,
            onDismiss = { showSpeedSheet = false }
        )
    }

    playlistPickerTrack?.let { trackToAdd ->
        PlaylistPickerDialog(
            playlists = playlists,
            onSelectPlaylist = { summary ->
                viewModel.addTrackToPlaylist(summary.playlist.id, summary.playlist.name, trackToAdd)
            },
            onCreateNew = {
                newPlaylistInitialTrack = trackToAdd
                showNewPlaylistDialog = true
            },
            onDismiss = { playlistPickerTrack = null }
        )
    }

    if (showNewPlaylistDialog) {
        PlaylistNameDialog(
            title = "New playlist",
            confirmLabel = "Create",
            onConfirm = { name ->
                viewModel.createPlaylist(name, newPlaylistInitialTrack)
                newPlaylistInitialTrack = null
            },
            onDismiss = {
                showNewPlaylistDialog = false
                newPlaylistInitialTrack = null
            }
        )
    }
}
