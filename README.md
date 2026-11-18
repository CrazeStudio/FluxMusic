# FluxMusic

FluxMusic is a native Android music player built with Kotlin, Jetpack Compose, Room, AndroidX Media3 and a MediaSessionService.

## App features

- Home discovery with language filters and featured music artwork.
- YouTube Music catalogue search with recent-search history.
- Audio playback through the native Media3 player and a foreground media service.
- Native YouTube Innertube player API audio stream resolution (no yt-dlp, Python or QuickJS).
- Background playback, notification/media controls, queue management, seek, previous/next, shuffle and repeat.
- Mini-player and expanded now-playing screen.
- Liked Songs, recently played tracks and user-created playlists.
- Lyrics lookup and local lyrics caching.
- Audio quality selection (Data Saver, Auto and High).
- Equalizer controls, presets and bass boost where supported by the device.
- Sleep timer, theme selection and offline-cache mode.
- Room persistence for library data, playlists, playback history, lyrics and preferences.
- Restored FluxMusic launcher icon and bundled artwork/fonts.

## Requirements

- Android 7.0 (API 24) or newer.
- arm64-v8a device build.
- Internet access for online discovery, lyrics and stream resolution.
- Android 13+ notification permission for visible playback notifications.

## Build

The project uses Gradle and Java 17.

```bash
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

APK output:

`app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions runs unit tests and builds a debug APK on pushes and pull requests.

## Playback and provider limitations

Online catalogue metadata and native player requests use YouTube Music endpoints. YouTube may withhold direct media URLs, require a PO token or sign-in, or return ciphered formats. The native resolver deliberately rejects formats it cannot play rather than launching a heavyweight external extractor. Availability can change due to region, network, account, age, bot-detection or provider-side changes; no third-party client can guarantee every track is playable.

FluxMusic is an independent project and is not affiliated with YouTube or Google. Users are responsible for complying with applicable laws, copyright rules and provider terms. Do not use the app to download, redistribute or exploit copyrighted material without authorization.
