package org.rustlanglatam.zeroclaw.android

import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.Icon
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.core.graphics.drawable.toBitmap
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Returns the label of the app [pkg], or `null` when it is not installed. */
@Composable
private fun appName(pkg: String): String? {
    val context = LocalContext.current
    return remember(pkg) { runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull() }
}

/** Draws the launcher icon of [pkg], clipped to a circle; draws nothing when it cannot be loaded. */
@Composable
fun AppIcon(pkg: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon = remember(pkg) {
        runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    if (icon != null) Image(icon, null, modifier.size(size).clip(CircleShape))
}

/** Draws a contact avatar: the name's initial, with the icon of the source messaging [app] as a badge. */
@Composable
private fun Avatar(name: String, app: String, size: Dp = 42.dp) {
    Box(Modifier.size(size + 4.dp)) {
        Box(Modifier.size(size).glass(CircleShape), contentAlignment = Alignment.Center) {
            // Phone numbers start with a non-letter ("+"), so they get a generic person icon.
            val initial = name.trim().firstOrNull()?.takeIf { it.isLetter() }
            if (initial != null) Text(initial.uppercase(), style = MaterialTheme.typography.titleMedium, color = Ink.warmth)
            else Icon(Icons.Rounded.Person, null, Modifier.size(size * 0.5f), tint = Ink.warmth)
        }
        if (app.isNotEmpty()) Box(
            Modifier.align(Alignment.BottomEnd).size(size * 0.46f).clip(CircleShape).background(Ink.void).padding(1.5.dp),
        ) { AppIcon(app, size * 0.46f - 3.dp) }
    }
}

/** Returns the seconds left before a queued item is sent, rounded up and at least 1 so the countdown never shows 0. */
private fun left(item: Outbox.Item) = ((Guard.undoSeconds * 1000L - (System.currentTimeMillis() - item.at) + 999) / 1000).coerceAtLeast(1)

/** Formats [at] as a relative time for the last hour, otherwise as `HH:mm`. */
private fun ago(at: Long): String {
    val seconds = (System.currentTimeMillis() - at) / 1000
    return when {
        seconds < 60 -> t(R.string.screens_a_moment_ago)
        seconds < 3600 -> t(R.string.screens_x_min_ago, seconds / 60)
        else -> DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
    }
}

/** Draws the undo countdown of [item] as a shrinking ring with the remaining seconds inside. */
@Composable
private fun Countdown(item: Outbox.Item, size: Int = 40) {
    Ui.tick
    val seconds = left(item)
    val fraction = seconds.toFloat() / Guard.undoSeconds.coerceAtLeast(1)
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 2.5.dp.toPx()
            drawCircle(Ink.line, (this.size.minDimension - w) / 2, style = Stroke(w))
            drawArc(Ink.wait, -90f, 360f * fraction, false, Offset(w / 2, w / 2), Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
        }
        Text("$seconds", style = Technical, color = Ink.mist)
    }
}

/** Draws a glass card for an item that needs the owner's attention, with an optional action label. */
@Composable
private fun Moment(
    title: String, detail: String?, action: String?, leading: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier, inner: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit,
) {
    val tap = haptic()
    // [modifier] applies to the card background and [inner] to its content, so the exit animation can
    // fade the background first and then the text.
    Box(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(Dimens.card)).clickable(enabled) { tap(); onClick() }) {
        Box(modifier.matchParentSize().glass(RoundedCornerShape(Dimens.card)))
        Row(inner.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) { leading(); Spacer(Modifier.width(14.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = Ink.slate, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (action != null) { Spacer(Modifier.width(10.dp)); Text(action, style = MaterialTheme.typography.labelLarge, color = Ink.warmth) }
        }
    }
}

// Home screen: the presence mark, the greeting, items needing attention, and the entry point for talking.

/** Draws a full-width widget: the greeting (replaced by the voice line while speaking in app mode) or the panorama line. */
@Composable
fun Wide(ui: MainActivity, widget: Widget, modifier: Modifier = Modifier) {
    when (widget) {
        Widget.GREETING -> Box(modifier.fillMaxWidth().height(52.dp).padding(horizontal = 2.dp), contentAlignment = Alignment.CenterStart) {
            val hour = java.time.LocalTime.now().hour
            // App mode shows no presence mark, so the greeting area shows the voice line while speaking.
            androidx.compose.animation.Crossfade(Guard.appMode && Ui.speaking, label = "greeting") { voice ->
                if (voice) VoiceLine(Modifier.fillMaxSize())
                // Auto-sized so the owner's name is not truncated on narrow screens.
                else androidx.compose.foundation.text.BasicText(
                    "${when (hour) { in 6..11 -> t(R.string.screens_good_morning); in 12..19 -> t(R.string.screens_good_afternoon); else -> t(R.string.screens_good_evening) }}, ${Policy.owner}.",
                    Modifier.rise(), style = MaterialTheme.typography.displaySmall.copy(color = MaterialTheme.colorScheme.onBackground), maxLines = 1,
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = MaterialTheme.typography.displaySmall.fontSize),
                )
            }
        }
        else -> Box(modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp)) {
            val attention = Ui.pending != null || Guard.outbox.waiting().isNotEmpty() || !ui.permitted() || (Inbox.apps.isNotEmpty() && !ui.listening())
            PanoramaLine(ui, panorama(ui, Guard.policy.stopped, attention), Modifier.rise(100))
        }
    }
}

/** Collects the current device state into the items the panorama line rotates through. */
@Composable
private fun panorama(ui: MainActivity, stopped: Boolean, attention: Boolean): List<Panorama.Item> {
    val now = System.currentTimeMillis()
    val mail = Mailbox.mails.lastOrNull { it.important && it.id !in Mailbox.opened && now - it.at < 12 * 3600_000L }
    return Panorama.items(
        Panorama.Now(
            stopped = stopped,
            offline = CatchUp.offline(ui),
            arranging = Ui.arranging,
            attention = attention,
            back = CatchUp.last?.takeIf { now - it.second < 6 * 3600_000L }?.first,
            unread = Inbox.unread().map { it.sender to it.text },
            importantMail = mail?.let { Triple(it.from.substringBefore(' '), it.subject, MailNotice.OPEN + it.id) },
            mails = Mailbox.mails.count { it.id !in Mailbox.opened },
            next = loaded(Ui.tick, null) { PhoneWidgets(ui).agenda(1)?.firstOrNull()?.let { it.first to it.second } },
            reminder = loaded(Ui.tick, null) {
                PhoneReminders(ui).all().filter { it.at in now..now + 12 * 3600_000L }.minByOrNull { it.at }
                    ?.let { java.time.Instant.ofEpochMilli(it.at).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0).toString() to it.text }
            },
            briefing = loaded(Ui.tick, null) {
                PhoneNews(ui).briefings().lastOrNull { now - it.at < 24 * 3600_000L }?.let { it.topic to it.text.substringBefore(". ").take(140) }
            },
            curious = Panorama.curious { Memory.depth(it) },
        ),
    )
}

