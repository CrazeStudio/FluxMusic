import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:audio_service/audio_service.dart';
import 'package:dart_ytmusic_api/yt_music.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'models/track.dart';
import 'services/flux_audio_handler.dart';

late final FluxAudioHandler fluxAudio;

const _ink = Color(0xFFF5F5F5);
const _cardInk = Color(0xFF29261F);
const _panel = Color(0xFF1E1E1E);
const _mint = Color(0xFF777B43);
const _violet = Color(0xFFB9796A);
const _muted = Color(0xFFB0B0B0);
const _playerMuted = Color(0xFF9E9E9E);

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setPreferredOrientations(const <DeviceOrientation>[
    DeviceOrientation.portraitUp,
    DeviceOrientation.landscapeLeft,
    DeviceOrientation.landscapeRight,
  ]);
  fluxAudio = await AudioService.init<FluxAudioHandler>(
    builder: FluxAudioHandler.new,
    config: const AudioServiceConfig(
      androidNotificationChannelId: 'dev.crazestudio.fluxmusic.playback',
      androidNotificationChannelName: 'FluxMusic playback',
      androidNotificationChannelDescription: 'Music playback controls',
      androidNotificationOngoing: true,
      androidStopForegroundOnPause: true,
    ),
  );
  runApp(const FluxMusicApp());
}

class FluxMusicApp extends StatelessWidget {
  const FluxMusicApp({super.key});

  @override
  Widget build(BuildContext context) {
    final base = ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      scaffoldBackgroundColor: const Color(0xFF121212),
      colorScheme: ColorScheme.fromSeed(
        seedColor: _mint,
        brightness: Brightness.dark,
        surface: const Color(0xFF1E1E1E),
      ),
      textTheme: const TextTheme(
        headlineLarge: TextStyle(fontSize: 28, fontWeight: FontWeight.w800, letterSpacing: -1.2, color: _ink),
        headlineMedium: TextStyle(fontSize: 22, fontWeight: FontWeight.w800, letterSpacing: -.7, color: _ink),
        titleLarge: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: _ink),
        titleMedium: TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: _ink),
        bodyMedium: TextStyle(fontSize: 14, color: _muted),
      ),
      appBarTheme: const AppBarTheme(backgroundColor: Color(0xFF121212), foregroundColor: _ink, surfaceTintColor: Colors.transparent),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: const Color(0xFF121212),
        indicatorColor: _mint.withValues(alpha: .15),
        labelTextStyle: WidgetStateProperty.resolveWith<TextStyle?>(
          (states) => TextStyle(
            fontSize: 10,
            fontWeight: states.contains(WidgetState.selected) ? FontWeight.w800 : FontWeight.w500,
            color: states.contains(WidgetState.selected) ? _ink : _muted,
          ),
        ),
      ),
      inputDecorationTheme: const InputDecorationTheme(
        hintStyle: TextStyle(color: Color(0xFF9E9E9E), fontSize: 13),
      ),
      sliderTheme: const SliderThemeData(
        activeTrackColor: _mint,
        thumbColor: _mint,
        inactiveTrackColor: Color(0xFF444444),
        overlayColor: Color(0x22777B43),
      ),
      snackBarTheme: const SnackBarThemeData(behavior: SnackBarBehavior.floating),
    );
    return MaterialApp(
      title: 'FluxMusic',
      debugShowCheckedModeBanner: false,
      theme: base,
      home: const MusicHome(),
    );
  }
}

class MusicHome extends StatefulWidget {
  const MusicHome({super.key});

  @override
  State<MusicHome> createState() => _MusicHomeState();
}

class _MusicHomeState extends State<MusicHome> {
  final YTMusic _ytMusic = YTMusic();
  final TextEditingController _searchController = TextEditingController();

  int _tab = 0;
  int _libraryFilter = 0;
  bool _searching = false;
  String? _error;
  String _lastQuery = '';
  List<Track> _results = <Track>[];
  final Map<String, List<Track>> _searchCache = <String, List<Track>>{};
  int _searchGeneration = 0;
  List<Track> _favorites = <Track>[];
  List<Track> _recent = <Track>[];
  List<String> _searchHistory = <String>[];

  static const List<String> _moods = <String>[
    'Trending now',
    'Anime openings',
    'Lofi for focus',
    'Indian indie',
    'Arijit Singh',
    'Night drive',
  ];

  @override
  void initState() {
    super.initState();
    _initialize();
    Permission.notification.request();
  }

  Future<void> _initialize() async {
    try {
      await _ytMusic.initialize();
      final prefs = await SharedPreferences.getInstance();
      final favJson = prefs.getStringList('fluxmusic.favorites.v1') ?? <String>[];
      final recentJson = prefs.getStringList('fluxmusic.recent.v1') ?? <String>[];
      _searchHistory = prefs.getStringList('fluxmusic.search_history.v1') ?? <String>[];
      _favorites = favJson.map(_decodeTrack).whereType<Track>().toList();
      _recent = recentJson.map(_decodeTrack).whereType<Track>().toList();
      if (mounted) setState(() {});
    } catch (e) {
      if (mounted) {
        setState(() {
          _error = 'Music catalogue could not connect. Check your internet and try again.';
        });
      }
    }
  }

  String _encodeTrack(Track track) => jsonEncode(<String, dynamic>{
        'id': track.id,
        'title': track.title,
        'artist': track.artist,
        'album': track.album,
        'thumbnailUrl': track.thumbnailUrl,
        'durationText': track.durationText,
      });

  Track? _decodeTrack(String raw) {
    try {
      final value = jsonDecode(raw) as Map<String, dynamic>;
      return Track(
        id: value['id'] as String? ?? '',
        title: value['title'] as String? ?? 'Unknown track',
        artist: value['artist'] as String? ?? 'Unknown artist',
        album: value['album'] as String? ?? '',
        thumbnailUrl: value['thumbnailUrl'] as String? ?? '',
        durationText: value['durationText'] as String? ?? '',
      );
    } catch (_) {
      return null;
    }
  }

  Future<void> _search(String query) async {
    final clean = query.trim();
    if (clean.isEmpty) return;
    final searchKey = clean.toLowerCase();
    final generation = ++_searchGeneration;

    _searchController.text = clean;
    _searchHistory.removeWhere((item) => item.toLowerCase() == searchKey);
    _searchHistory.insert(0, clean);
    if (_searchHistory.length > 12) _searchHistory = _searchHistory.take(12).toList();
    unawaited(_saveSearchHistory());
    FocusScope.of(context).unfocus();
    setState(() {
      _tab = 1;
      _searching = true;
      _lastQuery = clean;
      _error = null;
    });

    final cached = _searchCache[searchKey];
    if (cached != null) {
      if (!mounted || generation != _searchGeneration) return;
      setState(() {
        _results = cached;
        _searching = false;
        _error = cached.isEmpty ? 'No songs found. Try a different search.' : null;
      });
      return;
    }

    try {
      final dynamic raw = await _ytMusic
          .searchSongs(clean)
          .timeout(const Duration(seconds: 18));
      final tracks = <Track>[];
      final seenIds = <String>{};
      if (raw is Iterable) {
        for (final item in raw) {
          final track = Track.fromYouTubeMusic(item);
          if (track.id.isNotEmpty && seenIds.add(track.id)) {
            tracks.add(track);
          }
        }
      }
      if (!mounted || generation != _searchGeneration) return;
      _searchCache[searchKey] = List<Track>.unmodifiable(tracks);
      if (_searchCache.length > 24) {
        _searchCache.remove(_searchCache.keys.first);
      }
      setState(() {
        _results = tracks;
        _searching = false;
        _error = tracks.isEmpty ? 'No songs found. Try a different search.' : null;
      });
    } catch (_) {
      if (!mounted || generation != _searchGeneration) return;
      setState(() {
        _searching = false;
        _error = 'Search took too long or the catalogue is unavailable. Check your connection and retry.';
      });
    }
  }

  bool _isFavorite(Track track) => _favorites.any((item) => item.id == track.id);

  Future<void> _toggleFavorite(Track track) async {
    setState(() {
      if (_isFavorite(track)) {
        _favorites.removeWhere((item) => item.id == track.id);
      } else {
        _favorites.insert(0, track);
      }
    });
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList(
      'fluxmusic.favorites.v1',
      _favorites.map(_encodeTrack).toList(),
    );
  }

  Future<void> _remember(Track track) async {
    _recent.removeWhere((item) => item.id == track.id);
    _recent.insert(0, track);
    if (_recent.length > 30) _recent = _recent.take(30).toList();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList('fluxmusic.recent.v1', _recent.map(_encodeTrack).toList());
    if (mounted) setState(() {});
  }


