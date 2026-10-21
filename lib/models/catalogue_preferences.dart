/// Language choices and default search phrases used for FluxMusic's first-run
/// catalogue personalization. The queries are discovery hints, not a language
/// classifier for every track returned by YouTube Music.
class CataloguePreferences {
  const CataloguePreferences._();

  static const Map<String, String> searchTerms = <String, String>{
    'Hindi': 'Hindi new songs hits',
    'Bengali': 'Bengali Bangla songs',
    'English': 'English pop hits',
    'Tamil': 'Tamil hit songs',
    'Telugu': 'Telugu hit songs',
    'Punjabi': 'Punjabi hit songs',
    'Marathi': 'Marathi songs hits',
    'Malayalam': 'Malayalam hit songs',
    'Kannada': 'Kannada hit songs',
    'Urdu': 'Urdu songs',
    'Japanese': 'J-pop Japanese songs',
    'Korean': 'K-pop Korean songs',
    'Spanish': 'Spanish pop songs',
    'Instrumental': 'instrumental music',
  };

  static const List<String> languages = <String>[
    'Hindi',
    'Bengali',
    'English',
    'Tamil',
    'Telugu',
    'Punjabi',
    'Marathi',
    'Malayalam',
    'Kannada',
    'Urdu',
    'Japanese',
    'Korean',
    'Spanish',
    'Instrumental',
  ];

  static String queryFor(String language) => searchTerms[language] ?? '$language music';

  static bool isPersonalized({
    required bool randomized,
    required Iterable<String> languages,
  }) =>
      !randomized && languages.isNotEmpty;
}
