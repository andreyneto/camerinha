package dev.andreyneto.camerinha

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * @param url main stream, used in fullscreen.
 * @param subUrl optional low-res substream, used in the grid. TV boxes only have a few
 *   hardware decoders, so several main streams at once may not decode.
 */
data class Camera(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val subUrl: String = "",
    /** Every stream the camera is known to offer (ONVIF or detected), largest first; empty for manual cameras. */
    val streams: List<StreamOption> = emptyList(),
) {
    val gridUrl: String get() = subUrl.ifBlank { url }
}

/**
 * One stream a camera offers, credentials embedded in [url]. [width] / [height] are 0 when unknown.
 * [label] is the ONVIF profile name, [DETECTED] for a guessed URL that answered, or empty.
 */
data class StreamOption(val url: String, val width: Int = 0, val height: Int = 0, val label: String = "") {
    val area: Long get() = width.toLong() * height

    companion object {
        const val DETECTED = "detected"
    }
}

/**
 * The lightest option that is not the stream at [mainUrl]: what the grid and the floating window
 * should play. Null when there is none, or when it would be no lighter than the main one.
 */
fun List<StreamOption>.lighterThan(mainUrl: String): StreamOption? {
    if (isEmpty()) return null
    val main = firstOrNull { it.url == mainUrl } ?: first()
    val pick = lastOrNull { it.url != mainUrl && it.url != main.url } ?: return null
    if (main.area > 0 && pick.area > 0 && pick.area >= main.area) return null
    return pick
}

class CameraStore(context: Context) {
    private val prefs = context.getSharedPreferences("cameras", Context.MODE_PRIVATE)

    fun load(): List<Camera> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                Camera(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    url = o.getString("url"),
                    subUrl = o.optString("subUrl"),
                    streams = o.optJSONArray("streams")?.let { streams ->
                        List(streams.length()) { j ->
                            val s = streams.getJSONObject(j)
                            StreamOption(s.getString("url"), s.optInt("width"), s.optInt("height"), s.optString("label"))
                        }
                    }.orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(cameras: List<Camera>) {
        val array = JSONArray()
        cameras.forEach { c ->
            array.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("url", c.url)
                    .put("subUrl", c.subUrl)
                    .put("streams", JSONArray().apply {
                        c.streams.forEach { s ->
                            put(JSONObject().put("url", s.url).put("width", s.width).put("height", s.height).put("label", s.label))
                        }
                    })
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    /** Camera last shown in the floating window: the one it reopens with. */
    var lastCameraId: String?
        get() = prefs.getString(KEY_LAST, null)
        set(value) = prefs.edit().putString(KEY_LAST, value).apply()

    /** Camera last watched in fullscreen: the one the app opens on. */
    var lastViewedId: String?
        get() = prefs.getString(KEY_VIEWED, null)
        set(value) = prefs.edit().putString(KEY_VIEWED, value).apply()

    fun preferred(cameras: List<Camera>): Camera? =
        cameras.firstOrNull { it.id == lastCameraId } ?: cameras.firstOrNull()

    private companion object {
        const val KEY = "list"
        const val KEY_LAST = "lastCameraId"
        const val KEY_VIEWED = "lastViewedId"
    }
}

class RtspTarget(val uri: Uri, val username: String?, val password: String?)

/** Splits `rtsp://user:pass@host/path` into a credential-free URI plus credentials. */
fun parseRtsp(url: String): RtspTarget {
    val uri = Uri.parse(url.trim())
    val userInfo = uri.encodedUserInfo ?: return RtspTarget(uri, null, null)
    val username = Uri.decode(userInfo.substringBefore(':'))
    val password = if (':' in userInfo) Uri.decode(userInfo.substringAfter(':')) else null
    val authority = uri.encodedAuthority.orEmpty().substringAfterLast('@')
    return RtspTarget(uri.buildUpon().encodedAuthority(authority).build(), username, password)
}

/** For showing a URL on screen without the password. */
fun maskPassword(url: String): String =
    url.replace(Regex("(?<=://)([^:/@]+):.*@(?=[^@]*$)"), "$1:•••@")