  Future<void> _saveSearchHistory() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList('fluxmusic.search_history.v1', _searchHistory);
  }

  Future<void> _clearListeningHistory() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        backgroundColor: const Color(0xFF1E1E1E),
        title: const Text('Clear listening history?'),
        content: const Text('This removes recently played tracks from this device. Your liked tracks stay saved.'),
        actions: <Widget>[
          TextButton(onPressed: () => Navigator.pop(dialogContext, false), child: const Text('Cancel')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: _mint, foregroundColor: _ink),
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('Clear history'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    _recent.clear();
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove('fluxmusic.recent.v1');
    if (mounted) setState(() {});
  }

  Future<void> _clearSearchHistory() async {
    setState(() => _searchHistory.clear());
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove('fluxmusic.search_history.v1');
  }

  Future<void> _playList(List<Track> tracks, int index) async {
    if (tracks.isEmpty || index < 0 || index >= tracks.length) return;

    // Keep stream/playback errors separate from history persistence errors.
    // A preferences failure must not be reported as a failed audio stream.
    try {
      await fluxAudio.playTracks(
        tracks.map((track) => track.toMediaItem()).toList(),
        startIndex: index,
      );
    } catch (e) {
      if (!mounted) return;
      final message = fluxAudio.lastPlaybackError ??
          'Could not start this track: $e';
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(message),
          behavior: SnackBarBehavior.floating,
          duration: const Duration(seconds: 6),
          action: SnackBarAction(
            label: 'RETRY',
            onPressed: () => _playList(tracks, index),
          ),
        ),
      );
      return;
    }

    try {
      await _remember(tracks[index]);
    } catch (_) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Track started, but listening history could not be saved.'),
          behavior: SnackBarBehavior.floating,
          duration: Duration(seconds: 3),
        ),
      );
    }
  }

  void _openPlayer() {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      useSafeArea: true,
      builder: (_) => NowPlayingSheet(
        handler: fluxAudio,
        isFavorite: (track) => _isFavorite(track),
        onToggleFavorite: _toggleFavorite,
      ),
    );
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: <Widget>[
          const _Atmosphere(),
          SafeArea(
            child: Column(
              children: <Widget>[
                _topBar(),
                Expanded(
                  child: AnimatedSwitcher(
                    duration: const Duration(milliseconds: 260),
                    switchInCurve: Curves.easeOutCubic,
                    switchOutCurve: Curves.easeInCubic,
                    child: KeyedSubtree(
                      key: ValueKey<int>(_tab),
                      child: _tab == 0
                          ? _discover()
                          : _tab == 1
                              ? _searchPage()
                              : _libraryPage(),
                    ),
                  ),
                ),
                _miniPlayer(),
                NavigationBar(
                  height: 62,
                  elevation: 0,
                  backgroundColor: const Color(0xFF121212),
                  surfaceTintColor: Colors.transparent,
                  labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
                  selectedIndex: _tab,
                  onDestinationSelected: (index) => setState(() => _tab = index),
                  destinations: const <NavigationDestination>[
                    NavigationDestination(icon: Icon(Icons.home_outlined), selectedIcon: Icon(Icons.home_rounded), label: 'Home'),
                    NavigationDestination(icon: Icon(Icons.search_rounded), label: 'Search'),
                    NavigationDestination(icon: Icon(Icons.library_music_outlined), selectedIcon: Icon(Icons.library_music_rounded), label: 'Library'),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  void _openAudioEnhancements() {
    showModalBottomSheet<void>(context: context, isScrollControlled: true, backgroundColor: Colors.transparent, useSafeArea: true, builder: (_) => const AudioEnhancementsSheet());
  }

  Widget _topBar() {
    if (_tab != 0) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 7, 20, 4),
      child: SizedBox(
        height: 42,
        child: Row(children: <Widget>[
          Container(
            width: 31, height: 31,
            decoration: BoxDecoration(color: const Color(0xFF2A2A2A), borderRadius: BorderRadius.circular(11)),
            child: const Icon(Icons.music_note_rounded, color: _mint, size: 20),
          ),
          const SizedBox(width: 9),
          const Expanded(child: Text('FluxMusic', style: TextStyle(fontSize: 23, fontWeight: FontWeight.w800, letterSpacing: -.8, color: _ink))),
          Tooltip(
            message: 'Audio settings',
            child: InkWell(
              borderRadius: BorderRadius.circular(22),
              onTap: _openAudioEnhancements,
              child: Container(
                width: 36, height: 36, alignment: Alignment.center,
                decoration: BoxDecoration(color: const Color(0xFF1E1E1E), shape: BoxShape.circle, border: Border.all(color: const Color(0xFF333333))),
                child: const Icon(Icons.tune_rounded, color: _mint, size: 19),
              ),
            ),
          ),
        ]),
      ),
    );
  }

  Widget _topBarAction({
    required IconData icon,
    required String label,
    required VoidCallback onTap,
    bool highlighted = false,
  }) {
    final tint = highlighted ? _mint : Colors.white;
    return Tooltip(
      message: label,
      child: Material(
        color: highlighted ? _mint.withValues(alpha: .10) : Colors.white.withValues(alpha: .045),
        borderRadius: BorderRadius.circular(14),
        child: InkWell(
          borderRadius: BorderRadius.circular(14),
          onTap: onTap,
          child: Container(
            width: 42,
            height: 42,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: highlighted ? _mint.withValues(alpha: .20) : Colors.white.withValues(alpha: .07)),
            ),
            child: Icon(icon, color: tint, size: 21),
          ),
        ),
      ),
    );
  }

  Widget _discover() {
    final recent = _recent.take(10).toList();
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 14, 20, 20),
      children: <Widget>[
        const Text('Good music, good mood.', style: TextStyle(fontSize: 27, height: 1.08, fontWeight: FontWeight.w800, letterSpacing: -1.0)),
        const SizedBox(height: 6),
        const Text('Find a sound that feels like you.', style: TextStyle(color: _muted, fontSize: 13)),
        const SizedBox(height: 23),
        Row(children: <Widget>[
          const Expanded(child: Text('Recently played', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w800, letterSpacing: -.4))),
          if (recent.isNotEmpty)
            TextButton(onPressed: () => setState(() { _tab = 2; _libraryFilter = 1; }), style: TextButton.styleFrom(foregroundColor: _mint), child: const Text('See all', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700))),
        ]),
        const SizedBox(height: 10),
        SizedBox(
          height: 150,
          child: recent.isNotEmpty
              ? ListView.separated(
                  scrollDirection: Axis.horizontal,
                  itemCount: recent.length,
                  separatorBuilder: (_, __) => const SizedBox(width: 12),
                  itemBuilder: (_, index) => _recentCard(recent[index], index),
                )
              : ListView(
                  scrollDirection: Axis.horizontal,
                  children: <Widget>[
                    _starterArtCard('Lo-fi sunset', 'Focus & unwind', 0, 'lofi beats focus'),
                    const SizedBox(width: 12),
                    _starterArtCard('Anime moments', 'Openings & OSTs', 1, 'anime openings soundtrack'),
                    const SizedBox(width: 12),
                    _starterArtCard('Night drive', 'Chill & ambient', 2, 'night drive chill music'),
                  ],
                ),
        ),
        const SizedBox(height: 25),
        Row(children: <Widget>[
          const Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
            Text('Recommended for you', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w800, letterSpacing: -.4)),
            SizedBox(height: 3),
            Text('Pick a mood, we’ll find the music.', style: TextStyle(color: _muted, fontSize: 11)),
          ])),
          TextButton(onPressed: () => setState(() => _tab = 1), style: TextButton.styleFrom(foregroundColor: _mint), child: const Text('Explore', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700))),
        ]),
        const SizedBox(height: 11),
        SizedBox(
          height: 142,
          child: ListView(
            scrollDirection: Axis.horizontal,
            children: <Widget>[
              _moodCard('Indie picks', 0),
              const SizedBox(width: 12),
              _moodCard('Lo-fi focus', 1),
              const SizedBox(width: 12),
              _moodCard('Night drive', 2),
              const SizedBox(width: 12),
              _moodCard('Anime OST', 3),
            ],
          ),
        ),
        const SizedBox(height: 24),
        Row(children: <Widget>[
          const Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
            Text('Browse categories', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w800, letterSpacing: -.4)),
            SizedBox(height: 3),
            Text('A soundtrack for every side of you', style: TextStyle(color: _muted, fontSize: 11)),
          ])),
          TextButton(onPressed: () => setState(() => _tab = 1), style: TextButton.styleFrom(foregroundColor: _mint), child: const Text('Browse all ↗', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700))),
        ]),
        const SizedBox(height: 10),
        GridView.count(
          crossAxisCount: 2,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          crossAxisSpacing: 10,
          mainAxisSpacing: 10,
          childAspectRatio: 2.05,
          children: <Widget>[
            _catalogueTile('Anime & OST', 'Openings and scores', const Color(0xFFD9C8E9), Icons.auto_awesome_rounded, 'anime openings'),
            _catalogueTile('Lo-fi & Focus', 'Stay in your zone', const Color(0xFFC5D5C1), Icons.headphones_rounded, 'lofi beats focus'),
            _catalogueTile('Indian Hits', 'Hindi • Bengali • more', const Color(0xFFE8C4B8), Icons.graphic_eq_rounded, 'Indian hit songs Hindi Bengali'),
            _catalogueTile('Indie Finds', 'Beyond the mainstream', const Color(0xFFC7D7E8), Icons.album_rounded, 'indie music'),
            _catalogueTile('Chill & Ambient', 'Slow down a little', const Color(0xFFD3D5B7), Icons.nightlight_round, 'chill ambient music'),
            _catalogueTile('Workout Energy', 'Turn it up', const Color(0xFFE8C4D0), Icons.bolt_rounded, 'workout upbeat music'),
          ],
        ),
        const SizedBox(height: 18),
        if (_recent.isNotEmpty) ...<Widget>[
          const Text('Your listening history', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800)),
          const SizedBox(height: 4),
          ..._recent.take(4).toList().asMap().entries.map((entry) => _recentTrackRow(entry.value, entry.key)),
        ],
        if (_error != null) Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Text(_error!, style: const TextStyle(color: _muted, fontSize: 11)),
        ),
      ],
    );
  }

  Widget _starterArtCard(String title, String subtitle, int variant, String query) {
    return SizedBox(
      width: 112,
      child: InkWell(
        borderRadius: BorderRadius.circular(15),
        onTap: () => _search(query),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
          _DecorativeArt(size: 105, variant: variant),
          const SizedBox(height: 7),
          Text(title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w800)),
          const SizedBox(height: 2),
          Text(subtitle, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 10, color: _muted)),
        ]),
      ),
    );
  }

  Widget _catalogueTile(String title, String subtitle, Color color, IconData icon, String query) {
    return Material(
      color: color.withValues(alpha: .65),
      borderRadius: BorderRadius.circular(17),
      child: InkWell(
        borderRadius: BorderRadius.circular(17),
        onTap: () => _search(query),
        child: Ink(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(17),
            border: Border.all(color: color.withValues(alpha: .95)),
          ),
          child: Stack(children: <Widget>[
            Positioned(right: 8, top: 6, child: Icon(icon, size: 39, color: _cardInk.withValues(alpha: .55))),
            Padding(
              padding: const EdgeInsets.fromLTRB(11, 9, 5, 9),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisAlignment: MainAxisAlignment.end, children: <Widget>[
                Text(title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _cardInk, fontSize: 12, fontWeight: FontWeight.w800)),
                const SizedBox(height: 3),
                Text(subtitle, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _muted, fontSize: 9)),
              ]),
            ),
          ]),
        ),
      ),
    );
  }

  Widget _quickChip(String label, String query, {bool selected = false}) {
    return Material(
      color: selected ? const Color(0xFF2A2A2A) : const Color(0xFF1E1E1E),
      borderRadius: BorderRadius.circular(18),
      child: InkWell(
        borderRadius: BorderRadius.circular(18),
        onTap: () => _search(query),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
          decoration: BoxDecoration(borderRadius: BorderRadius.circular(18), border: Border.all(color: selected ? _mint.withValues(alpha: .45) : const Color(0xFF333333))),
          child: Text(label, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: selected ? _ink : _muted)),
        ),
      ),
    );
  }

  Widget _recentTrackRow(Track track, int index) {
    return InkWell(
      borderRadius: BorderRadius.circular(14),
      onTap: () => _playList(_recent, index),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Row(children: <Widget>[
          _CoverArt(url: track.thumbnailUrl, size: 54, radius: 12),
          const SizedBox(width: 12),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
            Text(track.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13)),
            const SizedBox(height: 4),
            Text(track.artist, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _muted, fontSize: 11)),
          ])),
          IconButton(tooltip: 'Play track', visualDensity: VisualDensity.compact, onPressed: () => _playList(_recent, index), icon: const Icon(Icons.play_arrow_rounded, color: _muted, size: 22)),
        ]),
      ),
    );
  } 

  Widget _featureCard() {
    return Container(
      height: 166,
      clipBehavior: Clip.antiAlias,
      padding: const EdgeInsets.all(13),
      decoration: BoxDecoration(color: const Color(0xFF1E1E1E), borderRadius: BorderRadius.circular(20), border: Border.all(color: const Color(0xFF333333))),
      child: Row(children: <Widget>[
        const _DecorativeArt(size: 122, variant: 0),
        const SizedBox(width: 13),
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
          const Text('YOUR DAILY MIX', style: TextStyle(color: _mint, fontSize: 9, fontWeight: FontWeight.w900, letterSpacing: 1.1)),
          const Spacer(),
          const Text('A little more\nyou.', style: TextStyle(color: _ink, fontSize: 22, height: 1.08, fontWeight: FontWeight.w800, letterSpacing: -.55)),
          const SizedBox(height: 7),
          const Text('Fresh sounds for your day', maxLines: 2, style: TextStyle(color: _muted, fontSize: 10, height: 1.3)),
          const Spacer(),
          Align(alignment: Alignment.centerRight, child: Material(
            color: _mint, shape: const CircleBorder(),
            child: InkWell(customBorder: const CircleBorder(), onTap: () => _search('new music mix'), child: const SizedBox(width: 38, height: 38, child: Icon(Icons.play_arrow_rounded, color: Colors.white, size: 24))),
          )),
        ])),
      ]),
    );
  }

  Widget _moodCard(String mood, int index) {
    const palettes = <List<Color>>[
      <Color>[Color(0xFFE9D7B9), Color(0xFFDCC5A5)],
      <Color>[Color(0xFFD5DCC5), Color(0xFFBFCDAF)],
      <Color>[Color(0xFFE8D0C5), Color(0xFFDAB5A7)],
      <Color>[Color(0xFFD0DCE8), Color(0xFFB6C8DB)],
      <Color>[Color(0xFFE5D0D8), Color(0xFFD8B7C5)],
      <Color>[Color(0xFFD7D6BA), Color(0xFFC6C6A1)],
    ];
    const icons = <IconData>[
      Icons.local_fire_department_rounded,
      Icons.bolt_rounded,
      Icons.spa_rounded,
      Icons.headphones_rounded,
      Icons.mic_external_on_rounded,
      Icons.nightlight_round,
    ];
    final colors = palettes[index % palettes.length];
    final icon = icons[index % icons.length];
    return SizedBox(
      width: 154,
      child: Material(
        color: Colors.transparent,
        borderRadius: BorderRadius.circular(18),
        child: InkWell(
          borderRadius: BorderRadius.circular(18),
          onTap: () => _search(mood == 'Trending now' ? 'trending music 2026' : mood),
          child: Ink(
            decoration: BoxDecoration(
              gradient: LinearGradient(colors: colors, begin: Alignment.topLeft, end: Alignment.bottomRight),
              borderRadius: BorderRadius.circular(18),
              border: Border.all(color: colors.last),
            ),
            child: Stack(children: <Widget>[
              Positioned(right: -13, top: -19, child: Icon(icon, size: 95, color: _cardInk.withValues(alpha: .075))),
              Padding(
                padding: const EdgeInsets.all(13),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
                  Container(
                    width: 34, height: 34,
                    decoration: BoxDecoration(color: Colors.white.withValues(alpha: .6), borderRadius: BorderRadius.circular(12)),
                    child: Icon(icon, color: _cardInk.withValues(alpha: .75), size: 19),
                  ),
                  const Spacer(),
                  Text(mood, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _cardInk, fontSize: 12, fontWeight: FontWeight.w800)),
                  const SizedBox(height: 4),
                  Row(children: <Widget>[
                    const Text('PLAY MIX', style: TextStyle(fontSize: 8, color: _muted, fontWeight: FontWeight.w800, letterSpacing: 1.05)),
                    const Spacer(),
                    Icon(Icons.arrow_outward_rounded, color: _cardInk.withValues(alpha: .7), size: 13),
                  ]),
                ]),
              ),
            ]),
          ),
        ),
      ),
    );
  }

  Widget _recentCard(Track track, int index) {
    return SizedBox(
      width: 128,
      child: Material(
        color: Colors.white.withValues(alpha: .035),
        borderRadius: BorderRadius.circular(18),
        child: InkWell(
          borderRadius: BorderRadius.circular(18),
          onTap: () => _playList(_recent, index),
          child: Container(
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(18),
              border: Border.all(color: Colors.white.withValues(alpha: .065)),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Stack(
                  children: <Widget>[
                    _CoverArt(url: track.thumbnailUrl, size: 83, radius: 13),
                    Positioned(
                      right: 5,
                      bottom: 5,
                      child: Container(
                        width: 27,
                        height: 27,
                        decoration: const BoxDecoration(color: _mint, shape: BoxShape.circle),
                        child: const Icon(Icons.play_arrow_rounded, color: _ink, size: 19),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 9),
                Text(track.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 11.5)),
                const SizedBox(height: 3),
                Text(track.artist, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _muted, fontSize: 10)),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _searchPage() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 0),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
        const Text('Search', style: TextStyle(fontSize: 25, fontWeight: FontWeight.w800, letterSpacing: -.7, color: _ink)),
        const SizedBox(height: 5),
        const Text('Find artists, songs, and more.', style: TextStyle(color: _muted, fontSize: 13)),
        const SizedBox(height: 18),
        _searchBox(hint: 'Search songs, artists, albums', onSubmitted: _search),
        const SizedBox(height: 18),
        if (_lastQuery.isEmpty)
          Expanded(child: ListView(padding: const EdgeInsets.only(bottom: 16), children: <Widget>[
            const Text('Browse categories', style: TextStyle(color: _ink, fontSize: 19, fontWeight: FontWeight.w800, letterSpacing: -.3)),
            const SizedBox(height: 10),
            Wrap(spacing: 10, runSpacing: 10, children: <Widget>[
              _genreTile('Electronic', const Color(0xFFD9C8E9), 'electronic music'),
              _genreTile('Chill', const Color(0xFFC5D5C1), 'chill music'),
              _genreTile('Indie', const Color(0xFFE8C4B8), 'indie music'),
              _genreTile('Focus', const Color(0xFFD3D5B7), 'focus music'),
              _genreTile('Hip-hop', const Color(0xFFE8C4D0), 'hip hop'),
              _genreTile('Ambient', const Color(0xFFC7D7E8), 'ambient music'),
            ]),
            const SizedBox(height: 22),
            Row(children: <Widget>[
              Expanded(child: Text(_searchHistory.isEmpty ? 'Try a search' : 'Recent searches', style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w800))),
              if (_searchHistory.isNotEmpty) TextButton(onPressed: _clearSearchHistory, style: TextButton.styleFrom(foregroundColor: _mint, visualDensity: VisualDensity.compact), child: const Text('Clear', style: TextStyle(fontSize: 11))),
            ]),
            const SizedBox(height: 4),
            if (_searchHistory.isNotEmpty)
              ..._searchHistory.take(5).map((query) => ListTile(
                contentPadding: EdgeInsets.zero, dense: true, leading: const Icon(Icons.history_rounded, color: _muted, size: 19),
                title: Text(query, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 13)),
                trailing: const Icon(Icons.north_west_rounded, color: _muted, size: 16), onTap: () => _search(query),
              ))
            else
              ...<String>['After Dark', 'Lo-fi beats', 'Anime openings'].map((query) => ListTile(
                contentPadding: EdgeInsets.zero, dense: true, leading: const Icon(Icons.search_rounded, color: _muted, size: 18),
                title: Text(query, style: const TextStyle(fontSize: 13)), trailing: const Icon(Icons.north_west_rounded, color: _muted, size: 16),
                onTap: () => _search(query),
              )),
          ]))
        else
          Expanded(child: _searching
            ? const Center(child: CircularProgressIndicator(color: _mint))
            : _error != null && _results.isEmpty
              ? _ErrorState(message: _error!, onRetry: () => _search(_lastQuery))
              : _results.isEmpty
                ? const _EmptyState(icon: Icons.search_rounded, title: 'No songs found', subtitle: 'Try another song, artist, or search term.')
                : ListView.builder(
                    padding: const EdgeInsets.only(bottom: 14), itemCount: _results.length,
                    itemBuilder: (_, index) => _trackTile(_results[index], index, _results),
                  )),
      ]),
    );
  }

  Widget _genreTile(String label, Color color, String query) {
    final double tileWidth = ((MediaQuery.sizeOf(context).width - 50) / 2).clamp(140.0, 190.0).toDouble();
    return Material(
      color: color.withValues(alpha: .8),
      borderRadius: BorderRadius.circular(17),
      child: InkWell(
        borderRadius: BorderRadius.circular(17),
        onTap: () => _search(query),
        child: SizedBox(
          width: tileWidth, height: 98,
          child: Stack(children: <Widget>[
            Positioned(right: -8, top: 3, child: Icon(Icons.graphic_eq_rounded, size: 68, color: _ink.withValues(alpha: .10))),
            Positioned(right: 15, top: 12, child: Icon(Icons.album_rounded, size: 31, color: _ink.withValues(alpha: .55))),
            Positioned(left: 13, bottom: 12, child: Text(label, style: const TextStyle(color: _ink, fontSize: 15, fontWeight: FontWeight.w800))),
          ]),
        ),
      ),
    );
  }

  Widget _searchSuggestion(String label, IconData icon, String query) {
    return Material(
      color: Colors.white.withValues(alpha: .045),
      borderRadius: BorderRadius.circular(20),
      child: InkWell(
        borderRadius: BorderRadius.circular(20),
        onTap: () => _search(query),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 8),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(20),
            border: Border.all(color: Colors.white.withValues(alpha: .07)),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              Icon(icon, color: _mint, size: 14),
              const SizedBox(width: 6),
              Text(label, style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700)),
            ],
          ),
        ),
      ),
    );
  }

  Widget _libraryPage() {
    final showingRecent = _libraryFilter == 1;
    final visibleTracks = showingRecent ? _recent : _favorites;
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 18),
      children: <Widget>[
        const Text('Your library', style: TextStyle(fontSize: 25, fontWeight: FontWeight.w800, letterSpacing: -.7, color: _ink)),
        const SizedBox(height: 5),
        const Text('Your music, all together.', style: TextStyle(color: _muted, fontSize: 13)),
        const SizedBox(height: 18),
        Row(children: <Widget>[_libraryChip('Liked songs', 0), const SizedBox(width: 8), _libraryChip('Recently played', 1)]),
        const SizedBox(height: 17),
        Container(
          height: 130, clipBehavior: Clip.antiAlias, padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(color: const Color(0xFFE3D6C1), borderRadius: BorderRadius.circular(20), border: Border.all(color: const Color(0xFFD8C8AF))),
          child: Stack(children: <Widget>[
            Positioned(right: -5, top: -15, child: Icon(Icons.favorite_rounded, size: 115, color: Colors.white.withValues(alpha: .13))),
            Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
              const Text('YOUR COLLECTION', style: TextStyle(color: _muted, fontSize: 9, fontWeight: FontWeight.w900, letterSpacing: 1.1)),
              const Spacer(),
              Text(showingRecent ? 'Recently played' : 'Liked songs', style: const TextStyle(color: _ink, fontSize: 22, fontWeight: FontWeight.w800, letterSpacing: -.4)),
              const SizedBox(height: 3),
              Text('${visibleTracks.length} tracks', style: const TextStyle(color: _muted, fontSize: 11)),
            ]),
            Positioned(right: 5, bottom: 2, child: Material(
              color: _mint, shape: const CircleBorder(),
              child: InkWell(
                customBorder: const CircleBorder(), onTap: visibleTracks.isEmpty ? null : () => _playList(visibleTracks, 0),
                child: SizedBox(width: 42, height: 42, child: Icon(Icons.play_arrow_rounded, color: visibleTracks.isEmpty ? _muted : _ink, size: 25)),
              ),
            )),
          ]),
        ),
        const SizedBox(height: 22),
        Row(children: <Widget>[
          Expanded(child: Text(showingRecent ? 'Listening history' : 'Your liked tracks', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800, letterSpacing: -.2))),
          if (showingRecent && _recent.isNotEmpty)
            TextButton(onPressed: _clearListeningHistory, style: TextButton.styleFrom(foregroundColor: _mint, visualDensity: VisualDensity.compact), child: const Text('Clear', style: TextStyle(fontSize: 11))),
        ]),
        const SizedBox(height: 7),
        if (visibleTracks.isEmpty)
          _EmptyState(
            icon: showingRecent ? Icons.history_rounded : Icons.favorite_border_rounded,
            title: showingRecent ? 'Nothing played yet' : 'Your favourites start here',
            subtitle: showingRecent ? 'Play a track and it will appear here.' : 'Tap the heart on any song to save it here.',
          )
        else
          ...visibleTracks.asMap().entries.map((entry) => _trackTile(entry.value, entry.key, visibleTracks, compact: showingRecent)),
      ],
    );
  }

  Widget _libraryChip(String label, int index) {
    final selected = _libraryFilter == index;
    return Material(
      color: selected ? const Color(0xFF2A2A2A) : const Color(0xFF1E1E1E),
      borderRadius: BorderRadius.circular(18),
      child: InkWell(
        borderRadius: BorderRadius.circular(18), onTap: () => setState(() => _libraryFilter = index),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          child: Text(label, style: TextStyle(color: selected ? _ink : _muted, fontSize: 11, fontWeight: FontWeight.w800)),
        ),
      ),
    );
  }

  Widget _searchBox({required String hint, required ValueChanged<String> onSubmitted}) {
    return Container(
      decoration: BoxDecoration(
        color: const Color(0xFF1E1E1E),
        borderRadius: BorderRadius.circular(17),
        border: Border.all(color: const Color(0xFF333333)),
        boxShadow: <BoxShadow>[BoxShadow(color: const Color(0xFF000000).withValues(alpha: .09), blurRadius: 18, offset: const Offset(0, 6))],
      ),
      child: TextField(
        controller: _searchController,
        textInputAction: TextInputAction.search,
        onSubmitted: onSubmitted,
        style: const TextStyle(color: _ink, fontSize: 14, fontWeight: FontWeight.w600),
        decoration: InputDecoration(
          prefixIcon: const Icon(Icons.search_rounded, color: _mint),
          suffixIcon: IconButton(
            tooltip: 'Search',
            icon: const Icon(Icons.arrow_forward_rounded, color: _mint),
            onPressed: () => onSubmitted(_searchController.text),
          ),
          hintText: hint,
          hintStyle: const TextStyle(color: Color(0xFF9E9E9E), fontSize: 13),
          border: InputBorder.none,
          contentPadding: const EdgeInsets.symmetric(vertical: 17, horizontal: 10),
        ),
      ),
    );
  }

  Widget _sectionHeading(String title, String overline) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: <Widget>[
        Expanded(child: Text(title, style: const TextStyle(fontSize: 19, fontWeight: FontWeight.w800, letterSpacing: -.3))),
        Text(overline, style: const TextStyle(color: _muted, fontSize: 9, fontWeight: FontWeight.w800, letterSpacing: 1.3)),
      ],
    );
  }

  Widget _trackTile(Track track, int index, List<Track> source, {bool compact = false}) {
    final favorite = _isFavorite(track);
    return InkWell(
      borderRadius: BorderRadius.circular(16),
      onTap: () => _playList(source, index),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 7),
        child: Row(
          children: <Widget>[
            _CoverArt(url: track.thumbnailUrl, size: compact ? 48 : 54, radius: 13),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  Text(track.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13.5)),
                  const SizedBox(height: 4),
                  Text(
                    track.artist + (track.album.isNotEmpty ? ' · ${track.album}' : ''),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: _muted, fontSize: 11.5),
                  ),
                ],
              ),
            ),
            IconButton(
              tooltip: favorite ? 'Remove from favourites' : 'Add to favourites',
              visualDensity: VisualDensity.compact,
              onPressed: () => _toggleFavorite(track),
              icon: Icon(favorite ? Icons.favorite_rounded : Icons.favorite_border_rounded, color: favorite ? _mint : _muted, size: 20),
            ),
            if (!compact)
              IconButton(
                tooltip: 'Play track',
                visualDensity: VisualDensity.compact,
                onPressed: () => _playList(source, index),
                icon: const Icon(Icons.play_circle_fill_rounded, color: Colors.white, size: 27),
              ),
          ],
        ),
      ),
    );
  }

  Widget _miniPlayer() {
    return StreamBuilder<MediaItem?>(
      stream: fluxAudio.mediaItem,
      builder: (context, snapshot) {
        final item = snapshot.data;
        if (item == null) return const SizedBox.shrink();
        return Padding(
          padding: const EdgeInsets.fromLTRB(14, 4, 14, 7),
          child: Material(
            color: const Color(0xFF1E1E1E),
            borderRadius: BorderRadius.circular(15),
            child: InkWell(
              borderRadius: BorderRadius.circular(15), onTap: _openPlayer,
              child: Container(
                height: 55,
                padding: const EdgeInsets.symmetric(horizontal: 9),
                decoration: BoxDecoration(borderRadius: BorderRadius.circular(15), border: Border.all(color: const Color(0xFF333333))),
                child: Row(children: <Widget>[
                  _CoverArt(url: item.extras?['thumbnailUrl'] as String? ?? '', size: 39, radius: 9),
                  const SizedBox(width: 10),
                  Expanded(child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
                    Text(item.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _ink, fontSize: 12, fontWeight: FontWeight.w800)),
                    const SizedBox(height: 2),
                    Text(item.artist ?? 'Unknown artist', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _muted, fontSize: 10)),
                  ])),
                  StreamBuilder<PlaybackState>(
                    stream: fluxAudio.playbackState,
                    builder: (context, stateSnapshot) {
                      final playing = stateSnapshot.data?.playing ?? false;
                      return IconButton(
                        tooltip: playing ? 'Pause' : 'Play',
                        visualDensity: VisualDensity.compact,
                        constraints: const BoxConstraints.tightFor(width: 39, height: 42),
                        onPressed: () => playing ? fluxAudio.pause() : fluxAudio.play(),
                        icon: Icon(playing ? Icons.pause_rounded : Icons.play_arrow_rounded, color: _mint, size: 26),
                      );
                    },
                  ),
                  IconButton(
                    tooltip: 'Next track',
                    visualDensity: VisualDensity.compact,
                    constraints: const BoxConstraints.tightFor(width: 35, height: 42),
                    onPressed: () => fluxAudio.skipToNext(),
                    icon: const Icon(Icons.skip_next_rounded, color: _ink, size: 23),
                  ),
                ]),
              ),
            ),
          ),
        );
      },
    );
  }
}

