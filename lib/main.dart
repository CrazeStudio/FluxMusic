import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:audio_service/audio_service.dart';
import 'package:dart_ytmusic_api/yt_music.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'models/catalogue_preferences.dart';
import 'models/track.dart';
import 'services/flux_audio_handler.dart';
import 'services/autoplay_radio.dart';
import 'services/listening_stats.dart';
import 'services/music_catalog.dart';
import 'services/queue_store.dart';
import 'theme/appearance_controller.dart';
import 'widgets/art_gradient.dart';
import 'widgets/search_suggestions.dart';
import 'widgets/skeleton.dart';

late final FluxAudioHandler fluxAudio;
late final YTMusic fluxYtMusic;
late final MusicCatalog fluxCatalog;
late final AppearanceController fluxAppearance;
late final ListeningStats fluxStats;
late final AutoplayRadio fluxRadio;
Timer? _queuePersistenceTimer;

const _ink = Color(0xFFF5F5F5);
const _cardInk = Color(0xFF29261F);
const _mint = Color(0xFFFF8A35);
const _violet = Color(0xFFFFB16D);
const _muted = Color(0xFFB8B8B8);
const _playerMuted = Color(0xFFA3A3A3);

class _YtMusicPlaylist {
  const _YtMusicPlaylist({
    required this.id,
    required this.name,
    this.artist = '',
    this.thumbnailUrl = '',
    this.trackCount = '',
  });

  final String id;
  final String name;
  final String artist;
  final String thumbnailUrl;
  final String trackCount;

  factory _YtMusicPlaylist.fromSource(dynamic source) {
    dynamic read(String field) {
      if (source is Map) return source[field];
      try {
        switch (field) {
          case 'playlistId': return source.playlistId;
          case 'name': return source.name;
          case 'title': return source.title;
          case 'artist': return source.artist;
          case 'author': return source.author;
          case 'thumbnail': return source.thumbnail;
          case 'thumbnailUrl': return source.thumbnailUrl;
          case 'thumbnails': return source.thumbnails;
          case 'videoCount': return source.videoCount;
          case 'trackCount': return source.trackCount;
          case 'count': return source.count;
        }
      } catch (_) {
        return null;
      }
      return null;
    }

    String string(dynamic value) => value == null ? '' : value.toString().trim();
    final rawId = string(read('playlistId'));
    final name = string(read('name')).isNotEmpty
        ? string(read('name'))
        : string(read('title'));
    final artist = string(read('artist')).isNotEmpty
        ? string(read('artist'))
        : string(read('author'));
    var thumbnail = string(read('thumbnailUrl'));
    final directThumbnail = read('thumbnail');
    if (thumbnail.isEmpty && directThumbnail != null) {
      try {
        thumbnail = string(directThumbnail.url);
      } catch (_) {
        thumbnail = string(directThumbnail);
      }
    }
    if (thumbnail.isEmpty) {
      final thumbnails = read('thumbnails');
      if (thumbnails is Iterable) {
        for (final item in thumbnails.toList().reversed) {
          try {
            final url = string(item.url);
            if (url.startsWith('http')) {
              thumbnail = url;
              break;
            }
          } catch (_) {}
        }
      }
    }
    final count = string(read('trackCount')).isNotEmpty
        ? string(read('trackCount'))
        : string(read('videoCount')).isNotEmpty
            ? string(read('videoCount'))
            : string(read('count'));
    return _YtMusicPlaylist(
      id: rawId,
      name: name.isEmpty ? 'YouTube Music playlist' : name,
      artist: artist,
      thumbnailUrl: thumbnail.startsWith('http') ? thumbnail : '',
      trackCount: count,
    );
  }
}

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
  fluxYtMusic = YTMusic();
  fluxCatalog = MusicCatalog(fluxYtMusic);
  fluxAppearance = AppearanceController();
  await fluxAppearance.load();
  fluxStats = ListeningStats();
  await fluxStats.load();
  fluxRadio = AutoplayRadio(fluxAudio, fluxCatalog.related)..start();
  fluxAudio.trackStarted.listen((item) {
    fluxStats.record(item);
    unawaited(fluxStats.save());
  });
  final savedQueue = await QueueStore.load();
  if (savedQueue != null) {
    await fluxAudio.restoreQueue(savedQueue.items, index: savedQueue.index, position: savedQueue.position);
  }
  _queuePersistenceTimer = Timer.periodic(const Duration(seconds: 8), (_) {
    unawaited(QueueStore.save(fluxAudio.queue.value, fluxAudio.currentQueueIndex, fluxAudio.currentPosition));
  });
  runApp(const FluxMusicApp());
}

class FluxMusicApp extends StatelessWidget {
  const FluxMusicApp({super.key});

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: fluxAppearance,
    builder: (context, _) => MaterialApp(
      title: 'FluxMusic',
      debugShowCheckedModeBanner: false,
      theme: fluxAppearance.theme,
      home: const MusicHome(),
    ),
  );
}

class MusicHome extends StatefulWidget {
  const MusicHome({super.key});

  @override
  State<MusicHome> createState() => _MusicHomeState();
}

class _MusicHomeState extends State<MusicHome> {
  final YTMusic _ytMusic = fluxYtMusic;
  final TextEditingController _searchController = TextEditingController();

  int _tab = 0;
  int _libraryFilter = 0;
  bool _searching = false;
  String _searchQueryDraft = '';
  String? _error;
  String _lastQuery = '';
  List<Track> _results = <Track>[];
  final Map<String, List<Track>> _searchCache = <String, List<Track>>{};
  bool _notificationPermissionHandled = false;
  int _searchGeneration = 0;
  List<Track> _favorites = <Track>[];
  List<Track> _recent = <Track>[];
  List<String> _searchHistory = <String>[];
  Future<void>? _ytMusicInitialization;
  bool _ytMusicReady = false;
  bool _preferencesLoaded = false;
  bool _onboardingComplete = false;
  bool _randomCatalogue = true;
  bool _languageSelectionError = false;
  final Set<String> _selectedLanguages = <String>{};
  final List<String> _discoveryMoods = <String>['Indie picks', 'Lo-fi focus', 'Night drive', 'Anime OST'];
  final Map<String, List<Track>> _playlists = <String, List<Track>>{};
  bool _searchPlaylistsMode = false;
  String? _loadedOnlinePlaylistTitle;
  List<_YtMusicPlaylist> _ytPlaylistResults = <_YtMusicPlaylist>[];
  final Map<String, List<_YtMusicPlaylist>> _playlistSearchCache = <String, List<_YtMusicPlaylist>>{};

  @override
  void initState() {
    super.initState();
    _ytMusicInitialization = _initializeMusicCatalogue();
    _initialize();
  }

  Future<void> _initializeMusicCatalogue() async {
    try {
      await _ytMusic.initialize();
      _ytMusicReady = true;
    } catch (_) {
      _ytMusicReady = false;
      if (mounted) {
        setState(() {
          _error = 'Music catalogue could not connect. Check your internet and try again.';
        });
      }
    }
  }

