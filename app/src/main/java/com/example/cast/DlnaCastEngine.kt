package com.example.cast

import android.content.Context
import android.net.wifi.WifiManager
import com.example.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

data class DlnaPlaybackStatus(
    val transportState: String,
    val positionMs: Long,
    val durationMs: Long
)

/**
 * Pure Kotlin DLNA / UPnP MediaRenderer discovery (SSDP) and AVTransport/RenderingControl SOAP client.
 * Enables casting directly to Samsung, LG WebOS, Sony Bravia, Roku, Xbox, Sonos, Kodi, and
 * other UPnP/DLNA Smart TVs and Wi-Fi speakers on the same Wi-Fi network.
 */
class DlnaCastEngine(private val context: Context) {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val xmlMediaType = "text/xml; charset=\"utf-8\"".toMediaType()

    suspend fun discoverRenderers(timeoutMs: Long = 2800L): List<CastDevice> = withContext(Dispatchers.IO) {
        val locations = LinkedHashSet<String>()
        var multicastLock: WifiManager.MulticastLock? = null
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("OffGridDlnaDiscoveryLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Throwable) {}

        val searchTargets = listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1"
        )

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                soTimeout = 450
                broadcast = true
            }
            val multicastAddress = InetAddress.getByName("239.255.255.250")
            for (st in searchTargets) {
                val payload = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: 239.255.255.250:1900\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 2\r\n")
                    append("ST: $st\r\n")
                    append("USER-AGENT: Android/OffGridMusic UPnP/1.1\r\n\r\n")
                }.toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(payload, payload.size, multicastAddress, 1900)
                try {
                    socket.send(packet)
                } catch (_: Throwable) {}
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val recvBuf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(recvBuf, recvBuf.size)
                try {
                    socket.receive(packet)
                    val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val location = parseSsdpLocation(response)
                    if (!location.isNullOrBlank()) {
                        locations.add(location)
                    }
                } catch (_: SocketTimeoutException) {
                    // Continue until deadline
                } catch (_: Throwable) {
                    break
                }
            }
        } catch (_: Throwable) {
            // Network unavailable or multicast blocked
        } finally {
            try { socket?.close() } catch (_: Throwable) {}
            try {
                if (multicastLock?.isHeld == true) multicastLock.release()
            } catch (_: Throwable) {}
        }

        val devices = LinkedHashMap<String, CastDevice>()
        for (location in locations) {
            val parsed = fetchAndParseDeviceDescription(location)
            if (parsed != null) {
                devices[parsed.id] = parsed
            }
        }
        devices.values.toList()
    }

    private fun parseSsdpLocation(response: String): String? {
        for (line in response.lineSequence()) {
            if (line.startsWith("LOCATION:", ignoreCase = true)) {
                return line.substringAfter(':').trim()
            }
        }
        return null
    }

    private fun fetchAndParseDeviceDescription(locationUrl: String): CastDevice? {
        return try {
            val request = Request.Builder().url(locationUrl).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val xml = response.body?.string() ?: return null
                parseDeviceDescriptionXml(locationUrl, xml)
            }
        } catch (_: Throwable) {
            null
        }
    }

    internal fun parseDeviceDescriptionXml(locationUrl: String, xml: String): CastDevice? {
        return try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var friendlyName = ""
            var manufacturer = ""
            var modelName = ""
            var udn = ""
            var avTransportControlUrl: String? = null
            var avTransportServiceType = "urn:schemas-upnp-org:service:AVTransport:1"
            var renderingControlUrl: String? = null

            var inService = false
            var currentServiceType = ""
            var currentControlUrl = ""
            var currentTag = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.substringAfter(':').lowercase(Locale.US)
                        currentTag = tag
                        if (tag == "service") {
                            inService = true
                            currentServiceType = ""
                            currentControlUrl = ""
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            if (inService) {
                                when (currentTag) {
                                    "servicetype" -> currentServiceType = text
                                    "controlurl" -> currentControlUrl = text
                                }
                            } else {
                                when (currentTag) {
                                    "friendlyname" -> if (friendlyName.isEmpty()) friendlyName = text
                                    "manufacturer" -> if (manufacturer.isEmpty()) manufacturer = text
                                    "modelname" -> if (modelName.isEmpty()) modelName = text
                                    "udn" -> if (udn.isEmpty()) udn = text
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name.substringAfter(':').lowercase(Locale.US)
                        if (tag == "service") {
                            if (currentServiceType.contains("AVTransport", ignoreCase = true) && currentControlUrl.isNotBlank()) {
                                avTransportControlUrl = resolveRelativeUrl(locationUrl, currentControlUrl)
                                avTransportServiceType = currentServiceType
                            } else if (currentServiceType.contains("RenderingControl", ignoreCase = true) && currentControlUrl.isNotBlank()) {
                                renderingControlUrl = resolveRelativeUrl(locationUrl, currentControlUrl)
                            }
                            inService = false
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }

            val controlUrl = avTransportControlUrl ?: return null
            val displayName = friendlyName.ifBlank { modelName.ifBlank { "Smart TV / DLNA Renderer" } }
            val subtitle = listOf(manufacturer, modelName)
                .filter { it.isNotBlank() && !displayName.contains(it, ignoreCase = true) }
                .distinct()
                .joinToString(" • ")
                .ifBlank { "DLNA / UPnP Media Renderer" }

            val combined = "$displayName $modelName $manufacturer".lowercase(Locale.US)
            val kind = when {
                combined.contains("speaker") || combined.contains("sonos") || combined.contains("soundbar") || combined.contains("homepod") || combined.contains("audio") -> CastDeviceKind.SPEAKER
                else -> CastDeviceKind.TV
            }

            CastDevice(
                id = "dlna_${udn.ifBlank { locationUrl }.hashCode()}",
                name = displayName,
                subtitle = subtitle,
                protocol = CastProtocol.DLNA_UPNP,
                kind = kind,
                dlnaAvTransportUrl = controlUrl,
                dlnaRenderingControlUrl = renderingControlUrl,
                dlnaServiceType = avTransportServiceType
            )
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun loadAndPlay(
        device: CastDevice,
        song: Song,
        mediaUrls: CastMediaUrls
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val controlUrl = device.dlnaAvTransportUrl
            ?: return@withContext Result.failure(IllegalStateException("Missing AVTransport endpoint"))
        val serviceType = device.dlnaServiceType

        val didl = buildDidlLiteMetadata(song, mediaUrls)
        val setUriBody = """
            <u:SetAVTransportURI xmlns:u="$serviceType">
                <InstanceID>0</InstanceID>
                <CurrentURI>${escapeXml(mediaUrls.streamUrl)}</CurrentURI>
                <CurrentURIMetaData>${escapeXml(didl)}</CurrentURIMetaData>
            </u:SetAVTransportURI>
        """.trimIndent()

        // Best-effort stop previous item before loading new URI
        sendSoapAction(
            controlUrl = controlUrl,
            serviceType = serviceType,
            action = "Stop",
            innerBody = "<u:Stop xmlns:u=\"$serviceType\"><InstanceID>0</InstanceID></u:Stop>"
        )

        val setResult = sendSoapAction(controlUrl, serviceType, "SetAVTransportURI", setUriBody)
        if (setResult.isFailure) return@withContext Result.failure(setResult.exceptionOrNull()!!)

        play(device)
    }

    suspend fun play(device: CastDevice): Result<Unit> = withContext(Dispatchers.IO) {
        val controlUrl = device.dlnaAvTransportUrl ?: return@withContext Result.failure(IllegalStateException("No endpoint"))
        val st = device.dlnaServiceType
        sendSoapAction(
            controlUrl = controlUrl,
            serviceType = st,
            action = "Play",
            innerBody = "<u:Play xmlns:u=\"$st\"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play>"
        ).map { Unit }
    }

    suspend fun pause(device: CastDevice): Result<Unit> = withContext(Dispatchers.IO) {
        val controlUrl = device.dlnaAvTransportUrl ?: return@withContext Result.failure(IllegalStateException("No endpoint"))
        val st = device.dlnaServiceType
        sendSoapAction(
            controlUrl = controlUrl,
            serviceType = st,
            action = "Pause",
            innerBody = "<u:Pause xmlns:u=\"$st\"><InstanceID>0</InstanceID></u:Pause>"
        ).map { Unit }
    }

    suspend fun stop(device: CastDevice): Result<Unit> = withContext(Dispatchers.IO) {
        val controlUrl = device.dlnaAvTransportUrl ?: return@withContext Result.failure(IllegalStateException("No endpoint"))
        val st = device.dlnaServiceType
        sendSoapAction(
            controlUrl = controlUrl,
            serviceType = st,
            action = "Stop",
            innerBody = "<u:Stop xmlns:u=\"$st\"><InstanceID>0</InstanceID></u:Stop>"
        ).map { Unit }
    }

    suspend fun seek(device: CastDevice, positionMs: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val controlUrl = device.dlnaAvTransportUrl ?: return@withContext Result.failure(IllegalStateException("No endpoint"))
        val st = device.dlnaServiceType
        val target = formatUpnpTime(positionMs.coerceAtLeast(0L))
        sendSoapAction(
            controlUrl = controlUrl,
            serviceType = st,
            action = "Seek",
            innerBody = "<u:Seek xmlns:u=\"$st\"><InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$target</Target></u:Seek>"
        ).map { Unit }
    }

    suspend fun setVolume(device: CastDevice, volume01: Float): Result<Unit> = withContext(Dispatchers.IO) {
        val controlUrl = device.dlnaRenderingControlUrl ?: return@withContext Result.success(Unit)
        val st = "urn:schemas-upnp-org:service:RenderingControl:1"
        val level = (volume01.coerceIn(0f, 1f) * 100f).toInt()
        sendSoapAction(
            controlUrl = controlUrl,
            serviceType = st,
            action = "SetVolume",
            innerBody = "<u:SetVolume xmlns:u=\"$st\"><InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$level</DesiredVolume></u:SetVolume>"
        ).map { Unit }
    }

    suspend fun queryPlaybackStatus(device: CastDevice): DlnaPlaybackStatus? = withContext(Dispatchers.IO) {
        val controlUrl = device.dlnaAvTransportUrl ?: return@withContext null
        val st = device.dlnaServiceType
        val transportXml = sendSoapAction(
            controlUrl = controlUrl,
            serviceType = st,
            action = "GetTransportInfo",
            innerBody = "<u:GetTransportInfo xmlns:u=\"$st\"><InstanceID>0</InstanceID></u:GetTransportInfo>"
        ).getOrNull() ?: return@withContext null

        val posXml = sendSoapAction(
            controlUrl = controlUrl,
            serviceType = st,
            action = "GetPositionInfo",
            innerBody = "<u:GetPositionInfo xmlns:u=\"$st\"><InstanceID>0</InstanceID></u:GetPositionInfo>"
        ).getOrNull().orEmpty()

        val state = extractXmlTagValue(transportXml, "CurrentTransportState").orEmpty().uppercase(Locale.US)
        val relTime = extractXmlTagValue(posXml, "RelTime")
        val duration = extractXmlTagValue(posXml, "TrackDuration")
        DlnaPlaybackStatus(
            transportState = state,
            positionMs = parseUpnpTimeMs(relTime),
            durationMs = parseUpnpTimeMs(duration)
        )
    }

    private fun sendSoapAction(
        controlUrl: String,
        serviceType: String,
        action: String,
        innerBody: String
    ): Result<String> {
        val envelope = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    $innerBody
  </s:Body>
</s:Envelope>"""
        return try {
            val request = Request.Builder()
                .url(controlUrl)
                .header("SOAPACTION", "\"$serviceType#$action\"")
                .post(envelope.toRequestBody(xmlMediaType))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    Result.success(body)
                } else {
                    Result.failure(IllegalStateException("TV returned HTTP ${response.code} for $action"))
                }
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private fun buildDidlLiteMetadata(song: Song, mediaUrls: CastMediaUrls): String {
        val durationAttr = if (song.durationMs > 0L) " duration=\"${formatUpnpTime(song.durationMs)}\"" else ""
        val sizeAttr = if (mediaUrls.fileSizeBytes > 0L) " size=\"${mediaUrls.fileSizeBytes}\"" else ""
        val artTag = mediaUrls.artworkUrl?.let { "<upnp:albumArtURI>${escapeXml(it)}</upnp:albumArtURI>" }.orEmpty()
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="${song.id}" parentID="0" restricted="1"><dc:title>${escapeXml(song.displayTitle)}</dc:title><dc:creator>${escapeXml(song.displayArtist)}</dc:creator><upnp:artist>${escapeXml(song.displayArtist)}</upnp:artist><upnp:album>${escapeXml(song.displayAlbum)}</upnp:album>$artTag<upnp:class>object.item.audioItem.musicTrack</upnp:class><res protocolInfo="http-get:*:${mediaUrls.mimeType}:DLNA.ORG_OP=01;DLNA.ORG_CI=0"$durationAttr$sizeAttr>${escapeXml(mediaUrls.streamUrl)}</res></item></DIDL-Lite>"""
    }

    companion object {
        fun resolveRelativeUrl(baseUrl: String, relativePath: String): String {
            return try {
                URI(baseUrl).resolve(relativePath).toString()
            } catch (_: Throwable) {
                if (relativePath.startsWith("http://") || relativePath.startsWith("https://")) {
                    relativePath
                } else {
                    val base = baseUrl.trimEnd('/')
                    val rel = relativePath.trimStart('/')
                    "$base/$rel"
                }
            }
        }

        fun escapeXml(input: String): String {
            return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
        }

        fun formatUpnpTime(ms: Long): String {
            val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        }

        fun parseUpnpTimeMs(raw: String?): Long {
            if (raw.isNullOrBlank()) return 0L
            val clean = raw.trim().substringBefore('.')
            val parts = clean.split(':')
            if (parts.size != 3) return 0L
            val h = parts[0].toLongOrNull() ?: return 0L
            val m = parts[1].toLongOrNull() ?: return 0L
            val s = parts[2].toLongOrNull() ?: return 0L
            return ((h * 3600L) + (m * 60L) + s) * 1000L
        }

        fun extractXmlTagValue(xml: String, tagName: String): String? {
            val regex = Regex("<(?:[a-zA-Z0-9_]+:)?$tagName[^>]*>([^<]*)</(?:[a-zA-Z0-9_]+:)?$tagName>", RegexOption.IGNORE_CASE)
            return regex.find(xml)?.groupValues?.getOrNull(1)?.trim()
        }
    }
}