class AudioEnhancementsSheet extends StatefulWidget {
  const AudioEnhancementsSheet({super.key});
  @override
  State<AudioEnhancementsSheet> createState() => _AudioEnhancementsSheetState();
}

class _AudioEnhancementsSheetState extends State<AudioEnhancementsSheet> {
  static const MethodChannel _effects = MethodChannel('dev.crazestudio.fluxmusic/audio_effects');
  bool _normalization = true;
  bool _limiter = true;
  bool _drc = false;
  bool _bassBoost = false;
  String? _note;

  Future<void> _set(String key, bool value) async {
    setState(() {
      if (key == 'normalization') _normalization = value;
      if (key == 'limiter') _limiter = value;
      if (key == 'drc') _drc = value;
      if (key == 'bassBoost') _bassBoost = value;
      _note = null;
    });
    try {
      await _effects.invokeMethod<void>('setSettings', <String, Object>{'normalization': _normalization, 'limiter': _limiter, 'drc': _drc, 'bassBoost': _bassBoost});
      if (mounted) setState(() => _note = 'Audio settings updated.');
    } catch (_) {
      if (mounted) setState(() => _note = 'Start playback to apply effects on supported Android devices.');
    }
  }

  @override
  Widget build(BuildContext context) => Theme(
    data: ThemeData.dark(useMaterial3: true).copyWith(
  colorScheme: ColorScheme.fromSeed(seedColor: _mint, brightness: Brightness.dark, surface: const Color(0xFF121212)),
  textTheme: ThemeData.dark().textTheme.apply(bodyColor: Colors.white, displayColor: Colors.white),
  sliderTheme: const SliderThemeData(activeTrackColor: _mint, thumbColor: _mint, inactiveTrackColor: Color(0xFF444444)),
),
    child: Container(
    padding: const EdgeInsets.fromLTRB(22, 12, 22, 28),
    decoration: const BoxDecoration(color: Color(0xFF121212), borderRadius: BorderRadius.vertical(top: Radius.circular(28))),
    child: Column(mainAxisSize: MainAxisSize.min, children: <Widget>[
      Container(width: 42, height: 4, decoration: BoxDecoration(color: Colors.white24, borderRadius: BorderRadius.circular(8))),
      const SizedBox(height: 22),
      const Row(children: <Widget>[
        Icon(Icons.graphic_eq_rounded, color: _mint, size: 32),
        SizedBox(width: 12),
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
          Text('Audio enhancements', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800)),
          SizedBox(height: 4),
          Text('Cleaner sound, controlled peaks.', style: TextStyle(color: _playerMuted, fontSize: 12)),
        ])),
      ]),
      const SizedBox(height: 18),
      _tile(Icons.equalizer_rounded, 'Normalization', 'Android loudness compensation; per-track LUFS matching is not available yet.', _normalization, (v) => _set('normalization', v)),
      const SizedBox(height: 9),
      _tile(Icons.shield_rounded, 'Look-ahead limiter', 'Controls sudden peaks to help prevent clipping.', _limiter, (v) => _set('limiter', v)),
      const SizedBox(height: 9),
      _tile(Icons.compress_rounded, 'Dynamic range compression', 'Makes quieter details easier to hear in noisy places.', _drc, (v) => _set('drc', v)),
      const SizedBox(height: 9),
      _tile(Icons.speaker_rounded, 'Bass boost', 'Adds weight to low frequencies for bass-forward listening.', _bassBoost, (v) => _set('bassBoost', v)),
      if (_note != null) Padding(padding: const EdgeInsets.only(top: 12), child: Text(_note!, style: const TextStyle(color: _playerMuted, fontSize: 11))),
      const SizedBox(height: 12),
      const Text('Effect support depends on Android version and device audio hardware.', textAlign: TextAlign.center, style: TextStyle(color: Color(0xFF9E9E9E), fontSize: 10)),
    ]),
  ));

  Widget _tile(IconData icon, String title, String subtitle, bool value, ValueChanged<bool> onChanged) => Container(
    padding: const EdgeInsets.fromLTRB(14, 12, 8, 12),
    decoration: BoxDecoration(color: const Color(0xFF1E1E1E), borderRadius: BorderRadius.circular(17), border: Border.all(color: Colors.white.withValues(alpha: .055))),
    child: Row(children: <Widget>[
      Icon(icon, color: _mint, size: 24),
      const SizedBox(width: 12),
      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
        Text(title, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w800)),
        const SizedBox(height: 4),
        Text(subtitle, style: const TextStyle(fontSize: 10.5, color: _playerMuted, height: 1.35)),
      ])),
      Switch.adaptive(value: value, activeColor: _mint, onChanged: onChanged),
    ]),
  );
}

