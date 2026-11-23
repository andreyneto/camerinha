package dev.andreyneto.camerinha

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.util.Base64
import androidx.annotation.StringRes
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** An ONVIF camera that answered the discovery probe. */
data class OnvifDevice(val host: String, val serviceUrl: String, val name: String)

/** A failure with a message meant for the user, as a string resource plus its arguments. */
class OnvifException(@StringRes val messageId: Int, vararg val args: Any) : Exception()

/**
 * Just enough ONVIF to find cameras on the LAN and ask them for their RTSP URLs.
 * All functions block; call them off the main thread.
 */
object Onvif {

    /** WS-Discovery: multicast a probe and collect whoever answers within [timeoutMs]. */
    fun discover(context: Context, timeoutMs: Int = 4_000): List<OnvifDevice> {
        val probe = """<?xml version="1.0" encoding="UTF-8"?><e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl"><e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"""
            .toByteArray()

        // Wi-Fi drops incoming multicast/broadcast replies unless a lock is held.
        val lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("camerinha-onvif")
        runCatching { lock?.acquire() }
        val found = LinkedHashMap<String, OnvifDevice>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { socket.send(DatagramPacket(probe, probe.size, group, 3702)) }
                val buffer = ByteArray(65_535)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val xml = String(packet.data, 0, packet.length)
                    val host = packet.address.hostAddress ?: continue
                    val addresses = Regex("XAddrs>([^<]+)<").find(xml)?.groupValues?.get(1)
                        ?.trim()?.split(Regex("\\s+")).orEmpty()
                    // Prefer the address on the IP that actually answered (skips IPv6/link-local ones).
                    val serviceUrl = addresses.firstOrNull { host in it }
                        ?: addresses.firstOrNull { it.startsWith("http") }
                        ?: continue
                    val scope = Regex("onvif://www\\.onvif\\.org/(?:name|hardware)/([^\\s<]+)").find(xml)
                    found[host] = OnvifDevice(host, serviceUrl, Uri.decode(scope?.groupValues?.get(1) ?: context.getString(R.string.default_camera_name)))
                }
            }
        } finally {
            runCatching { lock?.release() }
        }
        return found.values.sortedBy { it.host }
    }

    /**
     * Every RTSP stream the camera offers, credentials embedded, largest first (unknown sizes last).
     * When its profiles collapse onto one URL (SONOFF: Profile_1 640×360 reports the main stream's
     * path) or there is only one, common substream URLs next to it are probed too.
     * @throws OnvifException when the camera refuses, fails or has nothing to offer.
     */
    suspend fun streams(device: OnvifDevice, username: String, password: String): List<StreamOption> {
        // Cameras reject logins whose timestamp is off, and their clocks are often wrong:
        // sign with the camera's own idea of "now".
        val clockOffsetMs = runCatching {
            parseCameraTime(soap(device.serviceUrl, "<tds:GetSystemDateAndTime/>", null)) - System.currentTimeMillis()
        }.getOrDefault(0L)
        val auth = Auth(username, password, clockOffsetMs)

        val mediaUrl = runCatching {
            val capabilities = soap(
                device.serviceUrl,
                "<tds:GetCapabilities><tds:Category>Media</tds:Category></tds:GetCapabilities>",
                auth,
            )
            Regex("Media>.*?XAddr>\\s*(https?://[^<\\s]+)", RegexOption.DOT_MATCHES_ALL).find(capabilities)?.groupValues?.get(1)
        }.getOrNull()?.let { keepHost(it, device) } ?: device.serviceUrl

        val profiles = soap(mediaUrl, "<trt:GetProfiles/>", auth)
        val blocks = Regex("<[\\w:]*Profiles[\\s>].*?</[\\w:]*Profiles>", RegexOption.DOT_MATCHES_ALL)
            .findAll(profiles).map { it.value }.toList()
        if (blocks.isEmpty()) throw OnvifException(R.string.onvif_no_streams)

        val found = blocks.mapNotNull { block ->
            val token = Regex("^<[\\w:]*Profiles[^>]*\\stoken=\"([^\"]+)\"").find(block)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val width = Regex("Width>(\\d+)<").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val height = Regex("Height>(\\d+)<").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val answer = runCatching {
                soap(
                    mediaUrl,
                    "<trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream>" +
                        "<tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup>" +
                        "<trt:ProfileToken>${escape(token)}</trt:ProfileToken></trt:GetStreamUri>",
                    auth,
                )
            }.getOrNull() ?: return@mapNotNull null
            // Anchored on the scheme: "<MediaUri> <Uri>rtsp://…" has a whitespace-only match first.
            val uri = Regex("Uri>\\s*(rtsps?://[^<\\s]+)").find(answer)?.groupValues?.get(1) ?: return@mapNotNull null
            val name = Regex("Name>([^<]+)<").find(block)?.groupValues?.get(1)?.trim()?.let(::unescape) ?: token
            StreamOption(withCredentials(unescape(uri), device, username, password), width, height, name)
        }
        if (found.isEmpty()) throw OnvifException(R.string.onvif_no_streams)

        val unique = StreamFinder.sorted(found)
        val sizes = found.filter { it.area > 0 }.map { it.width to it.height }.distinct()
        if (unique.size > 1 && unique.size >= sizes.size) return unique
        // Rtsp.describe never throws, so this only adds what answered.
        val detected = StreamFinder.detect(unique.first(), unique.map { it.url })
        return StreamFinder.sorted(unique + detected)
    }

    private class Auth(val username: String, val password: String, val clockOffsetMs: Long)

    private fun soap(url: String, body: String, auth: Auth?): String {
        val header = if (auth == null) "" else {
            val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(System.currentTimeMillis() + auth.clockOffsetMs))
            val digest = MessageDigest.getInstance("SHA-1")
                .digest(nonce + created.toByteArray() + auth.password.toByteArray())
            """<s:Header><wsse:Security s:mustUnderstand="1" xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"><wsse:UsernameToken><wsse:Username>${escape(auth.username)}</wsse:Username><wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">${b64(digest)}</wsse:Password><wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">${b64(nonce)}</wsse:Nonce><wsu:Created>$created</wsu:Created></wsse:UsernameToken></wsse:Security></s:Header>"""
        }
        val envelope = """<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">$header<s:Body>$body</s:Body></s:Envelope>"""

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
        }
        try {
            connection.outputStream.use { it.write(envelope.toByteArray()) }
            val code = connection.responseCode
            val text = (if (code < 400) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code == 401 || "NotAuthorized" in text || "FailedAuthentication" in text) {
                throw OnvifException(R.string.wrong_login)
            }
            if (code >= 400) throw OnvifException(R.string.onvif_http_error, code)
            return text
        } catch (e: java.io.IOException) {
            throw OnvifException(R.string.onvif_no_answer, e.message.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun parseCameraTime(xml: String): Long {
        val utc = Regex("UTCDateTime>(.*?)</[\\w:]*UTCDateTime>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: error("no time in answer")
        fun field(name: String) = Regex("$name>(\\d+)<").find(utc)?.groupValues?.get(1)?.toInt()
            ?: error("no time in answer")
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(field("Year"), field("Month") - 1, field("Day"), field("Hour"), field("Minute"), field("Second"))
        }.timeInMillis
    }

    /** Cameras behind NAT or with stale config advertise URLs on the wrong host; trust the one that answered. */
    private fun keepHost(url: String, device: OnvifDevice): String {
        val uri = Uri.parse(unescape(url.trim()))
        if (uri.host == device.host) return uri.toString()
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return uri.buildUpon().encodedAuthority(device.host + port).build().toString()
    }

    private fun withCredentials(rtspUrl: String, device: OnvifDevice, username: String, password: String): String {
        val uri = Uri.parse(rtspUrl)
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val userInfo = if (username.isEmpty()) "" else Uri.encode(username) + ":" + Uri.encode(password) + "@"
        return uri.buildUpon().encodedAuthority(userInfo + device.host + port).build().toString()
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun unescape(text: String) = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
}
