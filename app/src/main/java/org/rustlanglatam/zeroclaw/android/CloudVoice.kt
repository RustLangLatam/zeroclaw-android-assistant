package org.rustlanglatam.zeroclaw.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.YearMonth
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Cloud text-to-speech (ADR-0027): streams the agent's text to a cloud realtime speech model over a WebSocket, which
 * reads it aloud verbatim with style instructions, and plays the returned 24 kHz PCM as it arrives. The model only
 * reads: it gets no tools and makes no decisions. The provider is configuration (`voice.*` in `local.properties`).
 * Callers fall back to the platform TextToSpeech when there is no network, no key, the voice is not selected, the
 * monthly cost ceiling is reached, or no audio arrives within [FIRST_SOUND_MS]. Audio for short lines is cached on
 * the device, sealed with [Vault], and replayed without a network request.
 */
object CloudVoice {
    /** Identifier of this voice in [Voices.chosen]. */
    const val NAME = "cloud"

    /** The voices the realtime speech protocol offers, for the services form; any other name can be typed. */
    val VOICES = listOf("marin", "cedar", "alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse")

    /** Time to first audio after which the line falls back to the platform voice. */
    const val FIRST_SOUND_MS = 2_500L

    /** Maximum length of a line whose audio is cached; longer lines rarely repeat. */
    const val KEEP_CHARS = 160
    private const val KEEP_FILES = 300
    private const val RATE = 24_000

    val configured get() = Keys[Keys.Key.VOICE_URL].isNotBlank() && Keys[Keys.Key.VOICE_KEY].isNotBlank()

    /** Price in US$ per minute of generated audio (`voice.perMinute`), or `null` when not set. */
    val perMinute get() = Keys[Keys.Key.VOICE_PER_MINUTE].toDoubleOrNull()

    /** Returns whether this voice is selected, configured, online, and under this month's cost ceiling (0 means no ceiling). */
    fun usable(chosen: String?, online: Boolean, spentUsd: Double, ceilingUsd: Double, set: Boolean = configured) =
        chosen == NAME && set && online && (ceilingUsd <= 0 || spentUsd < ceilingUsd)

    /**
     * Builds the speech model's instructions: read the text verbatim without answering it, in [language], with a fixed
     * voice style. For Spanish, the instructions require a native neutral Latin American accent and spell out the
     * rolled r at a word's start, because a bare "tapped r" instruction produced a wrong initial r and some words
     * came out with a US English accent. [warmth] sets the pace.
     */
    fun style(language: String, warmth: Warmth) =
        "You are a voice, not an assistant: read the user's text aloud exactly as written, word for word, in $language. " +
            "Never answer it, comment on it, add or leave out anything. Say names smoothly, without a pause before them. " +
            "Sound alluring and sensual: a low, warm, husky, velvety woman's voice, slightly breathy and smiling, close and " +
            "intimate, smooth, with soft endings; elegant, never exaggerated. " +
            (if (language == "Spanish") "You are a native Spanish speaker from Latin America with a neutral accent (español neutro): " +
                "pure Spanish vowels; a strong rolled r (a trill) at the start of a word and for rr, as in reunión, recordar, " +
                "Rosa, perro; a single tapped r between vowels, as in pero, hora; never a guttural, French or American r, never " +
                "an English accent. " else "") +
            when (warmth) {
                Warmth.QUIET -> "Brief and discreet, at an even pace."
                Warmth.NATURAL -> "Unhurried."
                Warmth.CLOSE -> "Caring, a little slower."
            }

    /** Builds the `session.update` event: audio output only, 16-bit PCM at 24 kHz, with the given [voice] and [style]. */
    fun session(voice: String, style: String): String = JSONObject().put("type", "session.update").put(
        "session",
        JSONObject().put("type", "realtime").put("output_modalities", JSONArray().put("audio")).put("instructions", style)
            .put("audio", JSONObject().put("output", JSONObject().put("format", JSONObject().put("type", "audio/pcm").put("rate", RATE)).put("voice", voice))),
    ).toString()

