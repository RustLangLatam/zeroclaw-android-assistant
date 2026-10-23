package org.rustlanglatam.zeroclaw.android

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/**
 * "Share with Ksandra" from the gallery, a file manager or an email: a picture or a PDF is read on the phone (text
 * recognition on the device; the first pages of a PDF rendered with [PdfRenderer]), plain text is taken as is. The
 * picture never leaves the phone. The text goes to [Shares.offer], which shows what was found with actions the
 * owner confirms by tapping.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val type = intent.type.orEmpty()
        @Suppress("DEPRECATION") val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        thread {
            val text = runCatching {
                when {
                    type.startsWith("text/") && uri == null -> intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                    type == "application/pdf" && uri != null -> pdf(uri)
                    type.startsWith("image/") && uri != null -> image(InputImage.fromFilePath(this, uri))
                    else -> ""
                }
            }.onFailure { Log.w(AssistantService.TAG, "share: ${it.message}") }.getOrDefault("")
            Shares.last = text
            startActivity(MainActivity.sealed(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("shared", true)))
            finish()
        }
    }

    /** Recognizes the text in [image] on the device; blocks the calling (background) thread. */
    private fun image(image: InputImage): String =
        com.google.android.gms.tasks.Tasks.await(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)).text

    /** Renders the first [PAGES] pages of the PDF at [uri] and recognizes their text. */
    private fun pdf(uri: Uri): String = contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
        PdfRenderer(fd).use { pdf ->
            (0 until minOf(pdf.pageCount, PAGES)).joinToString("\n") { i ->
                pdf.openPage(i).use { page ->
                    val scale = WIDTH.toFloat() / page.width
                    val bitmap = Bitmap.createBitmap(WIDTH, (page.height * scale).toInt(), Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    image(InputImage.fromBitmap(bitmap, 0)).also { bitmap.recycle() }
                }
            }
        }
    }

    private companion object {
        const val PAGES = 3
        const val WIDTH = 1240
    }
}

/** Turns shared text into a card with what was found and actions that run on the owner's tap. */
object Shares {
    /** The text of the last share, handed from [ShareActivity] to the main activity; in memory only. */
    @Volatile var last: String? = null

    private val day = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** A date as the owner reads it in the assistant's language ("26 oct", "Oct 26"). */
    private fun said(date: LocalDate) = date.format(DateTimeFormatter.ofPattern(if (Language.current == Lang.EN) "MMM d" else "d MMM", Language.locale))

    /** Shows the card for [text]; [speak] reads it aloud and [ask] sends it to the agent, only when the owner taps them. */
    fun offer(text: String, speak: (String) -> Unit, ask: (String) -> Unit) {
        if (text.isBlank()) { Cards.show(t(R.string.share_nothing_title), t(R.string.share_nothing), emptyList()); return }
        val today = LocalDate.now()
        val found = Shared.read(text, today, Inbox.english(text))
        val tools = AssistantService.phoneTools
        val title = when (found.kind) {
            Shared.Kind.BILL -> t(R.string.share_bill)
            Shared.Kind.APPOINTMENT -> t(R.string.share_appointment)
            Shared.Kind.OTHER -> t(R.string.share_document)
        }
        val facts = listOfNotNull(
            found.amount?.let { "**$it**" },
            found.date?.let { d -> (if (found.kind == Shared.Kind.BILL) t(R.string.share_due_x, said(d)) else said(d)) + (found.time?.let { " · $it" } ?: "") },
        ).joinToString(" · ")
        val excerpt = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").take(400)
        val actions = linkedMapOf<String, () -> Unit>()
        if (found.date != null && tools != null) {
            val date = found.date
            when (found.kind) {
                Shared.Kind.BILL -> {
                    val on = date.minusDays(2).takeIf { it.isAfter(today) } ?: date
                    actions[t(R.string.share_remind_me_on_x, said(on))] = {
                        tools.owner("remind", JSONObject().put("text", t(R.string.share_pay_x, found.amount ?: title)).put("date", on.format(day)).put("time", "09:00"))
                    }
                }
                Shared.Kind.APPOINTMENT -> if (found.time != null) actions[t(R.string.share_add_to_calendar)] = {
                    tools.owner("add_event", JSONObject().put("title", title).put("date", date.format(day)).put("start", found.time.toString()).put("note", excerpt.take(200)))
                }
                Shared.Kind.OTHER -> actions[t(R.string.share_remind_me_on_x, said(date))] = {
                    tools.owner("remind", JSONObject().put("text", excerpt.take(60)).put("date", date.format(day)).put("time", "09:00"))
                }
            }
        }
        actions[t(R.string.share_read_it_to_me)] = { speak(text.take(1500)) }
        actions[t(R.string.share_ask_ksandra)] = { ask(text.take(4000)) }
        val shown = actions.keys.toList().take(3)
        Cards.show(title, listOf(facts, "> $excerpt").filter { it.isNotBlank() }.joinToString("\n\n"), shown, actions.filterKeys { it in shown })
    }
}
