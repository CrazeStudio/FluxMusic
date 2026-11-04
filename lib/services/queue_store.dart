import 'dart:convert';

import 'package:audio_service/audio_service.dart';

import 'safe_prefs.dart';

/// Saves and restores the playback queue so the app resumes where you left off.
class QueueStore {
  const QueueStore._();

  static const _queueKey = 'fluxmusic.queue.v1';
  static const _indexKey = 'fluxmusic.queue_index.v1';
  static const _positionKey = 'fluxmusic.queue_position_ms.v1';
  static const _maxItems = 200;

  static String encodeItem(MediaItem item) => jsonEncode(<String, dynamic>{
        'id': item.id,
        'title': item.title,
        'artist': item.artist,
        'album': item.album,
        'durationMs': item.duration?.inMilliseconds,
        'thumbnailUrl': item.extras?['thumbnailUrl'],
        'videoId': item.extras?['videoId'] ?? item.id,
      });

  static MediaItem? decodeItem(String raw) {
    try {
      final map = jsonDecode(raw) as Map<String, dynamic>;
      final id = map['id'] as String? ?? '';
      if (id.isEmpty) return null;
      final durationMs = map['durationMs'];
      return MediaItem(
        id: id,
        title: map['title'] as String? ?? 'Unknown track',
        artist: map['artist'] as String?,
        album: map['album'] as String?,
        duration: durationMs is int ? Duration(milliseconds: durationMs) : null,
        extras: <String, dynamic>{
          'thumbnailUrl': map['thumbnailUrl'] as String? ?? '',
          'videoId': map['videoId'] as String? ?? id,
        },
      );
    } catch (_) {
      return null;
    }
  }

  static Future<void> save(List<MediaItem> queue, int index, Duration position) async {
    if (queue.isEmpty) return;
    final trimmed = queue.take(_maxItems).toList();
    await SafePrefs.setStringList(_queueKey, trimmed.map(encodeItem).toList());
    await SafePrefs.setInt(_indexKey, index.clamp(0, trimmed.length - 1).toInt());
    await SafePrefs.setInt(_positionKey, position.inMilliseconds);
  }

  static Future<({List<MediaItem> items, int index, Duration position})?> load() async {
    final raw = await SafePrefs.getStringList(_queueKey);
    final items = raw.map(decodeItem).whereType<MediaItem>().toList();
    if (items.isEmpty) return null;
    final index = await SafePrefs.getInt(_indexKey) ?? 0;
    final position = await SafePrefs.getInt(_positionKey) ?? 0;
    return (
      items: items,
      index: index.clamp(0, items.length - 1).toInt(),
      position: Duration(milliseconds: position),
    );
  }

  static Future<void> clear() async {
    await SafePrefs.remove(_queueKey);
    await SafePrefs.remove(_indexKey);
    await SafePrefs.remove(_positionKey);
  }
}
