package com.autoclick.gesture.model

import org.json.JSONArray
import org.json.JSONObject

/** One touch sample within a stroke, timed relative to the stroke's own start. */
data class TouchPoint(val x: Float, val y: Float, val offsetMs: Long)

/** A single continuous finger-down-to-finger-up path, timed relative to the recording's start. */
data class Stroke(val startOffsetMs: Long, val points: List<TouchPoint>) {
    val durationMs: Long
        get() = (points.lastOrNull()?.offsetMs ?: 0L).coerceAtLeast(1L)
}

data class GestureRecording(val name: String, val strokes: List<Stroke>) {

    val totalDurationMs: Long
        get() = strokes.maxOfOrNull { it.startOffsetMs + it.durationMs } ?: 0L

    fun toJson(): JSONObject {
        val strokesArray = JSONArray()
        for (stroke in strokes) {
            val pointsArray = JSONArray()
            for (p in stroke.points) {
                pointsArray.put(JSONObject().apply {
                    put("x", p.x)
                    put("y", p.y)
                    put("t", p.offsetMs)
                })
            }
            strokesArray.put(JSONObject().apply {
                put("start", stroke.startOffsetMs)
                put("points", pointsArray)
            })
        }
        return JSONObject().apply {
            put("name", name)
            put("strokes", strokesArray)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): GestureRecording {
            val strokesArray = json.getJSONArray("strokes")
            val strokes = mutableListOf<Stroke>()
            for (i in 0 until strokesArray.length()) {
                val strokeObj = strokesArray.getJSONObject(i)
                val pointsArray = strokeObj.getJSONArray("points")
                val points = mutableListOf<TouchPoint>()
                for (j in 0 until pointsArray.length()) {
                    val pointObj = pointsArray.getJSONObject(j)
                    points.add(
                        TouchPoint(
                            x = pointObj.getDouble("x").toFloat(),
                            y = pointObj.getDouble("y").toFloat(),
                            offsetMs = pointObj.getLong("t")
                        )
                    )
                }
                strokes.add(Stroke(startOffsetMs = strokeObj.getLong("start"), points = points))
            }
            return GestureRecording(name = json.getString("name"), strokes = strokes)
        }
    }
}
