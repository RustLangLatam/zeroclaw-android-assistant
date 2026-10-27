package org.rustlanglatam.zeroclaw.android

import android.Manifest
import androidx.compose.ui.graphics.asImageBitmap
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import android.os.CancellationSignal
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread

/** A line of the owner's chat; [at] is its timestamp in epoch milliseconds, [from] what was tapped when [via] is [Via.TAP]. */
data class Line(val who: Who, val text: String, val at: Long = System.currentTimeMillis(), val via: Via? = null, val from: String? = null) {
    enum class Who { ME, ASSISTANT, TOOL, NOTE }

    /** How the owner's input arrived: spoken, typed, or a tap on a UI element such as a widget or a suggestion. */
    enum class Via { VOICE, TYPED, TAP }
}

/** Compose UI state shared by the screens; survives activity recreation (e.g. rotation) but not process death. */
object Ui {
    val chat = mutableStateListOf<Line>()

    /** The current screen ([Place]); there is no persistent navigation menu, screens open on request. */
    var place by mutableIntStateOf(Place.HOME)

    /**
     * The current input level from the speech recognizer, normalized to 0..1, updated only while listening.
     * Drives the mark's listening animation so it reacts only to actual sound.
     */
    var micLevel by mutableFloatStateOf(0f)

    /** Time (epoch ms) when the splash icon started animating onto the home screen's mark; the mark's wake-up animation starts from it. */
    var wokeAt by mutableStateOf(0L)

    /** Whether the splash icon is about to animate onto the home screen's mark, which then skips its own entry animation. */
    var splashing by mutableStateOf(false)

    /** Whether the splash was dismissed before its icon animation completed. */
    var wokeBare = false

    /** Bounds of the home screen's mark in window coordinates; the target of the splash icon animation. */
    @Volatile var markBounds: androidx.compose.ui.geometry.Rect? = null

    /** Incremented on each word the text-to-speech output starts; the presence animation pulses on each change. */
    var word by mutableIntStateOf(0)

    /** The last photo search result: its title and the photo URIs, shown in [Place.PHOTOS]. */
    var photos by mutableStateOf<Pair<String, List<String>>?>(null)

    /** Publishes a photo search result on the main thread and switches to [Place.PHOTOS] if the app is visible. */
    fun show(title: String, uris: List<String>) = android.os.Handler(android.os.Looper.getMainLooper()).post {
        photos = title to uris
        if (Floating.appVisible) { menu = false; place = Place.PHOTOS }
    }

    /** The last file search result: the query and its hits, shown in [Place.FILES]. */
    var files by mutableStateOf<Pair<String, List<Files.Hit>>?>(null)

    fun showFiles(query: String, hits: List<Files.Hit>) = android.os.Handler(android.os.Looper.getMainLooper()).post {
        files = query to hits
        if (Floating.appVisible) { menu = false; place = Place.FILES }
    }

    /** Whether the menu is shown; it opens only on the owner's request. */
    var menu by mutableStateOf(false)

    /** The connector sheet open over the app ([ConnectorSheet]): [Connectors.PICK], a provider key, or "account:<alias>"; `null` when closed. */
    var connector by mutableStateOf<String?>(null)

    /** A settings section to reveal ([Sections]), by scrolling to it or opening its sheet; consumed by the settings screen. */
    var section by mutableStateOf<String?>(null)
    var ready by mutableStateOf(false)
    var thinking by mutableStateOf(false)
    var speaking by mutableStateOf(false)

    /** Debug builds only (UI automation scripts): overrides the ring shown on the mark; `null` uses the ring computed from memory. */
    var shownBond by mutableStateOf<Bond.Ring?>(null)

    /** Debug builds only: plays pre-recorded narration and face video muted, so a script can add the audio track afterwards. */
    var quietRecorded by mutableStateOf(false)

    /** Debug builds only: lifts [android.view.WindowManager.LayoutParams.FLAG_SECURE] on private screens for automated UI captures. */
    var allowCapture by mutableStateOf(false)

    /** Debug builds only: hides cost figures during automated UI captures. */
    var hideCosts by mutableStateOf(false)

    /** Debug builds only: forces the presence state (listening, processing, speaking) regardless of actual activity; `null` for none. */
    var pose by mutableStateOf<Presence?>(null)

    /** Debug builds only: keeps the mark visible while speaking, pulsing with each word. */
    var keepMark by mutableStateOf(false)

    /** Debug builds only: an image drawn in place of the mark's center; `null` draws the mark. */
    var face by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)

    /** Debug builds only: a video file played inside the mark button; `null` for none. */
    var faceVideo by mutableStateOf<java.io.File?>(null)


    /** Whether the speech recognizer is active, i.e. the microphone is in use. */
    var listening by mutableStateOf(false)

    /** Whether the home screen widgets are in rearrange mode (entered by a long press or a voice command). */
    var arranging by mutableStateOf(false)
    var pending by mutableStateOf<Approvals.Pending?>(null)

    /** The setup being edited (first run, or reopened from settings); `null` when none. */
    var draft by mutableStateOf<Draft?>(null)

    /**
     * Recomposition trigger, incremented every second while a screen is shown and on state changes;
     * screens read it before reading non-Compose state ([Guard], [Inbox]) so they recompose.
     */
    var tick by mutableIntStateOf(0)
    /** Increments [tick] on the main thread so screens re-read non-Compose state; callable from any thread. */
    fun refresh() { android.os.Handler(android.os.Looper.getMainLooper()).post { tick++ } }

    /** Appends [line] to the chat on the main thread and logs it; callable from any thread. */
    fun add(line: Line) = android.os.Handler(android.os.Looper.getMainLooper()).post {
        android.util.Log.i(AssistantService.TAG, "chat: ${line.who.name.lowercase()}: ${AssistantService.logged(line.text)}")
        chat += line
    }

    /** Inserts earlier lines, restored from ZeroClaw's session, before the lines already shown. */
    fun prepend(lines: List<Line>) = android.os.Handler(android.os.Looper.getMainLooper()).post { chat.addAll(0, lines) }
}

/** The editable setup values, saved by [MainActivity.saveSetup]. */
class Draft(owner: String, apps: Set<String>, groupChats: Boolean, family: List<String>, work: List<String>, voice: Boolean) {
    var owner by mutableStateOf(owner)
    var apps by mutableStateOf(apps)
    var groupChats by mutableStateOf(groupChats)
    var family by mutableStateOf(family)
    var work by mutableStateOf(work)
    var voice by mutableStateOf(voice)

    /** The current setup page: 0 name, 1 permissions, 2 contacts and apps. */
    var step by mutableIntStateOf(0)

    companion object {
        /** Returns an empty draft for the first run: no apps read and no contact groups until the owner picks them. */
        fun fresh() = Draft("", emptySet(), false, emptyList(), emptyList(), false)
        fun current() = Draft(Policy.owner, Inbox.apps, Inbox.groups, Policy.members("family"), Policy.members("work"), Guard.voice)
    }
}

/**
 * Hosts the owner's chat with the `owner` agent over the gateway's `/ws/chat`, by text or voice, and provides
 * what the screens ([App]) need from Android: credential confirmation, permissions, settings screens,
 * speech recognition and synthesis, and contacts.
 */
class MainActivity : ComponentActivity() {

    private var tts: TextToSpeech? = null

    @Volatile private var ttsReady = false
    private var unsaid: String? = null
    private var heardBy: ((String) -> Unit)? = null

