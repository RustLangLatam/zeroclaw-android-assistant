package org.rustlanglatam.zeroclaw.android

/**
 * Resolves a message sender's identity against the owner's saved contacts (ADR-0019), never trusting the display name
 * in a notification, which the sender controls (anyone can call themselves "Ana" on Telegram).
 *
 * A sender counts as a contact only when a phone number, email address or contact link in the notification resolves
 * to a saved contact; the contact's own name is then used and its groups apply. Any other sender keeps the given name
 * with the [UNVERIFIED] suffix and belongs to no group. Pure Kotlin, unit-tested on the JVM; the contacts lookup is
 * done on Android.
 */
object Identity {
    /** Suffix appended to an unresolved sender's name; guarantees the result never matches a key of [Policy.groups]. */
    val UNVERIFIED get() = t(R.string.identity_unverified)

    /** The suffix in every language: a name saved while another language was active is still recognized. */
    private val suffixes get() = Lang.entries.map { t(it, R.string.identity_unverified) }

    /** Returns whether [name] carries the [UNVERIFIED] suffix, in any language. */
    fun unverified(name: String) = suffixes.any { it in name }

    /** Returns [name] without the [UNVERIFIED] suffix, in any language. */
    fun bare(name: String) = suffixes.fold(name) { n, s -> n.replace(s, "") }

    /** A notification datum that can identify a saved contact. */
    sealed interface Clue {
        data class Number(val number: String) : Clue
        data class Email(val address: String) : Clue
        data class Contact(val uri: String) : Clue
    }

    /**
     * Extracts a [Clue] from the sender's person URI [who] (`tel:`, `mailto:`, a contacts URI, or WhatsApp's
     * `<number>@s.whatsapp.net`), or from a [name] that is a phone number (an SMS from an unsaved number).
     */
    fun clue(who: String?, name: String): Clue? {
        val link = who?.trim().orEmpty()
        return when {
            link.startsWith("tel:") -> Clue.Number(link.removePrefix("tel:"))
            link.startsWith("mailto:") -> Clue.Email(link.removePrefix("mailto:"))
            link.startsWith("content://com.android.contacts/") -> Clue.Contact(link)
            Regex("""^(\d{6,15})@s\.whatsapp\.net$""").matches(link) -> Clue.Number("+" + link.substringBefore('@'))
            name.count(Char::isDigit) >= 6 && name.all { it.isDigit() || it in "+ ()-." } -> Clue.Number(name)
            else -> null
        }
    }

    /** Returns the saved [contact]'s name, or [name] marked as unverified when there is no contact. */
    fun named(name: String, contact: String?): String = contact ?: (name + UNVERIFIED)
}
