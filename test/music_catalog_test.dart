import 'package:flutter_test/flutter_test.dart';
import 'package:fluxmusic/services/music_catalog.dart';

class _Artist { final String name; _Artist(this.name); }
class _Thumb { final String url; _Thumb(this.url); }
class _Song {
  final String? videoId = 'vid1';
  final String name = 'Hello';
  final _Artist artist = _Artist('Adele');
  final int duration = 295;
  final List<_Thumb> thumbnails = [_Thumb('s.jpg'), _Thumb('big.jpg')];
}
class _UpNext {
  final String videoId = 'vid2';
  final String title = 'Next';
  final List<_Artist> artists = [_Artist('A'), _Artist('B')];
}
class _Broken {}

void main() {
  test('maps a song-like object', () {
    final m = MusicCatalog.toItem(_Song())!;
    expect(m.id, 'vid1');
    expect(m.title, 'Hello');
    expect(m.artist, 'Adele');
    expect(m.duration, const Duration(seconds: 295));
    expect(m.extras?['thumbnailUrl'], 'big.jpg');
  });

  test('maps up-next shape (title + artists list)', () {
    final m = MusicCatalog.toItem(_UpNext())!;
    expect(m.title, 'Next');
    expect(m.artist, 'A, B');
  });

  test('objects without a video id are skipped, never thrown', () {
    expect(MusicCatalog.toItem(_Broken()), isNull);
    expect(MusicCatalog.toItem(null), isNull);
  });
}
