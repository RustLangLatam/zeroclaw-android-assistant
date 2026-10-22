package org.rustlanglatam.zeroclaw.android

import android.app.AppOpsManager
import android.app.SearchManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Process
import android.provider.MediaStore
import android.view.KeyEvent

/**
 * Android-side counterpart of [Apps]: lists installed launcher apps with their foreground usage (when usage
 * access is granted) and default music app, opens an app, starts a play-from-search in a media app, and
 * dispatches play/pause/next/previous media keys.
 */
class PhoneApps(private val context: Context) {

    private val pm = context.packageManager

    /** Returns whether usage access is granted; without it, usage minutes are unavailable and ranking falls back to defaults. */
    fun usageKnown(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    /** Returns foreground minutes over the last 30 days by package; empty without usage access. */
    private fun minutes(): Map<String, Long> {
        if (!usageKnown()) return emptyMap()
        val now = System.currentTimeMillis()
        return context.getSystemService(UsageStatsManager::class.java)
            .queryAndAggregateUsageStats(now - 30L * 24 * 3600 * 1000, now)
            .mapValues { it.value.totalTimeInForeground / 60_000 }
    }

    /** Returns the package of the default music app, or `null` when none is set (the resolver resolves to `android`). */
    private fun defaultMusic(): String? =
        pm.resolveActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC), 0)
            ?.activityInfo?.packageName?.takeIf { it != "android" }

    private fun playsSearch(pkg: String) =
        pm.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg), 0).isNotEmpty()

    /** Returns every launchable app except this one, with its kind, usage, and play-from-search support. */
    fun installed(): List<App> {
        val used = minutes()
        val music = defaultMusic()
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { info ->
                val kind = Apps.kind(info.packageName, info.category)
                App(
                    info.packageName, pm.getApplicationLabel(info).toString(), kind,
                    minutes = used[info.packageName] ?: 0, default = info.packageName == music,
                    playsSearch = (kind == AppKind.MUSIC || kind == AppKind.VIDEO || kind == AppKind.PODCASTS) && playsSearch(info.packageName),
                )
            }
    }

    fun open(app: App): String {
        val intent = pm.getLaunchIntentForPackage(app.pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return "${app.label} cannot be opened."
        return runCatching { context.startActivity(intent) }.fold({ "Opened ${app.label}." }, { "Could not open ${app.label}: ${it.message}" })
    }

    /** Starts playing [query] in [app] via play-from-search; opens the app instead when it does not support it. */
    fun play(app: App, query: String): String {
        if (!app.playsSearch) return open(app) + " It does not take a search: the owner picks what to play there."
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(app.pkg)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.fold({ "Asked ${app.label} to play \"$query\"; it plays if the owner is signed in there. Say you asked it, not that it is playing." }, { "Could not play in ${app.label}: ${it.message}" })
    }

    /** Sends a media key (play, pause, next or previous, in English or Spanish) to the active media session. */
    fun control(action: String): String {
        val key = when (Commands.normalize(action)) {
            in Lexicon.words("apps.media.play") -> KeyEvent.KEYCODE_MEDIA_PLAY
            in Lexicon.words("apps.media.pause") -> KeyEvent.KEYCODE_MEDIA_PAUSE
            in Lexicon.words("apps.media.next") -> KeyEvent.KEYCODE_MEDIA_NEXT
            in Lexicon.words("apps.media.previous") -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return "Unknown action \"$action\": play, pause, next or previous."
        }
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return if (audio.isMusicActive || key == KeyEvent.KEYCODE_MEDIA_PAUSE) "Done: $action." else "Sent $action; nothing seems to be playing."
    }
}
