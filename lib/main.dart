import 'dart:convert';

import 'package:audio_service/audio_service.dart';
import 'package:dart_ytmusic_api/yt_music.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'models/track.dart';
import 'services/flux_audio_handler.dart';

late final FluxAudioHandler fluxAudio;

const _ink = Color(0xFF080B16);
const _panel = Color(0xFF13182A);
const _mint = Color(0xFFB9F65A);
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

  Future<void> _playList(List<Track> tracks, int index) async {
    if (tracks.isEmpty) return;
    try {
      await fluxAudio.playTracks(
        tracks.map((track) => track.toMediaItem()).toList(),
        startIndex: index,
      );
      await _remember(tracks[index]);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Could not start this track. Try another result or try again later.'),
          behavior: SnackBarBehavior.floating,
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

  Widget _topBar() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(22, 12, 20, 14),
      child: Row(
        children: <Widget>[
          Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(14),
              gradient: const LinearGradient(
                colors: <Color>[_mint, Color(0xFF63D9C4)],
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
              ),
              boxShadow: <BoxShadow>[BoxShadow(color: _mint.withValues(alpha: .18), blurRadius: 22, spreadRadius: 1)],
            ),
            child: const Icon(Icons.graphic_eq_rounded, color: _ink, size: 25),
          ),
          const SizedBox(width: 11),
          const Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text('FluxMusic', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900, letterSpacing: -.6)),
              Text('FIND YOUR FREQUENCY', style: TextStyle(fontSize: 9, fontWeight: FontWeight.w700, color: _muted, letterSpacing: 1.7)),
            ],
          ),
          const Spacer(),
          IconButton(
            tooltip: 'Search music',
            onPressed: () => setState(() => _tab = 1),
            icon: const Icon(Icons.search_rounded, color: Colors.white, size: 25),
          ),
          Container(
            width: 38,
            height: 38,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: Colors.white.withValues(alpha: .06),
              border: Border.all(color: Colors.white.withValues(alpha: .08)),
            ),
            child: const Icon(Icons.waves_rounded, color: _violet, size: 19),
          ),
        ],
      ),
    );
  }

  Widget _discover() {
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 4, 20, 20),
      children: <Widget>[
        const Text('YOUR SOUND,', style: TextStyle(fontSize: 11, color: _mint, fontWeight: FontWeight.w800, letterSpacing: 3)),
        const SizedBox(height: 4),
        const Text('your universe.', style: TextStyle(fontSize: 34, height: 1.08, fontWeight: FontWeight.w900, letterSpacing: -1.6)),
        const SizedBox(height: 10),
        const Text('Discover tracks for every version of you.', style: TextStyle(color: _muted, fontSize: 14)),
        const SizedBox(height: 22),
        _searchBox(
          hint: 'Songs, artists, albums...',
          onSubmitted: _search,
        ),
        const SizedBox(height: 22),
        _featureCard(),
        const SizedBox(height: 27),
        Row(
          children: <Widget>[
            const Expanded(child: Text('Pick a vibe', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w800))),
            Text('EXPLORE', style: TextStyle(fontSize: 10, color: _mint.withValues(alpha: .9), letterSpacing: 1.6, fontWeight: FontWeight.w800)),
          ],
        ),
        const SizedBox(height: 13),
        Wrap(
          spacing: 9,
          runSpacing: 9,
          children: _moods.asMap().entries.map((entry) {
            final index = entry.key;
            final mood = entry.value;
            final colors = <Color>[_mint, _violet, const Color(0xFFFFA36C), const Color(0xFF64D7E7)];
            return ActionChip(
              onPressed: () => _search(mood == 'Trending now' ? 'trending music 2026' : mood),
              backgroundColor: Colors.white.withValues(alpha: .045),
              side: BorderSide(color: colors[index % colors.length].withValues(alpha: .34)),
              avatar: Icon(
                <IconData>[
                  Icons.local_fire_department_rounded,
                  Icons.bolt_rounded,
                  Icons.spa_rounded,
                  Icons.headphones_rounded,
                  Icons.mic_external_on_rounded,
                  Icons.nightlight_round,
                ][index],
                size: 16,
                color: colors[index % colors.length],
              ),
              label: Text(mood, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 12)),
              padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 8),
            );
          }).toList(),
        ),
        const SizedBox(height: 28),
        if (_recent.isNotEmpty) ...<Widget>[
          _sectionHeading('Recently played', 'PICK UP THE VIBE'),
          const SizedBox(height: 10),
          ..._recent.take(4).toList().asMap().entries.map(
                (entry) => _trackTile(entry.value, entry.key, _recent, compact: true),
              ),
        ] else ...<Widget>[
          const _EmptyState(
            icon: Icons.surround_sound_rounded,
            title: 'Your next favourite is out there',
            subtitle: 'Search for a song or pick a vibe to start your own soundtrack.',
          ),
        ],
      ],
    );
  }

  Widget _featureCard() {
    return Container(
      height: 180,
      clipBehavior: Clip.antiAlias,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(25),
        gradient: const LinearGradient(
          colors: <Color>[Color(0xFF252447), Color(0xFF172D38), Color(0xFF152024)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        border: Border.all(color: Colors.white.withValues(alpha: .08)),
      ),
      child: Stack(
        children: <Widget>[
          Positioned(right: -12, top: -22, child: Transform.rotate(angle: -.22, child: Icon(Icons.graphic_eq_rounded, size: 215, color: _mint.withValues(alpha: .09)))),
          Positioned(right: 30, top: 34, child: Container(width: 88, height: 88, decoration: BoxDecoration(shape: BoxShape.circle, border: Border.all(color: _violet.withValues(alpha: .45), width: 1.5)))),
          Positioned(right: 48, top: 52, child: Container(width: 52, height: 52, decoration: BoxDecoration(shape: BoxShape.circle, color: _mint.withValues(alpha: .12), border: Border.all(color: _mint.withValues(alpha: .5))), child: const Icon(Icons.play_arrow_rounded, size: 34, color: _mint))),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 21, 145, 20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
                  decoration: BoxDecoration(color: _mint.withValues(alpha: .13), borderRadius: BorderRadius.circular(30)),
                  child: const Text('FLUX SELECTS', style: TextStyle(color: _mint, fontWeight: FontWeight.w900, fontSize: 9, letterSpacing: 1.5)),
                ),
                const Spacer(),
                const Text('Press play.\nLeave the ordinary.', style: TextStyle(fontSize: 21, height: 1.12, fontWeight: FontWeight.w900, letterSpacing: -.5)),
                const SizedBox(height: 7),
                const Text('Music for wherever you are.', style: TextStyle(color: _muted, fontSize: 11)),
                const Spacer(),
                InkWell(
                  borderRadius: BorderRadius.circular(30),
                  onTap: () => _search('best new music mix'),
                  child: const Row(
                    mainAxisSize: MainAxisSize.min,
                    children: <Widget>[
                      Text('Explore now', style: TextStyle(color: _mint, fontWeight: FontWeight.w800, fontSize: 12)),
                      SizedBox(width: 7),
                      Icon(Icons.arrow_forward_rounded, size: 15, color: _mint),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _searchPage() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 3, 20, 18),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              const Text('Find your next loop.', style: TextStyle(fontSize: 27, fontWeight: FontWeight.w900, letterSpacing: -1)),
              const SizedBox(height: 14),
              _searchBox(hint: 'Search songs or artists', onSubmitted: _search),
              const SizedBox(height: 12),
              if (_lastQuery.isNotEmpty)
                Text('RESULTS FOR  “${_lastQuery.toUpperCase()}”', style: const TextStyle(color: _muted, fontSize: 10, letterSpacing: 1.3, fontWeight: FontWeight.w800)),
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
                          padding: const EdgeInsets.fromLTRB(20, 0, 20, 14),
                          itemCount: _results.length,
                          itemBuilder: (_, index) => _trackTile(_results[index], index, _results),
                        ),
        ),
      ],
    );
  }

  Widget _libraryPage() {
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 4, 20, 20),
      children: <Widget>[
        const Text('Your library', style: TextStyle(fontSize: 29, fontWeight: FontWeight.w900, letterSpacing: -1)),
        const SizedBox(height: 6),
        const Text('The tracks you keep close.', style: TextStyle(color: _muted)),
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
          _sectionHeading('Listening history', 'ON THIS DEVICE'),
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
          padding: const EdgeInsets.fromLTRB(12, 5, 12, 9),
          child: Material(
            color: const Color(0xFF1C2234),
            borderRadius: BorderRadius.circular(17),
            child: InkWell(
              borderRadius: BorderRadius.circular(17),
              onTap: _openPlayer,
              child: Container(
                padding: const EdgeInsets.fromLTRB(9, 8, 9, 8),
                decoration: BoxDecoration(borderRadius: BorderRadius.circular(17), border: Border.all(color: Colors.white.withValues(alpha: .08))),
                child: Row(
                  children: <Widget>[
                    _CoverArt(url: item.extras?['thumbnailUrl'] as String? ?? '', size: 43, radius: 10),
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
                          onPressed: () => playing ? fluxAudio.pause() : fluxAudio.play(),
                          icon: Icon(playing ? Icons.pause_rounded : Icons.play_arrow_rounded, color: _mint, size: 29),
                        );
                      },
                    ),
                    IconButton(
                      onPressed: () => fluxAudio.skipToNext(),
                      icon: const Icon(Icons.skip_next_rounded, color: Colors.white, size: 25),
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

  Widget _artFallback() => Container(
        color: const Color(0xFF24243A),
        alignment: Alignment.center,
        child: const Icon(Icons.graphic_eq_rounded, color: _mint, size: 25),
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