class NowPlayingSheet extends StatefulWidget {
  const NowPlayingSheet({super.key, required this.handler, required this.isFavorite, required this.onToggleFavorite});
  final FluxAudioHandler handler;
  final bool Function(Track track) isFavorite;
  final Future<void> Function(Track track) onToggleFavorite;

  @override
  State<NowPlayingSheet> createState() => _NowPlayingSheetState();
}

class _NowPlayingSheetState extends State<NowPlayingSheet> {
  bool _liked = false;
  String? _likedTrackId;

  @override
  Widget build(BuildContext context) {
    return StreamBuilder<MediaItem?>(
      stream: widget.handler.mediaItem,
      builder: (context, itemSnapshot) {
        final item = itemSnapshot.data;
        if (item == null) return const SizedBox(height: 280, child: Center(child: Text('Choose a track to begin.')));
        final track = Track.fromMediaItem(item);
        if (_likedTrackId != item.id) {
          _likedTrackId = item.id;
          _liked = widget.isFavorite(track);
        }
        return StreamBuilder<PlaybackState>(
          stream: widget.handler.playbackState,
          builder: (context, playbackSnapshot) {
            final playback = playbackSnapshot.data;
            final playing = playback?.playing ?? false;
            return Theme(
              data: ThemeData.dark(useMaterial3: true).copyWith(
  colorScheme: ColorScheme.fromSeed(seedColor: _mint, brightness: Brightness.dark, surface: const Color(0xFF121212)),
  textTheme: ThemeData.dark().textTheme.apply(bodyColor: Colors.white, displayColor: Colors.white),
  sliderTheme: const SliderThemeData(activeTrackColor: _mint, thumbColor: _mint, inactiveTrackColor: Color(0xFF444444)),
),
              child: Container(
              height: MediaQuery.sizeOf(context).height * .96,
              padding: const EdgeInsets.fromLTRB(20, 9, 20, 18),
              decoration: const BoxDecoration(color: Color(0xFF121212), borderRadius: BorderRadius.vertical(top: Radius.circular(28))),
              child: Column(children: <Widget>[
                Container(width: 42, height: 4, decoration: BoxDecoration(color: Colors.white.withValues(alpha: .2), borderRadius: BorderRadius.circular(8))),
                const SizedBox(height: 10),
                Row(children: <Widget>[
                  IconButton(visualDensity: VisualDensity.compact, onPressed: () => Navigator.of(context).pop(), icon: const Icon(Icons.keyboard_arrow_down_rounded, size: 27)),
                  const Expanded(child: Text('NOW PLAYING', textAlign: TextAlign.center, style: TextStyle(color: _playerMuted, fontSize: 10, fontWeight: FontWeight.w900, letterSpacing: 1.6))),
                  PopupMenuButton<String>(
                    tooltip: 'More playback options', icon: const Icon(Icons.more_horiz_rounded, size: 24), color: const Color(0xFF252525),
                    onSelected: (value) async {
                      if (value == 'copy') {
                        await Clipboard.setData(ClipboardData(text: 'https://music.youtube.com/watch?v=${item.id}'));
                        if (!mounted) return;
                        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Track link copied'), behavior: SnackBarBehavior.floating));
                      } else if (value.startsWith('speed:')) {
                        await widget.handler.setSpeed(double.parse(value.substring(6)));
                      } else if (value.startsWith('sleep:')) {
                        final option = value.substring(6);
                        widget.handler.setSleepTimer(
                          option == 'off' ? null : Duration(minutes: int.parse(option)),
                        );
                        if (!mounted) return;
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(
                            content: Text(option == 'off'
                                ? 'Sleep timer cancelled.'
                                : 'Music will pause in $option minutes.'),
                            behavior: SnackBarBehavior.floating,
                          ),
                        );
                      }
                    },
                    itemBuilder: (_) => <PopupMenuEntry<String>>[
                      const PopupMenuItem<String>(value: 'copy', child: Text('Copy track link')),
                      const PopupMenuDivider(),
                      for (final speed in <double>[0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0])
                        PopupMenuItem<String>(value: 'speed:$speed', child: Row(children: <Widget>[
                          if ((playback?.speed ?? 1.0) == speed) const Icon(Icons.check_rounded, color: _mint, size: 16) else const SizedBox(width: 16),
                          const SizedBox(width: 8),
                          Text('${speed.toStringAsFixed(speed == speed.roundToDouble() ? 0 : 2)}× speed'),
                        ])),
                      const PopupMenuDivider(),
                      const PopupMenuItem<String>(enabled: false, child: Text('Sleep timer')),
                      const PopupMenuItem<String>(value: 'sleep:15', child: Text('Pause in 15 minutes')),
                      const PopupMenuItem<String>(value: 'sleep:30', child: Text('Pause in 30 minutes')),
                      const PopupMenuItem<String>(value: 'sleep:60', child: Text('Pause in 60 minutes')),
                      const PopupMenuItem<String>(value: 'sleep:off', child: Text('Cancel sleep timer')),
                    ],
                  ),
                ]),
                const SizedBox(height: 10),
                Expanded(child: Center(child: ConstrainedBox(
                  constraints: BoxConstraints(maxWidth: 314, maxHeight: MediaQuery.sizeOf(context).height * .39),
                  child: AspectRatio(aspectRatio: 1, child: _CoverArt(url: item.extras?['thumbnailUrl'] as String? ?? '', size: 314, radius: 16)),
                ))),
                const SizedBox(height: 8),
                Row(children: <Widget>[
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
                    Text(item.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 22, height: 1.15, fontWeight: FontWeight.w800, letterSpacing: -.35)),
                    const SizedBox(height: 5),
                    Text(item.artist ?? 'Unknown artist', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _playerMuted, fontSize: 13)),
                  ])),
                  IconButton(tooltip: _liked ? 'Remove from favourites' : 'Add to favourites', onPressed: () async {
                    await widget.onToggleFavorite(track);
                    if (mounted) setState(() => _liked = widget.isFavorite(track));
                  }, icon: Icon(_liked ? Icons.favorite_rounded : Icons.favorite_border_rounded, color: _liked ? _mint : _playerMuted, size: 25)),
                ]),
                const SizedBox(height: 8),
                StreamBuilder<Duration>(
                  stream: widget.handler.positionStream, initialData: Duration.zero,
                  builder: (context, positionSnapshot) {
                    final position = positionSnapshot.data ?? Duration.zero;
                    return StreamBuilder<Duration?>(
                      stream: widget.handler.durationStream,
                      builder: (context, durationSnapshot) {
                        final duration = durationSnapshot.data ?? Duration.zero;
                        final maxMs = duration.inMilliseconds > 0 ? duration.inMilliseconds.toDouble() : 1.0;
                        final value = position.inMilliseconds.clamp(0, maxMs.toInt()).toDouble();
                        return Column(children: <Widget>[
                          Slider(min: 0, max: maxMs, value: value, onChanged: duration == Duration.zero ? null : (next) => widget.handler.seek(Duration(milliseconds: next.round()))),
                          Padding(padding: const EdgeInsets.symmetric(horizontal: 4), child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: <Widget>[
                            Text(_formatTime(position), style: const TextStyle(color: _playerMuted, fontSize: 10)),
                            Text(_formatTime(duration), style: const TextStyle(color: _playerMuted, fontSize: 10)),
                          ])),
                        ]);
                      },
                    );
                  },
                ),
                if (playback?.errorMessage != null) ...<Widget>[
                  Padding(padding: const EdgeInsets.only(top: 4), child: Row(children: <Widget>[
                    Expanded(child: Text(playback!.errorMessage!, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(color: Color(0xFFFFA7B5), fontSize: 10))),
                    TextButton(onPressed: () => widget.handler.play(), child: const Text('Retry', style: TextStyle(color: _mint, fontSize: 11))),
                  ])),
                ],
                const SizedBox(height: 7),
                Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: <Widget>[
                  IconButton(tooltip: widget.handler.shuffleEnabled ? 'Turn shuffle off' : 'Shuffle queue', onPressed: () async { await widget.handler.toggleShuffle(); if (mounted) setState(() {}); }, icon: Icon(Icons.shuffle_rounded, color: widget.handler.shuffleEnabled ? _mint : _playerMuted, size: 21)),
                  IconButton(onPressed: () => widget.handler.skipToPrevious(), icon: const Icon(Icons.skip_previous_rounded, size: 29)),
                  Container(width: 62, height: 62, decoration: const BoxDecoration(shape: BoxShape.circle, color: _mint), child: IconButton(onPressed: () => playing ? widget.handler.pause() : widget.handler.play(), icon: Icon(playing ? Icons.pause_rounded : Icons.play_arrow_rounded, color: _ink, size: 34))),
                  IconButton(onPressed: () => widget.handler.skipToNext(), icon: const Icon(Icons.skip_next_rounded, size: 29)),
                  IconButton(tooltip: widget.handler.repeatEnabled ? 'Turn repeat off' : 'Repeat current track', onPressed: () async { await widget.handler.toggleRepeat(); if (mounted) setState(() {}); }, icon: Icon(Icons.repeat_one_rounded, color: widget.handler.repeatEnabled ? _mint : _playerMuted, size: 21)),
                ]),
                const SizedBox(height: 8),
                InkWell(
                  borderRadius: BorderRadius.circular(14),
                  onTap: () => showModalBottomSheet<void>(context: context, isScrollControlled: true, useSafeArea: true, backgroundColor: Colors.transparent, builder: (_) => LyricsSheet(item: item)),
                  child: Container(height: 60, padding: const EdgeInsets.symmetric(horizontal: 14), decoration: BoxDecoration(color: const Color(0xFF1E1E1E), borderRadius: BorderRadius.circular(14)),
                    child: const Row(children: <Widget>[
                      Icon(Icons.lyrics_rounded, color: _mint, size: 21), SizedBox(width: 12),
                      Expanded(child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
                        Text('View lyrics', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w800)),
                        SizedBox(height: 3), Text('Follow along with the words', style: TextStyle(color: _playerMuted, fontSize: 10)),
                      ])),
                      Icon(Icons.chevron_right_rounded, color: _playerMuted, size: 22),
                    ]),
                  ),
                ),
                const SizedBox(height: 8),
                InkWell(
                  borderRadius: BorderRadius.circular(13),
                  onTap: () {
                    final items = widget.handler.queue.value;
                    showModalBottomSheet<void>(
                      context: context, isScrollControlled: true, useSafeArea: true, backgroundColor: Colors.transparent,
                      builder: (sheetContext) => Container(
                        height: MediaQuery.sizeOf(sheetContext).height * .7,
                        padding: const EdgeInsets.fromLTRB(18, 12, 18, 16),
                        decoration: const BoxDecoration(color: Color(0xFF121212), borderRadius: BorderRadius.vertical(top: Radius.circular(24))),
                        child: Column(children: <Widget>[
                          Container(width: 42, height: 4, decoration: BoxDecoration(color: Colors.white24, borderRadius: BorderRadius.circular(8))),
                          const SizedBox(height: 16),
                          const Align(alignment: Alignment.centerLeft, child: Text('Up next', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800))),
                          const SizedBox(height: 8),
                          Expanded(child: ListView.builder(itemCount: items.length, itemBuilder: (_, index) {
                            final queued = items[index];
                            final current = queued.id == item.id;
                            return ListTile(contentPadding: EdgeInsets.zero,
                              leading: _CoverArt(url: queued.extras?['thumbnailUrl'] as String? ?? '', size: 46, radius: 9),
                              title: Text(queued.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 13, fontWeight: current ? FontWeight.w800 : FontWeight.w500)),
                              subtitle: Text(queued.artist ?? 'Unknown artist', maxLines: 1, style: const TextStyle(color: _playerMuted, fontSize: 11)),
                              trailing: current ? const Icon(Icons.equalizer_rounded, color: _mint) : null,
                              onTap: () { Navigator.of(sheetContext).pop(); widget.handler.skipToQueueItem(index); },
                            );
                          })),
                        ]),
                      ),
                    );
                  },
                  child: Container(height: 50, padding: const EdgeInsets.symmetric(horizontal: 14), decoration: BoxDecoration(color: const Color(0xFF1E1E1E), borderRadius: BorderRadius.circular(13)),
                    child: Row(children: <Widget>[
                      const Icon(Icons.queue_music_rounded, color: _playerMuted, size: 20), const SizedBox(width: 12),
                      const Expanded(child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
                        Text('Up next', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w800)),
                        SizedBox(height: 2), Text('Your playback queue', style: TextStyle(color: _playerMuted, fontSize: 10)),
                      ])),
                      Text('${widget.handler.queue.value.length} songs', style: const TextStyle(color: _playerMuted, fontSize: 10)),
                      const SizedBox(width: 8), const Icon(Icons.chevron_right_rounded, color: _playerMuted, size: 20),
                    ]),
                  ),
                ),
              ]),
            ));
          },
        );
      },
    );
  }
}