/**
 * Rotates through [items] one line at a time: each slides in vertically, long lines scroll horizontally
 * (marquee), and the next follows after a delay. A tap opens the related screen or mail, sends the item's
 * question to the agent, or has the assistant speak the line.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PanoramaLine(ui: MainActivity, items: List<Panorama.Item>, modifier: Modifier = Modifier) {
    var turn by remember { mutableStateOf(0) }
    // The display time grows with text length so the marquee reaches the end before the next line.
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(5_000L + (items[turn % items.size].text.length - 40).coerceAtLeast(0) * 160L); turn++ } }
    val item = items[turn % items.size]
    androidx.compose.animation.AnimatedContent(
        item, modifier.fillMaxWidth(),
        transitionSpec = {
            (androidx.compose.animation.slideInVertically(tween(450)) { it / 2 } + fadeIn(tween(450))) togetherWith
                (androidx.compose.animation.slideOutVertically(tween(350)) { -it / 2 } + fadeOut(tween(350)))
        },
        label = "panorama",
    ) { shown ->
        Column(
            Modifier.fillMaxWidth().clickable {
                when {
                    shown.place != null -> Ui.place = shown.place
                    shown.kind == Panorama.Kind.CURIOUS -> ui.ask(shown.text)
                    shown.ask?.startsWith(MailNotice.OPEN) == true -> Mailbox.find(shown.ask.removePrefix(MailNotice.OPEN).toInt())?.let(ui::openMail)
                    shown.ask != null -> ui.send(shown.ask, byVoice = true, from = shown.kind.label)
                    else -> ui.remark(shown.text)
                }
            },
        ) {
            Text(
                shown.kind.label.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                color = if (shown.kind == Panorama.Kind.STATE) Ink.slate else Ink.warmth,
            )
            Text(
                shown.text, Modifier.basicMarquee(iterations = 1, initialDelayMillis = 1_500, velocity = 60.dp),
                style = MaterialTheme.typography.bodyLarge, color = Ink.slate, maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Home(ui: MainActivity) {
    val state = presence()
    val stopped = state == Presence.PAUSED
    val waiting = Guard.outbox.waiting()
    val pending = Ui.pending
    // Missing permissions, each with a description and an action that opens the relevant setting.
    val lacking = ui.lacking().map { it.says to it.fix }
    val attention = (if (pending != null) 1 else 0) + waiting.size + lacking.size
    val arranging = remember { Arranging() }
    var adding by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { arranging.origin = it.boundsInRoot().topLeft }) {
        Column(Modifier.fillMaxSize().padding(horizontal = Dimens.screen)) {
            // No status text here: the assistant's state is conveyed by the presence mark.
            Spacer(Modifier.height(22.dp))
            val hour = java.time.LocalTime.now().hour
            // The greeting and panorama are wide widgets placed in the widget rows. Assistant mode shows the presence
            // mark between the two rows; app mode stacks both rows and shows the icon [Board] below them.
            androidx.compose.animation.AnimatedContent(
                Guard.appMode, Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = { fadeIn(tween(380, 140)) togetherWith fadeOut(tween(220)) },
                label = "mode",
            ) { app ->
                if (app) Column(Modifier.fillMaxSize()) {
                    WidgetArea(ui, top = true, arranging)
                    Spacer(Modifier.height(8.dp))
                    WidgetArea(ui, top = false, arranging)
                    Board(ui, stopped, Modifier.weight(1f))
                } else Column(Modifier.fillMaxSize()) {
                    WidgetArea(ui, top = true, arranging, Modifier.rise(150))
                    // The presence mark keeps a fixed size, centered in the space left by the rows and the cards.
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // Tap to talk, long-press for the menu. There is no text field on this screen: voice
                            // through the presence mark is the only input.
                            Presence(ui, state, 220.dp, stopped)
                            Box(Modifier.height(48.dp), contentAlignment = Alignment.BottomCenter) {
                                if (!stopped && !Ui.arranging) TalkHint(Modifier.rise(250))
                            }
                        }
                    }
                    WidgetArea(ui, top = false, arranging, Modifier.rise(300))
                }
            }
            if (Ui.arranging) ArrangeBar { adding = true }
            // Current items (a pending approval, a queued reply, an important email, a missing permission), at most two.
            // Keyed so that an item that ends (reply sent, approval answered) animates out instead of disappearing.
            val moments = buildList<Pair<Any, @Composable (Boolean, Modifier, Modifier) -> Unit>> {
                if (pending != null) add("ask-${pending.id}" to { gone, m, inner -> Moment(pending.act.title, t(R.string.screens_waiting_for_your_approval), t(R.string.screens_decide), { Light(Ink.warmth) }, m, inner, !gone) { Ui.pending = null; Ui.pending = pending } })
                waiting.forEach { item ->
                    add("out-${item.id}" to { gone, m, inner -> Moment(item.act.title, item.act.detail?.let { "“$it”" }, t(R.string.screens_undo), { Countdown(item) }, m, inner, !gone) { Guard.undo(item.id); Ui.refresh() } })
                }
                Mailbox.mails.lastOrNull { it.important && it.id !in Mailbox.opened && System.currentTimeMillis() - it.at < 12 * 3600_000L }?.let { mail ->
                    add("mail-${mail.id}" to { gone, m, inner ->
                        Moment(t(R.string.screens_an_email_from_x_looks_important, mail.from.substringBefore(' ')), mail.subject, t(R.string.screens_read), { Avatar(mail.from, mail.app, 36.dp) }, m, inner, !gone) {
                            ui.openMail(mail)
                        }
                    })
                }
                lacking.take(1).forEach { (text, fix) -> add("lack-$text" to { gone, m, inner -> Moment(text, null, t(R.string.screens_fix_it), null, m, inner, !gone) { fix() } }) }
            }.take(2)
            if (!Ui.arranging) Column(Modifier.rise(300).padding(top = 10.dp)) { Evanescing(moments, { it.first }) { (_, show), gone, m, inner -> show(gone, m, inner) } }
            if (stopped) GlowButton(t(R.string.screens_resume), Modifier.fillMaxWidth().padding(top = 8.dp), icon = Icons.Rounded.PlayArrow) { ui.setStopped(false) }
            Spacer(Modifier.height(10.dp))
        }
        DraggedWidget(ui, arranging)
    }
    if (adding) AddWidgetSheet { adding = false }
}

/** An entry on the app-mode board; [mark] draws the presence mark instead of [icon]. */
private class Tile(val icon: ImageVector, val label: String, val mark: Boolean = false, val open: () -> Unit)

