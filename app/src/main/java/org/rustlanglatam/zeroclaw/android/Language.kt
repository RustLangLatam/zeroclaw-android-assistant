package org.rustlanglatam.zeroclaw.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.util.Locale

/** Supported UI and assistant languages; [english] is the name used for the language in the persona prompt. */
enum class Lang(val tag: String, val english: String, val label: String) {
    ES("es", "Spanish", "español"),
    EN("en", "English", "English"),
}

/**
 * Resolves the active language (ADR-0024): the device language unless the owner chose one, falling back to Spanish
 * (the default brand language) when the device language is not supported. Backed by Compose state, so screens that
 * display text recompose when it changes. Pure apart from reading [Locale.getDefault].
 */
object Language {
    /** The owner's choice: a language tag, [PHONE] to follow the device, or `null` if never set (also follows the device). */
    var chosen by androidx.compose.runtime.mutableStateOf<String?>(null)

    const val PHONE = "phone"

    /** Invoked when the language changes, so text-to-speech and the speech recognizer can switch. */
    @Volatile var onChange: () -> Unit = {}

    val current: Lang
        get() = when (val c = chosen) {
            null, PHONE -> of(Locale.getDefault().language) ?: Lang.ES
            else -> of(c) ?: Lang.ES
        }

    /** The [Locale] for text-to-speech and the speech recognizer. */
    val locale: Locale get() = Locale.forLanguageTag(current.tag)

    /** Whether the active language follows the device language. */
    val followsPhone get() = chosen == null || chosen == PHONE

    fun of(tag: String) = Lang.entries.firstOrNull { it.tag == tag }

    /** Parses a free-form language choice (a language name in Spanish or English, a tag, or a reference to the device) into a tag or [PHONE]; `null` when not recognized. */
    fun named(value: String): String? {
        val v = Commands.normalize(value)
        return when {
            Regex(Lexicon["language.phone"]).containsMatchIn(v) -> PHONE
            // A bare tag counts only as the whole input: in Spanish "en" is also a preposition ("in").
            v == "en" || Regex(Lexicon["language.english"]).containsMatchIn(v) -> Lang.EN.tag
            v == "es" || Regex(Lexicon["language.spanish"]).containsMatchIn(v) -> Lang.ES.tag
            else -> null
        }
    }
}

/** Returns string resource [id] in the [Language.current] language, formatted with [args] when given. */
fun t(@androidx.annotation.StringRes id: Int, vararg args: Any?): String = Strings.get(id, args)

/** Returns string resource [id] in [lang] rather than the assistant's language, for text addressed to someone else. */
fun t(lang: Lang, @androidx.annotation.StringRes id: Int, vararg args: Any?): String = Strings.get(id, args, lang)

/** Returns string-array resource [id] in the [Language.current] language. */
fun ta(@androidx.annotation.ArrayRes id: Int): List<String> = Strings.array(id)

/** Returns plurals resource [id] for [count] in the [Language.current] language, formatted with [args]. */
fun tn(@androidx.annotation.PluralsRes id: Int, count: Int, vararg args: Any?): String = Strings.plural(id, count, args)

/**
 * Resolves string resources in the assistant's language, which can differ from the device locale (ADR-0029).
 *
 * On Android the resources come from a configuration context per [Lang], created once and cached. Outside Android
 * (JVM unit tests) there is no [android.content.res.Resources]; strings are then read from the resource XML files in
 * the directory named by the `strings.dir` system property, which the unit-test task sets.
 */
object Strings {
    @Volatile private var context: android.content.Context? = null
    private val resources = java.util.concurrent.ConcurrentHashMap<Lang, android.content.res.Resources>()

    /** Binds the application context; called once from [KsandraApplication.onCreate]. */
    fun init(context: android.content.Context) {
        this.context = context.applicationContext
    }

    fun get(id: Int, args: Array<out Any?>, lang: Lang = Language.current): String {
        val app = context ?: return Xml.get(lang, id, args)
        val res = resources(app, lang)
        return if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
    }

    fun plural(id: Int, count: Int, args: Array<out Any?>): String {
        val app = context ?: return Xml.plural(Language.current, id, count, args)
        return resources(app).getQuantityString(id, count, *args)
    }

