package dev.andreyneto.camerinha

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import com.alexvas.rtsp.codec.VideoDecoderSurfaceThread
import com.alexvas.rtsp.widget.RtspProcessor
import com.alexvas.rtsp.widget.RtspStatusListener
import com.limelight.binding.video.MediaCodecHelper

/**
 * Same low-latency player as the library's RtspSurfaceView, but on a [TextureView]: the picture
 * is a normal view, so it can be rounded, faded, shadowed, animated and read back with
 * [getBitmap]. Costs about one frame of extra latency; used only for the floating window.
 */
class RtspTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    private var surface: Surface? = null
    private var pending: (() -> Unit)? = null

    private val processor = RtspProcessor(
        onVideoDecoderCreateRequested = { mimeType, rotation, frameQueue, listener, decoderType, stabilization ->
            VideoDecoderSurfaceThread(
                // The texture can vanish while the connection is being set up; decode into a
                // throwaway surface rather than crash the RTSP thread.
                surface ?: Surface(SurfaceTexture(0)),
                mimeType, 1920, 1080, rotation, frameQueue, listener, decoderType, stabilization,
            )
        }
    )

    init {
        MediaCodecHelper.initialize(context, "")
        surfaceTextureListener = this
    }

    /** Starts video-only playback as soon as the texture exists. */
    fun play(target: RtspTarget, listener: RtspStatusListener) {
        val start = {
            processor.statusListener = listener
            processor.init(target.uri, target.username, target.password, "camerinha")
            processor.start(requestVideo = true, requestAudio = false)
        }
        if (surface != null) start() else pending = start
    }

    fun release() {
        pending = null
        processor.statusListener = null
        processor.stop()
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(texture)
        pending?.invoke()
        pending = null
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        processor.stop()
        processor.stopDecoders()
        surface?.release()
        surface = null
        return true
    }
}
