package org.rustlanglatam.zeroclaw.android

/**
 * A home-screen widget. Most occupy one cell; wide ones ([span] equal to [Widgets.COLUMNS], such as the greeting and
 * the panorama) take a whole row. Widgets are laid out in up to [Widgets.ROWS_ABOVE] rows above the presence mark and
 * [Widgets.ROWS_BELOW] below it; the mark never shrinks to make room. The owner can drag, remove and add widgets while
 * there is space. Pure Kotlin, unit-tested on the JVM; [PhoneWidgets] supplies the displayed data.
 */
enum class Widget(@androidx.annotation.StringRes private val labelId: Int, @androidx.annotation.StringRes private val detailId: Int, val span: Int = 1) {
    GREETING(R.string.widget_greeting_label, R.string.widget_greeting_detail, Widgets.COLUMNS),
    PANORAMA(R.string.widget_panorama_label, R.string.widget_panorama_detail, Widgets.COLUMNS),
    WEATHER(R.string.widget_weather_label, R.string.widget_weather_detail),
    ALARM(R.string.widget_alarm_label, R.string.widget_alarm_detail),
    AGENDA(R.string.widget_agenda_label, R.string.widget_agenda_detail),
    MESSAGES(R.string.widget_messages_label, R.string.widget_messages_detail),
    MAIL(R.string.widget_mail_label, R.string.widget_mail_detail),
    REMINDERS(R.string.widget_reminders_label, R.string.widget_reminders_detail),
    BATTERY(R.string.widget_battery_label, R.string.widget_battery_detail),
    NEWS(R.string.widget_news_label, R.string.widget_news_detail),
    KSANDRA(R.string.widget_ksandra_label, R.string.widget_ksandra_detail),
    LISTS(R.string.widget_lists_label, R.string.widget_lists_detail),
    ;

    /** Localized name; [detail] is the localized description of what the widget shows. */
    val label get() = t(labelId)
    val detail get() = t(detailId)
}

/** A widget placed on the home screen, above the presence mark when [top] is true; list order defines its position. */
data class Placed(val widget: Widget, val top: Boolean)

object Widgets {

    /** Number of cells per row. */
    const val COLUMNS = 4

    /** Maximum rows above the presence mark; [ROWS_BELOW] is the maximum below it. */
    const val ROWS_ABOVE = 3
    const val ROWS_BELOW = 1

    val DEFAULT = listOf(
        Placed(Widget.GREETING, true), Placed(Widget.PANORAMA, true),
        Placed(Widget.WEATHER, true), Placed(Widget.ALARM, true), Placed(Widget.AGENDA, true),
        Placed(Widget.MESSAGES, false), Placed(Widget.MAIL, false), Placed(Widget.REMINDERS, false),
    )

    fun row(items: List<Placed>, top: Boolean) = items.filter { it.top == top }

    /** Splits one area's widgets into rows, in order: a wide widget gets its own row, others fill rows of [COLUMNS] cells. */
    fun rows(items: List<Placed>, top: Boolean): List<List<Placed>> {
        val rows = mutableListOf<MutableList<Placed>>()
        for (p in row(items, top)) {
            val last = rows.lastOrNull()
            val room = last != null && last.sumOf { it.widget.span } + p.widget.span <= COLUMNS && last.none { it.widget.span == COLUMNS }
            if (room && p.widget.span < COLUMNS) last!!.add(p) else rows.add(mutableListOf(p))
        }
        return rows
    }

    /** Returns whether the layout fits in [ROWS_ABOVE] rows above and [ROWS_BELOW] rows below. */
    fun fits(items: List<Placed>) = rows(items, true).size <= ROWS_ABOVE && rows(items, false).size <= ROWS_BELOW

