import 'dart:convert';

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

const _ink = Color(0xFF080B16);
const _panel = Color(0xFF13182A);
const _mint = Color(0xFF62E6B6);
const _violet = Color(0xFF9A7BFF);
const _muted = Color(0xFF9DA5BC);

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
      scaffoldBackgroundColor: _ink,
      colorScheme: ColorScheme.fromSeed(
        seedColor: _mint,
        brightness: Brightness.dark,
        surface: _ink,
      ),
      textTheme: const TextTheme(
        headlineLarge: TextStyle(fontSize: 33, fontWeight: FontWeight.w800, letterSpacing: -1.4),
        headlineMedium: TextStyle(fontSize: 25, fontWeight: FontWeight.w800, letterSpacing: -0.8),
        titleLarge: TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
        titleMedium: TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
        bodyMedium: TextStyle(fontSize: 14, color: _muted),
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: const Color(0xFF0C1020),
        indicatorColor: _mint.withValues(alpha: .15),
        labelTextStyle: WidgetStateProperty.resolveWith<TextStyle?>(
          (states) => TextStyle(
            fontSize: 11,
            fontWeight: states.contains(WidgetState.selected) ? FontWeight.w700 : FontWeight.w500,
            color: states.contains(WidgetState.selected) ? _mint : _muted,
          ),
        ),
      ),
      sliderTheme: const SliderThemeData(
        activeTrackColor: _mint,
        thumbColor: _mint,
        inactiveTrackColor: Color(0xFF343A4D),
        overlayColor: Color(0x22B9F65A),
      ),
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
  bool _searching = false;
  String? _error;
  String _lastQuery = '';
  List<Track> _results = <Track>[];
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
    _searchController.text = clean;
    _searchHistory.removeWhere((item) => item.toLowerCase() == clean.toLowerCase());
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
    try {
      final dynamic raw = await _ytMusic.searchSongs(clean);
      final tracks = <Track>[];
      if (raw is Iterable) {
        for (final item in raw) {
          final track = Track.fromYouTubeMusic(item);
          if (track.id.isNotEmpty && !tracks.any((t) => t.id == track.id)) {
            tracks.add(track);
          }
        }
      }
      if (!mounted) return;
      setState(() {
        _results = tracks;
        _searching = false;
        _error = tracks.isEmpty ? 'No songs found. Try a different search.' : null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _searching = false;
        _error = 'Search failed. The catalogue may be temporarily unavailable.';
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
        backgroundColor: const Color(0xFF171C2D),
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
                  height: 70,
                  elevation: 0,
                  backgroundColor: const Color(0xFF0A0D17),
                  surfaceTintColor: Colors.transparent,
                  labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
                  selectedIndex: _tab,
                  onDestinationSelected: (index) => setState(() => _tab = index),
                  destinations: const <NavigationDestination>[
                    NavigationDestination(icon: Icon(Icons.blur_on_rounded), selectedIcon: Icon(Icons.blur_on_rounded), label: 'Discover'),
                    NavigationDestination(icon: Icon(Icons.search_rounded), label: 'Search'),
                    NavigationDestination(icon: Icon(Icons.favorite_border_rounded), selectedIcon: Icon(Icons.favorite_rounded), label: 'Library'),
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
    return Padding(
      padding: const EdgeInsets.fromLTRB(18, 10, 18, 12),
      child: Row(
        children: <Widget>[
          Container(
            width: 46,
            height: 46,
            padding: const EdgeInsets.all(1.2),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(16),
              gradient: const LinearGradient(
                colors: <Color>[_mint, _violet],
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
              ),
              boxShadow: <BoxShadow>[
                BoxShadow(color: _mint.withValues(alpha: .12), blurRadius: 20, offset: const Offset(0, 5)),
              ],
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(15),
              child: SvgPicture.asset('assets/brand/fluxmusic.svg', fit: BoxFit.cover),
            ),
          ),
          const SizedBox(width: 10),
          const Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: <Widget>[
                Text('FluxMusic', style: TextStyle(fontSize: 21, fontWeight: FontWeight.w900, letterSpacing: -.9)),
                SizedBox(height: 3),
                Row(
                  children: <Widget>[
                    Icon(Icons.graphic_eq_rounded, size: 12, color: _mint),
                    SizedBox(width: 5),
                    Flexible(
                      child: Text(
                        'YOUR SOUND. NO LIMITS.',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(fontSize: 8, fontWeight: FontWeight.w900, color: _muted, letterSpacing: 1.05),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          _topBarAction(
            icon: Icons.tune_rounded,
            label: 'Audio enhancements',
            highlighted: true,
            onTap: _openAudioEnhancements,
          ),
          const SizedBox(width: 7),
          _topBarAction(
            icon: Icons.search_rounded,
            label: 'Search music',
            onTap: () => setState(() => _tab = 1),
          ),
        ],
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
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 4, 20, 26),
      children: <Widget>[
        Row(
          children: <Widget>[
            Container(
              width: 7,
              height: 7,
              decoration: BoxDecoration(
                color: _mint,
                shape: BoxShape.circle,
                boxShadow: <BoxShadow>[BoxShadow(color: _mint.withValues(alpha: .55), blurRadius: 9)],
              ),
            ),
            const SizedBox(width: 8),
            const Text('YOUR PERSONAL SOUNDTRACK', style: TextStyle(fontSize: 9, color: _mint, fontWeight: FontWeight.w900, letterSpacing: 1.7)),
            const Spacer(),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
              decoration: BoxDecoration(
                color: Colors.white.withValues(alpha: .045),
                borderRadius: BorderRadius.circular(20),
                border: Border.all(color: Colors.white.withValues(alpha: .07)),
              ),
              child: const Text('DISCOVER', style: TextStyle(fontSize: 8, color: _muted, fontWeight: FontWeight.w800, letterSpacing: 1.1)),
            ),
          ],
        ),
        const SizedBox(height: 13),
        const Text(
          'Find your\nfrequency.',
          style: TextStyle(fontSize: 38, height: 1.03, fontWeight: FontWeight.w900, letterSpacing: -1.8),
        ),
        const SizedBox(height: 10),
        const Text('The right sound can change the whole moment.', style: TextStyle(color: _muted, fontSize: 13, height: 1.45)),
        const SizedBox(height: 21),
        _searchBox(hint: 'Songs, artists, albums...', onSubmitted: _search),
        const SizedBox(height: 20),
        _featureCard(),
        const SizedBox(height: 28),
        _sectionHeading('Made for your mood', 'QUICK PICKS'),
        const SizedBox(height: 13),
        SizedBox(
          height: 126,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            itemCount: _moods.length,
            separatorBuilder: (_, __) => const SizedBox(width: 10),
            itemBuilder: (_, index) => _moodCard(_moods[index], index),
          ),
        ),
        const SizedBox(height: 28),
        if (_recent.isNotEmpty) ...<Widget>[
          Row(
            children: <Widget>[
              const Expanded(child: Text('Pick up where you left off', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w800, letterSpacing: -.3))),
              TextButton.icon(
                onPressed: _clearListeningHistory,
                icon: const Icon(Icons.delete_sweep_rounded, size: 16),
                label: const Text('Clear'),
                style: TextButton.styleFrom(foregroundColor: _muted, visualDensity: VisualDensity.compact),
              ),
            ],
          ),
          const SizedBox(height: 13),
          SizedBox(
            height: 164,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              itemCount: _recent.take(8).length,
              separatorBuilder: (_, __) => const SizedBox(width: 12),
              itemBuilder: (_, index) => _recentCard(_recent[index], index),
            ),
          ),
        ] else ...<Widget>[
          const _EmptyState(
            icon: Icons.surround_sound_rounded,
            title: 'Your next favourite is out there',
            subtitle: 'Search for a song or pick a mood to start your soundtrack.',
          ),
        ],
        const SizedBox(height: 10),
      ],
    );
  }

  Widget _featureCard() {
    return Container(
      height: 228,
      clipBehavior: Clip.antiAlias,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(27),
        gradient: const LinearGradient(
          colors: <Color>[Color(0xFF183A37), Color(0xFF172B32), Color(0xFF211D38)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        border: Border.all(color: Colors.white.withValues(alpha: .09)),
        boxShadow: <BoxShadow>[
          BoxShadow(color: _mint.withValues(alpha: .075), blurRadius: 30, offset: const Offset(0, 13)),
        ],
      ),
      child: Stack(
        children: <Widget>[
          Positioned(
            right: -44,
            bottom: -52,
            child: Container(
              width: 242,
              height: 242,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: RadialGradient(
                  colors: <Color>[_mint.withValues(alpha: .2), _violet.withValues(alpha: .07), Colors.transparent],
                ),
              ),
            ),
          ),
          Positioned(
            right: -21,
            bottom: -29,
            child: Opacity(
              opacity: .83,
              child: SvgPicture.asset(
                'assets/brand/fluxmusic_foreground.svg',
                width: 220,
                height: 220,
                fit: BoxFit.contain,
              ),
            ),
          ),
          Positioned(
            right: 16,
            top: 16,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              decoration: BoxDecoration(
                color: Colors.black.withValues(alpha: .22),
                borderRadius: BorderRadius.circular(30),
                border: Border.all(color: Colors.white.withValues(alpha: .12)),
              ),
              child: const Row(
                mainAxisSize: MainAxisSize.min,
                children: <Widget>[
                  Icon(Icons.auto_awesome_rounded, color: _mint, size: 11),
                  SizedBox(width: 5),
                  Text('FLUX SELECTS', style: TextStyle(fontSize: 8, fontWeight: FontWeight.w900, letterSpacing: 1.15, color: Colors.white)),
                ],
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(19, 20, 126, 18),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Container(
                  width: 36,
                  height: 3,
                  decoration: BoxDecoration(color: _mint, borderRadius: BorderRadius.circular(10)),
                ),
                const Spacer(),
                const Text(
                  'Your next\nobsession.',
                  style: TextStyle(fontSize: 27, height: 1.02, fontWeight: FontWeight.w900, letterSpacing: -1.1),
                ),
                const SizedBox(height: 8),
                const Text('Fresh sounds for wherever life takes you.', style: TextStyle(color: Color(0xFFB5C7CB), fontSize: 11, height: 1.35)),
                const Spacer(),
                FilledButton.icon(
                  onPressed: () => _search('new music mix'),
                  style: FilledButton.styleFrom(
                    backgroundColor: _mint,
                    foregroundColor: const Color(0xFF07140F),
                    minimumSize: const Size(0, 39),
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(13)),
                    textStyle: const TextStyle(fontWeight: FontWeight.w900, fontSize: 10.5),
                  ),
                  icon: const Icon(Icons.play_arrow_rounded, size: 18),
                  label: const Text('Explore music'),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _moodCard(String mood, int index) {
    const palettes = <List<Color>>[
      <Color>[Color(0xFF25453B), Color(0xFF142823)],
      <Color>[Color(0xFF3C2858), Color(0xFF211731)],
      <Color>[Color(0xFF493326), Color(0xFF282018)],
      <Color>[Color(0xFF1E3D50), Color(0xFF142833)],
      <Color>[Color(0xFF493044), Color(0xFF281C2C)],
      <Color>[Color(0xFF283452), Color(0xFF171F35)],
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
    return SizedBox(
      width: 154,
      child: Material(
        color: Colors.transparent,
        borderRadius: BorderRadius.circular(20),
        child: InkWell(
          borderRadius: BorderRadius.circular(20),
          onTap: () => _search(mood == 'Trending now' ? 'trending music 2026' : mood),
          child: Ink(
            decoration: BoxDecoration(
              gradient: LinearGradient(colors: colors, begin: Alignment.topLeft, end: Alignment.bottomRight),
              borderRadius: BorderRadius.circular(20),
              border: Border.all(color: Colors.white.withValues(alpha: .075)),
            ),
            child: Stack(
              children: <Widget>[
                Positioned(
                  right: -22,
                  top: -26,
                  child: Icon(icons[index % icons.length], size: 95, color: Colors.white.withValues(alpha: .055)),
                ),
                Padding(
                  padding: const EdgeInsets.all(13),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Container(
                        width: 34,
                        height: 34,
                        decoration: BoxDecoration(
                          color: Colors.white.withValues(alpha: .09),
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: Icon(icons[index % icons.length], color: index == 0 ? _mint : const Color(0xFFE0D3FF), size: 19),
                      ),
                      const Spacer(),
                      Text(mood, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w800)),
                      const SizedBox(height: 4),
                      Row(
                        children: <Widget>[
                          const Text('PLAY MIX', style: TextStyle(fontSize: 8, color: Color(0xFFB8BDCB), fontWeight: FontWeight.w800, letterSpacing: 1.05)),
                          const Spacer(),
                          Icon(Icons.arrow_outward_rounded, color: Colors.white.withValues(alpha: .8), size: 13),
                        ],
                      ),
                    ],
                  ),
                ),
              ],
            ),
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
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Padding(
          padding: const EdgeInsets.fromLTRB(18, 5, 18, 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Row(
                children: <Widget>[
                  Container(
                    width: 6,
                    height: 6,
                    decoration: const BoxDecoration(color: _mint, shape: BoxShape.circle),
                  ),
                  const SizedBox(width: 7),
                  const Text(
                    'YOUR NEXT FAVOURITE',
                    style: TextStyle(color: _mint, fontSize: 9, fontWeight: FontWeight.w900, letterSpacing: 1.6),
                  ),
                  const Spacer(),
                  const Icon(Icons.graphic_eq_rounded, color: _muted, size: 17),
                ],
              ),
              const SizedBox(height: 10),
              const Text('Search the\nsoundtrack.', style: TextStyle(fontSize: 31, height: 1.02, fontWeight: FontWeight.w900, letterSpacing: -1.25)),
              const SizedBox(height: 8),
              const Text('Songs, artists and sounds on your mind.', style: TextStyle(color: _muted, fontSize: 12.5)),
              const SizedBox(height: 16),
              _searchBox(hint: 'Search songs or artists', onSubmitted: _search),
              const SizedBox(height: 12),
              if (_lastQuery.isNotEmpty)
                Text(
                  'RESULTS FOR  “${_lastQuery.toUpperCase()}”',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(color: _muted, fontSize: 10, letterSpacing: 1.2, fontWeight: FontWeight.w800),
                )
              else
                Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    if (_searchHistory.isNotEmpty) ...<Widget>[
                      Row(
                        children: <Widget>[
                          const Expanded(child: Text('RECENT SEARCHES', style: TextStyle(color: _muted, fontSize: 9, letterSpacing: 1.2, fontWeight: FontWeight.w800))),
                          TextButton(
                            onPressed: _clearSearchHistory,
                            style: TextButton.styleFrom(foregroundColor: _muted, visualDensity: VisualDensity.compact, padding: const EdgeInsets.symmetric(horizontal: 7)),
                            child: const Text('Clear'),
                          ),
                        ],
                      ),
                      Wrap(
                        spacing: 7,
                        runSpacing: 7,
                        children: _searchHistory.take(4).map((query) => ActionChip(
                          avatar: const Icon(Icons.history_rounded, size: 14, color: _mint),
                          label: Text(query, maxLines: 1, overflow: TextOverflow.ellipsis),
                          onPressed: () => _search(query),
                          backgroundColor: const Color(0xFF171C2D),
                          side: BorderSide(color: Colors.white.withValues(alpha: .07)),
                          labelStyle: const TextStyle(color: Colors.white, fontSize: 11),
                        )).toList(),
                      ),
                      const SizedBox(height: 9),
                    ],
                    SingleChildScrollView(
                      scrollDirection: Axis.horizontal,
                      child: Row(
                        children: <Widget>[
                          _searchSuggestion('Trending', Icons.local_fire_department_rounded, 'trending music 2026'),
                          const SizedBox(width: 8),
                          _searchSuggestion('Anime', Icons.bolt_rounded, 'anime openings'),
                          const SizedBox(width: 8),
                          _searchSuggestion('Lofi', Icons.spa_rounded, 'lofi for focus'),
                        ],
                      ),
                    ),
                  ],
                ),
            ],
          ),
        ),
        Expanded(
          child: _searching
              ? const Center(child: CircularProgressIndicator(color: _mint))
              : _error != null && _results.isEmpty
                  ? _ErrorState(message: _error!, onRetry: () => _search(_lastQuery))
                  : _results.isEmpty
                      ? const _EmptyState(
                          icon: Icons.search_rounded,
                          title: 'The whole world of music',
                          subtitle: 'Search a track, an artist, or a lyric you remember.',
                        )
                      : ListView.builder(
                          padding: const EdgeInsets.fromLTRB(18, 0, 18, 14),
                          itemCount: _results.length,
                          itemBuilder: (_, index) => _trackTile(_results[index], index, _results),
                        ),
        ),
      ],
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
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 4, 20, 20),
      children: <Widget>[
        Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: <Widget>[
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  const Text('Your library', style: TextStyle(fontSize: 30, fontWeight: FontWeight.w900, letterSpacing: -1.1)),
                  const SizedBox(height: 6),
                  const Text('The tracks you keep close.', style: TextStyle(color: _muted, fontSize: 12.5)),
                ],
              ),
            ),
            Container(
              width: 48,
              height: 48,
              decoration: BoxDecoration(
                color: _mint.withValues(alpha: .09),
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: _mint.withValues(alpha: .17)),
              ),
              child: const Icon(Icons.library_music_rounded, color: _mint, size: 24),
            ),
          ],
        ),
        const SizedBox(height: 22),
        Container(
          padding: const EdgeInsets.all(17),
          decoration: BoxDecoration(
            gradient: const LinearGradient(colors: <Color>[Color(0xFF20243E), Color(0xFF17272D)]),
            borderRadius: BorderRadius.circular(22),
            border: Border.all(color: Colors.white.withValues(alpha: .07)),
          ),
          child: Row(
            children: <Widget>[
              Container(
                width: 54,
                height: 54,
                decoration: BoxDecoration(color: _mint.withValues(alpha: .13), borderRadius: BorderRadius.circular(16)),
                child: const Icon(Icons.favorite_rounded, color: _mint, size: 26),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    const Text('Liked tracks', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w800)),
                    Text('${_favorites.length} songs saved on this device', style: const TextStyle(fontSize: 12, color: _muted)),
                  ],
                ),
              ),
              IconButton(
                onPressed: _favorites.isEmpty ? null : () => _playList(_favorites, 0),
                icon: const Icon(Icons.play_circle_fill_rounded, color: _mint, size: 35),
              ),
            ],
          ),
        ),
        const SizedBox(height: 25),
        _sectionHeading('Your favourites', 'SAVED LOCALLY'),
        const SizedBox(height: 12),
        if (_favorites.isEmpty)
          const _EmptyState(
            icon: Icons.favorite_border_rounded,
            title: 'Keep the songs that get you',
            subtitle: 'Tap the heart on any result to save it here.',
          )
        else
          ..._favorites.asMap().entries.map((entry) => _trackTile(entry.value, entry.key, _favorites)),
        if (_recent.isNotEmpty) ...<Widget>[
          const SizedBox(height: 24),
          Row(
            children: <Widget>[
              const Expanded(child: Text('Listening history', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w800, letterSpacing: -.3))),
              TextButton.icon(
                onPressed: _clearListeningHistory,
                icon: const Icon(Icons.delete_sweep_rounded, size: 16),
                label: const Text('Clear history'),
                style: TextButton.styleFrom(foregroundColor: _muted, visualDensity: VisualDensity.compact),
              ),
            ],
          ),
          const SizedBox(height: 10),
          ..._recent.take(8).toList().asMap().entries.map((entry) => _trackTile(entry.value, entry.key, _recent, compact: true)),
        ],
      ],
    );
  }

  Widget _searchBox({required String hint, required ValueChanged<String> onSubmitted}) {
    return Container(
      decoration: BoxDecoration(
        color: const Color(0xFF171C2D),
        borderRadius: BorderRadius.circular(17),
        border: Border.all(color: Colors.white.withValues(alpha: .08)),
        boxShadow: <BoxShadow>[BoxShadow(color: Colors.black.withValues(alpha: .12), blurRadius: 18, offset: const Offset(0, 7))],
      ),
      child: TextField(
        controller: _searchController,
        textInputAction: TextInputAction.search,
        onSubmitted: onSubmitted,
        style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
        decoration: InputDecoration(
          prefixIcon: const Icon(Icons.search_rounded, color: _mint),
          suffixIcon: IconButton(
            tooltip: 'Search',
            icon: const Icon(Icons.arrow_forward_rounded, color: _mint),
            onPressed: () => onSubmitted(_searchController.text),
          ),
          hintText: hint,
          hintStyle: const TextStyle(color: Color(0xFF737C96), fontSize: 13),
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
          padding: const EdgeInsets.fromLTRB(12, 4, 12, 8),
          child: Material(
            color: Colors.transparent,
            borderRadius: BorderRadius.circular(19),
            child: InkWell(
              borderRadius: BorderRadius.circular(19),
              onTap: _openPlayer,
              child: Container(
                padding: const EdgeInsets.fromLTRB(9, 8, 9, 7),
                decoration: BoxDecoration(
                  gradient: const LinearGradient(
                    colors: <Color>[Color(0xFF1C2A2A), Color(0xFF171E2C), Color(0xFF1C1B2C)],
                    begin: Alignment.centerLeft,
                    end: Alignment.centerRight,
                  ),
                  borderRadius: BorderRadius.circular(19),
                  border: Border.all(color: _mint.withValues(alpha: .19)),
                  boxShadow: <BoxShadow>[BoxShadow(color: Colors.black.withValues(alpha: .16), blurRadius: 18, offset: const Offset(0, 6))],
                ),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: <Widget>[
                    Row(
                      children: <Widget>[
                        _CoverArt(url: item.extras?['thumbnailUrl'] as String? ?? '', size: 44, radius: 11),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: <Widget>[
                              Text(item.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w800)),
                              const SizedBox(height: 3),
                              Text(item.artist ?? 'Unknown artist', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _muted, fontSize: 10.5)),
                            ],
                          ),
                        ),
                        StreamBuilder<PlaybackState>(
                          stream: fluxAudio.playbackState,
                          builder: (context, stateSnapshot) {
                            final playing = stateSnapshot.data?.playing ?? false;
                            return IconButton(
                              visualDensity: VisualDensity.compact,
                              onPressed: () => playing ? fluxAudio.pause() : fluxAudio.play(),
                              icon: Icon(playing ? Icons.pause_rounded : Icons.play_arrow_rounded, color: _mint, size: 28),
                            );
                          },
                        ),
                        IconButton(
                          visualDensity: VisualDensity.compact,
                          onPressed: () => fluxAudio.skipToNext(),
                          icon: const Icon(Icons.skip_next_rounded, color: Colors.white, size: 25),
                        ),
                      ],
                    ),
                    const SizedBox(height: 6),
                    ClipRRect(
                      borderRadius: BorderRadius.circular(5),
                      child: StreamBuilder<Duration>(
                        stream: fluxAudio.positionStream,
                        initialData: Duration.zero,
                        builder: (context, positionSnapshot) {
                          return StreamBuilder<Duration?>(
                            stream: fluxAudio.durationStream,
                            builder: (context, durationSnapshot) {
                              final position = positionSnapshot.data ?? Duration.zero;
                              final duration = durationSnapshot.data ?? Duration.zero;
                              final progress = duration.inMilliseconds > 0
                                  ? (position.inMilliseconds / duration.inMilliseconds).clamp(0.0, 1.0).toDouble()
                                  : 0.0;
                              return LinearProgressIndicator(
                                value: progress,
                                minHeight: 2,
                                backgroundColor: Colors.white.withValues(alpha: .09),
                                valueColor: const AlwaysStoppedAnimation<Color>(_mint),
                              );
                            },
                          );
                        },
                      ),
                    ),
                  ],
                ),
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
  String? _note;

  Future<void> _set(String key, bool value) async {
    setState(() {
      if (key == 'normalization') _normalization = value;
      if (key == 'limiter') _limiter = value;
      if (key == 'drc') _drc = value;
      _note = null;
    });
    try {
      await _effects.invokeMethod<void>('setSettings', <String, Object>{'normalization': _normalization, 'limiter': _limiter, 'drc': _drc});
      if (mounted) setState(() => _note = 'Audio settings updated.');
    } catch (_) {
      if (mounted) setState(() => _note = 'Start playback to apply effects on supported Android devices.');
    }
  }

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.fromLTRB(22, 12, 22, 28),
    decoration: const BoxDecoration(color: Color(0xFF101526), borderRadius: BorderRadius.vertical(top: Radius.circular(28))),
    child: Column(mainAxisSize: MainAxisSize.min, children: <Widget>[
      Container(width: 42, height: 4, decoration: BoxDecoration(color: Colors.white24, borderRadius: BorderRadius.circular(8))),
      const SizedBox(height: 22),
      const Row(children: <Widget>[
        Icon(Icons.graphic_eq_rounded, color: _mint, size: 32),
        SizedBox(width: 12),
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
          Text('Audio enhancements', style: TextStyle(fontSize: 22, fontWeight: FontWeight.w900)),
          SizedBox(height: 4),
          Text('Cleaner sound, controlled peaks.', style: TextStyle(color: _muted, fontSize: 12)),
        ])),
      ]),
      const SizedBox(height: 18),
      _tile(Icons.equalizer_rounded, 'Normalization', 'Android loudness compensation; per-track LUFS matching is not available yet.', _normalization, (v) => _set('normalization', v)),
      const SizedBox(height: 9),
      _tile(Icons.shield_rounded, 'Look-ahead limiter', 'Controls sudden peaks to help prevent clipping.', _limiter, (v) => _set('limiter', v)),
      const SizedBox(height: 9),
      _tile(Icons.compress_rounded, 'Dynamic range compression', 'Makes quieter details easier to hear in noisy places.', _drc, (v) => _set('drc', v)),
      if (_note != null) Padding(padding: const EdgeInsets.only(top: 12), child: Text(_note!, style: const TextStyle(color: _muted, fontSize: 11))),
      const SizedBox(height: 12),
      const Text('Effect support depends on Android version and device audio hardware.', textAlign: TextAlign.center, style: TextStyle(color: Color(0xFF737C96), fontSize: 10)),
    ]),
  );

  Widget _tile(IconData icon, String title, String subtitle, bool value, ValueChanged<bool> onChanged) => Container(
    padding: const EdgeInsets.fromLTRB(14, 12, 8, 12),
    decoration: BoxDecoration(color: const Color(0xFF191F32), borderRadius: BorderRadius.circular(17), border: Border.all(color: Colors.white.withValues(alpha: .055))),
    child: Row(children: <Widget>[
      Icon(icon, color: _mint, size: 24),
      const SizedBox(width: 12),
      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
        Text(title, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w800)),
        const SizedBox(height: 4),
        Text(subtitle, style: const TextStyle(fontSize: 10.5, color: _muted, height: 1.35)),
      ])),
      Switch.adaptive(value: value, activeColor: _mint, onChanged: onChanged),
    ]),
  );
}