  Future<void> _initialize() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final favJson = prefs.getStringList('fluxmusic.favorites.v1') ?? <String>[];
      final recentJson = prefs.getStringList('fluxmusic.recent.v1') ?? <String>[];
      _searchHistory = prefs.getStringList('fluxmusic.search_history.v1') ?? <String>[];
      _favorites = favJson.map(_decodeTrack).whereType<Track>().toList();
      _recent = recentJson.map(_decodeTrack).whereType<Track>().toList();
      _onboardingComplete = prefs.getBool('fluxmusic.personalization_done.v1') ?? false;
      _randomCatalogue = prefs.getBool('fluxmusic.random_catalogue.v1') ?? true;
      if (_randomCatalogue) _discoveryMoods.shuffle(Random());
      _selectedLanguages
        ..clear()
        ..addAll(prefs.getStringList('fluxmusic.catalogue_languages.v1') ?? <String>[]);
      final savedQuality = prefs.getString('fluxmusic.audio_quality.v1');
      fluxAudio.setAudioQuality(FluxAudioQuality.values.firstWhere(
        (quality) => quality.name == savedQuality,
        orElse: () => FluxAudioQuality.balanced,
      ));
      for (final raw in prefs.getStringList('fluxmusic.playlists.v1') ?? <String>[]) {
        final entry = _decodePlaylist(raw);
        if (entry != null) _playlists[entry.key] = entry.value;
      }
    } catch (_) {
      // The app still opens when local storage is unavailable.
    } finally {
      if (mounted) setState(() => _preferencesLoaded = true);
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

  MapEntry<String, List<Track>>? _decodePlaylist(String raw) {
    try {
      final value = jsonDecode(raw) as Map<String, dynamic>;
      final name = (value['name'] as String? ?? '').trim();
      final encodedTracks = value['tracks'];
      if (name.isEmpty || encodedTracks is! List) return null;
      final tracks = encodedTracks
          .whereType<String>()
          .map(_decodeTrack)
          .whereType<Track>()
          .toList();
      return MapEntry<String, List<Track>>(name, tracks);
    } catch (_) {
      return null;
    }
  }

  void _editPersonalization() {
    setState(() {
      _onboardingComplete = false;
      _languageSelectionError = false;
    });
  }

  Future<void> _finishPersonalization({required bool skip}) async {
    if (!skip && _selectedLanguages.isEmpty) {
      setState(() => _languageSelectionError = true);
      return;
    }
    final chosen = skip ? <String>[] : _selectedLanguages.toList();
    if (skip) _discoveryMoods.shuffle(Random());
    setState(() {
      _randomCatalogue = skip;
      _onboardingComplete = true;
      _languageSelectionError = false;
      if (skip) _selectedLanguages.clear();
    });
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('fluxmusic.personalization_done.v1', true);
      await prefs.setBool('fluxmusic.random_catalogue.v1', skip);
      await prefs.setStringList('fluxmusic.catalogue_languages.v1', chosen);
    } catch (_) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Your catalogue preference could not be saved on this device.')),
      );
    }
  }

  Widget _languageOnboarding() {
    return Scaffold(
      backgroundColor: const Color(0xFF121212),
      body: Stack(children: <Widget>[
        const _Atmosphere(),
        SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(22, 28, 22, 20),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
              Row(children: <Widget>[
                SizedBox(width: 44, height: 44, child: Center(child: SvgPicture.asset('assets/brand/fluxmusic.svg', fit: BoxFit.contain))),
                const SizedBox(width: 12),
                const Text('FluxMusic', style: TextStyle(fontSize: 26, fontWeight: FontWeight.w800, letterSpacing: -.8)),
              ]),
              const SizedBox(height: 30),
              const Text('Make your catalogue yours', style: TextStyle(fontSize: 29, height: 1.12, fontWeight: FontWeight.w800, letterSpacing: -.9)),
              const SizedBox(height: 9),
              const Text(
                'Choose every language you enjoy. We’ll put those music searches first. You can skip and get a mixed, randomized catalogue instead.',
                style: TextStyle(color: _muted, fontSize: 13, height: 1.5),
              ),
              const SizedBox(height: 20),
              Row(children: <Widget>[
                const Expanded(child: Text('Your languages', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w800))),
                Text('${_selectedLanguages.length} selected', style: const TextStyle(color: _muted, fontSize: 11)),
              ]),
              const SizedBox(height: 12),
              Expanded(
                child: SingleChildScrollView(
                  child: Wrap(
                    spacing: 9,
                    runSpacing: 9,
                    children: CataloguePreferences.languages.map((language) => FilterChip(
                      label: Text(language),
                      selected: _selectedLanguages.contains(language),
                      showCheckmark: true,
                      onSelected: (selected) => setState(() {
                        if (selected) {
                          _selectedLanguages.add(language);
                        } else {
                          _selectedLanguages.remove(language);
                        }
                        _languageSelectionError = false;
                      }),
                    )).toList(),
                  ),
                ),
              ),
              if (_languageSelectionError)
                const Padding(
                  padding: EdgeInsets.only(top: 8),
                  child: Text('Pick at least one language, or skip for the randomized catalogue.', style: TextStyle(color: Color(0xFFFFB4AB), fontSize: 11)),
                ),
              const SizedBox(height: 14),
              Row(children: <Widget>[
                Expanded(
                  child: TextButton(
                    onPressed: () => _finishPersonalization(skip: true),
                    child: const Text('Skip · random catalogue', textAlign: TextAlign.center),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: FilledButton.icon(
                    onPressed: _selectedLanguages.isEmpty ? null : () => _finishPersonalization(skip: false),
                    icon: const Icon(Icons.check_rounded, size: 18),
                    label: const Text('Personalize'),
                  ),
                ),
              ]),
            ]),
          ),
        ),
      ]),
    );
  }

  Future<void> _savePlaylists() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setStringList(
        'fluxmusic.playlists.v1',
        _playlists.entries.map((entry) => jsonEncode(<String, dynamic>{
          'name': entry.key,
          'tracks': entry.value.map(_encodeTrack).toList(),
        })).toList(),
      );
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Playlists could not be saved on this device.')),
        );
      }
    }
  }

  Future<void> _createPlaylist({String? initialName, List<Track>? seedTracks}) async {
    final controller = TextEditingController(text: initialName ?? '');
    final name = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        backgroundColor: const Color(0xFF1E1E1E),
        title: const Text('Create playlist'),
        content: TextField(
          controller: controller,
          autofocus: true,
          maxLength: 48,
          textCapitalization: TextCapitalization.sentences,
          decoration: const InputDecoration(hintText: 'Playlist name'),
          onSubmitted: (value) => Navigator.of(dialogContext).pop(value),
        ),
        actions: <Widget>[
          TextButton(onPressed: () => Navigator.of(dialogContext).pop(), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.of(dialogContext).pop(controller.text), child: const Text('Create')),
        ],
      ),
    );
    controller.dispose();
    if (!mounted || name == null || name.trim().isEmpty) return;
    final cleanName = name.trim();
    if (_playlists.keys.any((existing) => existing.toLowerCase() == cleanName.toLowerCase())) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('A playlist with that name already exists.')));
      return;
    }
    setState(() {
      _playlists[cleanName] = List<Track>.of(seedTracks ?? <Track>[]);
      _libraryFilter = 2;
      _tab = 2;
    });
    await _savePlaylists();
  }

  Future<void> _addTrackToPlaylist(Track track) async {
    if (_playlists.isEmpty) {
      await _createPlaylist();
      if (!mounted || _playlists.isEmpty) return;
    }
    final selectedName = await showModalBottomSheet<String>(
      context: context,
      backgroundColor: const Color(0xFF1A1A1A),
      showDragHandle: true,
      builder: (sheetContext) => SafeArea(
        child: ListView(
          shrinkWrap: true,
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 20),
          children: <Widget>[
            const Padding(
              padding: EdgeInsets.fromLTRB(8, 0, 8, 10),
              child: Text('Add to playlist', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800)),
            ),
            ..._playlists.keys.map((name) => ListTile(
              leading: const Icon(Icons.queue_music_rounded, color: _mint),
              title: Text(name),
              subtitle: Text('${_playlists[name]?.length ?? 0} tracks'),
              onTap: () => Navigator.of(sheetContext).pop(name),
            )),
            TextButton.icon(
              onPressed: () => Navigator.of(sheetContext).pop('__create__'),
              icon: const Icon(Icons.add_rounded),
              label: const Text('Create a new playlist'),
            ),
          ],
        ),
      ),
    );
    if (!mounted || selectedName == null) return;
    if (selectedName == '__create__') {
      await _createPlaylist(seedTracks: <Track>[track]);
      return;
    }
    final tracks = _playlists[selectedName];
    if (tracks == null) return;
    if (tracks.any((item) => item.id == track.id)) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('That track is already in the playlist.')));
      return;
    }
    setState(() => tracks.add(track));
    await _savePlaylists();
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Added to $selectedName.')));
  }

  Future<void> _searchYouTubePlaylists(String query) async {
    final clean = query.trim();
    if (clean.isEmpty) return;
    final key = clean.toLowerCase();
    final generation = ++_searchGeneration;
    _searchController.text = clean;
    _searchHistory.removeWhere((item) => item.toLowerCase() == key);
    _searchHistory.insert(0, clean);
    if (_searchHistory.length > 12) _searchHistory = _searchHistory.take(12).toList();
    unawaited(_saveSearchHistory());
    FocusScope.of(context).unfocus();
    setState(() {
      _tab = 1;
      _searchPlaylistsMode = true;
      _loadedOnlinePlaylistTitle = null;
      _searching = true;
      _lastQuery = clean;
      _error = null;
      _ytPlaylistResults = <_YtMusicPlaylist>[];
    });
    final cached = _playlistSearchCache[key];
    if (cached != null) {
      if (!mounted || generation != _searchGeneration) return;
      setState(() {
        _ytPlaylistResults = cached;
        _searching = false;
        _error = cached.isEmpty ? 'No playlists found. Try a different search.' : null;
      });
      return;
    }
    try {
      await _ytMusicInitialization;
      if (!_ytMusicReady) throw StateError('Music catalogue is not initialized.');
      final dynamic raw = await _ytMusic.searchPlaylists(clean).timeout(const Duration(seconds: 18));
      final playlists = <_YtMusicPlaylist>[];
      final seen = <String>{};
      if (raw is Iterable) {
        for (final item in raw) {
          final playlist = _YtMusicPlaylist.fromSource(item);
          if (playlist.id.isNotEmpty && seen.add(playlist.id)) playlists.add(playlist);
        }
      }
      if (!mounted || generation != _searchGeneration) return;
      _playlistSearchCache[key] = List<_YtMusicPlaylist>.unmodifiable(playlists);
      if (_playlistSearchCache.length > 20) _playlistSearchCache.remove(_playlistSearchCache.keys.first);
      setState(() {
        _ytPlaylistResults = playlists;
        _searching = false;
        _error = playlists.isEmpty ? 'No playlists found. Try a different search.' : null;
      });
    } catch (_) {
      if (!mounted || generation != _searchGeneration) return;
      setState(() {
        _searching = false;
        _error = 'YouTube Music playlist search is unavailable right now. Check your connection and retry.';
      });
    }
  }

  Future<void> _loadYouTubePlaylist(_YtMusicPlaylist playlist) async {
    final generation = ++_searchGeneration;
    setState(() {
      _searching = true;
      _error = null;
    });
    try {
      await _ytMusicInitialization;
      if (!_ytMusicReady) throw StateError('Music catalogue is not initialized.');
      final dynamic raw = await _ytMusic.getPlaylistVideos(playlist.id).timeout(const Duration(seconds: 25));
      final tracks = <Track>[];
      final seen = <String>{};
      if (raw is Iterable) {
        for (final item in raw) {
          final track = Track.fromYouTubeMusic(item);
          if (track.id.isNotEmpty && seen.add(track.id)) tracks.add(track);
        }
      }
      if (!mounted || generation != _searchGeneration) return;
      setState(() {
        _searchPlaylistsMode = false;
        _loadedOnlinePlaylistTitle = playlist.name;
        _searching = false;
        _lastQuery = playlist.name;
        _results = tracks;
        _error = tracks.isEmpty ? 'This playlist did not return any playable track entries.' : null;
      });
      if (tracks.isNotEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Opened "${playlist.name}" · ${tracks.length} tracks')),
        );
      }
    } catch (_) {
      if (!mounted || generation != _searchGeneration) return;
      setState(() {
        _searching = false;
        _searchPlaylistsMode = true;
        _error = 'Could not load that playlist. Try opening it again.';
      });
    }
  }

  void _openLocalPlaylist(String name) {
    final tracks = _playlists[name];
    if (tracks == null) return;
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: const Color(0xFF121212),
      showDragHandle: false,
      builder: (sheetContext) => SizedBox(
        height: MediaQuery.sizeOf(sheetContext).height * .88,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
            Row(children: <Widget>[
              IconButton.filledTonal(
                tooltip: 'Back to playlists',
                style: IconButton.styleFrom(
                  backgroundColor: Colors.black,
                  foregroundColor: Colors.white,
                  side: const BorderSide(color: Color(0xFF3A3A3A)),
                ),
                onPressed: () => Navigator.of(sheetContext).pop(),
                icon: const Icon(Icons.arrow_back_rounded),
              ),
              const SizedBox(width: 10),
              Expanded(child: Text(name, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800))),
              IconButton(
                tooltip: 'Playlist options',
                onPressed: () {
                  Navigator.of(sheetContext).pop();
                  _createPlaylist();
                },
                icon: const Icon(Icons.add_rounded),
              ),
            ]),
            Padding(
              padding: const EdgeInsets.only(left: 8, top: 3),
              child: Text('${tracks.length} songs', style: const TextStyle(color: _muted, fontSize: 12)),
            ),
            const SizedBox(height: 14),
            Row(children: <Widget>[
              FilledButton.icon(
                onPressed: tracks.isEmpty ? null : () {
                  Navigator.of(sheetContext).pop();
                  unawaited(_playList(tracks, 0));
                },
                icon: const Icon(Icons.play_arrow_rounded),
                label: const Text('Play all'),
              ),
              const SizedBox(width: 8),
              TextButton.icon(
                onPressed: () {
                  Navigator.of(sheetContext).pop();
                  unawaited(_createPlaylist());
                },
                icon: const Icon(Icons.add_rounded),
                label: const Text('New playlist'),
              ),
            ]),
            const Divider(color: Color(0xFF333333)),
            Expanded(
              child: tracks.isEmpty
                  ? const _EmptyState(icon: Icons.queue_music_rounded, title: 'This playlist is empty', subtitle: 'Search for songs and use the menu to add them here.')
                  : ListView.builder(
                      itemCount: tracks.length,
                      itemBuilder: (_, index) => _trackTile(tracks[index], index, tracks),
                    ),
            ),
          ]),
        ),
      ),
    );
  }

  Widget _playlistsPage() {
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 24),
      children: <Widget>[
        Row(children: <Widget>[
          IconButton(
            tooltip: 'Back to Library',
            onPressed: () => setState(() => _libraryFilter = 0),
            style: IconButton.styleFrom(
              backgroundColor: const Color(0xFF1E1E1E),
              foregroundColor: _ink,
              side: const BorderSide(color: Color(0xFF333333)),
            ),
            icon: const Icon(Icons.arrow_back_rounded),
          ),
          const SizedBox(width: 10),
          const Expanded(child: Text('Your playlists', style: TextStyle(fontSize: 25, fontWeight: FontWeight.w800, letterSpacing: -.7, color: _ink))),
          IconButton.filledTonal(
            tooltip: 'Create playlist',
            onPressed: _createPlaylist,
            icon: const Icon(Icons.add_rounded),
          ),
        ]),
        const SizedBox(height: 14),
        if (_playlists.isEmpty)
          const _EmptyState(icon: Icons.queue_music_rounded, title: 'Create your first playlist', subtitle: 'Group your favourites and search results into your own collections.')
        else
          ..._playlists.entries.map((entry) => Container(
            margin: const EdgeInsets.only(bottom: 10),
            decoration: BoxDecoration(
              color: const Color(0xFF1E1E1E),
              borderRadius: BorderRadius.circular(18),
              border: Border.all(color: const Color(0xFF303030)),
            ),
            child: ListTile(
              contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
              leading: entry.value.isNotEmpty
                  ? _CoverArt(url: entry.value.first.thumbnailUrl, size: 56, radius: 14)
                  : Container(
                      width: 56,
                      height: 56,
                      decoration: BoxDecoration(
                        gradient: const LinearGradient(
                          begin: Alignment.topLeft,
                          end: Alignment.bottomRight,
                          colors: <Color>[Color(0xFFFF8A35), Color(0xFF3A2518)],
                        ),
                        borderRadius: BorderRadius.circular(14),
                      ),
                      child: const Icon(Icons.queue_music_rounded, color: Colors.white, size: 27),
                    ),
              title: Text(entry.key, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15)),
              subtitle: Padding(
                padding: const EdgeInsets.only(top: 5),
                child: Text('${entry.value.length} songs', style: const TextStyle(color: _muted, fontSize: 12)),
              ),
              onTap: () => _openLocalPlaylist(entry.key),
              trailing: PopupMenuButton<String>(
                tooltip: 'Playlist options',
                onSelected: (action) async {
                  if (action != 'delete') return;
                  final confirmed = await showDialog<bool>(
                    context: context,
                    builder: (dialogContext) => AlertDialog(
                      title: Text('Delete "${entry.key}"?'),
                      content: const Text('This removes the local playlist, not the songs from YouTube Music.'),
                      actions: <Widget>[
                        TextButton(onPressed: () => Navigator.of(dialogContext).pop(false), child: const Text('Cancel')),
                        FilledButton(onPressed: () => Navigator.of(dialogContext).pop(true), child: const Text('Delete')),
                      ],
                    ),
                  );
                  if (confirmed == true && mounted) {
                    setState(() => _playlists.remove(entry.key));
                    await _savePlaylists();
                  }
                },
                itemBuilder: (_) => const <PopupMenuEntry<String>>[
                  PopupMenuItem<String>(
                    value: 'delete',
                    child: Row(children: <Widget>[Icon(Icons.delete_outline_rounded, size: 18), SizedBox(width: 10), Text('Delete playlist')]),
                  ),
                ],
              ),
            ),
          )),
      ],
    );
  }

  Future<void> _search(String query) async {
    final clean = query.trim();
    if (clean.isEmpty) return;
    final searchKey = clean.toLowerCase();
    final generation = ++_searchGeneration;

    _searchController.text = clean;
    _searchQueryDraft = clean;
    _searchHistory.removeWhere((item) => item.toLowerCase() == searchKey);
    _searchHistory.insert(0, clean);
    if (_searchHistory.length > 12) _searchHistory = _searchHistory.take(12).toList();
    unawaited(_saveSearchHistory());
    FocusScope.of(context).unfocus();
    setState(() {
      _tab = 1;
      _searchPlaylistsMode = false;
      _loadedOnlinePlaylistTitle = null;
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
      await _ytMusicInitialization;
      if (!_ytMusicReady) throw StateError('Music catalogue is not initialized.');
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

  Future<void> _requestNotificationPermissionInContext() async {
    if (_notificationPermissionHandled) return;
    _notificationPermissionHandled = true;
    try {
      final prefs = await SharedPreferences.getInstance();
      if (prefs.getBool('fluxmusic.notification_permission_prompted.v1') ?? false) return;
      final status = await Permission.notification.status;
      if (status.isGranted) {
        await prefs.setBool('fluxmusic.notification_permission_prompted.v1', true);
        return;
      }
      if (!mounted) return;
      final shouldRequest = await showDialog<bool>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          backgroundColor: const Color(0xFF1E1E1E),
          title: const Text('Keep playback controls available'),
          content: const Text('Notifications let FluxMusic show playback controls on your lock screen and notification shade while music plays. You can continue without allowing them.'),
          actions: <Widget>[
            TextButton(onPressed: () => Navigator.pop(dialogContext, false), child: const Text('Not now')),
            FilledButton(
              style: FilledButton.styleFrom(backgroundColor: _mint, foregroundColor: _ink),
              onPressed: () => Navigator.pop(dialogContext, true),
              child: const Text('Allow notifications'),
            ),
          ],
        ),
      );
      if (shouldRequest == true) {
        final result = await Permission.notification.request();
        if (!result.isGranted && mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('Playback will continue, but Android may hide notification controls. You can enable notifications in system settings.'),
              behavior: SnackBarBehavior.floating,
            ),
          );
        }
      }
      await prefs.setBool('fluxmusic.notification_permission_prompted.v1', true);
    } catch (_) {
      // Permission APIs vary by Android version and OEM; never block playback.
    }
  }

  Future<void> _playList(List<Track> tracks, int index) async {
    if (tracks.isEmpty || index < 0 || index >= tracks.length) return;
    await _requestNotificationPermissionInContext();

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
    if (!_preferencesLoaded) {
      return const Scaffold(
        backgroundColor: Color(0xFF121212),
        body: Center(child: CircularProgressIndicator(color: _mint)),
      );
    }
    if (!_onboardingComplete) return _languageOnboarding();
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
                Container(
                  decoration: const BoxDecoration(
                    color: Color(0xFF121212),
                    border: Border(top: BorderSide(color: Color(0xFF292929))),
                  ),
                  child: NavigationBar(
                    height: 66,
                    elevation: 0,
                    backgroundColor: const Color(0xFF121212),
                    surfaceTintColor: Colors.transparent,
                    indicatorColor: _mint.withValues(alpha: .16),
                    labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
                    selectedIndex: _tab,
                    onDestinationSelected: (index) => setState(() => _tab = index),
                    destinations: const <NavigationDestination>[
                      NavigationDestination(icon: Icon(Icons.home_outlined), selectedIcon: Icon(Icons.home_rounded), label: 'Home'),
                      NavigationDestination(icon: Icon(Icons.search_rounded), selectedIcon: Icon(Icons.search_rounded), label: 'Search'),
                      NavigationDestination(icon: Icon(Icons.library_music_outlined), selectedIcon: Icon(Icons.library_music_rounded), label: 'Library'),
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

  void _openAudioEnhancements() {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      useSafeArea: true,
      builder: (_) => AudioEnhancementsSheet(handler: fluxAudio),
    );
  }

  void _openSettings() {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      useSafeArea: true,
      builder: (sheetContext) => AppSettingsSheet(
        languageSummary: _randomCatalogue
            ? 'Mixed catalogue'
            : '${_selectedLanguages.length} languages selected',
        onOpenAudio: () {
          Navigator.of(sheetContext).pop();
          _openAudioEnhancements();
        },
        onEditCatalogue: () {
          Navigator.of(sheetContext).pop();
          _editPersonalization();
        },
      ),
    );
  }

  Widget _topBar() {
    if (_tab != 0) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 7, 20, 4),
      child: SizedBox(
        height: 46,
        child: Row(children: <Widget>[
          Container(
            width: 46,
            height: 46,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: <Color>[Color(0xFF38251A), Color(0xFF1E1E1E)],
              ),
              borderRadius: BorderRadius.circular(15),
              border: Border.all(color: const Color(0xFF5A3825)),
              boxShadow: const <BoxShadow>[
                BoxShadow(color: Color(0x263F210F), blurRadius: 14, offset: Offset(0, 4)),
              ],
            ),
            child: Padding(
              padding: const EdgeInsets.all(2),
              child: SvgPicture.asset('assets/brand/fluxmusic.svg', fit: BoxFit.contain),
            ),
          ),
          const SizedBox(width: 11),
          const Spacer(),
          Tooltip(
            message: 'Personalize catalogue',
            child: InkWell(
              borderRadius: BorderRadius.circular(22),
              onTap: _editPersonalization,
              child: Container(
                width: 36, height: 36, alignment: Alignment.center,
                decoration: BoxDecoration(color: const Color(0xFF1E1E1E), shape: BoxShape.circle, border: Border.all(color: const Color(0xFF333333))),
                child: const Icon(Icons.language_rounded, color: _mint, size: 19),
              ),
            ),
          ),
          const SizedBox(width: 7),
          Tooltip(
            message: 'Settings',
            child: InkWell(
              borderRadius: BorderRadius.circular(22),
              onTap: _openSettings,
              child: Container(
                width: 36, height: 36, alignment: Alignment.center,
                decoration: BoxDecoration(color: const Color(0xFF1E1E1E), shape: BoxShape.circle, border: Border.all(color: const Color(0xFF3A3029))),
                child: const Icon(Icons.settings_rounded, color: _mint, size: 19),
              ),
            ),
          ),
        ]),
      ),
    );
  }

  Widget _discover() {
    final recent = _recent.take(10).toList();
    final personalized = CataloguePreferences.isPersonalized(
      randomized: _randomCatalogue,
      languages: _selectedLanguages,
    );
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 14, 20, 20),
      children: <Widget>[
        const SizedBox(height: 4),
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
        if (personalized) ...<Widget>[
          const Text('Your languages', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w800, letterSpacing: -.4)),
          const SizedBox(height: 10),
          SizedBox(
            height: 112,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              itemCount: _selectedLanguages.length,
              separatorBuilder: (_, __) => const SizedBox(width: 10),
              itemBuilder: (_, index) {
                final language = _selectedLanguages.elementAt(index);
                return SizedBox(
                  width: 154,
                  child: _catalogueTile(
                    '$language music',
                    'Personalized picks',
                    const Color(0xFF252525),
                    Icons.language_rounded,
                    CataloguePreferences.queryFor(language),
                  ),
                );
              },
            ),
          ),
          const SizedBox(height: 22),
        ],
        const SizedBox(height: 25),
        Row(children: <Widget>[
          const Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
            Text('For you', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w800, letterSpacing: -.4)),
          ])),
          TextButton(onPressed: () => setState(() => _tab = 1), style: TextButton.styleFrom(foregroundColor: _mint), child: const Text('Explore', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700))),
        ]),
        const SizedBox(height: 11),
        SizedBox(
          height: 142,
          child: ListView(
            scrollDirection: Axis.horizontal,
            children: <Widget>[
              for (var index = 0; index < _discoveryMoods.length; index++) ...<Widget>[
                _moodCard(_discoveryMoods[index], index),
                if (index < _discoveryMoods.length - 1) const SizedBox(width: 12),
              ],
            ],
          ),
        ),
        const SizedBox(height: 24),
        Row(children: <Widget>[
          const Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
            Text('Categories', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w800, letterSpacing: -.4)),
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
            _catalogueTile('Anime & OST', 'Openings and scores', const Color(0xFF25202B), Icons.auto_awesome_rounded, 'anime openings'),
            _catalogueTile('Lo-fi & Focus', 'Stay in your zone', const Color(0xFF202820), Icons.headphones_rounded, 'lofi beats focus'),
            _catalogueTile('Indian Hits', 'Hindi • Bengali • more', const Color(0xFF2A211F), Icons.graphic_eq_rounded, 'Indian hit songs Hindi Bengali'),
            _catalogueTile('Indie Finds', 'Beyond the mainstream', const Color(0xFF20252B), Icons.album_rounded, 'indie music'),
            _catalogueTile('Chill & Ambient', 'Slow down a little', const Color(0xFF29291E), Icons.nightlight_round, 'chill ambient music'),
            _catalogueTile('Workout Energy', 'Turn it up', const Color(0xFF2A2026), Icons.bolt_rounded, 'workout upbeat music'),
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
        const SizedBox(height: 28),
        Center(
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              SizedBox(width: 24, height: 24, child: SvgPicture.asset('assets/brand/crazestudio-mark.svg', fit: BoxFit.contain)),
              const SizedBox(width: 8),
              const Text('CrazeStudio', style: TextStyle(color: _muted, fontSize: 11, fontWeight: FontWeight.w700, letterSpacing: .5)),
            ],
          ),
        ),
        const SizedBox(height: 8),
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
      color: color,
      borderRadius: BorderRadius.circular(17),
      child: InkWell(
        borderRadius: BorderRadius.circular(17),
        onTap: () => _search(query),
        child: Ink(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(17),
            border: Border.all(color: Colors.white.withValues(alpha: .07)),
          ),
          child: Stack(children: <Widget>[
            Positioned(right: 8, top: 6, child: Icon(icon, size: 39, color: _muted.withValues(alpha: .65))),
            Padding(
              padding: const EdgeInsets.fromLTRB(11, 9, 5, 9),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisAlignment: MainAxisAlignment.end, children: <Widget>[
                Text(title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _ink, fontSize: 12, fontWeight: FontWeight.w800)),
                const SizedBox(height: 3),
                Text(subtitle, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _muted, fontSize: 9)),
              ]),
            ),
          ]),
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

  Widget _moodCard(String mood, int index) {
    const palettes = <List<Color>>[
      <Color>[Color(0xFF3A281D), Color(0xFF211915)],
      <Color>[Color(0xFF30271F), Color(0xFF1C1916)],
      <Color>[Color(0xFF3A2420), Color(0xFF211716)],
      <Color>[Color(0xFF28242A), Color(0xFF18171B)],
      <Color>[Color(0xFF34231E), Color(0xFF1D1715)],
      <Color>[Color(0xFF30291F), Color(0xFF1B1915)],
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
          onTap: () => _search(mood == 'Trending now' ? 'trending music ${DateTime.now().year}' : mood),
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
                    decoration: BoxDecoration(color: _mint, borderRadius: BorderRadius.circular(12)),
                    child: Icon(icon, color: const Color(0xFF21160F), size: 19),
                  ),
                  const Spacer(),
                  Text(mood, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _ink, fontSize: 12, fontWeight: FontWeight.w800)),
                  const SizedBox(height: 4),
                  Row(children: <Widget>[
                    const Text('PLAY MIX', style: TextStyle(fontSize: 8, color: _muted, fontWeight: FontWeight.w800, letterSpacing: 1.05)),
                    const Spacer(),
                    Icon(Icons.arrow_outward_rounded, color: _ink.withValues(alpha: .72), size: 13),
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
        const SizedBox(height: 14),
        Wrap(spacing: 8, children: <Widget>[
          ChoiceChip(
            label: const Text('Songs'),
            selected: !_searchPlaylistsMode,
            onSelected: (_) => setState(() {
              _searchPlaylistsMode = false;
              _lastQuery = '';
              _searching = false;
              _error = null;
            }),
          ),
          ChoiceChip(
            label: const Text('Playlists'),
            selected: _searchPlaylistsMode,
            onSelected: (_) => setState(() {
              _searchPlaylistsMode = true;
              _lastQuery = '';
              _searching = false;
              _error = null;
            }),
          ),
        ]),
        const SizedBox(height: 12),
        _searchBox(
          hint: _searchPlaylistsMode ? 'Search YouTube Music playlists' : 'Search songs, artists, albums',
          onSubmitted: _searchPlaylistsMode ? _searchYouTubePlaylists : _search,
          onChanged: (value) => setState(() => _searchQueryDraft = value),
        ),
        if (!_searchPlaylistsMode && _searchQueryDraft.trim().length >= 2 && _lastQuery.isEmpty)
          SearchSuggestions(query: _searchQueryDraft, fetch: fluxCatalog.suggestions, onPick: _search),
        const SizedBox(height: 14),
        if (_lastQuery.isEmpty)
          Expanded(
            child: _searchPlaylistsMode
                ? const _EmptyState(
                    icon: Icons.queue_music_rounded,
                    title: 'Find a playlist',
                    subtitle: 'Search YouTube Music, then open a result to load its track list.',
                  )
                : ListView(padding: const EdgeInsets.only(bottom: 16), children: <Widget>[
                    const Text('Browse categories', style: TextStyle(color: _ink, fontSize: 19, fontWeight: FontWeight.w800, letterSpacing: -.3)),
                    const SizedBox(height: 10),
                    Wrap(spacing: 10, runSpacing: 10, children: <Widget>[
                      _genreTile('Electronic', const Color(0xFF25202B), 'electronic music'),
                      _genreTile('Chill', const Color(0xFF202820), 'chill music'),
                      _genreTile('Indie', const Color(0xFF2A211F), 'indie music'),
                      _genreTile('Focus', const Color(0xFF29291E), 'focus music'),
                      _genreTile('Hip-hop', const Color(0xFF2A2026), 'hip hop'),
                      _genreTile('Ambient', const Color(0xFF20262B), 'ambient music'),
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
                  ]),
          )
        else
          Expanded(
            child: _searching
                ? ListView(padding: const EdgeInsets.only(top: 12), children: List.generate(6, (_) => const TrackRowSkeleton()))
                : _searchPlaylistsMode
                    ? _onlinePlaylistResults()
                    : _error != null && _results.isEmpty
                        ? _ErrorState(message: _error!, onRetry: () => _search(_lastQuery))
                        : _results.isEmpty
                            ? const _EmptyState(icon: Icons.search_rounded, title: 'No songs found', subtitle: 'Try another song, artist, or search term.')
                            : ListView.builder(
                                padding: const EdgeInsets.only(bottom: 14),
                                itemCount: _results.length + (_loadedOnlinePlaylistTitle == null ? 0 : 1),
                                itemBuilder: (_, index) {
                                  if (_loadedOnlinePlaylistTitle != null && index == 0) {
                                    return Card(
                                      color: const Color(0xFF1E1E1E),
                                      margin: const EdgeInsets.only(bottom: 10),
                                      child: Padding(
                                        padding: const EdgeInsets.all(13),
                                        child: Row(children: <Widget>[
                                          const Icon(Icons.cloud_done_rounded, color: _mint, size: 25),
                                          const SizedBox(width: 10),
                                          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
                                            const Text('YouTube Music playlist loaded', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w800)),
                                            Text(_loadedOnlinePlaylistTitle!, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _muted, fontSize: 10)),
                                          ])),
                                          const SizedBox(width: 5),
                                          IconButton(
                                            tooltip: 'Save tracklist as a local playlist',
                                            onPressed: () => _createPlaylist(
                                              initialName: _loadedOnlinePlaylistTitle,
                                              seedTracks: _results,
                                            ),
                                            icon: const Icon(Icons.playlist_add_rounded, color: _mint),
                                          ),
                                        ]),
                                      ),
                                    );
                                  }
                                  final trackIndex = index - (_loadedOnlinePlaylistTitle == null ? 0 : 1);
                                  return _trackTile(_results[trackIndex], trackIndex, _results);
                                },
                              ),
          ),
      ]),
    );
  }

  Widget _onlinePlaylistResults() {
    if (_error != null && _ytPlaylistResults.isEmpty) {
      return _ErrorState(message: _error!, onRetry: () => _searchYouTubePlaylists(_lastQuery));
    }
    if (_ytPlaylistResults.isEmpty) {
      return const _EmptyState(icon: Icons.queue_music_rounded, title: 'No playlists found', subtitle: 'Try a different title, artist, or mood.');
    }
    return ListView.builder(
      padding: const EdgeInsets.only(bottom: 14),
      itemCount: _ytPlaylistResults.length,
      itemBuilder: (_, index) {
        final playlist = _ytPlaylistResults[index];
        final detail = <String>[
          if (playlist.artist.isNotEmpty) playlist.artist,
          if (playlist.trackCount.isNotEmpty) '${playlist.trackCount} tracks',
          'YouTube Music',
        ].join(' · ');
        return Card(
          color: const Color(0xFF1E1E1E),
          margin: const EdgeInsets.only(bottom: 8),
          child: ListTile(
            leading: playlist.thumbnailUrl.isEmpty
                ? const CircleAvatar(backgroundColor: Color(0xFF29291E), child: Icon(Icons.queue_music_rounded, color: _mint))
                : _CoverArt(url: playlist.thumbnailUrl, size: 48, radius: 11),
            title: Text(playlist.name, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 13)),
            subtitle: Text(detail, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _muted, fontSize: 10)),
            trailing: const Icon(Icons.open_in_new_rounded, color: _mint, size: 19),
            onTap: () => _loadYouTubePlaylist(playlist),
          ),
        );
      },
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

  Widget _libraryPage() {
    if (_libraryFilter == 2) return _playlistsPage();
    final showingRecent = _libraryFilter == 1;
    final visibleTracks = showingRecent ? _recent : _favorites;
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 18),
      children: <Widget>[
        const Text('Library', style: TextStyle(fontSize: 25, fontWeight: FontWeight.w800, letterSpacing: -.7, color: _ink)),
        const SizedBox(height: 18),
        Wrap(spacing: 8, runSpacing: 8, children: <Widget>[
          _libraryChip('Liked songs', 0),
          _libraryChip('Recently played', 1),
          _libraryChip('Playlists', 2),
        ]),
        const SizedBox(height: 17),
        Container(
          height: 130, clipBehavior: Clip.antiAlias, padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: showingRecent
                  ? const <Color>[Color(0xFF2B211B), Color(0xFF1B1B1B)]
                  : const <Color>[Color(0xFF332018), Color(0xFF1B1B1B)],
            ),
            borderRadius: BorderRadius.circular(20),
            border: Border.all(color: const Color(0xFF4A3325)),
          ),
          child: Stack(children: <Widget>[
            Positioned(right: -5, top: -15, child: Icon(Icons.favorite_rounded, size: 115, color: Colors.white.withValues(alpha: .13))),
            Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
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
    return AnimatedContainer(
      duration: const Duration(milliseconds: 180),
      curve: Curves.easeOutCubic,
      decoration: BoxDecoration(
        color: selected ? _mint : const Color(0xFF1D1D1D),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: selected ? _mint : const Color(0xFF343434)),
        boxShadow: selected
            ? const <BoxShadow>[BoxShadow(color: Color(0x1AFF8A35), blurRadius: 10, offset: Offset(0, 3))]
            : const <BoxShadow>[],
      ),
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: () => setState(() => _libraryFilter = index),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 11),
          child: Text(
            label,
            style: TextStyle(
              color: selected ? const Color(0xFF17110D) : _muted,
              fontSize: 11,
              fontWeight: FontWeight.w800,
            ),
          ),
        ),
      ),
    );
  }

  Widget _searchBox({required String hint, required ValueChanged<String> onSubmitted, ValueChanged<String>? onChanged}) {
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
        onChanged: onChanged,
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
            PopupMenuButton<String>(
              tooltip: 'Track options',
              icon: const Icon(Icons.more_vert_rounded, color: _muted, size: 21),
              onSelected: (action) {
                if (action == 'playlist') {
                  unawaited(_addTrackToPlaylist(track));
                  return;
                }
                final item = track.toMediaItem();
                if (action == 'next') {
                  fluxAudio.playNext(item);
                } else {
                  fluxAudio.addToQueue(item);
                }
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(
                    content: Text(action == 'next' ? 'Added to play next.' : 'Added to queue.'),
                    behavior: SnackBarBehavior.floating,
                    duration: const Duration(seconds: 2),
                  ),
                );
              },
              itemBuilder: (_) => const <PopupMenuEntry<String>>[
                PopupMenuItem<String>(
                  value: 'next',
                  child: Row(children: <Widget>[
                    Icon(Icons.play_arrow_rounded, size: 18),
                    SizedBox(width: 10),
                    Text('Play next'),
                  ]),
                ),
                PopupMenuItem<String>(
                  value: 'queue',
                  child: Row(children: <Widget>[
                    Icon(Icons.queue_music_rounded, size: 18),
                    SizedBox(width: 10),
                    Text('Add to queue'),
                  ]),
                ),
                PopupMenuItem<String>(
                  value: 'playlist',
                  child: Row(children: <Widget>[
                    Icon(Icons.playlist_add_rounded, size: 18),
                    SizedBox(width: 10),
                    Text('Add to playlist'),
                  ]),
                ),
              ],
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
          padding: const EdgeInsets.fromLTRB(12, 5, 12, 8),
          child: Material(
            color: const Color(0xFF1C1C1C),
            borderRadius: BorderRadius.circular(17),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              borderRadius: BorderRadius.circular(17),
              onTap: _openPlayer,
              child: Container(
                height: 64,
                padding: const EdgeInsets.fromLTRB(9, 7, 6, 7),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(17),
                  border: Border.all(color: const Color(0xFF3A3029)),
                ),
                child: Stack(
                  children: <Widget>[
                    Positioned(
                      left: 0,
                      right: 0,
                      top: 0,
                      child: StreamBuilder<Duration>(
                        stream: fluxAudio.positionStream,
                        initialData: Duration.zero,
                        builder: (context, positionSnapshot) {
                          return StreamBuilder<Duration?>(
                            stream: fluxAudio.durationStream,
                            builder: (context, durationSnapshot) {
                              final duration = durationSnapshot.data ?? Duration.zero;
                              final position = positionSnapshot.data ?? Duration.zero;
                              final progress = duration.inMilliseconds <= 0
                                  ? 0.0
                                  : (position.inMilliseconds / duration.inMilliseconds).clamp(0.0, 1.0).toDouble();
                              return ClipRRect(
                                borderRadius: BorderRadius.circular(4),
                                child: LinearProgressIndicator(
                                  value: progress,
                                  minHeight: 2,
                                  backgroundColor: Colors.white.withValues(alpha: .06),
                                  valueColor: const AlwaysStoppedAnimation<Color>(_mint),
                                ),
                              );
                            },
                          );
                        },
                      ),
                    ),
                    Row(
                      children: <Widget>[
                        _CoverArt(url: item.extras?['thumbnailUrl'] as String? ?? '', size: 46, radius: 11),
                        const SizedBox(width: 11),
                        Expanded(
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: <Widget>[
                              Text(item.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _ink, fontSize: 12.5, fontWeight: FontWeight.w800)),
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
                              tooltip: playing ? 'Pause' : 'Play',
                              visualDensity: VisualDensity.compact,
                              constraints: const BoxConstraints.tightFor(width: 42, height: 44),
                              onPressed: () => playing ? fluxAudio.pause() : fluxAudio.play(),
                              icon: Icon(playing ? Icons.pause_rounded : Icons.play_arrow_rounded, color: _mint, size: 27),
                            );
                          },
                        ),
                        IconButton(
                          tooltip: 'Next track',
                          visualDensity: VisualDensity.compact,
                          constraints: const BoxConstraints.tightFor(width: 36, height: 44),
                          onPressed: () => fluxAudio.skipToNext(),
                          icon: const Icon(Icons.skip_next_rounded, color: _ink, size: 24),
                        ),
                      ],
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

class AppSettingsSheet extends StatelessWidget {
  const AppSettingsSheet({
    super.key,
    required this.languageSummary,
    required this.onOpenAudio,
    required this.onEditCatalogue,
  });

  final String languageSummary;
  final VoidCallback onOpenAudio;
  final VoidCallback onEditCatalogue;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      decoration: const BoxDecoration(
        color: Color(0xFF121212),
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
      child: SafeArea(
        top: false,
        child: ConstrainedBox(
          constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(context).height * .84),
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Center(
                  child: Container(
                    width: 42,
                    height: 4,
                    decoration: BoxDecoration(color: Colors.white24, borderRadius: BorderRadius.circular(8)),
                  ),
                ),
                const SizedBox(height: 18),
                Row(
                  children: <Widget>[
                    const Expanded(
                      child: Text('Settings', style: TextStyle(fontSize: 25, fontWeight: FontWeight.w800, letterSpacing: -.7)),
                    ),
                    IconButton(
                      tooltip: 'Close settings',
                      onPressed: () => Navigator.of(context).pop(),
                      icon: const Icon(Icons.close_rounded),
                    ),
                  ],
                ),
                const SizedBox(height: 18),
                _sectionLabel('PLAYBACK & AUDIO'),
                const SizedBox(height: 9),
                _row(
                  icon: Icons.graphic_eq_rounded,
                  title: 'Playback & sound',
                  subtitle: 'Quality, normalization, limiter and bass',
                  onTap: onOpenAudio,
                ),
                const SizedBox(height: 20),
                _sectionLabel('YOUR MUSIC'),
                const SizedBox(height: 9),
                _row(
                  icon: Icons.language_rounded,
                  title: 'Catalogue & languages',
                  subtitle: languageSummary,
                  onTap: onEditCatalogue,
                ),
                const SizedBox(height: 20),
                _sectionLabel('APPEARANCE'),
                const SizedBox(height: 9),
                Container(
                  padding: const EdgeInsets.all(13),
                  decoration: BoxDecoration(
                    color: const Color(0xFF1E1E1E),
                    borderRadius: BorderRadius.circular(17),
                    border: Border.all(color: const Color(0xFF333333)),
                  ),
                  child: Row(
                    children: <Widget>[
                      Container(
                        width: 42,
                        height: 42,
                        decoration: BoxDecoration(
                          color: const Color(0xFF242424),
                          borderRadius: BorderRadius.circular(13),
                        ),
                        child: const Icon(Icons.palette_outlined, color: _mint, size: 22),
                      ),
                      const SizedBox(width: 12),
                      const Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: <Widget>[
                            Text('Dark charcoal', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w800)),
                            SizedBox(height: 4),
                            Text('Orange accent · FluxMusic theme', style: TextStyle(color: _playerMuted, fontSize: 11)),
                          ],
                        ),
                      ),
                      Container(
                        width: 23,
                        height: 23,
                        decoration: const BoxDecoration(color: _mint, shape: BoxShape.circle),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 24),
                Center(
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: <Widget>[
                      SizedBox(width: 22, height: 22, child: SvgPicture.asset('assets/brand/crazestudio-mark.svg', fit: BoxFit.contain)),
                      const SizedBox(width: 8),
                      const Text('CrazeStudio', style: TextStyle(color: _playerMuted, fontSize: 11, fontWeight: FontWeight.w700, letterSpacing: .5)),
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

  Widget _sectionLabel(String value) => Text(
        value,
        style: const TextStyle(color: _mint, fontSize: 10, fontWeight: FontWeight.w900, letterSpacing: 1.5),
      );

  Widget _row({
    required IconData icon,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
  }) {
    return Material(
      color: const Color(0xFF1E1E1E),
      borderRadius: BorderRadius.circular(17),
      child: InkWell(
        borderRadius: BorderRadius.circular(17),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(13),
          child: Row(
            children: <Widget>[
              Container(
                width: 42,
                height: 42,
                decoration: BoxDecoration(
                  color: const Color(0xFF35251C),
                  borderRadius: BorderRadius.circular(13),
                ),
                child: Icon(icon, color: _mint, size: 22),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Text(title, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w800)),
                    const SizedBox(height: 4),
                    Text(subtitle, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _playerMuted, fontSize: 11, height: 1.3)),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              const Icon(Icons.chevron_right_rounded, color: _playerMuted, size: 22),
            ],
          ),
        ),
      ),
    );
  }
}

class AudioEnhancementsSheet extends StatefulWidget {
  const AudioEnhancementsSheet({super.key, required this.handler});

  final FluxAudioHandler handler;

  @override
  State<AudioEnhancementsSheet> createState() => _AudioEnhancementsSheetState();
}

class _AudioEnhancementsSheetState extends State<AudioEnhancementsSheet> {
  static const MethodChannel _effects = MethodChannel('dev.crazestudio.fluxmusic/audio_effects');
  bool _normalization = true;
  bool _limiter = true;
  bool _drc = false;
  bool _bassBoost = false;
  FluxAudioQuality _quality = FluxAudioQuality.balanced;
  String? _note;

  @override
  void initState() {
    super.initState();
    _quality = widget.handler.audioQuality;
    unawaited(_loadSavedEffects());
  }

  Future<void> _loadSavedEffects() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final normalization = prefs.getBool('fluxmusic.audio.normalization.v1') ?? true;
      final limiter = prefs.getBool('fluxmusic.audio.limiter.v1') ?? true;
      final drc = prefs.getBool('fluxmusic.audio.drc.v1') ?? false;
      final bassBoost = prefs.getBool('fluxmusic.audio.bassBoost.v1') ?? false;
      if (mounted) {
        setState(() {
          _normalization = normalization;
          _limiter = limiter;
          _drc = drc;
          _bassBoost = bassBoost;
        });
      }
      await _effects.invokeMethod<void>('setSettings', <String, Object>{
        'normalization': normalization,
        'limiter': limiter,
        'drc': drc,
        'bassBoost': bassBoost,
      });
    } catch (_) {
      // Keep enhancement controls available even on devices without the channel.
    }
  }

  Future<void> _setQuality(FluxAudioQuality quality) async {
    setState(() {
      _quality = quality;
      _note = null;
    });
    widget.handler.setAudioQuality(quality);
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('fluxmusic.audio_quality.v1', quality.name);
      if (mounted) setState(() => _note = 'Quality saved. It applies on the next stream load.');
    } catch (_) {
      if (mounted) setState(() => _note = 'Quality applies to the next stream load; saving failed.');
    }
  }

  Future<void> _set(String key, bool value) async {
    setState(() {
      if (key == 'normalization') _normalization = value;
      if (key == 'limiter') _limiter = value;
      if (key == 'drc') _drc = value;
      if (key == 'bassBoost') _bassBoost = value;
      _note = null;
    });
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('fluxmusic.audio.$key.v1', value);
      await _effects.invokeMethod<void>('setSettings', <String, Object>{
        'normalization': _normalization,
        'limiter': _limiter,
        'drc': _drc,
        'bassBoost': _bassBoost,
      });
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
      const Align(alignment: Alignment.centerLeft, child: Text('Streaming quality', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w800))),
      const SizedBox(height: 7),
      Wrap(spacing: 7, runSpacing: 6, children: <Widget>[
        ChoiceChip(
          label: const Text('Data saver'),
          selected: _quality == FluxAudioQuality.dataSaver,
          onSelected: (selected) { if (selected) unawaited(_setQuality(FluxAudioQuality.dataSaver)); },
        ),
        ChoiceChip(
          label: const Text('Balanced'),
          selected: _quality == FluxAudioQuality.balanced,
          onSelected: (selected) { if (selected) unawaited(_setQuality(FluxAudioQuality.balanced)); },
        ),
        ChoiceChip(
          label: const Text('High'),
          selected: _quality == FluxAudioQuality.high,
          onSelected: (selected) { if (selected) unawaited(_setQuality(FluxAudioQuality.high)); },
        ),
      ]),
      const SizedBox(height: 4),
      const Align(
        alignment: Alignment.centerLeft,
        child: Text('Uses the nearest available audio bitrate; high quality may use more data. Source availability varies and this is not lossless upscaling.', style: TextStyle(color: _playerMuted, fontSize: 10.5, height: 1.35)),
      ),
      const SizedBox(height: 13),
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
    decoration: BoxDecoration(
      color: const Color(0xFF1E1E1E),
      borderRadius: BorderRadius.circular(18),
      border: Border.all(color: Colors.white.withValues(alpha: .075)),
    ),
    child: Row(children: <Widget>[
      Icon(icon, color: _mint, size: 24),
      const SizedBox(width: 12),
      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
        Text(title, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w800)),
        const SizedBox(height: 4),
        Text(subtitle, style: const TextStyle(fontSize: 10.5, color: _playerMuted, height: 1.35)),
      ])),
      Switch.adaptive(value: value, activeThumbColor: _ink, activeTrackColor: _mint, onChanged: onChanged),
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
  double? _dragPositionMs;

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
              child: SingleChildScrollView(
                child: Column(mainAxisSize: MainAxisSize.min, children: <Widget>[
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
                        if (!context.mounted) return;
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
                const SizedBox(height: 4),
                SizedBox(
                  height: (MediaQuery.sizeOf(context).height * .34).clamp(156.0, 292.0).toDouble(),
                  child: Center(
                    child: _CoverArt(
                      url: item.extras?['thumbnailUrl'] as String? ?? '',
                      size: (MediaQuery.sizeOf(context).height * .34).clamp(156.0, 292.0).toDouble(),
                      radius: 16,
                    ),
                  ),
                ),
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
                ValueListenableBuilder<Duration?>(
                  valueListenable: widget.handler.sleepTimerRemaining,
                  builder: (context, remaining, _) {
                    if (remaining == null) return const SizedBox.shrink();
                    return Align(
                      alignment: Alignment.centerLeft,
                      child: ActionChip(
                        avatar: const Icon(Icons.timer_outlined, color: _mint, size: 17),
                        backgroundColor: const Color(0xFF202020),
                        side: BorderSide(color: _mint.withValues(alpha: .4)),
                        label: Text(
                          'Sleep timer · ${_formatTime(remaining)}',
                          style: const TextStyle(color: _ink, fontSize: 11, fontWeight: FontWeight.w700),
                        ),
                        onPressed: () => widget.handler.setSleepTimer(null),
                      ),
                    );
                  },
                ),
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
                        final liveValue = position.inMilliseconds.clamp(0, maxMs.toInt()).toDouble();
                        final value = (_dragPositionMs ?? liveValue).clamp(0.0, maxMs);
                        final shownPosition = Duration(milliseconds: value.round());
                        return Column(children: <Widget>[
                          Slider(
                            min: 0,
                            max: maxMs,
                            value: value,
                            onChanged: duration == Duration.zero ? null : (next) {
                              setState(() => _dragPositionMs = next);
                            },
                            onChangeEnd: duration == Duration.zero ? null : (next) async {
                              try {
                                await widget.handler.seek(Duration(milliseconds: next.round()));
                              } finally {
                                if (mounted) setState(() => _dragPositionMs = null);
                              }
                            },
                          ),
                          Padding(padding: const EdgeInsets.symmetric(horizontal: 4), child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: <Widget>[
                            Text(_formatTime(_dragPositionMs == null ? position : shownPosition), style: const TextStyle(color: _playerMuted, fontSize: 10)),
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
                  Container(
                    width: 62,
                    height: 62,
                    decoration: const BoxDecoration(shape: BoxShape.circle, color: Colors.black),
                    child: IconButton(
                      tooltip: playing ? 'Pause' : 'Play',
                      onPressed: () => playing ? widget.handler.pause() : widget.handler.play(),
                      icon: Icon(playing ? Icons.pause_rounded : Icons.play_arrow_rounded, color: Colors.white, size: 34),
                    ),
                  ),
                  IconButton(onPressed: () => widget.handler.skipToNext(), icon: const Icon(Icons.skip_next_rounded, size: 29)),
                  IconButton(
                    tooltip: switch (widget.handler.repeatMode) {
                      FluxRepeatMode.off => 'Repeat off · tap for repeat all',
                      FluxRepeatMode.all => 'Repeat all · tap for repeat one',
                      FluxRepeatMode.one => 'Repeat one · tap to turn off',
                    },
                    onPressed: () async {
                      await widget.handler.toggleRepeat();
                      if (mounted) setState(() {});
                    },
                    icon: Icon(
                      widget.handler.repeatMode == FluxRepeatMode.one ? Icons.repeat_one_rounded : Icons.repeat_rounded,
                      color: widget.handler.repeatMode == FluxRepeatMode.off ? _playerMuted : _mint,
                      size: 21,
                    ),
                  ),
                ]),
                const SizedBox(height: 8),
                InkWell(
                  borderRadius: BorderRadius.circular(14),
                  onTap: () => showModalBottomSheet<void>(context: context, isScrollControlled: true, useSafeArea: true, backgroundColor: Colors.transparent, builder: (_) => LyricsSheet(item: item, handler: widget.handler)),
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
                    showModalBottomSheet<void>(
                      context: context,
                      isScrollControlled: true,
                      useSafeArea: true,
                      backgroundColor: Colors.transparent,
                      builder: (sheetContext) => Container(
                        height: MediaQuery.sizeOf(sheetContext).height * .7,
                        padding: const EdgeInsets.fromLTRB(18, 12, 18, 16),
                        decoration: const BoxDecoration(
                          color: Color(0xFF121212),
                          borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
                        ),
                        child: Column(children: <Widget>[
                          Container(width: 42, height: 4, decoration: BoxDecoration(color: Colors.white24, borderRadius: BorderRadius.circular(8))),
                          const SizedBox(height: 16),
                          const Align(alignment: Alignment.centerLeft, child: Text('Up next', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800))),
                          const Align(alignment: Alignment.centerLeft, child: Text('Hold to reorder · swipe to remove', style: TextStyle(color: _playerMuted, fontSize: 11))),
                          const SizedBox(height: 8),
                          Expanded(
                            child: StreamBuilder<List<MediaItem>>(
                              stream: widget.handler.queue,
                              initialData: widget.handler.queue.value,
                              builder: (context, queueSnapshot) {
                                final items = queueSnapshot.data ?? const <MediaItem>[];
                                if (items.isEmpty) {
                                  return const _EmptyState(
                                    icon: Icons.queue_music_rounded,
                                    title: 'Queue is empty',
                                    subtitle: 'Use a track’s menu to add songs to your queue.',
                                  );
                                }
                                return ReorderableListView.builder(
                                  itemCount: items.length,
                                  onReorderItem: widget.handler.moveQueueItem,
                                  itemBuilder: (context, index) {
                                    final queued = items[index];
                                    final current = index == widget.handler.currentQueueIndex;
                                    return Dismissible(
                                      key: ObjectKey(queued),
                                      direction: current ? DismissDirection.none : DismissDirection.endToStart,
                                      confirmDismiss: (_) async => index != widget.handler.currentQueueIndex,
                                      onDismissed: (_) => widget.handler.removeQueueItemAt(index),
                                      background: Container(
                                        alignment: Alignment.centerRight,
                                        padding: const EdgeInsets.only(right: 18),
                                        margin: const EdgeInsets.symmetric(vertical: 4),
                                        decoration: BoxDecoration(
                                          color: const Color(0xFF3A2024),
                                          borderRadius: BorderRadius.circular(12),
                                        ),
                                        child: const Icon(Icons.delete_outline_rounded, color: Color(0xFFFFA7B5)),
                                      ),
                                      child: ListTile(
                                        contentPadding: EdgeInsets.zero,
                                        leading: _CoverArt(url: queued.extras?['thumbnailUrl'] as String? ?? '', size: 46, radius: 9),
                                        title: Text(
                                          queued.title,
                                          maxLines: 1,
                                          overflow: TextOverflow.ellipsis,
                                          style: TextStyle(fontSize: 13, fontWeight: current ? FontWeight.w800 : FontWeight.w500),
                                        ),
                                        subtitle: Text(queued.artist ?? 'Unknown artist', maxLines: 1, style: const TextStyle(color: _playerMuted, fontSize: 11)),
                                        trailing: current ? const Icon(Icons.equalizer_rounded, color: _mint) : const Icon(Icons.drag_handle_rounded, color: _playerMuted),
                                        onTap: () {
                                          Navigator.of(sheetContext).pop();
                                          widget.handler.skipToQueueItem(index);
                                        },
                                      ),
                                    );
                                  },
                                );
                              },
                            ),
                          ),
                        ]),
                      ),
                    );
                  },
                  child: Container(
                    height: 50,
                    padding: const EdgeInsets.symmetric(horizontal: 14),
                    decoration: BoxDecoration(color: const Color(0xFF1E1E1E), borderRadius: BorderRadius.circular(13)),
                    child: Row(children: <Widget>[
                      const Icon(Icons.queue_music_rounded, color: _playerMuted, size: 20),
                      const SizedBox(width: 12),
                      const Expanded(child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
                        Text('Up next', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w800)),
                        SizedBox(height: 2),
                        Text('Your playback queue', style: TextStyle(color: _playerMuted, fontSize: 10)),
                      ])),
                      Text('${widget.handler.queue.value.length} songs', style: const TextStyle(color: _playerMuted, fontSize: 10)),
                      const SizedBox(width: 8),
                      const Icon(Icons.chevron_right_rounded, color: _playerMuted, size: 20),
                    ]),
                  ),
                )
              ]),
              ),
            ));
          },
        );
      },
    );
  }
}

