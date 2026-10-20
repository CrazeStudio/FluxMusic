import 'dart:async';
import 'dart:math';

import 'package:audio_service/audio_service.dart';
import 'package:just_audio/just_audio.dart';
import 'package:youtube_explode_dart/youtube_explode_dart.dart';

/// Owns playback independently of the Flutter screen, so audio and media
/// controls keep working when the UI is backgrounded or the screen is locked.
class FluxAudioHandler extends BaseAudioHandler with QueueHandler, SeekHandler {
  final AudioPlayer _player = AudioPlayer();
  final YoutubeExplode _youtube = YoutubeExplode();

  int _queueIndex = 0;
  int _loadGeneration = 0;
  bool _shuffleEnabled = false;
  List<MediaItem>? _unshuffledQueue;

  bool get shuffleEnabled => _shuffleEnabled;

  FluxAudioHandler() {
    _player.playbackEventStream.listen(
      (_) => _publishPlaybackState(),
      onError: (Object error, StackTrace stackTrace) {
        playbackState.add(playbackState.value.copyWith(
          playing: false,
          processingState: AudioProcessingState.error,
          errorMessage: error.toString(),
        ));
      },
    );
    _player.playingStream.listen((_) => _publishPlaybackState());
    _player.processingStateStream.listen((state) {
      _publishPlaybackState();
      if (state == ProcessingState.completed && _queueIndex + 1 < queue.value.length) {
        unawaited(skipToNext());
      }
    });
  }

  Stream<Duration> get positionStream => _player.positionStream;
  Stream<Duration?> get durationStream => _player.durationStream;

  void _publishPlaybackState() {
    final processing = switch (_player.processingState) {
      ProcessingState.idle => AudioProcessingState.idle,
      ProcessingState.loading => AudioProcessingState.loading,
      ProcessingState.buffering => AudioProcessingState.buffering,
      ProcessingState.ready => AudioProcessingState.ready,
      ProcessingState.completed => AudioProcessingState.completed,
    };

    playbackState.add(PlaybackState(
      controls: <MediaControl>[
        MediaControl.skipToPrevious,
        if (_player.playing) MediaControl.pause else MediaControl.play,
        MediaControl.skipToNext,
        MediaControl.stop,
      ],
      androidCompactActionIndices: const <int>[0, 1, 2],
      systemActions: const <MediaAction>{MediaAction.seek},
      processingState: processing,
      playing: _player.playing,
      updatePosition: _player.position,
      bufferedPosition: _player.bufferedPosition,
      speed: _player.speed,
      queueIndex: _queueIndex,
    ));
  }

  /// Replaces the visible queue and starts at the requested item.
  Future<void> playTracks(
    List<MediaItem> items, {
    int startIndex = 0,
  }) async {
    if (items.isEmpty) return;
    queue.add(List<MediaItem>.unmodifiable(items));
    _shuffleEnabled = false;
    _unshuffledQueue = null;
    final safeIndex = startIndex.clamp(0, items.length - 1).toInt();
    await skipToQueueItem(safeIndex);
  }

  /// Shuffles the queue while keeping the current item playing. Turning shuffle
  /// off restores the order that was active when shuffle was enabled.
  Future<void> toggleShuffle() async {
    final current = mediaItem.value;
    final currentQueue = queue.value;
    if (current == null || currentQueue.length < 2) return;

    if (!_shuffleEnabled) {
      _unshuffledQueue = List<MediaItem>.of(currentQueue);
      final shuffled = List<MediaItem>.of(currentQueue)
        ..removeWhere((item) => item.id == current.id)
        ..shuffle(Random());
      shuffled.insert(0, current);
      queue.add(List<MediaItem>.unmodifiable(shuffled));
      _queueIndex = 0;
    } else {
      final restored = List<MediaItem>.of(_unshuffledQueue ?? currentQueue);
      queue.add(List<MediaItem>.unmodifiable(restored));
      final restoredIndex = restored.indexWhere((item) => item.id == current.id);
      _queueIndex = restoredIndex < 0 ? 0 : restoredIndex;
      _unshuffledQueue = null;
    }
    _shuffleEnabled = !_shuffleEnabled;
    _publishPlaybackState();
  }

  @override
  Future<void> playMediaItem(MediaItem item) => _loadAndPlay(item);

  Future<void> _loadAndPlay(MediaItem item) async {
    final generation = ++_loadGeneration;
    final videoId = (item.extras?['videoId'] as String?) ?? item.id;
    if (videoId.trim().isEmpty) {
      throw StateError('This track does not have a playable video ID.');
    }

    mediaItem.add(item);
    playbackState.add(playbackState.value.copyWith(
      processingState: AudioProcessingState.loading,
      playing: false,
    ));

    try {
      final manifest = await _youtube.videos.streams.getManifest(
        videoId,
        ytClients: <YoutubeApiClient>[
          YoutubeApiClient.ios,
          YoutubeApiClient.androidVr,
        ],
      );
      final streams = manifest.audioOnly.toList()
        ..sort((a, b) => b.bitrate.bitsPerSecond.compareTo(a.bitrate.bitsPerSecond));
      if (streams.isEmpty) {
        throw StateError('No audio stream was offered for this track.');
      }
      if (generation != _loadGeneration) return;

      await _player.setAudioSource(
        AudioSource.uri(
          streams.first.url,
          tag: item,
        ),
      );
      if (generation != _loadGeneration) return;
      // AudioPlayer.play() completes when playback ends; don't keep the queue
      // change request waiting for the entire song to finish.
      unawaited(play());
    } catch (_) {
      if (generation == _loadGeneration) {
        playbackState.add(playbackState.value.copyWith(
          playing: false,
          processingState: AudioProcessingState.error,
        ));
      }
      rethrow;
    }
  }

  @override
  Future<void> play() async => _player.play();

  @override
  Future<void> pause() => _player.pause();

  @override
  Future<void> seek(Duration position) => _player.seek(position);

  @override
  Future<void> skipToNext() async {
    final items = queue.value;
    if (items.isEmpty || _queueIndex + 1 >= items.length) return;
    await skipToQueueItem(_queueIndex + 1);
  }

  @override
  Future<void> skipToPrevious() async {
    if (_player.position > const Duration(seconds: 4)) {
      await seek(Duration.zero);
      return;
    }
    final items = queue.value;
    if (items.isEmpty) return;
    final previous = (_queueIndex - 1 + items.length) % items.length;
    await skipToQueueItem(previous);
  }

  @override
  Future<void> skipToQueueItem(int index) async {
    final items = queue.value;
    if (index < 0 || index >= items.length) return;
    _queueIndex = index;
    await _loadAndPlay(items[index]);
  }

  @override
  Future<void> stop() async {
    ++_loadGeneration;
    await _player.stop();
    mediaItem.add(null);
    await super.stop();
  }

  /// Called by Flutter's lifecycle only when the service is genuinely done.
  Future<void> disposePlayer() async {
    ++_loadGeneration;
    _youtube.close();
    await _player.dispose();
  }
}
