package org.rustlanglatam.zeroclaw.android

import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import android.Manifest
import android.content.Intent
import android.provider.AlarmClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Drag-and-drop state for arranging the home screen's widgets: the [dragged] widget, the [pointer] position in root
 * coordinates, the [grab] point within the card, and the measured bounds of tiles and areas.
 */
class Arranging {
    var dragged by mutableStateOf<Widget?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var grab by mutableStateOf(Offset.Zero)
    val tiles = mutableStateMapOf<Widget, Rect>()
    val areas = mutableStateMapOf<Boolean, Rect>()

    /** Offset of the home screen within the root (below the status bar); the dragged card is drawn relative to it. */
    var origin by mutableStateOf(Offset.Zero)

    /**
     * Returns the drop target under the pointer: another card with its area, or `null` with the area (top or bottom)
     * to append to; `null` when the pointer is over neither.
     */
    fun target(): Pair<Widget?, Boolean>? {
        // [tiles] keeps stale bounds of removed cards, so only cards currently placed are considered.
        val placed = Guard.widgets.associate { it.widget to it.top }
        val over = tiles.entries.firstOrNull { (w, r) -> w != dragged && w in placed && r.contains(pointer) }
        if (over != null) return over.key to placed.getValue(over.key)
        return areas.entries.firstOrNull { it.value.contains(pointer) }?.let { null to it.key }
    }

    /** Moves the dragged widget to the current [target], persists the layout and ends the drag. */
    fun drop() {
        val w = dragged ?: return
        target()?.let { (on, top) -> Guard.widgets = Widgets.move(Guard.widgets, w, on, top); Guard.save() }
        dragged = null
    }
}


/** Card aspect ratio, from a typical width of about 84 × 74 dp; narrower screens get smaller cards, not stretched ones. */
private const val TILE_RATIO = 84f / 74f
private val TILE_WIDTH = 84.dp
private val GAP = 8.dp

/**
 * The home screen's widget grid above ([top]) or below the Presence Mark: rows of [Widgets.COLUMNS] equal cells,
 * with a wide widget taking a whole row. While arranging, free cells are outlined and an extra empty row is shown
 * if the area has room.
 */
@Composable
fun WidgetArea(ui: MainActivity, top: Boolean, arranging: Arranging, modifier: Modifier = Modifier) {
    val rows = Widgets.rows(Guard.widgets, top)
    if (rows.isEmpty() && !Ui.arranging) return
    val target = if (arranging.dragged != null) arranging.target() else null
    val here = target?.let { it.first == null && it.second == top } == true
    fun Modifier.lit(w: Widget) = if (target?.first == w) border(1.5.dp, Ink.warmth, RoundedCornerShape(Dimens.control)) else this
    @Composable
    fun free(modifier: Modifier) = Box(modifier.aspectRatio(TILE_RATIO).let {
        if (Ui.arranging) it.border(1.dp, Ink.slate.copy(alpha = if (here) 0.9f else 0.3f), RoundedCornerShape(Dimens.control)) else it
    })
    Column(
        modifier.fillMaxWidth().onGloballyPositioned { arranging.areas[top] = it.boundsInRoot() },
        verticalArrangement = Arrangement.spacedBy(GAP),
    ) {
        rows.forEach { row ->
            if (row.size == 1 && row[0].widget.span == Widgets.COLUMNS) {
                WidgetTile(ui, row[0].widget, arranging, Modifier.fillMaxWidth().lit(row[0].widget))
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                row.forEach { p -> WidgetTile(ui, p.widget, arranging, Modifier.weight(1f).lit(p.widget)) }
                // Empty cells keep every card the same width; while arranging they are outlined as drop targets.
                repeat(Widgets.COLUMNS - row.sumOf { it.widget.span }) { free(Modifier.weight(1f)) }
            }
        }
        if (Ui.arranging && rows.size < (if (top) Widgets.ROWS_ABOVE else Widgets.ROWS_BELOW)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) { repeat(Widgets.COLUMNS) { free(Modifier.weight(1f)) } }
        }
    }
}

