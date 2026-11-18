package com.crazestudio.fluxmusic.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.crazestudio.fluxmusic.data.model.LrcParser
import com.crazestudio.fluxmusic.data.model.Track
import com.crazestudio.fluxmusic.ui.viewmodel.LyricsUiState

@Composable
fun LyricsStageCard(
    track: Track?,
    lyricsState: LyricsUiState,
    positionMs: Long,
    offlineCacheMode: Boolean,
    onSeekTo: (Long) -> Unit,
    onRetry: (Track, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.testTag("player_lyrics_stage")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lyrics,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                val badgeText = when {
                    lyricsState.lyrics?.fromCache == true -> "LRCLIB · Cached offline"
                    lyricsState.lyrics?.hasSynced == true -> "LRCLIB · Tap line to seek"
                    lyricsState.lyrics?.hasPlain == true -> "LRCLIB · Plain lyrics"
                    else -> "Lyrics via LRCLIB"
                }
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { track?.let { onRetry(it, true) } },
                    enabled = track != null && !offlineCacheMode && !lyricsState.isLoading,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("lyrics_refresh_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Refresh lyrics"
                    )
                }
            }

            when {
                track == null -> {
                    LyricsEmptyState(
                        title = "No track selected",
                        subtitle = "Start playing a song to view synced lyrics."
                    )
                }

                lyricsState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                            Text(
                                text = "Fetching lyrics from LRCLIB…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                lyricsState.error != null -> {
                    LyricsEmptyState(
                        title = "Lyrics unavailable",
                        subtitle = lyricsState.error,
                        showRetry = !offlineCacheMode,
                        onRetry = { onRetry(track, true) }
                    )
                }

                lyricsState.lyrics == null -> {
                    LyricsEmptyState(
                        title = if (offlineCacheMode) "No cached lyrics for this song" else "No lyrics found",
                        subtitle = if (offlineCacheMode) {
                            "Disable Offline Cache Mode in Settings to fetch lyrics from LRCLIB."
                        } else {
                            "LRCLIB didn't have a match for \"${track.safeTitle}\"."
                        },
                        showRetry = !offlineCacheMode,
                        onRetry = { onRetry(track, true) }
                    )
                }

                lyricsState.lyrics.instrumental && !lyricsState.lyrics.hasSynced && !lyricsState.lyrics.hasPlain -> {
                    LyricsEmptyState(
                        title = "Instrumental track",
                        subtitle = "No vocals detected for this track."
                    )
                }

                lyricsState.lyrics.hasSynced -> {
                    val lines = lyricsState.lyrics.syncedLines
                    val activeIdx = remember(lines, positionMs) {
                        LrcParser.activeLineIndex(lines, positionMs)
                    }
                    val listState = rememberLazyListState()
                    LaunchedEffect(activeIdx) {
                        if (activeIdx >= 0) {
                            listState.animateScrollToItem((activeIdx - 2).coerceAtLeast(0))
                        }
                    }
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("lyrics_synced_list")
                    ) {
                        itemsIndexed(lines, key = { index, line -> "${line.timeMs}_$index" }) { index, line ->
                            val isActive = index == activeIdx
                            val textColor by animateColorAsState(
                                targetValue = when {
                                    isActive -> MaterialTheme.colorScheme.primary
                                    index < activeIdx -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                                },
                                label = "lyric_line_color"
                            )
                            Surface(
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
                                } else {
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSeekTo(line.timeMs) }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = Track.formatDuration(line.timeMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isActive) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                        },
                                        modifier = Modifier.width(44.dp)
                                    )
                                    Text(
                                        text = line.text,
                                        style = if (isActive) {
                                            MaterialTheme.typography.titleMedium
                                        } else {
                                            MaterialTheme.typography.bodyLarge
                                        },
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                        color = textColor,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }

                lyricsState.lyrics.hasPlain -> {
                    val plainLines = remember(lyricsState.lyrics.plainLyrics) {
                        lyricsState.lyrics.plainLyrics.orEmpty().lines()
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("lyrics_plain_list")
                    ) {
                        itemsIndexed(plainLines) { _, line ->
                            if (line.isBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                            } else {
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsEmptyState(
    title: String,
    subtitle: String,
    showRetry: Boolean = false,
    onRetry: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (showRetry && onRetry != null) {
                Button(onClick = onRetry) {
                    Text("Retry")
                }
            }
        }
    }
}
