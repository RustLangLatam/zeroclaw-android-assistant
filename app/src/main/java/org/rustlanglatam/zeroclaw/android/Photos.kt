package org.rustlanglatam.zeroclaw.android

import java.time.LocalDate
import java.time.YearMonth

/**
 * Builds photo search criteria from a spoken or written request: a date range, the content wanted
 * (on-device image labels such as Dog or Beach) and whether a face must appear.
 * Pure Kotlin, unit-tested on the JVM; [PhoneGallery] reads and labels the photos.
 */
object Photos {

    /** A photo as indexed by the gallery: its on-device labels (in English) and the number of detected faces. */
    data class Photo(val id: Long, val uri: String, val taken: Long, val labels: Set<String>, val faces: Int)

    /**
     * Returns the inclusive date range to search: a month (of [year], or [yearsAgo] years before
     * [today]), a whole year, explicit ISO dates in [from]/[to], or everything since 1970.
     */
    fun range(month: Int?, year: Int?, yearsAgo: Int?, from: String?, to: String?, today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> {
        if (!from.isNullOrBlank() || !to.isNullOrBlank()) {
            val start = from?.takeIf { it.isNotBlank() }?.let(LocalDate::parse) ?: LocalDate.of(1970, 1, 1)
            val end = to?.takeIf { it.isNotBlank() }?.let(LocalDate::parse) ?: today
            return start to end
        }
        val y = year ?: (today.year - (yearsAgo ?: 0))
        if (month != null && month in 1..12) {
            val ym = YearMonth.of(y, month)
            return ym.atDay(1) to ym.atEndOfMonth()
        }
        return if (year != null || yearsAgo != null) LocalDate.of(y, 1, 1) to LocalDate.of(y, 12, 31) else LocalDate.of(1970, 1, 1) to today
    }

    /**
     * Maps request words (Spanish or English, normalized) to the on-device labels they stand for; the `photos.labels`
     * lexicon entry lists them as `word=Label,Label`, separated by `|`.
     */
    private val WORDS = Lexicon.words("photos.labels").associate { it.substringBefore('=') to it.substringAfter('=').split(',').toSet() }

    /** Returns the labels for the comma- or space-separated words in [what]; unknown words are used as labels themselves, capitalized. */
    fun labels(what: String?): Set<String> = what.orEmpty().split(',', ' ', ';').map { Commands.normalize(it) }.filter { it.length > 1 }
        .flatMap { w -> WORDS[w] ?: WORDS[w.removeSuffix("s")] ?: setOf(w.replaceFirstChar { it.uppercase() }) }.toSet()

    /** Returns a localized title for the search: the period, the content words and, if [withPeople], a marker for people, joined by " · ". */
    fun title(from: LocalDate, to: LocalDate, what: String?, withPeople: Boolean): String {
        val period = when {
            from.dayOfMonth == 1 && to == YearMonth.from(from).atEndOfMonth() -> t(R.string.photos_month_year, from.month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, Language.locale).replaceFirstChar { it.uppercase() }, from.year)
            from.dayOfYear == 1 && to.year == from.year && to.monthValue == 12 && to.dayOfMonth == 31 -> "${from.year}"
            from.year <= 1970 -> t(R.string.photos_all)
            else -> t(R.string.photos_from_x_to_x, from, to)
        }
        return listOfNotNull(period, what?.trim()?.takeIf { it.isNotEmpty() }, t(R.string.photos_with_you).takeIf { withPeople }).joinToString(" · ")
    }

    /** Returns whether [photo] has any of the [wanted] labels (any photo when empty) and, if [withPeople], at least one face. */
    fun matches(photo: Photo, wanted: Set<String>, withPeople: Boolean): Boolean =
        (wanted.isEmpty() || photo.labels.any { label -> wanted.any { it.equals(label, ignoreCase = true) } }) && (!withPeople || photo.faces > 0)
}