/** One grid card: a long press starts arranging and picks it up; releasing drops it into the target position. */
@Composable
private fun WidgetTile(ui: MainActivity, widget: Widget, arranging: Arranging, modifier: Modifier) {
    val tap = haptic()
    val wobble by rememberInfiniteTransition(label = "wobble").animateFloat(-0.8f, 0.8f, infiniteRepeatable(tween(260), RepeatMode.Reverse), label = "w")
    val picked = arranging.dragged == widget
    Box(
        modifier
            .onGloballyPositioned { arranging.tiles[widget] = it.boundsInRoot() }
            .rotate(if (Ui.arranging && !picked) wobble else 0f)
            .alpha(if (picked) 0.25f else 1f)
            .pointerInput(widget) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { at ->
                        tap()
                        Ui.arranging = true
                        arranging.grab = at
                        arranging.pointer = (arranging.tiles[widget]?.topLeft ?: Offset.Zero) + at
                        arranging.dragged = widget
                    },
                    onDrag = { change, delta -> change.consume(); arranging.pointer += delta },
                    onDragEnd = { arranging.drop(); tap() },
                    onDragCancel = { arranging.dragged = null },
                )
            },
    ) {
        if (widget.span == Widgets.COLUMNS) Wide(ui, widget, Modifier.let { if (Ui.arranging) it.glass(RoundedCornerShape(Dimens.control)) else it })
        else Card(ui, widget, Modifier.clickable(enabled = !Ui.arranging) { tap(); open(ui, widget) })
        if (Ui.arranging) Box(
            Modifier.align(Alignment.TopStart).offset((-6).dp, (-6).dp).size(22.dp).clip(CircleShape).background(Ink.sheet)
                .border(1.dp, Ink.slate.copy(alpha = 0.4f), CircleShape)
                .clickable { tap(); Guard.widgets = Widgets.remove(Guard.widgets, widget); Guard.save() },
            contentAlignment = Alignment.Center,
        ) { Text("−", style = MaterialTheme.typography.titleMedium, color = Ink.mist) }
    }
}

/** Draws the dragged card above all content, following the pointer. */
@Composable
fun DraggedWidget(ui: MainActivity, arranging: Arranging) {
    val widget = arranging.dragged ?: return
    val bounds = arranging.tiles[widget] ?: return
    val density = LocalDensity.current
    val at = arranging.pointer - arranging.grab - arranging.origin
    Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }.rotate(-2f)) {
        with(density) {
            if (widget.span == Widgets.COLUMNS) Wide(ui, widget, Modifier.size(bounds.width.toDp(), bounds.height.toDp()).glass(RoundedCornerShape(Dimens.control), lit = 1.8f))
            else Card(ui, widget, Modifier.size(bounds.width.toDp(), bounds.height.toDp()), lit = 1.8f)
        }
    }
}

/** Action bar shown while arranging: add a remaining widget, or finish. */
@Composable
fun ArrangeBar(onAdd: () -> Unit) {
    val tap = haptic()
    Row(Modifier.fillMaxWidth().padding(top = GAP), horizontalArrangement = Arrangement.spacedBy(GAP)) {
        if (Widgets.available(Guard.widgets).isNotEmpty()) GhostButton(t(R.string.homewidgets_add), Modifier.weight(1f)) { tap(); onAdd() }
        GlowButton(t(R.string.homewidgets_done), Modifier.weight(1f)) { tap(); Ui.arranging = false }
    }
}

/** Bottom sheet listing the widgets not yet on the home screen; selecting one adds it. */
@Composable
fun AddWidgetSheet(onDismiss: () -> Unit) {
    val tap = haptic()
    Sheet(onDismiss) {
        Text(t(R.string.homewidgets_add_to_your_home), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(t(R.string.homewidgets_what_you_want_to_see), style = MaterialTheme.typography.bodyMedium, color = Ink.slate)
        Spacer(Modifier.height(18.dp))
        Widgets.available(Guard.widgets).forEach { w ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).glass(RoundedCornerShape(Dimens.card), lit = 0.8f)
                    .clickable { tap(); Guard.widgets = Widgets.add(Guard.widgets, w); Guard.save(); onDismiss() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(w.label, style = MaterialTheme.typography.titleMedium)
                    Text(w.detail, style = MaterialTheme.typography.bodyMedium, color = Ink.slate)
                }
            }
        }
    }
}