/**
 * Draws the app-mode board: a divider below the widgets, then every screen and action as an icon, four per
 * row, each scaling in with a staggered delay.
 */
@Composable
private fun Board(ui: MainActivity, stopped: Boolean, modifier: Modifier = Modifier) {
    val tiles = listOf(
        Tile(Glyphs.mic, t(R.string.screens_talk)) { if (!stopped && Ui.ready) ui.listen() },
        Tile(Glyphs.chat, t(R.string.screens_interactions)) { Ui.place = Place.TALK },
        Tile(Glyphs.pulse, t(R.string.screens_activity)) { Ui.place = Place.ACTIVITY },
        Tile(Glyphs.news, t(R.string.screens_news)) { Ui.place = Place.NEWS },
        Tile(Glyphs.spark, t(R.string.screens_memory)) { Ui.place = Place.MEMORY },
        Tile(Glyphs.photo, t(R.string.screens_photos)) { Ui.place = Place.PHOTOS },
        Tile(Glyphs.folder, t(R.string.screens_files)) { Ui.place = Place.FILES },
        Tile(Icons.Rounded.Settings, t(R.string.screens_settings)) { Ui.place = Place.CONTROL },
        Tile(Glyphs.shield, t(R.string.screens_what_i_can_do)) { Ui.place = Place.RULES },
        Tile(Icons.Rounded.Edit, t(R.string.screens_widgets)) { Ui.arranging = true },
        Tile(if (stopped) Icons.Rounded.PlayArrow else Glyphs.pause, if (stopped) t(R.string.screens_resume) else t(R.string.screens_pause)) { ui.setStopped(!stopped) },
        Tile(Glyphs.home, t(R.string.screens_assistant_mode), mark = true) { ui.setAppMode(false) },
    )
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier.padding(vertical = 16.dp).fillMaxWidth().height(1.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Ink.palette.ringB.copy(alpha = 0.45f), Color.Transparent))),
        )
        tiles.chunked(4).forEachIndexed { row, line ->
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                line.forEachIndexed { col, tile -> BoardIcon(tile, row * 4 + col, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun BoardIcon(tile: Tile, index: Int, modifier: Modifier = Modifier) {
    val tap = haptic()
    val arrive = remember { Animatable(0f) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60L + index * 45L); arrive.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 260f)) }
    val talking = tile.label == t(R.string.screens_talk) && Ui.listening
    val p = Ink.palette
    Column(
        modifier.graphicsLayer { alpha = arrive.value.coerceIn(0f, 1f); scaleX = 0.7f + 0.3f * arrive.value; scaleY = scaleX }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { tap(); tile.open() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Glass tile with a palette-gradient border, brighter while the talk tile is listening.
        Box(
            Modifier.size(58.dp).glass(RoundedCornerShape(18.dp))
                .border(1.dp, Brush.linearGradient(listOf(p.ringA, p.ringB, p.ringC).map { it.copy(alpha = if (talking) 0.95f else 0.38f) }), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // The tile that switches back to assistant mode shows the live presence mark.
            if (tile.mark) PresenceSymbol(presence(), 52.dp)
            else Icon(tile.icon, tile.label, Modifier.size(24.dp), tint = if (talking) Ink.signal else Ink.warmth)
        }
        Spacer(Modifier.height(6.dp))
        Text(tile.label, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp), color = Ink.slate, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Draws the presence mark: tap to talk, long-press for the menu; reflects the pressed state. */
@Composable
private fun Presence(ui: MainActivity, state: Presence, size: Dp, stopped: Boolean) {
    val touch = remember { MutableInteractionSource() }
    val pressed by touch.collectIsPressedAsState()
    // Long-press timeout raised to 800 ms: the default (400 ms) triggered the menu on ordinary taps.
    val base = androidx.compose.ui.platform.LocalViewConfiguration.current
    val patient = remember(base) {
        object : androidx.compose.ui.platform.ViewConfiguration by base { override val longPressTimeoutMillis = 800L }
    }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalViewConfiguration provides patient) { PresenceSymbol(
        state, size,
        Modifier.landing(200).onGloballyPositioned { Ui.markBounds = it.boundsInWindow() }.combinedClickable(
            touch, null,
            onLongClick = { Ui.menu = true },
            onClick = { if (Ui.arranging) Ui.arranging = false else if (!stopped && Ui.ready) ui.listen() },
        ),
        pressed = pressed,
    ) }
}

/** Draws the hint below the presence mark: the current state, or a prompt to tap and talk. */
@Composable
private fun TalkHint(modifier: Modifier = Modifier) {
    val breath by rememberInfiniteTransition(label = "hint").animateFloat(
        0.45f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "breath",
    )
    val (title, detail) = when {
        !Ui.ready -> t(R.string.screens_one_moment) to null
        Ui.listening -> t(R.string.screens_listening) to null
        Ui.thinking -> Offline.thinking() to null
        Ui.speaking -> "" to null
        // Offline: states whether answers come from the on-device model or are unavailable.
        Offline.offline -> t(R.string.screens_no_internet) to (if (Offline.wanted && Offline.ready) t(R.string.screens_i_answer_from_the_phone_more)
            else t(R.string.screens_i_m_limited_until_it_s))
        Guard.openMic -> t(R.string.screens_talk_to_me) to t(R.string.screens_open_microphone_tell_me_what_you)
        else -> t(R.string.screens_talk_to_me) to t(R.string.screens_tap_the_circle)
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink.mist)
            // The microphone icon is shown only while listening (including open-microphone mode); a crossed-out icon
            // next to the talk prompt was misread as the microphone being unavailable.
            if (title.isNotEmpty() && (Ui.listening || Guard.openMic)) {
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Icon(Glyphs.mic, t(R.string.screens_open_microphone), Modifier.size(16.dp), tint = Ink.signal)
            }
        }
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.alpha(if (Ui.listening) 1f else breath), verticalAlignment = Alignment.CenterVertically) {
                Light(Ink.warmth, 5.dp)
                Spacer(Modifier.width(8.dp))
                Eyebrow(detail)
            }
        }
    }
}


// Conversation screen: the owner's private chat history, by voice and text.

