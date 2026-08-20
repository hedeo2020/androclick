package com.autoclick.gesture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.autoclick.gesture.model.GestureRecording
import com.autoclick.gesture.model.Stroke
import com.autoclick.gesture.model.TouchPoint
import kotlin.math.abs

/**
 * Owns the floating control bubble and, while recording, a full-screen
 * transparent capture view. Both are separate overlay windows so the bubble
 * always stays on top and clickable while the capture view underneath
 * swallows the rest of the screen's touches for recording.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var captureView: TouchCaptureView? = null

    private var isRecording = false
    private var isPlaying = false

    private lateinit var storage: RecordingStorage

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        storage = RecordingStorage(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        addBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        GestureAccessibilityService.instance?.stopPlayback()
        removeCaptureView()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
    }

    // ---- bubble ----

    private fun addBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission missing", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            // NOT_TOUCH_MODAL is required here: without it this small window is treated as
            // touch-modal and silently swallows every touch on the whole screen, not just
            // taps on the bubble itself.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 200
        }

        view.setOnTouchListener(BubbleDragTouchListener(params))
        // ImageButton children are clickable, so they consume their own touch sequence before
        // the root's OnTouchListener ever sees it - real click listeners are required here,
        // hit-testing taps from the root touch listener does not work.
        view.findViewById<ImageButton>(R.id.btnRecord).setOnClickListener { onRecordTapped() }
        view.findViewById<ImageButton>(R.id.btnPlay).setOnClickListener { onPlayTapped() }
        view.findViewById<ImageButton>(R.id.btnClose).setOnClickListener { stopSelf() }
        windowManager.addView(view, params)
        bubbleView = view
        updateStatus("idle")
    }

    private inner class BubbleDragTouchListener(private val params: WindowManager.LayoutParams) :
        View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var downParamX = 0
        private var downParamY = 0
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downParamX = params.x
                    downParamY = params.y
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (dragging || abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP) {
                        dragging = true
                        params.x = downParamX + dx.toInt()
                        params.y = downParamY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(v, params) }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> return true
            }
            return false
        }
    }

    // ---- recording ----

    private fun onRecordTapped() {
        if (isPlaying) return
        if (isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        isRecording = true
        updateStatus("recording")
        setRecordIcon(R.drawable.ic_stop)

        val startElapsed = SystemClock.elapsedRealtime()
        val strokes = mutableListOf<Stroke>()

        val view = TouchCaptureView(this) { finishedStrokes ->
            strokes.clear()
            strokes.addAll(finishedStrokes)
        }
        view.recordingStartElapsed = startElapsed

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(view, params)
        captureView = view

        // Re-add the bubble so it stays on top of the new full-screen capture window.
        bubbleView?.let { b ->
            runCatching { windowManager.removeView(b) }
            windowManager.addView(b, b.layoutParams)
        }

        Toast.makeText(this, "Recording… tap the bubble again to stop", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        isRecording = false
        setRecordIcon(R.drawable.ic_record)
        val strokes = captureView?.finishAndGetStrokes() ?: emptyList()
        removeCaptureView()

        if (strokes.isEmpty()) {
            updateStatus("idle")
            Toast.makeText(this, "No touches recorded", Toast.LENGTH_SHORT).show()
            return
        }

        val recording = GestureRecording(RecordingStorage.DEFAULT_NAME, strokes)
        storage.save(recording)
        updateStatus("saved (${strokes.size} stroke${if (strokes.size == 1) "" else "s"})")
        Toast.makeText(this, "Gesture saved", Toast.LENGTH_SHORT).show()
    }

    private fun removeCaptureView() {
        captureView?.let { runCatching { windowManager.removeView(it) } }
        captureView = null
    }

    // ---- playback ----

    private fun onPlayTapped() {
        if (isRecording) return
        if (isPlaying) {
            GestureAccessibilityService.instance?.stopPlayback()
            isPlaying = false
            updateStatus("stopped")
            return
        }
        val service = GestureAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "Enable the AutoClick accessibility service first", Toast.LENGTH_LONG).show()
            return
        }
        val recording = storage.latest()
        if (recording == null) {
            Toast.makeText(this, "No recorded gesture yet", Toast.LENGTH_SHORT).show()
            return
        }
        isPlaying = true
        updateStatus("playing")
        service.play(recording) {
            isPlaying = false
            updateStatus("idle")
        }
    }

    // ---- helpers ----

    private fun updateStatus(text: String) {
        bubbleView?.findViewById<TextView>(R.id.txtStatus)?.text = text
    }

    private fun setRecordIcon(resId: Int) {
        bubbleView?.findViewById<ImageButton>(R.id.btnRecord)?.setImageResource(resId)
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun buildNotification(): android.app.Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.overlay_channel_name), NotificationManager.IMPORTANCE_MIN
            )
            nm.createNotificationChannel(channel)
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "autoclick_overlay"
        private const val TOUCH_SLOP = 12f
    }
}

/**
 * Full-screen transparent view used only while recording: captures every
 * finger-down-to-finger-up path as a [Stroke] timed relative to when
 * recording started.
 */
private class TouchCaptureView(
    context: Context,
    private val onStrokesUpdated: (List<Stroke>) -> Unit
) : View(context) {

    var recordingStartElapsed: Long = SystemClock.elapsedRealtime()

    private val strokes = mutableListOf<Stroke>()
    private var currentPoints: MutableList<TouchPoint>? = null
    private var currentStrokeStart = 0L

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val now = SystemClock.elapsedRealtime()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                currentStrokeStart = now - recordingStartElapsed
                currentPoints = mutableListOf(TouchPoint(event.x, event.y, 0L))
            }
            MotionEvent.ACTION_MOVE -> {
                val strokeElapsed = now - recordingStartElapsed - currentStrokeStart
                currentPoints?.add(TouchPoint(event.x, event.y, strokeElapsed))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val points = currentPoints
                if (points != null && points.isNotEmpty()) {
                    strokes.add(Stroke(currentStrokeStart, points))
                    onStrokesUpdated(strokes.toList())
                }
                currentPoints = null
            }
        }
        return true
    }

    fun finishAndGetStrokes(): List<Stroke> = strokes.toList()
}
