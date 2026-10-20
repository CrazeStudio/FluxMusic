# FluxMusic

**FluxMusic** is a dark, animated Flutter music app built by CrazeStudio. It uses `dart_ytmusic_api` to discover tracks and `just_audio` + `audio_service` for playback, background operation, and Android media controls.

## Features

- YouTube Music song search and track metadata through `dart_ytmusic_api`
- Audio playback with play/pause, previous/next, seek, and a queue
- Android background playback, media notification, lock-screen controls, and headset media buttons
- Custom FluxMusic neon launcher icon and adaptive Android icon resources
- Stream resolver retries several YouTube client strategies and reports actionable playback errors
- Animated dark UI, mood shortcuts, mini-player, rotating artwork, and full player sheet
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
- Audio URLs are resolved from YouTube video IDs at playback time. This relies on an unofficial reverse-engineered endpoint that can break when YouTube changes access rules. FluxMusic retries several stream-client strategies and shows a readable error, but cannot guarantee direct playback for every video. For reliable long-term production playback, use a licensed music provider or an official playback SDK.
- Use the app only for content you are authorized to access. Do not use it to download or redistribute copyrighted material.
- For Android 13+, allow notification permission when prompted. Some manufacturers apply aggressive battery restrictions; you may need to allow background activity for reliable playback.
- The package `dart_ytmusic_api` is GPL-3.0 licensed. Review its license and the terms of every dependency before distributing a modified app.

## Project layout

- `lib/main.dart` — FluxMusic UI, search, favourites, recent history
- `lib/models/track.dart` — normalizes YouTube Music search results
- `lib/services/flux_audio_handler.dart` — queue, audio resolution, playback state, media session
- `tool/patch_android.py` — adds required Android permissions and audio-service declarations to a Flutter-generated Android project
- `.github/workflows/build-apk.yml` — builds and uploads the APK artifact
