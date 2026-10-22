package org.rustlanglatam.zeroclaw.android

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * On-device photo search. Narrows candidates by date through a MediaStore query (metadata only),
 * then runs ML Kit image labeling and face detection with bundled models on those candidates, so no
 * image leaves the device. Results per photo are cached in a sealed index file, so each photo is
 * analyzed once.
 */
class PhoneGallery(private val context: Context) {

    /** Result of [find]: the matching photos, how many candidates were analyzed within the budget, and the candidate total. */
    data class Found(val photos: List<Photos.Photo>, val looked: Int, val total: Int)

    /** Returns whether the app holds the image read permission for this API level. */
    fun permitted() = context.checkSelfPermission(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE,
    ) == PackageManager.PERMISSION_GRANTED

    /** Returns `(id, takenAtMillis)` for photos taken between [from] and [to] inclusive, newest first. */
    private fun between(from: LocalDate, to: LocalDate): List<Pair<Long, Long>> {
        val zone = ZoneId.systemDefault()
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_MODIFIED)
        // Filters by period in the MediaStore query itself rather than scanning every image row.
        // DATE_TAKEN is in milliseconds; DATE_MODIFIED (the fallback) is in seconds.
        val period = "(${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?) OR " +
            "((${MediaStore.Images.Media.DATE_TAKEN} IS NULL OR ${MediaStore.Images.Media.DATE_TAKEN} = 0) AND " +
            "${MediaStore.Images.Media.DATE_MODIFIED} >= ? AND ${MediaStore.Images.Media.DATE_MODIFIED} < ?)"
        val args = arrayOf("$start", "$end", "${start / 1000}", "${end / 1000}")
        return context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns, period, args, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    // Images without EXIF have no DATE_TAKEN; the file's modification time is used instead.
                    val taken = c.getLong(1).takeIf { it > 0 } ?: (c.getLong(2) * 1000)
                    if (taken in start until end) add(c.getLong(0) to taken)
                }
            }
        }.orEmpty().sortedByDescending { it.second }
    }

    private fun uri(id: Long) = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    /** Returns the labels and face count of one photo, computed from a 480x480 thumbnail and cached; `null` if no thumbnail. */
    private fun look(id: Long): Pair<Set<String>, Int>? {
        index[id]?.let { return it }
        val bitmap = runCatching { context.contentResolver.loadThumbnail(uri(id), Size(480, 480), null) }.getOrNull() ?: return null
        val image = InputImage.fromBitmap(bitmap, 0)
        val labels = runCatching { Tasks.await(labeler.process(image)).map { it.text }.toSet() }.getOrDefault(emptySet())
        val faces = runCatching { Tasks.await(detector.process(image)).size }.getOrDefault(0)
        bitmap.recycle()
        return (labels to faces).also { index[id] = it; dirty = true }
    }

    /**
     * Finds photos in [from]..[to] matching any of [wanted] labels and, if [withPeople], containing a face.
     * Stops analyzing uncached photos once [budgetMs] has elapsed; cached ones are still evaluated.
     */
    fun find(from: LocalDate, to: LocalDate, wanted: Set<String>, withPeople: Boolean, budgetMs: Long = 25_000): Found {
        load()
        val candidates = between(from, to).take(MAX)
        val deadline = System.currentTimeMillis() + budgetMs
        var looked = 0
        val found = mutableListOf<Photos.Photo>()
        for ((id, taken) in candidates) {
            val needsLook = wanted.isNotEmpty() || withPeople
            if (needsLook && index[id] == null && System.currentTimeMillis() > deadline) break
            val (labels, faces) = if (needsLook) look(id) ?: continue else emptySet<String>() to 0
            looked++
            val photo = Photos.Photo(id, uri(id).toString(), taken, labels, faces)
            if (Photos.matches(photo, wanted, withPeople)) found += photo
        }
        save()
        return Found(found, looked, candidates.size)
    }

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val json = JSONObject(file().readSealed())
            json.keys().forEach { k ->
                val o = json.getJSONObject(k)
                val labels = o.getJSONArray("l").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                index[k.toLong()] = labels to o.getInt("f")
            }
        }
    }

    private fun save() {
        if (!dirty) return
        dirty = false
        // Prunes entries for images no longer present in MediaStore.
        runCatching {
            val present = context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), null, null, null)
                ?.use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
            if (present != null) index.keys.retainAll(present)
        }
        val json = JSONObject()
        index.forEach { (id, v) -> json.put(id.toString(), JSONObject().put("l", JSONArray(v.first.toList())).put("f", v.second)) }
        file().writeSealed(json.toString())
    }

    private fun file() = File(context.filesDir, "photo-index.json")

    companion object {
        /** Maximum number of candidates (newest first) considered per search. */
        private const val MAX = 400

        private val index = ConcurrentHashMap<Long, Pair<Set<String>, Int>>()
        @Volatile private var loaded = false
        @Volatile private var dirty = false

        private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build()) }
        private val detector by lazy {
            FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).setMinFaceSize(0.08f).build())
        }
    }
}
