import 'package:flutter/material.dart';

void main() => runApp(const FluxMusicApp());

const bg = Color(0xFF101010);
const surface = Color(0xFF1B1B1B);
const accent = Color(0xFFFF7A18);

class FluxMusicApp extends StatelessWidget {
  const FluxMusicApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'FluxMusic',
    theme: ThemeData(
      brightness: Brightness.dark,
      scaffoldBackgroundColor: bg,
      colorScheme: const ColorScheme.dark(primary: accent, surface: surface),
      fontFamily: 'Roboto',
      useMaterial3: true,
    ),
    home: const MusicHome(),
  );
}

class Track {
  final String title, artist, art;
  const Track(this.title, this.artist, this.art);
}

const tracks = [
  Track('Night Changes', 'One Direction', 'https://i.ytimg.com/vi/syFZfO_wfMQ/hqdefault.jpg'),
  Track('Heat Waves', 'Glass Animals', 'https://i.ytimg.com/vi/mRD0-GxqHVo/hqdefault.jpg'),
  Track('The Nights', 'Avicii', 'https://i.ytimg.com/vi/UtF6Jej8yb4/hqdefault.jpg'),
  Track('Husn', 'Anuv Jain', 'https://i.ytimg.com/vi/g7s2L7G8XcA/hqdefault.jpg'),
];

class MusicHome extends StatefulWidget {
  const MusicHome({super.key});
  @override
  State<MusicHome> createState() => _MusicHomeState();
}

class _MusicHomeState extends State<MusicHome> {
  int tab = 0;
  Track current = tracks[0];
  bool playing = false;
  final search = TextEditingController();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: CustomScrollView(
          slivers: [
            SliverToBoxAdapter(child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 22, 20, 14),
              child: Row(children: [
                const Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('FLUXMUSIC', style: TextStyle(color: accent, fontWeight: FontWeight.w900, letterSpacing: 2.4, fontSize: 12)),
                  SizedBox(height: 5),
                  Text('Good music.\nNo limits.', style: TextStyle(fontSize: 28, fontWeight: FontWeight.w800, height: 1.1)),
                ])),
                IconButton(onPressed: () {}, icon: const Icon(Icons.settings_outlined, size: 25)),
              ]),
            )),
            SliverToBoxAdapter(child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 18),
              child: TextField(
                controller: search,
                onSubmitted: (q) => setState(() => tab = 1),
                decoration: InputDecoration(
                  hintText: 'Search songs, artists, albums',
                  prefixIcon: const Icon(Icons.search),
                  filled: true, fillColor: surface,
                  border: OutlineInputBorder(borderRadius: BorderRadius.circular(16), borderSide: BorderSide.none),
                ),
              ),
            )),
            SliverToBoxAdapter(child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 24, 20, 12),
              child: const Text('Made for you', style: TextStyle(fontSize: 21, fontWeight: FontWeight.bold)),
            )),
            SliverToBoxAdapter(child: SizedBox(height: 190, child: ListView(
              scrollDirection: Axis.horizontal, padding: const EdgeInsets.symmetric(horizontal: 18),
              children: [
                _featureCard('Your daily mix', 'A little of everything', const Color(0xFF6B3A91), Icons.auto_awesome),
                _featureCard('Fresh releases', 'New music for you', const Color(0xFF9A4A20), Icons.album),
                _featureCard('Discover', 'Find your next favourite', const Color(0xFF245B55), Icons.explore),
              ],
            ))),
            SliverToBoxAdapter(child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 26, 20, 12),
              child: Row(children: [const Expanded(child: Text('Quick picks', style: TextStyle(fontSize: 21, fontWeight: FontWeight.bold))), TextButton(onPressed: () => setState(() => tab = 1), child: const Text('See all'))]),
            )),
            SliverList(delegate: SliverChildBuilderDelegate((context, i) => _trackRow(tracks[i]), childCount: tracks.length)),
            const SliverToBoxAdapter(child: SizedBox(height: 130)),
          ],
        ),
      ),
      bottomNavigationBar: _bottomBar(),
    );
  }

  Widget _featureCard(String title, String subtitle, Color color, IconData icon) => Container(
    width: 155, margin: const EdgeInsets.only(right: 12), padding: const EdgeInsets.all(16),
    decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(20)),
    child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
      Icon(icon, size: 34), Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(title, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.bold)),
        const SizedBox(height: 4), Text(subtitle, style: const TextStyle(fontSize: 12, color: Colors.white70)),
      ]),
    ]),
  );

  Widget _trackRow(Track t) => ListTile(
    contentPadding: const EdgeInsets.symmetric(horizontal: 18, vertical: 3),
    leading: _art(t.art, 54),
    title: Text(t.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w600)),
    subtitle: Text(t.artist),
    trailing: IconButton(icon: const Icon(Icons.more_vert), onPressed: () {}),
    onTap: () => setState(() { current = t; playing = true; }),
  );

  Widget _art(String url, double size) => ClipRRect(
    borderRadius: BorderRadius.circular(10), width: size, height: size,
    child: Image.network(url, fit: BoxFit.cover, errorBuilder: (_, __, ___) => Container(color: surface, child: const Icon(Icons.music_note))),
  );

  Widget _bottomBar() => Container(
    decoration: const BoxDecoration(color: bg, border: Border(top: BorderSide(color: Colors.white10))),
    child: SafeArea(top: false, child: Column(mainAxisSize: MainAxisSize.min, children: [
      if (current.title.isNotEmpty) InkWell(onTap: () => _openPlayer(), child: Container(
        height: 64, margin: const EdgeInsets.fromLTRB(10, 8, 10, 4), padding: const EdgeInsets.symmetric(horizontal: 10),
        decoration: BoxDecoration(color: surface, borderRadius: BorderRadius.circular(13)),
        child: Row(children: [
          _art(current.art, 44), const SizedBox(width: 10),
          Expanded(child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(current.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w600)),
            Text(current.artist, style: const TextStyle(color: Colors.white60, fontSize: 12)),
          ])),
          IconButton(onPressed: () => setState(() => playing = !playing), icon: Icon(playing ? Icons.pause : Icons.play_arrow)),
          IconButton(onPressed: () => setState(() => current = tracks[(tracks.indexOf(current) + 1) % tracks.length]), icon: const Icon(Icons.skip_next)),
        ]),
      )),
      NavigationBar(height: 64, backgroundColor: bg, selectedIndex: tab, onDestinationSelected: (i) => setState(() => tab = i), destinations: const [
        NavigationDestination(icon: Icon(Icons.home_outlined), selectedIcon: Icon(Icons.home), label: 'Home'),
        NavigationDestination(icon: Icon(Icons.search), label: 'Search'),
        NavigationDestination(icon: Icon(Icons.library_music_outlined), label: 'Library'),
      ]),
    ])),
  );

  void _openPlayer() => showModalBottomSheet(context: context, isScrollControlled: true, backgroundColor: bg, builder: (_) => PlayerSheet(track: current, playing: playing, onToggle: () => setState(() => playing = !playing)));
}

