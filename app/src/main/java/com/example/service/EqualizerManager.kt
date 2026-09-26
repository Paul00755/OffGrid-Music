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

    fun initAudioSession(audioSessionId: Int, preset: String = currentPreset) {
        if (audioSessionId <= 0 || audioSessionId == androidx.media3.common.C.AUDIO_SESSION_ID_UNSET) return
        currentPreset = preset
        if (currentSessionId == audioSessionId && equalizer != null) {
            if (preset.equals("CUSTOM", ignoreCase = true)) {
                setCustomBands(lastCustomBands, lastBassBoostStrength, lastVirtualizerStrength)
            } else {
                applyPreset(preset)
            }
            return
        }

        release()
        currentSessionId = audioSessionId

        try {
            equalizer = Equalizer(1000, audioSessionId).apply {
                enabled = isEnabled
            }
        } catch (e: Exception) {
            try {
                equalizer = Equalizer(0, audioSessionId).apply {
                    enabled = isEnabled
                }
            } catch (_: Exception) {}
        }

        try {
            bassBoost = BassBoost(1000, audioSessionId).apply {
                enabled = isEnabled && lastBassBoostStrength > 0
                if (strengthSupported && lastBassBoostStrength > 0) {
                    setStrength(lastBassBoostStrength.coerceIn(0, 1000).toShort())
                }
            }
        } catch (e: Exception) {
            try {
                bassBoost = BassBoost(0, audioSessionId).apply {
                    enabled = isEnabled && lastBassBoostStrength > 0
                    if (strengthSupported && lastBassBoostStrength > 0) {
                        setStrength(lastBassBoostStrength.coerceIn(0, 1000).toShort())
                    }
                }
            } catch (_: Exception) {}
        }

        try {
            virtualizer = Virtualizer(1000, audioSessionId).apply {
                enabled = isEnabled && lastVirtualizerStrength > 0
                if (strengthSupported && lastVirtualizerStrength > 0) {
                    setStrength(lastVirtualizerStrength.coerceIn(0, 1000).toShort())
                }
            }
        } catch (e: Exception) {
            try {
                virtualizer = Virtualizer(0, audioSessionId).apply {
                    enabled = isEnabled && lastVirtualizerStrength > 0
                    if (strengthSupported && lastVirtualizerStrength > 0) {
                        setStrength(lastVirtualizerStrength.coerceIn(0, 1000).toShort())
                    }
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
            val minLevel = eq.bandLevelRange?.getOrNull(0)?.toFloat() ?: -1500f
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
        try {
            bassBoost?.apply {
                enabled = isEnabled && strength > 0
                if (strengthSupported) {
                    setStrength(strength.coerceIn(0, 1000).toShort())
                }
            }
        } catch (e: Exception) {
            Log.e("EqualizerManager", "Error setting bass boost", e)
        }
        applyCurrentState()
    }

    fun setVirtualizerStrength(strength: Int) {
        lastVirtualizerStrength = strength
        try {
            virtualizer?.apply {
                enabled = isEnabled && strength > 0
                if (strengthSupported) {
                    setStrength(strength.coerceIn(0, 1000).toShort())
                }
            }
        } catch (e: Exception) {
            Log.e("EqualizerManager", "Error setting virtualizer", e)
        }
        applyCurrentState()
    }

    private fun applyCurrentState() {
        val eq = equalizer ?: return
        try {
            if (!eq.enabled && isEnabled) eq.enabled = true
            val numBands = eq.numberOfBands.toInt()
            val minLevel = eq.bandLevelRange?.getOrNull(0)?.toInt() ?: -1500
            val maxLevel = eq.bandLevelRange?.getOrNull(1)?.toInt() ?: 1500
            val absMin = abs(minLevel)

            val bassOffset = (lastBassBoostStrength / 1000f) * 0.40f
            val virtOffset = (lastVirtualizerStrength / 1000f) * 0.30f

            lastCustomBands.forEachIndexed { idx, basePercent ->
                if (idx < numBands) {
                    var effectivePercent = basePercent
                    // Blend bass boost into 60Hz and 230Hz bands for punchy acoustic feel
                    if (idx == 0) effectivePercent = (effectivePercent + bassOffset).coerceIn(-1.0f, 1.0f)
                    if (idx == 1) effectivePercent = (effectivePercent + (bassOffset * 0.6f)).coerceIn(-1.0f, 1.0f)
                    // Blend virtualizer into 3.6kHz and 14kHz bands for 3D presence
                    if (idx == 3) effectivePercent = (effectivePercent + (virtOffset * 0.5f)).coerceIn(-1.0f, 1.0f)
                    if (idx == 4) effectivePercent = (effectivePercent + virtOffset).coerceIn(-1.0f, 1.0f)

                    val level = if (effectivePercent >= 0f) {
                        (maxLevel * effectivePercent.coerceIn(0f, 1f)).toInt().toShort()
                    } else {
                        (-absMin * abs(effectivePercent.coerceIn(-1f, 0f))).toInt().toShort()
                    }
                    eq.setBandLevel(idx.toShort(), level)
                }
            }
        } catch (e: Exception) {
            Log.e("EqualizerManager", "Error applying current state", e)
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

        try {
            bassBoost?.apply {
                enabled = isEnabled && bassVal > 0
                if (strengthSupported) {
                    setStrength(bassVal.coerceIn(0, 1000).toShort())
                }
            }

            virtualizer?.apply {
                enabled = isEnabled && virtVal > 0
                if (strengthSupported) {
                    setStrength(virtVal.coerceIn(0, 1000).toShort())
                }
            }
        } catch (e: Exception) {
            Log.e("EqualizerManager", "Error applying hardware effects for preset $presetName", e)
        }

        applyCurrentState()
    }

    fun release() {
        try {
            equalizer?.release()
        } catch (_: Exception) {}
        equalizer = null

        try {
            bassBoost?.release()
        } catch (_: Exception) {}
        bassBoost = null

        try {
            virtualizer?.release()
        } catch (_: Exception) {}
        virtualizer = null
        currentSessionId = 0
    }
}
