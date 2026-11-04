import 'package:flutter_test/flutter_test.dart';
import 'package:fluxmusic/services/safe_prefs.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('round-trips a string list', () async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    expect(await SafePrefs.setStringList('k', <String>['a', 'b']), isTrue);
    expect(await SafePrefs.getStringList('k'), <String>['a', 'b']);
  });

  test('missing keys return safe defaults', () async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    expect(await SafePrefs.getStringList('nope'), isEmpty);
    expect(await SafePrefs.getInt('nope'), isNull);
  });

  test('remove deletes the key', () async {
    SharedPreferences.setMockInitialValues(<String, Object>{'k': <String>['x']});
    await SafePrefs.remove('k');
    expect(await SafePrefs.getStringList('k'), isEmpty);
  });
}
