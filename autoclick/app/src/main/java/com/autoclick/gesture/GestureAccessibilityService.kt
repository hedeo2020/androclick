package com.autoclick.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.autoclick.gesture.model.GestureRecording
import com.autoclick.gesture.model.Stroke

/**
 * Replays recorded [GestureRecording]s via the Accessibility gesture-dispatch API.
 * This is the only way to synthesize touches on other apps without root.
 */
class GestureAccessibilityService : AccessibilityService() {

    private var cancelled = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected, canPerformGestures should now be available")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Accessibility service destroyed/unbound")
        if (instance === this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun stopPlayback() {
        cancelled = true
    }

    /**
     * Dispatches every stroke in [recording], chunked so no single dispatchGesture()
     * call exceeds the platform's max total gesture duration. Calls [onFinished] once
     * every chunk has been dispatched (or immediately if cancelled/empty).
     */
    fun play(recording: GestureRecording, onFinished: () -> Unit) {
        cancelled = false
        val maxDurationMs = maxGestureDurationMs()
        val batches = batchStrokes(recording.strokes, maxDurationMs)
        Log.d(TAG, "play: ${recording.strokes.size} strokes split into ${batches.size} batch(es), maxGestureDuration=${maxDurationMs}ms")
        dispatchBatches(batches, 0, onFinished)
    }

    private fun dispatchBatches(batches: List<List<Stroke>>, index: Int, onFinished: () -> Unit) {
        if (cancelled) {
            Log.d(TAG, "dispatchBatches: cancelled, stopping at batch $index/${batches.size}")
            onFinished()
            return
        }
        if (index >= batches.size) {
            Log.d(TAG, "dispatchBatches: all ${batches.size} batch(es) done")
            onFinished()
            return
        }
        val batch = batches[index]
        val batchStart = batch.first().startOffsetMs
        val builder = GestureDescription.Builder()
        for (stroke in batch) {
            val path = Path()
            stroke.points.forEachIndexed { i, point ->
                if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            val relativeStart = stroke.startOffsetMs - batchStart
            val first = stroke.points.first()
            val last = stroke.points.last()
            Log.d(
                TAG,
                "batch $index: stroke ${stroke.points.size} pts, from (${first.x}, ${first.y}) to (${last.x}, ${last.y}), " +
                    "dx=${last.x - first.x} dy=${last.y - first.y}, relativeStart=${relativeStart}ms duration=${stroke.durationMs}ms"
            )
            Log.v(TAG, "batch $index dispatch path: " + stroke.points.joinToString { "(${it.x},${it.y})@${it.offsetMs}ms" })
            builder.addStroke(GestureDescription.StrokeDescription(path, relativeStart, stroke.durationMs))
        }

        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "batch $index: onCompleted (system accepted and ran it)")
                dispatchBatches(batches, index + 1, onFinished)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "batch $index: onCancelled")
                onFinished()
            }
        }

        val accepted = dispatchGesture(builder.build(), callback, null)
        Log.d(TAG, "batch $index: dispatchGesture(...) returned accepted=$accepted")
        if (!accepted) {
            Log.w(TAG, "dispatchGesture rejected batch $index")
            onFinished()
        }
    }

    /** Splits strokes into groups whose combined span fits under [maxDurationMs]. */
    private fun batchStrokes(strokes: List<Stroke>, maxDurationMs: Long): List<List<Stroke>> {
        if (strokes.isEmpty()) return emptyList()
        val sorted = strokes.sortedBy { it.startOffsetMs }
        val batches = mutableListOf<MutableList<Stroke>>()
        var current = mutableListOf<Stroke>()
        var batchStart = sorted.first().startOffsetMs
        for (stroke in sorted) {
            val span = stroke.startOffsetMs + stroke.durationMs - batchStart
            if (current.isNotEmpty() && span > maxDurationMs) {
                batches.add(current)
                current = mutableListOf()
                batchStart = stroke.startOffsetMs
            }
            current.add(stroke)
        }
        if (current.isNotEmpty()) batches.add(current)
        return batches
    }

    private fun maxGestureDurationMs(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            GestureDescription.getMaxGestureDuration()
        } else {
            60_000L
        }

    companion object {
        private const val TAG = "AutoClick"

        /** Set while the service is bound; null when it isn't (permission not granted, etc). */
        var instance: GestureAccessibilityService? = null
            private set
    }
}