/** Handles a tap on a card: navigates to the related screen, opens the system alarms, or sends a query to the assistant. */
private fun open(ui: MainActivity, widget: Widget) = when (widget) {
    Widget.MESSAGES -> Ui.place = Place.ACTIVITY
    Widget.MAIL -> ui.send(t(R.string.homewidgets_which_emails_haven_t_i_read), byVoice = true, from = widget.label)
    Widget.AGENDA -> ui.send(t(R.string.homewidgets_what_do_i_have_today_and), byVoice = true, from = widget.label)
    Widget.ALARM -> runCatching { ui.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }.let { }
    Widget.NEWS -> Ui.place = Place.NEWS
    Widget.REMINDERS -> ui.send(t(R.string.homewidgets_what_reminders_do_i_have), byVoice = true, from = widget.label)
    Widget.KSANDRA -> Notes.current().firstOrNull()?.let { ui.send(t(R.string.homewidgets_tell_me_about_x, it.title), byVoice = true, from = widget.label) }.let { }
    Widget.LISTS -> ui.send(t(R.string.homewidgets_what_is_on_my_list), byVoice = true, from = widget.label)
    Widget.WEATHER, Widget.BATTERY, Widget.GREETING, Widget.PANORAMA -> Unit
}

/** A standard card: one prominent value with a small caption describing it. */
@Composable
private fun Card(ui: MainActivity, widget: Widget, modifier: Modifier = Modifier, lit: Float = 1f) {
    Ui.tick
    val phone = remember { PhoneWidgets(ui) }
    // Scales the card's content density with its width so smaller cards are uniformly scaled copies; otherwise the
    // second line overflowed on narrow screens.
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(TILE_RATIO).glass(RoundedCornerShape(Dimens.control), lit)) {
    val outer = androidx.compose.ui.platform.LocalDensity.current
    val scale = (maxWidth / TILE_WIDTH).coerceIn(0.55f, 1f)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(outer.density * scale, outer.fontScale)) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (widget) {
            Widget.WEATHER -> Weather(phone)
            Widget.ALARM -> phone.alarm().let { a -> Passing(a ?: "", a?.first ?: "—", if (a == null || a.second == PhoneWidgets.today()) t(R.string.homewidgets_alarm) else a.second, placeholder = a == null) }
            Widget.MAIL -> {
                val unread = Mailbox.mails.count { it.id !in Mailbox.opened }
                val important = Mailbox.mails.count { it.important && it.id !in Mailbox.opened }
                // Shows the unread count; an important unread email only switches the value to the accent color.
                Value("$unread", if (unread == 1) t(R.string.homewidgets_email) else t(R.string.homewidgets_emails), warm = important > 0)
            }
            Widget.MESSAGES -> {
                val byApp = Widgets.unread(Inbox.unread().map { it.app })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Fitted("${byApp.sumOf { it.second }}", MaterialTheme.typography.headlineSmall, Ink.mist)
                    byApp.take(2).forEach { (app, _) -> Spacer(Modifier.width(4.dp)); AppIcon(app, 16.dp) }
                }
                Spacer(Modifier.height(2.dp))
                Caption(if (byApp.sumOf { it.second } == 1) t(R.string.homewidgets_message) else t(R.string.homewidgets_messages))
            }
            Widget.AGENDA -> loaded(Ui.tick, null) { phone.agenda(1) }.let { next ->
                when {
                    next == null -> Passing("", "—", t(R.string.homewidgets_agenda), placeholder = true)
                    next.isEmpty() -> Passing("free", t(R.string.homewidgets_free), t(R.string.homewidgets_agenda))
                    else -> Passing(next[0], next[0].first, next[0].second)
                }
            }
            Widget.REMINDERS -> loaded(Ui.tick, null) { PhoneReminders(ui).all().minByOrNull { it.at } }.let { r ->
                if (r == null) Passing("", "—", t(R.string.homewidgets_reminders), placeholder = true)
                else Passing(r.at to r.text, java.time.Instant.ofEpochMilli(r.at).atZone(java.time.ZoneId.systemDefault()).toLocalTime().toString().take(5), r.text, warm = r.level == Reminders.Level.HIGH)
            }
            Widget.NEWS -> loaded(Ui.tick, null) { PhoneNews(ui).briefings().lastOrNull() }.let { b ->
                if (b == null) Value("—", t(R.string.homewidgets_news)) else Value(b.topic, t(R.string.homewidgets_news), warm = System.currentTimeMillis() - b.at < 3 * 3600_000L)
            }
            Widget.BATTERY -> phone.battery().let { (level, charging) -> Value("$level%", if (charging) t(R.string.homewidgets_charging) else t(R.string.homewidgets_battery), warm = level <= 20 && !charging) }
            // Notes the assistant pinned to the card; several rotate every few seconds.
            Widget.KSANDRA -> Notes.current().let { notes ->
                if (notes.isEmpty()) Value("—", t(R.string.homewidgets_from_ksandra))
                else notes[(Ui.tick / 6) % notes.size].let { Value(it.value, it.title.lowercase(), warm = true) }
            }
            // The fullest list: its item count large, the first items small.
            Widget.LISTS -> Lists.all().maxByOrNull { it.items.size }.let { l ->
                if (l == null || l.items.isEmpty()) Value("—", t(R.string.homewidgets_lists))
                else Value("${l.items.size}", l.items.take(3).joinToString(", ").lowercase(), warm = true)
            }
            Widget.GREETING, Widget.PANORAMA -> Unit
        }
    }
    }
    }
}