private val SUGGESTIONS = listOf(t(R.string.screens_what_do_i_have_today), t(R.string.screens_set_an_alarm_for_7_00), t(R.string.screens_do_i_have_ana_s_number))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Conversation(ui: MainActivity, onBack: () -> Unit) {
    val list = rememberLazyListState()
    val lines = Ui.chat
    LaunchedEffect(lines.size, Ui.thinking) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.size + if (Ui.thinking) 1 else 0) }
    Column(Modifier.fillMaxSize()) {
        // The hush button appears only while speaking; pausing the whole assistant is a separate command or menu entry.
        // The list holds more than chat turns: widget taps, tool calls and notes.
        Header(t(R.string.screens_interactions), onBack) {
            if (Ui.speaking) GlassIcon(Glyphs.pause, t(R.string.screens_hush)) { ui.hush() }
        }
        if (lines.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Dimens.screen),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text(t(R.string.screens_how_can_i_help), Modifier.rise(), style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text(t(R.string.screens_i_set_alarms_check_your_calendar), Modifier.rise(100), style = MaterialTheme.typography.bodyMedium, color = Ink.slate, textAlign = TextAlign.Center)
                Spacer(Modifier.height(22.dp))
                FlowRow(Modifier.rise(200), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SUGGESTIONS.forEach { s ->
                        Text(s, Modifier.glass(Pill).clickable(enabled = Ui.ready) { ui.send(s, from = t(R.string.screens_a_suggestion)) }.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else {
            Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = Dimens.screen, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                items(lines) { Said(it) }
                if (Ui.thinking) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PresenceSymbol(Presence.PROCESSING, 36.dp)
                        Spacer(Modifier.size(8.dp))
                        Text(Offline.thinking(), style = MaterialTheme.typography.bodyMedium, color = Ink.slate)
                    }
                }
            }
            // When scrolled up, a button scrolls back to the latest entry.
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            androidx.compose.animation.AnimatedVisibility(
                list.canScrollForward, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp),
                enter = fadeIn() + androidx.compose.animation.scaleIn(), exit = fadeOut() + androidx.compose.animation.scaleOut(),
            ) {
                GlassIcon(Icons.Rounded.KeyboardArrowDown, t(R.string.screens_go_to_the_latest)) {
                    scope.launch { list.animateScrollToItem(lines.size + if (Ui.thinking) 1 else 0) }
                }
            }
            }
        }
        Column(Modifier.animateContentSize()) { Evanescing(Guard.outbox.waiting(), { it.id }) { item, gone, m, inner ->
            Box(Modifier.padding(horizontal = 16.dp)) {
                Moment(item.act.title, item.act.detail?.let { "“$it”" }, t(R.string.screens_undo), { Countdown(item, 36) }, m, inner, !gone) { Guard.undo(item.id); Ui.refresh() }
            }
        } }
        Composer(ui)
    }
}

@Composable
private fun Said(line: Line) {
    Ui.tick
    when (line.who) {
        Line.Who.ME -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            // Shows how the request was made: by voice, typed, or a widget tap (whose text is generated by the app).
            Eyebrow(when (line.via) {
                Line.Via.TAP -> t(R.string.screens_you_touched_x_x, line.from, ago(line.at))
                Line.Via.VOICE -> t(R.string.screens_you_by_voice_x, ago(line.at))
                Line.Via.TYPED -> t(R.string.screens_you_typed_x, ago(line.at))
                null -> t(R.string.screens_you_x, ago(line.at))
            })
            Spacer(Modifier.height(6.dp))
            Text(
                line.text,
                Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(Dimens.card)).background(Ink.warmth.copy(alpha = 0.10f))
                    .border(1.dp, Ink.warmth.copy(alpha = 0.28f), RoundedCornerShape(Dimens.card)).padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Line.Who.ASSISTANT -> Column {
            // Offline answers (on-device model or the app itself) are labeled so their simpler quality is expected.
            Eyebrow(if (line.from == Offline.AGENT) t(R.string.screens_x_offline, Policy.ASSISTANT) else Policy.ASSISTANT, color = Ink.warmth)
            Spacer(Modifier.height(6.dp))
            Text(line.text, Modifier.glass(RoundedCornerShape(Dimens.card)).padding(horizontal = 16.dp, vertical = 14.dp), style = MaterialTheme.typography.bodyLarge)
        }
        // A tool line for a shown card reopens that card on tap.
        Line.Who.TOOL -> Row(Modifier.glass(Pill, lit = 0.7f).then(Cards.all.lastOrNull { line.text.endsWith(" · ${it.title}") && line.text.startsWith(t(R.string.screens_showed_you)) }
            ?.let { card -> Modifier.clickable { Cards.shown = card } } ?: Modifier).padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Light(Ink.signal, 5.dp)
            Spacer(Modifier.size(10.dp))
            Text(line.text, style = Technical, color = Ink.mist)
        }
        Line.Who.NOTE -> Text(line.text, style = MaterialTheme.typography.bodyMedium, color = Ink.alert)
    }
}

@Composable
private fun Composer(ui: MainActivity) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    fun send() { ui.send(text); text = "" }
    Row(
        Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 60.dp).glass(RoundedCornerShape(30.dp), lit = 1.3f).padding(start = 22.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text(if (Ui.listening) t(R.string.screens_listening) else t(R.string.screens_write_to_me), style = MaterialTheme.typography.bodyLarge, color = Ink.slate)
            BasicTextField(
                text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus), maxLines = 5,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.mist),
                cursorBrush = SolidColor(Ink.warmth),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
        }
        Spacer(Modifier.size(10.dp))
        val writing = text.isNotBlank()
        LightDot(if (writing) Glyphs.up else Glyphs.mic, if (writing) t(R.string.screens_send) else t(R.string.screens_talk), 46.dp, voice = !writing, enabled = Ui.ready) { if (writing) send() else ui.listen() }
    }
}

// Activity screen: incoming messages from others and the assistant's actions, each with the rule that applied.

