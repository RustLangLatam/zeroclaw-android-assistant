package org.rustlanglatam.zeroclaw.android

/**
 * Parses requests for the phone's system controls. Android lets an app change some settings directly
 * (brightness, volume, ringer, flashlight, do not disturb, rotation) and reserves others for the user
 * (airplane mode, Bluetooth, Wi-Fi, mobile data, location, NFC); for those the app opens the system
 * settings panel instead ([Setting.direct] is `false`). Pure Kotlin, unit-tested on the JVM; [PhoneDevice] applies them.
 */
object Device {

    enum class Setting(@androidx.annotation.StringRes private val labelId: Int, val direct: Boolean) {
        BRIGHTNESS(R.string.device_brightness, true),
        VOLUME(R.string.device_volume, true),
        RINGER(R.string.device_ringer, true),
        FLASHLIGHT(R.string.device_flashlight, true),
        DO_NOT_DISTURB(R.string.device_do_not_disturb, true),
        ROTATION(R.string.device_rotation, true),
        AIRPLANE(R.string.device_airplane, false),
        BLUETOOTH(R.string.device_bluetooth, false),
        WIFI(R.string.device_wifi, false),
        MOBILE_DATA(R.string.device_mobile_data, false),
        LOCATION(R.string.device_location, false),
        NFC(R.string.device_nfc, false),
        ;

        /** The setting's localized display name. */
        val label get() = t(labelId)

        companion object {
            /** Returns the setting matching [name] by prefix, in English or Spanish, ignoring case, accents, spaces and underscores; `null` if none. */
            fun named(name: String): Setting? {
                val n = Commands.normalize(name).replace(" ", "").replace("_", "")
                return when {
                    n.isEmpty() -> null
                    Lexicon.words("device.setting.brightness").any(n::startsWith) -> BRIGHTNESS
                    Lexicon.words("device.setting.ringer").any(n::startsWith) -> RINGER
                    Lexicon.words("device.setting.volume").any(n::startsWith) -> VOLUME
                    Lexicon.words("device.setting.flashlight").any(n::startsWith) -> FLASHLIGHT
                    Lexicon.words("device.setting.do_not_disturb").any(n::startsWith) -> DO_NOT_DISTURB
                    Lexicon.words("device.setting.rotation").any(n::startsWith) -> ROTATION
                    Lexicon.words("device.setting.airplane").any(n::startsWith) -> AIRPLANE
                    Lexicon.words("device.setting.bluetooth").any(n::startsWith) -> BLUETOOTH
                    Lexicon.words("device.setting.wifi").any(n::startsWith) -> WIFI
                    Lexicon.words("device.setting.mobile_data").any(n::startsWith) -> MOBILE_DATA
                    Lexicon.words("device.setting.location").any(n::startsWith) -> LOCATION
                    Lexicon.words("device.setting.nfc").any(n::startsWith) -> NFC
                    else -> null
                }
            }
        }
    }

    /**
     * Returns the level on a 0..[max] scale requested by [value]: a number is read as a percentage,
     * up/down words (English or Spanish) move a quarter of the scale from [current], and max/min words
     * give the bounds; `null` when [value] is not a level.
     */
    fun level(value: String, current: Int, max: Int): Int? {
        val v = Commands.normalize(value)
        val step = maxOf(1, max / 4)
        val number = Regex("\\d+").find(v)?.value?.toIntOrNull()
        return when {
            number != null -> (number.coerceIn(0, 100) * max + 50) / 100
            Lexicon.words("device.level.max").any(v::startsWith) -> max
            Lexicon.words("device.level.min").any(v::startsWith) -> 0
            Lexicon.words("device.level.up").any(v::startsWith) -> (current + step).coerceAtMost(max)
            Lexicon.words("device.level.down").any(v::startsWith) -> (current - step).coerceAtLeast(0)
            else -> null
        }
    }

    /** Returns `true` or `false` for on/off words in English or Spanish (matched by prefix), `null` when neither. */
    fun onOff(value: String): Boolean? {
        val v = Commands.normalize(value)
        return when {
            Regex(Lexicon["device.on"]).containsMatchIn(v) -> true
            Regex(Lexicon["device.off"]).containsMatchIn(v) -> false
            else -> null
        }
    }

    fun percent(level: Int, max: Int) = if (max <= 0) 0 else (level * 100 + max / 2) / max
}
