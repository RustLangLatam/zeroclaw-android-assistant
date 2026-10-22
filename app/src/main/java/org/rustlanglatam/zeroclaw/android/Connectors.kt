package org.rustlanglatam.zeroclaw.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Holds the constants of the Connectors screen, which lists the external accounts the assistant is connected to,
 * grouped by kind (currently email accounts). It lives outside the Settings root because the list can grow. Each
 * provider has its own setup guide and form, simpler for a known provider.
 */
object Connectors {
    /** Value of [Ui.connector] that opens the email provider picker. */
    const val PICK = "pick"

    /** Value of [Ui.connector] that opens the services form (model, search, videos, voice keys). */
    const val SERVICES = "services"

    /** The services form's groups: a title and its settings, each with its field label. */
    val GROUPS = listOf(
        R.string.connectors_model to listOf(Keys.Key.MODEL_PROVIDER to R.string.connectors_provider, Keys.Key.MODEL to R.string.connectors_model_name, Keys.Key.MODEL_KEY to R.string.connectors_api_key),
        R.string.connectors_web_search to listOf(Keys.Key.SEARCH_PROVIDER to R.string.connectors_provider, Keys.Key.SEARCH_KEY to R.string.connectors_api_key_or_url),
        R.string.connectors_videos to listOf(Keys.Key.YOUTUBE_KEY to R.string.connectors_api_key),
        R.string.connectors_natural_voice to listOf(
            Keys.Key.VOICE_URL to R.string.connectors_websocket_url, Keys.Key.VOICE_MODEL to R.string.connectors_model_name,
            Keys.Key.VOICE_KEY to R.string.connectors_api_key, Keys.Key.VOICE_NAME to R.string.connectors_voice_name,
            Keys.Key.VOICE_PER_MINUTE to R.string.connectors_usd_per_minute,
        ),
    )
}

@Composable
fun ConnectorsPlace(ui: MainActivity, onBack: () -> Unit) {
    Ui.tick
    Column(Modifier.fillMaxSize()) {
        Header(t(R.string.connectors_connectors), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(t(R.string.connectors_what_you_connect_with_me), Modifier.padding(start = 4.dp, top = 6.dp), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                t(R.string.connectors_i_read_each_account_whole_and),
                Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium, color = Ink.slate,
            )
            SectionLabel(t(R.string.connectors_services))
            // Computed here, not inside the panel's content, so a save (which bumps Ui.tick) redraws it.
            val model = if (Keys.modelReady) "${Keys[Keys.Key.MODEL_PROVIDER]} · ${Keys[Keys.Key.MODEL]}" else t(R.string.connectors_not_set_i_can_t_think)
            Panel {
                Item(
                    t(R.string.connectors_model),
                    model,
                    divider = false, onClick = { Ui.connector = Connectors.SERVICES },
                ) { Chevron() }
            }
            SectionLabel(t(R.string.connectors_email))
            Panel {
                EmailAccounts.accounts.forEach { a ->
                    Item(
                        a.label,
                        if (a.onPhone) t(R.string.connectors_also_in_your_email_app)
                        else t(R.string.connectors_only_in_ksandra_i_tell_you),
                        onClick = { Ui.connector = "account:${a.alias}" },
                    ) { Chevron() }
                }
                Item(t(R.string.connectors_add_an_email_account), t(R.string.connectors_gmail_yahoo_icloud_or_another),
                    divider = false, onClick = { Ui.connector = Connectors.PICK }) { Chevron() }
            }
        }
    }
}

