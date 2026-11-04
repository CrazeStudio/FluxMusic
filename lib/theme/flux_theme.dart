import 'package:flutter/material.dart';

/// Central design tokens. Replaces the hardcoded colours and the three
/// copy-pasted ThemeData blocks in main.dart.
class FluxColors {
  const FluxColors._();
  static const ink = Color(0xFFF5F5F5);
  static const accent = Color(0xFFFF8A35); // was `_mint` (it is orange)
  static const accentSoft = Color(0xFFFFB16D); // was `_violet`
  static const muted = Color(0xFFB8B8B8);
  static const playerMuted = Color(0xFFA3A3A3);
  static const surface = Color(0xFF1E1E1E);
  static const background = Color(0xFF121212);
  static const amoled = Color(0xFF000000);
  static const outline = Color(0xFF333333);
  static const danger = Color(0xFFFFA7B5);
}

class FluxTheme {
  const FluxTheme._();

  /// App theme. Pass [amoled] for a true-black background.
  static ThemeData app({Color accent = FluxColors.accent, bool amoled = false}) {
    final bg = amoled ? FluxColors.amoled : FluxColors.background;
    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      scaffoldBackgroundColor: bg,
      colorScheme: ColorScheme.fromSeed(
        seedColor: accent,
        brightness: Brightness.dark,
        surface: FluxColors.surface,
      ),
      textTheme: const TextTheme(
        headlineLarge: TextStyle(fontSize: 28, fontWeight: FontWeight.w800, letterSpacing: -1.2, color: FluxColors.ink),
        headlineMedium: TextStyle(fontSize: 22, fontWeight: FontWeight.w800, letterSpacing: -.7, color: FluxColors.ink),
        titleLarge: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: FluxColors.ink),
        titleMedium: TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: FluxColors.ink),
        bodyMedium: TextStyle(fontSize: 14, color: FluxColors.muted),
      ),
      appBarTheme: AppBarTheme(backgroundColor: bg, foregroundColor: FluxColors.ink, surfaceTintColor: Colors.transparent),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: bg,
        indicatorColor: accent.withValues(alpha: .15),
        labelTextStyle: WidgetStateProperty.resolveWith<TextStyle?>(
          (states) => TextStyle(
            fontSize: 10,
            fontWeight: states.contains(WidgetState.selected) ? FontWeight.w800 : FontWeight.w500,
            color: states.contains(WidgetState.selected) ? FluxColors.ink : FluxColors.muted,
          ),
        ),
      ),
      inputDecorationTheme: const InputDecorationTheme(
        hintStyle: TextStyle(color: Color(0xFF9E9E9E), fontSize: 13),
      ),
      sliderTheme: SliderThemeData(
        activeTrackColor: accent,
        thumbColor: accent,
        inactiveTrackColor: const Color(0xFF444444),
      ),
      snackBarTheme: const SnackBarThemeData(behavior: SnackBarBehavior.floating),
    );
  }

  /// One theme for NowPlayingSheet, LyricsSheet and AudioEnhancementsSheet.
  static ThemeData sheet({Color accent = FluxColors.accent}) {
    final base = ThemeData.dark(useMaterial3: true);
    return base.copyWith(
      colorScheme: ColorScheme.fromSeed(
        seedColor: accent,
        brightness: Brightness.dark,
        surface: FluxColors.background,
      ),
      textTheme: base.textTheme.apply(bodyColor: Colors.white, displayColor: Colors.white),
      sliderTheme: SliderThemeData(
        activeTrackColor: accent,
        thumbColor: accent,
        inactiveTrackColor: const Color(0xFF444444),
      ),
    );
  }
}
