import 'dart:async';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';

/// Extracts a dominant colour from album art without extra packages.
class ArtPalette {
  const ArtPalette._();
  static final Map<String, Color> _cache = <String, Color>{};

  static Future<Color?> dominant(String url) async {
    if (url.isEmpty) return null;
    final cached = _cache[url];
    if (cached != null) return cached;
    try {
      final provider = ResizeImage(NetworkImage(url), width: 24, height: 24);
      final completer = Completer<ui.Image>();
      final stream = provider.resolve(ImageConfiguration.empty);
      late final ImageStreamListener listener;
      listener = ImageStreamListener(
        (info, _) {
          if (!completer.isCompleted) completer.complete(info.image);
          stream.removeListener(listener);
        },
        onError: (Object e, StackTrace? s) {
          if (!completer.isCompleted) completer.completeError(e);
          stream.removeListener(listener);
        },
      );
      stream.addListener(listener);
      final image = await completer.future.timeout(const Duration(seconds: 8));
      final data = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
      if (data == null) return null;
      final color = averageVivid(data.buffer.asUint8List());
      if (_cache.length > 40) _cache.remove(_cache.keys.first);
      _cache[url] = color;
      return color;
    } catch (_) {
      return null;
    }
  }

  /// Saturation-weighted average of RGBA bytes, darkened for text contrast.
  static Color averageVivid(List<int> rgba) {
    double r = 0, g = 0, b = 0, total = 0;
    for (var i = 0; i + 3 < rgba.length; i += 4) {
      final pr = rgba[i] / 255, pg = rgba[i + 1] / 255, pb = rgba[i + 2] / 255;
      final maxC = [pr, pg, pb].reduce((a, c) => a > c ? a : c);
      final minC = [pr, pg, pb].reduce((a, c) => a < c ? a : c);
      final weight = 0.1 + (maxC - minC);
      r += pr * weight;
      g += pg * weight;
      b += pb * weight;
      total += weight;
    }
    if (total == 0) return const Color(0xFF121212);
    final hsl = HSLColor.fromColor(Color.fromARGB(255, (r / total * 255).round(), (g / total * 255).round(), (b / total * 255).round()));
    return hsl.withLightness((hsl.lightness * 0.55).clamp(0.12, 0.38)).toColor();
  }
}

/// Animated background that tints to the current track's artwork.
class ArtGradientBackground extends StatelessWidget {
  const ArtGradientBackground({super.key, required this.artUrl, required this.child});
  final String artUrl;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<Color?>(
      future: ArtPalette.dominant(artUrl),
      builder: (context, snapshot) {
        final color = snapshot.data ?? const Color(0xFF1A1A1A);
        return AnimatedContainer(
          duration: const Duration(milliseconds: 600),
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [color, const Color(0xFF121212)],
            ),
          ),
          child: child,
        );
      },
    );
  }
}