class NowPlayingSheet extends StatefulWidget {
  const NowPlayingSheet({
    super.key,
    required this.handler,
    required this.isFavorite,
    required this.onToggleFavorite,
  });

  final FluxAudioHandler handler;
  final bool Function(Track track) isFavorite;
  final Future<void> Function(Track track) onToggleFavorite;

  @override
  State<NowPlayingSheet> createState() => _NowPlayingSheetState();
}

class _NowPlayingSheetState extends State<NowPlayingSheet> with SingleTickerProviderStateMixin {
  late final AnimationController _spin = AnimationController(vsync: this, duration: const Duration(seconds: 18))..repeat();
  bool _liked = false;
  String? _likedTrackId;

  @override
  void dispose() {
    _spin.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return StreamBuilder<MediaItem?>(
      stream: widget.handler.mediaItem,
      builder: (context, itemSnapshot) {
        final item = itemSnapshot.data;
        if (item == null) {
          return const SizedBox(height: 280, child: Center(child: Text('Choose a track to begin.')));
        }
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
            if (playing && !_spin.isAnimating) _spin.repeat();
            if (!playing && _spin.isAnimating) _spin.stop();

            return Container(
              height: MediaQuery.sizeOf(context).height * .91,
              padding: const EdgeInsets.fromLTRB(24, 12, 24, 25),
              decoration: const BoxDecoration(
                color: Color(0xFF0D1120),
                borderRadius: BorderRadius.vertical(top: Radius.circular(30)),
              ),
              child: Column(
                children: <Widget>[
                  Container(width: 44, height: 4, decoration: BoxDecoration(color: Colors.white.withValues(alpha: .2), borderRadius: BorderRadius.circular(8))),
                  const SizedBox(height: 20),
                  Row(
                    children: <Widget>[
                      IconButton(onPressed: () => Navigator.of(context).pop(), icon: const Icon(Icons.keyboard_arrow_down_rounded, size: 29)),
                      const Expanded(child: Column(children: <Widget>[
                        Text('NOW PLAYING', style: TextStyle(fontSize: 10, fontWeight: FontWeight.w900, letterSpacing: 2, color: _mint)),
                        SizedBox(height: 4),
                        Text('FluxMusic session', style: TextStyle(fontSize: 12, color: _muted)),
                      ])),
                      IconButton(
                        tooltip: 'Copy YouTube Music link',
                        onPressed: () async {
                          await Clipboard.setData(ClipboardData(text: 'https://music.youtube.com/watch?v=${item.id}'));
                          if (!mounted) return;
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(content: Text('Track link copied'), behavior: SnackBarBehavior.floating),
                          );
                        },
                        icon: const Icon(Icons.more_horiz_rounded, size: 27),
                      ),
                    ],
                  ),
                  const Spacer(),
                  RotationTransition(
                    turns: _spin,
                    child: Container(
                      width: MediaQuery.sizeOf(context).width * .62,
                      constraints: const BoxConstraints(maxWidth: 300, maxHeight: 300),
                      child: AspectRatio(
                        aspectRatio: 1,
                        child: Stack(
                          alignment: Alignment.center,
                          children: <Widget>[
                            Container(
                              decoration: BoxDecoration(
                                shape: BoxShape.circle,
                                gradient: const RadialGradient(colors: <Color>[Color(0xFF2A3050), Color(0xFF101525), Color(0xFF20263C)]),
                                boxShadow: <BoxShadow>[BoxShadow(color: _violet.withValues(alpha: .18), blurRadius: 38, spreadRadius: 2)],
                                border: Border.all(color: Colors.white.withValues(alpha: .1), width: 2),
                              ),
                            ),
                            ...List<Widget>.generate(6, (index) => Container(
                                  width: 54.0 + index * 33,
                                  height: 54.0 + index * 33,
                                  decoration: BoxDecoration(shape: BoxShape.circle, border: Border.all(color: Colors.white.withValues(alpha: .055), width: 1)),
                                )),
                            ClipOval(
                              child: SizedBox(
                                width: 155,
                                height: 155,
                                child: _CoverArt(url: item.extras?['thumbnailUrl'] as String? ?? '', size: 155, radius: 100),
                              ),
                            ),
                            Container(width: 14, height: 14, decoration: const BoxDecoration(shape: BoxShape.circle, color: _ink)),
                          ],
                        ),
                      ),
                    ),
                  ),
                  const Spacer(),
                  Row(
                    children: <Widget>[
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: <Widget>[
                            Text(item.title, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 23, height: 1.1, fontWeight: FontWeight.w900, letterSpacing: -.6)),
                            const SizedBox(height: 8),
                            Text(item.artist ?? 'Unknown artist', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _muted, fontSize: 14)),
                          ],
                        ),
                      ),
                      IconButton(
                        tooltip: _liked ? 'Remove from favourites' : 'Add to favourites',
                        onPressed: () async {
                          await widget.onToggleFavorite(track);
                          if (mounted) setState(() => _liked = !_liked);
                        },
                        icon: Icon(_liked ? Icons.favorite_rounded : Icons.favorite_border_rounded, color: _liked ? _mint : _muted, size: 27),
                      ),
                    ],
                  ),
                  const SizedBox(height: 20),
                  StreamBuilder<Duration>(
                    stream: widget.handler.positionStream,
                    initialData: Duration.zero,
                    builder: (context, positionSnapshot) {
                      final position = positionSnapshot.data ?? Duration.zero;
                      return StreamBuilder<Duration?>(
                        stream: widget.handler.durationStream,
                        builder: (context, durationSnapshot) {
                          final duration = durationSnapshot.data ?? Duration.zero;
                          final maxMs = duration.inMilliseconds > 0 ? duration.inMilliseconds.toDouble() : 1.0;
                          final value = position.inMilliseconds.clamp(0, maxMs.toInt()).toDouble();
                          return Column(
                            children: <Widget>[
                              Slider(
                                min: 0,
                                max: maxMs,
                                value: value,
                                onChanged: duration == Duration.zero
                                    ? null
                                    : (next) => widget.handler.seek(Duration(milliseconds: next.round())),
                              ),
                              Padding(
                                padding: const EdgeInsets.symmetric(horizontal: 4),
                                child: Row(
                                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                  children: <Widget>[
                                    Text(_formatTime(position), style: const TextStyle(color: _muted, fontSize: 10)),
                                    Text(_formatTime(duration), style: const TextStyle(color: _muted, fontSize: 10)),
                                  ],
                                ),
                              ),
                            ],
                          );
                        },
                      );
                    },
                  ),
                  if (playback?.errorMessage != null) ...<Widget>[
                    Container(
                      width: double.infinity,
                      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
                      decoration: BoxDecoration(
                        color: const Color(0xFF3A1D2A),
                        borderRadius: BorderRadius.circular(14),
                        border: Border.all(color: const Color(0xFF804055)),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: <Widget>[
                          Text(
                            playback!.errorMessage!,
                            maxLines: 3,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(color: Color(0xFFFFC4D0), fontSize: 11.5, height: 1.35),
                          ),
                          Align(
                            alignment: Alignment.centerRight,
                            child: TextButton.icon(
                              onPressed: () => widget.handler.play(),
                              icon: const Icon(Icons.refresh_rounded, size: 17),
                              label: const Text('Retry audio'),
                              style: TextButton.styleFrom(foregroundColor: _mint),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                  const SizedBox(height: 18),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                    children: <Widget>[
                      IconButton(
                        tooltip: widget.handler.shuffleEnabled ? 'Turn shuffle off' : 'Shuffle queue',
                        onPressed: () async {
                          await widget.handler.toggleShuffle();
                          if (mounted) setState(() {});
                        },
                        icon: Icon(
                          Icons.shuffle_rounded,
                          color: widget.handler.shuffleEnabled ? _mint : _muted,
                          size: 23,
                        ),
                      ),
                      IconButton(onPressed: () => widget.handler.skipToPrevious(), icon: const Icon(Icons.skip_previous_rounded, size: 37)),
                      Container(
                        width: 68,
                        height: 68,
                        decoration: BoxDecoration(shape: BoxShape.circle, color: _mint, boxShadow: <BoxShadow>[BoxShadow(color: _mint.withValues(alpha: .2), blurRadius: 25)]),
                        child: IconButton(
                          onPressed: () => playing ? widget.handler.pause() : widget.handler.play(),
                          icon: Icon(playing ? Icons.pause_rounded : Icons.play_arrow_rounded, color: _ink, size: 37),
                        ),
                      ),
                      IconButton(onPressed: () => widget.handler.skipToNext(), icon: const Icon(Icons.skip_next_rounded, size: 37)),
                      IconButton(onPressed: () => widget.handler.stop(), icon: const Icon(Icons.stop_rounded, color: _muted, size: 23)),
                    ],
                  ),
                  const SizedBox(height: 12),
                  const Text('Audio controls stay available from your notification and lock screen.', textAlign: TextAlign.center, style: TextStyle(color: Color(0xFF69728B), fontSize: 10)),
                ],
              ),
            );
          },
        );
      },
    );
  }
}

String _formatTime(Duration duration) {
  final minutes = duration.inMinutes.remainder(60).toString().padLeft(2, '0');
  final seconds = duration.inSeconds.remainder(60).toString().padLeft(2, '0');
  final hours = duration.inHours;
  return hours > 0 ? '$hours:$minutes:$seconds' : '$minutes:$seconds';
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
    return Positioned.fill(
      child: IgnorePointer(
        child: Stack(
          children: <Widget>[
            const DecoratedBox(decoration: BoxDecoration(gradient: LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight, colors: <Color>[_ink, Color(0xFF0D1020), _ink]))),
            Positioned(
              top: -120,
              right: -100,
              child: _Glow(size: 290, color: _violet.withValues(alpha: .11)),
            ),
            Positioned(
              top: 250,
              left: -160,
              child: _Glow(size: 310, color: const Color(0xFF2CD5BB).withValues(alpha: .07)),
            ),
            Positioned(
              bottom: -160,
              right: -100,
              child: _Glow(size: 300, color: const Color(0xFF4D5DFF).withValues(alpha: .08)),
            ),
          ],
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