@Composable
fun ActivityPlace(onBack: () -> Unit) {
    Ui.tick
    // Marks the digest as read so the messages widget counts only newer entries.
    Inbox.digest.lastOrNull()?.let { Inbox.readUpTo = it.id }
    var log by rememberSaveable { mutableStateOf(false) }
    val digest = Inbox.digest.reversed()
    val counts = digest.groupingBy { it.status }.eachCount()
    Column(Modifier.fillMaxSize()) {
        Header(t(R.string.screens_activity), onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            item {
                Text(t(R.string.screens_what_people_wrote_to_you_and), Modifier.padding(start = 4.dp, top = 6.dp), style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Figure(digest.size, t(R.string.screens_received), Ink.mist, Modifier.weight(1f))
                    Figure(counts[Inbox.Status.ANSWERED] ?: 0, t(R.string.screens_answered), Ink.warmth, Modifier.weight(1f))
                    Figure((counts[Inbox.Status.WAITING] ?: 0) + (counts[Inbox.Status.QUEUED] ?: 0), t(R.string.screens_waiting), Ink.wait, Modifier.weight(1f))
                }
                SectionLabel(t(R.string.screens_messages))
            }
            if (digest.isEmpty()) item {
                Panel { Item(t(R.string.screens_nothing_yet), t(R.string.screens_when_someone_writes_to_you_on), divider = false) }
            } else item {
                Panel { digest.forEachIndexed { i, entry -> Incoming(entry, i != digest.lastIndex) } }
            }
            item {
                SectionLabel(t(R.string.screens_action_log))
                Panel {
                    Item(t(R.string.screens_each_action_and_the_rule_that), t(R.string.screens_x_entries_on_this_phone, Guard.journal.entries.size), divider = log, onClick = { log = !log }) {
                        Text(if (log) t(R.string.screens_hide) else t(R.string.screens_show), style = MaterialTheme.typography.labelLarge, color = Ink.warmth)
                    }
                    AnimatedVisibility(log, enter = fadeIn(), exit = fadeOut()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Guard.journal.entries.reversed().take(100).forEach { Text(it.toString(), style = Technical.copy(fontSize = 11.sp, lineHeight = 16.sp), color = Ink.slate) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Figure(value: Int, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.glass(RoundedCornerShape(Dimens.card)).padding(16.dp)) {
        Text("$value", style = Technical.copy(fontSize = 30.sp, lineHeight = 36.sp), color = color)
        Spacer(Modifier.height(4.dp))
        // Auto-sized: long labels were truncated on narrow screens.
        androidx.compose.foundation.text.BasicText(
            label.uppercase(), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 1.2.sp, color = Ink.slate), maxLines = 1,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = 9.sp),
        )
    }
}

@Composable
private fun Incoming(entry: Inbox.Entry, divider: Boolean) {
    val group = Policy.groups[entry.sender].orEmpty().joinToString { Labels.group(it) }.ifEmpty { t(R.string.screens_unknown) }
    val color = when (entry.status) {
        Inbox.Status.ANSWERED -> Ink.ok
        Inbox.Status.WAITING, Inbox.Status.QUEUED, Inbox.Status.RECEIVED -> Ink.wait
        Inbox.Status.COULD_NOT_ANSWER -> Ink.alert
        else -> Ink.slate
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Avatar(entry.sender, entry.app)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                // Sender and time share one line with the time unwrapped (a long number used to squeeze them into a
                // narrow column); group below. The unverified marker is part of the sender key, so it is stripped for
                // display and shown as a localized label.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Identity.bare(entry.sender), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.size(8.dp))
                    Text(entry.at, style = Technical, color = Ink.slate, maxLines = 1, softWrap = false)
                }
                Eyebrow(group + if (Identity.unverified(entry.sender)) t(R.string.screens_unverified) else "")
                Spacer(Modifier.height(2.dp))
                Text(entry.text, style = MaterialTheme.typography.bodyMedium, color = Ink.mist.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Light(color, 6.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(Labels.status(entry.status).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium, color = color)
                }
            }
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 74.dp), color = Ink.line)
    }
}

// Settings screen: who the assistant answers and how, voice, offline mode, costs, appearance, and the stop switch.

@Composable
fun Control(ui: MainActivity, onBack: () -> Unit) {
    Ui.tick
    var editing by remember { mutableStateOf<String?>(null) }
    // Section offsets, used to scroll to a section requested through [Sections].
    val scroll = rememberScrollState()
    val anchors = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>() }
    fun at(key: String) = Modifier.onGloballyPositioned { anchors[key] = it.positionInParent().y.toInt() }
    LaunchedEffect(Ui.section, anchors.size) {
        val key = Ui.section ?: return@LaunchedEffect
        if (key in Sections.SHEETS) { editing = key; Ui.section = null }
        else anchors[key]?.let { scroll.animateScrollTo(it); Ui.section = null }
    }
    val state = presence()
    val stopped = state == Presence.PAUSED
    val needs = listOf(ui.permitted(), ui.canCall(), ui.unrestricted(), ui.listening())
    Column(Modifier.fillMaxSize()) {
        Header(t(R.string.screens_settings), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(t(R.string.screens_you_decide_what_i_can_do), Modifier.padding(start = 4.dp, top = 6.dp), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(20.dp))
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(Dimens.card)).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PresenceSymbol(state, 72.dp)
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (stopped) t(R.string.screens_paused) else t(R.string.screens_active), style = MaterialTheme.typography.headlineSmall)
                        Text(if (stopped) t(R.string.screens_i_do_nothing_until_you_resume) else t(R.string.screens_everything_i_do_is_logged), style = MaterialTheme.typography.bodyMedium, color = Ink.slate)
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (stopped) GlowButton(t(R.string.screens_resume), Modifier.fillMaxWidth(), icon = Icons.Rounded.PlayArrow) { ui.setStopped(false) }
                else GhostButton(t(R.string.screens_pause_everything), Modifier.fillMaxWidth(), icon = Glyphs.pause) { ui.setStopped(true) }
            }
            SectionLabel(t(R.string.screens_you))
            Panel { Item(t(R.string.screens_name), Policy.owner, divider = false, onClick = { editing = "name" }) { Chevron() } }
            SectionLabel(t(R.string.screens_presence), at("presence"))
            Panel {
                Item(t(R.string.screens_always_at_hand), t(R.string.screens_a_small_presence_over_your_apps), onClick = null) {
                    Toggle(Guard.bubble && ui.canFloat()) { ui.setBubble(it) }
                }
                if (Guard.bubble && ui.canFloat()) {
                    Ui.tick
                    // Floating mark position: screen side and height, set by dragging or by request.
                    @Composable
                    fun chips(options: List<Pair<String, Boolean>>, pick: (Int) -> Unit) =
                        Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            options.forEachIndexed { i, (label, on) ->
                                Text(
                                    label,
                                    Modifier.weight(1f).let { if (on) it.clip(Pill).background(Glow) else it.glass(Pill) }.clickable { pick(i); Ui.refresh() }.padding(vertical = 11.dp),
                                    style = Technical, textAlign = TextAlign.Center, color = if (on) Ink.onLight else Ink.mist,
                                )
                            }
                        }
                    chips(listOf(t(R.string.screens_left) to !Floating.right, t(R.string.screens_right) to Floating.right)) { Floating.place(ui, right = it == 1) }
                    val heights = listOf(0.18f, 0.45f, 0.72f)
                    val near = heights.minBy { kotlin.math.abs(it - Floating.height) }
                    chips(listOf(t(R.string.screens_top), t(R.string.screens_middle), t(R.string.screens_bottom)).mapIndexed { i, l -> l to (heights[i] == near) }) { Floating.place(ui, height = heights[it]) }
                }
                Item(t(R.string.screens_speak_aloud), t(R.string.screens_i_read_my_answers_aloud_as)) { Toggle(Guard.voice) { ui.setVoice(it) } }
                Item(
                    t(R.string.screens_open_microphone),
                    t(R.string.screens_while_i_m_in_front_of),
                ) {
                    Toggle(Guard.openMic) { ui.setOpenMic(it) }
                }
                Item(t(R.string.screens_how_i_talk_to_you), Guard.warmth.detail, divider = false)
                Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Warmth.entries.forEach { w ->
                        val on = Guard.warmth == w
                        Text(
                            w.label,
                            Modifier.weight(1f).let { if (on) it.clip(Pill).background(Glow) else it.glass(Pill) }.clickable { ui.setWarmth(w) }.padding(vertical = 11.dp),
                            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = if (on) Ink.onLight else Ink.mist,
                        )
                    }
                }
            }
            SectionLabel(t(R.string.screens_colour), at("color"))
            Panel {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Palettes.all.forEach { p -> Swatch(Daylight.tune(p, Ink.hour), p.key == Ink.base.key) { ui.setPalette(p) } }
                }
            }
            SectionLabel(t(R.string.screens_people), at("people"))
            Panel {
                Item(t(R.string.screens_family), Policy.members("family").joinToString().ifEmpty { t(R.string.screens_nobody) } + t(R.string.screens_i_answer_them_you_can_undo), onClick = { editing = "family" }) { Chevron() }
                Item(t(R.string.screens_work), Policy.members("work").joinToString().ifEmpty { t(R.string.screens_nobody) } + t(R.string.screens_only_with_your_approval), divider = false, onClick = { editing = "work" }) { Chevron() }
            }
            SectionLabel(t(R.string.screens_messages), at("messages"))
            Panel {
                Item(t(R.string.screens_apps_i_read), Inbox.apps.map(ui::label).sorted().joinToString().ifEmpty { t(R.string.screens_none) } + if (Inbox.groups) t(R.string.screens_and_their_groups) else "", onClick = { editing = "apps" }) { Chevron() }
                Item(t(R.string.screens_connectors), EmailAccounts.accounts.takeIf { it.isNotEmpty() }?.let { t(R.string.screens_email_x_account_s, it.size) }
                    ?: t(R.string.screens_your_email_accounts_and_more), onClick = { Ui.place = Place.CONNECTORS }) { Chevron() }
                Item(t(R.string.screens_read_your_email), t(R.string.screens_i_tell_you_about_the_ones)) {
                    Toggle(Mailbox.reading) { ui.setMail(it) }
                }
                Item(t(R.string.screens_time_to_undo), t(R.string.screens_what_goes_out_waits_before_it), divider = false)
                Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 30, 60, 120).forEach { s ->
                        val on = Guard.undoSeconds == s
                        Text(
                            "$s s",
                            Modifier.weight(1f).let { if (on) it.clip(Pill).background(Glow) else it.glass(Pill) }.clickable { ui.setUndoSeconds(s) }.padding(vertical = 11.dp),
                            style = Technical, textAlign = TextAlign.Center, color = if (on) Ink.onLight else Ink.mist,
                        )
                    }
                }
            }
            // Text-to-speech voices for the current language, best first; selecting one switches to it.
            SectionLabel(t(R.string.screens_her_voice), at("voice"))
            Panel {
                val cloud = Voices.chosen == CloudVoice.NAME
                // The cloud voice (ADR-0027) is listed first when configured.
                if (CloudVoice.configured) Item(
                    t(R.string.screens_natural_voice),
                    t(R.string.screens_the_most_human_needs_internet) +
                        (CloudVoice.perMinute?.takeUnless { Ui.hideCosts }?.let { " · " + Usage.usd(it) + t(R.string.screens_a_minute) } ?: ""),
                    divider = Voices.available.isNotEmpty(), onClick = { ui.chooseVoice(CloudVoice.NAME) },
                ) { Mark(cloud) }
                if (Voices.available.isEmpty()) Item(t(R.string.screens_the_phone_s_voices), t(R.string.screens_i_don_t_know_them_yet), divider = false)
                Voices.available.forEachIndexed { i, v ->
                    val (title, detail) = Voices.label(v)
                    Item(title, detail, divider = i < Voices.available.lastIndex, onClick = { ui.chooseVoice(v.name) }) { Mark(!cloud && v.name == Voices.current) }
                }
            }
            SectionLabel(t(R.string.screens_without_internet), at("offline"))
            Panel {
                Item(t(R.string.screens_answer_even_without_internet), Offline.status, divider = false) { Toggle(Offline.wanted) { ui.setOffline(it) } }
            }
            // Costs are hidden when [Ui.hideCosts] is set (debug builds only).
            if (!Ui.hideCosts) {
            SectionLabel(t(R.string.screens_what_i_cost), at("cost"))
            val cost = loaded(Unit, null as io.github.rustlanglatam.zeroclaw.CostSummary?) { Usage.read(ui) }
            Panel {
                if (cost == null) Item(t(R.string.screens_this_month), t(R.string.screens_i_can_t_read_it_now), divider = false)
                else {
                    val agents = cost.byAgent.orEmpty().values.sortedByDescending { it.costUsd }
                    Item(t(R.string.screens_this_month), if (cost.monthlyCostUsd > 0) t(R.string.screens_x_today_x, Usage.usd(cost.monthlyCostUsd), Usage.usd(cost.dailyCostUsd))
                        else Usage.amount(0.0, cost.totalTokens) + t(R.string.screens_no_price_for_the_model), divider = agents.isNotEmpty())
                    agents.forEachIndexed { i, a ->
                        Item(Usage.who(a.agentAlias), t(R.string.screens_x_x_calls, Usage.amount(a.costUsd, a.totalTokens, priced = cost.monthlyCostUsd > 0), a.requestCount), divider = i < agents.lastIndex)
                    }
                }
            }
            // Cloud voice usage this month (minutes and cost) and the spending ceiling above which the system TTS is used.
            if (CloudVoice.configured) Panel {
                val minutes = CloudVoice.month(ui).current() / 60
                val spent = CloudVoice.spent(ui)
                Item(t(R.string.screens_my_natural_voice), "%.1f min".format(minutes) + (spent?.let { " · " + Usage.usd(it) } ?: "") +
                    t(R.string.screens_this_month_at_the_cap_the), divider = false)
                Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2.0, 5.0, 10.0, 0.0).forEach { usd ->
                        val on = Guard.voiceCeiling == usd
                        Text(
                            if (usd > 0) "US$ ${usd.toInt()}" else t(R.string.screens_no_cap),
                            Modifier.weight(1f).let { if (on) it.clip(Pill).background(Glow) else it.glass(Pill) }.clickable { ui.setVoiceCeiling(usd) }.padding(vertical = 11.dp),
                            style = Technical, textAlign = TextAlign.Center, color = if (on) Ink.onLight else Ink.mist,
                        )
                    }
                }
            }
            }
            SectionLabel(t(R.string.screens_permissions_and_privacy), at("privacy"))
            Panel {
                Item(t(R.string.screens_what_i_can_do), if (ui.fingerprint()) t(R.string.screens_x_rules_each_change_asks_for, Guard.policy.rules.size) else t(R.string.screens_x_rules_you_confirm_each_change, Guard.policy.rules.size), onClick = { Ui.place = Place.RULES }) { Chevron() }
                Item(t(R.string.screens_phone_permissions), t(R.string.screens_x_of_x_granted, needs.count { it }, needs.size), onClick = { Ui.draft = Draft.current().apply { step = 1 } }) { Chevron() }
                Item(
                    t(R.string.screens_what_leaves_the_phone),
                    t(R.string.screens_to_the_model_what_you_ask),
                    divider = false,
                )
            }
            Spacer(Modifier.height(28.dp))
            Text("${Policy.ASSISTANT.lowercase()} · ${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth(), style = Technical, color = Ink.slate.copy(alpha = 0.6f), textAlign = TextAlign.Center)
        }
    }
    editing?.let { what ->
        val draft = remember(what) { Draft.current() }
        val done = { editing = null }
        if (what == "name") NameSheet(draft.owner, done) { draft.owner = it; done(); ui.saveSetup(draft, t(R.string.screens_change_your_name)) }
        else DraftPicker(ui, draft, what, done) { done(); ui.saveSetup(draft, t(R.string.screens_change_who_i_talk_to)) }
    }
}

