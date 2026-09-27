package com.example.myapp

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player

/**
 * Pauses [player] the moment a Bluetooth or wired output goes away, so playback never carries on
 * through the phone speaker. ExoPlayer's own "audio becoming noisy" handling is supposed to do this,
 * but MIUI does not send that broadcast for every disconnect (a speaker switched off or out of
 * range). Returns the unregister call, for the service's onDestroy.
 */
fun pauseOnOutputLost(context: Context, player: Player): () -> Unit {
    val audioManager = context.getSystemService(AudioManager::class.java)
    val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { it.isSink && it.type in PERSONAL_OUTPUTS }) player.pause()
        }
    }
    audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    return { audioManager.unregisterAudioDeviceCallback(callback) }
}

private val PERSONAL_OUTPUTS = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET,
)
