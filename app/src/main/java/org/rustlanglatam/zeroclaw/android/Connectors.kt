package org.rustlanglatam.zeroclaw.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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

    /** Value of [Ui.connector] that opens the integrations form (other apps and services: web search, videos). */
    const val APPS = "apps"

    /** The groups of the models form (the assistant's own thinking, sight and voice); the rest are integrations. */
    val MODEL_GROUPS = setOf(R.string.connectors_model, R.string.connectors_photos, R.string.connectors_natural_voice)

    /**
     * The services form's groups: a title, the settings shown, and those under "Advanced" (prefilled, rarely changed),
     * each with its field label. The model's provider and name and the voice service and voice are chosen from lists.
     */
    /**
     * The fields each group needs once it is in use: a model provider needs its model and key (and a custom gateway its
     * URL), a photos provider its model and key, and a voice service its voice, speech model and key. Search keys
     * stay optional: some search providers need none. [value] gives each field's current value.
     */
    fun required(value: (Keys.Key) -> String): List<Keys.Key> = buildList {
        if (value(Keys.Key.MODEL_PROVIDER).isNotBlank()) {
            addAll(listOf(Keys.Key.MODEL, Keys.Key.MODEL_KEY))
            if (value(Keys.Key.MODEL_PROVIDER) == Keys.CUSTOM) add(Keys.Key.MODEL_URL)
        }
        if (value(Keys.Key.VISION_PROVIDER).isNotBlank()) addAll(listOf(Keys.Key.VISION_MODEL, Keys.Key.VISION_KEY))
        if (value(Keys.Key.VOICE_URL).isNotBlank()) addAll(listOf(Keys.Key.VOICE_NAME, Keys.Key.VOICE_MODEL, Keys.Key.VOICE_KEY))
    }

    /** Whether the group titled [title] is in use with its saved settings: the main model, photos or the natural voice. */
    fun active(title: Int) = when (title) {
        R.string.connectors_model -> Keys.modelReady
        R.string.connectors_photos -> Keys.visionReady
        R.string.connectors_natural_voice -> CloudVoice.configured && Keys[Keys.Key.VOICE_NAME].isNotBlank()
        else -> false
    }

    /** Each key and the provider (or voice service) it belongs to: a saved key stops counting once that changes. */
    val KEY_OF = mapOf(Keys.Key.MODEL_KEY to Keys.Key.MODEL_PROVIDER, Keys.Key.VISION_KEY to Keys.Key.VISION_PROVIDER, Keys.Key.VOICE_KEY to Keys.Key.VOICE_URL)

    val GROUPS = listOf(
        Triple(R.string.connectors_model,
            listOf(Keys.Key.MODEL_PROVIDER to R.string.connectors_provider, Keys.Key.MODEL to R.string.connectors_model_name,
                Keys.Key.MODEL_URL to R.string.connectors_server_url, Keys.Key.MODEL_KEY to R.string.connectors_api_key),
            listOf(Keys.Key.MODEL_INPUT_PER_MTOK to R.string.connectors_input_price, Keys.Key.MODEL_OUTPUT_PER_MTOK to R.string.connectors_output_price)),
        Triple(R.string.connectors_photos,
            listOf(Keys.Key.VISION_PROVIDER to R.string.connectors_provider, Keys.Key.VISION_MODEL to R.string.connectors_model_name,
                Keys.Key.VISION_KEY to R.string.connectors_api_key), emptyList()),
        Triple(R.string.connectors_web_search,
            listOf(Keys.Key.SEARCH_PROVIDER to R.string.connectors_provider, Keys.Key.SEARCH_KEY to R.string.connectors_api_key_or_url), emptyList()),
        Triple(R.string.connectors_videos, listOf(Keys.Key.YOUTUBE_KEY to R.string.connectors_api_key), emptyList()),
        Triple(R.string.connectors_natural_voice,
            listOf(Keys.Key.VOICE_URL to R.string.connectors_voice_provider, Keys.Key.VOICE_NAME to R.string.connectors_voice_name,
                Keys.Key.VOICE_KEY to R.string.connectors_api_key),
            listOf(Keys.Key.VOICE_MODEL to R.string.connectors_model_name, Keys.Key.VOICE_PER_MINUTE to R.string.connectors_usd_per_minute)),
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

    /** The recommended models per provider and use ("chat", "vision"), from `assets/models.json` (ADR-0033). */
    @Volatile private var recommended: org.json.JSONObject? = null

    /** Returns [provider]'s recommended model for [use], or `null` when the file names none. */
    fun recommended(context: android.content.Context, provider: String, use: String): String? =
        (recommended ?: runCatching { org.json.JSONObject(context.assets.open("models.json").bufferedReader().use { it.readText() }).getJSONObject("providers") }
            .getOrDefault(org.json.JSONObject()).also { recommended = it }).optJSONObject(provider)?.optString(use)?.takeIf { it.isNotBlank() }

    /**
     * The model proposed when a provider is chosen: the [recommended] one if the provider's list has it (or there is no
     * list to check), else the list's first; `null` when there is neither.
     */
    fun pick(recommended: String?, models: List<String>?): String? =
        recommended?.takeIf { models.isNullOrEmpty() || it in models } ?: models?.firstOrNull()

    /** The name shown for [provider]: the catalog's display name when loaded. */
    fun label(provider: String) = providers?.firstOrNull { it.name == provider }?.displayName ?: provider

    /** US$ per million tokens from a per-token price string, as the price fields take it; `null` if unknown. */
    fun perMillion(price: String?) = price?.toDoubleOrNull()?.takeIf { it >= 0 }?.let { "%.4f".format(java.util.Locale.ROOT, it * 1e6).trimEnd('0').trimEnd('.') }
}

