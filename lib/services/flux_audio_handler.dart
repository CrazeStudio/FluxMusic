import 'dart:async';
import 'dart:math';

import 'package:audio_service/audio_service.dart';
import 'package:flutter/foundation.dart';
import 'package:just_audio/just_audio.dart';
import 'package:flutter/services.dart';
import 'package:youtube_explode_dart/youtube_explode_dart.dart';

/// Background audio service. Stream resolution is unofficial and may break
/// when YouTube changes its delivery mechanisms, so the handler tries more
/// than one supported client and keeps a readable error for the UI.
enum FluxRepeatMode { off, all, one }

class FluxAudioHandler extends BaseAudioHandler with QueueHandler, SeekHandler {
  final AudioPlayer _player = AudioPlayer();
  final YoutubeExplode _youtube = YoutubeExplode();
  static const MethodChannel _audioEffects = MethodChannel('dev.crazestudio.fluxmusic/audio_effects');
  final Map<String, _CandidateCacheEntry> _candidateCache = <String, _CandidateCacheEntry>{};
  final Map<String, Future<List<dynamic>>> _candidateRequests = <String, Future<List<dynamic>>>{};
  Timer? _sleepTimer;
  Timer? _sleepTimerTicker;
  DateTime? _sleepTimerEndsAt;
  final ValueNotifier<Duration?> sleepTimerRemaining = ValueNotifier<Duration?>(null);

  int _queueIndex = 0;
  int _loadGeneration = 0;
  bool _shuffleEnabled = false;
  FluxRepeatMode _repeatMode = FluxRepeatMode.off;
  List<MediaItem>? _unshuffledQueue;
  int? _unshuffledQueueIndex;
  bool _autoAdvanceRunning = false;
  String? _lastPlaybackError;

  bool get shuffleEnabled => _shuffleEnabled;
  int get currentQueueIndex => _queueIndex;
  bool get repeatEnabled => _repeatMode != FluxRepeatMode.off;
  FluxRepeatMode get repeatMode => _repeatMode;
  String? get lastPlaybackError => _lastPlaybackError;
  DateTime? get sleepTimerEndsAt => _sleepTimerEndsAt;

