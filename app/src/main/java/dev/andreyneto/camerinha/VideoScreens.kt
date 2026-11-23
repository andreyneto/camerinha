package dev.andreyneto.camerinha

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.sqrt

/** Outer margin and gutter of the camera grid. */
private val GridGap = 8.dp

/** Fake corner radius of a grid tile, and the focus stroke drawn inside it. */
private val TileRadius = 20.dp
private val FocusStroke = 4.dp

/** Clearance between the name chip row and a bottom-centre card above it. */
private val ChipGap = 12.dp

private const val HINT_MS = 6_000L
private const val BANNER_MS = 2_500L
private const val FADE_MS = 180

/** The fullscreen remote hint shows on the first fullscreen entry of the process only. */
private var fullHintShown = false

@Composable
fun GridScreen(
    cameras: List<Camera>,
    inPip: Boolean,
    /** Camera whose tile takes focus (the one last watched); the first tile when it is gone. */
    focusId: String?,
    onOpen: (Int) -> Unit,
    onSettings: () -> Unit,
) {
    val columns = ceil(sqrt(cameras.size.toDouble())).toInt().coerceAtLeast(1)
    // Resolved by id, so deleting or reordering cameras never lands on the wrong tile.
    val focusIndex = cameras.indexOfFirst { it.id == focusId }.coerceAtLeast(0)
    val focus = rememberInitialFocus(focusId)
    var showHint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(HINT_MS)
        showHint = false
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(GridGap).focusGroup(),
            verticalArrangement = Arrangement.spacedBy(GridGap),
        ) {
            cameras.chunked(columns).forEachIndexed { rowIndex, row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GridGap)) {
                    row.forEachIndexed { columnIndex, camera ->
                        val index = rowIndex * columns + columnIndex
                        CameraTile(
                            camera = camera,
                            position = index + 1,
                            count = cameras.size,
                            inPip = inPip,
                            onClick = { onOpen(index) },
                            onLongClick = onSettings,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .then(if (index == focusIndex) Modifier.focusRequester(focus) else Modifier),
                        )
                    }
                    // Keep cells the same size when the last row is not full.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        HintCard(
            items = listOf(stringResource(R.string.hint_open), stringResource(R.string.hint_settings)),
            visible = showHint && !inPip,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Above the bottom row's name chips, so it never covers one.
                .padding(start = Safe.horizontal, end = Safe.horizontal, bottom = GridGap + ChipInset + ChipHeight + ChipGap),
        )
    }
}

@Composable
private fun CameraTile(
    camera: Camera,
    position: Int,
    count: Int,
    inPip: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<StreamStatus>(StreamStatus.Connecting) }
    val description = stringResource(R.string.camera_position, camera.name, position, count)
    val state = when (val s = status) {
        StreamStatus.Playing -> stringResource(R.string.status_live)
        StreamStatus.Connecting -> stringResource(R.string.connecting)
        is StreamStatus.Failed -> s.reason
    }
    val settingsLabel = stringResource(R.string.a11y_open_settings)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .semantics {
                contentDescription = description
                stateDescription = state
                customActions = listOf(CustomAccessibilityAction(settingsLabel) { onLongClick(); true })
            },
        // The video is a SurfaceView: it is never clipped, rounded or scaled. Corners and focus are drawn over it.
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Tones.containerLow,
            focusedContainerColor = Tones.containerLow,
            pressedContainerColor = Tones.containerLow,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
    ) {
        Box(Modifier.fillMaxSize()) {
            CameraView(
                url = camera.gridUrl,
                name = camera.name,
                audio = false,
                muted = true,
                showOverlay = !inPip,
                focused = focused,
                containerColor = Tones.containerLow,
                onStatus = { status = it },
                modifier = Modifier.fillMaxSize(),
            )
            if (!inPip) {
                TileChrome(
                    focused = { focused },
                    corner = colors.background,
                    stroke = colors.primary,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

/**
 * Rounded look for a rectangular video tile: paints the four corner wedges in the screen colour and, when
 * focused, a primary stroke. Paths are built once per size; focus only re-runs the draw lambda.
 */
@Composable
private fun TileChrome(focused: () -> Boolean, corner: Color, stroke: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier.drawWithCache {
            val radius = TileRadius.toPx()
            val width = FocusStroke.toPx()
            val bounds = Rect(Offset.Zero, size)
            val wedges = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(bounds)
                addRoundRect(RoundRect(bounds, CornerRadius(radius)))
            }
            val ring = Path().apply {
                fillType = PathFillType.EvenOdd
                addRoundRect(RoundRect(bounds, CornerRadius(radius)))
                addRoundRect(RoundRect(bounds.deflate(width), CornerRadius(radius - width)))
            }
            onDrawBehind {
                drawPath(wedges, corner)
                if (focused()) drawPath(ring, stroke)
            }
        }
    )
}

/** One camera on its main stream. Left/right = switch, OK = floating window, Back = grid. */
@Composable
fun FullScreen(
    cameras: List<Camera>,
    index: Int,
    inPip: Boolean,
    canFloat: Boolean,
    onIndex: (Int) -> Unit,
    onPip: () -> Unit,
) {
    val camera = cameras[index]
    val focus = rememberInitialFocus()
    val position = stringResource(R.string.camera_position, camera.name, index + 1, cameras.size)

    var showHint by remember { mutableStateOf(!fullHintShown) }
    LaunchedEffect(Unit) {
        fullHintShown = true
        delay(HINT_MS)
        showHint = false
    }

    // The banner announces a switch, not the camera opened from the grid.
    val entering = remember { booleanArrayOf(true) }
    var showBanner by remember { mutableStateOf(false) }
    LaunchedEffect(index) {
        if (entering[0]) {
            entering[0] = false
            return@LaunchedEffect
        }
        showHint = false
        showBanner = true
        delay(BANNER_MS)
        showBanner = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .semantics {
                contentDescription = position
                liveRegion = LiveRegionMode.Polite
            }
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> onIndex((index + 1) % cameras.size)
                    Key.DirectionLeft -> onIndex((index - 1 + cameras.size) % cameras.size)
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> onPip()
                    else -> return@onKeyEvent false
                }
                true
            }
    ) {
        CameraView(
            url = camera.url,
            name = camera.name,
            audio = true,
            muted = inPip,
            showOverlay = !inPip,
            labelInsets = PaddingValues(start = Safe.horizontal, bottom = Safe.vertical),
            modifier = Modifier.fillMaxSize(),
        )
        // The chip can reach ~570dp from the left, into the centred cards: they sit above the chip row.
        val bottom = Modifier
            .align(Alignment.BottomCenter)
            .padding(start = Safe.horizontal, end = Safe.horizontal, bottom = Safe.vertical + ChipHeight + ChipGap)
        HintCard(
            items = listOfNotNull(
                stringResource(R.string.hint_switch),
                if (canFloat) stringResource(R.string.hint_float) else null,
                stringResource(R.string.hint_grid),
            ),
            visible = showHint && !inPip,
            modifier = bottom,
        )
        AnimatedVisibility(
            visible = showBanner && !inPip,
            modifier = bottom,
            enter = fadeIn(tween(FADE_MS)),
            exit = fadeOut(tween(FADE_MS)),
        ) {
            SwitchBanner(
                name = camera.name,
                position = stringResource(R.string.position_short, index + 1, cameras.size),
            )
        }
    }
}
