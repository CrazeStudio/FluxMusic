import 'dart:async';
import 'dart:math';

import 'package:audio_service/audio_service.dart';
import 'package:just_audio/just_audio.dart';
import 'package:flutter/services.dart';
import 'package:youtube_explode_dart/youtube_explode_dart.dart';

/// Background audio service. Stream resolution is unofficial and may break
/// when YouTube changes its delivery mechanisms, so the handler tries more
/// than one supported client and keeps a readable error for the UI.
class FluxAudioHandler extends BaseAudioHandler with QueueHandler, SeekHandler {
  final AudioPlayer _player = AudioPlayer();
  final YoutubeExplode _youtube = YoutubeExplode();
  static const MethodChannel _audioEffects = MethodChannel('dev.crazestudio.fluxmusic/audio_effects');
  final Map<String, _CandidateCacheEntry> _candidateCache = <String, _CandidateCacheEntry>{};
  final Map<String, Future<List<dynamic>>> _candidateRequests = <String, Future<List<dynamic>>>{};
  Timer? _sleepTimer;
  DateTime? _sleepTimerEndsAt;

  int _queueIndex = 0;
  int _loadGeneration = 0;
  bool _shuffleEnabled = false;
  bool _repeatEnabled = false;
  List<MediaItem>? _unshuffledQueue;
  String? _lastPlaybackError;

  bool get shuffleEnabled => _shuffleEnabled;
  bool get repeatEnabled => _repeatEnabled;
  String? get lastPlaybackError => _lastPlaybackError;
  DateTime? get sleepTimerEndsAt => _sleepTimerEndsAt;

  void setSleepTimer(Duration? duration) {
    _sleepTimer?.cancel();
    _sleepTimer = null;
    _sleepTimerEndsAt = null;
    if (duration == null || duration <= Duration.zero) return;
    _sleepTimerEndsAt = DateTime.now().add(duration);
    _sleepTimer = Timer(duration, () {
      _sleepTimer = null;
      _sleepTimerEndsAt = null;
      unawaited(pause());
    });
  }

  Future<List<dynamic>> _resolveAudioCandidates(String videoId) {
    final now = DateTime.now();
    final cached = _candidateCache[videoId];
    if (cached != null && cached.expiresAt.isAfter(now)) {
      return Future<List<dynamic>>.value(cached.candidates);
    }
    final pending = _candidateRequests[videoId];
    if (pending != null) return pending;

    final request = _youtube.videos.streams
        .getManifest(videoId, requireWatchPage: true)
        .timeout(const Duration(seconds: 25))
        .then<List<dynamic>>((manifest) {
          final candidates = manifest.audioOnly.toList();
          // A moderate bitrate usually starts faster on mobile data than
          // always attempting the largest stream first.
          candidates.sort((a, b) {
            final aDelta = (a.bitrate.bitsPerSecond - 128000).abs();
            final bDelta = (b.bitrate.bitsPerSecond - 128000).abs();
            return aDelta.compareTo(bDelta);
          });
          _candidateCache[videoId] = _CandidateCacheEntry(
            DateTime.now().add(const Duration(minutes: 3)),
            candidates,
          );
          if (_candidateCache.length > 12) {
            _candidateCache.remove(_candidateCache.keys.first);
          }
          return candidates;
        }).whenComplete(() {
          _candidateRequests.remove(videoId);
        });
    _candidateRequests[videoId] = request;
    return request;
  }

  void _prefetchNextManifest() {
    final items = queue.value;
    final nextIndex = _queueIndex + 1;
    if (nextIndex >= items.length) return;
    final next = items[nextIndex];
    final nextId = (next.extras?['videoId'] as String?) ?? next.id;
    if (nextId.isEmpty) return;
    unawaited(_resolveAudioCandidates(nextId).then<void>((_) {}).catchError((Object _) {}));
  }

  FluxAudioHandler() {
    _player.playbackEventStream.listen(
      (_) => _publishPlaybackState(),
      onError: (Object error, StackTrace stackTrace) => _setError(error),
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

  String _describeError(Object error) {
    final text = error.toString();
    final lower = text.toLowerCase();
    if (lower.contains('403') || lower.contains('forbidden')) {
      return 'YouTube refused this audio stream. Try another version of the song or retry later.';
    }
    if (lower.contains('429') || lower.contains('rate limit')) {
      return 'YouTube is temporarily rate-limiting requests. Wait a little, then retry.';
    }
    if (lower.contains('video unavailable') || lower.contains('unplayable')) {
      return 'This video is unavailable for direct playback. Try another search result.';
    }
    if (lower.contains('socketexception') ||
        lower.contains('network is unreachable') ||
        lower.contains('failed host lookup') ||
        lower.contains('timed out')) {
      return 'The stream could not connect. Check your internet connection and retry.';
    }
    final firstLine = text.split('\n').first.replaceAll(RegExp(r'https?://\S+'), '[stream URL]');
    final concise = firstLine.length > 150 ? '${firstLine.substring(0, 147)}…' : firstLine;
    return 'Could not load audio: $concise';
  }

  void _setError(Object error) {
    _lastPlaybackError = _describeError(error);
    playbackState.add(playbackState.value.copyWith(
      playing: false,
      processingState: AudioProcessingState.error,
      errorMessage: _lastPlaybackError,
    ));
  }

  void _publishPlaybackState() {
    final playerProcessing = switch (_player.processingState) {
      ProcessingState.idle => AudioProcessingState.idle,
      ProcessingState.loading => AudioProcessingState.loading,
      ProcessingState.buffering => AudioProcessingState.buffering,
      ProcessingState.ready => AudioProcessingState.ready,
      ProcessingState.completed => AudioProcessingState.completed,
    };
    final processing = _lastPlaybackError == null ? playerProcessing : AudioProcessingState.error;
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
      playing: _lastPlaybackError == null && _player.playing,
      updatePosition: _player.position,
      bufferedPosition: _player.bufferedPosition,
      speed: _player.speed,
      queueIndex: _queueIndex,
      errorMessage: _lastPlaybackError,
    ));
  }