class LyricsSheet extends StatefulWidget {
  const LyricsSheet({super.key, required this.item});
  final MediaItem item;

  @override
  State<LyricsSheet> createState() => _LyricsSheetState();
}

class _LyricsSheetState extends State<LyricsSheet> {
  bool _loading = true;
  String? _lyrics;
  String? _message;
  bool _synced = false;

  @override
  void initState() {
    super.initState();
    _loadLyrics();
  }

  Future<void> _loadLyrics() async {
    setState(() {
      _loading = true;
      _lyrics = null;
      _message = null;
    });

    final client = HttpClient()..connectionTimeout = const Duration(seconds: 10);
    var receivedResponse = false;

    String cleanTitle(String value) => value
        .replaceAll(
          RegExp(r'\s*[\[(](?:official|lyrics?|lyric video|music video|audio|video|visualizer|4k|hd)[^\])]*[\])]', caseSensitive: false),
          '',
        )
        .replaceFirst(
          RegExp(r'\s*[-|–]\s*(?:official audio|official video|lyrics?|lyric video|music video|visualizer).*$', caseSensitive: false),
          '',
        )
        .trim();

    String normalise(String value) => value
        .toLowerCase()
        .replaceAll(RegExp(r'\[[^\]]*\]|\([^)]*\)'), ' ')
        // Keep non-Latin titles searchable; remove ASCII punctuation without stripping Unicode letters.
        .replaceAll(RegExp(r'[\u0000-\u002F\u003A-\u0040\u005B-\u0060\u007B-\u007E]'), ' ')
        .replaceAll(RegExp(r'\s+'), ' ')
        .trim();

    Future<Object?> fetchJson(Uri uri) async {
      final request = await client.getUrl(uri).timeout(const Duration(seconds: 10));
      request.headers.set(HttpHeaders.userAgentHeader, 'FluxMusic/1.1 (https://github.com/CrazeStudio/FluxMusic)');
      request.headers.set(HttpHeaders.acceptHeader, 'application/json');
      final response = await request.close().timeout(const Duration(seconds: 12));
      final body = await response.transform(utf8.decoder).join();
      receivedResponse = true;
      if (response.statusCode == 404) return null;
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw HttpException('Lyrics provider returned HTTP ' + response.statusCode.toString());
      }
      return jsonDecode(body);
    }