/** Weather card: requests location permission, prompts to enable location, or shows the current conditions. */
@Composable
private fun Weather(phone: PhoneWidgets) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // After a denial Android may not show the dialog again, so the app's settings page is opened instead.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) phone.refreshWeather()
        else runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))) }
    }
    // Refreshes every minute: a location fix may arrive later and the data goes stale.
    LaunchedEffect(Unit) { while (true) { phone.refreshWeather(); kotlinx.coroutines.delay(60_000) } }
    val w = PhoneWidgets.weather
    when {
        !phone.locationPermitted() -> Column(Modifier.clickable { ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) { Value(t(R.string.homewidgets_tap), t(R.string.homewidgets_weather)) }
        w == null && PhoneWidgets.noPlace -> Column(Modifier.clickable {
            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        }) { Value(t(R.string.homewidgets_location), t(R.string.homewidgets_turn_it_on)) }
        w == null -> Value("…", t(R.string.homewidgets_weather))
        else -> {
            // Sky icon beside the temperature; below, the forecast text, highlighted when rain is likely.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                SkyIcon(Widgets.kind(w.code), w.day, Modifier.size(22.dp))
                Spacer(Modifier.width(2.dp))
                Fitted("${w.now}°", MaterialTheme.typography.headlineSmall, Ink.mist, Modifier.weight(1f, fill = false))
            }
            Spacer(Modifier.height(2.dp))
            val rain = w.rain >= Widgets.RAIN
            Text(
                Widgets.forecast(w.code, w.rain), style = MaterialTheme.typography.labelMedium, color = if (rain) Ink.ice else Ink.slate,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
        }
    }
}

