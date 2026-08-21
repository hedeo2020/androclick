package com.autoclick.gesture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
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

/**
 * Owns the floating control bubble and two independent recording tools:
 *
 * - A small draggable target reticle for taps: drag it to line up precisely over a target, then
 *   confirm to log a tap point. Its own window is small so only its own bounds intercept touches
 *   - the rest of the screen stays fully usable while you position it.
 * - A one-shot, full-screen swipe capture for swipes/scrolls: tapping the swipe button arms a
 *   single momentary full-screen window that captures exactly one real drag wherever you actually
 *   perform it (e.g. right over the list you want scrolled), then removes itself. Swipes don't go
 *   through the reticle at all - dragging a tiny dot to imitate a scroll is imprecise and doesn't
 *   need to happen where the dot starts out, whereas this captures the true gesture in place.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var storage: RecordingStorage

    private var bubbleView: View? = null

    private var reticleAnchorView: TargetReticleView? = null
    private var reticleAnchorParams: WindowManager.LayoutParams? = null
    private var swipeCaptureView: View? = null

    private var isRecording = false
    private var isPlaying = false

    private var recordingStartElapsed = 0L
    private val recordedStrokes = mutableListOf<Stroke>()

    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val elapsedSec = (SystemClock.elapsedRealtime() - recordingStartElapsed) / 1000
            updateStatus("REC %d:%02d".format(elapsedSec / 60, elapsedSec % 60))
            timerHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OverlayService onCreate, accessibilityService.instance=${GestureAccessibilityService.instance}")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        storage = RecordingStorage(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        addBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OverlayService onDestroy")
        timerHandler.removeCallbacksAndMessages(null)
        GestureAccessibilityService.instance?.stopPlayback()
        removeReticle()
        endSwipeCapture()
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

        view.setOnTouchListener(DragTouchListener(params, view))
        // ImageButton children are clickable, so they consume their own touch sequence before
        // the root's OnTouchListener ever sees it - real click listeners are required here.
        view.findViewById<ImageButton>(R.id.btnRecord).setOnClickListener { onRecordButtonTapped() }
        view.findViewById<ImageButton>(R.id.btnSwipe).setOnClickListener { onSwipeButtonTapped() }
        view.findViewById<ImageButton>(R.id.btnPlay).setOnClickListener { onPlayButtonTapped() }
        // Close is long-press-only: it sits right next to Play/Stop&Save in a small bubble, and
        // a plain single tap here was repeatedly killing the service by accident (confirmed via
        // logcat - a tap on this window was immediately followed by destroyService each time).
        view.findViewById<ImageButton>(R.id.btnClose).setOnClickListener {
            Toast.makeText(this, "Long-press to close", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<ImageButton>(R.id.btnClose).setOnLongClickListener {
            stopSelf()
            true
        }
        windowManager.addView(view, params)
        bubbleView = view
        updateStatus(idleStatusText())
    }

    /** Drag-to-move behaviour shared by the bubble and the reticle. */
    private inner class DragTouchListener(
        private val params: WindowManager.LayoutParams,
        private val view: View
    ) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var downParamX = 0
        private var downParamY = 0

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downParamX = params.x
                    downParamY = params.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = downParamX + (event.rawX - downRawX).toInt()
                    params.y = downParamY + (event.rawY - downRawY).toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    return true
                }
                MotionEvent.ACTION_UP -> return true
            }
            return false
        }
    }

    // ---- recording ----

    private fun onRecordButtonTapped() {
        Log.d(TAG, "record button tapped (isRecording=$isRecording isPlaying=$isPlaying)")
        if (isPlaying) return
        if (isRecording) addTapPoint() else startRecording()
    }

    private fun onPlayButtonTapped() {
        Log.d(TAG, "play button tapped (isRecording=$isRecording isPlaying=$isPlaying)")
        if (isRecording) {
            stopRecording()
            return
        }
        if (isPlaying) stopPlayback() else startPlayback()
    }

    private fun startRecording() {
        if (GestureAccessibilityService.instance == null) {
            Toast.makeText(this, "Tip: enable the accessibility service before playback", Toast.LENGTH_SHORT).show()
        }
        isRecording = true
        recordedStrokes.clear()
        recordingStartElapsed = SystemClock.elapsedRealtime()
        addReticle()
        setButtonIcon(R.id.btnRecord, R.drawable.ic_confirm)
        setButtonIcon(R.id.btnPlay, R.drawable.ic_stop)
        timerHandler.post(timerTick)
        Toast.makeText(
            this,
            "Drag the dot + check = log a tap. Tap the swipe icon, then swipe/scroll on screen to log that.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun addTapPoint() {
        val reticle = reticleAnchorView
        if (reticle == null) {
            Log.w(TAG, "addTapPoint: reticleAnchorView is null, ignoring")
            return
        }
        val (x, y) = reticle.centerScreenPosition()
        val offset = SystemClock.elapsedRealtime() - recordingStartElapsed
        recordedStrokes.add(
            Stroke(
                startOffsetMs = offset,
                points = listOf(TouchPoint(x, y, 0L), TouchPoint(x, y, TAP_DURATION_MS))
            )
        )
        Log.d(TAG, "logged point #${recordedStrokes.size} at ($x, $y) offset=${offset}ms")
        Toast.makeText(this, "Point ${recordedStrokes.size} logged", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        isRecording = false
        timerHandler.removeCallbacksAndMessages(null)
        removeReticle()
        endSwipeCapture()
        setButtonIcon(R.id.btnRecord, R.drawable.ic_record)
        setButtonIcon(R.id.btnPlay, R.drawable.ic_play)

        if (recordedStrokes.isEmpty()) {
            Log.d(TAG, "stopRecording: nothing recorded")
            updateStatus(idleStatusText())
            Toast.makeText(this, "No points recorded", Toast.LENGTH_SHORT).show()
            return
        }

        val name = storage.nextAutoName()
        storage.save(GestureRecording(name, recordedStrokes.toList()))
        storage.setActive(name)
        Log.d(TAG, "stopRecording: saved ${recordedStrokes.size} strokes as '$name'")
        updateStatus("saved '$name'")
        Toast.makeText(this, "Saved as '$name' - rename it from the app screen", Toast.LENGTH_LONG).show()
    }

    private fun addReticle() {
        val metrics = resources.displayMetrics
        val sizePx = (RETICLE_SIZE_DP * metrics.density).toInt()
        val anchor = TargetReticleView(this, sizePx)
        val anchorParams = WindowManager.LayoutParams(
            sizePx, sizePx,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels - sizePx) / 2
            y = (metrics.heightPixels - sizePx) / 2
        }
        // Plain drag-to-move, same as the bubble: the dot only ever repositions itself, it never
        // tries to double as a swipe recorder, so there's no threshold to fight while pointing it.
        anchor.setOnTouchListener(DragTouchListener(anchorParams, anchor))
        windowManager.addView(anchor, anchorParams)
        reticleAnchorView = anchor
        reticleAnchorParams = anchorParams
    }

    private fun removeReticle() {
        reticleAnchorView?.let { runCatching { windowManager.removeView(it) } }
        reticleAnchorView = null
        reticleAnchorParams = null
    }

    private fun onSwipeButtonTapped() {
        Log.d(TAG, "swipe button tapped (isRecording=$isRecording isPlaying=$isPlaying)")
        if (isPlaying) return
        if (!isRecording) {
            Toast.makeText(this, "Start recording first, then tap this to log a swipe", Toast.LENGTH_SHORT).show()
            return
        }
        startSwipeCapture()
    }

    /**
     * Arms a one-shot, full-screen capture window: the next single down-drag-up performed
     * anywhere on the screen is logged as a swipe stroke and the window removes itself. It never
     * moves for the whole gesture, so its raw coordinates are inherently reliable - no window
     * repositioning during an active touch means none of the coordinate corruption that came from
     * trying to reuse the small reticle for this. Being full-screen also means the swipe happens
     * exactly where the user needs it (e.g. right over the list they want scrolled), not wherever
     * a small dot happened to start out.
     */
    private fun startSwipeCapture() {
        if (swipeCaptureView != null) return
        val metrics = resources.displayMetrics
        val overlay = View(this).apply {
            setBackgroundColor(Color.argb(40, 255, 82, 82))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        val minDistancePx = MIN_SWIPE_DISTANCE_DP * metrics.density
        var downElapsed = 0L
        val pathPoints = mutableListOf<TouchPoint>()
        overlay.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downElapsed = SystemClock.elapsedRealtime()
                    pathPoints.clear()
                    pathPoints.add(TouchPoint(event.rawX, event.rawY, 0L))
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    pathPoints.add(TouchPoint(event.rawX, event.rawY, SystemClock.elapsedRealtime() - downElapsed))
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val first = pathPoints.first()
                    val last = pathPoints.last()
                    val distance = kotlin.math.hypot((last.x - first.x).toDouble(), (last.y - first.y).toDouble())
                    if (distance >= minDistancePx) {
                        onSwipeRecorded(downElapsed, pathPoints.toList())
                    } else {
                        Log.d(TAG, "swipe capture: released with almost no movement, not logging")
                        Toast.makeText(this, "No movement detected - try again", Toast.LENGTH_SHORT).show()
                    }
                    endSwipeCapture()
                    true
                }
                else -> false
            }
        }
        runCatching { windowManager.addView(overlay, params) }
        swipeCaptureView = overlay
        Toast.makeText(this, "Swipe or scroll now, anywhere on screen", Toast.LENGTH_SHORT).show()
    }

    private fun endSwipeCapture() {
        swipeCaptureView?.let { runCatching { windowManager.removeView(it) } }
        swipeCaptureView = null
    }

    private fun onSwipeRecorded(downElapsed: Long, points: List<TouchPoint>) {
        val offset = downElapsed - recordingStartElapsed
        recordedStrokes.add(Stroke(startOffsetMs = offset, points = points))
        val first = points.first()
        val last = points.last()
        Log.d(
            TAG,
            "logged swipe #${recordedStrokes.size}: ${points.size} pts, " +
                "from (${first.x}, ${first.y}) to (${last.x}, ${last.y}), " +
                "dx=${last.x - first.x} dy=${last.y - first.y}, duration=${last.offsetMs}ms"
        )
        Log.v(TAG, "swipe #${recordedStrokes.size} full path: " + points.joinToString { "(${it.x},${it.y})@${it.offsetMs}ms" })
        Toast.makeText(this, "Swipe ${recordedStrokes.size} logged", Toast.LENGTH_SHORT).show()
    }

    // ---- playback ----

    private fun startPlayback() {
        val service = GestureAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "startPlayback: GestureAccessibilityService.instance is null - not bound/enabled")
            Toast.makeText(this, "Enable the AutoClick accessibility service first", Toast.LENGTH_LONG).show()
            return
        }
        val recording = storage.activeOrLatest()
        if (recording == null) {
            Log.w(TAG, "startPlayback: no saved recording found")
            Toast.makeText(this, "No recorded gesture yet", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d(TAG, "startPlayback: playing '${recording.name}' with ${recording.strokes.size} strokes, totalDuration=${recording.totalDurationMs}ms")
        isPlaying = true
        setButtonIcon(R.id.btnPlay, R.drawable.ic_pause)
        updateStatus("playing")
        service.play(recording) {
            Log.d(TAG, "startPlayback: playback finished")
            isPlaying = false
            setButtonIcon(R.id.btnPlay, R.drawable.ic_play)
            updateStatus(idleStatusText())
        }
    }

    private fun stopPlayback() {
        Log.d(TAG, "stopPlayback: user stopped playback")
        GestureAccessibilityService.instance?.stopPlayback()
        isPlaying = false
        setButtonIcon(R.id.btnPlay, R.drawable.ic_play)
        updateStatus(idleStatusText())
    }

    // ---- helpers ----

    private fun updateStatus(text: String) {
        bubbleView?.findViewById<TextView>(R.id.txtStatus)?.text = text
    }

    /** "idle" with the active recording's name, so the bubble shows what Play will run. */
    private fun idleStatusText(): String =
        storage.activeName()?.let { "idle ($it)" } ?: "idle"

    private fun setButtonIcon(viewId: Int, resId: Int) {
        bubbleView?.findViewById<ImageButton>(viewId)?.setImageResource(resId)
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
        private const val TAG = "AutoClick"
        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "autoclick_overlay"
        private const val RETICLE_SIZE_DP = 56
        private const val TAP_DURATION_MS = 60L
        private const val MIN_SWIPE_DISTANCE_DP = 8f
    }
}

/** Small draggable circular target the user positions over whatever they want tapped while recording. */
private class TargetReticleView(context: Context, private val sizePx: Int) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 82, 82)
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    init {
        // A plain View skips onDraw() by default unless this is cleared (the optimization
        // assumes a backgroundless view has nothing to paint).
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val r = sizePx / 2f
        canvas.drawCircle(r, r, r - 4f, fillPaint)
        canvas.drawCircle(r, r, r - 4f, strokePaint)
        canvas.drawLine(r, 8f, r, sizePx - 8f, strokePaint)
        canvas.drawLine(8f, r, sizePx - 8f, r, strokePaint)
    }

    /** Absolute on-screen coordinates of this view's center, matching what dispatchGesture expects. */
    fun centerScreenPosition(): Pair<Float, Float> {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return Pair(loc[0] + width / 2f, loc[1] + height / 2f)
    }
}
