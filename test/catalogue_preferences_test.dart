import 'package:flutter_test/flutter_test.dart';
import 'package:fluxmusic/models/catalogue_preferences.dart';

void main() {
  group('CataloguePreferences', () {
    test('offers multi-language choices including Hindi and Bengali', () {
      expect(CataloguePreferences.languages, containsAll(<String>['Hindi', 'Bengali', 'English']));
      expect(CataloguePreferences.languages.toSet().length, CataloguePreferences.languages.length);
      expect(CataloguePreferences.languages.length, greaterThanOrEqualTo(10));
    });

    test('builds focused search phrases for supported languages', () {
      expect(CataloguePreferences.queryFor('Hindi'), 'Hindi new songs hits');
      expect(CataloguePreferences.queryFor('Bengali'), 'Bengali Bangla songs');
      expect(CataloguePreferences.queryFor('Japanese'), 'J-pop Japanese songs');
    });

    test('falls back safely for a language not in the preset list', () {
      expect(CataloguePreferences.queryFor('Basque'), 'Basque music');
    });

    test('uses mixed catalogue when skipped and personalizes when languages exist', () {
      expect(
        CataloguePreferences.isPersonalized(randomized: true, languages: <String>['Hindi']),
        isFalse,
      );
      expect(
        CataloguePreferences.isPersonalized(randomized: false, languages: <String>[]),
        isFalse,
      );
      expect(
        CataloguePreferences.isPersonalized(randomized: false, languages: <String>['Hindi', 'Bengali']),
        isTrue,
      );
    });
  });
}