    bool hasLyrics(Map<String, dynamic> row) {
      final synced = row['syncedLyrics'] ?? row['synced_lyrics'];
      final plain = row['plainLyrics'] ?? row['plain_lyrics'];
      return (synced is String && synced.trim().isNotEmpty) ||
          (plain is String && plain.trim().isNotEmpty);
    }

    Map<String, dynamic>? bestSearchMatch(Object? payload, String title, String artist) {
      if (payload is! List) return null;
      final titleKey = normalise(title);
      final artistKey = normalise(artist);
      final rows = payload
          .whereType<Map>()
          .map((row) => Map<String, dynamic>.from(row))
          .where(hasLyrics)
          .toList();
      if (rows.isEmpty) return null;

      int score(Map<String, dynamic> row) {
        final rowTitle = normalise((row['trackName'] ?? row['name'] ?? row['track_name'] ?? '').toString());
        final rowArtist = normalise((row['artistName'] ?? row['artist_name'] ?? '').toString());
        var value = 0;
        if (titleKey.isNotEmpty && rowTitle.isNotEmpty && rowTitle == titleKey) {
          value += 100;
        } else if (titleKey.isNotEmpty && rowTitle.isNotEmpty && (rowTitle.contains(titleKey) || titleKey.contains(rowTitle))) {
          value += 35;
        }
        if (artistKey.isNotEmpty && rowArtist == artistKey) {
          value += 60;
        } else if (artistKey.isNotEmpty &&
            (rowArtist.contains(artistKey) || artistKey.contains(rowArtist))) {
          value += 25;
        }
        final targetDuration = (widget.item.duration ?? Duration.zero).inSeconds;
        final rowDuration = row['duration'];
        if (targetDuration > 0 && rowDuration is num) {
          final difference = (rowDuration - targetDuration).abs();
          if (difference <= 5) value += 25;
          else if (difference <= 15) value += 10;
        }
        return value;
      }

      rows.sort((first, second) => score(second).compareTo(score(first)));
      return rows.first;
    }

