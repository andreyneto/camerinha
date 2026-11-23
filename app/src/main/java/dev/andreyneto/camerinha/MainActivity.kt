package dev.andreyneto.camerinha

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.SoundEffectConstants
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme

sealed interface Screen {
    data object Grid : Screen
    data class Full(val index: Int) : Screen
    data class Settings(val start: SettingsStart = SettingsStart.List) : Screen
}

class MainActivity : ComponentActivity() {

    companion object {
        private val DIRECTIONS = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to View.FOCUS_UP,
            KeyEvent.KEYCODE_DPAD_DOWN to View.FOCUS_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to View.FOCUS_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to View.FOCUS_RIGHT,
        )

        private val CONFIRM_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        )

        /** Non-null while the app is on screen: lets the remote shortcut send it to the floating window. */
        var floatRequest: (() -> Unit)? = null
            private set
    }

    private lateinit var store: CameraStore
    private var cameras by mutableStateOf(emptyList<Camera>())
    private var screen by mutableStateOf<Screen>(Screen.Grid)
    private var started by mutableStateOf(false)
    private var inPip by mutableStateOf(false)
    /** Camera last shown in fullscreen: the grid focuses its tile (by id, so deleting or reordering cameras is safe). */
    private var lastViewedId by mutableStateOf<String?>(null)

    private val pipSupported by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = CameraStore(this)
        cameras = store.load()
        lastViewedId = store.lastViewedId
        addCameraFromIntent(intent)
        // Open straight on the camera watched last; Back from there shows the grid.
        showCamera(store.lastViewedId ?: store.lastCameraId)

        setContent {
            CamerinhaTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    val current = screen
                    BackHandler(enabled = current != Screen.Grid) { screen = Screen.Grid }
                    when {
                        // Streams only run while the activity is visible (PiP counts as visible).
                        !started -> Unit

                        current is Screen.Settings -> SettingsScreen(
                            cameras = cameras,
                            onChange = ::updateCameras,
                            start = current.start,
                            onExit = { screen = Screen.Grid },
                            onStartConsumed = { screen = Screen.Settings(SettingsStart.List) },
                        )

                        cameras.isEmpty() -> EmptyScreen(
                            onSearch = { screen = Screen.Settings(SettingsStart.Discovery) },
                            onAdd = { screen = Screen.Settings(SettingsStart.NewCamera) },
                        )

                        current is Screen.Full -> FullScreen(
                            cameras = cameras,
                            index = current.index.coerceIn(cameras.indices),
                            inPip = inPip,
                            // Read on every composition: the overlay permission can be granted while the app runs.
                            canFloat = pipSupported || android.provider.Settings.canDrawOverlays(this@MainActivity),
                            onIndex = ::showFull,
                            onPip = { enterPip() },
                        )

                        else -> GridScreen(
                            cameras = cameras,
                            inPip = inPip,
                            focusId = lastViewedId,
                            onOpen = ::showFull,
                            onSettings = {
                                // Opened by holding OK: the rest of that press must not click
                                // whatever gets focus on the settings screen.
                                swallowHeldConfirm = true
                                screen = Screen.Settings()
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Coming back from the floating window: continue with that camera in fullscreen.
        if (OverlayService.running) showCamera(store.lastCameraId)
        OverlayService.stop(this)
        started = true
        floatRequest = { enterPip(anyScreen = true) }
    }

    override fun onStop() {
        super.onStop()
        started = false
        floatRequest = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        addCameraFromIntent(intent)
    }

    /** Home button while watching: keep the cameras on screen as a PiP window. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    /** True from a long-press OK until that OK is released (see [dispatchKeyEvent]). */
    private var swallowHeldConfirm = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (swallowHeldConfirm && event.keyCode in CONFIRM_KEYS) {
            when {
                event.action == KeyEvent.ACTION_UP -> {
                    swallowHeldConfirm = false
                    return true
                }
                // A fresh press means the release was missed (e.g. settings opened by TalkBack): stop swallowing.
                event.repeatCount == 0 -> swallowHeldConfirm = false
                else -> return true
            }
        }
        val handled = super.dispatchKeyEvent(event)
        playSystemSound(event, handled)
        return handled
    }

    /** Whether the press of the direction key now going up already made its sound on the way down. */
    private var directionSounded = false

    /**
     * Views get the system's navigation and click sounds from the framework; Compose under D-pad
     * does not, so ask for them here. [View.playSoundEffect] follows the TV's "System sounds"
     * setting and volume. A key counts as acted on when the UI consumed it: focus moved, a row was
     * clicked, or a screen handled it itself (fullscreen switches cameras on key up).
     */
    private fun playSystemSound(event: KeyEvent, handled: Boolean) {
        val view = window.decorView
        val direction = DIRECTIONS[event.keyCode]
        when {
            direction != null && event.action == KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) directionSounded = false
                if (handled) {
                    directionSounded = true
                    view.playSoundEffect(SoundEffectConstants.getConstantForFocusDirection(direction, false))
                }
            }
            direction != null && event.action == KeyEvent.ACTION_UP ->
                if (handled && !directionSounded) {
                    view.playSoundEffect(SoundEffectConstants.getConstantForFocusDirection(direction, false))
                }
            event.keyCode in CONFIRM_KEYS && event.action == KeyEvent.ACTION_UP && handled ->
                view.playSoundEffect(SoundEffectConstants.CLICK)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            if (screen !is Screen.Settings) screen = Screen.Settings()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * @param anyScreen true for the remote shortcut, which floats a camera even from the grid;
     *   Home/OK only do it from fullscreen.
     */
    private fun enterPip(anyScreen: Boolean = false) {
        if (cameras.isEmpty() || inPip) return
        if (screen is Screen.Settings && !anyScreen) return
        if (pipSupported) {
            val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            runCatching { enterPictureInPictureMode(params) }
            return
        }
        // No system PiP on this TV: float one camera over other apps instead.
        val full = screen as? Screen.Full
        val camera = when {
            full != null -> cameras[full.index.coerceIn(cameras.indices)]
            anyScreen -> store.preferred(cameras)
            else -> null
        } ?: return
        if (OverlayService.running || !started) return
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                getString(R.string.overlay_permission_missing, packageName),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        OverlayService.start(this, camera)
        moveTaskToBack(true)
    }

    private fun showFull(index: Int) {
        screen = Screen.Full(index)
        cameras.getOrNull(index)?.let {
            lastViewedId = it.id
            store.lastViewedId = it.id
        }
    }

    private fun showCamera(id: String?) {
        val index = cameras.indexOfFirst { it.id == id }
        if (index >= 0) showFull(index)
    }

    private fun updateCameras(list: List<Camera>) {
        cameras = list
        store.save(list)
    }

    /**
     * Lets you add a camera without typing on the remote:
     * `adb shell am start -n dev.andreyneto.camerinha/.MainActivity --es name Sala --es url rtsp://… [--es sub rtsp://…]`
     * A camera with the same name is replaced.
     */
    private fun addCameraFromIntent(intent: Intent?) {
        val name = intent?.getStringExtra("name")?.trim().orEmpty()
        val url = intent?.getStringExtra("url")?.trim().orEmpty()
        if (name.isEmpty() || url.isEmpty()) return
        val sub = intent?.getStringExtra("sub")?.trim().orEmpty()
        val existing = cameras.firstOrNull { it.name == name }
        updateCameras(
            if (existing != null) cameras.map { if (it === existing) it.copy(url = url, subUrl = sub) else it }
            else cameras + Camera(name = name, url = url, subUrl = sub)
        )
        intent?.removeExtra("url")
    }
}
