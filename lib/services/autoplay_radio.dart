import 'dart:async';

import 'package:audio_service/audio_service.dart';

import 'flux_audio_handler.dart';

typedef RelatedFetcher = Future<List<MediaItem>> Function(MediaItem seed);

/// Keeps music going: when the queue is nearly finished, fetches related
/// tracks for the current song and appends them. [fetchRelated] is supplied by
/// main.dart so this file does not depend on a specific API version.
class AutoplayRadio {
  AutoplayRadio(this.handler, this.fetchRelated, {this.enabled = true});

  final FluxAudioHandler handler;
  final RelatedFetcher fetchRelated;
  bool enabled;
  StreamSubscription<MediaItem>? _sub;
  bool _busy = false;

  void start() {
    _sub ??= handler.trackStarted.listen(_onTrack);
  }

  Future<void> _onTrack(MediaItem item) async {
    if (!enabled || _busy) return;
    final remaining = handler.queue.value.length - handler.currentQueueIndex - 1;
    if (remaining > 1) return;
    _busy = true;
    try {
      final related = await fetchRelated(item).timeout(const Duration(seconds: 15));
      for (final track in pickNew(handler.queue.value, related)) {
        handler.addToQueue(track);
      }
    } catch (_) {
      // Radio is best-effort; playback must never break because of it.
    } finally {
      _busy = false;
    }
  }

  /// Related tracks not already queued, capped at [max].
  static List<MediaItem> pickNew(List<MediaItem> queue, List<MediaItem> related, {int max = 15}) {
    final seen = queue.map((e) => e.id).toSet();
    final out = <MediaItem>[];
    for (final track in related) {
      if (seen.add(track.id)) out.add(track);
      if (out.length >= max) break;
    }
    return out;
  }

  void dispose() => _sub?.cancel();
}