/**
 * Checks a key just entered with its own provider before it is saved: one authenticated read (the provider's model
 * list) at the address the data files give for it (`check` in `assets/models.json` and `assets/voices.json`, ADR-0033).
 * Keys already saved are not checked again.
 */
object KeyCheck {
    enum class Result { VALID, INVALID, UNKNOWN }

    /** The `check` entry for [key] given the form's [value]s: its provider's, or its voice service's; `null` when none. */
    private fun spec(context: android.content.Context, key: Keys.Key, value: (Keys.Key) -> String): org.json.JSONObject? = runCatching {
        fun read(file: String) = org.json.JSONObject(context.assets.open(file).bufferedReader().use { it.readText() })
        when (key) {
            Keys.Key.MODEL_KEY, Keys.Key.VISION_KEY ->
                read("models.json").getJSONObject("providers").optJSONObject(value(Connectors.KEY_OF.getValue(key)))?.optJSONObject("check")
            Keys.Key.VOICE_KEY -> read("voices.json").getJSONArray("providers").let { a -> (0 until a.length()).map(a::getJSONObject) }
                .firstOrNull { it.getString("url") == value(Keys.Key.VOICE_URL).trim() }?.optJSONObject("check")
            else -> null
        }
    }.getOrNull()

    /** Whether [key] can be checked at all (its provider has a `check` entry). */
    fun checkable(context: android.content.Context, key: Keys.Key, value: (Keys.Key) -> String) = spec(context, key, value) != null

