import 'dart:convert';

import 'package:audio_service/audio_service.dart';

import 'safe_prefs.dart';

/// Local-only listening stats ("your top artists"). Nothing leaves the device.
class ListeningStats {
  ListeningStats();
  static const _key = 'fluxmusic.stats.v1';

  final Map<String, int> artistPlays = <String, int>{};
  final Map<String, int> trackPlays = <String, int>{};
  final Map<String, String> trackTitles = <String, String>{};
  int totalPlays = 0;

  void record(MediaItem item) {
    final artist = (item.artist ?? '').trim();
    if (artist.isNotEmpty) artistPlays[artist] = (artistPlays[artist] ?? 0) + 1;
    trackPlays[item.id] = (trackPlays[item.id] ?? 0) + 1;
    trackTitles[item.id] = '${item.title}${artist.isEmpty ? '' : ' · $artist'}';
    totalPlays++;
  }

  List<MapEntry<String, int>> topArtists([int n = 5]) => _top(artistPlays, n);
  List<MapEntry<String, int>> topTracks([int n = 5]) =>
      _top(trackPlays, n).map((e) => MapEntry(trackTitles[e.key] ?? e.key, e.value)).toList();

  static List<MapEntry<String, int>> _top(Map<String, int> map, int n) {
    final entries = map.entries.toList()..sort((a, b) => b.value.compareTo(a.value));
    return entries.take(n).toList();
  }

  Map<String, dynamic> toJson() => {
        'artists': artistPlays,
        'tracks': trackPlays,
        'titles': trackTitles,
        'total': totalPlays,
      };

  void loadJson(Map<String, dynamic> json) {
    artistPlays
      ..clear()
      ..addAll((json['artists'] as Map? ?? {}).map((k, v) => MapEntry('$k', (v as num).toInt())));
    trackPlays
      ..clear()
      ..addAll((json['tracks'] as Map? ?? {}).map((k, v) => MapEntry('$k', (v as num).toInt())));
    trackTitles
      ..clear()
      ..addAll((json['titles'] as Map? ?? {}).map((k, v) => MapEntry('$k', '$v')));
    totalPlays = (json['total'] as num?)?.toInt() ?? 0;
  }

  Future<void> load() async {
    final raw = await SafePrefs.getStringList(_key);
    if (raw.isEmpty) return;
    try {
      loadJson(jsonDecode(raw.first) as Map<String, dynamic>);
    } catch (_) {}
  }

  Future<void> save() => SafePrefs.setStringList(_key, [jsonEncode(toJson())]);
}
