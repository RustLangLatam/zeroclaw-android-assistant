package org.rustlanglatam.zeroclaw.android

import android.content.Context
import org.json.JSONObject

/**
 * Keyword lists and regular-expression fragments used to interpret the owner's input in the supported languages,
 * kept as data in `assets/lexicon.json` rather than in code.
 *
 * Each entry is a string, usually a regex alternation; callers build their patterns from it. On Android the file is read
 * from the APK's assets once [init] has been called. On the JVM (unit tests) it is read from the path in the
 * `lexicon.file` system property, which the unit-test task sets.
 */
object Lexicon {
    @Volatile private var context: Context? = null

    /** Binds the application context; called once from [KsandraApplication.onCreate]. */
    fun init(context: Context) {
        this.context = context.applicationContext
    }

    private val table: Map<String, String> by lazy {
        val text = context?.assets?.open(FILE)?.bufferedReader()?.use { it.readText() }
            ?: java.io.File(System.getProperty("lexicon.file") ?: error("Lexicon: no Context and no lexicon.file")).readText()
        val json = JSONObject(text)
        json.keys().asSequence().associateWith { json.getString(it) }
    }

    /** Returns the entry for [key]; fails fast on an unknown key. */
    operator fun get(key: String): String = table[key] ?: error("Lexicon: no entry $key")

    /** Returns the entry for [key] as a list of words, for entries written as a `|`-separated list. */
    fun words(key: String): List<String> = get(key).split("|")

    private const val FILE = "lexicon.json"
}
