# FluxMusic — Native Android

Native Android music player built with Java, Android views, AndroidX Media3 and a MediaSessionService.

## Current functionality
- Warm-white UI based on the supplied three-screen design.
- Home, Search, Library, Playlists and Settings tabs.
- Device music discovery via Android MediaStore.
- Search by local track title, artist and album.
- Recently played and recent searches.
- Liked Songs and user-created playlists.
- Queue, play next, shuffle, repeat-one, seek, previous and next.
- Background playback through MediaSessionService and Android media controls.

## Build
GitHub Actions builds a debug APK on pushes to main. The APK artifact is named FluxMusic-debug-apk.
Local build command: gradle --no-daemon :app:assembleDebug
Output: app/build/outputs/apk/debug/app-debug.apk

## Scope
This version plays audio available on the user's device. It does not include a commercial streaming catalogue, Spotify account integration, lyrics service, cross-device sync or online recommendations. Such functionality requires authorized provider APIs, backend services and credentials. The UI must not present placeholder content as a real streaming catalogue.
