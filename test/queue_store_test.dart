import 'package:audio_service/audio_service.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:fluxmusic/services/queue_store.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final item = MediaItem(
    id: 'abc123',
    title: 'Song',
    artist: 'Artist',
    duration: const Duration(minutes: 3),
    extras: const <String, dynamic>{'thumbnailUrl': 'https://x/y.jpg', 'videoId': 'abc123'},
  );

  test('item encode/decode round trip', () {
    final decoded = QueueStore.decodeItem(QueueStore.encodeItem(item))!;
    expect(decoded.id, 'abc123');
    expect(decoded.title, 'Song');
    expect(decoded.duration, const Duration(minutes: 3));
    expect(decoded.extras?['thumbnailUrl'], 'https://x/y.jpg');
  });

  test('corrupt entries are dropped', () {
    expect(QueueStore.decodeItem('not json'), isNull);
    expect(QueueStore.decodeItem('{"title":"no id"}'), isNull);
  });

  test('save then load restores queue, index and position', () async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await QueueStore.save(<MediaItem>[item, item], 1, const Duration(seconds: 42));
    final loaded = await QueueStore.load();
    expect(loaded!.items.length, 2);
    expect(loaded.index, 1);
    expect(loaded.position, const Duration(seconds: 42));
  });

  test('load returns null when nothing is saved', () async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    expect(await QueueStore.load(), isNull);
  });
}
