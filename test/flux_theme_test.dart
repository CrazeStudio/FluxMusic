import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:fluxmusic/theme/flux_theme.dart';

void main() {
  test('amoled theme uses true black', () {
    expect(FluxTheme.app(amoled: true).scaffoldBackgroundColor, FluxColors.amoled);
  });

  test('default theme is dark with the orange accent slider', () {
    final theme = FluxTheme.app();
    expect(theme.brightness, Brightness.dark);
    expect(theme.sliderTheme.activeTrackColor, FluxColors.accent);
  });

  test('sheet theme is dark Material 3', () {
    final theme = FluxTheme.sheet();
    expect(theme.useMaterial3, isTrue);
    expect(theme.brightness, Brightness.dark);
  });
}
