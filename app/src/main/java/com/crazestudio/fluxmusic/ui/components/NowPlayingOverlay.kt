package com.crazestudio.fluxmusic.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.crazestudio.fluxmusic.R
import com.crazestudio.fluxmusic.data.model.Track
import com.crazestudio.fluxmusic.playback.PlaybackUiState
import com.crazestudio.fluxmusic.ui.viewmodel.LyricsUiState

@Composable
fun NowPlayingOverlay(
    playbackState: PlaybackUiState,
    lyricsState: LyricsUiState,
    offlineCacheMode: Boolean,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onLoadLyrics: (Track, Boolean) -> Unit,
    onSeekTo: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenSpeed: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onDismiss)

    val active = playbackState.activeTrack
    var showLyrics by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(showLyrics, active?.id) {
        if (showLyrics && active != null) {
            onLoadLyrics(active, false)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            val isWide = maxWidth >= 680.dp

            Column(
                modifier = Modifier
                    .widthIn(max = 980.dp)
                    .fillMaxSize()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("player_dismiss_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Minimize player",
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "PLAYING FROM",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = playbackState.sourceLabel,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = { active?.let(onAddToPlaylist) },
                        enabled = active != null,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("player_add_playlist_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                            contentDescription = "Add to playlist"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                ArtworkLyricsModeToggle(
                    showLyrics = showLyrics,
                    onSelectLyrics = { showLyrics = it },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (isWide) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (showLyrics) {
                                LyricsStageCard(
                                    track = active,
                                    lyricsState = lyricsState,
                                    positionMs = playbackState.positionMs,
                                    offlineCacheMode = offlineCacheMode,
                                    onSeekTo = onSeekTo,
                                    onRetry = onLoadLyrics,
                                    modifier = Modifier
                                        .widthIn(max = 420.dp)
                                        .fillMaxWidth()
                                        .height(380.dp)
                                )
                            } else {
                                PlayerArtworkCard(
                                    track = active,
                                    modifier = Modifier
                                        .widthIn(max = 360.dp)
                                        .aspectRatio(1f)
                                )
                            }
                        }
                        Column(
                            modifier = Modifier
                                .weight(1.1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center
                        ) {
                            PlayerControlsBlock(
                                playbackState = playbackState,
                                active = active,
                                isFavorite = isFavorite,
                                showLyrics = showLyrics,
                                onToggleLyrics = { showLyrics = !showLyrics },
                                onToggleFavorite = onToggleFavorite,
                                onSeekTo = onSeekTo,
                                onTogglePlayPause = onTogglePlayPause,
                                onSkipPrevious = onSkipPrevious,
                                onSkipNext = onSkipNext,
                                onToggleShuffle = onToggleShuffle,
                                onCycleRepeat = onCycleRepeat,
                                onOpenQueue = onOpenQueue,
                                onOpenEqualizer = onOpenEqualizer,
                                onOpenSleepTimer = onOpenSleepTimer,
                                onOpenSpeed = onOpenSpeed,
                                onRetry = onRetry
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (showLyrics) {
                                LyricsStageCard(
                                    track = active,
                                    lyricsState = lyricsState,
                                    positionMs = playbackState.positionMs,
                                    offlineCacheMode = offlineCacheMode,
                                    onSeekTo = onSeekTo,
                                    onRetry = onLoadLyrics,
                                    modifier = Modifier
                                        .widthIn(max = 400.dp)
                                        .fillMaxWidth()
                                        .height(330.dp)
                                )
                            } else {
                                PlayerArtworkCard(
                                    track = active,
                                    modifier = Modifier
                                        .widthIn(max = 330.dp)
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        PlayerControlsBlock(
                            playbackState = playbackState,
                            active = active,
                            isFavorite = isFavorite,
                            showLyrics = showLyrics,
                            onToggleLyrics = { showLyrics = !showLyrics },
                            onToggleFavorite = onToggleFavorite,
                            onSeekTo = onSeekTo,
                            onTogglePlayPause = onTogglePlayPause,
                            onSkipPrevious = onSkipPrevious,
                            onSkipNext = onSkipNext,
                            onToggleShuffle = onToggleShuffle,
                            onCycleRepeat = onCycleRepeat,
                            onOpenQueue = onOpenQueue,
                            onOpenEqualizer = onOpenEqualizer,
                            onOpenSleepTimer = onOpenSleepTimer,
                            onOpenSpeed = onOpenSpeed,
                            onRetry = onRetry
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtworkLyricsModeToggle(
    showLyrics: Boolean,
    onSelectLyrics: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = { onSelectLyrics(false) },
                color = if (!showLyrics) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                contentColor = if (!showLyrics) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.testTag("player_mode_artwork")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Icon(Icons.Rounded.Album, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Artwork", style = MaterialTheme.typography.labelLarge)
                }
            }
            Surface(
                onClick = { onSelectLyrics(true) },
                color = if (showLyrics) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                contentColor = if (showLyrics) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.testTag("player_mode_lyrics")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Icon(Icons.Rounded.Lyrics, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Lyrics", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun PlayerArtworkCard(
    track: Track?,
    modifier: Modifier = Modifier
) {
    ArtworkImage(
        thumbnailUrl = track?.thumbnailUrl.orEmpty(),
        trackId = track?.id ?: "empty",
        contentDescription = track?.safeTitle ?: "Album artwork",
        cornerRadius = 26.dp,
        iconSize = 72.dp,
        modifier = modifier.shadow(18.dp, RoundedCornerShape(26.dp))
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerControlsBlock(
    playbackState: PlaybackUiState,
    active: Track?,
    isFavorite: Boolean,
    showLyrics: Boolean,
    onToggleLyrics: () -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onSeekTo: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenSpeed: () -> Unit,
    onRetry: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = active?.safeTitle ?: "Nothing playing",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = active?.safeArtist ?: "Pick a track to begin",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = { active?.let(onToggleFavorite) },
                enabled = active != null,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("player_favorite_button")
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (isFavorite) "Unlike" else "Like",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        if (playbackState.isBuffering && playbackState.playbackError == null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (playbackState.positionMs == 0L) "Connecting audio stream…" else "Buffering…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (playbackState.playbackError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = playbackState.playbackError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRetry) {
                        Text("Retry")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        val duration = playbackState.durationMs.coerceAtLeast(1L)
        var isDragging by remember { mutableStateOf(false) }
        var dragFraction by remember { mutableFloatStateOf(0f) }
        val liveFraction = (playbackState.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        val sliderValue = if (isDragging) dragFraction else liveFraction
        val displayedPosition = if (isDragging) (dragFraction * duration).toLong() else playbackState.positionMs

        Slider(
            value = sliderValue,
            onValueChange = {
                isDragging = true
                dragFraction = it
            },
            onValueChangeFinished = {
                onSeekTo((dragFraction * duration).toLong())
                isDragging = false
            },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = Track.formatDuration(displayedPosition),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = Track.formatDuration(playbackState.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onToggleShuffle,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("player_shuffle_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (playbackState.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = onSkipPrevious,
                modifier = Modifier
                    .size(52.dp)
                    .testTag("player_prev_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = "Previous",
                    modifier = Modifier.size(34.dp)
                )
            }

            FilledIconButton(
                onClick = onTogglePlayPause,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = CircleShape,
                modifier = Modifier
                    .size(68.dp)
                    .testTag("player_play_pause_button")
            ) {
                if (playbackState.isBuffering) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(28.dp)
                    )
                } else {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            IconButton(
                onClick = onSkipNext,
                modifier = Modifier
                    .size(52.dp)
                    .testTag("player_next_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "Next",
                    modifier = Modifier.size(34.dp)
                )
            }

            IconButton(
                onClick = onCycleRepeat,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("player_repeat_button")
            ) {
                val repeatIcon = if (playbackState.repeatMode == Player.REPEAT_MODE_ONE) {
                    Icons.Rounded.RepeatOne
                } else {
                    Icons.Rounded.Repeat
                }
                Icon(
                    imageVector = repeatIcon,
                    contentDescription = "Repeat mode",
                    tint = if (playbackState.repeatMode != Player.REPEAT_MODE_OFF) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionPill(
                label = "Lyrics",
                active = showLyrics,
                icon = {
                    Icon(Icons.Rounded.Lyrics, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                onClick = onToggleLyrics,
                tag = "player_lyrics_pill"
            )

            ActionPill(
                label = "${"%.2f".format(playbackState.playbackSpeed)}x",
                active = kotlin.math.abs(playbackState.playbackSpeed - 1.0f) > 0.02f,
                icon = {
                    Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                onClick = onOpenSpeed,
                tag = "player_speed_pill"
            )

            ActionPill(
                label = if (playbackState.equalizer.enabled) "EQ On" else "EQ",
                active = playbackState.equalizer.enabled,
                icon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_equalizer),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                onClick = onOpenEqualizer,
                tag = "player_eq_pill"
            )

            val sleepLabel = when {
                playbackState.sleepEndOfTrack -> "End track"
                playbackState.sleepRemainingSec > 0 -> {
                    val m = playbackState.sleepRemainingSec / 60
                    val s = playbackState.sleepRemainingSec % 60
                    "%d:%02d".format(m, s)
                }
                else -> "Sleep"
            }
            ActionPill(
                label = sleepLabel,
                active = playbackState.sleepRemainingSec > 0 || playbackState.sleepEndOfTrack,
                icon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_sleep_timer),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                onClick = onOpenSleepTimer,
                tag = "player_sleep_pill"
            )

            ActionPill(
                label = "Queue (${playbackState.queue.size})",
                active = false,
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                onClick = onOpenQueue,
                tag = "player_queue_pill"
            )
        }
    }
}

@Composable
private fun ActionPill(
    label: String,
    active: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    tag: String
) {
    Surface(
        onClick = onClick,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(
            1.dp,
            if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier.testTag(tag)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            icon()
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