    fun array(id: Int): List<String> {
        val app = context ?: return Xml.array(Language.current, id)
        return resources(app).getStringArray(id).toList()
    }

    private fun resources(app: android.content.Context, lang: Lang = Language.current) = resources.getOrPut(lang) {
        val config = android.content.res.Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang.tag)) }
        app.createConfigurationContext(config).resources
    }

    /** Reads `values/strings.xml` (English) and `values-es/strings.xml` for JVM unit tests. */
    private object Xml {
        private val dir by lazy { java.io.File(System.getProperty("strings.dir") ?: error("Strings: no Context and no strings.dir")) }
        private val tables = java.util.concurrent.ConcurrentHashMap<Lang, Map<String, String>>()
        private val names by lazy {
            Class.forName("${Strings::class.java.packageName}.R\$string").fields.associate { it.getInt(null) to it.name }
        }

        fun get(lang: Lang, id: Int, args: Array<out Any?>): String {
            val name = names[id] ?: error("Strings: unknown id $id")
            val table = tables.getOrPut(lang) { load(if (lang == Lang.EN) "values" else "values-${lang.tag}") }
            val raw = table[name] ?: load("values")[name] ?: error("Strings: no string $name")
            return if (args.isEmpty()) raw else String.format(Locale.ROOT, raw, *args)
        }

        private val pluralNames by lazy {
            Class.forName("${Strings::class.java.packageName}.R\$plurals").fields.associate { it.getInt(null) to it.name }
        }

        /** Spanish and English both use "one" for exactly 1 and "other" for any other count. */
        fun plural(lang: Lang, id: Int, count: Int, args: Array<out Any?>): String {
            val name = pluralNames[id] ?: error("Strings: unknown plurals id $id")
            val table = plurals.getOrPut(lang) { loadPlurals(if (lang == Lang.EN) "values" else "values-${lang.tag}") }
            val raw = table["$name/${if (count == 1) "one" else "other"}"] ?: error("Strings: no plurals $name")
            return String.format(Locale.ROOT, raw, *args)
        }

        private val plurals = java.util.concurrent.ConcurrentHashMap<Lang, Map<String, String>>()

        private val arrayNames by lazy {
            Class.forName("${Strings::class.java.packageName}.R\$array").fields.associate { it.getInt(null) to it.name }
        }

        fun array(lang: Lang, id: Int): List<String> {
            val name = arrayNames[id] ?: error("Strings: unknown array id $id")
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(java.io.File(dir, "${if (lang == Lang.EN) "values" else "values-${lang.tag}"}/strings.xml"))
            val arrays = doc.getElementsByTagName("string-array")
            val node = (0 until arrays.length).map { arrays.item(it) as org.w3c.dom.Element }.firstOrNull { it.getAttribute("name") == name }
                ?: error("Strings: no array $name")
            val items = node.getElementsByTagName("item")
            return (0 until items.length).map { unescape(items.item(it).textContent) }
        }

        private fun loadPlurals(folder: String): Map<String, String> {
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(java.io.File(dir, "$folder/strings.xml"))
            val nodes = doc.getElementsByTagName("item")
            return (0 until nodes.length).map { nodes.item(it) }.filter { it.parentNode.nodeName == "plurals" }.associate { node ->
                "${node.parentNode.attributes.getNamedItem("name").nodeValue}/${node.attributes.getNamedItem("quantity").nodeValue}" to unescape(node.textContent)
            }
        }

        private fun load(folder: String): Map<String, String> {
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(java.io.File(dir, "$folder/strings.xml"))
            val nodes = doc.getElementsByTagName("string")
            return (0 until nodes.length).associate { i ->
                val node = nodes.item(i)
                node.attributes.getNamedItem("name").nodeValue to unescape(node.textContent)
            }
        }

        /** Applies aapt's string rules: surrounding double quotes kept verbatim, and backslash escapes. */
        private fun unescape(text: String): String {
            val s = if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) text.substring(1, text.length - 1) else text
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    out.append(when (val n = s[i + 1]) { 'n' -> '\n'; 't' -> '\t'; else -> n })
                    i += 2
                } else {
                    out.append(c); i++
                }
            }
            return out.toString()
        }
    }
}
