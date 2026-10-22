package org.rustlanglatam.zeroclaw.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Holds the notes the agent pins to the assistant's own home-screen widget (a flight time, a deadline, a birthday),
 * on request or on its own initiative. Each note is stored in ZeroClaw's memory under category [CATEGORY], which is
 * the source of truth, with a sealed copy on the phone; a note may expire ([Note.until]). Pure Kotlin apart from the hooks.
 */
object Notes {
    const val CATEGORY = "widget"

    /** A widget note: [title] is the small line, [value] the large one; [until] is the expiry in epoch milliseconds, or `null` for none. */
    data class Note(val title: String, val value: String, val until: Long? = null) {
        val key get() = "widget-" + Commands.normalize(title).replace(' ', '-').take(40)
    }

    private val notes = java.util.concurrent.CopyOnWriteArrayList<Note>()
    private var file: File? = null

    /** Hooks called after a note is added (to store it in ZeroClaw's memory) or removed. */
    @Volatile var onPut: (Note) -> Unit = {}
    @Volatile var onDrop: (Note) -> Unit = {}

    /** Returns the notes not expired at [now], newest first. */
    fun current(now: Long = System.currentTimeMillis()): List<Note> = notes.filter { it.until == null || it.until > now }.reversed()

    /** Adds [note], replacing any note with the same normalized title. */
    @Synchronized
    fun put(note: Note) {
        notes.removeAll { it.key == note.key }
        notes += note
        save()
        onPut(note)
    }

    /** Removes the note with [title]; returns whether one existed. */
    @Synchronized
    fun remove(title: String): Boolean {
        val key = Note(title, "").key
        val gone = notes.filter { it.key == key }
        notes.removeAll(gone.toSet())
        if (gone.isNotEmpty()) { save(); gone.forEach(onDrop) }
        return gone.isNotEmpty()
    }

    /** Replaces the local copy with [list] as read back from ZeroClaw's memory; does not call the hooks. */
    @Synchronized
    fun replace(list: List<Note>) {
        notes.clear(); notes += list; save()
    }

    /** Serializes [note] for ZeroClaw's memory: title, value and expiry (empty if none), one per line. */
    fun content(note: Note) = "${note.title}\n${note.value}\n${note.until ?: ""}"

    fun fromStore(key: String, content: String): Note {
        val lines = content.split('\n')
        return if (lines.size >= 2) Note(lines[0], lines[1], lines.getOrNull(2)?.toLongOrNull())
        else Note(key.removePrefix("widget-").replace('-', ' '), content)
    }

    fun load(file: File) {
        this.file = file
        runCatching {
            val a = JSONArray(file.readSealed())
            notes.clear()
            for (i in 0 until a.length()) a.getJSONObject(i).let { notes += Note(it.getString("title"), it.getString("value"), it.optLong("until", 0).takeIf { u -> u > 0 }) }
        }
    }

    private fun save() {
        val target = file ?: return
        runCatching { target.writeSealed(JSONArray(notes.map { JSONObject().put("title", it.title).put("value", it.value).put("until", it.until ?: 0) }).toString()) }
    }
}