    /** Whether the last request was spoken; if so, its answer is spoken as well. */
    @Volatile private var spoken = false

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Ui.listening = false
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { heard ->
                heardBy?.let { it(heard); heardBy = null } ?: send(heard, byVoice = true)
            }
        }
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Ui.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AssistantService.keepAlive()
        if (Build.VERSION.SDK_INT >= 31) {
            Ui.splashing = savedInstanceState == null
            if (Ui.splashing) standIn()
            splashScreen.setOnExitAnimationListener(::fuse)
        }
        Guard.load(filesDir)
        Offline.load(this)
        Voices.load(this)
        Guard.missing = { lacking().map { it.says } }
        if (!Guard.setUp && Ui.draft == null) Ui.draft = Draft.fresh()
        if (Guard.setUp && !permitted()) askPermissions()
        // Text-to-speech and the recognizer follow the assistant's language (ADR-0024).
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            Language.onChange = { runOnUiThread { tune(); Ui.refresh() } }
            tune()
            // Each newly installed voice is probed once, muted, to classify it; the voice is then chosen again.
            VoiceProbe(this) { runOnUiThread { tune() } }.start()
            ttsReady = true
            unsaid?.let { speak(it); unsaid = null }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = began()
                override fun onDone(id: String?) = finished()
                // Fires before each spoken word; drives the presence pulse.
                override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) { Ui.word++ }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { Ui.speaking = false }
            })
        }
        setContent {
            // System bar icon contrast follows the palette: dark icons on light, light on dark.
            val light = Ink.palette.light
            androidx.compose.runtime.LaunchedEffect(light) {
                val clear = android.graphics.Color.TRANSPARENT
                val style = if (light) SystemBarStyle.light(clear, clear) else SystemBarStyle.dark(clear)
                enableEdgeToEdge(style, style)
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
            // FLAG_SECURE keeps private screens out of screenshots, screen recordings and recent-apps thumbnails.
            val private = Place.private(Ui.place, Ui.pending != null, Ui.connector) && !Ui.allowCapture
            androidx.compose.runtime.LaunchedEffect(private) {
                if (private) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
            KsandraTheme { App(this) }
        }

        startForegroundService(Intent(this, AssistantService::class.java))
        OwnerChat.start(this)
        OwnerChat.onAnswer = { text -> runOnUiThread { if (Guard.voice || spoken) speak(text) } }
        OwnerChat.onError = { runOnUiThread { again() } }
        intents(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intents(intent)
    }

    /**
     * Handles intent extras (approve, listen, open an email, open a screen) only from the app's own intents.
     * This activity is exported as the launcher entry, so any app can start it with arbitrary extras; the app's
     * own intents carry this process's seal ([sealed]).
     */
    private fun intents(intent: Intent) {
        debugIntent(intent)
        if (!trusted(intent)) return
        intent.removeExtra(SEAL)
        approveIntent(intent)
        listenIntent(intent)
        sharedIntent(intent)
        photoIntent(intent)
        intent.getIntExtra("mailOffer", -1).takeIf { it >= 0 }?.let { id ->
            intent.removeExtra("mailOffer")
            Shares.mails[id]?.let { mail ->
                Shares.offer("${mail.subject}\n${mail.body}", speak = { runOnUiThread { speak(it) } }, ask = { shared -> runOnUiThread { send(t(R.string.share_ask_prompt) + "\n\n" + shared, byVoice = false) } }, from = mail.from)
            }
        }
        intent.getStringExtra("birthday")?.let { name ->
            intent.removeExtra("birthday")
            Birthdays.offer(name) { prompt -> runOnUiThread { send(prompt, byVoice = false) } }
        }
        mailIntent(intent)
        placeIntent(intent)
    }

    /** Opens the screen named by the `place` extra (e.g. the news screen from a briefing notification). */
    private fun placeIntent(intent: Intent) {
        val place = intent.getIntExtra("place", -1)
        if (place < 0) return
        intent.removeExtra("place")
        Ui.place = place
    }

    /** Opens the email named by the `mail` extra, from an important-email notification. */
    /** Shows what the owner shared ([ShareActivity]) as a card; the text leaves the phone only if the owner taps "ask". */
    private fun sharedIntent(intent: Intent) {
        if (!intent.getBooleanExtra("shared", false)) return
        intent.removeExtra("shared")
        val text = Shares.last ?: return
        Shares.last = null
        Shares.offer(text, speak = { runOnUiThread { speak(it) } }, ask = { shared ->
            runOnUiThread { send(t(R.string.share_ask_prompt) + "\n\n" + shared, byVoice = false) }
        })
    }

    /**
     * After [PhotoActivity]: the photo is saved and labeled; asks whether to explain it or just keep it. The photo
     * leaves the phone only for an explanation, and only to a cloud model (the local one reads its text instead).
     */
    private fun photoIntent(intent: Intent) {
        if (!intent.getBooleanExtra("photo", false)) return
        intent.removeExtra("photo")
        val (id, labels, text) = PhotoActivity.last ?: return
        PhotoActivity.last = null
        val read = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        val explain = t(R.string.photo_explain)
        val keep = t(R.string.photo_keep)
        Cards.show(t(R.string.photo_saved_title), t(R.string.photo_saved) + read.take(200).takeIf { it.isNotEmpty() }?.let { "\n\n> $it" }.orEmpty(), listOf(explain, keep), mapOf(
            explain to {
                val small = PhoneGallery(this).small(id).takeIf { it.exists() && !Offline.now() }
                val words = read.take(1500).takeIf { it.isNotEmpty() }
                // The phone's labels, for a model that cannot see the photo.
                val labelled = t(R.string.photo_explain_prompt) +
                    labels.takeIf { it.isNotEmpty() }?.let { "\n\n" + t(R.string.photo_labels_seen) + " " + it.joinToString(", ") }.orEmpty() +
                    words?.let { "\n\n" + t(R.string.photo_text_read) + "\n" + it }.orEmpty()
                // A model that sees the photo gets neither labels nor the text the phone read: it reads the picture better,
                // and it repeated both when wrong (a TV remote taken for a phone and a tie, a code "read" from its buttons).
                val seeing = t(R.string.photo_explain_prompt) + " " + t(R.string.photo_look)
                runOnUiThread {
                    if (small != null && Keys.visionReady) send(seeing, byVoice = Guard.voice, image = small.path, blind = labelled)
                    else send(labelled, byVoice = Guard.voice, image = small?.path)
                }
            },
            keep to {},
        ))
        remark(t(R.string.photo_question))
    }

    private fun mailIntent(intent: Intent) {
        val id = intent.getIntExtra("mail", -1)
        if (id < 0) return
        intent.removeExtra("mail")
        val mail = Mailbox.find(id) ?: return
        openMail(mail)
    }

    /**
     * Opens [mail] where the owner reads email: the originating notification's content intent, else the email app's
     * launcher, else a dialog. Accounts connected only to the assistant are shown as a card. Nothing is read aloud.
     */
    fun openMail(mail: Mailbox.Mail) {
        Mailbox.open(mail.id)
        Ui.refresh()
        val options = if (android.os.Build.VERSION.SDK_INT >= 34) android.app.ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null
        // An account connected only to the assistant has no email app on the phone, so it is shown as a card.
        if (mail.account != null && EmailAccounts.accounts.firstOrNull { it.alias == mail.account }?.onPhone != true) {
            Cards.show(mail.subject.ifBlank { mail.from }, t(R.string.mainactivity_from) + "${mail.from}${mail.address?.let { " <$it>" }.orEmpty()}\n\n${mail.body.ifBlank { mail.subject }}", emptyList())
            return
        }
        if (MessageListener.mailLinks[mail.id]?.let { runCatching { it.send(this, 0, null, null, null, null, options) }.isSuccess } == true) return
        packageManager.getLaunchIntentForPackage(mail.app)?.let { if (runCatching { startActivity(it) }.isSuccess) return }
        android.app.AlertDialog.Builder(this)
            .setTitle(mail.subject.ifBlank { mail.from })
            .setMessage(t(R.string.mainactivity_from_2) + "${mail.from}${mail.address?.let { " <$it>" }.orEmpty()}\n\n${mail.body.ifBlank { mail.subject }}")
            .setPositiveButton(t(R.string.mainactivity_close), null)
            .show()
    }

    /** Starts listening when the `listen` extra is set (long press on the floating mark), once the UI is ready. */
    private fun listenIntent(intent: Intent) {
        if (!intent.getBooleanExtra("listen", false)) return
        intent.removeExtra("listen")
        thread { repeat(50) { if (!Ui.ready) Thread.sleep(200) }; runOnUiThread { listen() } }
    }

    // While the app is visible, approvals and notices are shown in it rather than as notifications.
    override fun onStart() {
        super.onStart()
        note = { text -> add(Line(Line.Who.NOTE, text)); Chime.play(this) }
        prompt = { pending -> runOnUiThread { Ui.pending = pending; Gestures.play(Gesture.EXPECT); if (Guard.voice) speak(Phrases.approval(Guard.warmth, pending.act.title) ?: return@runOnUiThread) } }
        Floating.shown(this, true)
        Offers.morning(this) { said -> runOnUiThread { remark(said) } }
        MailNotice.onImportant = { mail -> runOnUiThread { remark(Phrases.mail(Guard.warmth, mail.from.substringBefore(' '))) } }
        PhoneReminders.onDue = { r -> runOnUiThread { remark(t(R.string.mainactivity_x_a_reminder_x, Policy.owner, r.text)) } }
        PhoneNews.onBriefing = { b -> runOnUiThread { remark(b.text) } }
        CatchUp.onBack = { text -> runOnUiThread { remark(text) } }
        // A catch-up summary already posted as a notification but not yet spoken is spoken now.
        val untold = CatchUp.last?.takeIf { !CatchUp.told }
        val greetings = getSharedPreferences("greeting", MODE_PRIVATE)
        if (untold != null) { CatchUp.markTold(this); greetings.edit().putLong("at", System.currentTimeMillis()).apply(); remark(untold.first) }
        // Greets the owner after more than three hours away (not during setup): generated by the agent from
        // known context when connected, a canned phrase otherwise. The timestamp is persisted so that a new
        // process or activity does not count as a return.
        if (Guard.setUp && !CatchUp.coming && System.currentTimeMillis() - greetings.getLong("at", 0) > 3 * 3600_000L) {
            greetings.edit().putLong("at", System.currentTimeMillis()).apply()
            val now = java.time.LocalDateTime.now()
            thread {
                repeat(25) { if (!Ui.ready) Thread.sleep(200) }
                val day = dayFacts(now)
                runOnUiThread {
                    if (Ui.ready && OwnerChat.socket != null && !Guard.policy.stopped) prompt(Phrases.welcome(Policy.owner, now, day))
                    else remark(Phrases.greeting(Guard.warmth, Policy.owner, now.hour))
                }
            }
        }
        Floating.update(this)
    }

    override fun onStop() {
        prompt = null
        note = null
        MailNotice.onImportant = null
        PhoneReminders.onDue = null
        PhoneNews.onBriefing = null
        CatchUp.onBack = null
        Floating.seen = Inbox.digest.size
        Floating.shown(this, false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Returning from a settings screen: the setup checks are re-evaluated.
        Ui.refresh()
        if (askedOverlay) {
            askedOverlay = false
            // If granted, enable it; otherwise explain where to find it (a sideloaded app's switch may be greyed out as a restricted setting).
            if (canFloat()) setBubble(true)
            else android.app.AlertDialog.Builder(this)
                .setTitle(t(R.string.mainactivity_always_at_hand))
                .setMessage(if (xiaomi) t(R.string.mainactivity_on_your_xiaomi_in_ksandra_s) else t(R.string.mainactivity_to_float_over_your_apps_i))
                .setPositiveButton(t(R.string.mainactivity_go_to_the_permission)) { _, _ -> askedOverlay = true; openOverlay() }
                // Some OEM builds ignore the package in the overlay intent and list every app; the app details page also
                // has the switch (sometimes under advanced permissions) and the overflow menu for restricted settings.
                .setNeutralButton(t(R.string.mainactivity_ksandra_s_info)) { _, _ ->
                    askedOverlay = true; openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName")
                }
                .setNegativeButton(t(R.string.mainactivity_not_now), null)
                .show()
        }
        again()
    }

    // The microphone is only used while the activity is in the foreground.
    override fun onPause() {
        barging = false
        recognizer?.cancel()
        Ui.listening = false
        super.onPause()
    }

    override fun onDestroy() {
        tts?.shutdown()
        CloudVoice.stop()
        recognizer?.destroy()
        // The chat connection is process-scoped; only this activity's callbacks are detached.
        OwnerChat.onAnswer = null
        OwnerChat.onError = null
        super.onDestroy()
    }

    /** Handles the `approve` extra from a strong approval's notification; approval requires the owner's biometrics or device credential. */
    private fun approveIntent(intent: Intent) {
        val id = intent.getIntExtra("approve", -1)
        if (id < 0) return
        intent.removeExtra("approve")
        getSystemService(NotificationManager::class.java).cancel(1000 + id)
        Ui.pending = null
        // The prompt text comes from the pending request itself; a request that already ended cannot be approved here.
        val pending = Guard.approvals.find(id) ?: return
        confirmOwner(pending.act.title) { ok -> Guard.approvals.answer(id, ok) }
    }

    /**
     * Debug builds only: applies adb intent extras used by `scripts/e2e.sh`, `scripts/screenshots.sh` and other UI automation,
     * e.g. `--es say …` (with `--ez spoken true` to treat it as speech), `--es from … --es incoming …` for a third-party
     * message, and the notification channel's settings.
     */
    private fun debugIntent(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        intent.getStringExtra("say")?.let { text ->
            val spoken = intent.getBooleanExtra("spoken", false)
            thread { while (!Ui.ready) Thread.sleep(300); runOnUiThread { send(text, byVoice = spoken) } }
        }
        // Scripted UI captures: palette, settings section, card shown or closed, and a line spoken verbatim.
        intent.getStringExtra("palette")?.let { setPalette(Palettes.of(it)) }
        // The birthday card for a contact (`--es birthdayCard Ana`), as the day-before notice opens it.
        intent.getStringExtra("birthdayCard")?.let { name -> Birthdays.offer(name) { prompt -> runOnUiThread { send(prompt, byVoice = false) } } }
        // The morning-briefing offer, whatever the hour (`--ez offerMorning true`).
        if (intent.getBooleanExtra("offerMorning", false)) Offers.showMorning(this) { said -> runOnUiThread { remark(said) } }
        // Busy mode for N minutes (`--ei busy 30`); 0 ends it.
        if (intent.hasExtra("busy")) intent.getIntExtra("busy", 0).let { if (it > 0) Busy.start(this, it.toLong(), Busy.Reason.BUSY) else Busy.end(this) }
        // Schedules a high-level reminder three seconds ahead to exercise the full-screen reminder call.
        intent.getStringExtra("call")?.let { PhoneReminders(this).add(it, System.currentTimeMillis() + 3_000, Reminders.Level.HIGH, false) }
        if (intent.hasExtra("appMode")) { Ui.place = Place.HOME; Guard.appMode = intent.getBooleanExtra("appMode", false); Guard.save() }
        if (intent.hasExtra("arrange")) { Ui.place = Place.HOME; Ui.arranging = intent.getBooleanExtra("arrange", false) }
        intent.getStringExtra("voiceName")?.let { name ->
            if (name == "natural") chooseVoice(CloudVoice.NAME) else Voices.available.firstOrNull { Voices.name(it) == name }?.let { chooseVoice(it.name) }
        }
        intent.getStringExtra("section")?.let { Cards.shown = null; Ui.connector = null; Sections.open(it) }
        intent.getStringExtra("card")?.let { Cards.show(it, intent.getStringExtra("cardBody").orEmpty().replace("\\n", "\n"), emptyList()) }
        if (intent.getBooleanExtra("closeCard", false)) Cards.shown = null
        intent.getStringExtra("narrate")?.let { speak(it) }
        // `--es narrateFile <file>`: plays a pre-recorded audio file from files/narration as speech output.
        intent.getStringExtra("narrateFile")?.let { narrateFile(java.io.File(filesDir, "narration/$it")) }
        // `--ez ringFull true|false`: animates the ring to fully lit over two seconds, or back to its computed state.
        if (intent.hasExtra("ringFull")) {
            val on = intent.getBooleanExtra("ringFull", false)
            val from = Ui.shownBond ?: Bond.of { Memory.depth(it) }
            val to = if (on) Bond.Ring(1f, 1f, 1f) else Bond.of { Memory.depth(it) }
            thread {
                for (k in 1..40) {
                    val x = k / 40f
                    Ui.shownBond = Bond.Ring(from.you + (to.you - from.you) * x, from.day + (to.day - from.day) * x, from.world + (to.world - from.world) * x)
                    Thread.sleep(50)
                }
                if (!on) Ui.shownBond = null
            }
        }
        if (intent.hasExtra("quietRecorded")) Ui.quietRecorded = intent.getBooleanExtra("quietRecorded", false)
        if (intent.hasExtra("allowCapture")) Ui.allowCapture = intent.getBooleanExtra("allowCapture", false)
        if (intent.hasExtra("hideCosts")) Ui.hideCosts = intent.getBooleanExtra("hideCosts", false)
        if (intent.hasExtra("keepMark")) Ui.keepMark = intent.getBooleanExtra("keepMark", false)
        // `--es pose listening|thinking|speaking`: forces that presence state; an empty value clears it.
        intent.getStringExtra("pose")?.let { p ->
            Ui.pose = when (p) { "listening" -> Presence.LISTENING; "thinking" -> Presence.PROCESSING; "speaking" -> Presence.SPEAKING; else -> null }
        }
        // `--es faceVideo <file>`: a video from the app's files played once in the mark button; an empty value clears it.
        intent.getStringExtra("faceVideo")?.let { name -> Ui.faceVideo = name.takeIf { it.isNotEmpty() }?.let { java.io.File(filesDir, it) }?.takeIf { it.exists() } }
        // `--es face <file>`: an image from the app's files (placed with `run-as`) drawn in the mark's center; an empty value clears it.
        intent.getStringExtra("face")?.let { path ->
            Ui.face = path.takeIf { it.isNotEmpty() }?.let { android.graphics.BitmapFactory.decodeFile(java.io.File(filesDir, it).path) }?.asImageBitmap()
        }
        // `--ei news <id>`: runs a followed topic's check now instead of at its scheduled time.
        intent.getIntExtra("news", -1).takeIf { it >= 0 }?.let { PhoneNews(this).check(it) }
        // `--ei newsAgain <id>`: forgets the topic's seen headlines and checks it now (a briefing for screenshots).
        intent.getIntExtra("newsAgain", -1).takeIf { it >= 0 }?.let { PhoneNews(this).run { unsee(it); check(it) } }
        intent.getIntExtra("watch", -1).takeIf { it >= 0 }?.let { PhoneNews(this).watch(it) }
        intent.getStringExtra("incoming")?.let { text ->
            if (text.isNotBlank()) Turns.thirdParty(this, intent.getStringExtra("from").orEmpty().trim().ifEmpty { "unknown" }, text)
        }
        // `--es apps a,b` and `--ez groups …` configure the channel without owner confirmation;
        // `--ez stop true|false` stops or resumes, so each end-to-end run starts from the same state
        // (outside debug builds, resuming always requires the owner's confirmation).
        intent.getStringExtra("apps")?.let { Inbox.apps = it.split(',').map(String::trim).filter(String::isNotEmpty).toSet() }
        if (intent.hasExtra("groups")) Inbox.groups = intent.getBooleanExtra("groups", false)
        if (intent.hasExtra("stop")) Guard.setStopped(intent.getBooleanExtra("stop", false))
        // `--ez clean true`: resets daily counters and contact introductions for a reproducible end-to-end run.
        if (intent.getBooleanExtra("clean", false)) { Guard.policy.resetCounters(); Inbox.forgetIntroductions(); Guard.save() }
        // `--ez clearActivity true` clears the activity log; `--ez fresh true` starts a new owner session (ZeroClaw keeps the old one).
        if (intent.getBooleanExtra("clearActivity", false)) Guard.clearActivity()
        if (intent.getBooleanExtra("fresh", false)) {
            OwnerChat.fresh(this)
        }
        // `--es language <tag>`: sets the UI and assistant language the checks expect (button labels, replies).
        intent.getStringExtra("language")?.let { setLanguage(it) }
        // `--ez voice false`: disables speech output, which otherwise keeps the UI animating and prevents it from going idle.
        if (intent.hasExtra("voice")) setVoice(intent.getBooleanExtra("voice", false))
        // `--es owner …`: completes setup with the given owner name (the emulator image holds fake contacts).
        intent.getStringExtra("owner")?.let { Policy.owner = it; Guard.setUp = true; Ui.draft = null; CatchUp.last = null }
        // `--es contacts Laura:work,Ana:family`: sets contact groups for a reproducible run.
        intent.getStringExtra("contacts")?.let { list ->
            Policy.groups = list.split(',').mapNotNull { it.split(':').takeIf { p -> p.size == 2 }?.let { (name, group) -> name.trim() to setOf(group.trim()) } }.toMap()
        }
        if (intent.hasExtra("apps") || intent.hasExtra("groups") || intent.hasExtra("stop") || intent.hasExtra("owner") || intent.hasExtra("contacts")) {
            Guard.save()
            Ui.refresh()
            android.util.Log.i(AssistantService.TAG, "debug: reads ${Inbox.apps.joinToString()}; groups ${Inbox.groups}; stopped ${Guard.policy.stopped}")
        }
    }

    private fun add(line: Line) = Ui.add(line)

    /**
     * Handles the owner's spoken or typed input: interface commands (e.g. opening the menu) are handled locally and
     * never sent; anything else is sent to the `owner` agent.
     *
     * @param from the UI element tapped to produce [text], if any; the chat line is then marked as a tap.
     * @param image a picture on the phone for the model to see along with [text]; not shown in the chat.
     */
    fun send(text: String, byVoice: Boolean = false, from: String? = null, image: String? = null, blind: String? = null) {
        val trimmed = text.trim()
        Commands.of(trimmed)?.let { return act(it, byVoice) }
        if (trimmed.isEmpty() || OwnerChat.socket == null) return
        Ui.menu = false
        // A new owner turn: any pending conflict question counts as answered, and no third-party content has been read in it yet.
        Guard.undecided = false
        Guard.heardOthers = false
        Empathy.Style.learn(trimmed)
        // Spoken input is answered aloud on the current screen; typed input switches to the conversation screen.
        if (!byVoice) Ui.place = Place.TALK
        spoken = byVoice
        // Added before sending so that an immediate local answer (e.g. offline) appears after it.
        add(Line(Line.Who.ME, trimmed, via = if (from != null) Line.Via.TAP else if (byVoice) Line.Via.VOICE else Line.Via.TYPED, from = from))
        Ui.thinking = true
        OwnerChat.send(trimmed, image = image, blind = blind)
        // Opens the cloud voice connection while the agent is processing, to reduce speech latency.
        if (Guard.voice || byVoice) CloudVoice.warm(this)
    }

    private fun act(command: Command, byVoice: Boolean) {
        android.util.Log.i(AssistantService.TAG, "command: $command")
        fun go(place: Int) { Ui.menu = false; Ui.place = place }
        when (command) {
            Command.MENU -> { Ui.menu = true; if (byVoice || Guard.voice) speak(MENU_PROMPT) }
            Command.CLOSE -> Ui.menu = false
            Command.HOME -> go(Place.HOME)
            Command.TALK -> go(Place.TALK)
            Command.ACTIVITY -> go(Place.ACTIVITY)
            Command.CONTROL -> go(Place.CONTROL)
            Command.RULES -> go(Place.RULES)
            Command.MEMORY -> go(Place.MEMORY)
            Command.PHOTOS -> go(Place.PHOTOS)
            Command.FILES -> go(Place.FILES)
            Command.NEWS -> go(Place.NEWS)
            Command.MODELS -> Sections.open("models")
            Command.INTEGRATIONS -> Sections.open("integrations")
            Command.THEMES -> Sections.open("color")
            Command.WIDGETS -> { go(Place.HOME); Ui.arranging = true }
            Command.PAUSE -> { Ui.menu = false; setStopped(true); if (byVoice) speak(t(R.string.mainactivity_all_right_i_ll_stay_paused)) }
            Command.RESUME -> { Ui.menu = false; setStopped(false) }
            Command.SIMPLE_MODE, Command.NORMAL_MODE -> thread {
                Simple.set(this, command == Command.SIMPLE_MODE)
                runOnUiThread { tune(); remark(if (Guard.simple) t(R.string.simple_on) else t(R.string.simple_off)) }
            }
            Command.APP_MODE -> setAppMode(true)
            Command.ASSISTANT_MODE -> setAppMode(false)
        }
    }

    /**
     * Starts listening for a spoken request. Uses an in-app [SpeechRecognizer] without a dialog (the presence
     * animation shows the state); without the microphone permission or an available recognizer, falls back to the
     * system's speech dialog. Stops any ongoing speech output.
     */
    fun listen() = listen(byItself = false)

    /** @param byItself `true` when the open-microphone mode restarts listening automatically, `false` when the owner initiated it. */
    private fun listen(byItself: Boolean) {
        if (Ui.listening) return
        listenedByItself = byItself
        if (barging) { barging = false; recognizer?.cancel() }
        quiet()
        Ui.speaking = false
        val ear = recognizer ?: if (granted(Manifest.permission.RECORD_AUDIO) && SpeechRecognizer.isRecognitionAvailable(this)) {
            SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(hearing); recognizer = it }
        } else null
        val intent = hearIntent().putExtra(RecognizerIntent.EXTRA_PROMPT, t(R.string.mainactivity_ask_x, Policy.ASSISTANT))
        try {
            if (ear != null) ear.startListening(intent) else speech.launch(intent)
            Ui.listening = true
        } catch (e: ActivityNotFoundException) {
            toast(t(R.string.mainactivity_this_phone_has_no_speech_recognition))
        }
    }

    private var recognizer: SpeechRecognizer? = null

    /** Whether the current listening session has already been retried once after a recognizer error. */
    private var heardAgain = false

    /**
     * Builds the recognizer intent. The language tag includes the device's country when the languages match
     * (e.g. "es-CO"), because a bare language tag returned no results on some devices. Offline recognition is
     * preferred only without connectivity: always preferring it returned nothing on devices lacking the offline model.
     */
    private fun hearIntent(): Intent {
        val phone = java.util.Locale.getDefault()
        val tag = if (phone.language == Language.current.tag && phone.country.isNotEmpty()) phone.toLanguageTag() else Language.locale.toLanguageTag()
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            .apply { if (!Offline.online) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) }
    }

    /** Whether the current listening session was started automatically by open-microphone mode. */
    private var listenedByItself = false

    /** Time of the last exchange; for a while after it, a follow-up does not need to start with the assistant's name. */
    private var lastExchange = 0L

    /** The text currently being spoken, used to tell the assistant's own echo from the owner's speech. */
    private var saying = ""

    /** Whether the recognizer is listening during speech output, so the owner can interrupt by talking (barge-in). */
    private var barging = false

    /**
     * Starts barge-in listening during speech output (foreground only, with the microphone permission). Speech
     * that is not the assistant's own echo ([Commands.interrupts]) stops the output and becomes the next request.
     */
    private fun barge() {
        // On the loudspeaker the recognizer picks up the assistant's own output and mistakes it for the owner, so
        // barge-in is enabled only with headphones; on the loudspeaker, tapping the mark stops speech.
        if (!headphones()) return
        if (barging || Ui.listening || Guard.policy.stopped || !granted(Manifest.permission.RECORD_AUDIO) ||
            !SpeechRecognizer.isRecognitionAvailable(this) || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return
        val ear = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(hearing); recognizer = it }
        barging = true
        runCatching {
            ear.startListening(hearIntent().putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        }.onFailure { barging = false }
    }

    private fun headphones() = getSystemService(android.media.AudioManager::class.java).getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type in setOf(android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO, android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    /** Handles a barge-in: stops speech output immediately and switches to listening. */
    private fun interrupted() {
        barging = false
        listenedByItself = false
        lastExchange = System.currentTimeMillis()
        quiet()
        Ui.speaking = false
        Ui.listening = true
    }

    private val hearing = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (barging) {
                // Only the assistant's echo, or nothing: speech continues and barge-in listening restarts.
                if (heard == null || !Commands.interrupts(heard, saying)) {
                    barging = false
                    if (talking()) barge()
                    return
                }
                interrupted()
            }
            Ui.listening = false
            heardAgain = false
            if (heard == null) { again(); return }
            // In open-microphone mode only speech addressed to the assistant (Commands.addressed) is handled; the rest is ignored.
            if (listenedByItself && heardBy == null && !Commands.addressed(heard, System.currentTimeMillis() - lastExchange)) { again(); return }
            lastExchange = System.currentTimeMillis()
            heardBy?.let { it(heard); heardBy = null } ?: send(heard, byVoice = true)
            // An interface command produces no spoken answer to wait for, so listening resumes now.
            if (!Ui.thinking && !Ui.speaking) again()
        }

        override fun onError(error: Int) {
            if (barging) {
                barging = false
                if (talking()) window.decorView.postDelayed({ if (talking()) barge() }, 300)
                return
            }
            Ui.listening = false
            android.util.Log.w(AssistantService.TAG, "hearing: error $error")
            // Silence or no match: open-microphone mode just listens again; a manual request shows a hint.
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                if (!listenedByItself && !Guard.openMic) toast(t(R.string.mainactivity_i_didn_t_hear_you_well))
                again(); return
            }
            // A busy or broken recognizer (seen after barge-in) or a missing language: recreate it and retry once.
            if (!heardAgain) {
                heardAgain = true
                recognizer?.destroy(); recognizer = null
                window.decorView.postDelayed({ listen(listenedByItself) }, 300)
                return
            }
            heardAgain = false
            toast(t(R.string.mainactivity_i_couldn_t_hear_you_error, error))
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        // The recognizer reports roughly -2 dB (silence) to 10 dB (clear speech); mapped to 0..1.
        override fun onRmsChanged(rmsdB: Float) { if (Ui.listening) Ui.micLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { Ui.micLevel = 0f }
        override fun onPartialResults(partialResults: Bundle?) {
            if (!barging) return
            val heard = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            if (Commands.interrupts(heard, saying)) interrupted()
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** Restarts listening after a short delay when open-microphone mode is on or an answer is awaited (foreground only). */
    private fun again() = window.decorView.postDelayed({
        if ((Guard.openMic || awaitAnswer) && !talking() && (recognizer != null || awaitAnswer) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            !Guard.policy.stopped && Ui.ready && !Ui.thinking && !Ui.speaking && Ui.draft == null
        ) { awaitAnswer = false; listen(byItself = true) }
    }, 400)

    /** Whether the assistant asked a question and should listen once for the answer, even without open-microphone mode. */
    @Volatile private var awaitAnswer = false

    /** Has the agent ask the owner [question] in its own words, then listens once for the answer; spoken as is when disconnected. */
    fun ask(question: String) {
        if (OwnerChat.socket == null) return remark(question)
        awaitAnswer = true
        prompt(
            "Ask ${Policy.owner}, in your own words and in one short sentence, what the app says you would like to know: " +
                "«$question». When they answer, keep it with remember (confirmed).",
        )
    }

    /** Sets the assistant's language ([Language]): persists it, rewrites the agent's identity files, and retunes speech output and recognition. */
    fun setLanguage(tag: String) {
        Language.chosen = tag
        Guard.save()
        thread { AssistantService.writeIdentity(this) }
        Language.onChange()
    }

    /** Turns offline answers on after the owner confirms the download, or off (which deletes the local model). */
    fun setOffline(on: Boolean) {
        if (!on) return Offline.turnOff(this)
        android.app.AlertDialog.Builder(this)
            .setTitle(t(R.string.mainactivity_answer_without_internet))
            .setMessage(Offline.explanation)
            .setPositiveButton(t(R.string.mainactivity_download)) { _, _ -> Offline.turnOn(this) }
            .setNegativeButton(t(R.string.mainactivity_not_now), null)
            .show()
    }

    fun setOpenMic(on: Boolean) {
        if (on && !granted(Manifest.permission.RECORD_AUDIO)) return microphone.launch(Manifest.permission.RECORD_AUDIO)
        val was = Guard.openMic
        Guard.openMic = on
        Guard.save()
        Ui.refresh()
        // Explains open-microphone mode once, when it is turned on.
        if (on && !was) remark(t(R.string.mainactivity_done_while_you_have_me_open))
        if (on) again() else { recognizer?.cancel(); Ui.listening = false }
    }

    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) setOpenMic(true) }

    /**
     * A view that mirrors the system splash until [fuse] takes over. [fuse] animates the splash icon to the position
     * and size of the home screen's mark while the splash background fades, then cross-fades into the real mark;
     * if the mark's bounds are not known in time, the splash just fades out.
     */
    private var standIn: android.view.View? = null

    /**
     * Adds a copy of the splash over the window until the splash is handed over: the system removes its splash a
     * few frames before the exit animation's view appears, and the app would otherwise show through. The splash
     * icon view is 192 dp and shows the central 160 of the icon's 240 units, so the full icon is 288 dp.
     */
    private fun standIn() {
        val side = (288 * resources.displayMetrics.density).toInt()
        standIn = android.widget.FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.splash))
            addView(android.widget.ImageView(context).apply { setImageResource(R.drawable.splash_mark_lit) }, android.widget.FrameLayout.LayoutParams(side, side, android.view.Gravity.CENTER))
            // Fallback in case the splash is never handed over.
            postDelayed({ (parent as? android.view.ViewGroup)?.removeView(this) }, 5_000)
        }
        (window.decorView as android.view.ViewGroup).addView(standIn, android.view.ViewGroup.LayoutParams(-1, -1))
    }

    @android.annotation.TargetApi(31)
    private fun fuse(splash: android.window.SplashScreenView, tries: Int = 0) {
        standIn?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); standIn = null }
        val icon = splash.iconView
        val target = Ui.markBounds
        if (icon == null || (target == null && tries >= 12)) {
            splash.animate().alpha(0f).setDuration(250).withEndAction { splash.remove(); Ui.splashing = false }
            return
        }
        if (target == null) { splash.postDelayed({ fuse(splash, tries + 1) }, 40); return }
        // Once handed over, the splash icon animation stops where it was; if it had not completed, the
        // mark's ring finishes lighting up after landing.
        val shown = splash.iconAnimationStart?.toEpochMilli()?.let { System.currentTimeMillis() - it } ?: Long.MAX_VALUE
        Ui.wokeBare = shown < (splash.iconAnimationDuration?.toMillis() ?: 0L)
        Ui.wokeAt = System.currentTimeMillis()
        val at = IntArray(2).also { icon.getLocationInWindow(it) }
        // The splash icon is 240 units, of which the system shows the central 160 across its view (Android 12
        // icon rule; measured: a 504 px view showed a 472 px disc). Its disc is 94 × 1.6 units; the mark's disc is
        // 94 of its 120 units.
        val back = splash.background?.mutate()
        // The background fades late in the flight; fading earlier would reveal the real mark behind the moving icon.
        android.animation.ValueAnimator.ofInt(255, 0).apply {
            startDelay = 380
            duration = 420
            addUpdateListener { back?.alpha = it.animatedValue as Int }
        }.start()
        // Each frame re-reads the target bounds, so the icon lands exactly even if the layout moves meanwhile.
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 560
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val f = it.animatedValue as Float
                val to = Ui.markBounds ?: target
                val scale = (to.width * 94f / 120f) / (icon.width * 94f * 1.6f / 160f)
                icon.translationX = f * (to.center.x - (at[0] + icon.width / 2f))
                icon.translationY = f * (to.center.y - (at[1] + icon.height / 2f))
                icon.scaleX = 1f + f * (scale - 1f)
                icon.scaleY = icon.scaleX
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    icon.animate().alpha(0f).setDuration(200).withEndAction { splash.remove(); Ui.splashing = false }
                }
            })
        }.start()
    }

    /** Stops speech output immediately; the text remains in the chat. */
    fun hush() {
        quiet()
        Ui.speaking = false
    }

    private fun speak(text: String) {
        // TextToSpeech initializes asynchronously; text spoken before it is ready is queued until then.
        if (!ttsReady) { unsaid = text; return }
        saying = text
        // Uses the cloud voice when selected; falls back to the platform TTS otherwise or if the cloud does not respond in time.
        if (CloudVoice.say(this, text, ::began, { Ui.word++ }, ::finished) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "answer") }) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "answer")
    }

    private var narration: android.media.MediaPlayer? = null

    /** Debug builds only: plays a pre-recorded audio file as speech output, with the mark animating as if speaking. */
    private fun narrateFile(file: java.io.File) {
        quiet()
        narration?.release()
        narration = android.media.MediaPlayer().apply {
            setDataSource(file.path)
            setOnCompletionListener { it.release(); narration = null; finished() }
            // Muted when the capture script adds the audio track itself (`--ez quietRecorded true`).
            if (Ui.quietRecorded) setVolume(0f, 0f)
            setOnPreparedListener { it.start(); android.util.Log.i(AssistantService.TAG, "narrating ${file.name}"); began(); beat(it) }
            prepareAsync()
        }
    }

    // Pulses the mark about four times a second while a recording plays.
    private fun beat(player: android.media.MediaPlayer) {
        window.decorView.postDelayed({ if (narration === player && runCatching { player.isPlaying }.getOrDefault(false)) { Ui.word++; beat(player) } }, 240)
    }

    private fun began() { Ui.speaking = true; runOnUiThread { barge() } }

    private fun finished() {
        Ui.speaking = false
        // UI automation scripts wait for this log line before their next step.
        if (BuildConfig.DEBUG) android.util.Log.i(AssistantService.TAG, "spoke")
        lastExchange = System.currentTimeMillis()
        // No barge-in happened: stop the recognizer that listened during speech.
        runOnUiThread { if (barging) { barging = false; recognizer?.cancel() }; again() }
    }

    /** Whether either the platform TTS or the cloud voice is speaking. */
    private fun talking() = tts?.isSpeaking == true || CloudVoice.playing

    /** Stops both the platform TTS and the cloud voice. */
    private fun quiet() { tts?.stop(); CloudVoice.stop() }

    private fun tune() { tts?.asKsandra() }

    /** Persists the voice [name] chosen in settings and speaks a sample with it. */
    fun chooseVoice(name: String) {
        Voices.chosen = name
        Guard.save()
        tune()
        speak(t(R.string.mainactivity_hi_x_this_is_how_i, Policy.owner))
    }

    /** Speaks [text] aloud; does nothing when `null`. */
    fun remark(text: String?) {
        if (text == null) return
        speak(text)
    }

    /** Switches between assistant mode and app mode (bottom navigation bar) and announces how to switch back. */
    fun setAppMode(on: Boolean) {
        Ui.menu = false
        Guard.appMode = on
        Guard.save()
        remark(
            if (on) t(R.string.mainactivity_done_now_you_see_me_as)
            else t(R.string.mainactivity_done_back_to_us_just_you),
        )
    }

    /**
     * Connects one of the owner's email accounts, or disconnects it when [remove] is set. A new account is first
     * verified against its server, then, since it widens what the assistant reads, confirmed with the owner's credential
     * (refused without a screen lock); it is stored sealed on the phone, ZeroClaw is reloaded with it and its inbox
     * watched. Disconnecting narrows and needs no credential. [done] receives the failure reason, or `null` on success.
     */
    fun connectEmail(account: EmailAccounts.Account, remove: Boolean = false, done: (String?) -> Unit) = thread {
        val problem = if (remove) null else PhoneEmail.check(account)
        runOnUiThread {
            if (problem != null) return@runOnUiThread done(problem)
            if (remove) saveEmail(account, remove = true, done)
            else confirmOwner(t(R.string.mainactivity_connect_x, account.address)) { ok -> if (ok) saveEmail(account, remove = false, done) else done(t(R.string.mainactivity_not_confirmed_nothing_connected)) }
        }
    }

    private fun saveEmail(account: EmailAccounts.Account, remove: Boolean, done: (String?) -> Unit) {
        EmailAccounts.save(account, remove)
        AssistantService.reload?.invoke()
        PhoneEmail.watch(applicationContext)
        Ui.connector = null
        remark(if (remove) t(R.string.mainactivity_done_i_no_longer_read_x, account.address)
            else t(R.string.mainactivity_done_i_read_all_your_email, account.address))
        done(null)
    }

    /**
     * Saves service settings and keys ([Keys]) and restarts the daemon with the rewritten config. Replacing settings
     * already in use needs the owner's credential (it changes where their data goes); the first ones do not.
     */
    fun saveKeys(values: Map<Keys.Key, String>, done: (String?) -> Unit) {
        if (!values.all { (k, v) -> Keys.valid(k, v) }) return done(t(R.string.mainactivity_that_value_can_t_be_used))
        fun apply() {
            Keys.save(values)
            AssistantService.reload?.invoke()
            Ui.connector = null
            Ui.refresh()
            remark(t(R.string.mainactivity_saved_i_use_it_from_now))
            done(null)
        }
        val first = !Keys.modelReady && Keys.Key.entries.none { Keys.entered(it) }
        if (first) apply() else confirmOwner(t(R.string.mainactivity_change_the_services_i_use)) { ok -> if (ok) apply() else done(null) }
    }

    fun setMail(on: Boolean) {
        Mailbox.reading = on
        Guard.save()
        Ui.refresh()
    }

    fun setWarmth(w: Warmth) {
        Guard.warmth = w
        Guard.save()
        tune()
        thread { AssistantService.writeIdentity(this) }
        remark(when (w) { Warmth.QUIET -> t(R.string.mainactivity_all_right_i_ll_say_only); Warmth.NATURAL -> t(R.string.mainactivity_perfect_that_s_how_i_ll); Warmth.CLOSE -> t(R.string.mainactivity_i_love_it_i_ll_talk) })
    }

    /** Speaks [text] aloud; used by the setup flow. */
    fun say(text: String) = speak(text)

    /** Listens once and passes the result to [onHeard] instead of sending it to the agent; used by the setup flow. */
    fun listenFor(onHeard: (String) -> Unit) {
        heardBy = onHeard
        listen()
    }

    fun toast(text: String) = runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }

    /** Records the owner's answer to [pending]; approving a strong request also requires [confirmOwner]. */
    fun answer(pending: Approvals.Pending, approved: Boolean) {
        Ui.pending = null
        getSystemService(NotificationManager::class.java).cancel(1000 + pending.id)
        if (approved && pending.strong) {
            confirmOwner(pending.act.title) { ok -> Guard.approvals.answer(pending.id, ok) }
        } else {
            Guard.approvals.answer(pending.id, approved)
        }
    }

    /** Whether a strong biometric (e.g. a fingerprint) is enrolled. */
    fun fingerprint() = getSystemService(android.hardware.biometrics.BiometricManager::class.java)
        .canAuthenticate(Authenticators.BIOMETRIC_STRONG) == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Confirms the owner before [done] receives `true`: a strong biometric or the screen lock when a biometric is
     * enrolled, the screen lock (PIN, pattern or password) alone otherwise. Without a screen lock there is no
     * credential to ask for and [done] receives `true`; capabilities that require a strong approval are refused
     * before reaching this point in that case (`Guard.secure`).
     */
    fun confirmOwner(what: String, done: (Boolean) -> Unit) {
        // Without a screen lock there is nothing to confirm with, and a tap could come from anyone holding the phone:
        // refused (fail closed), with the reason and the way to set a lock.
        if (!secure()) {
            toast(t(R.string.mainactivity_set_a_screen_lock_first))
            openSecurity()
            return done(false)
        }
        // Combining BIOMETRIC_STRONG with DEVICE_CREDENTIAL fails when no biometric is enrolled; DEVICE_CREDENTIAL
        // alone is supported from API 30, the minimum SDK.
        val authenticators = if (fingerprint()) Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
            else Authenticators.DEVICE_CREDENTIAL
        BiometricPrompt.Builder(this).setTitle(t(R.string.mainactivity_confirm_it_s_you)).setDescription(what)
            .setAllowedAuthenticators(authenticators)
            .build().authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(true)

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    toast(t(R.string.mainactivity_not_confirmed_x, errString))
                    done(false)
                }
            })
    }

    /** Stops the assistant immediately, or resumes it after [confirmOwner]. */
    fun setStopped(stopped: Boolean) {
        fun set() {
            Guard.setStopped(stopped)
            Ui.refresh()
            remark(if (stopped) Phrases.paused(Guard.warmth, Policy.owner) else Phrases.resumed(Guard.warmth, Policy.owner))
        }
        if (stopped) set() else confirmOwner(t(R.string.mainactivity_resume_the_assistant)) { ok -> if (ok) set() }
    }

    /** Changes rule [index] to [autonomy], with the owner's confirmation. */
    fun editRule(index: Int, autonomy: Autonomy) {
        val (title, _) = Labels.rule(Guard.policy.rules[index])
        // Narrowing a rule never needs the credential, so it works without a screen lock too.
        if (autonomy <= Guard.policy.rules[index].autonomy) {
            Guard.policy.edit(index, autonomy)
            Guard.save()
            Ui.refresh()
            return
        }
        confirmOwner("$title → ${Labels.autonomy(autonomy)}") { ok ->
            if (!ok) return@confirmOwner
            Guard.policy.edit(index, autonomy)
            Guard.save()
            Ui.refresh()
        }
    }

    /** Sets the monthly budget in US dollars for the cloud model ([Budget]); 0 means none. */
    fun setModelBudget(usd: Double) {
        Guard.modelBudget = usd
        Guard.save()
        Budget.forget()
        thread { runCatching { Budget.spent(this); Budget.check(this) } }
        Ui.refresh()
    }

    /** Sets the monthly spending ceiling in US dollars for the cloud voice; 0 means no ceiling. */
    fun setVoiceCeiling(usd: Double) {
        Guard.voiceCeiling = usd
        Guard.save()
        Ui.refresh()
    }

    fun setUndoSeconds(seconds: Int) {
        Guard.undoSeconds = seconds
        Guard.save()
        Ui.refresh()
    }

    /** Whether the owner was sent to the "display over other apps" settings; checked in [onResume]. */
    private var askedOverlay = false

    /** Turns the floating mark on or off; turning it on first requests the overlay permission if needed. */
    fun setBubble(on: Boolean) {
        if (on && !canFloat()) { askedOverlay = true; return openOverlay() }
        Guard.bubble = on
        Guard.save()
        Ui.refresh()
    }

    fun canFloat() = Floating.permitted(this)

    /** Whether usage access is granted, so the most used apps can be determined. */
    fun knowsUsage() = PhoneApps(this).usageKnown()

    fun openUsage() = openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun allFiles() = android.os.Environment.isExternalStorageManager()

    fun openAllFiles() = openSettings(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:$packageName")

    fun forget(item: Memory.Item) = Memory.forget(item.id)

    fun setLearning(on: Boolean) = Memory.pause(!on)

    fun setPalette(palette: Palette) {
        Ink.base = Palettes.of(palette.key)
        Guard.save()
    }

    /** Whether the device is Xiaomi, Redmi or POCO, whose own permission screen holds switches Android's settings do not show. */
    val xiaomi = android.os.Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")

    /** Opens MIUI's autostart list (app details as fallback); without autostart, MIUI kills the app in the background. */
    fun openAutostart() {
        if (runCatching { startActivity(Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")) }.isFailure)
            openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName")
    }

    fun openOverlay() {
        // MIUI's per-app permission editor holds "display pop-up windows" (and its background variant).
        if (xiaomi) listOf("com.miui.permcenter.permissions.PermissionsEditorActivity", "com.miui.permcenter.permissions.AppPermissionsEditorActivity").forEach { cls ->
            if (runCatching {
                    startActivity(Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter", cls).putExtra("extra_pkgname", packageName))
                }.isSuccess) return
        }
        openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName")
    }

    fun setVoice(on: Boolean) {
        Guard.voice = on
        Guard.save()
        Ui.refresh()
    }

    /**
     * Saves [d], with [confirmOwner] when the device has a screen lock; on the first save the assistant
     * then introduces itself.
     */
    fun saveSetup(d: Draft, what: String = t(R.string.mainactivity_save_the_assistant_s_setup)) {
        if (d.owner.isBlank()) return toast(t(R.string.mainactivity_first_tell_me_your_name))
        fun apply() {
            val first = !Guard.setUp
            Policy.owner = d.owner.trim()
            Inbox.apps = d.apps
            Inbox.groups = d.groupChats
            Policy.groups = Policy.groupsOf(d.family, d.work)
            Guard.voice = d.voice
            Guard.setUp = true
            Guard.save()
            thread { AssistantService.writeIdentity(this) }
            Ui.draft = null
            Ui.refresh()
            if (first) introduce()
        }
        // The first setup has nothing to protect yet; later, a change that widens what the assistant does (more
        // apps, group chats, people moved into a group) needs the credential, and without a screen lock is refused.
        if (!Guard.setUp || !widens(d)) apply() else confirmOwner(what) { ok -> if (ok) apply() }
    }

    private fun widens(d: Draft) =
        Policy.widens(Inbox.apps, d.apps, Inbox.groups, d.groupChats, Policy.groups, Policy.groupsOf(d.family, d.work))

    /**
     * Adds the introduction (the assistant's name and permissions, from the rules in force) to the chat; then,
     * if no form of address is set, sends the agent a hidden prompt to greet the owner and ask how to address them.
     */
    private fun introduce() {
        val text = introduction(Policy.owner, Guard.policy.rules, Policy.groups, Inbox.apps.map(::label), Guard.undoSeconds, Inbox.groups, missing())
        add(Line(Line.Who.ASSISTANT, text))
        if (Policy.address == null) thread {
            while (!Ui.ready) Thread.sleep(300)
            runOnUiThread {
                prompt(
                    "(${Policy.owner} just finished setting you up. In one or two warm, natural sentences in ${Language.current.english}, say you are glad " +
                        "to start, and ask how they would like you to refer to them, as you would in a real conversation: not as a " +
                        "list of options. Save their answer with set_address when they reply.)",
                )
            }
        }
    }

    /**
     * Collects context for the greeting: followed topics, today's next event and the number of reminders left today.
     * Only owner-authored data is used; event titles are excluded because any invitation sender can set them.
     */
    private fun dayFacts(now: java.time.LocalDateTime): Phrases.Day = Phrases.Day(
        topics = runCatching { PhoneNews(this).topics().map { it.query } }.getOrDefault(emptyList()),
        next = runCatching { PhoneWidgets(this).agenda(1)?.firstOrNull()?.takeIf { it.third == PhoneWidgets.today() }?.first }.getOrNull(),
        reminders = runCatching { PhoneReminders(this).all().count { java.time.Instant.ofEpochMilli(it.at).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == now.toLocalDate() && it.at > System.currentTimeMillis() } }.getOrDefault(0),
    )

    /** Sends the `owner` agent a message hidden from the chat; the answer is shown and spoken. */
    private fun prompt(text: String) {
        if (OwnerChat.socket == null) return
        spoken = true
        Ui.thinking = true
        OwnerChat.send(Phrases.fromApp(text))
    }

    /** Lists what the setup still lacks, phrased for the introduction. */
    private fun missing() = buildList {
        if (!permitted()) add(t(R.string.mainactivity_the_permissions_contacts_calendar))
        if (!secure()) add(t(R.string.mainactivity_a_screen_lock_so_i_decline))
        if (Inbox.apps.isNotEmpty() && !listening()) add(t(R.string.mainactivity_notification_access_so_i_can_t))
        if (!unrestricted()) add(t(R.string.mainactivity_the_background_permission_so_android_may))
        if (!canCall()) add(t(R.string.mainactivity_the_full_screen_permission_so_i))
    }

    // Setup checks and the settings screens they open.

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun permitted() = PERMISSIONS.all(::granted)

    fun askPermissions() = permissions.launch(PERMISSIONS)

    fun secure() = getSystemService(KeyguardManager::class.java).isDeviceSecure

    fun listening() = getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(ComponentName(this, MessageListener::class.java))

    fun unrestricted() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    fun openSecurity() = openSettings(Settings.ACTION_SECURITY_SETTINGS)

    /** Whether a high-level reminder may use a full-screen intent (user-granted from Android 14; granted before). */
    fun canCall() = Build.VERSION.SDK_INT < 34 || getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun openFullScreen() = openSettings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:$packageName")

    fun openBattery() = openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName")

    fun openListener() = openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    /** A missing permission: the message shown or spoken, and the action that opens where to grant it. */
    class Missing(val says: String, val fix: () -> Unit)

    /**
     * Whether restricted settings are still blocked. From Android 13 a sideloaded app cannot be granted notification
     * access or "display over other apps" until the user allows restricted settings (app details page, overflow menu).
     */
    private fun restricted() = Build.VERSION.SDK_INT >= 33 && runCatching {
        getSystemService(android.app.AppOpsManager::class.java)
            .unsafeCheckOpNoThrow("android:access_restricted_settings", applicationInfo.uid, packageName) != android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun appInfo() = openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName")

    /** Returns the missing permissions, most important first, each with a localized message (the home screen shows one at a time). */
    fun lacking(): List<Missing> = buildList {
        val unlock = t(R.string.mainactivity_first_in_ksandra_s_info_menu)
        if (!Keys.modelReady) add(Missing(t(if (Offline.wanted && Offline.ready) R.string.mainactivity_i_m_in_local_mode else R.string.mainactivity_i_have_no_model_to_think)) { Ui.place = Place.CONNECTORS; Ui.connector = Connectors.SERVICES })
        if (Inbox.apps.isNotEmpty() && !listening()) add(Missing(
            t(R.string.mainactivity_i_can_t_read_your_messages) +
                (if (restricted()) unlock else t(R.string.mainactivity_turn_it_on_for_ksandra)),
        ) { if (restricted()) appInfo() else openListener() })
        if (!granted(Manifest.permission.RECORD_AUDIO)) add(Missing(t(R.string.mainactivity_i_can_t_hear_you_i)) { askPermissions() })
        if (!permitted()) add(Missing(t(R.string.mainactivity_i_m_missing_basic_permissions_contacts)) { askPermissions() })
        if (!canCall()) add(Missing(t(R.string.mainactivity_i_couldn_t_call_you_full)) { openFullScreen() })
        if (!unrestricted()) add(Missing(t(R.string.mainactivity_android_may_close_me_in_the)) { openBattery() })
        if (Guard.bubble && !canFloat()) add(Missing(
            t(R.string.mainactivity_i_can_t_float_over_your) + (if (restricted()) unlock else t(R.string.mainactivity_let_me_display_over_other_apps)),
        ) { if (restricted()) appInfo() else setBubble(true) })
    }

    private fun openSettings(action: String, data: String? = null) {
        runCatching { startActivity(Intent(action).apply { if (data != null) setData(android.net.Uri.parse(data)) }) }
            .onFailure { toast(t(R.string.mainactivity_could_not_open_the_settings_x, it.message)) }
    }

    fun label(app: String) =
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(app, 0)).toString() }.getOrDefault(app)

    private fun installed(app: String) = runCatching { packageManager.getApplicationInfo(app, 0) }.isSuccess

    /** Returns the messaging apps the setup offers: supported and installed ones plus those already picked (and `fakechat` in debug builds). */
    fun messagingApps(picked: Set<String>) =
        (Inbox.DEFAULT_APPS + picked + (if (BuildConfig.DEBUG) setOf(FAKECHAT) else emptySet())).filter(::installed).sortedBy(::label)

    /** Returns the names of contacts with a phone number; empty without the contacts permission. */
    fun contactNames(): List<String> {
        if (!granted(Manifest.permission.READ_CONTACTS)) return emptyList()
        return contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
            "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1", null, ContactsContract.Contacts.DISPLAY_NAME,
        )?.use { c -> buildList { while (c.moveToNext()) c.getString(0)?.let(::add) } }.orEmpty().distinct()
    }

    companion object {
        /** Brings the app to the front if it is not visible, e.g. when a tool opens one of its screens. */
        fun bringToFront(context: android.content.Context) {
            if (Floating.appVisible) return
            runCatching { context.startActivity(sealed(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))) }
        }

        /** Shows an approval prompt while the app is open. */
        @Volatile var prompt: ((Approvals.Pending) -> Unit)? = null

        private const val SEAL = "seal"

        /** A random key generated per process; only the app's own intents (notifications, the floating mark) carry it. */
        private val seal = Loopback.newKey()

        /** Returns [intent] marked with the process seal so that [intents] trusts it. */
        fun sealed(intent: Intent): Intent = intent.putExtra(SEAL, seal)

        private fun trusted(intent: Intent) =
            intent.getStringExtra(SEAL)?.let { java.security.MessageDigest.isEqual(it.toByteArray(), seal.toByteArray()) } == true

        /** Set while the app is visible: shows a notice from [AssistantService.tell] in the chat instead of a notification. */
        @Volatile var note: ((String) -> Unit)? = null

        /** Runtime permissions requested at setup; POST_NOTIFICATIONS only from Android 13. */
        private val PERMISSIONS = listOfNotNull(
            Manifest.permission.POST_NOTIFICATIONS.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU },
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.RECORD_AUDIO,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE,
        ).toTypedArray()


        /** The prompt spoken when the owner opens the menu. */
        val MENU_PROMPT get() = t(R.string.mainactivity_sure_you_can_see_your_activity)

        /** Package of the debug-only `fakechat` module, which posts messages as a messaging app would. */
        const val FAKECHAT = "org.rustlanglatam.zeroclaw.fakechat"

    }
}