  Future<void> playTracks(List<MediaItem> items, {int startIndex = 0}) async {
    if (items.isEmpty) return;
    queue.add(List<MediaItem>.unmodifiable(items));
    _shuffleEnabled = false;
    _repeatEnabled = false;
    _unshuffledQueue = null;
    await _player.setLoopMode(LoopMode.off);
    final safeIndex = startIndex.clamp(0, items.length - 1).toInt();
    await skipToQueueItem(safeIndex);
  }


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

  Future<void> toggleRepeat() async {
    _repeatEnabled = !_repeatEnabled;
    await _player.setLoopMode(_repeatEnabled ? LoopMode.one : LoopMode.off);
    _publishPlaybackState();
  }

  @override
  Future<void> playMediaItem(MediaItem item) => _loadAndPlay(item);

  Future<void> _loadAndPlay(MediaItem item) async {
    final generation = ++_loadGeneration;
    final videoId = (item.extras?['videoId'] as String?) ?? item.id;
    if (videoId.trim().isEmpty) {
      const message = 'This result has no YouTube video ID. Try another search result.';
      _lastPlaybackError = message;
      throw StateError(message);
    }

    _lastPlaybackError = null;
    mediaItem.add(item);
    playbackState.add(playbackState.value.copyWith(
      processingState: AudioProcessingState.loading,
      playing: false,
      errorMessage: null,
    ));

    Object? lastError;
    try {
      // Do not force an old client here. YouTube has started rejecting audio
      // URLs from several previously working clients with HTTP 403. The pinned
      // extractor fork adds the visionOS client and selects compatible clients
      // itself. Fetching the watch page is required for that resolution path.
      final candidates = await _resolveAudioCandidates(videoId);

      if (candidates.isEmpty) {
        lastError = StateError('YouTube returned no audio-only streams for this track.');
      } else {
        // Try several audio formats/bitrates in case one endpoint is rejected.
        for (final stream in candidates.take(4)) {
          if (generation != _loadGeneration) return;
          try {
            await _player.setAudioSource(
              AudioSource.uri(
                stream.url,
                tag: item,
              ),
            ).timeout(const Duration(seconds: 22));
            if (generation != _loadGeneration) return;
            _lastPlaybackError = null;
            _publishPlaybackState();
            unawaited(_attachAudioEffects());
            unawaited(_playSafely(generation));
            _prefetchNextManifest();
            return;
          } catch (error) {
            lastError = error;
            await _player.stop();
          }
        }
      }
    } catch (error) {
      lastError = error;
    }

    if (generation == _loadGeneration) {
      _lastPlaybackError = _describeError(lastError ?? StateError('No playable stream was returned.'));
      playbackState.add(playbackState.value.copyWith(
        playing: false,
        processingState: AudioProcessingState.error,
        errorMessage: _lastPlaybackError,
      ));
      throw StateError(_lastPlaybackError!);
    }
  }

  Future<void> _attachAudioEffects() async {
    try {
      final sessionId = await _player.androidAudioSessionId;
      if (sessionId != null && sessionId > 0) {
        await _audioEffects.invokeMethod<void>('attachSession', <String, int>{'sessionId': sessionId});
      }
    } catch (_) {
      // Unsupported devices continue playback without platform enhancements.
    }
  }

  Future<void> _playSafely(int generation) async {
    try {
      await _player.play();
    } catch (error) {
      if (generation == _loadGeneration) _setError(error);
    }
  }

  @override
  Future<void> play() async {
    // A tap on Play after a failed stream should resolve a fresh URL, not
    // silently return while the old error remains set.
    if (_lastPlaybackError != null) {
      final current = mediaItem.value;
      if (current != null) {
        try {
          await _loadAndPlay(current);
        } catch (_) {
          // _loadAndPlay already publishes a readable error in playbackState.
          // Keep the tap handler from leaking an unhandled async exception.
        }
      }
      return;
    }
    try {
      await _player.play();
    } catch (error) {
      _setError(error);
      rethrow;
    }
  }

  @override
  Future<void> setSpeed(double speed) => _player.setSpeed(speed);

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
    _sleepTimer?.cancel();
    _sleepTimer = null;
    _sleepTimerEndsAt = null;
    _lastPlaybackError = null;
    await _player.stop();
    mediaItem.add(null);
    await super.stop();
  }

  Future<void> disposePlayer() async {
    ++_loadGeneration;
    _sleepTimer?.cancel();
    _youtube.close();
    await _player.dispose();
  }
}


class _CandidateCacheEntry {
  const _CandidateCacheEntry(this.expiresAt, this.candidates);

  final DateTime expiresAt;
  final List<dynamic> candidates;
}