    /**
     * Checks [secret] for [key]: [Result.VALID] on success, [Result.INVALID] when the provider refuses it (400, 401,
     * 403), [Result.UNKNOWN] when it cannot tell (no entry, offline, another error). Blocking; call off the main thread.
     */
    fun check(context: android.content.Context, key: Keys.Key, secret: String, value: (Keys.Key) -> String): Result {
        val spec = spec(context, key, value) ?: return Result.UNKNOWN
        val url = spec.optString("url").takeIf { it.startsWith("https://") } ?: return Result.UNKNOWN
        return runCatching {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            val auth = spec.optString("auth", "bearer")
            if (auth == "bearer") c.setRequestProperty("Authorization", "Bearer ${secret.trim()}") else c.setRequestProperty(auth, secret.trim())
            spec.optJSONObject("headers")?.let { h -> h.keys().forEach { c.setRequestProperty(it, h.getString(it)) } }
            val code = c.responseCode
            c.disconnect()
            when (code) { in 200..299 -> Result.VALID; 400, 401, 403 -> Result.INVALID; else -> Result.UNKNOWN }
        }.getOrDefault(Result.UNKNOWN)
    }
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
            // The providers' display names come from ZeroClaw's catalog, loaded once.
            val context = androidx.compose.ui.platform.LocalContext.current
            var named by remember { mutableStateOf(ModelCatalog.providers != null) }
            androidx.compose.runtime.LaunchedEffect(Unit) { if (!named) named = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ModelCatalog.load(context) } != null }
            SectionLabel(t(R.string.connectors_models))
            // Computed here, not inside the panel's content, so a save (which bumps Ui.tick) redraws it.
            val model = if (Keys.modelReady) "${ModelCatalog.label(Keys[Keys.Key.MODEL_PROVIDER])} · ${Keys[Keys.Key.MODEL]}" else if (Offline.wanted && Offline.ready) t(R.string.connectors_not_set_local_mode) else t(R.string.connectors_not_set_i_can_t_think)
            val photos = if (Keys.visionReady) "${ModelCatalog.label(Keys[Keys.Key.VISION_PROVIDER])} · ${Keys[Keys.Key.VISION_MODEL]}" else t(R.string.connectors_not_set)
            val voice = if (Connectors.active(R.string.connectors_natural_voice)) Keys[Keys.Key.VOICE_NAME].replaceFirstChar(Char::uppercase) else t(R.string.connectors_not_set)
            val models = { Ui.connector = Connectors.SERVICES }
            Panel {
                Item(t(R.string.connectors_model), model, onClick = models) { Chevron() }
                Item(t(R.string.connectors_photos), photos, onClick = models) { Chevron() }
                Item(t(R.string.connectors_natural_voice), voice, divider = false, onClick = models) { Chevron() }
            }
            SectionLabel(t(R.string.connectors_apps))
            val apps = { Ui.connector = Connectors.APPS }
            Panel {
                Item(t(R.string.connectors_web_search), Keys[Keys.Key.SEARCH_PROVIDER].ifBlank { t(R.string.connectors_not_set) }, onClick = apps) { Chevron() }
                Item(t(R.string.connectors_videos), t(if (Keys[Keys.Key.YOUTUBE_KEY].isNotBlank()) R.string.connectors_key_saved else R.string.connectors_not_set), divider = false, onClick = apps) { Chevron() }
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
        which == Connectors.SERVICES -> ServicesForm(ui, models = true)
        which == Connectors.APPS -> ServicesForm(ui, models = false)
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
 * left empty it keeps the one saved. The model's provider and name come from ZeroClaw's catalog, the voice service
 * and voice from `assets/voices.json` ([CloudVoice.providers]) or the owner's own URL, each with a search; prices come
 * from the catalog when the provider publishes them. Saving applies only what changed ([MainActivity.saveKeys]).
 */
@Composable
private fun ServicesForm(ui: MainActivity, models: Boolean) {
    val values = remember { androidx.compose.runtime.mutableStateMapOf<Keys.Key, String>() }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(setOf<Int>()) }
    // Saved keys the owner chose to replace: their field is shown instead of "saved".
    var changing by remember { mutableStateOf(setOf<Keys.Key>()) }
    var checking by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    fun value(key: Keys.Key) = values[key] ?: Keys[key]
    val provider = value(Keys.Key.MODEL_PROVIDER)
    var providers by remember { mutableStateOf(ModelCatalog.providers) }
    var catalog by remember { mutableStateOf<io.github.rustlanglatam.zeroclaw.CatalogModels?>(null) }
    var loading by remember { mutableStateOf(true) }
    val voices = remember { CloudVoice.providers(context) }
    val voiceService = CloudVoice.providerOf(value(Keys.Key.VOICE_URL), voices)
    // A service not listed is entered by its URL; chosen as "Other", or already saved that way.
    var ownVoiceUrl by remember { mutableStateOf(voiceService == null && Keys[Keys.Key.VOICE_URL].isNotBlank()) }
    fun pickModel(v: String) {
        values[Keys.Key.MODEL] = v; problem = null
        // The provider's published prices fill the cost fields; otherwise they stay for the owner.
        catalog?.pricing?.get(v)?.let { p ->
            ModelCatalog.perMillion(p.prompt)?.let { values[Keys.Key.MODEL_INPUT_PER_MTOK] = it }
            ModelCatalog.perMillion(p.completion)?.let { values[Keys.Key.MODEL_OUTPUT_PER_MTOK] = it }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        providers = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ModelCatalog.load(context) }
        if (providers == null) loading = false
    }
    // The photos model's choices, from its own provider's live list.
    val visionProvider = value(Keys.Key.VISION_PROVIDER)
    var visionModels by remember { mutableStateOf<List<String>?>(null) }
    androidx.compose.runtime.LaunchedEffect(visionProvider) {
        visionModels = null
        if (visionProvider.isBlank()) return@LaunchedEffect
        visionModels = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ModelCatalog.models(context, visionProvider) }?.models
        // A provider just chosen brings its model for pictures.
        if (value(Keys.Key.VISION_MODEL).isBlank()) ModelCatalog.pick(ModelCatalog.recommended(context, visionProvider, "vision"), visionModels)?.let { values[Keys.Key.VISION_MODEL] = it }
    }
    androidx.compose.runtime.LaunchedEffect(provider) {
        catalog = null
        loading = provider.isNotBlank() && provider != Keys.CUSTOM
        if (loading) catalog = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ModelCatalog.models(context, provider) }
        loading = false
        // A provider just chosen brings its model, with its prices when the provider publishes them.
        if (provider.isNotBlank() && value(Keys.Key.MODEL).isBlank()) ModelCatalog.pick(ModelCatalog.recommended(context, provider, "chat"), catalog?.models)?.let { pickModel(it) }
    }
    /** Whether [key]'s saved value still applies: set, and its provider unchanged. */
    fun kept(key: Keys.Key) = Keys[key].isNotBlank() && Connectors.KEY_OF[key]?.let { value(it) == Keys[it] } != false
    /**
     * The required fields left empty in the groups being changed now: marked, and saving waits. A group the owner did not
     * touch never holds back another's save (a voice service set by the build but with no key must not block photos).
     */
    fun lacking(): List<Keys.Key> {
        val touched = Connectors.GROUPS.map { (_, shown, more) -> (shown + more).map { it.first } }.filter { g -> g.any { it in values } }.flatten()
        return Connectors.required(::value).filter { it in touched && if (it.secret) values[it].isNullOrBlank() && !kept(it) else value(it).isBlank() }
    }
    @Composable
    fun Setting(key: Keys.Key, label: Int) {
        val change = { v: String -> values[key] = v; problem = null }
        when (key) {
            Keys.Key.MODEL_PROVIDER -> Combo(t(label), provider, ModelCatalog.label(provider), providers?.let { ModelCatalog.ordered(it, provider) }) { v ->
                if (v != provider) { change(v); values[Keys.Key.MODEL] = "" }
            }
            Keys.Key.MODEL -> Combo(t(label), value(key), value(key), catalog?.models?.map { it to it }, loading && providers != null, alert = key in lacking(), onPick = ::pickModel)
            Keys.Key.VISION_PROVIDER -> Combo(t(label), value(key), ModelCatalog.label(value(key)),
                providers?.filter { ModelCatalog.recommended(context, it.name, "vision") != null }?.let { ModelCatalog.ordered(it, value(key)) }) { v ->
                if (v != value(key)) { change(v); values[Keys.Key.VISION_MODEL] = "" }
            }
            Keys.Key.VISION_MODEL -> Combo(t(label), value(key), value(key), visionModels?.map { it to it }, alert = key in lacking(), onPick = change)
            Keys.Key.MODEL_URL -> if (provider == Keys.CUSTOM || value(key).isNotBlank()) Field(value(key), t(label), keyboard = KeyboardType.Uri) { change(it.trim()) } else return
            Keys.Key.VOICE_URL -> {
                val options = voices.map { it.url to it.name } + (OWN_URL to t(R.string.connectors_other_my_own_url))
                val shown = when { ownVoiceUrl -> t(R.string.connectors_other_my_own_url); else -> voiceService?.name.orEmpty() }
                Combo(t(label), if (ownVoiceUrl) OWN_URL else value(key), shown, options, other = false) { v ->
                    ownVoiceUrl = v == OWN_URL
                    CloudVoice.providerOf(v, voices)?.let { p ->
                        // A listed service brings its model, price and, if the current one isn't its, its first voice.
                        change(p.url); values[Keys.Key.VOICE_MODEL] = p.model; values[Keys.Key.VOICE_PER_MINUTE] = p.perMinute
                        if (value(Keys.Key.VOICE_NAME) !in p.voices) values[Keys.Key.VOICE_NAME] = p.voices.first()
                    } ?: change("")
                }
                if (ownVoiceUrl) { Spacer(Modifier.height(10.dp)); Field(value(key), t(R.string.connectors_websocket_url), keyboard = KeyboardType.Uri) { change(it.trim()) } }
            }
            Keys.Key.VOICE_NAME -> Combo(t(label), value(key), value(key).replaceFirstChar(Char::uppercase),
                voiceService?.voices?.map { it to it.replaceFirstChar(Char::uppercase) }, alert = key in lacking(), onPick = change)
            else -> {
                val shown = values[key] ?: if (key.secret) "" else Keys[key]
                // A key says whose it is: the model provider's name, or the voice service's host.
                val whose = when (key) {
                    Keys.Key.MODEL_KEY -> provider.takeIf { it.isNotBlank() }?.let(ModelCatalog::label)
                    Keys.Key.VISION_KEY -> value(Keys.Key.VISION_PROVIDER).takeIf { it.isNotBlank() }?.let(ModelCatalog::label)
                    Keys.Key.VOICE_KEY -> voiceService?.name ?: voiceHost(value(Keys.Key.VOICE_URL))
                    else -> null
                }
                val named = whose?.let { t(R.string.connectors_api_key_of_x, it) } ?: t(label)
                val empty = key in lacking()
                // A saved key reads as saved, with a way to replace it.
                if (key.secret && kept(key) && key !in changing) {
                    Panel { Item(named, t(R.string.connectors_key_saved), divider = false) {
                        Text(t(R.string.connectors_change), Modifier.clickable { changing = changing + key }.padding(8.dp), style = MaterialTheme.typography.labelLarge, color = Ink.warmth)
                    } }
                } else {
                    val hint = when {
                        empty -> t(R.string.connectors_x_empty, named)
                        key.secret && kept(key) -> t(R.string.connectors_x_new, named)
                        else -> named
                    }
                    Field(shown, hint, keyboard = if (key.secret) KeyboardType.Password else KeyboardType.Uri, hidden = key.secret, alert = empty, onChange = change)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    Sheet({ if (!busy) Ui.connector = null }) {
        Text(t(if (models) R.string.connectors_models else R.string.connectors_apps), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(t(R.string.connectors_services_explained), style = MaterialTheme.typography.bodyMedium, color = Ink.slate)
        Connectors.GROUPS.filter { (it.first in Connectors.MODEL_GROUPS) == models }.forEach { (title, shown, more) ->
            // A group whose saved settings are complete is the one in use: marked so at a glance.
            androidx.compose.foundation.layout.Row(Modifier.padding(top = 30.dp, bottom = 12.dp, start = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Eyebrow(t(title))
                if (Connectors.active(title)) {
                    Spacer(Modifier.width(12.dp)); Light(Ink.ok); Spacer(Modifier.width(6.dp))
                    Eyebrow(t(R.string.connectors_active), color = Ink.ok)
                }
            }
            shown.forEach { (key, label) -> Setting(key, label) }
            if (more.isNotEmpty()) {
                val open = title in advanced
                Text(t(R.string.connectors_advanced) + if (open) " ▴" else " ▾",
                    Modifier.clickable { advanced = if (open) advanced - title else advanced + title }.padding(start = 6.dp, top = 2.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.labelLarge, color = Ink.slate)
                if (open) more.forEach { (key, label) -> Setting(key, label) }
            }
        }
        if (checking) { Spacer(Modifier.height(10.dp)); Text(t(R.string.connectors_checking_keys), style = MaterialTheme.typography.bodyMedium, color = Ink.slate) }
        problem?.let { Spacer(Modifier.height(10.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink.alert) }
        Spacer(Modifier.height(20.dp))
        // An empty key field keeps the saved key; other fields left as shown are unchanged.
        val changed = values.filter { (k, v) -> if (k.secret) v.isNotBlank() else v.trim() != Keys[k] }
        GlowButton(t(R.string.connectors_save), Modifier.fillMaxWidth(), enabled = !busy && changed.isNotEmpty()) {
            if (lacking().isNotEmpty()) { problem = t(R.string.connectors_fill_the_marked); return@GlowButton }
            busy = true; checking = true; problem = null
            val snapshot = Keys.Key.entries.associateWith { value(it) }
            kotlin.concurrent.thread(name = "key-check") {
                // Only keys entered now are checked; one refused is dropped with the rest of its group's changes, so a
                // new provider is never saved with the old provider's key.
                val refused = changed.keys.filter { it.secret && it in Connectors.KEY_OF }
                    .filter { KeyCheck.check(context, it, changed.getValue(it), snapshot::getValue) == KeyCheck.Result.INVALID }
                val dropped = Connectors.GROUPS.map { (_, shown, more) -> (shown + more).map { it.first } }.filter { g -> g.any { it in refused } }.flatten().toSet()
                val keep = changed.filterKeys { it !in dropped }
                val whose = refused.joinToString(", ") { k -> ModelCatalog.label(snapshot.getValue(Connectors.KEY_OF.getValue(k))).let { if (k == Keys.Key.VOICE_KEY) CloudVoice.providerOf(it, voices)?.name ?: voiceHost(it) ?: it else it } }
                ui.runOnUiThread {
                    checking = false
                    refused.forEach { values.remove(it) }
                    val note = if (refused.isEmpty()) null else t(R.string.connectors_key_refused_x, whose)
                    if (keep.isEmpty()) { busy = false; problem = t(R.string.connectors_key_refused_only_x, whose); return@runOnUiThread }
                    ui.saveKeys(keep) { error -> busy = false; problem = error ?: note; if (error == null && note != null) ui.remark(note) }
                }
            }
        }
    }
}

/**
 * A choice from [options] (value to label), shown as a row with [label] and the current [shown] text; tapping it opens
 * a search and the matching options, plus "use what I typed" for a value not listed when [other]. While [loading], a row saying
 * so; with no options (the catalog can't be reached), a plain text field.
 */
@Composable
private fun Combo(label: String, value: String, shown: String, options: List<Pair<String, String>>?, loading: Boolean = false, other: Boolean = true, alert: Boolean = false, onPick: (String) -> Unit) {
    if (loading) return Panel { Item(label, t(R.string.connectors_loading), divider = false) }
    if (options == null) return Field(value, if (alert) t(R.string.connectors_x_empty, label) else label, keyboard = KeyboardType.Uri, alert = alert) { onPick(it.trim()) }
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Panel {
        Item(label, shown.ifBlank { t(if (alert) R.string.connectors_choose_needed else R.string.connectors_choose) }, divider = open, onClick = { open = !open; query = "" }) { Chevron() }
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
            if (other && q.isNotEmpty() && options.none { it.first == q }) Item(t(R.string.connectors_use_x, q), divider = false, onClick = { onPick(q); open = false })
        }
    }
}

/** Returns the host of the voice service's [url] (`wss://host/...`), or `null` when none is set. */
fun voiceHost(url: String) = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() }

/** The voice provider option for a service entered by its URL. */
private const val OWN_URL = "own-url"

/** Options listed at once in a [Combo]; the search narrows the rest. */
private const val MAX_SHOWN = 30