  void setSleepTimer(Duration? duration) {
    _sleepTimer?.cancel();
    _sleepTimerTicker?.cancel();
    _sleepTimer = null;
    _sleepTimerTicker = null;
    _sleepTimerEndsAt = null;
    sleepTimerRemaining.value = null;
    if (duration == null || duration <= Duration.zero) return;

    _sleepTimerEndsAt = DateTime.now().add(duration);
    sleepTimerRemaining.value = duration;
    _sleepTimerTicker = Timer.periodic(const Duration(seconds: 1), (_) {
      final end = _sleepTimerEndsAt;
      if (end == null) return;
      final remaining = end.difference(DateTime.now());
      sleepTimerRemaining.value = remaining.isNegative ? Duration.zero : remaining;
    });
    _sleepTimer = Timer(duration, () {
      _sleepTimerTicker?.cancel();
      _sleepTimerTicker = null;
      _sleepTimer = null;
      _sleepTimerEndsAt = null;
      sleepTimerRemaining.value = null;
      unawaited(pause().catchError((Object error) {
        _setError(error);
      }));
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
      if (state == ProcessingState.completed) {
        unawaited(_advanceAfterCompletion());
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
        MediaControl.rewind,
        if (_player.playing) MediaControl.pause else MediaControl.play,
        MediaControl.fastForward,
        MediaControl.skipToNext,
      ],
      // Android's compact notification keeps previous, play/pause, and next visible.
      androidCompactActionIndices: const <int>[0, 2, 4],
      systemActions: const <MediaAction>{
        MediaAction.seek,
        MediaAction.rewind,
        MediaAction.fastForward,
      },
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
    _repeatMode = FluxRepeatMode.off;
    _unshuffledQueue = null;
    _unshuffledQueueIndex = null;
    await _player.setLoopMode(LoopMode.off);
    final safeIndex = startIndex.clamp(0, items.length - 1).toInt();
    await skipToQueueItem(safeIndex);
  }


  Future<void> toggleShuffle() async {
    final currentQueue = queue.value;
    if (currentQueue.length < 2 || _queueIndex < 0 || _queueIndex >= currentQueue.length) return;

    if (!_shuffleEnabled) {
      _unshuffledQueue = List<MediaItem>.of(currentQueue);
      _unshuffledQueueIndex = _queueIndex;
      final current = currentQueue[_queueIndex];
      final shuffled = <MediaItem>[];
      for (var index = 0; index < currentQueue.length; index++) {
        if (index != _queueIndex) shuffled.add(currentQueue[index]);
      }
      shuffled.shuffle(Random());
      shuffled.insert(0, current);
      queue.add(List<MediaItem>.unmodifiable(shuffled));
      _queueIndex = 0;
    } else {
      final current = currentQueue[_queueIndex];
      final restored = List<MediaItem>.of(_unshuffledQueue ?? currentQueue);
      final identityIndex = restored.indexWhere((item) => identical(item, current));
      queue.add(List<MediaItem>.unmodifiable(restored));
      _queueIndex = (identityIndex >= 0 ? identityIndex : (_unshuffledQueueIndex ?? 0))
          .clamp(0, restored.length - 1)
          .toInt();
      _unshuffledQueue = null;
      _unshuffledQueueIndex = null;
    }
    _shuffleEnabled = !_shuffleEnabled;
    _publishPlaybackState();
  }

  void playNext(MediaItem item) {
    final items = List<MediaItem>.of(queue.value);
    final insertAt = items.isEmpty ? 0 : (_queueIndex + 1).clamp(0, items.length).toInt();
    items.insert(insertAt, item);
    queue.add(List<MediaItem>.unmodifiable(items));

    final original = _unshuffledQueue;
    if (original != null) {
      final originalIndex = ((_unshuffledQueueIndex ?? _queueIndex) + 1)
          .clamp(0, original.length)
          .toInt();
      original.insert(originalIndex, item);
    }
    _publishPlaybackState();
  }

  void addToQueue(MediaItem item) {
    final items = List<MediaItem>.of(queue.value)..add(item);
    queue.add(List<MediaItem>.unmodifiable(items));
    _unshuffledQueue?.add(item);
    _publishPlaybackState();
  }

  @override
  Future<void> removeQueueItemAt(int index) async {
    final items = List<MediaItem>.of(queue.value);
    if (index < 0 || index >= items.length || index == _queueIndex) return;
    final removed = items.removeAt(index);
    if (index < _queueIndex) _queueIndex--;

    final original = _unshuffledQueue;
    if (original != null) {
      final originalIndex = original.indexWhere((item) => identical(item, removed));
      if (originalIndex >= 0) {
        original.removeAt(originalIndex);
        if (_unshuffledQueueIndex != null && originalIndex < _unshuffledQueueIndex!) {
          _unshuffledQueueIndex = _unshuffledQueueIndex! - 1;
        }
      }
    }
    queue.add(List<MediaItem>.unmodifiable(items));
    _publishPlaybackState();
  }

  void moveQueueItem(int oldIndex, int newIndex) {
    final items = List<MediaItem>.of(queue.value);
    if (oldIndex < 0 || oldIndex >= items.length) return;
    if (newIndex < 0 || newIndex >= items.length || newIndex == oldIndex) return;
    final current = _queueIndex >= 0 && _queueIndex < items.length
        ? items[_queueIndex]
        : mediaItem.value;
    final moving = items.removeAt(oldIndex);
    items.insert(newIndex, moving);
    _shuffleEnabled = false;
    _unshuffledQueue = null;
    _unshuffledQueueIndex = null;
    final currentIndex = current == null
        ? -1
        : items.indexWhere((item) => identical(item, current));
    _queueIndex = currentIndex < 0 ? 0 : currentIndex;
    queue.add(List<MediaItem>.unmodifiable(items));
    _publishPlaybackState();
  }

  Future<void> toggleRepeat() async {
    _repeatMode = switch (_repeatMode) {
      FluxRepeatMode.off => FluxRepeatMode.all,
      FluxRepeatMode.all => FluxRepeatMode.one,
      FluxRepeatMode.one => FluxRepeatMode.off,
    };
    await _player.setLoopMode(_repeatMode == FluxRepeatMode.one ? LoopMode.one : LoopMode.off);
    _publishPlaybackState();
  }

  int? _nextQueueIndex() {
    final items = queue.value;
    if (items.isEmpty) return null;
    if (_queueIndex + 1 < items.length) return _queueIndex + 1;
    return _repeatMode == FluxRepeatMode.all ? 0 : null;
  }

  Future<void> _advanceAfterCompletion() async {
    if (_autoAdvanceRunning) return;
    _autoAdvanceRunning = true;
    Object? lastError;
    var failures = 0;
    try {
      while (failures < 3) {
        final nextIndex = _nextQueueIndex();
        if (nextIndex == null) {
          if (lastError != null) {
            await _player.stop();
            _lastPlaybackError =
                'Could not play the next track after $failures attempt(s). Try another version or retry later.';
            _publishPlaybackState();
          }
          return;
        }
        try {
          await skipToQueueItem(nextIndex);
          return;
        } catch (error) {
          lastError = error;
          failures++;
        }
      }
      await _player.stop();
      _lastPlaybackError =
          'Could not play the next $failures tracks. Check your connection or try another search result.';
      _publishPlaybackState();
    } catch (error) {
      _lastPlaybackError = _describeError(lastError ?? error);
      try {
        await _player.stop();
      } catch (_) {
        // Preserve the readable failure state if stopping fails.
      }
      _publishPlaybackState();
    } finally {
      _autoAdvanceRunning = false;
    }
  }

  @override
  Future<void> playMediaItem(MediaItem mediaItem) => _loadAndPlay(mediaItem);

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
      final sessionId = _player.androidAudioSessionId;
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

  @override
  Future<void> pause() => _player.pause();

  @override
  Future<void> seek(Duration position) => _player.seek(position);

  @override
  Future<void> rewind() async {
    final target = _player.position - const Duration(seconds: 10);
    await _player.seek(target < Duration.zero ? Duration.zero : target);
  }

  @override
  Future<void> fastForward() async {
    final target = _player.position + const Duration(seconds: 30);
    final duration = _player.duration;
    await _player.seek(duration != null && target > duration ? duration : target);
  }

  @override
  Future<void> skipToNext() async {
    final nextIndex = _nextQueueIndex();
    if (nextIndex == null) return;
    await skipToQueueItem(nextIndex);
  }

  @override
  Future<void> skipToPrevious() async {
    if (_player.position > const Duration(seconds: 4)) {
      await seek(Duration.zero);
      return;
    }
    final items = queue.value;
    if (items.isEmpty) return;
    if (_queueIndex > 0) {
      await skipToQueueItem(_queueIndex - 1);
    } else if (_repeatMode == FluxRepeatMode.all) {
      await skipToQueueItem(items.length - 1);
    }
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
    _sleepTimerTicker?.cancel();
    _sleepTimer = null;
    _sleepTimerTicker = null;
    _sleepTimerEndsAt = null;
    sleepTimerRemaining.value = null;
    _lastPlaybackError = null;
    await _player.stop();
    mediaItem.add(null);
    await super.stop();
  }

  Future<void> disposePlayer() async {
    ++_loadGeneration;
    _sleepTimer?.cancel();
    _sleepTimerTicker?.cancel();
    sleepTimerRemaining.dispose();
    _youtube.close();
    await _player.dispose();
  }
}


class _CandidateCacheEntry {
  const _CandidateCacheEntry(this.expiresAt, this.candidates);

  final DateTime expiresAt;
  final List<dynamic> candidates;
}
