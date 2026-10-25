package org.rustlanglatam.zeroclaw.android

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/**
 * "Take a photo": opens the phone's camera app, where the owner presses the shutter (the app holds no camera
 * permission). The photo goes to the gallery, in Pictures/Ksandra, and is looked at on the phone
 * ([PhoneGallery.taken]): labels for a later search and the text it contains; nothing leaves the phone. The main
 * activity then asks whether to explain it or just keep it.
 */
class PhotoActivity : ComponentActivity() {
    private var uri: Uri? = null
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { done(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = savedInstanceState?.getString("uri")?.let(Uri::parse)
        if (savedInstanceState != null) return
        uri = runCatching {
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Ksandra_${LocalDateTime.now().format(NAME)}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Ksandra")
            })
        }.getOrNull()
        runCatching { camera.launch(uri!!) }.onFailure { Log.w(AssistantService.TAG, "photo: ${it.message}"); done(false) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        uri?.let { outState.putString("uri", it.toString()) }
    }

    private fun done(ok: Boolean) {
        val taken = uri
        if (!ok || taken == null) {
            // Cancelled: the empty gallery entry made for the camera is removed.
            taken?.let { runCatching { contentResolver.delete(it, null, null) } }
            finish()
            return
        }
        thread {
            val id = ContentUris.parseId(taken)
            val (labels, text) = runCatching { PhoneGallery(this).taken(id) }.onFailure { Log.w(AssistantService.TAG, "photo: ${it.message}") }
                .getOrDefault(emptySet<String>() to "")
            last = Triple(id, labels, text)
            startActivity(MainActivity.sealed(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("photo", true)))
            finish()
        }
    }

    companion object {
        /** The last photo taken: its gallery id, the labels seen and the text read in it, for the main activity; in memory only. */
        @Volatile var last: Triple<Long, Set<String>, String>? = null

        private val NAME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}
