package dev.andreyneto.camerinha

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** What a successful DESCRIBE told about the stream; 0 × 0 when the SDP doesn't say. */
data class RtspInfo(val width: Int, val height: Int)

/**
 * An `rtsp://[user[:password]@]host[:port]/rest` URL split without android.net.Uri, so passwords
 * typed with odd characters still work (the credentials end at the last '@', like [maskPassword]).
 */
internal class RtspAddress(
    val scheme: String,
    val username: String?,
    val password: String?,
    val host: String,
    val port: Int,
    /** Path and query, as written ("/av_stream/ch0", "/cam/realmonitor?channel=1&subtype=0"). */
    val rest: String,
    /** "rtsp://user:pass@host:554": the original text up to [rest]. */
    val prefix: String,
) {
    /** The URL without credentials: what goes on the request line. */
    val requestUrl: String
        get() = "$scheme://" + (if (':' in host) "[$host]" else host) + (if (port == DEFAULT_PORT) "" else ":$port") + rest.ifEmpty { "/" }

    companion object {
        const val DEFAULT_PORT = 554

        fun parse(url: String): RtspAddress? {
            val text = url.trim()
            val match = Regex("^(rtsps?)://(.*)$", RegexOption.IGNORE_CASE).find(text) ?: return null
            val scheme = match.groupValues[1].lowercase()
            val afterScheme = match.groupValues[2]
            // Credentials end at the last '@' before the path; '@' is rare in paths but common in passwords.
            val firstSlash = afterScheme.indexOfAny(charArrayOf('/', '?'))
            val at = afterScheme.lastIndexOf('@').let { if (firstSlash in 0 until it) afterScheme.lastIndexOf('@', firstSlash) else it }
            val userInfo = if (at >= 0) afterScheme.substring(0, at) else null
            val afterUser = if (at >= 0) afterScheme.substring(at + 1) else afterScheme
            val end = afterUser.indexOfAny(charArrayOf('/', '?')).let { if (it < 0) afterUser.length else it }
            val hostPort = afterUser.substring(0, end)
            val rest = afterUser.substring(end)
            val host: String
            val portText: String?
            if (hostPort.startsWith("[")) {
                host = hostPort.substringAfter('[').substringBefore(']')
                portText = hostPort.substringAfter("]", "").removePrefix(":").ifEmpty { null }
            } else {
                host = hostPort.substringBefore(':')
                portText = if (':' in hostPort) hostPort.substringAfter(':') else null
            }
            if (host.isEmpty()) return null
            val port = portText?.toIntOrNull() ?: DEFAULT_PORT
            return RtspAddress(
                scheme = scheme,
                username = userInfo?.substringBefore(':')?.let(::percentDecode),
                password = userInfo?.takeIf { ':' in it }?.substringAfter(':')?.let(::percentDecode),
                host = host,
                port = port,
                rest = rest,
                prefix = text.substring(0, text.length - rest.length),
            )
        }

        /** Like Uri.decode: %XX only ('+' stays a plus). Malformed escapes are kept as written. */
        fun percentDecode(text: String): String {
            if ('%' !in text) return text
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val hex = if (c == '%' && i + 2 < text.length) text.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (hex != null) {
                    out.write(hex)
                    i += 3
                } else {
                    out.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return out.toString("UTF-8")
        }
    }
}

/**
 * The smallest RTSP client that answers "does this URL play, and how big is it?": one DESCRIBE,
 * with Basic or Digest (MD5) auth when the camera asks. Blocks; never throws.
 */
object Rtsp {

    /** The stream's info when the camera answers 200 to DESCRIBE, null for anything else. */
    fun describe(url: String, timeoutMs: Int = 3_500): RtspInfo? =
        runCatching { describeOrThrow(url, timeoutMs) }.getOrNull()

    private fun describeOrThrow(url: String, timeoutMs: Int): RtspInfo? {
        val address = RtspAddress.parse(url) ?: return null
        // Cameras use self-signed certificates for rtsps; not worth a TLS dance for a probe.
        if (address.scheme != "rtsp") return null
        var socket = open(address, timeoutMs)
        try {
            var response = request(socket, address, cseq = 1, authorization = null)
            if (response.status == 401) {
                val username = address.username ?: return null
                val authorization = authorize(response.headers("www-authenticate"), address, username, address.password.orEmpty())
                    ?: return null
                response = try {
                    request(socket, address, cseq = 2, authorization = authorization)
                } catch (_: IOException) {
                    // Some cameras close the connection after a 401: retry on a fresh one.
                    runCatching { socket.close() }
                    socket = open(address, timeoutMs)
                    request(socket, address, cseq = 2, authorization = authorization)
                }
            }
            if (response.status != 200) return null
            return Sdp.size(response.body) ?: RtspInfo(0, 0)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun open(address: RtspAddress, timeoutMs: Int): Socket = Socket().apply {
        soTimeout = timeoutMs
        connect(InetSocketAddress(address.host, address.port), timeoutMs)
    }

    private class Response(val status: Int, private val headerLines: List<String>, val body: String) {
        fun headers(name: String): List<String> = headerLines
            .filter { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
            .map { it.substringAfter(':').trim() }
    }

    private fun request(socket: Socket, address: RtspAddress, cseq: Int, authorization: String?): Response {
        val text = buildString {
            append("DESCRIBE ${address.requestUrl} RTSP/1.0\r\n")
            append("CSeq: $cseq\r\n")
            append("User-Agent: Camerinha\r\n")
            append("Accept: application/sdp\r\n")
            if (authorization != null) append("Authorization: $authorization\r\n")
            append("\r\n")
        }
        socket.getOutputStream().apply {
            write(text.toByteArray(Charsets.UTF_8))
            flush()
        }
        val input = socket.getInputStream()
        val statusLine = readLine(input) ?: throw IOException("closed")
        val status = Regex("^RTSP/\\d\\.\\d\\s+(\\d{3})").find(statusLine)?.groupValues?.get(1)?.toInt()
            ?: throw IOException("not RTSP")
        val headers = ArrayList<String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            if (headers.size < 64) headers += line
        }
        val length = headers.firstOrNull { it.substringBefore(':').trim().equals("Content-Length", true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull()?.coerceIn(0, 64 * 1024) ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return Response(status, headers, String(body, 0, read, Charsets.UTF_8))
    }

    /** One CRLF- (or LF-) terminated line, ISO-8859-1; null at end of stream. */
    private fun readLine(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString("ISO-8859-1")
            if (b == '\n'.code) return bytes.toString("ISO-8859-1").trimEnd('\r')
            if (bytes.size() < 8 * 1024) bytes.write(b)
        }
    }

    /** Authorization header for the challenge(s) in WWW-Authenticate: Digest when offered, else Basic. */
    internal fun authorize(challenges: List<String>, address: RtspAddress, username: String, password: String): String? {
        val digest = challenges.firstOrNull { it.startsWith("Digest", ignoreCase = true) }
        if (digest != null) {
            val params = Regex("(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))").findAll(digest.substring(6))
                .associate { it.groupValues[1].lowercase() to it.groupValues[2].ifEmpty { it.groupValues[3] } }
            val realm = params["realm"] ?: return null
            val nonce = params["nonce"] ?: return null
            val uri = address.requestUrl
            val ha1 = md5("$username:$realm:$password")
            val ha2 = md5("DESCRIBE:$uri")
            val qop = params["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it.equals("auth", true) }
            return buildString {
                append("Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\"")
                if (qop != null) {
                    val cnonce = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                    val nc = "00000001"
                    append(", response=\"${md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")}\"")
                    append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
                } else {
                    append(", response=\"${md5("$ha1:$nonce:$ha2")}\"")
                }
                params["opaque"]?.let { append(", opaque=\"$it\"") }
                if (params["algorithm"] != null) append(", algorithm=MD5")
            }
        }
        if (challenges.any { it.startsWith("Basic", ignoreCase = true) }) {
            return "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        }
        return null
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Whether something accepts TCP on [host]:[port]: true, false when refused, null when it timed out. */
    internal fun reachable(host: String, port: Int, timeoutMs: Int = 1_500): Boolean? = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (_: ConnectException) {
        false
    } catch (_: IOException) {
        null
    }
}

/** Width and height from an SDP: explicit attributes first, else the H.264 SPS in sprop-parameter-sets. */
internal object Sdp {
    fun size(sdp: String): RtspInfo? {
        Regex("a=x-dimensions:\\s*(\\d+)\\s*,\\s*(\\d+)").find(sdp)?.let { return info(it.groupValues[1], it.groupValues[2]) }
        Regex("a=framesize:\\s*\\d+\\s+(\\d+)-(\\d+)").find(sdp)?.let { return info(it.groupValues[1], it.groupValues[2]) }
        val sps = Regex("sprop-parameter-sets=([A-Za-z0-9+/=]+)").find(sdp)?.groupValues?.get(1)
        if (sps != null) {
            runCatching { H264.size(Base64.getDecoder().decode(sps)) }.getOrNull()?.let { return it }
        }
        // a=cliprect:top,left,bottom,right
        Regex("a=cliprect:\\s*\\d+\\s*,\\s*\\d+\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)").find(sdp)?.let { return info(it.groupValues[2], it.groupValues[1]) }
        return null
    }

    private fun info(width: String, height: String): RtspInfo? {
        val w = width.toIntOrNull() ?: return null
        val h = height.toIntOrNull() ?: return null
        return if (w in 16..16384 && h in 16..16384) RtspInfo(w, h) else null
    }
}

/** Just enough of an H.264 sequence parameter set (ITU-T H.264 7.3.2.1.1) to get the cropped frame size. */
internal object H264 {
    private class Bits(private val data: ByteArray) {
        private var pos = 0
        fun bit(): Int {
            val byte = data[pos ushr 3].toInt()
            return (byte ushr (7 - (pos++ and 7))) and 1
        }
        fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
        fun ue(): Int {
            var zeros = 0
            while (bit() == 0) if (++zeros > 31) error("bad exp-golomb")
            return ((1 shl zeros) - 1) + bits(zeros)
        }
        fun se(): Int { val k = ue(); return if (k and 1 == 1) (k + 1) / 2 else -(k / 2) }
    }

    fun size(nal: ByteArray): RtspInfo? {
        // Drop emulation-prevention bytes (00 00 03 → 00 00).
        val raw = java.io.ByteArrayOutputStream()
        var zeros = 0
        for (b in nal) {
            val v = b.toInt() and 0xff
            if (zeros >= 2 && v == 3) { zeros = 0; continue }
            raw.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        val r = Bits(raw.toByteArray())
        if (r.bits(8) and 0x1f != 7) return null // not an SPS
        val profile = r.bits(8)
        r.bits(16) // constraint flags, level
        r.ue() // seq_parameter_set_id
        var chroma = 1
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = r.ue()
            if (chroma == 3) r.bit()
            r.ue(); r.ue(); r.bit() // bit depths, qpprime bypass
            if (r.bit() == 1) {
                repeat(if (chroma != 3) 8 else 12) { i ->
                    if (r.bit() == 1) {
                        var last = 8
                        var next = 8
                        repeat(if (i < 6) 16 else 64) {
                            if (next != 0) next = (last + r.se() + 256) % 256
                            if (next != 0) last = next
                        }
                    }
                }
            }
        }
        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) {
            0 -> r.ue()
            1 -> { r.bit(); r.se(); r.se(); repeat(r.ue()) { r.se() } }
        }
        r.ue(); r.bit() // max_num_ref_frames, gaps
        val widthMbs = r.ue() + 1
        val heightMapUnits = r.ue() + 1
        val frameMbsOnly = r.bit()
        if (frameMbsOnly == 0) r.bit()
        r.bit() // direct_8x8_inference
        var left = 0; var right = 0; var top = 0; var bottom = 0
        if (r.bit() == 1) { left = r.ue(); right = r.ue(); top = r.ue(); bottom = r.ue() }
        val cropX = if (chroma == 0 || chroma == 3) 1 else 2
        val cropY = (if (chroma == 1) 2 else 1) * (2 - frameMbsOnly)
        val width = widthMbs * 16 - cropX * (left + right)
        val height = (2 - frameMbsOnly) * heightMapUnits * 16 - cropY * (top + bottom)
        return if (width in 16..16384 && height in 16..16384) RtspInfo(width, height) else null
    }
}

/** Finding a camera's other streams: guessing its substream URL and assembling the options. */
object StreamFinder {

    private val boundary = "(?=$|[?&/._])"

    /** (pattern on path+query, replacement) pairs: how vendors usually name the second stream. */
    private val rules: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("(?i)(ch)0$boundary") to { m -> m.groupValues[1] + "1" },                         // av_stream/ch0 (SONOFF)
        Regex("_0(?=(\\.\\w+)?$)") to { _ -> "_1" },                                             // ch0_0.h264, live/ch00_0
        Regex("(?i)(stream)([01])$boundary") to { m -> m.groupValues[1] + (m.groupValues[2].toInt() + 1) }, // stream1 → stream2
        Regex("(?<=/)101(?=$|[?/])") to { _ -> "102" },                                          // Streaming/Channels/101
        Regex("(?<=/)11(?=$|[?/])") to { _ -> "12" },                                            // /11 → /12
        Regex("(?i)main") to { m -> caseLike(m.value, "sub") },                                  // mainstream, live/MAIN
        Regex("(?i)(subtype=)0") to { m -> m.groupValues[1] + "1" },                             // realmonitor?…&subtype=0
        Regex("(?i)(stream=)0") to { m -> m.groupValues[1] + "1" },
        Regex("(?i)(profile_?)([01])$boundary") to { m -> m.groupValues[1] + (m.groupValues[2].toInt() + 1) }, // profile1 → profile2
        Regex("(?i)(onvif)1$") to { m -> m.groupValues[1] + "2" },
        Regex("(?<=/)0$") to { _ -> "1" },                                                       // ch01/0 → ch01/1
    )

    private fun caseLike(original: String, replacement: String) = when {
        original.all { it.isUpperCase() } -> replacement.uppercase()
        original.first().isUpperCase() -> replacement.replaceFirstChar { it.uppercase() }
        else -> replacement
    }

    /** Likely substream URLs for [mainUrl], credentials kept; unverified. */
    fun candidates(mainUrl: String): List<String> {
        val address = RtspAddress.parse(mainUrl) ?: return emptyList()
        if (address.rest.isEmpty()) return emptyList()
        return rules.mapNotNull { (pattern, replace) ->
            val changed = pattern.replace(address.rest) { replace(it) }
            if (changed == address.rest) null else address.prefix + changed
        }.distinct()
    }

    /**
     * Candidates for [main] that answer DESCRIBE with 200, labelled [StreamOption.DETECTED].
     * Drops ones that report the same size as [main] (cameras that serve the main stream on any path).
     */
    suspend fun detect(main: StreamOption, known: Collection<String>): List<StreamOption> {
        val urls = candidates(main.url).filter { it !in known }
        val checked = verify(urls)
        return checked.mapNotNull { (url, info) ->
            info ?: return@mapNotNull null
            if (main.width > 0 && info.width == main.width && info.height == main.height) return@mapNotNull null
            StreamOption(url, info.width, info.height, StreamOption.DETECTED)
        }
    }

    /** DESCRIBE every URL in parallel. */
    suspend fun verify(urls: List<String>): List<Pair<String, RtspInfo?>> = coroutineScope {
        urls.map { url -> async(Dispatchers.IO) { url to Rtsp.describe(url) } }.awaitAll()
    }

    /** Distinct by URL (largest size kept), largest first, unknown sizes last. */
    fun sorted(options: List<StreamOption>): List<StreamOption> = options
        .groupBy { it.url }.map { (_, same) -> same.maxBy { it.area } }
        .sortedByDescending { it.area }

    /**
     * For a camera saved with only a URL: asks ONVIF on the URL's host with the URL's login (port 80;
     * 8000 / 8080 when 80 refuses), then falls back to probing substream guesses next to the URL.
     * @throws OnvifException when nothing beyond what the camera already has could be confirmed.
     */
    suspend fun find(url: String): List<StreamOption> {
        val address = RtspAddress.parse(url) ?: throw OnvifException(R.string.onvif_no_streams)
        val username = address.username.orEmpty()
        val password = address.password.orEmpty()
        var failure: Throwable? = null
        for (port in listOf(80, 8000, 8080)) {
            val reachable = Rtsp.reachable(address.host, port)
            if (reachable == false) continue // refused: try the next port
            if (reachable == null) break // silent: not worth waiting on more ports
            val authority = if (':' in address.host) "[${address.host}]" else address.host
            val service = "http://$authority" + (if (port == 80) "" else ":$port") + "/onvif/device_service"
            try {
                return Onvif.streams(OnvifDevice(address.host, service, ""), username, password)
            } catch (e: OnvifException) {
                failure = e
                break
            }
        }
        // No ONVIF: check the guesses next to the URL the camera already plays.
        val info = Rtsp.describe(url)
        val main = StreamOption(url, info?.width ?: 0, info?.height ?: 0)
        val extra = detect(main, listOf(url))
        if (extra.isEmpty()) throw failure ?: OnvifException(R.string.streams_none_found)
        return sorted(listOf(main) + extra)
    }
}
