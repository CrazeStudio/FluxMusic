package com.crazestudio.fluxmusic.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.crazestudio.fluxmusic.data.model.AudioQuality
import com.crazestudio.fluxmusic.data.model.EqualizerPreset
import com.crazestudio.fluxmusic.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.fluxPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "flux_privacy_prefs")

data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val offlineCacheMode: Boolean = false,
    val audioQuality: AudioQuality = AudioQuality.AUTO,
    val eqEnabled: Boolean = false,
    val eqPreset: EqualizerPreset = EqualizerPreset.FLAT,
    val eqBands: List<Int> = emptyList(),
    val bassBoostStrength: Int = 0,
    val playbackSpeed: Float = 1.0f
)

class UserPreferencesRepository(private val context: Context) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val OFFLINE_CACHE_MODE = booleanPreferencesKey("offline_cache_mode")
        val AUDIO_QUALITY = stringPreferencesKey("audio_quality")
        val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        val EQ_PRESET = stringPreferencesKey("eq_preset")
        val EQ_BANDS = stringPreferencesKey("eq_bands")
        val BASS_BOOST = intPreferencesKey("bass_boost")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
    }

    val preferencesFlow: Flow<UserPreferences> = context.fluxPrefsStore.data.map { prefs ->
        val theme = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM
        val quality = prefs[Keys.AUDIO_QUALITY]?.let { runCatching { AudioQuality.valueOf(it) }.getOrNull() }
            ?: AudioQuality.AUTO
        val preset = prefs[Keys.EQ_PRESET]?.let { runCatching { EqualizerPreset.valueOf(it) }.getOrNull() }
            ?: EqualizerPreset.FLAT
        val bands = prefs[Keys.EQ_BANDS]
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: emptyList()
        UserPreferences(
            themeMode = theme,
            offlineCacheMode = prefs[Keys.OFFLINE_CACHE_MODE] ?: false,
            audioQuality = quality,
            eqEnabled = prefs[Keys.EQ_ENABLED] ?: false,
            eqPreset = preset,
            eqBands = bands,
            bassBoostStrength = (prefs[Keys.BASS_BOOST] ?: 0).coerceIn(0, 1000),
            playbackSpeed = (prefs[Keys.PLAYBACK_SPEED] ?: 1.0f).coerceIn(0.5f, 2.0f)
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.fluxPrefsStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setOfflineCacheMode(enabled: Boolean) {
        context.fluxPrefsStore.edit { it[Keys.OFFLINE_CACHE_MODE] = enabled }
    }

    suspend fun setAudioQuality(quality: AudioQuality) {
        context.fluxPrefsStore.edit { it[Keys.AUDIO_QUALITY] = quality.name }
    }

    suspend fun setEqualizer(enabled: Boolean, preset: EqualizerPreset, bands: List<Int>, bassBoost: Int) {
        context.fluxPrefsStore.edit {
            it[Keys.EQ_ENABLED] = enabled
            it[Keys.EQ_PRESET] = preset.name
            it[Keys.EQ_BANDS] = bands.joinToString(",")
            it[Keys.BASS_BOOST] = bassBoost.coerceIn(0, 1000)
        }
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        context.fluxPrefsStore.edit { it[Keys.PLAYBACK_SPEED] = speed.coerceIn(0.5f, 2.0f) }
    }
}
