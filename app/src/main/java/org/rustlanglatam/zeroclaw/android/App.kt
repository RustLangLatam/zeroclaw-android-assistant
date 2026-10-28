package org.rustlanglatam.zeroclaw.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Root composable: shows the setup flow until it completes, then the main screens, with approval, menu, card and connector sheets on top. */
@Composable
fun App(ui: MainActivity) {
    // One-second tick for countdowns and to re-read the policy state; also polls an offline model download.
    LaunchedEffect(Unit) { while (true) { delay(1000); Ui.refresh(); if (Offline.progress != null) Offline.poll(ui); Ink.hour = java.time.LocalTime.now().hour } }
    // Keeps the screen on while listening, processing or speaking; otherwise the display can time out mid-answer.
    val view = androidx.compose.ui.platform.LocalView.current
    val busy = Ui.listening || Ui.thinking || Ui.speaking
    androidx.compose.runtime.DisposableEffect(busy) { view.keepScreenOn = busy; onDispose { view.keepScreenOn = false } }
    Surface(Modifier.fillMaxSize(), color = Ink.void, contentColor = Ink.mist) {
        val draft = Ui.draft
        AnimatedContent(draft != null, Modifier.fillMaxSize(), transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(220)) }, label = "root") { setup ->
            if (setup && draft != null) Setup(ui, draft) else Main(ui)
        }
        if (Ui.menu && draft == null) MenuSheet(ui)
        Ui.pending?.let { ApprovalSheet(ui, it) }
        if (draft == null && Ui.pending == null) Cards.shown?.let { CardSheet(ui, it) }
        // The connector sheet is deferred until any open card (such as the provider guide) is closed.
        if (draft == null && Ui.pending == null && Cards.shown == null) Ui.connector?.let { ConnectorSheet(ui, it) }
    }
}

/** Returns the assistant's current state as rendered by the presence mark. */
fun presence(): Presence {
    Ui.tick
    return when {
        // During the splash transition the mark is drawn at rest so it matches the splash icon.
        Ui.splashing -> Presence.RESTING
        // Forced state for demonstrating each presence state (debug builds only).
        Ui.pose != null -> Ui.pose!!
        Guard.policy.stopped -> Presence.PAUSED
        Ui.listening -> Presence.LISTENING
        Ui.speaking -> Presence.SPEAKING
        Ui.thinking -> Presence.PROCESSING
        !Ui.ready -> Presence.OFFLINE
        else -> Presence.RESTING
    }
}

fun presenceLabel(state: Presence) = when (state) {
    Presence.RESTING -> t(R.string.app_available)
    Presence.LISTENING -> t(R.string.app_listening)
    Presence.PROCESSING -> t(R.string.app_thinking)
    Presence.SPEAKING -> t(R.string.app_speaking)
    Presence.PAUSED -> t(R.string.app_paused)
    Presence.OFFLINE -> t(R.string.app_connecting)
}

/** Identifiers of the top-level screens. [HOME] is the default; the others open on request. */
object Place {
    const val HOME = 0
    const val TALK = 1
    const val ACTIVITY = 2
    const val CONTROL = 3
    const val RULES = 4
    const val MEMORY = 5
    const val PHOTOS = 6
    const val FILES = 7
    const val NEWS = 8
    const val CONNECTORS = 9

    /** Screens showing contacts' messages, memory, rules or accounts; excluded from screenshots and the recent apps view. */
    val PRIVATE = setOf(TALK, ACTIVITY, CONTROL, RULES, MEMORY, CONNECTORS)

    /** Returns whether the current screen is private: a [PRIVATE] place, an open approval, or a connector sheet. */
    fun private(place: Int, approving: Boolean, connector: String?) = place in PRIVATE || approving || connector != null
}

/**
 * Maps the section keys accepted by the `open_section` tool to app navigation: a top-level place, a part of the
 * settings screen (scrolled to or opened as a sheet), or a connectors sheet. Has no side effects beyond the Compose
 * state it sets.
 */
object Sections {
    private val places = mapOf(
        "home" to Place.HOME, "interactions" to Place.TALK, "activity" to Place.ACTIVITY, "settings" to Place.CONTROL,
        "rules" to Place.RULES, "memory" to Place.MEMORY, "photos" to Place.PHOTOS, "files" to Place.FILES,
        "news" to Place.NEWS, "connectors" to Place.CONNECTORS, "email" to Place.CONNECTORS,
    )

