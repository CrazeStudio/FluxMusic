import 'package:audio_service/audio_service.dart';
import 'package:flutter/material.dart';

import '../services/music_catalog.dart';
import '../widgets/art_gradient.dart';
import '../widgets/skeleton.dart';

/// One page for both artists and albums. Open with
/// `Navigator.push(context, MaterialPageRoute(builder: (_) => CollectionPage(load: () => catalog.artist(id), onPlay: _playQueue)))`
class CollectionPage extends StatefulWidget {
  const CollectionPage({super.key, required this.load, required this.onPlay});
  final Future<CatalogPage> Function() load;

  /// Called with the full list and the tapped index; wire to `fluxAudio.playTracks`.
  final void Function(List<MediaItem> tracks, int index, {bool shuffle}) onPlay;

  @override
  State<CollectionPage> createState() => _CollectionPageState();
}

class _CollectionPageState extends State<CollectionPage> {
  late Future<CatalogPage> _future = widget.load();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: FutureBuilder<CatalogPage>(
        future: _future,
        builder: (context, snap) {
          if (snap.hasError) {
            return Center(
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                const Text('Could not load this page.'),
                TextButton(onPressed: () => setState(() => _future = widget.load()), child: const Text('Retry')),
              ]),
            );
          }
          if (!snap.hasData) {
            return ListView(padding: const EdgeInsets.only(top: 120), children: List.generate(8, (_) => const TrackRowSkeleton()));
          }
          final page = snap.data!;
          return ArtGradientBackground(
            artUrl: page.artUrl ?? '',
            child: CustomScrollView(slivers: [
              SliverAppBar(backgroundColor: Colors.transparent, pinned: true, title: Text(page.title, maxLines: 1)),
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(children: [
                    if ((page.artUrl ?? '').isNotEmpty)
                      ClipRRect(
                        borderRadius: BorderRadius.circular(16),
                        child: Image.network(page.artUrl!, width: 200, height: 200, fit: BoxFit.cover,
                            errorBuilder: (_, __, ___) => const SizedBox(width: 200, height: 200)),
                      ),
                    const SizedBox(height: 12),
                    Text(page.subtitle, style: Theme.of(context).textTheme.bodyMedium),
                    const SizedBox(height: 12),
                    Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                      FilledButton.icon(
                        onPressed: page.tracks.isEmpty ? null : () => widget.onPlay(page.tracks, 0, shuffle: false),
                        icon: const Icon(Icons.play_arrow_rounded),
                        label: const Text('Play'),
                      ),
                      const SizedBox(width: 12),
                      OutlinedButton.icon(
                        onPressed: page.tracks.isEmpty ? null : () => widget.onPlay(page.tracks, 0, shuffle: true),
                        icon: const Icon(Icons.shuffle_rounded),
                        label: const Text('Shuffle'),
                      ),
                    ]),
                  ]),
                ),
              ),
              SliverList.builder(
                itemCount: page.tracks.length,
                itemBuilder: (context, i) {
                  final t = page.tracks[i];
                  return ListTile(
                    leading: Text('${i + 1}', style: Theme.of(context).textTheme.bodyMedium),
                    title: Text(t.title, maxLines: 1, overflow: TextOverflow.ellipsis),
                    subtitle: Text(t.artist ?? '', maxLines: 1, overflow: TextOverflow.ellipsis),
                    onTap: () => widget.onPlay(page.tracks, i, shuffle: false),
                  );
                },
              ),
              const SliverToBoxAdapter(child: SizedBox(height: 96)),
            ]),
          );
        },
      ),
    );
  }
}
