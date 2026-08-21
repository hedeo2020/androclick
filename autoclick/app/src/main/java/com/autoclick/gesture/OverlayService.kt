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
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
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
 * Owns the floating control bubble and, while recording, a small draggable
 * target reticle. Recording works by point placement rather than live touch
 * capture: a full-screen capture window would have to consume every touch to
 * log it, which also blocks it from reaching the real app underneath (there
 * is no way to both log and pass through a touch with a single overlay
 * window, short of root). The reticle is a small window instead, so only its
 * own bounds intercept touches - the rest of the screen stays fully usable
 * while you position it.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var storage: RecordingStorage

    private var bubbleView: View? = null

    // The reticle is two separate overlay windows. The anchor is the actual touch source and
    // never moves mid-drag, so its raw coordinates stay reliable; the ghost is a purely visual,
    // non-touchable, full-screen window that draws the whole drag path so far (not just a dot)
    // so the user can see exactly where they are relative to where they started.
    private var reticleAnchorView: TargetReticleView? = null
    private var reticleAnchorParams: WindowManager.LayoutParams? = null
    private var reticleGhostView: SwipeTrailView? = null

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
            "Short drag + check = log a tap. Long drag (like a real scroll) = logs a swipe.",
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
        val swipeThresholdPx = SWIPE_THRESHOLD_DP * metrics.density
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
        anchor.setOnTouchListener(ReticleTouchListener(anchorParams, anchor, sizePx, swipeThresholdPx))
        windowManager.addView(anchor, anchorParams)
        reticleAnchorView = anchor
        reticleAnchorParams = anchorParams
    }

    private fun removeReticle() {
        removeGhost()
        reticleAnchorView?.let { runCatching { windowManager.removeView(it) } }
        reticleAnchorView = null
        reticleAnchorParams = null
    }

    /**
     * Adds the non-touchable ghost window covering the whole screen, starting the trail at the
     * finger's down position. It's full-screen so the trail can be drawn anywhere without ever
     * moving the window itself - since FLAG_NOT_TOUCHABLE excludes it from touch dispatch
     * entirely, its size has no effect on what the rest of the screen can still receive.
     */
    private fun showGhost(dotRadiusPx: Float, screenX: Float, screenY: Float) {
        val ghost = SwipeTrailView(this, dotRadiusPx)
        ghost.reset(screenX, screenY)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        runCatching { windowManager.addView(ghost, params) }
        reticleGhostView = ghost
    }

    /** Extends the drawn trail to the finger's current position - no window move, just a redraw. */
    private fun moveGhost(screenX: Float, screenY: Float) {
        reticleGhostView?.addPoint(screenX, screenY)
    }

    private fun removeGhost() {
        reticleGhostView?.let { runCatching { windowManager.removeView(it) } }
        reticleGhostView = null
    }

    /**
     * Tracks a drag starting on the anchor and records the full path in absolute screen
     * coordinates. A short drag (under [swipeThresholdPx]) just repositions the anchor for a
     * later tap; a longer, deliberate drag is logged as a swipe stroke on release.
     *
     * The anchor itself never moves until the single [WindowManager.updateViewLayout] call on
     * release (and only for the tap-reposition case) - it is the window supplying the raw
     * coordinates being recorded, and repositioning a window on every move event while that same
     * touch sequence is active is a known source of corrupted raw coordinates (the
     * window-manager IPC to relocate a window can lag a frame behind the next touch sample).
     * Live visual feedback during the drag instead comes from a separate non-touchable ghost
     * window ([showGhost]/[moveGhost]) that follows the finger - since it never receives touch
     * input itself, moving it every frame carries no such risk.
     */
    private inner class ReticleTouchListener(
        private val params: WindowManager.LayoutParams,
        private val view: View,
        private val sizePx: Int,
        private val swipeThresholdPx: Float
    ) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var downParamX = 0
        private var downParamY = 0
        private var downElapsed = 0L
        private val pathPoints = mutableListOf<TouchPoint>()

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downParamX = params.x
                    downParamY = params.y
                    downElapsed = SystemClock.elapsedRealtime()
                    pathPoints.clear()
                    pathPoints.add(TouchPoint(event.rawX, event.rawY, 0L))
                    showGhost(sizePx / 2f, event.rawX, event.rawY)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    moveGhost(event.rawX, event.rawY)
                    pathPoints.add(TouchPoint(event.rawX, event.rawY, SystemClock.elapsedRealtime() - downElapsed))
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    removeGhost()
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    val distance = kotlin.math.hypot(dx.toDouble(), dy.toDouble())
                    if (distance >= swipeThresholdPx) {
                        onSwipeRecorded(downElapsed, pathPoints.toList())
                    } else {
                        params.x = downParamX + dx.toInt()
                        params.y = downParamY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    return true
                }
            }
            return false
        }
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
        private const val SWIPE_THRESHOLD_DP = 24f
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

/**
 * Fills its (full-screen) window with the drag path recorded so far, plus a dot at the current
 * fingertip, so the user can see exactly where they are relative to where the drag started -
 * standing in for the fact that the real app underneath doesn't visually scroll while a gesture
 * is only being recorded, not yet played back.
 */
private class SwipeTrailView(context: Context, private val dotRadiusPx: Float) : View(context) {

    private val trailPoints = mutableListOf<PointF>()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 82, 82)
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 82, 82)
        style = Paint.Style.FILL
    }
    private val dotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    init {
        setWillNotDraw(false)
    }

    fun reset(x: Float, y: Float) {
        trailPoints.clear()
        trailPoints.add(PointF(x, y))
        invalidate()
    }

    fun addPoint(x: Float, y: Float) {
        trailPoints.add(PointF(x, y))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (trailPoints.size >= 2) {
            val path = Path()
            path.moveTo(trailPoints[0].x, trailPoints[0].y)
            for (i in 1 until trailPoints.size) path.lineTo(trailPoints[i].x, trailPoints[i].y)
            canvas.drawPath(path, linePaint)
        }
        trailPoints.lastOrNull()?.let { p ->
            canvas.drawCircle(p.x, p.y, dotRadiusPx, dotFillPaint)
            canvas.drawCircle(p.x, p.y, dotRadiusPx, dotStrokePaint)
        }
    }
}
