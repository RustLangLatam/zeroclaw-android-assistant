package org.rustlanglatam.zeroclaw.android

import androidx.compose.foundation.clickable
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

    /**
     * The services form's groups: a title, the settings shown, and those under "Advanced" (prefilled, rarely changed),
     * each with its field label. The model's provider and name and the voice's name are chosen from lists.
     */
    val GROUPS = listOf(
        Triple(R.string.connectors_model,
            listOf(Keys.Key.MODEL_PROVIDER to R.string.connectors_provider, Keys.Key.MODEL to R.string.connectors_model_name,
                Keys.Key.MODEL_URL to R.string.connectors_server_url, Keys.Key.MODEL_KEY to R.string.connectors_api_key),
            listOf(Keys.Key.MODEL_INPUT_PER_MTOK to R.string.connectors_input_price, Keys.Key.MODEL_OUTPUT_PER_MTOK to R.string.connectors_output_price)),
        Triple(R.string.connectors_web_search,
            listOf(Keys.Key.SEARCH_PROVIDER to R.string.connectors_provider, Keys.Key.SEARCH_KEY to R.string.connectors_api_key_or_url), emptyList()),
        Triple(R.string.connectors_videos, listOf(Keys.Key.YOUTUBE_KEY to R.string.connectors_api_key), emptyList()),
        Triple(R.string.connectors_natural_voice,
            listOf(Keys.Key.VOICE_NAME to R.string.connectors_voice_name, Keys.Key.VOICE_KEY to R.string.connectors_api_key),
            listOf(Keys.Key.VOICE_URL to R.string.connectors_websocket_url, Keys.Key.VOICE_MODEL to R.string.connectors_model_name,
                Keys.Key.VOICE_PER_MINUTE to R.string.connectors_usd_per_minute)),
    )
}

/**
 * ZeroClaw's model catalog (`/api/config/catalog`), so no provider or model name is written in the app: the providers
 * it knows and each provider's models, live from the provider when it can. `null` until loaded or when the daemon is
 * not reachable; the form then falls back to text fields.
 */
object ModelCatalog {
    @Volatile var providers: List<io.github.rustlanglatam.zeroclaw.CatalogModelProvider>? = null
    private val models = java.util.concurrent.ConcurrentHashMap<String, io.github.rustlanglatam.zeroclaw.CatalogModels>()

    /** Words of model ids that are not chat models (embeddings, speech, images, moderation). */
    private val NOT_CHAT = Regex("embed|moderation|tts|whisper|transcri|guard|image|audio|realtime|rerank", RegexOption.IGNORE_CASE)

    private fun <T> call(context: android.content.Context, block: suspend (io.github.rustlanglatam.zeroclaw.Client) -> T): T? =
        AssistantService.token(context)?.let { token -> runCatching { kotlinx.coroutines.runBlocking { block(AssistantService.client(token)) } }.getOrNull() }

    /** Loads the providers once: remote ones only (the phone's own model is the offline answers' job). */
    fun load(context: android.content.Context) =
        providers ?: call(context) { it.config.catalog() }?.filterNot { it.local }?.also { providers = it }

    /** Loads [provider]'s chat models; `null` when the daemon or the provider can't be reached. */
    fun models(context: android.content.Context, provider: String) = models[provider]
        ?: call(context) { it.config.catalogModels(provider) }?.let { m -> m.copy(models = m.models.filterNot { NOT_CHAT.containsMatchIn(it) }) }
            ?.also { models[provider] = it }

    /**
     * Returns [providers] as options (name to display name) in the catalog's own order, most used first, after the
     * [current] choice and the build's provider; the custom gateway last.
     */
    fun ordered(providers: List<io.github.rustlanglatam.zeroclaw.CatalogModelProvider>, current: String, build: String = BuildConfig.MODEL_PROVIDER) =
        providers.sortedBy { when (it.name) { current -> 0; build -> 1; Keys.CUSTOM -> 3; else -> 2 } }.map { it.name to it.displayName }

    /** The name shown for [provider]: the catalog's display name when loaded. */
    fun label(provider: String) = providers?.firstOrNull { it.name == provider }?.displayName ?: provider