/** Shows the sheet selected by [Ui.connector]: the provider picker, a provider's form, or a connected account. */
@Composable
fun ConnectorSheet(ui: MainActivity, which: String) {
    val close = { Ui.connector = null }
    when {
        which == Connectors.PICK -> Sheet(close) {
            Text(t(R.string.connectors_which_email_is_it), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(18.dp))
            Panel {
                EmailAccounts.Provider.entries.forEachIndexed { i, p ->
                    Item(p.label, if (p.other) t(R.string.connectors_any_other_with_its_imap_and) else null,
                        divider = i < EmailAccounts.Provider.entries.lastIndex, onClick = {
                            Cards.show(t(R.string.connectors_connect_x, p.yours), EmailAccounts.guide(p), emptyList())
                            Ui.connector = p.key
                        }) { Chevron() }
                }
            }
        }
        which == Connectors.SERVICES -> ServicesForm(ui)
        which.startsWith("account:") -> EmailAccounts.accounts.firstOrNull { it.alias == which.removePrefix("account:") }?.let { a ->
            Sheet(close) {
                Text(a.provider.label, style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text(a.address + " · " + if (a.onPhone) t(R.string.connectors_also_in_your_email_app_2) else t(R.string.connectors_only_in_ksandra),
                    style = MaterialTheme.typography.bodyMedium, color = Ink.slate)
                Spacer(Modifier.height(22.dp))
                GhostButton(t(R.string.connectors_disconnect), Modifier.fillMaxWidth()) { ui.connectEmail(a, remove = true) { }; close() }
            }
        } ?: close()
        else -> EmailAccounts.Provider.of(which)?.let { EmailForm(ui, it) } ?: close()
    }
}

/** Shows the connection form for [provider]: address and password, plus IMAP and SMTP servers for a generic provider. */
@Composable
private fun EmailForm(ui: MainActivity, provider: EmailAccounts.Provider) {
    var address by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var imap by remember { mutableStateOf("") }
    var smtp by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    Sheet({ if (!busy) Ui.connector = null }) {
        Text(provider.yours.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(
            when (provider) {
                EmailAccounts.Provider.OTHER -> t(R.string.connectors_your_address_its_password_and_your)
                EmailAccounts.Provider.ICLOUD -> t(R.string.connectors_your_icloud_address_and_the_app)
                else -> t(R.string.connectors_your_address_and_the_16_letter, provider.label)
            },
            style = MaterialTheme.typography.bodyMedium, color = Ink.slate,
        )
        Spacer(Modifier.height(18.dp))
        Field(address, t(R.string.connectors_your_address_example_com), keyboard = KeyboardType.Email) { address = it.trim(); problem = null }
        Spacer(Modifier.height(10.dp))
        Field(password, t(R.string.connectors_password), keyboard = KeyboardType.Password, hidden = true) { password = it; problem = null }
        if (provider.other) {
            Spacer(Modifier.height(10.dp))
            Field(imap, t(R.string.connectors_imap_server_imap_yourmail_com), keyboard = KeyboardType.Uri) { imap = it.trim(); problem = null }
            Spacer(Modifier.height(10.dp))
            Field(smtp, t(R.string.connectors_smtp_server_smtp_yourmail_com), keyboard = KeyboardType.Uri) { smtp = it.trim(); problem = null }
        }
        problem?.let { Spacer(Modifier.height(10.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink.alert) }
        Spacer(Modifier.height(20.dp))
        val ready = provider.valid(address, password) && (!provider.other || (imap.contains('.') && smtp.contains('.')))
        GlowButton(if (busy) t(R.string.connectors_checking) else t(R.string.connectors_connect), Modifier.fillMaxWidth(), enabled = !busy && ready) {
            busy = true
            val account = EmailAccounts.Account(
                EmailAccounts.accounts.firstOrNull { it.address == address.trim() }?.alias ?: EmailAccounts.alias(provider),
                provider, address.trim(), provider.password(password),
                imap = if (provider.other) imap else provider.imap, smtp = if (provider.other) smtp else provider.smtp,
            )
            ui.connectEmail(account) { error -> busy = false; problem = error }
        }
        Spacer(Modifier.height(10.dp))
        GhostButton(t(R.string.connectors_see_the_guide), Modifier.fillMaxWidth()) {
            Cards.show(t(R.string.connectors_connect_x, provider.yours), EmailAccounts.guide(provider), emptyList())
        }
    }
}

/**
 * Shows the services form: each group's settings, prefilled with the current value; a key is never shown back, and
 * left empty it keeps the one saved. Saving applies only what changed ([MainActivity.saveKeys]).
 */
@Composable
private fun ServicesForm(ui: MainActivity) {
    val values = remember { androidx.compose.runtime.mutableStateMapOf<Keys.Key, String>() }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    Sheet({ if (!busy) Ui.connector = null }) {
        Text(t(R.string.connectors_services), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(t(R.string.connectors_services_explained), style = MaterialTheme.typography.bodyMedium, color = Ink.slate)
        Connectors.GROUPS.forEach { (title, fields) ->
            SectionLabel(t(title))
            fields.forEach { (key, label) ->
                val shown = values[key] ?: if (key.secret) "" else Keys[key]
                val hint = if (key.secret && Keys[key].isNotBlank()) t(R.string.connectors_x_saved_leave_empty, t(label)) else t(label)
                Field(shown, hint, keyboard = if (key.secret) KeyboardType.Password else KeyboardType.Uri, hidden = key.secret) { values[key] = it; problem = null }
                Spacer(Modifier.height(10.dp))
            }
        }
        problem?.let { Spacer(Modifier.height(10.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink.alert) }
        Spacer(Modifier.height(20.dp))
        // An empty key field keeps the saved key; other fields left as shown are unchanged.
        val changed = values.filter { (k, v) -> if (k.secret) v.isNotBlank() else v.trim() != Keys[k] }
        GlowButton(t(R.string.connectors_save), Modifier.fillMaxWidth(), enabled = !busy && changed.isNotEmpty()) {
            busy = true
            ui.saveKeys(changed) { error -> busy = false; problem = error }
        }
    }
}
