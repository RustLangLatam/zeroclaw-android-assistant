package org.rustlanglatam.zeroclaw.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Holds the owner's connected email accounts (Gmail, Yahoo, iCloud or any IMAP/SMTP server), covering the whole
 * mailbox rather than only what arrives as a notification.
 *
 * ZeroClaw reads each account through its own email channel, configured in observer mode with no peer group and no bound
 * agent, so the channel never changes a flag and never replies. The channel only contributes the `email_search` and
 * `email_read` tools (the account alias is their `channel` argument), which are granted to the owner's agent only.
 * Marking read, archiving, deleting and sending are app tools gated by [Policy] (ADR-0026). Each password is typed by
 * the owner in the app and is never exposed to the model. Pure Kotlin apart from file storage; unit-tested on the JVM.
 */
object EmailAccounts {
    /**
     * Describes an email provider: its IMAP and SMTP servers, its app-password format and the page where the owner
     * creates one. [other] marks the generic provider, which requires the servers to be entered by hand; the others need
     * only the address and the password.
     */
    enum class Provider(
        val key: String, private val brand: String?, val imap: String, val smtp: String, val smtpPort: Int,
        val link: String?, val other: Boolean = false,
    ) {
        GMAIL("gmail", "Gmail", "imap.gmail.com", "smtp.gmail.com", 465, "https://myaccount.google.com/apppasswords"),
        YAHOO("yahoo", "Yahoo", "imap.mail.yahoo.com", "smtp.mail.yahoo.com", 465, "https://login.yahoo.com/account/security"),
        ICLOUD("icloud", "iCloud", "imap.mail.me.com", "smtp.mail.me.com", 587, "https://account.apple.com"),
        OTHER("correo", null, "", "", 465, null, other = true),
        ;

        val label get() = brand ?: t(R.string.emailaccounts_other_email)

        /** Returns the localized phrase for "your <provider> email", or just "your email" for [OTHER]. */
        val yours get() = if (other) t(R.string.emailaccounts_your_email) else t(R.string.emailaccounts_your_x_email, label)

        /**
         * Normalizes a typed password. Google, Yahoo and Apple app passwords are 16 letters (Apple's grouped with dashes),
         * so whitespace is removed; any other server's password is only trimmed.
         */
        fun password(typed: String) = if (this == OTHER) typed.trim() else typed.filter { !it.isWhitespace() }

        fun valid(address: String, password: String) = ADDRESS.matches(address.trim()) &&
            password(password).let { if (this == OTHER) it.isNotEmpty() else it.count { c -> c.isLetter() } == 16 }

        companion object {
            fun of(key: String?) = entries.firstOrNull { it.key == key?.lowercase() || it.label.equals(key, ignoreCase = true) }
        }
    }

    data class Account(
        val alias: String, val provider: Provider, val address: String, val password: String,
        val imap: String = provider.imap, val imapPort: Int = 993, val smtp: String = provider.smtp, val smtpPort: Int = provider.smtpPort,
        /**
         * Whether an email app on the phone also shows this account (a message was seen both over IMAP and in a
         * notification); otherwise the account is reachable only through the assistant.
         */
        val onPhone: Boolean = false,
    ) {
        /** Display label, e.g. "Gmail · milton@gmail.com". */
        val label get() = "${provider.label} · $address"
    }

    private val ADDRESS = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")

    /** ZeroClaw tools granted to the owner's agent while at least one account is connected. */
    val TOOLS = listOf("email_search", "email_read")

    @Volatile var accounts: List<Account> = emptyList()
        private set
    private var file: File? = null

    fun load(dir: File) {
        file = File(dir, "email_accounts")
        accounts = runCatching { parse(file!!.readSealed()) }.getOrNull()
            // Migrates the legacy single-Gmail file (#87) into the first account.
            ?: runCatching { File(dir, "gmail").readSealed().lines().let { (a, p) -> listOf(Account("gmail", Provider.GMAIL, a, p)) } }.getOrNull()
            ?: emptyList()
    }

    /** Adds [account], replacing an existing one with the same address, or removes it when [remove] is set; persists the list. */
    fun save(account: Account, remove: Boolean = false) {
        // Replaced in place so the list keeps its order and each alias keeps its channel.
        accounts = if (remove) accounts.filter { it.address != account.address }
            else if (accounts.any { it.address == account.address }) accounts.map { if (it.address == account.address) account else it }
            else accounts + account
        file?.writeSealed(json(accounts))
        file?.parentFile?.let { File(it, "gmail").delete() }
    }

    /** Marks the account [alias] as [Account.onPhone] after one of its messages was seen both over IMAP and in a notification. */
    fun seenOnPhone(alias: String) {
        accounts.firstOrNull { it.alias == alias && !it.onPhone }?.let { save(it.copy(onPhone = true)) }
    }