    /** US$ per million tokens from a per-token price string, as the price fields take it; `null` if unknown. */
    fun perMillion(price: String?) = price?.toDoubleOrNull()?.takeIf { it >= 0 }?.let { "%.4f".format(java.util.Locale.ROOT, it * 1e6).trimEnd('0').trimEnd('.') }
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
            val model = if (Keys.modelReady) "${ModelCatalog.label(Keys[Keys.Key.MODEL_PROVIDER])} · ${Keys[Keys.Key.MODEL]}" else if (Offline.wanted && Offline.ready) t(R.string.connectors_not_set_local_mode) else t(R.string.connectors_not_set_i_can_t_think)
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
 * left empty it keeps the one saved. The model's provider and name come from ZeroClaw's catalog and the voice from
 * [CloudVoice.VOICES], each with a search and "use what I typed"; prices come from the catalog when the provider
 * publishes them. Saving applies only what changed ([MainActivity.saveKeys]).
 */
@Composable
private fun ServicesForm(ui: MainActivity) {
    val values = remember { androidx.compose.runtime.mutableStateMapOf<Keys.Key, String>() }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(setOf<Int>()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    fun value(key: Keys.Key) = values[key] ?: Keys[key]
    val provider = value(Keys.Key.MODEL_PROVIDER)
    var providers by remember { mutableStateOf(ModelCatalog.providers) }
    var catalog by remember { mutableStateOf<io.github.rustlanglatam.zeroclaw.CatalogModels?>(null) }
    var loading by remember { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        providers = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ModelCatalog.load(context) }
        if (providers == null) loading = false
    }
    androidx.compose.runtime.LaunchedEffect(provider) {
        catalog = null
        loading = provider.isNotBlank() && provider != Keys.CUSTOM
        if (loading) catalog = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ModelCatalog.models(context, provider) }
        loading = false
    }
    @Composable
    fun Setting(key: Keys.Key, label: Int) {
        val change = { v: String -> values[key] = v; problem = null }
        when (key) {
            Keys.Key.MODEL_PROVIDER -> Combo(t(label), provider, ModelCatalog.label(provider), providers?.let { ModelCatalog.ordered(it, provider) }) { v ->
                if (v != provider) { change(v); values[Keys.Key.MODEL] = "" }
            }
            Keys.Key.MODEL -> Combo(t(label), value(key), value(key), catalog?.models?.map { it to it }, loading && providers != null) { v ->
                change(v)
                // The provider's published prices fill the cost fields; otherwise they stay for the owner.
                catalog?.pricing?.get(v)?.let { p ->
                    ModelCatalog.perMillion(p.prompt)?.let { values[Keys.Key.MODEL_INPUT_PER_MTOK] = it }
                    ModelCatalog.perMillion(p.completion)?.let { values[Keys.Key.MODEL_OUTPUT_PER_MTOK] = it }
                }
            }
            Keys.Key.MODEL_URL -> if (provider == Keys.CUSTOM || value(key).isNotBlank()) Field(value(key), t(label), keyboard = KeyboardType.Uri) { change(it.trim()) } else return
            Keys.Key.VOICE_NAME -> Combo(t(label), value(key), value(key), CloudVoice.VOICES.map { it to it.replaceFirstChar(Char::uppercase) }, onPick = change)
            else -> {
                val shown = values[key] ?: if (key.secret) "" else Keys[key]
                val hint = if (key.secret && Keys[key].isNotBlank()) t(R.string.connectors_x_saved_leave_empty, t(label)) else t(label)
                Field(shown, hint, keyboard = if (key.secret) KeyboardType.Password else KeyboardType.Uri, hidden = key.secret, onChange = change)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    Sheet({ if (!busy) Ui.connector = null }) {
        Text(t(R.string.connectors_services), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(t(R.string.connectors_services_explained), style = MaterialTheme.typography.bodyMedium, color = Ink.slate)
        Connectors.GROUPS.forEach { (title, shown, more) ->
            SectionLabel(t(title))
            shown.forEach { (key, label) -> Setting(key, label) }
            if (more.isNotEmpty()) {
                val open = title in advanced
                Text(t(R.string.connectors_advanced) + if (open) " ▴" else " ▾",
                    Modifier.clickable { advanced = if (open) advanced - title else advanced + title }.padding(start = 6.dp, top = 2.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.labelLarge, color = Ink.slate)
                if (open) more.forEach { (key, label) -> Setting(key, label) }
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

/**
 * A choice from [options] (value to label), shown as a row with [label] and the current [shown] text; tapping it opens
 * a search and the matching options, plus "use what I typed" for a value not listed. While [loading], a row saying
 * so; with no options (the catalog can't be reached), a plain text field.
 */
@Composable
private fun Combo(label: String, value: String, shown: String, options: List<Pair<String, String>>?, loading: Boolean = false, onPick: (String) -> Unit) {
    if (loading) return Panel { Item(label, t(R.string.connectors_loading), divider = false) }
    if (options == null) return Field(value, label, keyboard = KeyboardType.Uri) { onPick(it.trim()) }
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Panel {
        Item(label, shown.ifBlank { t(R.string.connectors_choose) }, divider = open, onClick = { open = !open; query = "" }) { Chevron() }
        if (open) {
            androidx.compose.foundation.layout.Box(Modifier.padding(12.dp)) {
                Field(query, t(R.string.connectors_search), keyboard = KeyboardType.Uri) { query = it }
            }
            val q = query.trim()
            val matches = options.filter { (v, l) -> q.isEmpty() || v.contains(q, true) || l.contains(q, true) }
            matches.take(MAX_SHOWN).forEach { (v, l) ->
                Item(l, onClick = { onPick(v); open = false }) { if (v == value) Mark(true) }
            }
            if (matches.size > MAX_SHOWN) Text(t(R.string.connectors_x_more_type_to_search, matches.size - MAX_SHOWN),
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = Ink.slate)
            if (q.isNotEmpty() && options.none { it.first == q }) Item(t(R.string.connectors_use_x, q), divider = false, onClick = { onPick(q); open = false })
        }
    }
}

/** Options listed at once in a [Combo]; the search narrows the rest. */
private const val MAX_SHOWN = 30
