package com.crazestudio.fluxmusic.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object SearchRoute

@Serializable
data object LibraryRoute

@Serializable
data object PlaylistsRoute

@Serializable
data object SettingsRoute

@Serializable
data class PlaylistDetailRoute(
    val playlistId: Long = -1L,
    val isLikedSongs: Boolean = false,
    val isOfflineCache: Boolean = false
)
