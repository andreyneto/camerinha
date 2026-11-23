package dev.andreyneto.camerinha

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.alexvas.rtsp.widget.RtspStatusListener
import kotlin.math.abs

/**
 * Home-made picture-in-picture for TVs without the system feature (e.g. Chromecast with
 * Google TV): one camera in a small always-on-top window over whatever else is playing.
 * Needs the "display over other apps" permission, which on TV is granted with
 * `adb shell appops set dev.andreyneto.camerinha SYSTEM_ALERT_WINDOW allow`.
 * The window takes no input; opening the app again closes it.
 *
 * In automatic mode the picture reacts to motion: large, opaque and shadowed while something
 * moves; small and slightly see-through once the scene has been still for a while.
 */
class OverlayService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val motionHandler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private val prefs by lazy { getSharedPreferences("overlay", Context.MODE_PRIVATE) }

    /** The window: transparent, fixed at the largest size, so animations never touch the window manager. */
    private var root: FrameLayout? = null

    /** The visible picture, pinned to the window's top-right corner and resized inside it. */
    private var picture: FrameLayout? = null
    private var player: RtspTextureView? = null

    /** Camera name and "connecting…" over the picture until the first frame arrives. */
    private var loading: TextView? = null

    /** Short-lived pill naming the size mode after a change. */
    private var pill: TextView? = null

    /** 1dp hairline around the picture, visible only while resting. */
    private val hairline = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
    private var url: String = ""
    private var name: String = ""
    private var ratio = 16f / 9f

    /** Picture height as a fraction of the screen height (animated). */
    private var fraction = HEIGHTS.last()

    /** 1 = full attention (opaque, shadow), 0 = resting (see-through, flat). Animated. */
    private var emphasis = 1f
    private var animator: ValueAnimator? = null

    /** [AUTO], or an index into [HEIGHTS] for a fixed size. */
    private var mode: Int
        get() = prefs.getInt("mode", AUTO).coerceIn(AUTO, HEIGHTS.lastIndex)
        set(value) = prefs.edit().putInt("mode", value).apply()

    // Motion detection state.
    private var hasPicture = false
    private var active = true
    private var lastMotionAt = 0L
    private val sample: Bitmap by lazy { Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888) }
    private val pixels = IntArray(SAMPLE_W * SAMPLE_H)
    private val luma = IntArray(SAMPLE_W * SAMPLE_H)
    private var hasPrevious = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESIZE) {
            if (root == null) stopSelf() else nextMode()
            return START_NOT_STICKY
        }
        val newUrl = intent?.getStringExtra(EXTRA_URL)
        if (newUrl.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        url = newUrl
        name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        running = true
        showWindow()
        connect()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        motionHandler.removeCallbacksAndMessages(null)
        animator?.cancel()
        pill?.let {
            it.removeCallbacks(hidePill)
            it.animate().cancel()
        }
        player?.release()
        root?.let { runCatching { windowManager.removeView(it) } }
        player = null
        loading = null
        pill = null
        picture = null
        root = null
        super.onDestroy()
    }

    // ---- window -------------------------------------------------------------------------

    private fun showWindow() {
        if (root != null) return
        val screen = resources.displayMetrics
        val shadowRoom = dp(SHADOW_ROOM_DP)
        val margin = ((screen.widthPixels * 0.025f).toInt() - shadowRoom).coerceAtLeast(0)
        val params = WindowManager.LayoutParams(
            windowWidth(),
            (screen.heightPixels * HEIGHTS.last()).toInt() + 2 * shadowRoom,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not FLAG_NOT_TOUCHABLE: Android then forces untrusted overlays down to 80% opacity.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // TextureView only works in a hardware-accelerated window.
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = margin
            y = margin
        }

        val fixed = mode
        fraction = if (fixed == AUTO) HEIGHTS.last() else HEIGHTS[fixed]
        emphasis = 1f
        active = true
        lastMotionAt = SystemClock.elapsedRealtime()

        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadius())
                }
            }
            hairline.setStroke(dp(1f).coerceAtLeast(1), HAIRLINE_COLOR)
            foreground = hairline
        }
        val window = FrameLayout(this).apply {
            setPadding(shadowRoom, shadowRoom, shadowRoom, shadowRoom)
            clipChildren = false
            clipToPadding = false
            addView(frame, FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.END))
        }
        picture = frame
        root = window
        layoutPicture()
        windowManager.addView(window, params)
        motionHandler.postDelayed(motionCheck, MOTION_INTERVAL_MS)
    }

    private fun windowWidth(): Int =
        (resources.displayMetrics.heightPixels * HEIGHTS.last() * ratio).toInt() + 2 * dp(SHADOW_ROOM_DP)

    private fun animateTo(targetFraction: Float, targetEmphasis: Float) {
        animator?.cancel()
        val fromFraction = fraction
        val fromEmphasis = emphasis
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_MS
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                val t = it.animatedValue as Float
                fraction = fromFraction + (targetFraction - fromFraction) * t
                emphasis = fromEmphasis + (targetEmphasis - fromEmphasis) * t
                layoutPicture()
            }
            start()
        }
    }

    /** Applies [fraction] and [emphasis]: size, corner radius, opacity and shadow. */
    private fun layoutPicture() {
        val frame = picture ?: return
        val height = (resources.displayMetrics.heightPixels * fraction).toInt()
        frame.layoutParams = (frame.layoutParams as FrameLayout.LayoutParams).apply {
            this.height = height
            width = (height * ratio).toInt()
        }
        frame.invalidateOutline()
        frame.alpha = RESTING_ALPHA + (1f - RESTING_ALPHA) * emphasis
        // The shadow only belongs to the fully opaque state; it fades in with it.
        frame.elevation = dp(SHADOW_ELEVATION_DP) * emphasis
        // The hairline is the resting state's edge; it fades out as the shadow fades in.
        hairline.cornerRadius = cornerRadius()
        hairline.alpha = ((1f - emphasis) * 255).toInt().coerceIn(0, 255)
    }

    /** Material 3 shape scale by size: "medium" (12dp) when smallest up to "extra large" (28dp). */
    private fun cornerRadius(): Float {
        val t = ((fraction - HEIGHTS.first()) / (HEIGHTS.last() - HEIGHTS.first())).coerceIn(0f, 1f)
        return (RADIUS_MIN_DP + (RADIUS_MAX_DP - RADIUS_MIN_DP) * t) * resources.displayMetrics.density
    }

    /** Hold on the shortcut button: automatic -> small -> medium -> large -> automatic. */
    private fun nextMode() {
        val next = if (mode == HEIGHTS.lastIndex) AUTO else mode + 1
        mode = next
        active = true
        lastMotionAt = SystemClock.elapsedRealtime()
        animateTo(if (next == AUTO) HEIGHTS.last() else HEIGHTS[next], 1f)
        showPill(
            getString(
                when (next) {
                    AUTO -> R.string.overlay_mode_auto
                    0 -> R.string.overlay_mode_small
                    1 -> R.string.overlay_mode_medium
                    else -> R.string.overlay_mode_large
                }
            )
        )
    }

    /** Names the new size mode at the bottom of the picture for a moment, then fades away. */
    private fun showPill(text: String) {
        val frame = picture ?: return
        val view = pill ?: TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            background = GradientDrawable().apply {
                setColor(PILL_COLOR)
                cornerRadius = 1000f
            }
            alpha = 0f
            pill = this
        }
        if (view.parent == null) {
            frame.addView(
                view,
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = dp(12f)
                },
            )
        }
        view.bringToFront()
        view.text = text
        view.removeCallbacks(hidePill)
        view.animate().cancel()
        view.animate().alpha(1f).setDuration(FADE_MS).start()
        view.postDelayed(hidePill, FADE_MS + PILL_HOLD_MS)
    }

    private val hidePill = Runnable { pill?.animate()?.alpha(0f)?.setDuration(FADE_MS)?.start() }

    private fun showLoading(frame: FrameLayout) {
        val label = getString(R.string.overlay_connecting)
        val text = SpannableStringBuilder().apply {
            if (name.isNotBlank()) {
                append(name, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append('\n')
            }
            append(label)
        }
        val view = loading ?: TextView(this).apply {
            setBackgroundColor(LOADING_COLOR)
            setTextColor(LOADING_TEXT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            loading = this
        }
        view.text = text
        if (view.parent == null) frame.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        pill?.bringToFront()
    }

    private fun hideLoading() {
        loading?.let { (it.parent as? FrameLayout)?.removeView(it) }
    }

    // ---- stream -------------------------------------------------------------------------

    /** Same policy as CameraView: a fresh player per attempt, retry forever. */
    private fun connect() {
        val frame = picture ?: return
        handler.removeCallbacksAndMessages(null)
        player?.let {
            it.release()
            frame.removeView(it)
        }
        hasPicture = false
        hasPrevious = false

        val view = RtspTextureView(this)
        player = view
        // Underneath the loading label and the mode pill.
        frame.addView(view, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        showLoading(frame)
        view.play(parseRtsp(url), object : RtspStatusListener {
            override fun onRtspStatusFailedUnauthorized() = retry()
            override fun onRtspStatusFailed(message: String?) = retry()
            override fun onRtspStatusDisconnected() = retry()
            override fun onRtspFirstFrameRendered() {
                handler.removeCallbacksAndMessages(null)
                handler.post {
                    hasPicture = true
                    hideLoading()
                }
            }

            override fun onRtspFrameSizeChanged(width: Int, height: Int) {
                // Also proof that pictures are coming: some codecs never report the first
                // rendered frame, and the watchdog must not restart a stream that plays fine.
                handler.removeCallbacksAndMessages(null)
                handler.post {
                    hasPicture = true
                    hideLoading()
                    resizeTo(width, height)
                }
            }
        })
        // No picture after a while counts as a failure too.
        handler.postDelayed(::connect, FIRST_FRAME_TIMEOUT_MS)
    }

    private fun retry() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(::connect, RETRY_DELAY_MS)
    }

    private fun resizeTo(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val newRatio = videoWidth.toFloat() / videoHeight
        if (newRatio == ratio) return
        ratio = newRatio
        val window = root ?: return
        val params = window.layoutParams as WindowManager.LayoutParams
        params.width = windowWidth()
        runCatching { windowManager.updateViewLayout(window, params) }
        layoutPicture()
    }

    // ---- motion -------------------------------------------------------------------------

    private val motionCheck = object : Runnable {
        override fun run() {
            motionHandler.postDelayed(this, MOTION_INTERVAL_MS)
            if (mode != AUTO) return
            val now = SystemClock.elapsedRealtime()
            if (sawMotion()) {
                lastMotionAt = now
                if (!active) {
                    active = true
                    animateTo(HEIGHTS.last(), 1f)
                }
            } else if (active && now - lastMotionAt > STILL_AFTER_MS) {
                active = false
                animateTo(HEIGHTS.first(), 0f)
            }
        }
    }

    /**
     * Compares a tiny greyscale copy of the current frame with the previous one. A pixel counts
     * as changed past [PIXEL_THRESHOLD]; enough changed pixels is motion. The thresholds are set
     * to ignore sensor noise and the camera's on-screen clock.
     */
    private fun sawMotion(): Boolean {
        val view = player ?: return false
        if (!hasPicture || !view.isAvailable) return false
        view.getBitmap(sample)
        sample.getPixels(pixels, 0, SAMPLE_W, 0, 0, SAMPLE_W, SAMPLE_H)
        var changed = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            val y = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
            if (hasPrevious && abs(y - luma[i]) > PIXEL_THRESHOLD) changed++
            luma[i] = y
        }
        val first = !hasPrevious
        hasPrevious = true
        return !first && changed > pixels.size * CHANGED_FRACTION
    }

    // ---- misc ---------------------------------------------------------------------------

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.overlay_channel), NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_active))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"
        private const val ACTION_RESIZE = "resize"
        private const val CHANNEL = "overlay"

        /** Picture height as a fraction of the screen height: smallest .. largest. */
        private val HEIGHTS = floatArrayOf(1f / 6f, 1f / 4f, 1f / 3f)
        private const val AUTO = -1

        private const val ANIMATION_MS = 450L
        private const val RESTING_ALPHA = 0.75f
        private const val RADIUS_MIN_DP = 12f
        private const val RADIUS_MAX_DP = 28f
        private const val SHADOW_ELEVATION_DP = 12f
        private const val SHADOW_ROOM_DP = 24f

        private const val MOTION_INTERVAL_MS = 500L
        private const val STILL_AFTER_MS = 10_000L
        private const val SAMPLE_W = 96
        private const val SAMPLE_H = 54
        private const val PIXEL_THRESHOLD = 22
        private const val CHANGED_FRACTION = 0.012f

        private const val RETRY_DELAY_MS = 3_000L
        private const val FIRST_FRAME_TIMEOUT_MS = 15_000L
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT

        private const val LOADING_COLOR = 0xFF271813.toInt()
        private const val LOADING_TEXT_COLOR = 0xFFF9DCD4.toInt()
        private const val PILL_COLOR = 0xB8000000.toInt()
        private const val HAIRLINE_COLOR = 0x1FFFFFFF // 12% white
        private const val FADE_MS = 180L
        private const val PILL_HOLD_MS = 1_500L

        @Volatile
        var running = false
            private set

        fun start(context: Context, camera: Camera) {
            // Remembered so the remote shortcut reopens the same camera next time.
            CameraStore(context).lastCameraId = camera.id
            val intent = Intent(context, OverlayService::class.java)
                .putExtra(EXTRA_URL, camera.gridUrl)
                .putExtra(EXTRA_NAME, camera.name)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        /** Next size mode for the window that is on screen. */
        fun cycleSize(context: Context) {
            if (running) context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_RESIZE))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
