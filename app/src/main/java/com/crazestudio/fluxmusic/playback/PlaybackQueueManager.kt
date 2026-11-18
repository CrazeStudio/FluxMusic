package com.crazestudio.fluxmusic.playback

import androidx.media3.common.Player
import com.crazestudio.fluxmusic.data.model.Track

/**
 * Owns queue mutation and navigation. PlaybackController only exposes commands
 * and observes state; queue rules are implemented here.
 */
class PlaybackQueueManager(private val player: Player) {

    fun replaceQueue(tracks: List<Track>, startIndex: Int): Track? {
        if (tracks.isEmpty()) return null
        val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
        player.setMediaItems(tracks.map(Track::toMediaItem), safeIndex, 0L)
        player.prepare()
        player.play()
        return tracks[safeIndex]
    }

    fun playNext(track: Track) {
        if (player.mediaItemCount == 0) {
            replaceQueue(listOf(track), 0)
            return
        }
        val insertAt = (player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount)
        player.addMediaItem(insertAt, track.toMediaItem())
    }

    fun addToEnd(track: Track) {
        if (player.mediaItemCount == 0) {
            replaceQueue(listOf(track), 0)
            return
        }
        player.addMediaItem(track.toMediaItem())
    }

    fun move(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in 0 until player.mediaItemCount ||
            toIndex !in 0 until player.mediaItemCount ||
            fromIndex == toIndex
        ) return
        player.moveMediaItem(fromIndex, toIndex)
    }

    fun remove(index: Int) {
        if (index in 0 until player.mediaItemCount) player.removeMediaItem(index)
    }

    fun clearUpcoming() {
        val current = player.currentMediaItemIndex
        if (current >= 0 && current + 1 < player.mediaItemCount) {
            player.removeMediaItems(current + 1, player.mediaItemCount)
        }
    }

    fun jumpTo(index: Int) {
        if (index !in 0 until player.mediaItemCount) return
        player.seekTo(index, 0L)
        player.play()
    }

    fun skipNext() {
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.play()
        }
    }

    fun skipPrevious(restartThresholdMs: Long = 3_500L) {
        if (player.currentPosition > restartThresholdMs) {
            player.seekTo(0L)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
            player.play()
        } else {
            player.seekTo(0L)
        }
    }
}
