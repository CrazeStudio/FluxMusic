import 'dart:convert';

/// Export/import playlists as portable JSON.
class PlaylistIO {
  const PlaylistIO._();
  static const version = 1;

  /// [playlists]: name -> list of encoded track maps (id, title, artist, ...).
  static String export(Map<String, List<Map<String, dynamic>>> playlists) =>
      const JsonEncoder.withIndent('  ').convert({'app': 'fluxmusic', 'version': version, 'playlists': playlists});

  static Map<String, List<Map<String, dynamic>>> import(String raw) {
    final decoded = jsonDecode(raw);
    if (decoded is! Map || decoded['playlists'] is! Map) {
      throw const FormatException('Not a FluxMusic playlist file.');
    }
    final out = <String, List<Map<String, dynamic>>>{};
    (decoded['playlists'] as Map).forEach((name, tracks) {
      if (tracks is! List) return;
      final clean = tracks
          .whereType<Map>()
          .map((m) => Map<String, dynamic>.from(m))
          .where((m) => (m['id'] as String?)?.isNotEmpty ?? false)
          .toList();
      out['$name'.trim().isEmpty ? 'Imported' : '$name'.trim()] = clean;
    });
    return out;
  }

  /// Merges imported tracks into existing ones without duplicates.
  static List<Map<String, dynamic>> merge(List<Map<String, dynamic>> existing, List<Map<String, dynamic>> incoming) {
    final ids = existing.map((e) => e['id']).toSet();
    return [...existing, ...incoming.where((e) => ids.add(e['id']))];
  }
}
