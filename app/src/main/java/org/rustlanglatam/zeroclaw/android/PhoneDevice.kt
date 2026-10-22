package org.rustlanglatam.zeroclaw.android

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.Settings

/**
 * Android implementation of [Device]: changes the settings an app may write directly and opens the
 * system settings panel for those Android reserves to the user.
 */
class PhoneDevice(context: Context) {
    private val context = context.applicationContext

    /**
     * Sets [setting] to [value] (a level or on/off).
     *
     * @return a plain-text result for the agent to relay.
     */
    fun set(setting: Device.Setting, value: String): String = when (setting) {
        Device.Setting.BRIGHTNESS -> brightness(value)
        Device.Setting.VOLUME -> volume(AudioManager.STREAM_MUSIC, "Media volume", value)
        Device.Setting.RINGER -> volume(AudioManager.STREAM_RING, "Ring volume", value)
        Device.Setting.FLASHLIGHT -> flashlight(value)
        Device.Setting.DO_NOT_DISTURB -> doNotDisturb(value)
        Device.Setting.ROTATION -> rotation(value)
        else -> panel(setting)
    }

    /** Returns a plain-text summary of brightness, volumes, do not disturb, airplane mode, Bluetooth and Wi-Fi. */
    fun status(): String {
        val audio = context.getSystemService(AudioManager::class.java)
        val brightness = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(-1)
        val auto = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC }.getOrDefault(false)
        fun global(name: String) = runCatching { Settings.Global.getInt(context.contentResolver, name) == 1 }.getOrDefault(false)
        val media = audio.getStreamVolume(AudioManager.STREAM_MUSIC) to audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val ring = audio.getStreamVolume(AudioManager.STREAM_RING) to audio.getStreamMaxVolume(AudioManager.STREAM_RING)
        val dnd = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
        return buildString {
            if (brightness >= 0) appendLine("Brightness: ${Device.percent(brightness, 255)}%" + if (auto) " (automatic)" else "")
            appendLine("Media volume: ${Device.percent(media.first, media.second)}%; ring volume: ${Device.percent(ring.first, ring.second)}%")
            appendLine("Do not disturb: ${if (dnd) "on" else "off"}")
            appendLine("Airplane mode: ${if (global(Settings.Global.AIRPLANE_MODE_ON)) "on" else "off"}")
            appendLine("Bluetooth: ${if (global(Settings.Global.BLUETOOTH_ON)) "on" else "off"}")
            appendLine("Wi-Fi: ${if (context.getSystemService(WifiManager::class.java).isWifiEnabled) "on" else "off"}")
        }.trim()
    }

    private fun brightness(value: String): String {
        if (!Settings.System.canWrite(context)) {
            open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
            return "Not yet allowed: the owner must let Ksandra change system settings once; the screen to allow it is open. Ask them to try again after."
        }
        val resolver = context.contentResolver
        val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val level = Device.level(value, current, 255) ?: return "Say a level: a percentage, up, down, max or min."
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, level.coerceAtLeast(1))
        return "Brightness set to ${Device.percent(level, 255)}% (automatic brightness off)."
    }

    private fun volume(stream: Int, name: String, value: String): String {
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(stream)
        Device.onOff(value)?.let { on ->
            audio.adjustStreamVolume(stream, if (on) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            return "$name ${if (on) "unmuted" else "muted"}."
        }
        val level = Device.level(value, audio.getStreamVolume(stream), max) ?: return "Say a level: a percentage, up, down, max, min, or mute."
        return runCatching { audio.setStreamVolume(stream, level, AudioManager.FLAG_SHOW_UI); "$name set to ${Device.percent(level, max)}%." }
            .getOrElse { "Could not change it: ${it.message}" }
    }

    private fun flashlight(value: String): String {
        val on = Device.onOff(value) ?: return "Say on or off."
        val camera = context.getSystemService(CameraManager::class.java)
        val id = camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return "This phone has no flashlight."
        return runCatching { camera.setTorchMode(id, on); "Flashlight ${if (on) "on" else "off"}." }.getOrElse { "Could not: ${it.message}" }
    }

    private fun doNotDisturb(value: String): String {
        val on = Device.onOff(value) ?: return "Say on or off."
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.isNotificationPolicyAccessGranted) {
            open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return "Not yet allowed: the owner must give Ksandra do-not-disturb access once; the screen to allow it is open."
        }
        manager.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return "Do not disturb ${if (on) "on (only priority interruptions)" else "off"}."
    }

    private fun rotation(value: String): String {
        val on = Device.onOff(value) ?: return "Say on or off."
        if (!Settings.System.canWrite(context)) {
            open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
            return "Not yet allowed: the owner must let Ksandra change system settings once; the screen to allow it is open."
        }
        Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
        return "Auto-rotate ${if (on) "on" else "off"}."
    }

    /** Opens the system panel or settings screen for a setting that apps cannot toggle themselves. */
    private fun panel(setting: Device.Setting): String {
        val action = when (setting) {
            Device.Setting.WIFI -> Settings.Panel.ACTION_WIFI
            Device.Setting.MOBILE_DATA -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            Device.Setting.NFC -> Settings.Panel.ACTION_NFC
            Device.Setting.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            Device.Setting.AIRPLANE -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            else -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
        }
        open(Intent(action))
        return "Android does not let apps switch ${setting.name.lowercase().replace('_', ' ')} themselves: its panel is open on the screen, " +
            "so the owner switches it with one touch. Say so briefly."
    }

    private fun open(intent: Intent) = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
