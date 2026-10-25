package org.rustlanglatam.zeroclaw.android

import android.content.Context

/**
 * The service settings and keys the owner enters in Connectors (model provider, model and key; web search; video
 * search; natural voice), kept in the app's encrypted preferences ([AssistantService.prefs], Keystore master key)
 * instead of being built into the APK (ADR-0030). A value not entered falls back to the build's: debug builds compile
 * `local.properties` into [BuildConfig]; release builds compile no keys (the `*_KEY` fields are empty).
 */
object Keys {
    /** Each setting, with the build's value as its fallback; [secret] values are never shown back. */
    enum class Key(val default: () -> String, val secret: Boolean = false) {
        MODEL_PROVIDER({ BuildConfig.MODEL_PROVIDER }),
        MODEL({ BuildConfig.MODEL }),
        MODEL_KEY({ BuildConfig.MODEL_API_KEY }, secret = true),
        /** The provider's full endpoint URL; needed by a custom gateway, optional otherwise (ZeroClaw's `uri`). */
        MODEL_URL({ "" }),
        /** The model's price, US$ per million input and output tokens; the build's applies only to the build's model. */
        MODEL_INPUT_PER_MTOK({ BuildConfig.MODEL_INPUT_PER_MTOK.takeIf { Keys[MODEL] == BuildConfig.MODEL }.orEmpty() }),
        MODEL_OUTPUT_PER_MTOK({ BuildConfig.MODEL_OUTPUT_PER_MTOK.takeIf { Keys[MODEL] == BuildConfig.MODEL }.orEmpty() }),
        /** A model that can see pictures, used only for messages with a photo when the main model cannot see them. */
        VISION_PROVIDER({ BuildConfig.VISION_PROVIDER }),
        VISION_MODEL({ BuildConfig.VISION_MODEL }),
        VISION_KEY({ BuildConfig.VISION_API_KEY }, secret = true),
        SEARCH_PROVIDER({ BuildConfig.SEARCH_PROVIDER }),
        SEARCH_KEY({ BuildConfig.SEARCH_KEY }, secret = true),
        YOUTUBE_KEY({ BuildConfig.YOUTUBE_KEY }, secret = true),
        VOICE_URL({ BuildConfig.VOICE_URL }),
        VOICE_MODEL({ BuildConfig.VOICE_MODEL }),
        VOICE_KEY({ BuildConfig.VOICE_API_KEY }, secret = true),
        VOICE_NAME({ BuildConfig.VOICE_NAME }),
        VOICE_PER_MINUTE({ BuildConfig.VOICE_PER_MINUTE }),
    }

    @Volatile private var app: Context? = null

    fun init(context: Context) { app = context.applicationContext }

    /** Returns the entered value, else the build's; on the JVM (no context) always the build's. */
    operator fun get(key: Key): String =
        app?.let { AssistantService.prefs(it).getString(PREFIX + key.name, null) }?.takeIf { it.isNotBlank() } ?: key.default()

    /** Whether the owner entered [key] (as opposed to the build's fallback). */
    fun entered(key: Key): Boolean = app?.let { AssistantService.prefs(it).contains(PREFIX + key.name) } == true

    /** Whether the cloud model can be reached: provider, model and key are all set, and a custom gateway's URL. */
    val modelReady get() = this[Key.MODEL_PROVIDER].isNotBlank() && this[Key.MODEL].isNotBlank() && this[Key.MODEL_KEY].isNotBlank() &&
        (this[Key.MODEL_PROVIDER] != CUSTOM || this[Key.MODEL_URL].isNotBlank())

    /** ZeroClaw's provider family for any compatible gateway, which needs [Key.MODEL_URL]. */
    const val CUSTOM = "custom"

    /** Whether a model that can see pictures is set. */
    val visionReady get() = listOf(Key.VISION_PROVIDER, Key.VISION_MODEL, Key.VISION_KEY).none { this[it].isBlank() }

    /** Number of saves so far; the daemon's config is rewritten when it differs from the one it was written with. */
    val version get() = app?.let { AssistantService.prefs(it).getInt(VERSION, 0) } ?: 0

    /**
     * Whether [value] may be stored for [key]. Values are written into ZeroClaw's TOML config, so quotes, backslashes and
     * control characters are refused (they could add or change config entries); provider names also name a TOML table.
     */
    fun valid(key: Key, value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return true
        if (v.length > 512 || v.any { it < ' ' || it == '"' || it == '\\' || it.code > 126 }) return false
        return when (key) {
            Key.MODEL_PROVIDER, Key.VISION_PROVIDER, Key.SEARCH_PROVIDER -> v.matches(Regex("[A-Za-z0-9_-]+"))
            Key.VOICE_URL -> v.startsWith("wss://")
            // Keys travel to this URL: encrypted, except to a gateway on this phone.
            Key.MODEL_URL -> v.startsWith("https://") || Regex("http://(127\\.0\\.0\\.1|localhost)(:\\d+)?(/.*)?").matches(v)
            Key.VOICE_PER_MINUTE, Key.MODEL_INPUT_PER_MTOK, Key.MODEL_OUTPUT_PER_MTOK -> v.toDoubleOrNull()?.let { it >= 0 } == true
            else -> true
        }
    }

    /** Stores [values] when all are [valid]; a blank value removes the entry, so the build's fallback applies again. */
    fun save(values: Map<Key, String>): Boolean {
        if (!values.all { (k, v) -> valid(k, v) }) return false
        val prefs = AssistantService.prefs(app ?: return false)
        prefs.edit().apply {
            values.forEach { (k, v) -> if (v.isBlank()) remove(PREFIX + k.name) else putString(PREFIX + k.name, v.trim()) }
            putInt(VERSION, prefs.getInt(VERSION, 0) + 1)
        }.apply()
        return true
    }

    private const val PREFIX = "key."
    private const val VERSION = "keys.version"
}