    /** Settings sections that are scrolled to ([ANCHORS]) or opened as a sheet ([SHEETS]). */
    val ANCHORS = listOf("presence", "color", "people", "messages", "voice", "offline", "cost", "privacy")
    val SHEETS = listOf("name", "family", "work", "apps")

    val keys get() = places.keys + ANCHORS + SHEETS + Connectors.GROUP_KEYS.keys + listOf("models", "integrations", "add_email", "phone_permissions")

    /** Navigates to the section named by [key]; returns `false` if the key is unknown. */
    fun open(key: String): Boolean {
        val k = Commands.normalize(key).replace(' ', '_')
        Ui.menu = false
        when {
            k in places -> Ui.place = places.getValue(k)
            k in ANCHORS || k in SHEETS -> { Ui.place = Place.CONTROL; Ui.section = k }
            k == "themes" || k == "theme" || k == "palette" -> { Ui.place = Place.CONTROL; Ui.section = "color" }
            k == "add_email" -> { Ui.place = Place.CONNECTORS; Ui.connector = Connectors.PICK }
            k == "services" || k == "models" -> { Ui.place = Place.CONNECTORS; Ui.connector = Connectors.SERVICES }
            k == "integrations" -> { Ui.place = Place.CONNECTORS; Ui.connector = Connectors.APPS }
            k in Connectors.GROUP_KEYS -> { Ui.place = Place.CONNECTORS; Ui.connector = Connectors.GROUP + k }
            k == "phone_permissions" -> Ui.draft = Draft.current().apply { step = 1 }
            else -> return false
        }
        return true
    }
}

@Composable
private fun Main(ui: MainActivity) {
    val place = Ui.place
    BackHandler(place != Place.HOME) { Ui.place = if (place == Place.RULES || place == Place.CONNECTORS) Place.CONTROL else Place.HOME }
    Atmosphere(glow = if (place == Place.HOME) 1f else 0.5f) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        AnimatedContent(
            place, Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = { (fadeIn(tween(320)) + scaleIn(tween(320), initialScale = 0.98f)) togetherWith fadeOut(tween(120)) },
            label = "place",
        ) {
            val home = { Ui.place = Place.HOME }
            when (it) {
                Place.TALK -> Conversation(ui, home)
                Place.ACTIVITY -> ActivityPlace(home)
                Place.CONTROL -> Control(ui, home)
                Place.RULES -> Rules(ui) { Ui.place = Place.CONTROL }
                Place.MEMORY -> MemoryPlace(ui, home)
                Place.PHOTOS -> PhotosPlace(home)
                Place.FILES -> FilesPlace(home)
                Place.NEWS -> NewsPlace(ui, home)
                Place.CONNECTORS -> ConnectorsPlace(ui) { Ui.place = Place.CONTROL }
                else -> Home(ui)
            }
        }
        }
    }
}


// Components.

val Pill = RoundedCornerShape(50)

/** Primary action button: a glowing pill. */
@Composable
fun GlowButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    val tap = haptic()
    val light by animateFloatAsState(if (enabled) 1f else 0f, tween(220), label = "light")
    val ink = if (enabled) Ink.onLight else Ink.slate
    Row(
        modifier.height(56.dp)
            .shadow((20 * light).dp, Pill, ambientColor = Ink.rose, spotColor = Ink.rose)
            .glass(Pill)
            .background(Glow, alpha = light)
            .clickable(enabled) { tap(); onClick() }
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp), tint = ink); Spacer(Modifier.width(10.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = ink)
    }
}

/** Secondary action button: a translucent pill. */
@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    val tap = haptic()
    Row(
        modifier.height(56.dp).glass(Pill).clickable { tap(); onClick() }.padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp), tint = Ink.mist); Spacer(Modifier.width(10.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = Ink.mist)
    }
}

/** Round translucent icon button. */
@Composable
fun GlassIcon(icon: ImageVector, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tap = haptic()
    Box(modifier.size(44.dp).glass(CircleShape).clickable { tap(); onClick() }, contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(20.dp), tint = Ink.mist)
    }
}

/** Small uppercase label shown above a title or section. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Ink.slate) =
    Text(text.uppercase(), modifier, style = MaterialTheme.typography.labelSmall, color = color)

/** Small glowing dot of [color]. */
@Composable
fun Light(color: Color, size: Dp = 8.dp) =
    Box(Modifier.size(size).shadow(8.dp, CircleShape, ambientColor = color, spotColor = color).clip(CircleShape).background(color))