    /** Returns a free ZeroClaw channel alias for [provider]: its key, numbered from the second account on ("gmail", "gmail2"). */
    fun alias(provider: Provider, taken: List<Account> = accounts): String =
        generateSequence(1) { it + 1 }.map { if (it == 1) provider.key else "${provider.key}$it" }.first { a -> taken.none { it.alias == a } }

    /**
     * Resolves the account a tool argument refers to, by alias, address or provider (only when unambiguous). A blank
     * argument resolves to the single account, if there is exactly one.
     */
    fun find(asked: String?, from: List<Account> = accounts): Account? {
        val a = asked?.trim()?.lowercase().orEmpty()
        if (a.isEmpty()) return from.singleOrNull()
        return from.firstOrNull { it.alias == a || it.address.lowercase() == a }
            ?: from.filter { it.provider.key == a || it.provider.label.lowercase() == a }.singleOrNull()
    }

    fun json(list: List<Account>) = JSONArray(list.map {
        JSONObject().put("alias", it.alias).put("provider", it.provider.key).put("address", it.address).put("password", it.password)
            .put("imap", it.imap).put("imapPort", it.imapPort).put("smtp", it.smtp).put("smtpPort", it.smtpPort).put("onPhone", it.onPhone)
    }).toString()

    fun parse(text: String): List<Account> = JSONArray(text).let { a ->
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let {
                val provider = Provider.of(it.getString("provider")) ?: Provider.OTHER
                Account(it.getString("alias"), provider, it.getString("address"), it.getString("password"),
                    it.getString("imap"), it.getInt("imapPort"), it.getString("smtp"), it.getInt("smtpPort"), it.optBoolean("onPhone"))
            }
        }
    }

    /** Renders one observer-mode `[channels.email.<alias>]` TOML section per account; empty for an empty list. */
    fun config(list: List<Account>): String = list.joinToString("\n") { account ->
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        """
            [channels.email.${account.alias}]
            enabled = true
            observer_mode = true
            imap_host = ${q(account.imap)}
            imap_port = ${account.imapPort}
            imap_folder = "INBOX"
            smtp_host = ${q(account.smtp)}
            smtp_port = ${account.smtpPort}
            smtp_tls = ${account.smtpPort == 465}
            username = ${q(account.address)}
            password = ${q(account.password)}
            from_address = ${q(account.address)}
        """.trimIndent() + "\n"
    }

    /** Returns whether the ZeroClaw config [text] contains exactly the channels of [list], with their current passwords. */
    fun inConfig(text: String, list: List<Account> = accounts) =
        Regex("""\[channels\.email\.""").findAll(text).count() == list.size &&
            list.all { "[channels.email.${it.alias}]" in text && "\"${it.password.replace("\\", "\\\\").replace("\"", "\\\"")}\"" in text }

    /**
     * Returns the Markdown setup guide for [provider], shown as a card: the purpose, numbered steps to create an app
     * password, a safety note and a link to the provider's page. Lines are newline-separated because a card keeps line breaks.
     */
    fun guide(provider: Provider): String {
        val why = t(R.string.emailaccounts_this_way_i_read_all_x, provider.yours)
        val back = t(R.string.emailaccounts_come_back_to_settings_connectors_email, provider.label)
        val safe = t(R.string.emailaccounts_only_you_type_that_password_in)
        val steps = when (provider) {
            Provider.GMAIL -> listOf(
                t(R.string.emailaccounts_open_app_passwords_the_link_below),
                t(R.string.emailaccounts_if_google_asks_you_to_turn),
                t(R.string.emailaccounts_type_a_name_for_example_ksandra),
                t(R.string.emailaccounts_copy_the_16_letters_it_shows),
            )
            Provider.YAHOO -> listOf(
                t(R.string.emailaccounts_open_yahoo_s_account_security_the),
                t(R.string.emailaccounts_find_generate_app_password_or_app),
                t(R.string.emailaccounts_type_a_name_for_example_ksandra_2),
                t(R.string.emailaccounts_copy_the_16_letters_it_shows),
            )
            Provider.ICLOUD -> listOf(
                t(R.string.emailaccounts_open_your_apple_account_the_link),
                t(R.string.emailaccounts_go_to_sign_in_and_security),
                t(R.string.emailaccounts_tap_type_a_name_for_example),
                t(R.string.emailaccounts_copy_the_password_it_shows_16),
            )
            Provider.OTHER -> listOf(
                t(R.string.emailaccounts_find_your_email_provider_s_imap),
                t(R.string.emailaccounts_if_your_provider_offers_app_passwords),
            )
        }
        return (listOf(why) + (steps + back).mapIndexed { i, s -> "${i + 1}. $s" } + safe +
            listOfNotNull(provider.link?.let { "- [${t(R.string.emailaccounts_open_the_page)}]($it)" })).joinToString("\n")
    }
}
