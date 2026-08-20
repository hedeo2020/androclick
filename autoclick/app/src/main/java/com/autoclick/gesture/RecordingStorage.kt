package com.autoclick.gesture

import android.content.Context
import com.autoclick.gesture.model.GestureRecording
import org.json.JSONObject
import java.io.File

/** Persists recorded gestures as JSON files under the app's private files dir. */
class RecordingStorage(context: Context) {

    private val dir: File = File(context.filesDir, "recordings").apply { mkdirs() }

    fun save(recording: GestureRecording) {
        File(dir, fileNameFor(recording.name)).writeText(recording.toJson().toString())
    }

    fun load(name: String): GestureRecording? {
        val file = File(dir, fileNameFor(name))
        if (!file.exists()) return null
        return GestureRecording.fromJson(JSONObject(file.readText()))
    }

    fun list(): List<String> =
        dir.listFiles { f -> f.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    fun latest(): GestureRecording? =
        dir.listFiles { f -> f.extension == "json" }
            ?.maxByOrNull { it.lastModified() }
            ?.let { GestureRecording.fromJson(JSONObject(it.readText())) }

    private fun fileNameFor(name: String) = "$name.json"

    companion object {
        const val DEFAULT_NAME = "last_recording"
    }
}