    Map<String, dynamic>? asLyricsRecord(Object? payload) {
      if (payload is Map) {
        final row = Map<String, dynamic>.from(payload);
        return hasLyrics(row) ? row : null;
      }
      return null;
    }

    try {
      final title = cleanTitle(widget.item.title);
      final rawArtist = widget.item.artist ?? '';
      final artist = rawArtist.replaceFirst(RegExp(r'\s*-\s*Topic$', caseSensitive: false), '').trim();
      Map<String, dynamic>? record;
      final duration = (widget.item.duration ?? Duration.zero).inSeconds;

      // Try the strict metadata lookup first, then fall back to fuzzy search.
      try {
        final exactPayload = await fetchJson(Uri.https('lrclib.net', '/api/get', <String, String>{
          'track_name': title,
          if (artist.isNotEmpty) 'artist_name': artist,
          if (duration > 0) 'duration': duration.toString(),
        }));
        record = asLyricsRecord(exactPayload);
      } catch (_) {
        // Continue to search if exact metadata does not match.
      }

      if (record == null) {
        final searches = <Map<String, String>>[
          <String, String>{
            'track_name': title,
            if (artist.isNotEmpty) 'artist_name': artist,
          },
          if (artist.isNotEmpty) <String, String>{'q': '$title $artist'},
          <String, String>{'track_name': title},
        ];

        for (final query in searches) {
          try {
            final payload = await fetchJson(Uri.https('lrclib.net', '/api/search', query));
            record = bestSearchMatch(payload, title, artist);
            if (record != null) break;
          } catch (_) {
            // Keep trying alternate query forms.
          }
        }
      }

      if (!mounted) return;
      final synced = record?['syncedLyrics'] ?? record?['synced_lyrics'];
      final plain = record?['plainLyrics'] ?? record?['plain_lyrics'];
      final lyrics = synced is String && synced.trim().isNotEmpty
          ? synced
          : (plain is String ? plain : null);
      setState(() {
        _synced = synced is String && synced.trim().isNotEmpty;
        _lyrics = lyrics;
        _message = lyrics == null || lyrics.trim().isEmpty
            ? (receivedResponse
                ? 'No matching lyrics were found. Try another version of this track.'
                : 'Could not reach the lyrics service. Check your connection and try again.')
            : null;
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _message = 'Could not reach the lyrics service. Check your connection and try again.';
      });
    } finally {
      client.close(force: true);
    }
  }
  @override
  Widget build(BuildContext context) {
    return Theme(
      data: ThemeData.dark(useMaterial3: true).copyWith(
  colorScheme: ColorScheme.fromSeed(seedColor: _mint, brightness: Brightness.dark, surface: const Color(0xFF121212)),
  textTheme: ThemeData.dark().textTheme.apply(bodyColor: Colors.white, displayColor: Colors.white),
  sliderTheme: const SliderThemeData(activeTrackColor: _mint, thumbColor: _mint, inactiveTrackColor: Color(0xFF444444)),
),
      child: Container(
      height: MediaQuery.sizeOf(context).height * .78,
      padding: const EdgeInsets.fromLTRB(22, 12, 22, 24),
      decoration: const BoxDecoration(
        color: Color(0xFF0D1120),
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
      child: Column(
        children: <Widget>[
          Container(width: 42, height: 4, decoration: BoxDecoration(color: Colors.white24, borderRadius: BorderRadius.circular(8))),
          const SizedBox(height: 18),
          Row(
            children: <Widget>[
              const Icon(Icons.lyrics_rounded, color: _mint, size: 25),
              const SizedBox(width: 10),
              const Expanded(child: Text('Lyrics', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800))),
              IconButton(onPressed: _loadLyrics, tooltip: 'Reload lyrics', icon: const Icon(Icons.refresh_rounded)),
              IconButton(onPressed: () => Navigator.of(context).pop(), tooltip: 'Close lyrics', icon: const Icon(Icons.close_rounded)),
            ],
          ),
          const SizedBox(height: 5),
          Align(
            alignment: Alignment.centerLeft,
            child: Text(
              '${widget.item.title} · ${widget.item.artist ?? 'Unknown artist'}',
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(color: _playerMuted, fontSize: 12),
            ),
          ),
          const SizedBox(height: 16),
          Expanded(
            child: _loading
                ? const Center(child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: <Widget>[
                      CircularProgressIndicator(color: _mint),
                      SizedBox(height: 14),
                      Text('Finding lyrics…', style: TextStyle(color: _playerMuted)),
                    ],
                  ))
                : _lyrics == null || _lyrics!.trim().isEmpty
                    ? Center(child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: <Widget>[
                          const Icon(Icons.music_off_rounded, color: _playerMuted, size: 36),
                          const SizedBox(height: 12),
                          Text(_message ?? 'Lyrics not found', textAlign: TextAlign.center, style: const TextStyle(color: _playerMuted)),
                          const SizedBox(height: 12),
                          FilledButton.tonalIcon(onPressed: _loadLyrics, icon: const Icon(Icons.refresh_rounded), label: const Text('Try again')),
                        ],
                      ))
                    : ListView(
                        children: <Widget>[
                          if (_synced)
                            const Padding(
                              padding: EdgeInsets.only(bottom: 10),
                              child: Text('Time-stamped lyrics available · displayed as text', style: TextStyle(color: _mint, fontSize: 11)),
                            ),
                          SelectableText(
                            _lyrics!.replaceAll(RegExp(r'\[\d{2}:\d{2}(?:\.\d+)?\]'), '').trim(),
                            style: const TextStyle(fontSize: 15, height: 1.7, fontWeight: FontWeight.w500),
                          ),
                          const SizedBox(height: 20),
                          const Text('Lyrics provided by LRCLIB. Availability and accuracy may vary.', style: TextStyle(color: _playerMuted, fontSize: 10)),
                        ],
                      ),
          ),
        ],
      ),
    ));
  }
}


