package com.example.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import com.example.data.model.Song
import com.example.util.ArtworkManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class CastMediaUrls(
    val streamUrl: String,
    val artworkUrl: String?,
    val mimeType: String,
    val fileSizeBytes: Long
)

/**
 * Lightweight on-demand HTTP/1.1 LAN media server that streams ONLY the currently authorized
 * local song and its album artwork to Chromecast / Google TV / DLNA Smart TVs.
 *
 * Security:
 * - Binds on an ephemeral free port only while a cast session is active.
 * - Requires a cryptographically random 128-bit per-session token in the path.
 * - Strictly serves only the currently authorized Song ID (never arbitrary file paths).
 * - Supports HTTP HEAD and Range (206 Partial Content) for smooth TV seeking.
 */
class LocalCastMediaServer(private val context: Context) {

    @Volatile
    private var serverSocket: ServerSocket? = null

    private val running = AtomicBoolean(false)
    private var acceptThread: Thread? = null
    private var workerPool: ExecutorService? = null

    @Volatile
    private var sessionToken: String = generateToken()

    @Volatile
    private var authorizedSong: Song? = null

    @Volatile
    private var cachedArtworkSongId: Long = -1L

    @Volatile
    private var cachedArtworkBytes: ByteArray? = null

    val port: Int
        get() = serverSocket?.localPort ?: -1

    val isRunning: Boolean
        get() = running.get() && serverSocket != null && serverSocket?.isClosed == false

