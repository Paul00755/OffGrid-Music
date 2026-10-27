package com.example.service

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.util.Log
import kotlin.math.abs

class EqualizerManager(private val context: Context) {

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var currentSessionId: Int = 0
    var currentPreset: String = "NORMAL"
        private set

    private var lastCustomBands: List<Float> = listOf(0f, 0f, 0f, 0f, 0f)
    private var lastBassBoostStrength: Int = 0
    private var lastVirtualizerStrength: Int = 0

    var isEnabled: Boolean = true
        set(value) {
            field = value
            try {
                equalizer?.enabled = value
                bassBoost?.enabled = value && lastBassBoostStrength > 0
                virtualizer?.enabled = value && lastVirtualizerStrength > 0
                if (value) {
                    applyCurrentState()
                }
            } catch (e: Exception) {
                Log.e("EqualizerManager", "Error updating enabled status", e)
            }
        }

    fun getBoundAudioSessionId(): Int = currentSessionId

    @Synchronized
    fun initAudioSession(audioSessionId: Int, preset: String = currentPreset) {
        if (audioSessionId <= 0 || audioSessionId == androidx.media3.common.C.AUDIO_SESSION_ID_UNSET) return
        if (currentSessionId == audioSessionId && equalizer != null) {
            if (!currentPreset.equals(preset, ignoreCase = true)) {
                currentPreset = preset
                if (preset.equals("CUSTOM", ignoreCase = true)) {
                    setCustomBands(lastCustomBands, lastBassBoostStrength, lastVirtualizerStrength)
                } else {
                    applyPreset(preset)
                }
            }
            return
        }
        currentPreset = preset

        release()
        currentSessionId = audioSessionId

        try {
            equalizer = Equalizer(1000, audioSessionId).apply {
                enabled = false
            }
        } catch (e: Exception) {
            try {
                equalizer = Equalizer(0, audioSessionId).apply {
                    enabled = false
                }
            } catch (_: Exception) {}
        }

        try {
            bassBoost = BassBoost(1000, audioSessionId).apply {
                enabled = false
            }
        } catch (e: Exception) {
            try {
                bassBoost = BassBoost(0, audioSessionId).apply {
                    enabled = false
                }
            } catch (_: Exception) {}
        }

        try {
            virtualizer = Virtualizer(1000, audioSessionId).apply {
                enabled = false
            }
        } catch (e: Exception) {
            try {
                virtualizer = Virtualizer(0, audioSessionId).apply {
                    enabled = false
                }
            } catch (_: Exception) {}
        }

        if (preset.equals("CUSTOM", ignoreCase = true)) {
            setCustomBands(lastCustomBands, lastBassBoostStrength, lastVirtualizerStrength)
        } else {
            applyPreset(preset)
        }
    }

    fun getBandFrequencies(): List<Int> {
        val eq = equalizer
        if (eq != null) {
            try {
                val count = eq.numberOfBands.toInt()
                if (count > 0) {
                    return (0 until count).map { (eq.getCenterFreq(it.toShort()) / 1000) }
                }
            } catch (_: Exception) {}
        }
        return listOf(60, 230, 910, 3600, 14000)
    }

    fun getBandLevels(): List<Float> {
        val eq = equalizer ?: return lastCustomBands
        return try {
            val numBands = eq.numberOfBands.toInt().coerceAtLeast(5)
            val maxLevel = eq.bandLevelRange?.getOrNull(1)?.toFloat() ?: 1500f
            val range = if (maxLevel > 0f) maxLevel else 1500f

            (0 until numBands).map { band ->
                val level = eq.getBandLevel(band.toShort()).toFloat()
                (level / range).coerceIn(-1.0f, 1.0f)
            }
        } catch (_: Exception) {
            lastCustomBands
        }
    }

    fun setBandLevel(bandIdx: Int, percent: Float) {
        val updatedBands = lastCustomBands.toMutableList()
        if (bandIdx in updatedBands.indices) {
            updatedBands[bandIdx] = percent
            lastCustomBands = updatedBands
        }
        currentPreset = "CUSTOM"
        applyCurrentState()
    }

    fun setCustomBands(bandLevels: List<Float>, bassBoostStrength: Int = lastBassBoostStrength, virtualizerStrength: Int = lastVirtualizerStrength) {
        currentPreset = "CUSTOM"
        lastCustomBands = bandLevels
        lastBassBoostStrength = bassBoostStrength
        lastVirtualizerStrength = virtualizerStrength
        applyCurrentState()
    }