String _formatTime(Duration duration) {
  final minutes = duration.inMinutes.remainder(60).toString().padLeft(2, '0');
  final seconds = duration.inSeconds.remainder(60).toString().padLeft(2, '0');
  final hours = duration.inHours;
  return hours > 0 ? '$hours:$minutes:$seconds' : '$minutes:$seconds';
}

class _DecorativeArt extends StatelessWidget {
  const _DecorativeArt({required this.size, required this.variant});
  final double size;
  final int variant;
  @override
  Widget build(BuildContext context) {
    final bases = <Color>[const Color(0xFFD9C8E9), const Color(0xFFE8C4B8), const Color(0xFFC5D5C1), const Color(0xFFC7D7E8)];
    final accents = <Color>[const Color(0xFFB9796A), const Color(0xFF777B43), const Color(0xFF6C927E), const Color(0xFF6C83A1)];
    final base = bases[variant % bases.length];
    final accent = accents[variant % accents.length];
    return Container(
      width: size, height: size, clipBehavior: Clip.antiAlias,
      decoration: BoxDecoration(borderRadius: BorderRadius.circular(14), gradient: LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight, colors: <Color>[base, Color.lerp(base, Colors.white, .4)!])),
      child: Stack(children: <Widget>[
        Positioned(right: -size * .04, top: size * .08, child: Container(width: size * .68, height: size * .68, decoration: BoxDecoration(shape: BoxShape.circle, color: accent.withValues(alpha: .88)))),
        Positioned(left: size * .10, bottom: size * .17, child: Container(width: size * .80, height: size * .14, decoration: BoxDecoration(color: const Color(0xFFC7F545), borderRadius: BorderRadius.circular(size * .08)))),
        Positioned(left: size * .18, top: size * .13, child: Container(width: size * .18, height: size * .18, decoration: const BoxDecoration(shape: BoxShape.circle, color: Color(0xFF121212)))),
        Positioned(right: size * .17, bottom: size * .12, child: Container(width: size * .13, height: size * .13, decoration: BoxDecoration(shape: BoxShape.circle, color: Colors.white.withValues(alpha: .72)))),
      ]),
    );
  }
}

class _CoverArt extends StatelessWidget {
  const _CoverArt({required this.url, required this.size, required this.radius});

  final String url;
  final double size;
  final double radius;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(radius),
        gradient: const LinearGradient(colors: <Color>[Color(0xFF333D60), Color(0xFF24203E)], begin: Alignment.topLeft, end: Alignment.bottomRight),
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(radius),
        child: url.startsWith('http')
            ? Image.network(
                url,
                fit: BoxFit.cover,
                width: size,
                height: size,
                errorBuilder: (_, __, ___) => _artFallback(),
              )
            : _artFallback(),
      ),
    );
  }

  Widget _artFallback() => SvgPicture.asset(
        'assets/brand/fluxmusic.svg',
        fit: BoxFit.cover,
      );
}

class _Atmosphere extends StatelessWidget {
  const _Atmosphere();

  @override
  Widget build(BuildContext context) {
    return const Positioned.fill(
      child: IgnorePointer(
        child: DecoratedBox(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: <Color>[
                Color(0xFFF8F4EA),
                Color(0xFFF2EBDD),
                Color(0xFFFBF8F0),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Glow extends StatelessWidget {
  const _Glow({required this.size, required this.color});

  final double size;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
        width: size,
        height: size,
        decoration: BoxDecoration(shape: BoxShape.circle, boxShadow: <BoxShadow>[BoxShadow(color: color, blurRadius: 90, spreadRadius: 20)]),
      );
}

class _EmptyState extends StatelessWidget {
  const _EmptyState({required this.icon, required this.title, required this.subtitle});

  final IconData icon;
  final String title;
  final String subtitle;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 28, horizontal: 12),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          Container(
            width: 62,
            height: 62,
            decoration: BoxDecoration(shape: BoxShape.circle, color: Colors.white.withValues(alpha: .045), border: Border.all(color: Colors.white.withValues(alpha: .06))),
            child: Icon(icon, color: _mint, size: 27),
          ),
          const SizedBox(height: 15),
          Text(title, textAlign: TextAlign.center, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w800)),
          const SizedBox(height: 7),
          Text(subtitle, textAlign: TextAlign.center, style: const TextStyle(color: _muted, height: 1.45, fontSize: 12)),
        ],
      ),
    );
  }
}

class _ErrorState extends StatelessWidget {
  const _ErrorState({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            const Icon(Icons.wifi_off_rounded, color: _violet, size: 36),
            const SizedBox(height: 14),
            Text(message, textAlign: TextAlign.center, style: const TextStyle(color: _muted, height: 1.5)),
            const SizedBox(height: 16),
            FilledButton.icon(
              onPressed: onRetry,
              style: FilledButton.styleFrom(backgroundColor: _mint, foregroundColor: _ink),
              icon: const Icon(Icons.refresh_rounded),
              label: const Text('Try again'),
            ),
          ],
        ),
      ),
    );
  }
}
