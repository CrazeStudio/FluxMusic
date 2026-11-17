package com.crazestudio.fluxmusic;

import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

public final class Track {
    public final String id;
    public final String title;
    public final String artist;
    public final String album;
    public final long duration;
    public final Uri uri;

    public Track(String id, String title, String artist, String album, long duration, Uri uri) {
        this.id = id;
        this.title = title == null || title.isEmpty() ? "Unknown title" : title;
        this.artist = artist == null || artist.isEmpty() ? "Unknown artist" : artist;
        this.album = album == null || album.isEmpty() ? "Unknown album" : album;
        this.duration = Math.max(0, duration);
        this.uri = uri;
    }

    public MediaItem toMediaItem() {
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .build();
        return new MediaItem.Builder()
                .setMediaId(id)
                .setUri(uri)
                .setMediaMetadata(metadata)
                .build();
    }

    public static String formatDuration(long millis) {
        long seconds = millis / 1000;
        return String.format(java.util.Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }
}
