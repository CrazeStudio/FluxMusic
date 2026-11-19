# FluxMusic Flutter rebuild

The Flutter app is being moved to the repository root. Native Kotlin/Gradle source is preserved on the `backup/native-gradle` branch before replacement.

## Android identity retained
- Application ID: `com.crazestudio.fluxmusic`
- Minimum Android: API 24
- Target SDK: 36 (Flutter-generated scaffold may update this)
- Version: 3.2.0+5

## Current status
UI foundation only. Search and playback are not yet wired to real providers. The build workflow generates Flutter's Android Gradle project and builds a debug APK.
