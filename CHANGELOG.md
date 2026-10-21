# Changelog

## 1.5.0 — 2026-10-21

### Added
- First-launch language personalization with multi-select language chips.
- Skip onboarding option for a randomized mixed catalogue.
- Language-focused catalogue shortcuts and a way to revisit language preferences.
- Device-local playlists with create, add-track, play-all, and delete actions.
- Public YouTube Music playlist search and track-list import into local playlists.
- Data saver, balanced, and high-bitrate streaming preferences.
- Persistent Android playback enhancement settings.
- Catalogue preference unit tests.

### Improved
- Removed the “Good music, good mood” headline.
- Updated the SVG launcher artwork to the pale, engraved music-note style.
- Reduced decoded cover-art dimensions to fit display size and device pixel ratio.
- Kept catalogue initialization from blocking the first-run preference screen.
- Added/retained search caching, audio-manifest caching, and next-track prefetch.

### Notes
- YouTube Music search and playlist retrieval use an unofficial, read-only library and may break if upstream changes.
- Playlists created in FluxMusic are stored locally. This version does not edit a signed-in YouTube Music account.
- Quality choices select among available source bitrates; they do not create lossless audio or upscale the source.
- Sound effects vary by Android version and device.