/** Draws a weather icon for [sky] in the brand's stroke style: sun (moon at night), clouds and precipitation. */
@Composable
private fun SkyIcon(sky: Widgets.Sky, day: Boolean, modifier: Modifier) = Canvas(modifier) {
    val u = size.minDimension / 24f
    val stroke = Stroke(1.6f * u, cap = StrokeCap.Round)
    fun sun(c: Offset, r: Float) {
        if (day) {
            drawCircle(Ink.warmth, r, c)
            for (i in 0 until 8) {
                val a = i * Math.PI.toFloat() / 4
                val d = Offset(kotlin.math.cos(a), kotlin.math.sin(a))
                drawLine(Ink.warmth, c + d * (r + 2f * u), c + d * (r + 4f * u), 1.4f * u, StrokeCap.Round)
            }
        } else {
            // Crescent made by path difference, so nothing is painted over the card background.
            fun disc(at: Offset, radius: Float) = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(at, radius)) }
            val moon = androidx.compose.ui.graphics.Path().apply {
                op(disc(c, r), disc(c + Offset(r * 0.55f, -r * 0.35f), r * 0.85f), androidx.compose.ui.graphics.PathOperation.Difference)
            }
            drawPath(moon, Ink.warmth)
        }
    }
    fun cloud(dy: Float = 0f, color: Color = Ink.mist) {
        val base = 15f * u + dy
        drawCircle(color, 4.2f * u, Offset(9f * u, base))
        drawCircle(color, 5.6f * u, Offset(13.5f * u, base - 2.2f * u))
        drawCircle(color, 3.8f * u, Offset(18f * u, base + 0.4f * u))
        drawRect(color, Offset(9f * u, base), androidx.compose.ui.geometry.Size(9f * u, 4.2f * u))
    }
    when (sky) {
        Widgets.Sky.CLEAR -> sun(Offset(12f * u, 12f * u), 5f * u)
        Widgets.Sky.PARTLY -> { sun(Offset(8.5f * u, 8.5f * u), 3.8f * u); cloud(2f * u) }
        Widgets.Sky.CLOUDY -> cloud()
        Widgets.Sky.FOG -> for (k in 0 until 3) drawLine(Ink.mist, Offset((4f + 2 * (k % 2)) * u, (8f + 4f * k) * u), Offset((20f - 2 * (k % 2)) * u, (8f + 4f * k) * u), 1.8f * u, StrokeCap.Round)
        Widgets.Sky.RAIN -> { cloud(-3f * u); for (k in 0 until 3) drawLine(Ink.ice, Offset((8.5f + 4f * k) * u, 19f * u), Offset((7.5f + 4f * k) * u, 22.5f * u), 1.6f * u, StrokeCap.Round) }
        Widgets.Sky.SNOW -> { cloud(-3f * u); for (k in 0 until 3) drawCircle(Ink.pearl, 1.1f * u, Offset((8.5f + 4f * k) * u, 21f * u)) }
        Widgets.Sky.STORM -> {
            cloud(-3f * u)
            val bolt = androidx.compose.ui.graphics.Path().apply {
                moveTo(13.5f * u, 17f * u); lineTo(11f * u, 21f * u); lineTo(13f * u, 21f * u); lineTo(11.5f * u, 24f * u)
            }
            drawPath(bolt, Ink.warmth, style = stroke)
        }
    }
}

/**
 * A [Value] that transitions when its subject changes (an event ended, an alarm rang, a reminder was done): the
 * previous value dissolves in place while the next appears. [key] identifies the subject rather than the text, so a
 * language change does not trigger the animation; a [placeholder] value is replaced without it.
 */
@Composable
private fun Passing(key: Any, main: String, what: String, warm: Boolean = false, placeholder: Boolean = false) =
    Box(contentAlignment = Alignment.Center) {
        val shown: @Composable () -> Unit = { Value(main, what, warm) }
        Evanescing(listOf(Triple(key, placeholder, shown)), { it.first }, { it.second }) { (_, _, show), _, _, inner ->
            Column(inner, horizontalAlignment = Alignment.CenterHorizontally) { show() }
        }
    }

/** Shows [main] large and centered (smaller when longer than five characters) with the caption [what] below. */
@Composable
private fun Value(main: String, what: String, warm: Boolean = false) {
    Fitted(main, if (main.length <= 5) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium, if (warm) Ink.warmth else Ink.mist)
    Spacer(Modifier.height(2.dp))
    Caption(what)
}

/** Single-line text that auto-sizes down to 11 sp to fit a narrow card instead of being truncated. */
@Composable
private fun Fitted(text: String, style: androidx.compose.ui.text.TextStyle, color: Color, modifier: Modifier = Modifier) =
    androidx.compose.foundation.text.BasicText(
        text, modifier, style = style.copy(color = color, textAlign = TextAlign.Center), maxLines = 1,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = style.fontSize),
    )

/**
 * Single-line caption: shrinks to fit, down to 11 sp on screen (also in simple mode, whose larger text would otherwise
 * cut short words like "reminders"); text that still does not fit scrolls once as a marquee and then rests.
 */
@Composable
private fun Caption(text: String) {
    var fits by remember(text) { mutableStateOf(true) }
    val style = MaterialTheme.typography.labelMedium.copy(color = Ink.slate, textAlign = TextAlign.Center)
    androidx.compose.foundation.text.BasicText(
        // A marquee instead of an ellipsis, so long captions remain readable.
        text, if (fits) Modifier else Modifier.basicMarquee(iterations = 1, initialDelayMillis = 2_000, velocity = 30.dp),
        style = style, maxLines = 1,
        autoSize = if (fits) androidx.compose.foundation.text.TextAutoSize.StepBased(
            minFontSize = (11f / if (Guard.simple) Simple.TEXT_SCALE else 1f).sp, maxFontSize = style.fontSize,
        ) else null,
        onTextLayout = { if (fits && it.hasVisualOverflow) fits = false },
    )
}
