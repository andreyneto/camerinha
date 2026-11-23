package dev.andreyneto.camerinha

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Shared between the settings screen and [ShortcutService] (same process, main thread). */
object Shortcut {
    /** The accessibility service is enabled and bound. */
    var connected by mutableStateOf(false)

    /** The next non-navigation key pressed becomes the shortcut. */
    var learning by mutableStateOf(false)

    /** Key that opens the app; 0 = none. */
    var keyCode by mutableIntStateOf(0)
        private set

    fun load(context: Context) {
        keyCode = prefs(context).getInt("keyCode", 0)
    }

    fun save(context: Context, code: Int) {
        keyCode = code
        prefs(context).edit().putInt("keyCode", code).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("shortcut", Context.MODE_PRIVATE)
}

/**
 * Turns a spare remote button (e.g. the Netflix one) into a Camerinha key:
 * press = floating window on/off (off opens the app), hold = resize the floating window.
 * Only an accessibility service gets to see and swallow a key before the system acts on it.
 * On TV it is enabled with:
 * `adb shell settings put secure enabled_accessibility_services dev.andreyneto.camerinha/.ShortcutService`
 * `adb shell settings put secure accessibility_enabled 1`
 */
class ShortcutService : AccessibilityService() {

    override fun onServiceConnected() {
        Shortcut.load(this)
        Shortcut.connected = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Shortcut.connected = false
        Shortcut.learning = false
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (Shortcut.learning) {
            // Never capture the keys needed to drive the TV.
            if (code in ESSENTIAL_KEYS) return false
            if (event.action == KeyEvent.ACTION_DOWN) {
                Shortcut.save(this, code)
                Shortcut.learning = false
            }
            return true
        }
        if (code == 0 || code != Shortcut.keyCode) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0 && !held) {
                held = true
                longPressed = false
                handler.postDelayed(longPress, LONG_PRESS_MS)
            }

            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                held = false
                if (!longPressed) toggle()
            }
        }
        return true
    }

    private val handler = Handler(Looper.getMainLooper())
    private var held = false
    private var longPressed = false

    /** Holding the button steps through the window sizes for as long as it is held. */
    private val longPress = object : Runnable {
        override fun run() {
            if (!held) return
            if (!OverlayService.running) return // nothing to resize: the release acts as a short press
            longPressed = true
            OverlayService.cycleSize(this@ShortcutService)
            handler.postDelayed(this, REPEAT_MS)
        }
    }

    /** Floating window showing -> open the app. Otherwise -> show the floating window. */
    private fun toggle() {
        val floatRequest = MainActivity.floatRequest
        when {
            OverlayService.running -> openApp()
            floatRequest != null -> floatRequest()
            else -> {
                val store = CameraStore(this)
                val camera = store.preferred(store.load())
                if (camera != null && Settings.canDrawOverlays(this)) OverlayService.start(this, camera) else openApp()
            }
        }
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private companion object {
        const val LONG_PRESS_MS = 500L
        const val REPEAT_MS = 800L
        val ESSENTIAL_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        )
    }
}
