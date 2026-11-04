import 'package:audio_service/audio_service.dart';
import 'package:dart_ytmusic_api/yt_music.dart';

/// Thin, defensive wrapper over dart_ytmusic_api (methods confirmed on
/// pub.dev: getSearchSuggestions, getUpNexts, getArtist, getArtistSongs,
/// getArtistAlbums, getAlbum). Response objects are read through [toItem] with
/// try/catch so a field rename in the package cannot crash the app.
class MusicCatalog {
  MusicCatalog(this._yt);
  final YTMusic _yt;
  final Map<String, List<String>> _suggestionCache = <String, List<String>>{};

  Future<List<String>> suggestions(String query) async {
    final q = query.trim();
    if (q.length < 2) return const <String>[];
    final cached = _suggestionCache[q.toLowerCase()];
    if (cached != null) return cached;
    try {
      final result = (await _yt.getSearchSuggestions(q)).map((e) => e.toString()).take(8).toList();
      if (_suggestionCache.length > 50) _suggestionCache.remove(_suggestionCache.keys.first);
      return _suggestionCache[q.toLowerCase()] = result;
    } catch (_) {
      return const <String>[];
    }
  }

  /// For AutoplayRadio: related songs for the playing track.
  Future<List<MediaItem>> related(MediaItem seed) async {
    final videoId = (seed.extras?['videoId'] as String?) ?? seed.id;
    final list = await _yt.getUpNexts(videoId);
    return list.map<MediaItem?>(toItem).whereType<MediaItem>().toList();
  }

  Future<CatalogPage> artist(String artistId) async {
    final info = await _yt.getArtist(artistId);
    final songs = await _yt.getArtistSongs(artistId);
    final dynamic d = info;
    return CatalogPage(
      title: _str(() => d.name) ?? 'Artist',
      subtitle: 'Top songs',
      artUrl: _thumb(d),
      tracks: songs.map<MediaItem?>(toItem).whereType<MediaItem>().toList(),
    );
  }

  Future<CatalogPage> album(String albumId) async {
    final a = await _yt.getAlbum(albumId);
    final dynamic d = a;
    final dynamic songs = _try(() => d.songs) ?? const <dynamic>[];
    final albumName = _str(() => d.name) ?? 'Album';
    final artistName = _str(() => d.artist.name) ?? '';
    return CatalogPage(
      title: albumName,
      subtitle: artistName,
      artUrl: _thumb(d),
      tracks: (songs as Iterable)
          .map<MediaItem?>((s) => toItem(s, fallbackAlbum: albumName, fallbackArtist: artistName, fallbackArt: _thumb(d)))
          .whereType<MediaItem>()
          .toList(),
    );
  }

  /// Converts any song-like response object into a [MediaItem], or null if it
  /// has no video id.
  static MediaItem? toItem(dynamic s, {String? fallbackAlbum, String? fallbackArtist, String? fallbackArt}) {
    final id = _str(() => s.videoId);
    if (id == null || id.isEmpty) return null;
    final title = _str(() => s.name) ?? _str(() => s.title) ?? 'Unknown track';
    final artist = _str(() => s.artist.name) ?? _artists(s) ?? fallbackArtist;
    final seconds = _try<num>(() => s.duration as num?);
    final art = _thumb(s) ?? fallbackArt ?? '';
    return MediaItem(
      id: id,
      title: title,
      artist: artist,
      album: _str(() => s.album.name) ?? fallbackAlbum,
      duration: seconds != null ? Duration(seconds: seconds.toInt()) : null,
      artUri: art.isEmpty ? null : Uri.tryParse(art),
      extras: <String, dynamic>{'videoId': id, 'thumbnailUrl': art},
    );
  }

  static String? _artists(dynamic s) {
    final list = _try<Iterable>(() => s.artists as Iterable?);
    if (list == null) return null;
    final names = list.map((a) => _str(() => (a as dynamic).name)).whereType<String>().toList();
    return names.isEmpty ? null : names.join(', ');
  }

  static String? _thumb(dynamic s) {
    final thumbs = _try<Iterable>(() => s.thumbnails as Iterable?);
    if (thumbs == null || thumbs.isEmpty) return null;
    return _str(() => (thumbs.last as dynamic).url);
  }

  static String? _str(dynamic Function() read) {
    final v = _try<dynamic>(read);
    final text = v?.toString().trim();
    return (text == null || text.isEmpty || text == 'null') ? null : text;
  }

  static T? _try<T>(T? Function() read) {
    try {
      return read();
    } catch (_) {
      return null;
    }
  }
}

class CatalogPage {
  const CatalogPage({required this.title, required this.subtitle, required this.artUrl, required this.tracks});
  final String title;
  final String subtitle;
  final String? artUrl;
  final List<MediaItem> tracks;
}
