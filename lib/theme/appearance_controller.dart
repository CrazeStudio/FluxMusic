import 'package:flutter/material.dart';

import '../services/safe_prefs.dart';
import 'flux_theme.dart';

/// Makes the Settings > Appearance card real: accent colour + AMOLED black.
/// Wrap MaterialApp in a ListenableBuilder on this controller.
class AppearanceController extends ChangeNotifier {
  AppearanceController();

  static const accents = <String, Color>{
    'Orange': FluxColors.accent,
    'Violet': Color(0xFF9B7BFF),
    'Mint': Color(0xFF3DDC97),
    'Sky': Color(0xFF4DA3FF),
    'Rose': Color(0xFFFF6B9A),
  };
  static const _accentKey = 'fluxmusic.accent.v1';
  static const _amoledKey = 'fluxmusic.amoled.v1';

  Color _accent = FluxColors.accent;
  bool _amoled = false;

  Color get accent => _accent;
  bool get amoled => _amoled;
  ThemeData get theme => FluxTheme.app(accent: _accent, amoled: _amoled);

  Future<void> load() async {
    final accentValue = await SafePrefs.getInt(_accentKey);
    final amoledValue = await SafePrefs.getInt(_amoledKey);
    if (accentValue != null) _accent = Color(accentValue);
    _amoled = amoledValue == 1;
    notifyListeners();
  }

  Future<void> setAccent(Color color) async {
    _accent = color;
    notifyListeners();
    await SafePrefs.setInt(_accentKey, color.toARGB32());
  }

  Future<void> setAmoled(bool value) async {
    _amoled = value;
    notifyListeners();
    await SafePrefs.setInt(_amoledKey, value ? 1 : 0);
  }
}