/** Screen header with a back button, an [Eyebrow] label and optional trailing content. */
@Composable
fun Header(eyebrow: String, onBack: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassIcon(Icons.AutoMirrored.Rounded.ArrowBack, t(R.string.app_back), onClick = onBack)
        Spacer(Modifier.width(14.dp))
        // While the assistant speaks, the label is replaced by the voice waveform.
        Box(Modifier.weight(1f).height(32.dp), contentAlignment = Alignment.CenterStart) {
            androidx.compose.animation.Crossfade(Ui.speaking, label = "title") { speaking ->
                if (speaking) VoiceLine(Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { Eyebrow(eyebrow) }
            }
        }
        trailing()
    }
}

/** Translucent card that holds [Item] rows. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().glass(RoundedCornerShape(Dimens.card)), content = content)
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) = Eyebrow(text, modifier.padding(top = 30.dp, bottom = 12.dp, start = 6.dp))

/** A [Panel] row with a title, optional detail text and optional trailing control. */
@Composable
fun Item(title: String, detail: String? = null, divider: Boolean = true, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val tap = haptic()
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).let { if (onClick != null) it.clickable { tap(); onClick() } else it }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (detail != null) { Spacer(Modifier.height(2.dp)); Text(detail, style = MaterialTheme.typography.bodyMedium, color = Ink.slate) }
            }
            if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 20.dp), color = Ink.line)
    }
}

@Composable
fun Chevron() = Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Ink.slate)

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) = Switch(
    on, onChange,
    colors = SwitchDefaults.colors(
        checkedTrackColor = Ink.warmth, checkedThumbColor = Ink.onLight, checkedBorderColor = Ink.warmth,
        uncheckedTrackColor = Ink.line, uncheckedThumbColor = Ink.slate, uncheckedBorderColor = Ink.slate.copy(alpha = 0.4f),
    ),
)

/** Completion indicator: a filled check when [done], an empty ring otherwise. */
@Composable
fun Mark(done: Boolean) {
    if (done) {
        Box(Modifier.size(28.dp).shadow(10.dp, CircleShape, ambientColor = Ink.rose, spotColor = Ink.rose).clip(CircleShape).background(Glow), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, null, Modifier.size(17.dp), tint = Ink.onLight)
        }
    } else {
        Box(Modifier.size(28.dp).glass(CircleShape))
    }
}

/** Single-line text field with an optional microphone button for voice input. */
@Composable
fun Field(
    value: String, placeholder: String, modifier: Modifier = Modifier, onDone: () -> Unit = {}, onMic: (() -> Unit)? = null,
    keyboard: androidx.compose.ui.text.input.KeyboardType? = null, hidden: Boolean = false, alert: Boolean = false, onChange: (String) -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 58.dp).glass(RoundedCornerShape(Dimens.control))
            .padding(start = 20.dp, end = if (onMic != null) 6.dp else 20.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = if (alert) Ink.alert else Ink.slate)
            FieldText(value, onDone, keyboard, hidden, onChange)
        }
        if (onMic != null) LightDot(Glyphs.mic, t(R.string.app_say_it), 44.dp, onClick = onMic)
    }
}

@Composable
private fun FieldText(value: String, onDone: () -> Unit, keyboard: androidx.compose.ui.text.input.KeyboardType?, hidden: Boolean, onChange: (String) -> Unit) {
    run {
        BasicTextField(
            value, onChange, Modifier.fillMaxWidth(), singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.mist),
            cursorBrush = SolidColor(Ink.warmth),
            // With an explicit keyboard type (email, password, URI), input is taken as typed: no capitalization or autocorrect.
            keyboardOptions = if (keyboard == null) KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done)
                else KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = keyboard, imeAction = ImeAction.Done),
            visualTransformation = if (hidden) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardActions = KeyboardActions(onDone = { onDone() }),
        )
    }
}

