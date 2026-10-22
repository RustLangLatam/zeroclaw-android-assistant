package org.rustlanglatam.zeroclaw.android

import android.content.ContentUris
import android.content.Context
import android.os.Environment
import android.provider.MediaStore

/**
 * Searches the owner's documents (Downloads, Documents, files received through WhatsApp or Telegram) by name through
 * MediaStore. Other apps' files require "all files access" (MANAGE_EXTERNAL_STORAGE); without it only shared media and
 * the app's own files are visible, which [Files.describe] reports.
 */
class PhoneFiles(private val context: Context) {

    fun everything() = Environment.isExternalStorageManager()

    /** Returns the documents whose names match [terms], as unranked hits. */
    fun find(terms: List<String>): List<Files.Hit> {
        if (terms.isEmpty()) return emptyList()
        val uri = MediaStore.Files.getContentUri("external")
        val columns = arrayOf(
            MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        // Documents only; photos and audio have dedicated searches.
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_NONE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT})"
        return context.contentResolver.query(uri, columns, selection, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val score = Files.score(name, terms)
                    if (score == 0) continue
                    add(Files.Hit(name, Files.source(c.getString(2).orEmpty()), ContentUris.withAppendedId(uri, c.getLong(0)).toString(), c.getString(3), c.getLong(4) * 1000, score))
                }
            }
        }.orEmpty()
    }
}
