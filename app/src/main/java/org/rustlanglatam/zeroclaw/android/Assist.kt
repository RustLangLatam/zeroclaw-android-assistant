package org.rustlanglatam.zeroclaw.android

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * The system's assist gesture (long press on home, a swipe from a bottom corner, a headset button) when the owner
 * chose the app as the digital assistant: opens it listening, over whatever app was on screen. No voice-interaction
 * service, no always-on microphone and no wake word: listening starts only on the gesture.
 */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            MainActivity.sealed(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("listen", true)),
        )
        finish()
    }

    companion object {
        /** Whether the owner chose this app as the digital assistant (the role that receives the gesture). */
        fun chosen(context: Context) = runCatching { context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)

        /** The system screen where the digital assistant app is chosen (the role cannot be requested by an app). */
        fun settings(): Intent = Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