class LyricsSheet extends StatefulWidget {
  const LyricsSheet({super.key, required this.item, required this.handler});
  final MediaItem item;
  final FluxAudioHandler handler;

  @override
  State<LyricsSheet> createState() => _LyricsSheetState();
}

class _LyricsSheetState extends State<LyricsSheet> {
  bool _loading = true;
  String? _lyrics;
  String? _message;
  bool _synced = false;
  final ScrollController _lyricsScrollController = ScrollController();
  List<_LyricLine> _lyricLines = <_LyricLine>[];
  int _activeLyricIndex = -1;

  @override
  void initState() {
    super.initState();
    _loadLyrics();
  }

  Future<void> _loadLyrics() async {
    setState(() {
      _loading = true;
      _lyrics = null;
      _lyricLines = <_LyricLine>[];
      _activeLyricIndex = -1;
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
        throw HttpException('Lyrics provider returned HTTP ${response.statusCode}');
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
          if (difference <= 5) {
            value += 25;
          } else if (difference <= 15) {
            value += 10;
          }
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
        _lyricLines = synced is String ? _parseLrc(synced) : <_LyricLine>[];
        _activeLyricIndex = -1;
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
  void dispose() {
    _lyricsScrollController.dispose();
    super.dispose();
  }

  List<_LyricLine> _parseLrc(String lyrics) {
    final timestamp = RegExp(r'\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\]');
    final parsed = <_LyricLine>[];
    for (final rawLine in lyrics.split(RegExp(r'\r?\n'))) {
      final matches = timestamp.allMatches(rawLine).toList();
      if (matches.isEmpty) continue;
      final text = rawLine.replaceAll(timestamp, '').trim();
      if (text.isEmpty) continue;
      for (final match in matches) {
        final minutes = int.tryParse(match.group(1) ?? '') ?? 0;
        final seconds = int.tryParse(match.group(2) ?? '') ?? 0;
        final fractional = (match.group(3) ?? '0').padRight(3, '0').substring(0, 3);
        final milliseconds = int.tryParse(fractional) ?? 0;
        parsed.add(_LyricLine(
          time: Duration(minutes: minutes, seconds: seconds, milliseconds: milliseconds),
          text: text,
        ));
      }
    }
    parsed.sort((a, b) => a.time.compareTo(b.time));
    return parsed;
  }

  Widget _syncedLyricsView() {
    return StreamBuilder<Duration>(
      stream: widget.handler.positionStream,
      initialData: Duration.zero,
      builder: (context, snapshot) {
        final position = snapshot.data ?? Duration.zero;
        var activeIndex = -1;
        for (var index = 0; index < _lyricLines.length; index++) {
          if (_lyricLines[index].time <= position) {
            activeIndex = index;
          } else {
            break;
          }
        }
        if (activeIndex != _activeLyricIndex) {
          _activeLyricIndex = activeIndex;
          if (activeIndex >= 0) {
            WidgetsBinding.instance.addPostFrameCallback((_) {
              if (!_lyricsScrollController.hasClients || !mounted) return;
              final target = (activeIndex * 58.0).clamp(
                0.0,
                _lyricsScrollController.position.maxScrollExtent,
              );
              _lyricsScrollController.animateTo(
                target,
                duration: const Duration(milliseconds: 220),
                curve: Curves.easeOut,
              );
            });
          }
        }
        return ListView.builder(
          controller: _lyricsScrollController,
          itemExtent: 58,
          itemCount: _lyricLines.length,
          itemBuilder: (context, index) {
            final line = _lyricLines[index];
            final active = index == activeIndex;
            return InkWell(
              onTap: () => widget.handler.seek(line.time),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 2, vertical: 7),
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    line.text,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: active ? _ink : _playerMuted,
                      fontSize: active ? 17 : 14,
                      height: 1.3,
                      fontWeight: active ? FontWeight.w800 : FontWeight.w500,
                    ),
                  ),
                ),
              ),
            );
          },
        );
      },
    );
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
        color: Color(0xFF121212),
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
                    : Column(
                        children: <Widget>[
                          if (_synced && _lyricLines.isNotEmpty)
                            const Padding(
                              padding: EdgeInsets.only(bottom: 6),
                              child: Text('Tap a line to seek · synced lyrics', style: TextStyle(color: _mint, fontSize: 11)),
                            ),
                          Expanded(
                            child: _synced && _lyricLines.isNotEmpty
                                ? _syncedLyricsView()
                                : SingleChildScrollView(
                                    child: SelectableText(
                                      _lyrics!.replaceAll(RegExp(r'\[\d{2}:\d{2}(?:\.\d+)?\]'), '').trim(),
                                      style: const TextStyle(fontSize: 15, height: 1.7, fontWeight: FontWeight.w500),
                                    ),
                                  ),
                          ),
                          const SizedBox(height: 14),
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

class _LyricLine {
  const _LyricLine({required this.time, required this.text});

  final Duration time;
  final String text;
}

class _DecorativeArt extends StatelessWidget {
  const _DecorativeArt({required this.size, required this.variant});
  final double size;
  final int variant;
  @override
  Widget build(BuildContext context) {
    final bases = <Color>[const Color(0xFF242126), const Color(0xFF29211F), const Color(0xFF222820), const Color(0xFF20252B)];
    final accents = <Color>[const Color(0xFFB9796A), const Color(0xFFFF8A35), const Color(0xFF6C927E), const Color(0xFF6C83A1)];
    final base = bases[variant % bases.length];
    final accent = accents[variant % accents.length];
    return Container(
      width: size, height: size, clipBehavior: Clip.antiAlias,
      decoration: BoxDecoration(borderRadius: BorderRadius.circular(14), gradient: LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight, colors: <Color>[base, Color.lerp(base, Colors.white, .08)!])),
      child: Stack(children: <Widget>[
        Positioned(right: -size * .04, top: size * .08, child: Container(width: size * .68, height: size * .68, decoration: BoxDecoration(shape: BoxShape.circle, color: accent.withValues(alpha: .88)))),
        Positioned(left: size * .10, bottom: size * .17, child: Container(width: size * .80, height: size * .14, decoration: BoxDecoration(color: const Color(0xFFFF8A35), borderRadius: BorderRadius.circular(size * .08)))),
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
    final decodeSize = (size * MediaQuery.devicePixelRatioOf(context)).round().clamp(64, 512).toInt();
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(radius),
        gradient: const LinearGradient(
          colors: <Color>[Color(0xFF3A2A22), Color(0xFF24203E)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        boxShadow: const <BoxShadow>[
          BoxShadow(color: Color(0x28000000), blurRadius: 12, offset: Offset(0, 5)),
        ],
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(radius),
        child: url.startsWith('http')
            ? Image.network(
                url,
                fit: BoxFit.cover,
                width: size,
                height: size,
                cacheWidth: decodeSize,
                cacheHeight: decodeSize,
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
  Widget build(BuildContext context) => Positioned.fill(
    child: IgnorePointer(
      child: StreamBuilder<MediaItem?>(
        stream: fluxAudio.mediaItem,
        builder: (context, snapshot) => ArtGradientBackground(
          artUrl: snapshot.data?.artUri?.toString() ?? '',
          child: const SizedBox.expand(),
        ),
      ),
    ),
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
