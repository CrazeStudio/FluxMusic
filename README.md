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
## Warning ⚠️ 
Important Notice

FluxMusic provides certain music-related features for educational, research, and personal experimentation purposes.

Any functionality involving YouTube Music data extraction, scraping, or metadata retrieval is intended solely to demonstrate technical concepts, API integration, and media-data processing.

- FluxMusic is an independent, third-party project and is not affiliated with, endorsed by, or sponsored by YouTube or Google.
- YouTube and YouTube Music are trademarks of Google LLC.
- Users are responsible for ensuring that their use of the application complies with applicable laws, copyright regulations, and YouTube's Terms of Service.
- FluxMusic does not claim ownership of any music, artwork, metadata, or other third-party content.
- Copyright remains with the respective owners and rights holders.
- Users must not use the application to download, redistribute, or exploit copyrighted content without proper authorization.
- Educational intent does not automatically grant permission to bypass access restrictions or copyright protections.

Developer Notice

This functionality is provided as an experimental educational implementation. The developers do not encourage copyright infringement or unauthorized distribution of copyrighted material.
