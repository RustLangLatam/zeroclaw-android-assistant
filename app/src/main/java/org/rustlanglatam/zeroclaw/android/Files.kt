package org.rustlanglatam.zeroclaw.android

/**
 * Matches documents against the owner's description (e.g. "the credit budget document"): extracts the significant
 * terms, labels each file's origin (downloads, WhatsApp, …) and scores name matches. Pure Kotlin, unit-tested on the
 * JVM; [PhoneFiles] lists the files, and emails and messages are searched as well.
 */
object Files {

    /** A search result: a file, an email or a message, with its origin [where] and match [score]. */
    data class Hit(val title: String, val where: String, val uri: String?, val mime: String?, val modified: Long, val score: Int)

    /** Words ignored in a file search: articles, prepositions and words for "file" or "find". */
    private val STOP = Lexicon.words("files.stop").toSet()

    /** Extracts search terms: normalized (no accents), longer than two characters, without stop words. */
    fun terms(query: String): List<String> = Commands.normalize(query).split(' ').filter { it.length > 2 && it !in STOP }.distinct()

    /**
     * Counts how many [terms] appear in [name], each once. Terms of five or more characters also match on a shared
     * five-character prefix.
     */
    fun score(name: String, terms: List<String>): Int {
        val words = Commands.normalize(name.replace('_', ' ').replace('-', ' ').replace('.', ' ')).split(' ')
        return terms.count { t -> words.any { w -> w == t || (t.length >= 5 && (w.startsWith(t.take(5)) || t.startsWith(w.take(5)) && w.length >= 5)) } }
    }

    /** Returns a localized, user-facing origin label (a folder name) derived from the file's path. */
    fun source(path: String): String {
        val p = path.lowercase()
        return when {
            "whatsapp" in p -> "WhatsApp"
            "telegram" in p -> "Telegram"
            "download" in p -> t(R.string.files_downloads)
            "documents" in p -> t(R.string.files_documents)
            "dcim" in p || "pictures" in p -> t(R.string.files_photos)
            else -> t(R.string.files_files)
        }
    }

    /** Drops non-matching hits and sorts by score, then by modification time, both descending. */
    fun rank(hits: List<Hit>) = hits.filter { it.score > 0 }.sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.modified })

    /** Renders the top 10 hits for the agent, noting when other apps' files could not be searched. */
    fun describe(hits: List<Hit>, terms: List<String>, filesKnown: Boolean): String {
        val note = if (filesKnown) "" else "\n(Files of other apps such as WhatsApp are not searched: the owner has not allowed access to all files; Ajustes › Permisos.)"
        if (hits.isEmpty()) return "Nothing found for ${terms.joinToString(" ")}.$note"
        return "Found, best first (shown on the Files screen):\n" +
            hits.take(10).joinToString("\n") { "- ${it.title} — ${it.where}" } + note
    }
}
