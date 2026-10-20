import 'package:audio_service/audio_service.dart';

/// A small, UI-friendly model around the dynamic objects returned by
/// dart_ytmusic_api. Keeping the parser defensive makes FluxMusic less fragile
/// when an unofficial upstream response changes slightly.
class Track {
  const Track({
    required this.id,
    required this.title,
    required this.artist,
    this.album = '',
    this.thumbnailUrl = '',
    this.durationText = '',
  });

  final String id;
  final String title;
  final String artist;
  final String album;
  final String thumbnailUrl;
  final String durationText;

  static dynamic _read(dynamic object, String field) {
    if (object == null) return null;
    try {
      switch (field) {
        case 'videoId':
          return object.videoId;
        case 'name':
          return object.name;
        case 'title':
          return object.title;
        case 'artist':
          return object.artist;
        case 'artists':
          return object.artists;
        case 'album':
          return object.album;
        case 'thumbnails':
          return object.thumbnails;
        case 'thumbnail':
          return object.thumbnail;
        case 'thumbnailUrl':
          return object.thumbnailUrl;
        case 'url':
          return object.url;
        case 'duration':
          return object.duration;
        case 'durationText':
          return object.durationText;
        case 'text':
          return object.text;
      }
    } catch (_) {
      return null;
    }
    return null;
  }

  static String _string(dynamic value) {
    if (value == null) return '';
    if (value is String) return value.trim();
    return value.toString().trim();
  }

  static String _thumbnail(dynamic source) {
    final direct = _read(source, 'thumbnail');
    final directUrl = _string(_read(direct, 'url'));
    if (directUrl.startsWith('http')) return directUrl;

    final raw = _read(source, 'thumbnails');
    if (raw is Iterable) {
      final choices = raw.toList();
      for (final item in choices.reversed) {
        final url = _string(_read(item, 'url'));
        if (url.startsWith('http')) return url;
      }
    }

    final other = _string(_read(source, 'thumbnailUrl'));
    return other.startsWith('http') ? other : '';
  }

  factory Track.fromYouTubeMusic(dynamic source) {
    final rawId = _string(_read(source, 'videoId'));
    final title = _string(_read(source, 'name')).isNotEmpty
        ? _string(_read(source, 'name'))
        : _string(_read(source, 'title'));
    final rawArtist = _read(source, 'artist');
    var artist = _string(_read(rawArtist, 'name'));
    if (artist.isEmpty) {
      final artists = _read(source, 'artists');
      if (artists is Iterable && artists.isNotEmpty) {
        artist = artists.map((item) {
          final name = _read(item, 'name');
          return _string(name ?? item);
        }).where((name) => name.isNotEmpty).join(', ');
      } else {
        artist = _string(rawArtist);
      }
    }

    final albumValue = _read(source, 'album');
    final album = _string(_read(albumValue, 'name')).isNotEmpty
        ? _string(_read(albumValue, 'name'))
        : _string(albumValue);

    return Track(
      id: rawId,
      title: title.isEmpty ? 'Unknown track' : title,
      artist: artist.isEmpty ? 'Unknown artist' : artist,
      album: album,
      thumbnailUrl: _thumbnail(source),
      durationText: _string(_read(source, 'durationText')).isNotEmpty
          ? _string(_read(source, 'durationText'))
          : _string(_read(source, 'duration')),
    );
  }

  factory Track.fromMediaItem(MediaItem item) => Track(
        id: _string(item.extras?['videoId']).isNotEmpty
            ? _string(item.extras?['videoId'])
            : item.id,
        title: item.title,
        artist: item.artist ?? 'Unknown artist',
        album: item.album ?? '',
        thumbnailUrl: _string(item.extras?['thumbnailUrl']),
        durationText: _string(item.extras?['durationText']),
      );

  MediaItem toMediaItem() => MediaItem(
        id: id,
        title: title,
        artist: artist,
        album: album.isEmpty ? 'FluxMusic' : album,
        artUri: thumbnailUrl.isEmpty ? null : Uri.tryParse(thumbnailUrl),
        extras: <String, dynamic>{
          'videoId': id,
          'thumbnailUrl': thumbnailUrl,
          'durationText': durationText,
        },
      );
}