// Memory screen: what the assistant knows about the owner, each item with its source and a delete action.

@Composable
fun MemoryPlace(ui: MainActivity, onBack: () -> Unit) {
    Ui.tick
    // Syncs to include entries the agent stored during conversations.
    LaunchedEffect(Unit) { PhoneMemory.sync(ui) }
    val items = Memory.items.reversed()
    val tap = haptic()
    Column(Modifier.fillMaxSize()) {
        Header(t(R.string.screens_memory), onBack)
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            item {
                Text(t(R.string.screens_what_i_know_about_you), Modifier.padding(start = 4.dp, top = 6.dp), style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    t(R.string.screens_i_learn_from_what_you_tell),
                    Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodyLarge, color = Ink.slate,
                )
                Spacer(Modifier.height(18.dp))
                Panel { Item(t(R.string.screens_learn_about_you), if (Memory.learning) t(R.string.screens_on_i_keep_what_is_worth) else t(R.string.screens_paused_i_keep_nothing_new), divider = false) { Toggle(Memory.learning) { ui.setLearning(it) } } }
                SectionLabel(t(R.string.screens_how_well_i_know_you))
                Knowing()
                SectionLabel(t(R.string.screens_memories_x, items.size))
            }
            if (items.isEmpty()) item {
                Panel { Item(t(R.string.screens_i_don_t_remember_anything_yet), t(R.string.screens_tell_me_what_you_like_your), divider = false) }
            }
            items(items, key = { it.id }) { memory ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp).glass(RoundedCornerShape(Dimens.card)).padding(start = 18.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        val how = when { memory.confirmed -> t(R.string.screens_you_told_me); memory.conversation -> t(R.string.screens_from_a_conversation); else -> t(R.string.screens_i_inferred_it) }
                        Eyebrow(memory.area.label + " · " + how + " · " + ago(memory.at), color = if (memory.confirmed) Ink.warmth else Ink.slate)
                        Spacer(Modifier.height(4.dp))
                        Text(memory.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    androidx.compose.material3.IconButton({ tap(); ui.forget(memory) }) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Clear, t(R.string.screens_forget), tint = Ink.slate)
                    }
                }
            }
        }
    }
}

