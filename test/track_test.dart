import 'package:flutter_test/flutter_test.dart';
import 'package:fluxmusic/models/track.dart';

class _Artist {
  const _Artist(this.name);
  final String name;
}

class _Album {
  const _Album(this.name);
  final String name;
}

class _Thumbnail {
  const _Thumbnail(this.url);
  final String url;
}

class _YouTubeMusicSong {
  const _YouTubeMusicSong({
    required this.videoId,
    required this.name,
    required this.artist,
    required this.album,
    required this.thumbnails,
    required this.durationText,
  });

  final String videoId;
  final String name;
  final _Artist artist;
  final _Album album;
  final List<_Thumbnail> thumbnails;
  final String durationText;
}

void main() {
  group('Track.fromYouTubeMusic', () {
    test('normalizes song metadata and selects the largest thumbnail', () {
      const song = _YouTubeMusicSong(
        videoId: 'video-123',
        name: '  Midnight City  ',
        artist: _Artist('M83'),
        album: _Album('Hurry Up, We’re Dreaming'),
        thumbnails: <_Thumbnail>[
          _Thumbnail('https://example.com/small.jpg'),
          _Thumbnail('https://example.com/large.jpg'),
        ],
        durationText: '4:03',
      );

      final track = Track.fromYouTubeMusic(song);

      expect(track.id, 'video-123');
      expect(track.title, 'Midnight City');
      expect(track.artist, 'M83');
      expect(track.album, 'Hurry Up, We’re Dreaming');
      expect(track.thumbnailUrl, 'https://example.com/large.jpg');
      expect(track.durationText, '4:03');
    });

    test('uses safe defaults for missing metadata', () {
      final track = Track.fromYouTubeMusic(null);

      expect(track.id, isEmpty);
      expect(track.title, 'Unknown track');
      expect(track.artist, 'Unknown artist');
      expect(track.thumbnailUrl, isEmpty);
    });
  });

  test('MediaItem round trip preserves track fields', () {
    const original = Track(
      id: 'round-trip-id',
      title: 'Test Song',
      artist: 'Test Artist',
      album: 'Test Album',
      thumbnailUrl: 'https://example.com/art.jpg',
      durationText: '3:21',
    );

    final restored = Track.fromMediaItem(original.toMediaItem());

    expect(restored.id, original.id);
    expect(restored.title, original.title);
    expect(restored.artist, original.artist);
    expect(restored.album, original.album);
    expect(restored.thumbnailUrl, original.thumbnailUrl);
    expect(restored.durationText, original.durationText);
  });
}
