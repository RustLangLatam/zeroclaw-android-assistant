package org.rustlanglatam.zeroclaw.fakechat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.util.Log
import java.io.File

/**
 * Posts a message the way a messaging app does: a MessagingStyle notification with the conversation history and a
 * reply action.
 *
 * Extras: `from` is the sender's display name; `number` is an optional phone number, set as the sender's `tel:` person
 * URI as WhatsApp and SMS apps do; `chat` is the conversation (defaults to `from`); `group` makes it a group conversation
 * with that title.
 */
class Post : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val from = intent.getStringExtra("from") ?: return
        val group = intent.getStringExtra("group")
        val chat = group ?: intent.getStringExtra("chat") ?: from
        val number = intent.getStringExtra("number")
        Chats.add(context, chat, if (number == null) from else "$from|$number", intent.getStringExtra("text").orEmpty())
        Chats.show(context, chat, group != null)
    }
}

/** Receives a reply typed into the notification, appends it to `replies.txt` and shows it as an outgoing message. */
class Replied : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chat = intent.getStringExtra("chat") ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Chats.KEY)?.toString() ?: return
        Log.i(Chats.TAG, "reply in $chat: $text")
        File(context.filesDir, "replies.txt").appendText("$chat\t${Chats.oneLine(text)}\n")
        Chats.add(context, chat, null, text)
        Chats.show(context, chat, intent.getBooleanExtra("group", false))
    }
}

object Chats {
    const val TAG = "FakeChat"
    const val KEY = "reply"

    /** Appends a message to the conversation's history file; a `null` [from] marks a message from the phone's owner. */
    fun add(context: Context, chat: String, from: String?, text: String) {
        File(context.filesDir, "chat-$chat.txt").appendText("${System.currentTimeMillis()}\t${from.orEmpty()}\t${oneLine(text)}\n")
    }

    /** Escapes line breaks as `\n` so each message occupies one line in the files. */
    fun oneLine(text: String) = text.replace("\n", "\\n")

    fun show(context: Context, chat: String, group: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("chats", "Chats", NotificationManager.IMPORTANCE_HIGH))
        val me = Person.Builder().setName("Me").build()
        val style = Notification.MessagingStyle(me).setGroupConversation(group)
        if (group) style.conversationTitle = chat
        File(context.filesDir, "chat-$chat.txt").readLines().forEach { line ->
            val (time, from, text) = line.split('\t', limit = 3)
            val (name, number) = from.split('|').let { it[0] to it.getOrNull(1) }
            val person = if (name.isEmpty()) null else Person.Builder().setName(name).apply { number?.let { setUri("tel:$it") } }.build()
            style.addMessage(text.replace("\\n", "\n"), time.toLong(), person)
        }
        val id = chat.hashCode()
        val reply = PendingIntent.getBroadcast(
            context, id,
            Intent(context, Replied::class.java).putExtra("chat", chat).putExtra("group", group),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = Notification.Action.Builder(null, "Reply", reply)
            .addRemoteInput(RemoteInput.Builder(KEY).setLabel("Reply").build())
            .build()
        manager.notify(
            id,
            Notification.Builder(context, "chats")
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setStyle(style)
                .addAction(action)
                .build(),
        )
    }
}

/** Posts an email as an email app would: the sender as title, the subject as text, the body in the expanded view. */
class Mail : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val from = intent.getStringExtra("from") ?: return
        val subject = intent.getStringExtra("subject").orEmpty()
        val text = intent.getStringExtra("text").orEmpty()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("mail", "Mail", NotificationManager.IMPORTANCE_HIGH))
        manager.notify(
            (from + subject).hashCode(),
            Notification.Builder(context, "mail")
                .setSmallIcon(android.R.drawable.sym_action_email)
                .setCategory(Notification.CATEGORY_EMAIL)
                .setContentTitle(from)
                .setContentText(subject)
                .setStyle(Notification.BigTextStyle().bigText("$subject\n$text"))
                // Tapping opens the email, as in a real email app.
                .setContentIntent(PendingIntent.getActivity(
                    context, (from + subject).hashCode(),
                    Intent(context, Inbox::class.java).putExtra("from", from).putExtra("subject", subject).putExtra("text", text),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ))
                .build(),
        )
    }
}

/** Handles a compose intent: records the draft (to, subject, body) in `mail-out.txt` and displays it; never sends. */
class Compose : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val to = intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.joinToString().orEmpty()
            .ifEmpty { intent.data?.schemeSpecificPart.orEmpty() }
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        val body = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        Log.i(Chats.TAG, "draft to $to: $subject")
        java.io.File(filesDir, "mail-out.txt").appendText("$to\t${Chats.oneLine(subject)}\t${Chats.oneLine(body)}\n")
        setTitle("Borrador listo para enviar")
        setContentView(TextView(this).apply { setPadding(48, 32, 48, 48); textSize = 15f; text = "Para: $to\nAsunto: $subject\n\n$body" })
    }
}

/** Displays a single email, opened from its notification. */
class Inbox : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = intent.getStringExtra("subject")
        setContentView(android.widget.TextView(this).apply {
            setPadding(48, 48, 48, 48)
            textSize = 16f
            text = "De: ${intent.getStringExtra("from")}\n\n${intent.getStringExtra("text")}"
        })
    }
}
