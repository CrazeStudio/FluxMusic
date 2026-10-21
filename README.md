# FluxMusic

**FluxMusic v1.5** is a dark, animated Flutter music app built by CrazeStudio. It uses `dart_ytmusic_api` to discover tracks and `just_audio` + `audio_service` for playback, background operation, and Android media controls.

## Features

- YouTube Music song search and track metadata through `dart_ytmusic_api`
- Audio playback with play/pause, previous/next, seek, and a queue
- Android background playback, media notification, lock-screen controls, and headset media buttons
- Supplied music-note branding in the app and adaptive Android launcher icons
- Stream resolver retries several YouTube client strategies and reports actionable playback errors
- Animated dark UI, mood shortcuts, mini-player, rotating artwork, and full player sheet
- First-run multi-select language personalization, with a skip option for a randomized mixed catalogue
- Language-focused music discovery shortcuts for Hindi, Bengali, English, Tamil, Telugu, Punjabi, and more
- Device-local playlists: create collections, add search results, and play a playlist
- Search public YouTube Music playlists and load their track lists into the app
- Persistent streaming quality targets: Data saver, Balanced, and High; the app selects among available source bitrates rather than upscaling audio
- Persistent Android audio effects: normalization, limiter, dynamic-range compression, and bass boost
- Reduced cover-art decode sizes, bounded search caches, audio-manifest caching, and next-track manifest prefetch
- Locally persisted favourites and listening history
- Android internet, wake-lock, foreground media playback, and notification permission setup
- GitHub Actions workflow that builds a release APK artifact

## Build on Android / Termux

Install the Flutter SDK and Android build prerequisites first. From the repository root:

```bash
flutter create . --platforms=android --org=dev.crazestudio --project-name=fluxmusic --no-pub
python3 tool/patch_android.py
flutter pub get
flutter run
```

Build a release APK:

```bash
flutter build apk --release
```

The APK is written to `build/app/outputs/flutter-apk/app-release.apk`.

You can also open **Actions → Build FluxMusic APK** on GitHub after a push. Download the `fluxmusic-android-release` artifact from a successful workflow run.

## Important notes

- The app needs an internet connection for catalogue search and stream resolution.
- `dart_ytmusic_api` is an **unofficial** YouTube Music API client. Search can stop working when upstream responses change.
- YouTube Music playlist search and track-list retrieval are unofficial, read-only discovery features. User-created playlists are stored locally on this device; creating/editing playlists in a signed-in YouTube Music account is not provided.
- Audio URLs are resolved from YouTube video IDs at playback time. This relies on an unofficial reverse-engineered endpoint that can break when YouTube changes access rules. FluxMusic pins a tested `youtube_explode_dart` fork revision that adds the visionOS client to address current audio-stream HTTP 403 rejections. The app lets the extractor choose its client instead of forcing a stale client. YouTube may still restrict individual videos or rate-limit requests; reliable long-term production playback requires a licensed music provider or an official playback SDK.
- Use the app only for content you are authorized to access. Do not use it to download or redistribute copyrighted material.
- For Android 13+, allow notification permission when prompted. Some manufacturers apply aggressive battery restrictions; you may need to allow background activity for reliable playback.
- The package `dart_ytmusic_api` is GPL-3.0 licensed. Review its license and the terms of every dependency before distributing a modified app.

## Project layout

- `lib/main.dart` — first-run personalization, catalogue, song/playlist search, local playlists, favourites, recent history, audio settings
- `lib/models/track.dart` — normalizes YouTube Music search results
- `lib/models/catalogue_preferences.dart` — language choices, search phrases, and personalization mode
- `lib/services/flux_audio_handler.dart` — queue, audio resolution, quality preference, playback state, media session
- `test/catalogue_preferences_test.dart` — personalization model tests
- `CHANGELOG.md` — v1.5 changes and limitations
- `tool/patch_android.py` — adds required Android permissions and audio-service declarations to a Flutter-generated Android project
- `.github/workflows/build-apk.yml` — builds and uploads the APK artifact
