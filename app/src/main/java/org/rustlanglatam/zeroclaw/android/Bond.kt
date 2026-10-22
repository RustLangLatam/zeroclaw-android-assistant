package org.rustlanglatam.zeroclaw.android

/**
 * Computes the state shown by the Presence Mark (see docs/specs/presence-mark.md). The ring's three thirds are three
 * [Strand]s, groups of [Memory.Area]s, and each one's color intensity grows with how much the assistant knows about
 * those areas: pale when nothing is known, fully lit when all areas are known well. The glow around the mark shows
 * the current [Load]. Pure Kotlin, unit-tested on the JVM; [PresenceSymbol] draws it.
 */
object Bond {

    /** The ring's three segments, drawn in the palette's ring colors A, B and C in declaration order. */
    enum class Strand(@androidx.annotation.StringRes private val labelId: Int, val areas: List<Memory.Area>) {
        YOU(R.string.strand_you, listOf(Memory.Area.PERSONAL, Memory.Area.PEOPLE, Memory.Area.HEALTH, Memory.Area.TASTES)),
        DAY(R.string.strand_day, listOf(Memory.Area.WORK, Memory.Area.ROUTINES, Memory.Area.PLACES)),
        WORLD(R.string.strand_world, listOf(Memory.Area.INTERESTS, Memory.Area.GOALS)),
        ;

        val label get() = t(labelId)
    }

    /** Returns the strand that [area] belongs to; [Strand.YOU] for an unmapped area. */
    fun strand(area: Memory.Area) = Strand.entries.firstOrNull { area in it.areas } ?: Strand.YOU

    /** Knowledge level of each strand, from 0 to 1. */
    data class Ring(val you: Float, val day: Float, val world: Float) {
        /** Mean knowledge level across the three strands, from 0 to 1. */
        val lit get() = (you + day + world) / 3f
        val whole get() = lit >= 1f
    }

    /** Returns the color intensity for a strand with knowledge level [known], never below [FIRST]. */
    fun color(known: Float) = FIRST + (1f - FIRST) * known.coerceIn(0f, 1f)

    // Minimum intensity: high enough to read as colored on both the in-app button and the floating mark.
    private const val FIRST = 0.65f

    private fun weight(d: Memory.Depth) = when (d) {
        Memory.Depth.NONE -> 0f
        Memory.Depth.LITTLE -> 1 / 3f
        Memory.Depth.SOME -> 2 / 3f
        Memory.Depth.WELL -> 1f
    }

    /** Returns the [Ring] from each area's [Memory.Depth], averaged per strand. */
    fun of(depth: (Memory.Area) -> Memory.Depth) = Strand.entries.map { s -> s.areas.map { weight(depth(it)) }.average().toFloat() }
        .let { (you, day, world) -> Ring(you, day, world) }

    /** The assistant's pending-work level, shown as the glow around the mark (and also stated in text by [Panorama], never by color alone). */
    enum class Load { CALM, BUSY, URGENT }

    /**
     * Returns [Load.URGENT] when something cannot wait (a high-level reminder ringing); [Load.BUSY] when items await the
     * owner (an approval, a pending outgoing reply, an important email, or at least three unread messages); otherwise [Load.CALM].
     */
    fun load(urgent: Boolean, approvals: Int, outgoing: Int, importantMail: Int, unread: Int) = when {
        urgent -> Load.URGENT
        approvals + outgoing + importantMail > 0 || unread >= 3 -> Load.BUSY
        else -> Load.CALM
    }
}
