package com.crazestudio.fluxmusic.data.model

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Warm Light"),
    DARK("Studio Dark")
}

enum class AudioQuality(val label: String, val description: String) {
    AUTO("Balanced", "Adapts smoothly for reliable streaming"),
    HIGH("High Fidelity", "Highest available audio bitrate"),
    DATA_SAVER("Data Saver", "Lower bitrate to conserve bandwidth")
}

enum class EqualizerPreset(val label: String) {
    FLAT("Flat"),
    BASS_BOOST("Bass Boost"),
    VOCAL("Vocal"),
    ACOUSTIC("Acoustic"),
    LATE_NIGHT("Late Night"),
    CUSTOM("Custom")
}
