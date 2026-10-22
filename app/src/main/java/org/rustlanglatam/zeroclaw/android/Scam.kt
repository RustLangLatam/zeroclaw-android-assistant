package org.rustlanglatam.zeroclaw.android

import android.app.Notification
import android.app.NotificationManager
import android.content.Context

/**
 * Possible-scam warnings, decided in code: a message or email that asks for money or credentials, carries a link or a
 * phone number, and either asks for codes or details or pushes money with urgency ([looks]). The assistant only warns
 * (a notice and an Activity entry); it never answers the sender, opens the link or acts. The wording says "looks
 * like", never "is". Words per language live in `lexicon.json` (`scam.*`). Pure apart from [warn].
 */
object Scam {
    enum class Sign { UNKNOWN_SENDER, URGENCY, LINK, PHONE, MONEY, CREDENTIALS }

    private val link = Regex("""https?://|www\.|\b(bit\.ly|tinyurl\.com|t\.co)/|\b[a-z0-9-]+\.(com|net|org|xyz|top|info|link|site|online|shop|click|co|ru|cn)(/|\b)""")
    private val phone = Regex("""\+?\d[\d \-]{8,}\d""")

    /** Returns the signs found in [text] from a sender the owner [knows] (a verified family or work contact). */
    fun signs(text: String, knows: Boolean): Set<Sign> {
        val raw = text.lowercase()
        val words = Commands.normalize(text)
        fun has(key: String) = Regex("\\b(" + Lexicon[key] + ")").containsMatchIn(words)
        return buildSet {
            if (!knows) add(Sign.UNKNOWN_SENDER)
            if (has("scam.urgency")) add(Sign.URGENCY)
            if (link.containsMatchIn(raw)) add(Sign.LINK)
            if (phone.containsMatchIn(raw)) add(Sign.PHONE)
            if (has("scam.money") || '€' in raw || '$' in raw) add(Sign.MONEY)
            if (has("scam.credentials")) add(Sign.CREDENTIALS)
        }
    }

    /**
     * Whether [signs] look like a scam: a way out (link or phone) and either a request for codes or details, or money
     * under pressure. A plain promotion (a link and a price, no pressure) does not; who sent it is reported, not decisive.
     */
    fun looks(signs: Set<Sign>): Boolean =
        (Sign.LINK in signs || Sign.PHONE in signs) &&
            (Sign.CREDENTIALS in signs || (Sign.MONEY in signs && Sign.URGENCY in signs))

    /** The signs in the owner's words, in the assistant's language. */
    fun why(signs: Set<Sign>): String = signs.sortedBy { it.ordinal }.joinToString(", ") {
        when (it) {
            Sign.UNKNOWN_SENDER -> t(R.string.scam_unknown_sender)
            Sign.URGENCY -> t(R.string.scam_urgency)
            Sign.LINK -> t(R.string.scam_link)
            Sign.PHONE -> t(R.string.scam_phone)
            Sign.MONEY -> t(R.string.scam_money)
            Sign.CREDENTIALS -> t(R.string.scam_credentials)
        }
    }

    /**
     * Checks a message or email from [sender] (known or not) and, when it looks like a scam, records it in Activity and
     * tells the owner. Returns whether it warned.
     */
    fun warn(context: Context, sender: String, text: String, knows: Boolean): Boolean {
        val signs = signs(text, knows)
        if (!looks(signs)) return false
        val why = why(signs)
        Guard.journal.add(Principal.THIRD_PARTY, "message from $sender", "possible scam", why)
        val said = t(R.string.scam_notice, Identity.bare(sender), why)
        Floating.notice(context, said)
        context.getSystemService(NotificationManager::class.java).notify(
            ID + Math.floorMod((sender + text).hashCode(), 1000),
            Notification.Builder(context, Chime.channel(context, "scam", t(R.string.scam_channel), NotificationManager.IMPORTANCE_HIGH))
                .setSmallIcon(R.drawable.ic_mark).setContentTitle(t(R.string.scam_title)).setContentText(said)
                .setStyle(Notification.BigTextStyle().bigText(said)).setAutoCancel(true).build(),
        )
        return true
    }

    /** A notification id range no other notification uses. */
    private const val ID = 401_000
}