/** Round glowing icon button, styled for voice input when [voice] is true and for sending otherwise. */
@Composable
fun LightDot(icon: ImageVector, description: String, size: Dp = 46.dp, voice: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val tap = haptic()
    val color = if (voice) Ink.signal else Ink.rose
    Box(
        Modifier.size(size).shadow(if (enabled) 14.dp else 0.dp, CircleShape, ambientColor = color, spotColor = color).clip(CircleShape)
            .background(
                when {
                    !enabled -> SolidColor(Ink.line)
                    voice -> Brush.verticalGradient(listOf(Ink.ice, Ink.signal))
                    else -> Glow
                },
            )
            .clickable(enabled) { tap(); onClick() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(20.dp), tint = if (enabled) Ink.onLight else Ink.slate) }
}

/** Fully expanded modal bottom sheet with a dimmed scrim. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // Status bar padding keeps a tall sheet from extending behind the status bar.
    ModalBottomSheet(
        onDismiss, Modifier.statusBarsPadding(), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.sheet, contentColor = Ink.mist, scrimColor = Color.Black.copy(alpha = if (Ink.palette.light) 0.25f else 0.6f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(40.dp, 4.dp).clip(Pill).background(Ink.slate.copy(alpha = 0.4f))) },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.screen).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), content = content)
    }
}

/** Multi-select sheet over [options] (key to label); [onSave] receives the selected keys. */
@Composable
fun PickSheet(
    title: String, detail: String, options: List<Pair<String, String>>, picked: Set<String>,
    empty: String, onDismiss: () -> Unit, extra: (@Composable () -> Unit)? = null, onSave: (Set<String>) -> Unit,
) {
    var chosen by remember { mutableStateOf(picked) }
    val tap = haptic()
    Sheet(onDismiss) {
        Text(title, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = Ink.slate)
        Spacer(Modifier.height(22.dp))
        if (options.isEmpty()) Text(empty, style = MaterialTheme.typography.bodyLarge, color = Ink.slate)
        options.forEach { (key, label) ->
            val on = key in chosen
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).glass(RoundedCornerShape(Dimens.card), lit = if (on) 1.8f else 0.7f)
                    .clickable { tap(); chosen = if (on) chosen - key else chosen + key }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(38.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                    Text(label.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = Ink.warmth)
                }
                Spacer(Modifier.width(14.dp))
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Mark(on)
            }
        }
        extra?.invoke()
        Spacer(Modifier.height(24.dp))
        GlowButton(t(R.string.app_save), Modifier.fillMaxWidth()) { onSave(chosen) }
    }
}