    fun ensureStarted(): Int {
        if (isRunning) return port
        stop()
        sessionToken = generateToken()
        val socket = ServerSocket(0)
        socket.reuseAddress = true
        serverSocket = socket
        running.set(true)
        workerPool = Executors.newCachedThreadPool { r ->
            Thread(r, "OffGridCastHttpWorker").apply { isDaemon = true }
        }
        acceptThread = Thread({
            while (running.get() && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    client.soTimeout = 15_000
                    workerPool?.execute { handleClient(client) }
                } catch (_: Throwable) {
                    if (!running.get()) break
                }
            }
        }, "OffGridCastHttpAccept").apply {
            isDaemon = true
            start()
        }
        return socket.localPort
    }

    fun stop() {
        running.set(false)
        authorizedSong = null
        cachedArtworkBytes = null
        cachedArtworkSongId = -1L
        try {
            serverSocket?.close()
        } catch (_: Throwable) {}
        serverSocket = null
        try {
            acceptThread?.interrupt()
        } catch (_: Throwable) {}
        acceptThread = null
        try {
            workerPool?.shutdownNow()
        } catch (_: Throwable) {}
        workerPool = null
    }

    fun prepareMediaForSong(song: Song, localIp: String): CastMediaUrls? {
        val boundPort = try {
            ensureStarted()
        } catch (_: Throwable) {
            return null
        }
        if (boundPort <= 0 || localIp.isBlank()) return null

        authorizedSong = song
        val artworkBytes = resolveArtworkBytes(song)
        cachedArtworkSongId = song.id
        cachedArtworkBytes = artworkBytes

        val ext = guessFileExtension(song)
        val mime = guessMimeType(ext)
        val size = resolveAudioSize(song)
        val baseUrl = "http://$localIp:$boundPort"
        val streamUrl = "$baseUrl/stream/$sessionToken/${song.id}.$ext"
        val artUrl = if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            "$baseUrl/art/$sessionToken/${song.id}.jpg"
        } else {
            null
        }
        return CastMediaUrls(
            streamUrl = streamUrl,
            artworkUrl = artUrl,
            mimeType = mime,
            fileSizeBytes = size
        )
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.trim().split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase(Locale.US)
                val rawPath = parts[1].substringBefore("?")

                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val sep = line.indexOf(':')
                    if (sep > 0) {
                        val key = line.substring(0, sep).trim().lowercase(Locale.US)
                        val value = line.substring(sep + 1).trim()
                        headers[key] = value
                    }
                }

                val out = BufferedOutputStream(socket.getOutputStream())
                if (method != "GET" && method != "HEAD") {
                    writeSimpleResponse(out, 405, "Method Not Allowed")
                    return
                }

                val segments = rawPath.trimStart('/').split('/')
                if (segments.size != 3) {
                    writeSimpleResponse(out, 404, "Not Found")
                    return
                }

                val kind = segments[0]
                val reqToken = segments[1]
                val reqIdStr = segments[2].substringBefore('.')
                val reqSongId = reqIdStr.toLongOrNull()

                if (reqToken != sessionToken || reqSongId == null) {
                    writeSimpleResponse(out, 403, "Forbidden")
                    return
                }

                val song = authorizedSong
                if (song == null || song.id != reqSongId) {
                    writeSimpleResponse(out, 404, "Not Found")
                    return
                }

                when (kind) {
                    "stream" -> serveAudioStream(song, method == "HEAD", headers["range"], out)
                    "art" -> serveArtwork(song, method == "HEAD", out)
                    else -> writeSimpleResponse(out, 404, "Not Found")
                }
            } catch (_: Throwable) {
                // Client disconnected mid-stream (normal on seek/stop)
            }
        }
    }

    private fun serveArtwork(song: Song, headOnly: Boolean, out: BufferedOutputStream) {
        val bytes = if (cachedArtworkSongId == song.id) cachedArtworkBytes else resolveArtworkBytes(song)
        if (bytes == null || bytes.isEmpty()) {
            writeSimpleResponse(out, 404, "No Artwork")
            return
        }
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: image/jpeg\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        if (!headOnly) {
            out.write(bytes)
        }
        out.flush()
    }

    private fun serveAudioStream(
        song: Song,
        headOnly: Boolean,
        rangeHeader: String?,
        out: BufferedOutputStream
    ) {
        val ext = guessFileExtension(song)
        val mime = guessMimeType(ext)
        val totalLength = resolveAudioSize(song)
        val file = song.path.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() && it.canRead() }

        if (totalLength > 0L && rangeHeader != null && rangeHeader.startsWith("bytes=", ignoreCase = true)) {
            val rangeSpec = rangeHeader.substringAfter("bytes=").substringBefore(",").trim()
            val dashIndex = rangeSpec.indexOf('-')
            if (dashIndex >= 0) {
                val startStr = rangeSpec.substring(0, dashIndex).trim()
                val endStr = rangeSpec.substring(dashIndex + 1).trim()
                val start = if (startStr.isEmpty()) {
                    val suffix = endStr.toLongOrNull() ?: 0L
                    (totalLength - suffix).coerceAtLeast(0L)
                } else {
                    startStr.toLongOrNull() ?: 0L
                }
                val end = if (endStr.isEmpty()) {
                    totalLength - 1L
                } else {
                    (endStr.toLongOrNull() ?: (totalLength - 1L)).coerceAtMost(totalLength - 1L)
                }

                if (start < 0L || start >= totalLength || end < start) {
                    val resp = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$totalLength\r\nConnection: close\r\n\r\n"
                    out.write(resp.toByteArray(Charsets.UTF_8))
                    out.flush()
                    return
                }

                val contentLength = (end - start) + 1L
                val header = buildString {
                    append("HTTP/1.1 206 Partial Content\r\n")
                    append("Content-Type: $mime\r\n")
                    append("Content-Length: $contentLength\r\n")
                    append("Content-Range: bytes $start-$end/$totalLength\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("transferMode.dlna.org: Streaming\r\n")
                    append("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(header.toByteArray(Charsets.UTF_8))
                if (!headOnly) {
                    if (file != null) {
                        streamFromRandomAccessFile(file, start, contentLength, out)
                    } else {
                        openAudioInputStream(song)?.use { input ->
                            skipFully(input, start)
                            copyBytes(input, out, contentLength)
                        }
                    }
                }
                out.flush()
                return
            }
        }

        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $mime\r\n")
            if (totalLength > 0L) {
                append("Content-Length: $totalLength\r\n")
            }
            append("Accept-Ranges: bytes\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("transferMode.dlna.org: Streaming\r\n")
            append("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        if (!headOnly) {
            if (file != null) {
                BufferedInputStream(FileInputStream(file), 64 * 1024).use { input ->
                    copyBytes(input, out, if (totalLength > 0L) totalLength else Long.MAX_VALUE)
                }
            } else {
                openAudioInputStream(song)?.use { input ->
                    copyBytes(input, out, if (totalLength > 0L) totalLength else Long.MAX_VALUE)
                }
            }
        }
        out.flush()
    }

    private fun streamFromRandomAccessFile(
        file: File,
        startOffset: Long,
        length: Long,
        out: BufferedOutputStream
    ) {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(startOffset)
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0L && running.get()) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read <= 0) break
                out.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    private fun openAudioInputStream(song: Song): InputStream? {
        val file = song.path.takeIf { it.isNotBlank() }?.let { File(it) }
        if (file != null && file.exists() && file.canRead()) {
            return BufferedInputStream(FileInputStream(file), 64 * 1024)
        }
        val uriStr = song.contentUriString.takeIf { it.isNotBlank() } ?: return null
        return try {
            context.contentResolver.openInputStream(Uri.parse(uriStr))?.let {
                BufferedInputStream(it, 64 * 1024)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveAudioSize(song: Song): Long {
        val file = song.path.takeIf { it.isNotBlank() }?.let { File(it) }
        if (file != null && file.exists() && file.canRead()) {
            val len = file.length()
            if (len > 0L) return len
        }
        val uriStr = song.contentUriString.takeIf { it.isNotBlank() } ?: return -1L
        return try {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(uriStr), "r")?.use { afd ->
                if (afd.length >= 0L) afd.length else -1L
            } ?: -1L
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun resolveArtworkBytes(song: Song): ByteArray? {
        return try {
            ArtworkManager.getCachedNotificationArtwork(song.id, song.path)
                ?: ArtworkManager.getNotificationArtworkBytes(
                    context = context,
                    songId = song.id,
                    filePath = song.path,
                    albumId = song.albumId,
                    artworkUriStr = song.artworkUri
                )
        } catch (_: Throwable) {
            null
        }
    }

    private fun skipFully(input: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val skipBuf = ByteArray(16 * 1024)
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val read = input.read(skipBuf, 0, minOf(skipBuf.size.toLong(), remaining).toInt())
                if (read <= 0) break
                remaining -= read
            }
        }
    }

    private fun copyBytes(input: InputStream, out: BufferedOutputStream, maxBytes: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = maxBytes
        while (remaining > 0L && running.get()) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read <= 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun writeSimpleResponse(out: BufferedOutputStream, code: Int, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        val resp = "HTTP/1.1 $code $message\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(resp.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    companion object {
        private val secureRandom = SecureRandom()

        fun generateToken(): String {
            val bytes = ByteArray(16)
            secureRandom.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun guessFileExtension(song: Song): String {
            val pathExt = song.path.substringAfterLast('.', "").lowercase(Locale.US)
                .takeIf { it.length in 2..5 && it.all { ch -> ch.isLetterOrDigit() } }
            return pathExt ?: "mp3"
        }

        fun guessMimeType(ext: String): String {
            return when (ext.lowercase(Locale.US)) {
                "mp3" -> "audio/mpeg"
                "m4a", "mp4", "aac" -> "audio/mp4"
                "flac" -> "audio/flac"
                "wav" -> "audio/wav"
                "ogg", "oga" -> "audio/ogg"
                "opus" -> "audio/opus"
                "webm" -> "audio/webm"
                "wma" -> "audio/x-ms-wma"
                else -> "audio/mpeg"
            }
        }

        fun isWifiConnected(context: Context): Boolean {
            return try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val activeNetwork = cm.activeNetwork ?: return false
                    val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                } else {
                    @Suppress("DEPRECATION")
                    val info = cm.activeNetworkInfo
                    @Suppress("DEPRECATION")
                    info != null && info.isConnected && (info.type == ConnectivityManager.TYPE_WIFI || info.type == ConnectivityManager.TYPE_ETHERNET)
                }
            } catch (_: Throwable) {
                false
            }
        }

        fun resolveLocalLanIpv4(context: Context): String? {
            // 1. Prefer LinkProperties from active Wi-Fi / Ethernet network
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val network = cm.activeNetwork
                    val caps = network?.let { cm.getNetworkCapabilities(it) }
                    if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                        val linkProps = cm.getLinkProperties(network)
                        val addr = linkProps?.linkAddresses
                            ?.mapNotNull { it.address as? Inet4Address }
                            ?.firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                            ?.hostAddress
                        if (!addr.isNullOrBlank()) return addr
                    }
                }
            } catch (_: Throwable) {}

            // 2. Fallback to scanning active non-loopback site-local IPv4 network interfaces
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
                var fallbackSiteLocal: String? = null
                while (interfaces.hasMoreElements()) {
                    val nif = interfaces.nextElement()
                    if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                    val name = nif.name.lowercase(Locale.US)
                    val addresses = nif.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                            val host = addr.hostAddress ?: continue
                            if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap")) {
                                return host
                            }
                            if (fallbackSiteLocal == null) {
                                fallbackSiteLocal = host
                            }
                        }
                    }
                }
                fallbackSiteLocal
            } catch (_: Throwable) {
                null
            }
        }
    }
}
