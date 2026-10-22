package org.rustlanglatam.zeroclaw.android

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Compose state for the platform TextToSpeech voices offered in settings and the one selected by the owner. Only
 * voices classified as female are offered, matching the assistant's persona. The engine does not expose a voice's
 * gender, so each voice is synthesized once ([VoiceProbe]) and its measured pitch is stored.
 */
object Voices {
    /** Name of the selected voice; `null` means the best ranked one. */
    var chosen by mutableStateOf<String?>(null)

    /** Voices offered in settings, best first; filled once the TTS engine is ready. */
    var available by mutableStateOf(emptyList<Voice>())

    /** Name of the voice currently in use. */
    var current by mutableStateOf<String?>(null)

    /** Each voice's measured median fundamental frequency in Hz; persisted across starts. */
    var heard by mutableStateOf(emptyMap<String, Float>())

    /** Pitch threshold for classifying a voice as female: typical female speech is ~165-255 Hz, male ~85-155 Hz. */
    const val WOMAN_HZ = 165f

    fun woman(v: Voice) = (heard[v.name] ?: 0f) >= WOMAN_HZ

    private fun prefs(context: Context) = context.getSharedPreferences("voices", Context.MODE_PRIVATE)

    fun load(context: Context) {
        heard = prefs(context).all.mapNotNull { (k, v) -> (v as? Float)?.let { k to it } }.toMap()
    }

    fun keep(context: Context, name: String, hz: Float) {
        android.util.Log.i(AssistantService.TAG, "voice heard: $name ${hz.toInt()} Hz")
        prefs(context).edit().putFloat(name, hz).apply()
        heard = heard + (name to hz)
    }

    /**
     * Returns the default voice and the list to offer. The default is the best ranked ([rank]) installed offline
     * female voice; the list holds the female voices of at least its quality, best first. Until voices have been
     * probed, the pool falls back to all voices, so a default always exists.
     */
    fun offer(voices: List<Voice>, country: String): Pair<Voice?, List<Voice>> {
        val ranked = voices.sortedWith(compareByDescending<Voice> { rank(it, country) }.thenBy { it.name })
        val women = ranked.filter(::woman)
        val pool = women.ifEmpty { ranked }
        val base = pool.firstOrNull { "notInstalled" !in it.features && !it.isNetworkConnectionRequired }
        return base to women.filter { base == null || it.quality >= base.quality }
    }

    /** Returns a display name for [v] and a description: quality, whether it needs internet or a download, and its region. */
    fun label(v: Voice): Pair<String, String> {
        val region = v.locale.getDisplayCountry(Language.locale).ifEmpty { v.locale.getDisplayLanguage(Language.locale) }
        val how = listOf(
            if (v.quality >= Voice.QUALITY_HIGH) t(R.string.voice_natural) else t(R.string.voice_plain),
            when {
                "notInstalled" in v.features -> t(R.string.voice_downloads_when_chosen)
                v.isNetworkConnectionRequired -> t(R.string.voice_needs_internet)
                else -> t(R.string.voice_on_the_phone)
            },
        )
        return name(v) to (how + region).joinToString(" · ")
    }

    /** Sorted engine ids of the female voices in the current language; a voice's index gives it a stable display name. */
    @Volatile var all: List<String> = emptyList()

    /** Returns a human-readable display name for [v] instead of the engine's id (e.g. `es-us-x-sfb-local`). */
    fun name(v: Voice): String {
        // Voices are offered in the assistant's language only, so the names are resolved in it.
        val names = ta(R.array.voice_names)
        val i = all.indexOf(v.name).takeIf { it >= 0 } ?: v.name.hashCode().mod(names.size)
        return names[i % names.size] + (if (i >= names.size) " ${i / names.size + 1}" else "")
    }
}

/**
 * Configures this engine with the selected voice, or else the best ranked voice for the current language and the
 * device's country (the engine's default often is not). Pitch stays at 1.0, since a raised pitch sounded synthetic;
 * higher [Warmth] levels slightly lower the speech rate.
 */
fun TextToSpeech.asKsandra() {
    language = Language.locale
    // Voices named `*-language` (e.g. `es-US-language`) are engine aliases of another voice; skipped to avoid duplicates.
    val all = voices.orEmpty().filter { it.locale.language == Language.current.tag && !it.name.endsWith("-language") }
    Voices.all = all.filter(Voices::woman).map { it.name }.sorted()
    val (base, offered) = Voices.offer(all, java.util.Locale.getDefault().country)
    Voices.available = offered
    (offered.firstOrNull { it.name == Voices.chosen } ?: base)?.let { voice = it; Voices.current = it.name }
    setPitch(1.0f)
    setSpeechRate(when (Guard.warmth) { Warmth.QUIET -> 1.0f; Warmth.NATURAL -> 0.97f; Warmth.CLOSE -> 0.95f })
}

