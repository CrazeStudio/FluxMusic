import 'package:shared_preferences/shared_preferences.dart';

/// SharedPreferences wrapper that never throws. Every write returns whether it
/// succeeded so callers can show a message instead of crashing.
class SafePrefs {
  const SafePrefs._();

  static Future<bool> setStringList(String key, List<String> value) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      return await prefs.setStringList(key, value);
    } catch (_) {
      return false;
    }
  }

  static Future<bool> setString(String key, String value) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      return await prefs.setString(key, value);
    } catch (_) {
      return false;
    }
  }

  static Future<bool> setInt(String key, int value) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      return await prefs.setInt(key, value);
    } catch (_) {
      return false;
    }
  }

  static Future<bool> remove(String key) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      return await prefs.remove(key);
    } catch (_) {
      return false;
    }
  }

  static Future<List<String>> getStringList(String key) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      return prefs.getStringList(key) ?? <String>[];
    } catch (_) {
      return <String>[];
    }
  }

  static Future<int?> getInt(String key) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      return prefs.getInt(key);
    } catch (_) {
      return null;
    }
  }
}