    fun setBassBoostStrength(strength: Int) {
        lastBassBoostStrength = strength
        applyCurrentState()
    }

    fun setVirtualizerStrength(strength: Int) {
        lastVirtualizerStrength = strength
        applyCurrentState()
    }

    private fun applyCurrentState() {
        val eq = equalizer ?: return
        try {
            val isNormalOrFlat = !isEnabled ||
                currentPreset.equals("NORMAL", ignoreCase = true) ||
                currentPreset.equals("FLAT", ignoreCase = true) ||
                (lastCustomBands.all { abs(it) < 0.01f } && lastBassBoostStrength == 0 && lastVirtualizerStrength == 0)

            // When in Normal/Flat mode, disable hardware AudioFX completely so the Android audio HAL
            // does not engage its headroom limiter (-3 to -6 dB attenuation) or biquad filter coloration.
            if (isNormalOrFlat) {
                try {
                    val numBands = eq.numberOfBands.toInt()
                    for (i in 0 until numBands) {
                        eq.setBandLevel(i.toShort(), 0)
                    }
                    if (eq.enabled) eq.enabled = false
                } catch (_: Exception) {}
                try {
                    bassBoost?.apply {
                        if (strengthSupported) setStrength(0)
                        if (enabled) enabled = false
                    }
                } catch (_: Exception) {}
                try {
                    virtualizer?.apply {
                        if (strengthSupported) setStrength(0)
                        if (enabled) enabled = false
                    }
                } catch (_: Exception) {}
                return
            }

            if (!eq.enabled) eq.enabled = true
            val numBands = eq.numberOfBands.toInt()
            val minLevel = eq.bandLevelRange?.getOrNull(0)?.toInt() ?: -1500
            val rawMaxLevel = eq.bandLevelRange?.getOrNull(1)?.toInt() ?: 1500
            // Cap positive band boosts to +9.0 dB (900 mB) to prevent digital clipping in the mixer
            val safeMaxLevel = minOf(rawMaxLevel, 900)
            val absMin = abs(minLevel)

            lastCustomBands.forEachIndexed { idx, basePercent ->
                if (idx < numBands) {
                    val effectivePercent = basePercent.coerceIn(-1.0f, 1.0f)
                    val level = if (effectivePercent >= 0f) {
                        (safeMaxLevel * effectivePercent).toInt().toShort()
                    } else {
                        (-absMin * abs(effectivePercent)).toInt().toShort()
                    }
                    eq.setBandLevel(idx.toShort(), level)
                }
            }
        } catch (e: Exception) {
            Log.e("EqualizerManager", "Error applying current state", e)
        }

        try {
            bassBoost?.apply {
                val enableBass = isEnabled && lastBassBoostStrength > 0
                if (enabled != enableBass) enabled = enableBass
                if (enableBass && strengthSupported) {
                    setStrength(lastBassBoostStrength.coerceIn(0, 1000).toShort())
                }
            }
        } catch (e: Exception) {
            Log.e("EqualizerManager", "Error setting bass boost", e)
        }

        try {
            virtualizer?.apply {
                val enableVirt = isEnabled && lastVirtualizerStrength > 0
                if (enabled != enableVirt) enabled = enableVirt
                if (enableVirt && strengthSupported) {
                    setStrength(lastVirtualizerStrength.coerceIn(0, 1000).toShort())
                }
            }
        } catch (e: Exception) {
            Log.e("EqualizerManager", "Error setting virtualizer", e)
        }
    }

    fun applyPreset(presetName: String) {
        currentPreset = presetName
        if (presetName.equals("CUSTOM", ignoreCase = true)) {
            applyCurrentState()
            return
        }

        val preset = com.example.ui.components.EQ_PRESETS.find { it.id.equals(presetName, ignoreCase = true) }
        val bands = preset?.bands ?: listOf(0f, 0f, 0f, 0f, 0f)
        val bassVal = preset?.bassBoost ?: 0
        val virtVal = preset?.virtualizer ?: 0

        lastCustomBands = bands
        lastBassBoostStrength = bassVal
        lastVirtualizerStrength = virtVal

        applyCurrentState()
    }

    @Synchronized
    fun release() {
        try {
            equalizer?.enabled = false
            equalizer?.release()
        } catch (_: Exception) {}
        equalizer = null

        try {
            bassBoost?.enabled = false
            bassBoost?.release()
        } catch (_: Exception) {}
        bassBoost = null

        try {
            virtualizer?.enabled = false
            virtualizer?.release()
        } catch (_: Exception) {}
        virtualizer = null
        currentSessionId = 0
    }
}