/**
 * Scores [v]: quality first, then locale proximity (the device's [country]; otherwise `es-US` for Spanish outside
 * Spain, as engines use it for Latin American Spanish, and `en-US` for English outside the UK and Commonwealth
 * locales), then offline availability.
 */
fun rank(v: Voice, country: String): Int {
    val near = v.locale.country == country ||
        (v.locale.country == "US" && v.locale.language == "es" && country != "ES") ||
        (v.locale.country == "US" && v.locale.language == "en" && country !in setOf("GB", "IE", "AU", "NZ", "IN", "ZA"))
    return v.quality * 4 + (if (v.locale.country == country) 3 else if (near) 2 else 0) + (if (v.isNetworkConnectionRequired) 0 else 1)
}

/**
 * Synthesizes a sample with each unprobed voice of the current language to a file (never played) and stores its
 * measured pitch, used to classify the voice. Uses its own TextToSpeech instance so the active engine is not
 * affected. A network voice that fails offline is probed again on a later start.
 */
class VoiceProbe(private val context: Context, private val done: () -> Unit) {
    private var tts: TextToSpeech? = null
    private val file = File(context.cacheDir, "voice-probe.wav")

    fun start() {
        tts = TextToSpeech(context) { status -> if (status == TextToSpeech.SUCCESS) next() else stop() }
    }

    private fun next() {
        val engine = tts ?: return
        val todo = engine.voices.orEmpty().filter { it.locale.language == Language.current.tag && it.name !in Voices.heard && "notInstalled" !in it.features }
        val v = todo.firstOrNull() ?: return stop()
        engine.voice = v
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                runCatching { pitch(file.readBytes()) }.getOrNull()?.let { Voices.keep(context, v.name, it) }
                    ?: Voices.keep(context, v.name, 0f)
                next()
            }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) {
                // A network voice cannot be probed offline: it is skipped for this run only and retried on the next start.
                if (!v.isNetworkConnectionRequired) Voices.keep(context, v.name, 0f)
                Voices.heard = Voices.heard + (v.name to 0f)
                next()
            }
        })
        engine.synthesizeToFile(t(R.string.voice_hi_i_m_ksandra_how_can), Bundle(), file, v.name)
    }

    private fun stop() {
        tts?.shutdown(); tts = null
        file.delete()
        done()
    }

    companion object {
        /** Returns the median pitch (Hz) of a 16-bit PCM WAV, or `null` when it has no voiced frames. */
        fun pitch(wav: ByteArray): Float? {
            val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
            val rate = b.getInt(24)
            var p = 12
            while (p + 8 <= wav.size && String(wav, p, 4) != "data") p += 8 + b.getInt(p + 4)
            if (p + 8 > wav.size) return null
            val n = (wav.size - p - 8) / 2
            val s = FloatArray(n) { b.getShort(p + 8 + it * 2) / 32768f }
            return pitch(s, rate)
        }

        /** Returns the median fundamental frequency (Hz) of [s] at [rate], by autocorrelation over its voiced frames. */
        fun pitch(s: FloatArray, rate: Int): Float? {
            val frame = rate / 25
            val hop = rate / 50
            val minLag = rate / 400
            val maxLag = rate / 70
            val found = mutableListOf<Float>()
            var start = 0
            while (start + frame + maxLag < s.size) {
                var energy = 0f
                for (i in start until start + frame) energy += s[i] * s[i]
                if (energy / frame > 1e-4f) {
                    val r = FloatArray(maxLag + 1)
                    for (l in minLag..maxLag) {
                        var c = 0f
                        for (i in start until start + frame) c += s[i] * s[i + l]
                        r[l] = c / energy
                    }
                    val best = r.max()
                    // Takes the first peak within 90% of the maximum, not the maximum: a double period correlates as well (octave error).
                    val lag = (minLag + 1 until maxLag).firstOrNull { r[it] >= 0.9f * best && r[it] >= r[it - 1] && r[it] >= r[it + 1] }
                    if (best > 0.6f && lag != null) found += rate.toFloat() / lag
                }
                start += hop
            }
            return found.sorted().getOrNull(found.size / 2)
        }
    }
}
