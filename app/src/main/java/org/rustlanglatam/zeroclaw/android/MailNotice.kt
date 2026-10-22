package org.rustlanglatam.zeroclaw.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Announces an email classified as important: a callback while the app is open, otherwise a floating-mark notice and a
 * default-importance notification. The notification opens [MainActivity] with the email's id.
 */
object MailNotice {

    /** Invoked for every important email so the open app can mention it ([MainActivity.remark]). */
    @Volatile var onImportant: ((Mailbox.Mail) -> Unit)? = null

    fun announce(context: Context, mail: Mailbox.Mail) {
        val name = mail.from.substringBefore(' ')
        onImportant?.invoke(mail)
        // While the app is visible the remark plus the chime is enough; no notification is posted.
        if (Floating.appVisible) return Chime.play(context)
        Floating.notice(context, t(R.string.mailnotice_email_from_x_looks_important, name), mail.app)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = Chime.channel(context, "important", t(R.string.mailnotice_what_looks_important), NotificationManager.IMPORTANCE_DEFAULT)
        val open = PendingIntent.getActivity(
            context, 7000 + mail.id,
            MainActivity.sealed(Intent(context, MainActivity::class.java).putExtra("mail", mail.id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            7000 + mail.id,
            Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_mark)
                .setContentTitle(t(R.string.mailnotice_an_email_from_x_looks_important, name))
                .setContentText(mail.subject)
                .setStyle(Notification.BigTextStyle().bigText("${mail.subject}\n${mail.why?.replaceFirstChar { it.uppercase() }.orEmpty()}."))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Action prefix used by the home-screen line for an important email; followed by the id ([MainActivity.openMail]). */
    const val OPEN = "mail:"
}