class PlayerSheet extends StatelessWidget {
  final Track track;
  final bool playing;
  final VoidCallback onToggle;
  const PlayerSheet({super.key, required this.track, required this.playing, required this.onToggle});

  @override
  Widget build(BuildContext context) => SizedBox(height: MediaQuery.of(context).size.height * .88, child: Padding(
    padding: const EdgeInsets.all(24), child: Column(children: [
      Row(children: [IconButton(onPressed: () => Navigator.pop(context), icon: const Icon(Icons.keyboard_arrow_down)), const Spacer(), const Text('NOW PLAYING', style: TextStyle(letterSpacing: 2, fontWeight: FontWeight.bold)), const Spacer(), IconButton(onPressed: () {}, icon: const Icon(Icons.more_horiz))]),
      const Spacer(),
      ClipRRect(borderRadius: BorderRadius.circular(22), child: Image.network(track.art, width: double.infinity, height: 310, fit: BoxFit.cover, errorBuilder: (_, __, ___) => Container(height: 310, color: surface, child: const Icon(Icons.music_note, size: 100)))),
      const SizedBox(height: 30),
      Row(children: [Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(track.title, style: const TextStyle(fontSize: 25, fontWeight: FontWeight.bold)), Text(track.artist, style: const TextStyle(color: Colors.white60, fontSize: 16))])), IconButton(onPressed: () {}, icon: const Icon(Icons.favorite_border, color: accent))]),
      const SizedBox(height: 24),
      const LinearProgressIndicator(value: 0, backgroundColor: Colors.white12, color: accent),
      const Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [Text('0:00'), Text('--:--')]),
      const SizedBox(height: 18),
      Row(mainAxisAlignment: MainAxisAlignment.spaceEvenly, children: [IconButton(onPressed: () {}, icon: const Icon(Icons.shuffle)), IconButton(onPressed: () {}, icon: const Icon(Icons.skip_previous, size: 40)), IconButton(onPressed: onToggle, icon: Icon(playing ? Icons.pause_circle_filled : Icons.play_circle_fill, size: 70, color: accent)), IconButton(onPressed: () {}, icon: const Icon(Icons.skip_next, size: 40)), IconButton(onPressed: () {}, icon: const Icon(Icons.repeat))]),
      const Spacer(),
    ]),
  ));
}
