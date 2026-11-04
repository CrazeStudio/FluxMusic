# FluxMusic upgrade guide (stages 1 to 4)

Everything in this branch is additive except `lib/services/flux_audio_handler.dart`,
which was replaced with a backward-compatible version. The new files are not used
by `main.dart` until you make the edits below. None of this was compiled on my
side: run `flutter analyze && flutter test` on the branch before merging.
Note: the theme/appearance code uses `Color.withValues` and `toARGB32()`, which
need Flutter 3.27 or newer.

## What changed
- `lib/services/flux_audio_handler.dart`: `trackStarted` stream (history for
  auto-advanced songs), repeat mode kept when starting a new queue,
  `setSleepAtEndOfTrack`, `restoreQueue`.
- `lib/theme/flux_theme.dart`: design tokens, `FluxTheme.app()`, `FluxTheme.sheet()`, AMOLED.
- `lib/theme/appearance_controller.dart`: persisted accent + AMOLED settings.
- `lib/services/safe_prefs.dart`, `queue_store.dart`, `listening_stats.dart`,
  `autoplay_radio.dart`, `playlist_io.dart`, `music_catalog.dart`.
- `lib/widgets/`: `skeleton.dart`, `art_gradient.dart`, `search_suggestions.dart`.
- `lib/screens/collection_page.dart`: shared artist/album page.
- `test/`: tests for all of the above.

## main.dart edits
1. Imports: `services/queue_store.dart`, `services/safe_prefs.dart`, `theme/flux_theme.dart`,
   `theme/appearance_controller.dart`, `services/music_catalog.dart`,
   `services/autoplay_radio.dart`, `services/listening_stats.dart`,
   `widgets/art_gradient.dart`, `widgets/skeleton.dart`, `widgets/search_suggestions.dart`,
   `screens/collection_page.dart`.
2. `FluxMusicApp.build`: replace `final base = ThemeData(...)` with `FluxTheme.app()`; to make
   Appearance work, create `final appearance = AppearanceController()..load();` and wrap
   `MaterialApp` in `ListenableBuilder(listenable: appearance, builder: (_, __) => MaterialApp(theme: appearance.theme, ...))`.
   Add a switch (`appearance.setAmoled`) and accent circles from `AppearanceController.accents`
   to the Settings Appearance card.
3. In `AudioEnhancementsSheet`, `NowPlayingSheet` and `LyricsSheet`, replace each
   `ThemeData.dark(useMaterial3: true).copyWith(...)` with `FluxTheme.sheet()`.
4. History fix: in `_MusicHomeState` add `StreamSubscription<MediaItem>? _trackSub;` and at the end
   of `initState()`:
   `_trackSub = fluxAudio.trackStarted.listen((item) => _remember(Track.fromMediaItem(item)));`
   then cancel it in `dispose()`.
5. Safe saves: replace the bodies of `_remember`, `_saveSearchHistory` and the favourites write
   in `_toggleFavorite` with `SafePrefs.setStringList(<same key>, ...)`.
6. Resume queue: at the end of `_initialize()`:
   ```dart
   final saved = await QueueStore.load();
   if (saved != null && fluxAudio.queue.value.isEmpty) {
     await fluxAudio.restoreQueue(saved.items, index: saved.index, position: saved.position);
   }
   ```
   Save with `QueueStore.save(fluxAudio.queue.value, fluxAudio.currentQueueIndex, fluxAudio.currentPosition)`
   after `playTracks` succeeds and when the app is paused (`WidgetsBindingObserver`).
7. Sleep timer menu: add `PopupMenuItem<String>(value: 'sleep:track', child: Text('Pause at end of track'))`
   and in `onSelected`: `widget.handler.setSleepAtEndOfTrack(true);`
8. Lyrics centering: scroll target `(activeIndex * 58.0) - (viewportDimension / 2) + 29`, clamped.
9. Player look: wrap the `NowPlayingSheet` body in
   `ArtGradientBackground(artUrl: item.artUri?.toString() ?? item.extras?['thumbnailUrl'] ?? '', child: ...)`;
   wrap artwork in `Hero(tag: 'now-playing-art', ...)`; use `TrackRowSkeleton` instead of spinners;
   switch the tab body to an `IndexedStack` to keep scroll positions.
10. Catalog features:
    ```dart
    final catalog = MusicCatalog(ytmusic);
    final radio = AutoplayRadio(fluxAudio, catalog.related)..start();
    final stats = ListeningStats()..load();
    fluxAudio.trackStarted.listen((i) { stats.record(i); stats.save(); });
    ```
    Under the search field: `SearchSuggestions(query: _searchController.text, fetch: catalog.suggestions, onPick: (s) { _searchController.text = s; _runSearch(s); })`.
    Open artist/album pages with `CollectionPage(load: () => catalog.artist(id), onPlay: (tracks, index, {shuffle = false}) async { await fluxAudio.playTracks(tracks, startIndex: index); if (shuffle) await fluxAudio.toggleShuffle(); })`.
11. Playlist backup: `PlaylistIO.export(...)` / `PlaylistIO.import(...)` + `merge(...)` with your playlist storage.

## Not included
- Offline downloads (conflicts with YouTube's terms); a bounded playback cache is a possible alternative.
- Splitting `main.dart` into screens/widgets: a mechanical follow-up once the above compiles.
- If a title, artist or cover is empty on artist/album pages, adjust `MusicCatalog.toItem`.
