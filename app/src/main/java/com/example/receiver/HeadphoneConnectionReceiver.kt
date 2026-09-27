package com.example.receiver

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.data.local.UserPreferencesRepository
import com.example.service.MusicPlaybackService
import com.example.service.PlaybackManager

class HeadphoneConnectionReceiver : BroadcastReceiver() {

    companion object {
        @Volatile
        private var lastTriggerTimeMs = 0L
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return

        var isConnectedEvent = false

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                isConnectedEvent = true
            }
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    isConnectedEvent = true
                }
            }
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    isConnectedEvent = true
                }
            }
        }

        if (!isConnectedEvent) return

        val now = System.currentTimeMillis()
        if (now - lastTriggerTimeMs < 1200L) {
            return
        }
        lastTriggerTimeMs = now

        // 1. Verify user's preference setting
        val prefs = UserPreferencesRepository.getInstance(context)
        val isAutoPlayEnabled = prefs.cachedAutoPlayOnHeadphoneConnection
        if (!isAutoPlayEnabled) return

        // 2. If playback is already active in RAM, do nothing
        val activePlayer = PlaybackManager.activeInstance?.player
        if (activePlayer != null && (activePlayer.isPlaying || activePlayer.playWhenReady)) {
            return
        }

        // 3. Start or signal MusicPlaybackService with ACTION_HEADPHONE_CONNECTED
        try {
            val serviceIntent = Intent(context, MusicPlaybackService::class.java).apply {
                this.action = MusicPlaybackService.ACTION_HEADPHONE_CONNECTED
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {}
    }
}
