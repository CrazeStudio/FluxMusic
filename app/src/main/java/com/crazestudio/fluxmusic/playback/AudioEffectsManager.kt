package com.crazestudio.fluxmusic.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.os.Bundle
import com.crazestudio.fluxmusic.data.model.EqualizerPreset
import kotlin.math.roundToInt

data class EqualizerState(
    val supported: Boolean = false,
    val enabled: Boolean = false,
    val preset: EqualizerPreset = EqualizerPreset.FLAT,
    val minBandLevelMb: Int = -1500,
    val maxBandLevelMb: Int = 1500,
    val centerFreqHz: List<Int> = listOf(60, 230, 910, 3600, 14000),
    val bandLevelsMb: List<Int> = listOf(0, 0, 0, 0, 0),
    val bassBoostStrength: Int = 0
) {
    fun toBundle(): Bundle = Bundle().apply {
        putBoolean("eq_supported", supported)
        putBoolean("eq_enabled", enabled)
        putString("eq_preset", preset.name)
        putInt("eq_min_mb", minBandLevelMb)
        putInt("eq_max_mb", maxBandLevelMb)
        putIntArray("eq_freqs", centerFreqHz.toIntArray())
        putIntArray("eq_levels", bandLevelsMb.toIntArray())
        putInt("eq_bass", bassBoostStrength)
    }

    companion object {
        fun fromBundle(b: Bundle): EqualizerState {
            val presetName = b.getString("eq_preset") ?: EqualizerPreset.FLAT.name
            val preset = runCatching { EqualizerPreset.valueOf(presetName) }.getOrDefault(EqualizerPreset.FLAT)
            return EqualizerState(
                supported = b.getBoolean("eq_supported", false),
                enabled = b.getBoolean("eq_enabled", false),
                preset = preset,
                minBandLevelMb = b.getInt("eq_min_mb", -1500),
                maxBandLevelMb = b.getInt("eq_max_mb", 1500),
                centerFreqHz = b.getIntArray("eq_freqs")?.toList()?.takeIf { it.isNotEmpty() }
                    ?: listOf(60, 230, 910, 3600, 14000),
                bandLevelsMb = b.getIntArray("eq_levels")?.toList()?.takeIf { it.isNotEmpty() }
                    ?: listOf(0, 0, 0, 0, 0),
                bassBoostStrength = b.getInt("eq_bass", 0)
            )
        }
    }
}

class AudioEffectsManager {
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var currentSessionId: Int = 0

    var state: EqualizerState = EqualizerState()
        private set

    fun attachToSession(audioSessionId: Int, savedEnabled: Boolean, savedPreset: EqualizerPreset, savedBands: List<Int>, savedBass: Int) {
        if (audioSessionId == 0) return
        if (audioSessionId == currentSessionId && equalizer != null) {
            applyConfig(savedEnabled, savedPreset, savedBands, savedBass)
            return
        }
        release()
        currentSessionId = audioSessionId
        try {
            val eq = Equalizer(0, audioSessionId)
            val numBands = eq.numberOfBands.toInt().coerceAtLeast(1)
            val range = eq.bandLevelRange
            val minMb = range[0].toInt()
            val maxMb = range[1].toInt()
            val freqs = (0 until numBands).map { idx -> eq.getCenterFreq(idx.toShort()) / 1000 }
            val initialLevels = if (savedBands.size == numBands) {
                savedBands.map { it.coerceIn(minMb, maxMb) }
            } else {
                List(numBands) { 0 }
            }
            equalizer = eq

            val bass = runCatching { BassBoost(0, audioSessionId) }.getOrNull()
            bassBoost = bass

            state = EqualizerState(
                supported = true,
                enabled = savedEnabled,
                preset = savedPreset,
                minBandLevelMb = minMb,
                maxBandLevelMb = maxMb,
                centerFreqHz = freqs,
                bandLevelsMb = initialLevels,
                bassBoostStrength = savedBass.coerceIn(0, 1000)
            )
            applyToHardware()
        } catch (_: Throwable) {
            release()
            state = EqualizerState(supported = false)
        }
    }

    fun applyConfig(enabled: Boolean, preset: EqualizerPreset, bands: List<Int>, bassStrength: Int) {
        val count = state.centerFreqHz.size
        val clampedBands = if (bands.size == count) {
            bands.map { it.coerceIn(state.minBandLevelMb, state.maxBandLevelMb) }
        } else {
            state.bandLevelsMb
        }
        state = state.copy(
            enabled = enabled,
            preset = preset,
            bandLevelsMb = clampedBands,
            bassBoostStrength = bassStrength.coerceIn(0, 1000)
        )
        applyToHardware()
    }

    fun setBandLevel(bandIndex: Int, levelMb: Int) {
        if (bandIndex !in state.bandLevelsMb.indices) return
        val updated = state.bandLevelsMb.toMutableList()
        updated[bandIndex] = levelMb.coerceIn(state.minBandLevelMb, state.maxBandLevelMb)
        state = state.copy(
            preset = EqualizerPreset.CUSTOM,
            bandLevelsMb = updated
        )
        applyToHardware()
    }

    fun applyPreset(preset: EqualizerPreset) {
        val count = state.centerFreqHz.size
        val minMb = state.minBandLevelMb
        val maxMb = state.maxBandLevelMb
        val ratios = when (preset) {
            EqualizerPreset.FLAT -> List(count) { 0f }
            EqualizerPreset.BASS_BOOST -> listOf(0.55f, 0.35f, 0.05f, -0.05f, 0.1f)
            EqualizerPreset.VOCAL -> listOf(-0.2f, 0.05f, 0.45f, 0.4f, 0.1f)
            EqualizerPreset.ACOUSTIC -> listOf(0.25f, 0.15f, 0.1f, 0.28f, 0.32f)
            EqualizerPreset.LATE_NIGHT -> listOf(-0.15f, 0.15f, 0.2f, 0.1f, -0.25f)
            EqualizerPreset.CUSTOM -> return
        }
        val levels = (0 until count).map { idx ->
            val ratio = ratios.getOrElse(idx) { 0f }
            val raw = if (ratio >= 0f) (maxMb * ratio) else (-minMb * ratio)
            raw.roundToInt().coerceIn(minMb, maxMb)
        }
        val bass = when (preset) {
            EqualizerPreset.BASS_BOOST -> 520
            EqualizerPreset.ACOUSTIC -> 180
            EqualizerPreset.FLAT -> 0
            else -> state.bassBoostStrength
        }
        state = state.copy(
            enabled = true,
            preset = preset,
            bandLevelsMb = levels,
            bassBoostStrength = bass
        )
        applyToHardware()
    }

    private fun applyToHardware() {
        val eq = equalizer ?: return
        try {
            eq.enabled = state.enabled
            state.bandLevelsMb.forEachIndexed { idx, level ->
                if (idx < eq.numberOfBands) {
                    eq.setBandLevel(idx.toShort(), level.toShort())
                }
            }
        } catch (_: Throwable) {
        }
        try {
            bassBoost?.let { bass ->
                if (bass.strengthSupported) {
                    bass.enabled = state.enabled && state.bassBoostStrength > 0
                    bass.setStrength(state.bassBoostStrength.toShort())
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        equalizer = null
        bassBoost = null
        currentSessionId = 0
    }
}
