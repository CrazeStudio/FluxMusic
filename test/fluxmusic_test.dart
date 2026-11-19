import 'package:flutter_test/flutter_test.dart';
import 'package:fluxmusic/main.dart';

void main() {
  test('formats track duration', () {
    expect(fmt(65000), '1:05');
    expect(fmt(3661000), '1:01:01');
    expect(fmt(0), '--:--');
  });

  test('track metadata survives JSON round-trip', () {
    const track = Track(id: 'abc', title: 'Song', artist: 'Artist', album: 'Album', art: 'https://example.com/a.jpg', duration: 125000);
    expect(Track.fromJson(track.toJson()).toJson(), track.toJson());
  });
}
