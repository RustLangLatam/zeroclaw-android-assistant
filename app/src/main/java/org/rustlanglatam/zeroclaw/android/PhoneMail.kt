package org.rustlanglatam.zeroclaw.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/**
 * Composes emails on the owner's behalf: resolves the address from contacts and opens the full message as a draft
 * in an email app. The app never sends; the owner reviews and sends the draft.
 */
class PhoneMail(private val context: Context) {

    private val pm = context.packageManager

    /** Returns the first email address of a contact whose display name contains [name], or `null` (also without READ_CONTACTS). */
    fun addressOf(name: String): String? {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val email = ContactsContract.CommonDataKinds.Email.CONTENT_URI
        val display = ContactsContract.CommonDataKinds.Email.DISPLAY_NAME
        return context.contentResolver.query(email, arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS), "$display LIKE ?", arrayOf("%$name%"), null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    /**
     * Picks the email app package: [prefer] (e.g. the app the replied-to email came from) if it handles `mailto:`,
     * else the default `mailto:` handler, else the most-used handler.
     */
    private fun app(prefer: String?): String? {
        val mailto = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        if (prefer != null && pm.queryIntentActivities(Intent(mailto).setPackage(prefer), 0).isNotEmpty()) return prefer
        pm.resolveActivity(mailto, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            ?.takeIf { it != "android" }?.let { return it } // "android" is the system chooser: no default set.
        val handlers = pm.queryIntentActivities(mailto, 0).map { it.activityInfo.packageName }.toSet()
        return Apps.rank(PhoneApps(context).installed().filter { it.pkg in handlers }).firstOrNull()?.pkg ?: handlers.firstOrNull()
    }

    /** Opens an email app with the message prefilled and returns a status line for the agent; the owner sends it. */
    fun draft(to: String, subject: String, body: String, prefer: String? = null): String {
        val pkg = app(prefer) ?: return "No email app installed."
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .setPackage(pkg)
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.fold(
            { "The email to $to is written and open in $label; the owner reads it and sends it." },
            { "Could not open $label: ${it.message}" },
        )
    }
}