/** Draws per-area knowledge depth as four-step bars. */
@Composable
private fun Knowing() {
    // Uses the presence ring's three colors, one per [Bond.Strand].
    fun color(s: Bond.Strand) = when (s) { Bond.Strand.YOU -> Ink.palette.ringA; Bond.Strand.DAY -> Ink.palette.ringB; Bond.Strand.WORLD -> Ink.palette.ringC }
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(Dimens.card)).padding(horizontal = 18.dp, vertical = 14.dp)) {
        Text(
            t(R.string.screens_my_ring_fills_with_colour_as), Modifier.padding(bottom = 6.dp),
            style = MaterialTheme.typography.bodyMedium, color = Ink.slate,
        )
        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Bond.Strand.entries.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(Pill).background(color(s)))
                    Spacer(Modifier.width(6.dp))
                    Text(s.label, style = MaterialTheme.typography.labelMedium, color = Ink.slate)
                }
            }
        }
        Memory.Area.KNOWN.forEach { area ->
            val depth = Memory.depth(area)
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(area.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(3) { k ->
                        Box(Modifier.size(width = 18.dp, height = 6.dp).clip(Pill).background(if (depth.ordinal > k) color(Bond.strand(area)) else Ink.slate.copy(alpha = 0.25f)))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(depth.label, Modifier.width(60.dp), style = MaterialTheme.typography.labelMedium, color = Ink.slate, maxLines = 1)
            }
        }
    }
}

// Files screen: search results, best match first; a tap opens the file.

@Composable
fun FilesPlace(onBack: () -> Unit) {
    val found = Ui.files
    val context = LocalContext.current
    val tap = haptic()
    Column(Modifier.fillMaxSize()) {
        Header(t(R.string.screens_files), onBack)
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            item {
                Text(found?.first?.let { "«$it»" } ?: t(R.string.screens_ask_me_for_a_document), Modifier.padding(start = 4.dp, top = 6.dp), style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        found == null -> t(R.string.screens_for_example_where_is_the_loan)
                        found.second.isEmpty() -> t(R.string.screens_i_didn_t_find_it_in)
                        else -> t(R.string.screens_i_looked_in_your_files_emails)
                    },
                    Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodyLarge, color = Ink.slate,
                )
                Spacer(Modifier.height(16.dp))
            }
            items(found?.second.orEmpty()) { hit ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp).glass(RoundedCornerShape(Dimens.card))
                        .clickable(enabled = hit.uri != null) {
                            tap()
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(hit.uri), hit.mime ?: "*/*")
                                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }.padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Icon(if (hit.uri != null) Glyphs.list else Glyphs.chat, null, Modifier.size(18.dp), tint = Ink.warmth)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(hit.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Eyebrow(hit.where)
                    }
                    if (hit.uri != null) Text(t(R.string.screens_open), style = MaterialTheme.typography.labelLarge, color = Ink.warmth)
                }
            }
        }
    }
}

// Photos screen: search results as a grid; a tap opens the photo in the gallery.

@Composable
fun PhotosPlace(onBack: () -> Unit) {
    val found = Ui.photos
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Header(t(R.string.screens_photos), onBack)
        Text(found?.first ?: t(R.string.screens_ask_me_for_photos), Modifier.padding(start = 24.dp, top = 6.dp, end = 24.dp), style = MaterialTheme.typography.displaySmall)
        Text(
            when {
                found == null -> t(R.string.screens_for_example_find_me_the_photos)
                found.second.isEmpty() -> t(R.string.screens_i_found_none_try_another_date)
                else -> "${found.second.size} ${if (found.second.size == 1) t(R.string.screens_photo) else t(R.string.screens_photos_2)}" + t(R.string.screens_searched_here_on_your_phone)
            },
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge, color = Ink.slate,
        )
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            androidx.compose.foundation.lazy.grid.GridCells.Fixed(3), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(found?.second.orEmpty().size) { i ->
                val uri = found!!.second[i]
                Thumb(uri) {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        }
    }
}

