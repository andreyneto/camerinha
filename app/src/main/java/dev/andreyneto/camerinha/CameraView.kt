package dev.andreyneto.camerinha

import android.view.SurfaceHolder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.alexvas.rtsp.widget.RtspStatusListener
import com.alexvas.rtsp.widget.RtspSurfaceView
import kotlinx.coroutines.delay

sealed interface StreamStatus {
    data object Connecting : StreamStatus
    data object Playing : StreamStatus
    data class Failed(val reason: String) : StreamStatus
}

private const val RETRY_DELAY_MS = 3_000L
private const val FIRST_FRAME_TIMEOUT_MS = 15_000L

/** How much the centred overlay blocks show, so they always fit a grid tile (~169dp tall at 5–9 cameras, ~127dp at 10+). */
private enum class BlockSize { Full, Medium, Small }

private fun blockSize(height: Dp) = when {
    height >= 180.dp -> BlockSize.Full
    height >= 110.dp -> BlockSize.Medium
    else -> BlockSize.Small
}

/**
 * One live RTSP stream. Reconnects forever: every attempt gets a brand-new
 * [RtspSurfaceView], so a dying connection can never interfere with its replacement.
 */
@Composable
fun CameraView(
    url: String,
    name: String,
    audio: Boolean,
    muted: Boolean,
    showOverlay: Boolean,
    modifier: Modifier = Modifier,
    /**
     * Inset of the name chip from the view's bottom-left corner. Bottom-left because cameras burn their
     * timestamp into the top-left of the picture.
     */
    labelInsets: PaddingValues = PaddingValues(start = ChipInset, bottom = ChipInset),
    focused: Boolean = false,
    /** Shows where the picture does not cover the view (letterbox, before the first frame). */
    containerColor: Color = Color.Black,
    onStatus: (StreamStatus) -> Unit = {},
) {
    val context = LocalContext.current
    var attempt by remember(url) { mutableIntStateOf(0) }
    var status by remember(url) { mutableStateOf<StreamStatus>(StreamStatus.Connecting) }
    var ratio by remember(url) { mutableFloatStateOf(16f / 9f) }

    val currentOnStatus by rememberUpdatedState(onStatus)
    LaunchedEffect(status) { currentOnStatus(status) }

    LaunchedEffect(url, attempt, status) {
        when (status) {
            is StreamStatus.Failed -> {
                delay(RETRY_DELAY_MS)
                status = StreamStatus.Connecting
                attempt++
            }
            StreamStatus.Connecting -> {
                delay(FIRST_FRAME_TIMEOUT_MS)
                status = StreamStatus.Failed(context.getString(R.string.no_video))
            }
            StreamStatus.Playing -> Unit
        }
    }

    BoxWithConstraints(modifier.background(containerColor), contentAlignment = Alignment.Center) {
        val boxRatio = maxWidth / maxHeight
        key(url, attempt, audio) {
            AndroidView(
                modifier = Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = boxRatio > ratio),
                factory = { context ->
                    RtspSurfaceView(context).apply {
                        val target = parseRtsp(url)
                        setStatusListener(object : RtspStatusListener {
                            override fun onRtspStatusFailedUnauthorized() {
                                status = StreamStatus.Failed(context.getString(R.string.wrong_login))
                            }

                            override fun onRtspStatusFailed(message: String?) {
                                status = StreamStatus.Failed(context.getString(failureReason(message)))
                            }

                            override fun onRtspStatusDisconnected() {
                                if (status !is StreamStatus.Failed) status = StreamStatus.Failed(context.getString(R.string.disconnected))
                            }

                            override fun onRtspFirstFrameRendered() {
                                status = StreamStatus.Playing
                            }

                            override fun onRtspFrameSizeChanged(width: Int, height: Int) {
                                if (width > 0 && height > 0) ratio = width.toFloat() / height
                                // The decoder is producing pictures. Some codecs never report
                                // "first frame rendered", and waiting for it would make the
                                // watchdog restart a stream that is playing fine.
                                status = StreamStatus.Playing
                            }
                        })
                        // The decoder needs a live surface, so the stream follows the surface.
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                init(target.uri, target.username, target.password, "camerinha")
                                start(requestVideo = true, requestAudio = audio)
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                stop()
                            }
                        })
                    }
                },
                update = { it.volume = if (muted) 0f else 1f },
                onRelease = {
                    it.setStatusListener(null)
                    it.stop()
                },
            )
        }

        if (showOverlay) {
            when (val s = status) {
                // Name and state sit in the middle while there is no picture; the chip returns with it.
                StreamStatus.Connecting -> ConnectingBlock(name, blockSize(maxHeight))
                is StreamStatus.Failed -> StreamErrorBlock(name, s.reason, blockSize(maxHeight))
                // Anchored to the picture, not the cell: when the video doesn't fill it (a 16:9 feed
                // in a taller grid cell), the chip stays on the video instead of the empty band.
                StreamStatus.Playing -> Box(Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = boxRatio > ratio)) {
                    StatusChip(
                        name = name,
                        focused = focused,
                        modifier = Modifier.align(Alignment.BottomStart).padding(labelInsets),
                    )
                }
            }
        }
    }
}

/**
 * Centred name + "connecting…" while waiting for the first frame. Static: no spinner over video.
 * Full/Medium need ~76dp of height, Small ~60dp.
 */
@Composable
private fun ConnectingBlock(name: String, size: BlockSize) {
    Column(
        modifier = Modifier.widthIn(max = 520.dp).padding(if (size == BlockSize.Small) 8.dp else 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.connecting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * What went wrong with this stream, and that it is being retried. Sheds detail to fit its tile:
 * Full ~178dp of height (icon, name, two-line reason, "Trying again…"), Medium ~106dp (name, one-line
 * reason), Small ~48dp ("name · reason" on one line).
 */
@Composable
private fun StreamErrorBlock(name: String, reason: String, size: BlockSize) {
    val c = MaterialTheme.colorScheme
    val small = size == BlockSize.Small
    Column(
        modifier = Modifier
            .padding(if (small) 8.dp else 16.dp)
            .widthIn(max = 360.dp)
            .background(c.errorContainer, if (small) MaterialTheme.shapes.small else MaterialTheme.shapes.large)
            .padding(horizontal = if (small) 12.dp else 20.dp, vertical = if (small) 6.dp else 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (small) {
            Text(
                text = "$name · $reason",
                style = MaterialTheme.typography.labelLarge,
                color = c.onErrorContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            return@Column
        }
        if (size == BlockSize.Full) {
            Icon(
                painter = painterResource(R.drawable.ic_videocam_off),
                contentDescription = null,
                tint = c.onErrorContainer,
                modifier = Modifier.padding(bottom = 4.dp).size(24.dp),
            )
        }
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            color = c.onErrorContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = reason,
            style = MaterialTheme.typography.bodyMedium,
            color = c.onErrorContainer,
            textAlign = TextAlign.Center,
            maxLines = if (size == BlockSize.Full) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (size == BlockSize.Full) {
            Text(
                text = stringResource(R.string.retrying),
                style = MaterialTheme.typography.bodyMedium,
                color = c.onErrorContainer.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Socket and RTSP error texts are for developers; turn the common ones into something a viewer can act on. */
private fun failureReason(message: String?): Int {
    val text = message.orEmpty().lowercase()
    return when {
        "timed out" in text || "timeout" in text -> R.string.camera_timeout
        "failed to connect" in text || "refused" in text || "unreachable" in text ||
            "no route" in text || "unknownhost" in text || "unable to resolve" in text -> R.string.camera_unreachable
        else -> R.string.connection_failed
    }
}
