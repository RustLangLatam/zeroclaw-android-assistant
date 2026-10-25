package org.rustlanglatam.zeroclaw.android

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Offline answering (ADR-0025): the owner's opt-in, the local model it requires and the local engine
 * (llama.cpp's `llama-server`) that serves it.
 *
 * The model is downloaded only after the owner opts in, verified by SHA-256, and deleted when the owner opts out.
 * The fields are Compose state so the settings screen reflects progress live.
 */
object Offline {
    // The local model is configuration (`offline.model.*` in local.properties): a GGUF file, its display name,
    // download URL, SHA-256 and size in bytes. Without a URL the offline option is unavailable.
    val MODEL: String = BuildConfig.OFFLINE_MODEL_NAME
    val URL: String = BuildConfig.OFFLINE_MODEL_URL
    val SHA256: String = BuildConfig.OFFLINE_MODEL_SHA256
    val BYTES: Long = BuildConfig.OFFLINE_MODEL_BYTES.toLongOrNull() ?: 0L
    /** File name on the device: the URL's last path segment, lower-cased. */
    val FILE: String = URL.substringAfterLast('/').lowercase().ifEmpty { "offline-model.gguf" }

    /** Whether a local model is configured for this build. */
    val configured get() = URL.isNotBlank() && SHA256.isNotBlank() && BYTES > 0

    /** ZeroClaw agent name used offline, the engine's provider alias, its loopback port and context size in tokens. */
    const val AGENT = "offline"
    const val ALIAS = "local"
    const val PORT = 42620
    const val CONTEXT = 8192

    /** Owner tools exposed to the local model: the subset that still works without internet. */
    val TOOLS = listOf("remind", "list_reminders", "create_alarm", "next_alarm", "list_events", "open_app")

    /**
     * Handles an explicit request (reminder, alarm, agenda, weather, opening an app) directly, without any model.
     *
     * @return the reply text, or `null` when [words] is not such a request or the phone tools are not running, in
     *   which case the local model is asked instead.
     */
    fun direct(words: String): String? {
        val (tool, args) = Reminders.asked(words) ?: return null
        if (tool == "weather") return app?.let { ctx -> weatherSaid(ctx) }
        val tools = AssistantService.phoneTools ?: return null
        val result = tools.owner(tool, args)
        return when {
            tool == "remind" && result.startsWith("Reminder #") -> {
                val at = Regex("""set for (\S+) (\d{2}:\d{2})""").find(result)
                val day = at?.groupValues?.get(1)?.let { if (it == java.time.LocalDate.now().toString()) t(R.string.offline_today) else if (it == java.time.LocalDate.now().plusDays(1).toString()) t(R.string.offline_tomorrow) else it }
                t(R.string.offline_done_i_ll_remind_you_to, args.optString("text"), day, at?.groupValues?.get(2)) +
                    (if ("level high" in result) t(R.string.offline_as_it_matters_i_ll_call) else "")
            }
            tool == "list_events" -> agenda(result, args.optInt("days_from_today"))
            tool == "list_reminders" -> reminders(result)
            tool == "next_alarm" -> nextAlarm(result)
            tool == "open_app" && result.startsWith("Opened ") -> t(R.string.offline_opening_x, result.removePrefix("Opened ").trimEnd('.'))
            tool == "open_app" -> t(R.string.offline_i_can_t_find_that_app)
            tool == "create_alarm" && result.startsWith("Alarm set") -> t(R.string.offline_done_alarm_at_02d_02d).format(args.optInt("hour"), args.optInt("minute"))
            else -> result
        }
    }

    /**
     * Turns the `next_alarm` tool's output into a sentence: the time and day, or an explanation when Android's
     * next-alarm API reports a calendar alert or a reminder call instead of the clock app's alarm.
     */
    fun nextAlarm(result: String): String {
        val at = Regex("""next alarm: (\d{1,2}):(\d{2})(?::\d{2})? on (\d{4}-\d{2}-\d{2})""").find(result)
        val mine = Regex("""Alarms you set for the owner \(and did not turn off\): (.+)\.""").find(result)?.groupValues?.get(1)
        val also = mine?.let { t(R.string.offline_i_also_set_x, it) }.orEmpty()
        return when {
            at != null -> {
                val day = java.time.LocalDate.parse(at.groupValues[3]).let { d ->
                    when (d) { java.time.LocalDate.now() -> t(R.string.offline_today); java.time.LocalDate.now().plusDays(1) -> t(R.string.offline_tomorrow)
                        else -> d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) } }
                t(R.string.offline_your_next_alarm_is_x_at, day).format(at.groupValues[1].padStart(2, '0'), at.groupValues[2]) + also
            }
            "calendar" in result -> t(R.string.offline_android_only_lets_me_see_the) + also
            "reminder calls" in result -> t(R.string.offline_what_rings_next_is_one_of) + also
            else -> t(R.string.offline_you_have_no_alarm_pending) + also
        }
    }

    /** Describes the last cached weather reading and its time, or reports that none is available. */
    private fun weatherSaid(context: Context): String {
        val (w, at) = PhoneWidgets.last(context) ?: return t(R.string.offline_i_have_no_weather_yet_and)
        val time = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }
        val rain = if (w.rain >= Widgets.RAIN) t(R.string.offline_with_a_x_chance_of_rain, w.rain) else ""
        return t(R.string.offline_the_last_i_saw_at_x, time, w.now, Widgets.forecast(w.code, w.rain).lowercase(), rain)
    }

    /** Returns the word for today, tomorrow or the day after, given an offset in days. */
    private fun dayWord(days: Int) = when (days) { 0 -> t(R.string.offline_today); 1 -> t(R.string.offline_tomorrow); else -> t(R.string.offline_the_day_after_tomorrow) }

    /** Turns the calendar tool's lines (`HH:mm-HH:mm Title`) into one sentence listing the events of the day. */
    fun agenda(result: String, days: Int): String {
        val day = dayWord(days)
        val items = result.lines().mapNotNull { Regex("""^(?:#\d+ )?(\d{2}:\d{2})-(\d{2}:\d{2}) (.+)$""").find(it.trim()) }
            .map { t(R.string.offline_x_from_x_to_x, it.groupValues[3], it.groupValues[1], it.groupValues[2]) }
        return when {
            "No access" in result -> t(R.string.offline_i_can_t_see_your_calendar)
            items.isEmpty() -> t(R.string.offline_nothing_on_calendar_x, day.replaceFirstChar { it.uppercase() }, day)
            else -> t(R.string.offline_x_you_have, day.replaceFirstChar { it.uppercase() }) + join(items) + "."
        }
    }

    /** Turns the reminders tool's lines (`#id yyyy-MM-dd HH:mm [level] text`) into one sentence. */
    fun reminders(result: String): String {
        val today = java.time.LocalDate.now()
        val items = result.lines().mapNotNull { Regex("""^#\d+ (\d{4}-\d{2}-\d{2}) (\d{2}:\d{2})( every day)? \[\w+] (.+)$""").find(it.trim()) }.map { m ->
            val date = java.time.LocalDate.parse(m.groupValues[1])
            val day = when {
                m.groupValues[3].isNotEmpty() -> t(R.string.offline_every_day)
                date == today -> t(R.string.offline_today)
                date == today.plusDays(1) -> t(R.string.offline_tomorrow)
                else -> "${date.dayOfMonth}/${date.monthValue}"
            }
            t(R.string.offline_x_x_at_x, m.groupValues[4], day, m.groupValues[2])
        }
        return if (items.isEmpty()) t(R.string.offline_you_have_no_reminders)
        else tn(R.plurals.offline_you_have_reminders, items.size, items.size) + join(items) + "."
    }

    private fun join(items: List<String>) = if (items.size == 1) items[0] else items.dropLast(1).joinToString(", ") + t(R.string.offline_and) + items.last()

    /**
     * Builds the prompt for the local model: the owner's [text] followed by the `/no_think` switch some local models accept (the chat
     * template's own thinking switch made the model emit tool calls as plain text), plus, when [again] is set,
     * a hint to use its tools.
     */
    fun toSmall(text: String, again: Boolean = false) =
        // Small models fill gaps with plausible times, dates or numbers; a short guard is appended every turn.
        text + (if (again) t(R.string.offline_use_your_tools_if_needed) else "") +
            t(R.string.offline_never_say_a_time_date_or) + " /no_think"

    /** Whether [answer] is a tool call emitted as plain text (`{"name": ..., "arguments": ...}`). */
    fun rawCall(answer: String) = answer.trim().let { it.startsWith("{") && "\"name\"" in it && "\"arguments\"" in it }

    /** Whether the device has a working internet connection, as reported by the network callback. */
    @Volatile var online = true

    /** Whether answers go to the local model now: opted in, model ready, and no internet or no cloud model. */
    fun now() = local(online, wanted, ready, Keys.modelReady)

    /** Local mode: online, but the on-device model answers because no cloud model is set. */
    val localMode get() = online && now()

    /** Whether the local model answers, given the connection, the owner's opt-in, the model file and the cloud model. */
    fun local(online: Boolean, wanted: Boolean, ready: Boolean, cloud: Boolean) = wanted && ready && (!online || !cloud)

    /** Count of offline answers and total seconds spent on them, shown on the settings screen. */
    var answers by mutableStateOf(0)
    var seconds by mutableStateOf(0L)

    @Volatile private var engine: Process? = null

    /** Whether an engine answers `/health` on [PORT], whether started here or already running. */
    fun listening() = runCatching {
        (java.net.URI("http://127.0.0.1:$PORT/health").toURL().openConnection() as java.net.HttpURLConnection).run {
            connectTimeout = 500; readTimeout = 1000
            responseCode.also { disconnect() } == 200
        }
    }.getOrDefault(false)

    /**
     * Starts the engine unless one is already listening, and waits up to [waitMs] for it to become healthy, since
     * loading the model takes seconds. Restores the saved prompt slot once it is up.
     *
     * The engine holds about 1.5 GB of memory, so it runs only while offline; [stop] is called when the internet
     * returns.
     *
     * @return `false` if the engine did not come up.
     */
    @Synchronized
    fun ensure(context: Context, waitMs: Long = 90_000): Boolean {
        if (listening()) return true
        if (engine?.isAlive != true) {
            val bin = File(context.applicationInfo.nativeLibraryDir, "libllama-server.so")
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            engine = runCatching {
                ProcessBuilder(bin.path, "-m", file(context).path, "--jinja", "-c", "$CONTEXT", "-np", "1", "--host", "127.0.0.1",
                    "--port", "$PORT", "--alias", ALIAS, "-t", "$threads",
                    // Prompt cache: the KV cache is reused across requests and the slot is saved to disk between runs.
                    "--cache-reuse", "256", "--slot-save-path", slots(context).path + "/")
                    .redirectErrorStream(true).redirectOutput(File(context.cacheDir, "llama.log")).start()
            }.onFailure { android.util.Log.w(AssistantService.TAG, "offline engine: ${it.message}") }.getOrNull() ?: return false
        }
        val until = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < until) {
            if (listening()) { restore(context); return true }
            if (engine?.isAlive != true) return false
            Thread.sleep(500)
        }
        return false
    }

    /**
     * Kills the engine immediately. A graceful stop makes the engine finish the generation in progress first,
     * which can take minutes on a slow device.
     */
    fun stop() {
        val e = engine ?: return
        engine = null
        // Reads the pid via reflection to send SIGKILL: Android's destroy() sends SIGTERM, which the engine treats as
        // "finish the current request first".
        val pid = runCatching { e.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(e) }.getOrNull()
        if (pid != null) android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL) else e.destroyForcibly()
    }

    /**
     * Handles a connectivity change. Going online stops the engine to free its memory, unless it is still the one
     * answering (local mode); going offline clears the local conversation and, if offline answering applies, warms up
     * the engine.
     */
    fun network(online: Boolean) {
        if (online != this.online) android.util.Log.i(AssistantService.TAG, "network: ${if (online) "online" else "offline"}; engine ${if (engine?.isAlive == true) "running" else "off"}")
        if (!online && this.online) OwnerChat.forgetLocal()
        this.online = online
        offline = !online
        if (online && !now()) stop()
        // Warms up before the first question: model loading dominated first-answer latency on low-end devices.
        else if (now()) app?.let { ctx -> kotlin.concurrent.thread(name = "offline-warm") { ensure(ctx) } }
    }

    /** Whether the device is offline; Compose state shown on the home and conversation screens. */
    var offline by mutableStateOf(false)

    @Volatile private var app: Context? = null

    /** Returns the in-progress label; while the local model is answering, it includes the elapsed seconds. */
    fun thinking(): String = OwnerChat.localSeconds()?.let { s ->
        t(if (online) R.string.offline_thinking_on_the_phone_x_s else R.string.offline_thinking_without_internet_x_s, s)
    } ?: t(R.string.offline_thinking)

    /**
     * Records an offline answer that took [millis] and persists the counters. After the first answer of each engine
     * run, saves the prompt slot in the background.
     */
    fun answered(context: Context, millis: Long) {
        answers += 1; seconds += millis / 1000
        prefs(context).edit().putInt("answers", answers).putLong("seconds", seconds).apply()
        // Saved once per run: the slot file is a few hundred MB.
        if (!saved) { saved = true; kotlin.concurrent.thread(name = "offline-save") { slot("save") } }
    }

    // Processing the fixed system prompt (persona and tool definitions) dominates first-answer latency on a phone,
    // and takes minutes under the emulator's binary translation. The engine's slot state after processing it is saved
    // and restored on the next start; only the differing suffix is processed again (`--cache-reuse`).
    private val SLOT get() = "$FILE.slot"
    @Volatile private var saved = false

    private fun slots(context: Context) = File(context.cacheDir, "slots").apply { mkdirs() }

    private fun restore(context: Context) {
        saved = false
        if (File(slots(context), SLOT).exists()) slot("restore")
    }

    /** Saves or restores slot 0 to [SLOT] through `llama-server`'s `/slots` API and logs the result. */
    private fun slot(action: String) = runCatching {
        (java.net.URI("http://127.0.0.1:$PORT/slots/0?action=$action").toURL().openConnection() as java.net.HttpURLConnection).run {
            requestMethod = "POST"; doOutput = true; connectTimeout = 2_000; readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write("""{"filename":"$SLOT"}""".toByteArray()) }
            val body = (if (responseCode < 400) inputStream else errorStream)?.bufferedReader()?.readText().orEmpty()
            android.util.Log.i(AssistantService.TAG, "offline engine: $action ${responseCode} ${body.take(160)}")
            disconnect()
        }
    }.onFailure { android.util.Log.w(AssistantService.TAG, "offline engine: $action failed: ${it.message}") }

    /** Whether the owner opted in to offline answering; persisted with the settings. */
    var wanted by mutableStateOf(false)

    /** Download progress from 0 to 1 while downloading; `null` otherwise. */
    var progress by mutableStateOf<Float?>(null)

    /** Whether the model file is present and its checksum verified. */
    var ready by mutableStateOf(false)

    /** User-facing reason the last download failed; `null` if it did not fail. */
    var failed by mutableStateOf<String?>(null)

    fun size(bytes: Long) = "%.1f GB".format(Language.locale, bytes / 1e9)

    /** Explanation shown to the owner before the download starts. */
    val explanation
        get() = t(R.string.offline_to_answer_you_even_without_internet, size(BYTES))

    /**
     * Reply used when offline and unable to answer locally: states the limitation and how to enable offline answering
     * (the download runs on Wi-Fi only, so after the connection returns).
     */
    val limited
        get() = if (wanted && !ready) t(R.string.offline_i_have_no_internet_now_and) else t(R.string.offline_i_have_no_internet_now_and_2)

    /** One-line status for the settings screen. */
    val status
        get() = when {
            progress != null -> t(R.string.offline_downloading_x, ((progress ?: 0f) * 100).toInt())
            ready -> t(R.string.offline_ready_x_x, MODEL, size(BYTES)) + used
            failed != null -> failed!!
            wanted -> t(R.string.offline_waiting_for_wi_fi_to_download)
            !configured -> t(R.string.offline_not_configured)
            else -> t(R.string.offline_off)
        }

    /** Usage suffix for [status]; empty before the first answer. */
    private val used
        get() = if (answers == 0) "" else tn(R.plurals.offline_used, answers, answers, minutes())

    private fun minutes() = if (seconds < 60) "$seconds s" else "${seconds / 60} min"

    fun file(context: Context) = File(context.getExternalFilesDir("models"), FILE)

    private fun prefs(context: Context) = context.getSharedPreferences("offline", Context.MODE_PRIVATE)

    /** Initializes state at startup: whether the model is ready, the usage counters, and any download in progress. */
    fun load(context: Context) {
        app = context.applicationContext
        ready = wanted && file(context).length() == BYTES && prefs(context).getBoolean("checked", false)
        answers = prefs(context).getInt("answers", 0); seconds = prefs(context).getLong("seconds", 0)
        if (wanted && !ready) poll(context)
    }

    /** Opts in and enqueues the model download with [DownloadManager], unmetered networks only, with a visible notification. */
    fun turnOn(context: Context) {
        if (!configured) { failed = t(R.string.offline_not_configured); return }
        wanted = true; failed = null; Guard.save()
        if (ready || prefs(context).getLong("download", -1) >= 0) return
        file(context).delete()
        val request = DownloadManager.Request(Uri.parse(URL))
            .setTitle(t(R.string.offline_brain_to_answer_without_internet))
            .setDestinationUri(Uri.fromFile(file(context)))
            .setAllowedOverMetered(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        val id = context.getSystemService(DownloadManager::class.java).enqueue(request)
        prefs(context).edit().putLong("download", id).putBoolean("checked", false).apply()
        progress = 0f
    }

    /** Opts out: cancels any download, stops the engine and deletes the model. */
    fun turnOff(context: Context) {
        wanted = false; Guard.save()
        prefs(context).getLong("download", -1).takeIf { it >= 0 }?.let { context.getSystemService(DownloadManager::class.java).remove(it) }
        prefs(context).edit().clear().apply()
        stop()
        file(context).delete()
        progress = null; ready = false; failed = null
    }

    @Volatile private var checking = false

    /**
     * Updates download progress (called every second while the settings screen is open) and verifies the file's
     * SHA-256 once the download completes; a mismatch deletes the file and opts out.
     */
    fun poll(context: Context) {
        val id = prefs(context).getLong("download", -1).takeIf { it >= 0 } ?: return
        val manager = context.getSystemService(DownloadManager::class.java)
        manager.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) { prefs(context).edit().remove("download").apply(); progress = null; return }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> if (!checking) {
                    checking = true
                    thread(name = "offline-check") {
                        val ok = sha256(file(context)) == SHA256
                        prefs(context).edit().remove("download").putBoolean("checked", ok).apply()
                        if (!ok) { file(context).delete(); failed = t(R.string.offline_the_download_arrived_damaged_turn_it); wanted = false; Guard.save() }
                        ready = ok; progress = null; checking = false
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    prefs(context).edit().remove("download").apply()
                    failed = t(R.string.offline_it_could_not_be_downloaded_turn)
                    wanted = false; progress = null; Guard.save()
                }
                else -> progress = done.toFloat() / BYTES
            }
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buf = ByteArray(1 shl 20); while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
