package com.crazestudio.fluxmusic.data.model

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.util.Locale

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val thumbnailUrl: String,
    val isFavorite: Boolean = false,
    val languageHint: String = ""
) {
    val safeTitle: String get() = title.ifBlank { "Unknown title" }
    val safeArtist: String get() = artist.ifBlank { "Unknown artist" }
    val safeAlbum: String get() = album.ifBlank { "YouTube Music" }

    fun toMediaItem(): MediaItem {
        val extras = Bundle().apply {
            putLong(EXTRA_DURATION_MS, durationMs)
            putString(EXTRA_THUMBNAIL_URL, thumbnailUrl)
            putString(EXTRA_LANGUAGE_HINT, languageHint)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(safeTitle)
            .setArtist(safeArtist)
            .setAlbumTitle(safeAlbum)
            .setArtworkUri(thumbnailUrl.takeIf { it.isNotBlank() }?.let(Uri::parse))
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(buildStreamProxyUri(id))
            .setMediaMetadata(metadata)
            .build()
    }

    companion object {
        const val FLUX_SCHEME = "fluxmusic"
        const val EXTRA_DURATION_MS = "flux_duration_ms"
        const val EXTRA_THUMBNAIL_URL = "flux_thumbnail_url"
        const val EXTRA_LANGUAGE_HINT = "flux_language_hint"

        fun buildStreamProxyUri(videoId: String): Uri =
            Uri.Builder().scheme(FLUX_SCHEME).authority("track").appendPath(videoId).build()

        fun extractVideoId(uri: Uri?): String? {
            if (uri?.scheme != FLUX_SCHEME) return null
            return uri.lastPathSegment?.takeIf { it.isNotBlank() }
        }

        fun fromMediaItem(item: MediaItem): Track {
            val md = item.mediaMetadata
            val extras = md.extras
            return Track(
                id = item.mediaId,
                title = md.title?.toString().orEmpty().ifBlank { "Unknown title" },
                artist = md.artist?.toString().orEmpty().ifBlank { "Unknown artist" },
                album = md.albumTitle?.toString().orEmpty().ifBlank { "YouTube Music" },
                durationMs = extras?.getLong(EXTRA_DURATION_MS, 0L) ?: 0L,
                thumbnailUrl = extras?.getString(EXTRA_THUMBNAIL_URL)
                    ?: md.artworkUri?.toString().orEmpty(),
                languageHint = extras?.getString(EXTRA_LANGUAGE_HINT).orEmpty()
            )
        }

        fun formatDuration(millis: Long): String {
            if (millis <= 0L) return "0:00"
            val totalSeconds = millis / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0L) {
                String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
            }
        }
    }
}