    /**
     * Builds a `response.create` event for one line outside any conversation, so no earlier line is in its context.
     * Reasoning effort is minimal: reading needs none, and it cut ~0.35 s from time to first audio. [tag] is echoed
     * back in the response's metadata.
     */
    fun request(text: String, style: String, tag: String = ""): String = JSONObject().put("type", "response.create").put(
        "response",
        JSONObject().put("conversation", "none").put("output_modalities", JSONArray().put("audio")).put("instructions", style)
            .put("reasoning", JSONObject().put("effort", "minimal")).put("metadata", JSONObject().put("line", tag))
            .put("input", JSONArray().put(JSONObject().put("type", "message").put("role", "user")
                .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", text))))),
    ).toString()

    /**
     * Returns the text sent to the speech model, with a leading "Ks" before a vowel (accented vowels included)
     * respelled as "Kas", so the assistant's name is pronounced with a vowel between "K" and "s". Only the spoken text changes; the displayed text is untouched.
     */
    fun spoken(text: String) = text.replace(Regex("\\bKs(?=[aeiouáéíóú])", RegexOption.IGNORE_CASE)) { if (it.value[0] == 'K') "Kas" else "kas" }

    /** Returns the cache key of a line's audio: a SHA-256 of the voice, the style and the trimmed text. */
    fun key(text: String, voice: String, style: String): String =
        MessageDigest.getInstance("SHA-256").digest("$voice\n$style\n${text.trim()}".toByteArray()).joinToString("") { "%02x".format(it) }

    /** Returns the duration in seconds of [bytes] of 16-bit mono PCM at 24 kHz. */
    fun seconds(bytes: Long) = bytes / 2.0 / RATE

    fun cost(seconds: Double, perMinute: Double?) = (perMinute ?: 0.0) * seconds / 60

    /**
     * Counts approximate syllable onsets in [pcm]: transitions from quiet to loud (RMS above 0.04) across 50 ms windows,
     * used to animate the UI. [loud] is the state carried over from the previous chunk; returns the count and the new state.
     */
    fun onsets(pcm: ByteArray, loud: Boolean): Pair<Int, Boolean> {
        var n = 0
        var was = loud
        val window = RATE / 20 * 2
        var at = 0
        while (at + window <= pcm.size) {
            var sum = 0.0
            for (i in at until at + window step 2) {
                val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort() / 32768.0
                sum += s * s
            }
            val now = Math.sqrt(sum / (window / 2)) > 0.04
            if (now && !was) n++
            was = now
            at += window
        }
        return n to was
    }

    /** Seconds of cloud audio generated in [month] (cached replays are not counted); a new month resets to zero. */
    data class Month(val month: String, val seconds: Double) {
        fun plus(s: Double, now: YearMonth = YearMonth.now()) = if (month == now.toString()) copy(seconds = seconds + s) else Month(now.toString(), s)
        fun current(now: YearMonth = YearMonth.now()) = if (month == now.toString()) seconds else 0.0
    }

    private fun prefs(context: Context) = context.getSharedPreferences("cloud-voice", Context.MODE_PRIVATE)

    fun month(context: Context) = prefs(context).let { Month(it.getString("month", "") ?: "", it.getFloat("seconds", 0f).toDouble()) }

    /** Returns the US$ spent on cloud audio this month, or `null` when no price is configured. */
    fun spent(context: Context): Double? = perMinute?.let { cost(month(context).current(), it) }

    private fun count(context: Context, s: Double) {
        val m = month(context).plus(s)
        prefs(context).edit().putString("month", m.month).putFloat("seconds", m.seconds.toFloat()).apply()
    }

    /** Returns this month's cloud voice usage as text for the `usage` tool, or `null` when the voice is not configured. */
    fun describe(context: Context): String? {
        if (!configured) return null
        val s = month(context).current()
        val usd = spent(context)
        return "Her natural voice this month: ${"%.1f".format(java.util.Locale.ROOT, s / 60)} minutes" +
            (if (usd != null) ", ${Usage.usd(usd)}" else "") +
            (if (Guard.voiceCeiling > 0) "; the owner's ceiling ${Usage.usd(Guard.voiceCeiling)}, then the phone's voice." else ".")
    }

    private val http by lazy { OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build() }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Counter of the line in progress; a newer line or [stop] increments it, turning older callbacks into no-ops. */
    @Volatile private var turn = 0
    private var track: AudioTrack? = null

    @Volatile var playing = false
        private set

    /**
     * A single WebSocket kept open across lines: opening a connection took ~1.4 s of ~2.4 s to first audio, versus
     * ~0.55 s on a warm connection. Opened early by [warm], closed after [IDLE_MS] without activity. Its events are
     * dispatched to the line in progress.
     */
    private var socket: WebSocket? = null
    @Volatile private var events: ((JSONObject) -> Unit)? = null
    @Volatile private var broken: ((String) -> Unit)? = null
    const val IDLE_MS = 60_000L
    private val idle = Runnable { if (events == null) close() }

    /** Opens the connection ahead of time (e.g. when the owner starts a turn) so the answer's audio starts sooner. */
    fun warm(context: Context) {
        if (usable(Voices.chosen, Offline.online, spent(context.applicationContext) ?: 0.0, Guard.voiceCeiling)) connection()
    }

    @Synchronized
    private fun connection(): WebSocket {
        main.removeCallbacks(idle)
        main.postDelayed(idle, IDLE_MS)
        socket?.let { return it }
        val url = Keys[Keys.Key.VOICE_URL] + (if ('?' in Keys[Keys.Key.VOICE_URL]) "&" else "?") + "model=" + Keys[Keys.Key.VOICE_MODEL]
        val ws = http.newWebSocket(
            Request.Builder().url(url).header("Authorization", "Bearer ${Keys[Keys.Key.VOICE_KEY]}").build(),
            object : WebSocketListener() {
                override fun onMessage(ws: WebSocket, message: String) {
                    runCatching { JSONObject(message) }.getOrNull()?.let { e -> events?.invoke(e) }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    gone(ws)
                    broken?.invoke("${response?.code ?: ""} ${t.message}")
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) = gone(ws)
            },
        )
        // OkHttp queues this until the socket opens.
        ws.send(session(Keys[Keys.Key.VOICE_NAME], style()))
        socket = ws
        return ws
    }

    @Synchronized private fun gone(ws: WebSocket) { if (socket === ws) socket = null }

    @Synchronized private fun close() { socket?.close(1000, null); socket = null }

    private fun style() = style(Language.current.english, Guard.warmth)

    /**
     * Speaks [text] with the cloud voice: [started] runs when the first audio plays, [beat] on each detected syllable,
     * [ended] after the last audio has played, and [instead] when the cloud fails before any audio (the caller then
     * uses the platform voice). Callbacks run on the main thread. Returns `false` immediately when [usable] is false.
     */
    fun say(context: Context, text: String, started: () -> Unit, beat: () -> Unit, ended: () -> Unit, instead: () -> Unit): Boolean {
        val app = context.applicationContext
        if (!usable(Voices.chosen, Offline.online, spent(app) ?: 0.0, Guard.voiceCeiling)) return false
        stop()
        val my = ++turn
        val voice = Keys[Keys.Key.VOICE_NAME]
        val style = style()
        val keep = text.length <= KEEP_CHARS
        val file = File(app.cacheDir, "voice/${key(text, voice, style)}")
        val kept = if (keep && file.exists()) runCatching { Base64.getDecoder().decode(file.readSealed()) }.getOrNull() else null
        val out = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
            .build()
        track = out
        var frames = 0L
        var loud = false
        val sound = java.io.ByteArrayOutputStream()
        fun mine() = turn == my
        fun play(pcm: ByteArray) {
            if (!mine()) return
            if (!playing) { playing = true; out.play(); main.post { if (mine()) started() } }
            val (n, now) = onsets(pcm, loud)
            loud = now
            repeat(n) { main.post { if (mine()) beat() } }
            out.write(pcm, 0, pcm.size)
            frames += pcm.size / 2
            if (keep) sound.write(pcm)
        }
        // Waits until the playback head reaches the last written frame, then releases the track.
        fun finish() = Thread {
            while (mine() && out.playbackHeadPosition < frames) Thread.sleep(40)
            if (!mine()) return@Thread
            playing = false
            runCatching { out.release() }
            main.post { if (mine()) ended() }
        }.start()
        if (kept != null) {
            file.setLastModified(System.currentTimeMillis())
            Thread { play(kept); finish() }.start()
            return true
        }
        fun fail(why: String) {
            if (!mine()) return
            Log.w(AssistantService.TAG, "cloud voice: $why")
            val spoke = playing
            stop()
            // No audio played yet: fall back to the platform voice; interrupted mid-line: end the line there.
            main.post { if (spoke) ended() else instead() }
        }
        main.postDelayed({ if (mine() && !playing) fail("no sound in $FIRST_SOUND_MS ms") }, FIRST_SOUND_MS)
        // Identifies this line's response by its metadata, so late events from a cancelled response are ignored.
        val tag = "line$my"
        var id: String? = null
        events = { event ->
            when (event.optString("type")) {
                "response.created" -> event.optJSONObject("response")?.takeIf { it.optJSONObject("metadata")?.optString("line") == tag }?.let { id = it.optString("id") }
                "response.output_audio.delta" -> if (id != null && event.optString("response_id") == id) play(Base64.getDecoder().decode(event.getString("delta")))
                "response.done" -> if (id != null && event.optJSONObject("response")?.optString("id") == id) {
                    events = null
                    connection()
                    count(app, seconds(frames * 2))
                    if (keep && frames > 0) runCatching { remember(app, file, sound.toByteArray()) }
                    if (frames == 0L) fail("no sound: ${event.optJSONObject("response")?.optString("status")}") else finish()
                }
                // A cancel error for a response that had just finished is not a failure.
                "error" -> event.optJSONObject("error")?.takeUnless { "cancel" in it.optString("code") }?.let { fail(it.optString("message")) }
            }
        }
        broken = { fail(it) }
        connection().send(request(spoken(text), style, tag))
        return true
    }

    /** Stops playback immediately and cancels the active response; the connection stays open for the next line. */
    fun stop() {
        turn++
        playing = false
        if (events != null) socket?.send("{\"type\":\"response.cancel\"}")
        events = null
        broken = null
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
    }

    private fun remember(context: Context, file: File, pcm: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeSealed(Base64.getEncoder().encodeToString(pcm))
        // Evicts the least recently used files beyond [KEEP_FILES].
        file.parentFile?.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP_FILES)?.forEach { it.delete() }
    }
}
