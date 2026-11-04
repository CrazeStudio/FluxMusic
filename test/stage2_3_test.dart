import 'package:audio_service/audio_service.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:fluxmusic/services/autoplay_radio.dart';
import 'package:fluxmusic/services/listening_stats.dart';
import 'package:fluxmusic/services/playlist_io.dart';
import 'package:fluxmusic/theme/appearance_controller.dart';
import 'package:fluxmusic/widgets/art_gradient.dart';
import 'package:shared_preferences/shared_preferences.dart';

MediaItem item(String id, [String artist = 'A']) => MediaItem(id: id, title: 't$id', artist: artist);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('stats rank artists and survive json round trip', () {
    final s = ListeningStats()..record(item('1', 'X'))..record(item('2', 'X'))..record(item('3', 'Y'));
    expect(s.topArtists().first.key, 'X');
    final copy = ListeningStats()..loadJson(s.toJson());
    expect(copy.totalPlays, 3);
    expect(copy.topArtists().first.value, 2);
  });

  test('radio skips queued tracks and caps results', () {
    final picked = AutoplayRadio.pickNew([item('1')], [item('1'), item('2'), item('2'), item('3')], max: 2);
    expect(picked.map((e) => e.id), ['2', '3']);
  });

  test('playlist export/import/merge', () {
    final raw = PlaylistIO.export({'Gym': [{'id': 'a', 'title': 'A'}]});
    final back = PlaylistIO.import(raw);
    expect(back['Gym']!.single['id'], 'a');
    expect(PlaylistIO.merge(back['Gym']!, [{'id': 'a'}, {'id': 'b'}]).length, 2);
    expect(() => PlaylistIO.import('{"x":1}'), throwsFormatException);
  });

  test('vivid average is dark enough for white text', () {
    final c = ArtPalette.averageVivid([255, 0, 0, 255, 250, 10, 5, 255]);
    expect(HSLColor.fromColor(c).lightness, lessThanOrEqualTo(0.38));
  });

  test('appearance persists accent and amoled', () async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    final a = AppearanceController();
    await a.setAmoled(true);
    await a.setAccent(const Color(0xFF3DDC97));
    final b = AppearanceController();
    await b.load();
    expect(b.amoled, isTrue);
    expect(b.accent.toARGB32(), 0xFF3DDC97);
  });
}