    /**
     * Moves [widget] to the position of [target], shifting the others. If the result does not fit, swaps the two instead
     * (when that fits). With a `null` [target] the widget is appended to the area given by [top] if there is room.
     *
     * @return the new layout, or [items] unchanged when no valid arrangement exists.
     */
    fun move(items: List<Placed>, widget: Widget, target: Widget?, top: Boolean): List<Placed> {
        val from = items.indexOfFirst { it.widget == widget }
        if (from < 0 || target == widget) return items
        val moving = items[from]
        val shifted = items.toMutableList().apply {
            removeAt(from)
            val to = indexOfFirst { it.widget == target }
            if (to < 0) add(moving.copy(top = top)) else add(to, moving.copy(top = top))
        }
        if (fits(shifted)) return shifted
        val to = items.indexOfFirst { it.widget == target }
        if (to < 0) return items
        val swapped = items.toMutableList().apply { this[from] = items[to].copy(top = moving.top); this[to] = moving.copy(top = items[to].top) }
        return if (fits(swapped)) swapped else items
    }

    /** Adds [widget] below the mark, or above when there is no room below; returns [items] unchanged when neither fits. */
    fun add(items: List<Placed>, widget: Widget): List<Placed> {
        if (items.any { it.widget == widget }) return items
        return listOf(items + Placed(widget, false), items + Placed(widget, true)).firstOrNull(::fits) ?: items
    }

    fun remove(items: List<Placed>, widget: Widget) = items.filterNot { it.widget == widget }

    /** Returns the widgets not yet placed that would still fit. */
    fun available(items: List<Placed>) = Widget.entries.filter { w -> items.none { it.widget == w } && add(items, w) != items }

    /** Layout version marker stored with the layout; older layouts are migrated by prepending the wide widgets. */
    private const val LAYOUT = "layout:2"

    fun encode(items: List<Placed>) = listOf(LAYOUT) + items.map { "${it.widget.name}:${if (it.top) "top" else "bottom"}" }

    fun decode(saved: List<String>?): List<Placed> {
        saved ?: return DEFAULT
        var list = saved.mapNotNull { s ->
            val (name, area) = s.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            Widget.entries.firstOrNull { it.name == name }?.let { Placed(it, area == "top") }
        }.distinctBy { it.widget }
        if (LAYOUT !in saved) list = listOf(Placed(Widget.GREETING, true), Placed(Widget.PANORAMA, true)) + list.filter { it.widget.span == 1 }
        // Drops trailing widgets until an older, fuller layout fits.
        while (!fits(list)) list = list.dropLast(1)
        return list
    }

    /**
     * Returns the weather widget's caption: the rain probability when it is at least [RAIN] percent and the sky is not
     * already rain, snow or storm; otherwise the sky description.
     */
    fun forecast(code: Int, rain: Int) = if (rain >= RAIN && kind(code) !in setOf(Sky.RAIN, Sky.STORM, Sky.SNOW)) t(R.string.widgets_rain_x, rain) else sky(code)

    /** Precipitation probability (percent) from which [forecast] shows rain instead of the sky. */
    const val RAIN = 30

    /** Sky category used to pick the widget's icon. */
    enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, STORM }

    fun kind(code: Int) = when (code) {
        0 -> Sky.CLEAR
        1, 2 -> Sky.PARTLY
        3 -> Sky.CLOUDY
        45, 48 -> Sky.FOG
        in 51..67, in 80..82 -> Sky.RAIN
        in 71..77, 85, 86 -> Sky.SNOW
        in 95..99 -> Sky.STORM
        else -> Sky.CLOUDY
    }

    /** Returns a short localized sky label for a WMO weather code, as returned by Open-Meteo. */
    fun sky(code: Int) = when (code) {
        0 -> t(R.string.widgets_clear)
        1, 2 -> t(R.string.widgets_partly)
        3 -> t(R.string.widgets_cloudy)
        45, 48 -> t(R.string.widgets_fog)
        in 51..57 -> t(R.string.widgets_drizzle)
        in 61..67 -> t(R.string.widgets_rain)
        in 71..77 -> t(R.string.widgets_snow)
        in 80..82 -> t(R.string.widgets_showers)
        85, 86 -> t(R.string.widgets_snowfall)
        in 95..99 -> t(R.string.widgets_storm)
        else -> "—"
    }

    /** Counts unread messages per app, highest first, for the messages widget. */
    fun unread(apps: List<String>): List<Pair<String, Int>> =
        apps.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
}