/** Shows a pending approval: the action, its target and the text that would be sent. */
@Composable
private fun ApprovalSheet(ui: MainActivity, pending: Approvals.Pending) {
    val (title, text) = pending.act.title to pending.act.detail
    // If the approval ends while shown (timed out or answered from the notification), the content fades out
    // with the [Evanesce] animation, quote background first, before the sheet closes.
    val over = remember(Ui.tick) { Guard.approvals.find(pending.id) == null }
    val gone = remember { Animatable(0f) }
    LaunchedEffect(over) {
        if (!over) return@LaunchedEffect
        gone.animateTo(1f, tween(Evanesce.TOTAL_MS, easing = LinearEasing))
        if (Ui.pending == pending) Ui.pending = null
    }
    val still = stillness()
    val words = Modifier.dissolve(gone.value, still).let { if (over) it.clearAndSetSemantics {} else it }
    Sheet({ if (!over) ui.answer(pending, false) }) { Column(words) {
        // While the assistant reads the request aloud, the label is replaced by the voice waveform.
        Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.CenterStart) {
            androidx.compose.animation.Crossfade(Ui.speaking, label = "asking") { speaking ->
                if (speaking) VoiceLine(Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { Eyebrow(t(R.string.app_i_need_your_approval), color = Ink.warmth) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.displaySmall)
        if (text != null) {
            Spacer(Modifier.height(20.dp))
            Box {
                Box(Modifier.matchParentSize().evanesce(gone.value).glass(RoundedCornerShape(Dimens.card)))
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.padding(vertical = 18.dp).width(3.dp).heightIn(min = 40.dp).clip(Pill).background(Glow))
                    Text("“$text”", Modifier.padding(horizontal = 18.dp, vertical = 16.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (pending.strong && ui.fingerprint()) {
            Spacer(Modifier.height(14.dp))
            Text(t(R.string.app_i_ll_ask_for_your_fingerprint), style = MaterialTheme.typography.bodyMedium, color = Ink.slate)
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GhostButton(t(R.string.app_decline), Modifier.weight(1f)) { if (!over) ui.answer(pending, false) }
            GlowButton(t(R.string.app_approve), Modifier.weight(1f)) { if (!over) ui.answer(pending, true) }
        }
    } }
}

/** Contextual menu sheet, shown only on request by voice or text command; closes on a choice or a close command. */
@Composable
private fun MenuSheet(ui: MainActivity) {
    val state = presence()
    val stopped = state == Presence.PAUSED
    fun go(place: Int) { Ui.menu = false; Ui.place = place }
    Sheet({ Ui.menu = false }) {
        Row(verticalAlignment = Alignment.Top) {
            PresenceSymbol(state, 52.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Eyebrow(Policy.ASSISTANT)
                Spacer(Modifier.height(6.dp))
                Text(MainActivity.MENU_PROMPT, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(20.dp))
        Option(Glyphs.chat, t(R.string.app_interactions), t(R.string.app_what_you_said_typed_or_touched)) { go(Place.TALK) }
        Option(Glyphs.pulse, t(R.string.app_activity), t(R.string.app_what_people_wrote_to_you_and)) { go(Place.ACTIVITY) }
        Option(Icons.Rounded.Settings, t(R.string.app_settings), t(R.string.app_people_apps_voice_and_privacy)) { go(Place.CONTROL) }
        Option(Glyphs.news, t(R.string.app_news), t(R.string.app_what_i_follow_for_you_in)) { go(Place.NEWS) }
        Option(Glyphs.folder, t(R.string.app_files), t(R.string.app_the_documents_i_found_for_you)) { go(Place.FILES) }
        Option(Glyphs.photo, t(R.string.app_photos), t(R.string.app_the_last_ones_i_found_for)) { go(Place.PHOTOS) }
        Option(Glyphs.spark, t(R.string.app_memory), t(R.string.app_what_i_know_about_you_you)) { go(Place.MEMORY) }
        Option(Glyphs.shield, t(R.string.app_what_i_can_do), t(R.string.app_my_rules_in_plain_words)) { go(Place.RULES) }
        Option(Glyphs.more, if (Guard.appMode) t(R.string.app_assistant_mode) else t(R.string.app_app_mode), if (Guard.appMode) t(R.string.app_just_me_on_screen_the_rest) else t(R.string.app_buttons_and_a_menu_always_in)) {
            ui.setAppMode(!Guard.appMode)
        }
        Option(if (stopped) Icons.Rounded.PlayArrow else Glyphs.pause, if (stopped) t(R.string.app_resume) else t(R.string.app_pause), if (stopped) t(R.string.app_i_m_available_again) else t(R.string.app_i_do_nothing_until_you_resume)) {
            Ui.menu = false; ui.setStopped(!stopped)
        }
        Spacer(Modifier.height(14.dp))
        Text(if (Guard.appMode) t(R.string.app_tap_outside_to_close_or_say) else t(R.string.app_say_close_menu_to_leave), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = Ink.slate, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Option(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    val tap = haptic()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).glass(RoundedCornerShape(Dimens.card), lit = 0.8f).clickable { tap(); onClick() }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).glass(CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(19.dp), tint = Ink.warmth) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Ink.slate)
        }
        Chevron()
    }
}

// Setup flow: the owner's name, then the required permissions, then contact groups and messaging apps.

@Composable
private fun Setup(ui: MainActivity, d: Draft) {
    val first = !Guard.setUp
    val home = if (first) 0 else 1
    BackHandler(d.step > 0 || !first) { if (d.step > home) d.step-- else Ui.draft = null }
    Atmosphere(glow = if (d.step == 0) 1f else 0.6f) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = Dimens.screen)) {
            Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    d.step > home -> GlassIcon(Icons.AutoMirrored.Rounded.ArrowBack, t(R.string.app_back)) { d.step-- }
                    !first -> GlassIcon(Icons.Rounded.Close, t(R.string.app_close)) { Ui.draft = null }
                    else -> Spacer(Modifier.size(44.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("0${d.step + 1} / 03", style = Technical, color = Ink.slate)
            }
            AnimatedContent(d.step, Modifier.weight(1f), transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(120)) }, label = "step") { step ->
                when (step) {
                    0 -> SetupName(ui, d)
                    1 -> SetupNeeds(ui, d)
                    else -> SetupPeople(ui, d, first)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupName(ui: MainActivity, d: Draft) {
    // Speaks the greeting as soon as the first step is shown.
    LaunchedEffect(Language.current) { ui.say(t(R.string.app_hi_i_m_x_i_live, Policy.ASSISTANT)) }
    fun next() { ui.say(t(R.string.app_nice_to_meet_you_x_to, d.owner.trim())); d.step = 1 }
    // Shrinks the mark while the keyboard is visible so the field stays on screen.
    val typing = WindowInsets.isImeVisible
    val mark by animateDpAsState(if (typing) 88.dp else 168.dp, tween(320), label = "mark")
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(0.7f))
        PresenceSymbol(setupPresence(), mark, Modifier.landing().onGloballyPositioned { Ui.markBounds = it.boundsInWindow() })
        Spacer(Modifier.height(20.dp))
        Text(Policy.ASSISTANT.lowercase(), Modifier.rise(150), style = Wordmark)
        Spacer(Modifier.height(8.dp))
        Eyebrow(t(R.string.app_a_presence_that_understands_you), Modifier.rise(250))
        // Language picker on the first step; later the language can be changed by voice command.
        Spacer(Modifier.height(14.dp))
        Row(Modifier.rise(300), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Lang.entries.forEach { lang ->
                val on = Language.current == lang
                Text(
                    lang.label,
                    Modifier.let { if (on) it.clip(Pill).background(Glow) else it.glass(Pill) }.clickable { ui.setLanguage(lang.tag) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge, color = if (on) Ink.onLight else Ink.mist,
                )
            }
        }
        if (!typing) {
            Spacer(Modifier.height(36.dp))
            Text(
                t(R.string.app_i_live_on_this_phone_and),
                Modifier.rise(350), style = MaterialTheme.typography.bodyLarge, color = Ink.slate, textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.weight(1f))
        Field(
            d.owner, t(R.string.app_what_should_i_call_you), Modifier.rise(450), onDone = { if (d.owner.isNotBlank()) next() },
            onMic = { ui.listenFor { heard -> d.owner = Commands.nameFrom(heard) } },
        ) { d.owner = it }
        Spacer(Modifier.height(12.dp))
        GlowButton(t(R.string.app_continue), Modifier.fillMaxWidth().rise(450), enabled = d.owner.isNotBlank()) { next() }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StepTitle(eyebrow: String, title: String, detail: String) {
    Spacer(Modifier.height(8.dp))
    Eyebrow(eyebrow, Modifier.rise())
    Spacer(Modifier.height(10.dp))
    Text(title, Modifier.rise(60), style = MaterialTheme.typography.displayMedium)
    Spacer(Modifier.height(12.dp))
    Text(detail, Modifier.rise(120), style = MaterialTheme.typography.bodyLarge, color = Ink.slate)
    Spacer(Modifier.height(26.dp))
}

@Composable
private fun SetupNeeds(ui: MainActivity, d: Draft) {
    Ui.tick
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            StepTitle(t(R.string.app_permissions), t(R.string.app_what_i_need), t(R.string.app_each_permission_enables_something_specific_you))
            Panel(Modifier.rise(200)) {
                Need(t(R.string.app_notifications_contacts_calendar_and_photos), t(R.string.app_to_let_you_know_find_people), ui.permitted(), t(R.string.app_allow)) { ui.askPermissions() }
                Need(t(R.string.app_fingerprint), t(R.string.app_optional_with_a_fingerprint_enrolled_i), ui.fingerprint(), t(R.string.app_set_up)) { ui.openSecurity() }
                Need(t(R.string.app_stay_active), t(R.string.app_to_stay_attentive_even_with_the), ui.unrestricted(), t(R.string.app_allow)) { ui.openBattery() }
                // MIUI/HyperOS kills background apps without Autostart; Android exposes no API to check that setting.
                if (ui.xiaomi) Need(t(R.string.app_autostart_xiaomi), t(R.string.app_on_your_xiaomi_turn_on_autostart), false, t(R.string.app_open)) { ui.openAutostart() }
                // MIUI/HyperOS has its own lock-screen permission; without it the full-screen reminder call stays hidden behind the lock screen.
                if (ui.xiaomi) Need(t(R.string.app_call_you_on_the_lock_screen),
                    t(R.string.app_in_ksandra_s_other_permissions_turn), false, t(R.string.app_open)) { ui.openOverlay() }
                Need(t(R.string.app_call_you_when_something_matters), t(R.string.app_so_an_important_reminder_like_a), ui.canCall(), t(R.string.app_allow)) { ui.openFullScreen() }
                Need(t(R.string.app_search_your_files), t(R.string.app_optional_to_find_documents_other_apps), ui.allFiles(), t(R.string.app_allow)) { ui.openAllFiles() }
                Need(t(R.string.app_know_which_apps_you_use_most), t(R.string.app_optional_to_pick_your_favourite_app), ui.knowsUsage(), t(R.string.app_allow)) { ui.openUsage() }
                Need(t(R.string.app_always_at_hand), t(R.string.app_optional_a_small_presence_over_your), ui.canFloat(), t(R.string.app_allow)) { ui.openOverlay() }
                Need(t(R.string.app_read_your_notifications), t(R.string.app_optional_to_see_the_messages_people), ui.listening(), t(R.string.app_turn_on), divider = false) { ui.openListener() }
            }
        }
        GlowButton(t(R.string.app_continue), Modifier.fillMaxWidth().padding(vertical = 16.dp)) { d.step = 2 }
    }
}

@Composable
private fun Need(title: String, why: String, done: Boolean, action: String, divider: Boolean = true, onClick: () -> Unit) {
    Item(title, why, divider, onClick = if (done) null else onClick) {
        if (done) Mark(true) else Text(action, style = MaterialTheme.typography.labelLarge, color = Ink.warmth)
    }
}

/** Returns the presence state during setup: speaking, listening, or resting, since the gateway may not be running yet. */
private fun setupPresence() = when {
    Ui.speaking -> Presence.SPEAKING
    Ui.listening -> Presence.LISTENING
    else -> Presence.RESTING
}

@Composable
private fun SetupPeople(ui: MainActivity, d: Draft, first: Boolean) {
    LaunchedEffect(Unit) { if (first) ui.say(t(R.string.app_last_tell_me_who_i_talk)) }
    var picking by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            StepTitle(t(R.string.app_people), t(R.string.app_who_i_talk_to), t(R.string.app_decide_whom_i_answer_and_how))
            Panel(Modifier.rise(200)) {
                Item(t(R.string.app_apps_i_read), d.apps.map(ui::label).sorted().joinToString().ifEmpty { t(R.string.app_none) } + if (d.groupChats) t(R.string.app_and_their_groups) else "", onClick = { picking = "apps" }) { Chevron() }
                Item(t(R.string.app_family), d.family.joinToString().ifEmpty { t(R.string.app_nobody) } + t(R.string.app_i_answer_them_you_can_undo), onClick = { picking = "family" }) { Chevron() }
                Item(t(R.string.app_work), d.work.joinToString().ifEmpty { t(R.string.app_nobody) } + t(R.string.app_only_with_your_approval), onClick = { picking = "work" }) { Chevron() }
                Item(t(R.string.app_anyone_else), t(R.string.app_i_never_answer_them_i_leave), divider = false)
            }
            Spacer(Modifier.height(14.dp))
            Panel(Modifier.rise(280)) { Item(t(R.string.app_speak_aloud), t(R.string.app_i_read_my_answers_aloud_as), divider = false) { Toggle(d.voice) { d.voice = it } } }
        }
        GlowButton(if (first) t(R.string.app_start) else t(R.string.app_save), Modifier.fillMaxWidth().padding(vertical = 16.dp)) { ui.saveSetup(d) }
    }
    picking?.let { what -> DraftPicker(ui, d, what, onDone = { picking = null }) }
}

/** Shows the picker for [what] (`apps`, `family` or `work`), shared by the setup flow and the settings screen. */
@Composable
fun DraftPicker(ui: MainActivity, d: Draft, what: String, onDone: () -> Unit, onSaved: () -> Unit = onDone) {
    when (what) {
        "apps" -> {
            var groups by remember { mutableStateOf(d.groupChats) }
            PickSheet(
                t(R.string.app_apps_i_read), t(R.string.app_i_only_read_messages_from_these),
                ui.messagingApps(d.apps).map { it to ui.label(it) }, d.apps, t(R.string.app_no_compatible_messaging_apps_are_installed), onDone,
                extra = {
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t(R.string.app_group_chats_too), style = MaterialTheme.typography.bodyLarge)
                            Text(t(R.string.app_off_i_ignore_group_chats), style = MaterialTheme.typography.bodyMedium, color = Ink.slate)
                        }
                        Toggle(groups) { groups = it }
                    }
                },
            ) { d.apps = it; d.groupChats = groups; onSaved() }
        }
        else -> {
            val family = what == "family"
            val names = remember { ui.contactNames() }
            PickSheet(
                if (family) t(R.string.app_family) else t(R.string.app_work),
                if (family) t(R.string.app_i_answer_them_myself_and_you) else t(R.string.app_i_answer_them_only_when_you),
                names.map { it to it }, (if (family) d.family else d.work).toSet(),
                if (ui.permitted()) t(R.string.app_no_contacts_with_a_phone_number) else t(R.string.app_first_allow_access_to_your_contacts),
                onDone,
            ) { picked -> val sorted = names.filter { it in picked }; if (family) d.family = sorted else d.work = sorted; onSaved() }
        }
    }
}

/** Shows the current [Cards] card; its action buttons send their label as the owner's reply. */
@Composable
private fun CardSheet(ui: MainActivity, card: Cards.Card) {
    fun close() { Cards.shown = null }
    Sheet(::close) {
        Eyebrow(t(R.string.app_x_shows_you, Policy.ASSISTANT), color = Ink.warmth)
        Spacer(Modifier.height(8.dp))
        Text(card.title, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(18.dp))
        Cards.blocks(card.body).forEach { block ->
            // A block containing a link opens it on tap; the card stays open so another option can be chosen.
            val text = when (block) { is Cards.Block.Bullet -> block.text; is Cards.Block.Step -> block.text; is Cards.Block.Paragraph -> block.text; else -> "" }
            val url = Cards.link(text)
            val opens = if (url == null) Modifier else Modifier.clip(RoundedCornerShape(Dimens.card)).glass(RoundedCornerShape(Dimens.card))
                .clickable { runCatching { ui.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } }
                .padding(horizontal = 14.dp, vertical = 12.dp)
            when (block) {
                is Cards.Block.Heading -> Text(rich(block.text), Modifier.padding(top = 10.dp, bottom = 6.dp), style = MaterialTheme.typography.titleMedium)
                is Cards.Block.Paragraph -> Text(rich(block.text), Modifier.padding(bottom = 10.dp).then(opens), style = MaterialTheme.typography.bodyLarge)
                is Cards.Block.Bullet -> Row(Modifier.padding(bottom = if (url == null) 6.dp else 10.dp).fillMaxWidth().then(opens)) {
                    Text(if (url == null) "•" else "↗", Modifier.width(22.dp), color = Ink.warmth, style = MaterialTheme.typography.bodyLarge)
                    Text(rich(block.text), style = MaterialTheme.typography.bodyLarge)
                }
                is Cards.Block.Step -> Row(Modifier.padding(bottom = if (url == null) 8.dp else 10.dp).fillMaxWidth().then(opens)) {
                    Text("${block.n}", Modifier.width(26.dp), color = Ink.warmth, style = MaterialTheme.typography.titleMedium)
                    Text(rich(block.text), style = MaterialTheme.typography.bodyLarge)
                }
                is Cards.Block.Quote -> Text(
                    rich(block.text),
                    Modifier.fillMaxWidth().padding(bottom = 10.dp).glass(RoundedCornerShape(Dimens.card)).padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Cards.Block.Rule -> Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.dp).background(Ink.slate.copy(alpha = 0.3f)))
            }
        }
        Spacer(Modifier.height(18.dp))
        // Tapping an action sends its label as the owner's message.
        card.actions.forEachIndexed { i, label ->
            val go: () -> Unit = {
                close()
                // A card the app made runs its action in code; any other answers the agent with the label.
                val handler = Cards.handler(card.id, label)
                if (handler != null) kotlin.concurrent.thread { handler() } else ui.send(label, from = card.title)
            }
            if (i == 0) GlowButton(label, Modifier.fillMaxWidth(), onClick = go) else GhostButton(label, Modifier.fillMaxWidth(), onClick = go)
            Spacer(Modifier.height(10.dp))
        }
        GhostButton(t(R.string.app_close), Modifier.fillMaxWidth(), onClick = ::close)
    }
}

/** Builds an annotated string for a card line, rendering its **bold** spans. */
private fun rich(text: String) = androidx.compose.ui.text.buildAnnotatedString {
    Cards.spans(Cards.plain(text)).forEach { (piece, bold) ->
        if (bold) withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) { append(piece) } else append(piece)
    }
}

