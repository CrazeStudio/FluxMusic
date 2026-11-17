package com.crazestudio.fluxmusic;

import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

public final class Track {
    public final String id,title,artist,album,thumbnailUrl;
    public final long duration;
    public final Uri uri;
    public Track(String id,String title,String artist,String album,long duration,Uri uri){this(id,title,artist,album,duration,uri==null?"":uri.toString(),uri);}
    public Track(String id,String title,String artist,String album,long duration,String thumbnailUrl){this(id,title,artist,album,duration,thumbnailUrl,null);}
    private Track(String id,String title,String artist,String album,long duration,String thumbnailUrl,Uri uri){
        this.id=id;this.title=clean(title,"Unknown title");this.artist=clean(artist,"Unknown artist");this.album=clean(album,"YouTube Music");
        this.duration=Math.max(0,duration);this.thumbnailUrl=thumbnailUrl==null?"":thumbnailUrl;this.uri=uri;
    }
    private static String clean(String s,String fallback){return s==null||s.trim().isEmpty()?fallback:s.trim();}
    public MediaItem toMediaItem(){
        MediaMetadata metadata=new MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
                .setArtworkUri(thumbnailUrl.isEmpty()?null:Uri.parse(thumbnailUrl)).build();
        return new MediaItem.Builder().setMediaId(id).setUri(uri).setMediaMetadata(metadata).build();
    }
    public MediaItem toMediaItem(Uri streamUri){
        MediaMetadata metadata=new MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
                .setArtworkUri(thumbnailUrl.isEmpty()?null:Uri.parse(thumbnailUrl)).build();
        return new MediaItem.Builder().setMediaId(id).setUri(streamUri).setMediaMetadata(metadata).build();
    }
    public static String formatDuration(long millis){long sec=millis/1000;return String.format(java.util.Locale.getDefault(),"%d:%02d",sec/60,sec%60);}
}
