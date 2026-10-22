package org.rustlanglatam.zeroclaw.android

import android.content.Context

/**
 * Simple mode, for older owners or anyone who wants less: assistant mode (only the mark), answers always read aloud,
 * text 30 % larger, slower speech and short answers. Turning it off restores the voice and mode the owner had. It
 * changes no permission: confirmations still need the device credential.
 */
object Simple {
    const val TEXT_SCALE = 1.3f
    const val SPEECH_RATE = 0.85f

    /** The persona line added in simple mode. */
    const val PERSONA = "Simple mode is on: answer in one or two short sentences with plain words, one thing at a time, and ask before going on."

    /** Turns simple mode on or off; returns a description for the agent. */
    fun set(context: Context, on: Boolean): String {
        if (on == Guard.simple) return if (on) "Simple mode was already on." else "Simple mode was already off."
        val prefs = context.getSharedPreferences("simple", Context.MODE_PRIVATE)
        if (on) {
            prefs.edit().putBoolean("voice", Guard.voice).putBoolean("appMode", Guard.appMode).apply()
            Guard.voice = true
            Guard.appMode = false
        } else {
            Guard.voice = prefs.getBoolean("voice", Guard.voice)
            Guard.appMode = prefs.getBoolean("appMode", Guard.appMode)
        }
        Guard.simple = on
        Guard.save()
        AssistantService.writeIdentity(context)
        Language.onChange()
        Ui.refresh()
        return if (on) "Simple mode on: larger text, everything read aloud, slower speech, short answers. Say it in one short sentence."
        else "Simple mode off: the screen, voice and answers are as before."
    }
}
