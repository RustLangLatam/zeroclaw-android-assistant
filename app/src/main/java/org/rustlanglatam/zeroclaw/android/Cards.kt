package org.rustlanglatam.zeroclaw.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Holds the cards the agent shows on screen (an email draft, a list of steps, web results), each with up to three
 * optional action buttons whose label is sent back to the agent as the owner's answer. Card text is a small Markdown
 * subset: headings, bullet lists, numbered steps, bold, quotes and rules. Pure Kotlin apart from the Compose state.
 */
object Cards {
    data class Card(val id: Int, val title: String, val body: String, val actions: List<String>)

    /** A rendered block of a card's text. */
    sealed interface Block {
        data class Heading(val text: String) : Block
        data class Paragraph(val text: String) : Block
        data class Bullet(val text: String) : Block
        data class Step(val n: Int, val text: String) : Block
        data class Quote(val text: String) : Block
        data object Rule : Block
    }

    /** The card currently on screen, or `null` when none is shown. */
    var shown by mutableStateOf<Card?>(null)

    /** The most recent cards (at most 30) shown during this process, so they can be reopened from the interactions screen. */
    val all = CopyOnWriteArrayList<Card>()
    private val ids = AtomicInteger()

    fun show(title: String, body: String, actions: List<String>): Card =
        Card(ids.incrementAndGet(), title.trim(), body.trim(), actions.map(String::trim).filter(String::isNotEmpty).take(3)).also {
            all += it
            while (all.size > 30) all.removeAt(0)
            shown = it
        }

    fun find(id: Int) = all.firstOrNull { it.id == id }

    /** Splits [body] into blocks, one per line; consecutive plain lines form one paragraph with their line breaks kept (e.g. email header lines). */
    fun blocks(body: String): List<Block> {
        val out = mutableListOf<Block>()
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += Block.Paragraph(para.toString().trim()); para.clear() }
        for (raw in body.lines()) {
            val line = raw.trim()
            val step = Regex("""^(\d+)[.)]\s+(.+)$""").find(line)
            when {
                line.isEmpty() -> flush()
                line.startsWith("#") -> { flush(); out += Block.Heading(line.trimStart('#').trim()) }
                line == "---" || line == "***" -> { flush(); out += Block.Rule }
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") -> { flush(); out += Block.Bullet(line.substring(2).trim()) }
                step != null -> { flush(); out += Block.Step(step.groupValues[1].toInt(), step.groupValues[2]) }
                line.startsWith(">") -> { flush(); out += Block.Quote(line.trimStart('>').trim()) }
                else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
            }
        }
        flush()
        return out
    }

    private val LINK = Regex("""\[([^\]]+)]\((https?://[^)\s]+)\)""")

    /** Returns the URL of the first Markdown link in [text]; only http(s) URLs match, never other apps' schemes. */
    fun link(text: String): String? = LINK.find(text)?.groupValues?.get(2)

    /** Returns [text] with each Markdown link replaced by its label. */
    fun plain(text: String) = LINK.replace(text) { it.groupValues[1] }

    /** Splits [text] on `**` markers into ordered (text, isBold) pieces. */
    fun spans(text: String): List<Pair<String, Boolean>> =
        text.split("**").mapIndexedNotNull { i, piece -> piece.takeIf { it.isNotEmpty() }?.let { it to (i % 2 == 1) } }
}
