package com.autoclick.gesture

import android.content.Context
import com.autoclick.gesture.model.GestureRecording
import org.json.JSONObject
import java.io.File

/** Persists recorded gestures as JSON files under the app's private files dir. */
class RecordingStorage(context: Context) {

    private val dir: File = File(context.filesDir, "recordings").apply { mkdirs() }
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(recording: GestureRecording) {
        File(dir, fileNameFor(recording.name)).writeText(recording.toJson().toString())
    }

    fun load(name: String): GestureRecording? {
        val file = File(dir, fileNameFor(name))
        if (!file.exists()) return null
        return GestureRecording.fromJson(JSONObject(file.readText()))
    }

    fun exists(name: String): Boolean = File(dir, fileNameFor(name)).exists()

    /** Saved recording names, most recently saved first. */
    fun list(): List<String> =
        dir.listFiles { f -> f.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()

    fun delete(name: String): Boolean {
        val deleted = File(dir, fileNameFor(name)).delete()
        if (deleted && activeName() == name) setActive(null)
        return deleted
    }

    /** Renames a recording in place, refusing to clobber an existing name. Returns success. */
    fun rename(oldName: String, newName: String): Boolean {
        if (oldName == newName) return true
        if (exists(newName)) return false
        val recording = load(oldName) ?: return false
        save(GestureRecording(newName, recording.strokes))
        File(dir, fileNameFor(oldName)).delete()
        if (activeName() == oldName) setActive(newName)
        return true
    }

    /** The next unused "Recording N" name, for auto-naming a freshly stopped recording. */
    fun nextAutoName(): String {
        val existing = list().toSet()
        var i = 1
        while (existing.contains("Recording $i")) i++
        return "Recording $i"
    }

    fun activeName(): String? = prefs.getString(KEY_ACTIVE, null)

    fun setActive(name: String?) {
        prefs.edit().putString(KEY_ACTIVE, name).apply()
    }

    /** The recording to play: the explicitly active one if it still exists, else the most recent. */
    fun activeOrLatest(): GestureRecording? {
        activeName()?.let { name -> load(name)?.let { return it } }
        return latest()
    }

    fun latest(): GestureRecording? =
        dir.listFiles { f -> f.extension == "json" }
            ?.maxByOrNull { it.lastModified() }
            ?.let { GestureRecording.fromJson(JSONObject(it.readText())) }

    private fun fileNameFor(name: String): String {
        val safe = name.map { if (it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-') it else '_' }
            .joinToString("")
        return "$safe.json"
    }

    companion object {
        const val DEFAULT_NAME = "last_recording"
        private const val PREFS_NAME = "autoclick_prefs"
        private const val KEY_ACTIVE = "active_recording"
    }
}
