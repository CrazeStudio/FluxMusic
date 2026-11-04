import 'dart:async';

import 'package:flutter/material.dart';

/// Debounced suggestion list. Place under the search field:
/// `SearchSuggestions(query: _controller.text, fetch: catalog.suggestions, onPick: _search)`
class SearchSuggestions extends StatefulWidget {
  const SearchSuggestions({super.key, required this.query, required this.fetch, required this.onPick});
  final String query;
  final Future<List<String>> Function(String query) fetch;
  final ValueChanged<String> onPick;

  @override
  State<SearchSuggestions> createState() => _SearchSuggestionsState();
}

class _SearchSuggestionsState extends State<SearchSuggestions> {
  Timer? _debounce;
  List<String> _items = const <String>[];
  int _token = 0;

  @override
  void didUpdateWidget(SearchSuggestions old) {
    super.didUpdateWidget(old);
    if (old.query != widget.query) _schedule();
  }

  @override
  void initState() {
    super.initState();
    _schedule();
  }

  void _schedule() {
    _debounce?.cancel();
    final token = ++_token;
    _debounce = Timer(const Duration(milliseconds: 250), () async {
      final result = await widget.fetch(widget.query);
      if (mounted && token == _token) setState(() => _items = result);
    });
  }

  @override
  void dispose() {
    _debounce?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_items.isEmpty) return const SizedBox.shrink();
    return Column(
      children: [
        for (final s in _items)
          ListTile(
            dense: true,
            leading: const Icon(Icons.search_rounded, size: 20),
            title: Text(s, maxLines: 1, overflow: TextOverflow.ellipsis),
            trailing: const Icon(Icons.north_west_rounded, size: 16),
            onTap: () => widget.onPick(s),
          ),
      ],
    );
  }
}