/** Draws a photo thumbnail loaded on the IO dispatcher. */
@Composable
private fun Thumb(uri: String, onClick: () -> Unit) {
    val context = LocalContext.current
    val bitmap by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(android.net.Uri.parse(uri), android.util.Size(360, 360), null).asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.fillMaxWidth().height(116.dp).clip(RoundedCornerShape(Dimens.control)).glass(RoundedCornerShape(Dimens.control)).clickable(onClick = onClick)) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
    }
}

/** Draws a selectable palette preview: background, accent colors, a small Presence Mark, and its name. */
@Composable
private fun Swatch(p: Palette, on: Boolean, onClick: () -> Unit) {
    val tap = haptic()
    Column(Modifier.clip(RoundedCornerShape(Dimens.control)).clickable { tap(); onClick() }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier.size(58.dp).clip(CircleShape)
                .border(if (on) 2.dp else 1.dp, if (on) Ink.rose else Ink.line, CircleShape),
        ) {
            val c = center
            val r = size.minDimension / 2
            drawRect(p.void)
            p.lights.forEach { l -> drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(l.color.copy(alpha = l.alpha * 1.6f), Color.Transparent), Offset(size.width * l.x, size.height * l.y), r * 1.4f), r * 2, c) }
            val rr = r * 0.52f
            drawArc(
                androidx.compose.ui.graphics.Brush.linearGradient(listOf(p.ringA, p.ringB, p.ringC), Offset(c.x - rr, c.y - rr), Offset(c.x + rr, c.y + rr)),
                8.7f, 296.7f, false, Offset(c.x - rr, c.y - rr), Size(rr * 2, rr * 2), style = Stroke(r * 0.09f, cap = StrokeCap.Round),
            )
            drawCircle(p.pearl, r * 0.13f, c)
        }
        Spacer(Modifier.height(6.dp))
        Text(p.name, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp), color = if (on) Ink.mist else Ink.slate)
    }
}

@Composable
private fun NameSheet(name: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(name) }
    Sheet(onDismiss) {
        Text(t(R.string.screens_what_should_i_call_you), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(t(R.string.screens_this_is_how_i_introduce_myself, Policy.ASSISTANT, value), style = MaterialTheme.typography.bodyMedium, color = Ink.slate)
        Spacer(Modifier.height(22.dp))
        Field(value, t(R.string.screens_your_name)) { value = it }
        Spacer(Modifier.height(24.dp))
        GlowButton(t(R.string.screens_save), Modifier.fillMaxWidth(), enabled = value.isNotBlank()) { onSave(value.trim()) }
    }
}

/** Lists every policy rule; each offers only the levels its risk ceiling allows, and a change requires the owner's credential. */
@Composable
fun Rules(ui: MainActivity, onBack: () -> Unit) {
    var open by remember { mutableStateOf<Int?>(null) }
    val rules = Guard.policy.rules
    Column(Modifier.fillMaxSize()) {
        Header(t(R.string.screens_what_i_can_do), onBack)
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            item { Text(t(R.string.screens_none_of_this_depends_on_what), Modifier.padding(start = 4.dp, top = 6.dp), style = MaterialTheme.typography.displaySmall) }
            listOf(Principal.OWNER to t(R.string.screens_for_you), Principal.THIRD_PARTY to t(R.string.screens_for_other_people)).forEach { (principal, title) ->
                val mine = rules.withIndex().filter { it.value.principal == principal }
                item {
                    SectionLabel(title)
                    Panel {
                        mine.forEachIndexed { i, (index, rule) ->
                            val (name, how) = Labels.rule(rule)
                            Item(name, how, i != mine.lastIndex, onClick = { open = index }) { Chevron() }
                        }
                    }
                }
            }
        }
    }
    open?.let { index ->
        val rule = rules[index]
        val tap = haptic()
        Sheet({ open = null }) {
            Text(Labels.rule(rule).first, style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(t(R.string.screens_i_only_offer_you_what_this), style = MaterialTheme.typography.bodyMedium, color = Ink.slate)
            Spacer(Modifier.height(18.dp))
            Policy.choices(rule).reversed().forEach { level ->
                val on = level == rule.autonomy
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).glass(RoundedCornerShape(Dimens.card), lit = if (on) 1.8f else 0.7f)
                        .clickable { tap(); open = null; if (!on) ui.editRule(index, level) }.padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(Labels.autonomy(level), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Mark(on)
                }
            }
        }
    }
}

// News screen: topics the assistant follows for the owner and the briefings it delivered.

@Composable
fun NewsPlace(ui: MainActivity, onBack: () -> Unit) {
    Ui.tick
    val phone = remember { PhoneNews(ui) }
    val topics = phone.topics()
    val told = phone.briefings().reversed()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Header(t(R.string.screens_news), onBack)
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            item {
                Text(if (topics.isEmpty()) t(R.string.screens_i_m_not_following_anything_for) else t(R.string.screens_what_i_follow_for_you), Modifier.padding(start = 4.dp, top = 6.dp), style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (topics.isEmpty()) t(R.string.screens_tell_me_for_example_keep_me)
                    else topics.joinToString(" · ") { t -> t.query + (t.everyHours?.let { t(R.string.screens_when_something_happens) } ?: t(R.string.screens_every_day_x, t.time)) },
                    Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodyLarge, color = Ink.slate,
                )
                if (topics.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        t(R.string.screens_to_stop_following_one_tell_me, topics.first().query),
                        Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium, color = Ink.slate.copy(alpha = 0.8f),
                    )
                }
                Spacer(Modifier.height(16.dp))
                // Topics followed but no briefing yet: shows when the first one is due instead of an empty list.
                if (topics.isNotEmpty() && told.isEmpty()) Panel {
                    val first = topics.mapNotNull { it.time }.minOrNull()
                    Item(
                        t(R.string.screens_no_news_yet),
                        if (first != null) t(R.string.screens_i_ll_tell_you_what_s, first) else t(R.string.screens_i_ll_tell_you_as_soon),
                        divider = false,
                    )
                }
            }
            items(told) { b ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).glass(RoundedCornerShape(Dimens.card)).padding(18.dp)) {
                    Eyebrow("${b.topic} · ${ago(b.at)}", color = Ink.warmth)
                    Spacer(Modifier.height(8.dp))
                    Text(b.text, style = MaterialTheme.typography.bodyLarge)
                    b.headlines.take(3).forEach { h ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${h.title} · ${h.source}",
                            Modifier.clickable { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(h.link)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                            style = MaterialTheme.typography.bodyMedium, color = Ink.slate, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
