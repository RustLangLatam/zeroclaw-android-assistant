package org.rustlanglatam.zeroclaw.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The owner's lists by voice (shopping, to-do, anything named): "add milk and bread to the shopping list", "what do I
 * need to buy?", "take the bread off". Each list is stored in ZeroClaw's memory under [CATEGORY] (key `list-<name>`,
 * one item per line), the source of truth, with a sealed copy on the phone, as [Notes]. Pure Kotlin apart from the hooks.
 */
object Lists {
    const val CATEGORY = "list"

    /** A list: its [name] as the owner says it and its [items] in the order added. */
    data class OwnerList(val name: String, val items: List<String>) {
        val key get() = keyOf(name)
    }

    fun keyOf(name: String) = "list-" + Commands.normalize(name).replace(' ', '-').take(40)

    private val lists = java.util.concurrent.CopyOnWriteArrayList<OwnerList>()
    private var file: File? = null

    @Volatile var onPut: (OwnerList) -> Unit = {}
    @Volatile var onDrop: (OwnerList) -> Unit = {}

    fun all(): List<OwnerList> = lists.toList()

    fun named(name: String): OwnerList? = lists.firstOrNull { it.key == keyOf(name) }

    /** Splits what the owner said into items: "milk, bread and eggs" → three items. */
    fun items(text: String): List<String> =
        text.split(Regex(",|;|\\n|\\s+(" + Lexicon["lists.and"] + ")\\s+")).map { it.trim().trim('.') }.filter { it.isNotEmpty() }

    /** Returns [list] with [add] appended, skipping items already on it (ignoring case and accents). */
    fun adding(list: OwnerList, add: List<String>): OwnerList {
        val have = list.items.map(Commands::normalize).toMutableSet()
        return list.copy(items = list.items + add.filter { have.add(Commands.normalize(it)) })
    }

    /**
     * Returns [list] without the items matching [drop]: an item matches when every significant word (3 letters or more,
     * so articles are ignored) of a dropped one is among its words, normalized: "la leche" takes off "leche desnatada".
     */
    fun removing(list: OwnerList, drop: List<String>): OwnerList {
        fun words(s: String) = Commands.normalize(s).split(' ').filter { it.length >= 3 }.toSet()
        val gone = drop.map(::words).filter { it.isNotEmpty() }
        return list.copy(items = list.items.filterNot { i -> val have = words(i); gone.any { have.containsAll(it) } })
    }

    @Synchronized fun add(name: String, add: List<String>): OwnerList =
        adding(named(name) ?: OwnerList(name.trim(), emptyList()), add).also { put(it) }

    @Synchronized fun remove(name: String, drop: List<String>): OwnerList? =
        named(name)?.let { removing(it, drop) }?.also { put(it) }

    @Synchronized fun clear(name: String): Boolean {
        val list = named(name) ?: return false
        lists.remove(list); save(); onDrop(list)
        return true
    }

    private fun put(list: OwnerList) {
        lists.removeAll { it.key == list.key }
        lists += list
        save()
        onPut(list)
    }

    /** Replaces the local copy with [read] as read back from ZeroClaw's memory; does not call the hooks. */
    @Synchronized fun replace(read: List<OwnerList>) { lists.clear(); lists += read; save() }

    /** Serializes [list] for ZeroClaw's memory: the name, then one item per line. */
    fun content(list: OwnerList) = (listOf(list.name) + list.items).joinToString("\n")

    fun fromStore(key: String, content: String): OwnerList {
        val lines = content.split('\n')
        return OwnerList(lines.firstOrNull()?.takeIf { it.isNotBlank() } ?: key.removePrefix("list-").replace('-', ' '), lines.drop(1).filter { it.isNotBlank() })
    }

    /** Renders [list] for the agent. */
    fun describe(list: OwnerList?) = when {
        list == null -> "There is no such list."
        list.items.isEmpty() -> "The ${list.name} list is empty."
        else -> "${list.name}: " + list.items.joinToString(", ") + "."
    }

    fun load(file: File) {
        this.file = file
        runCatching {
            val a = JSONArray(file.readSealed())
            lists.clear()
            for (i in 0 until a.length()) a.getJSONObject(i).let { o ->
                lists += OwnerList(o.getString("name"), o.getJSONArray("items").let { it2 -> (0 until it2.length()).map { j -> it2.getString(j) } })
            }
        }
    }

    private fun save() {
        val target = file ?: return
        runCatching { target.writeSealed(JSONArray(lists.map { JSONObject().put("name", it.name).put("items", JSONArray(it.items)) }).toString()) }
        Ui.refresh()
    }
}
