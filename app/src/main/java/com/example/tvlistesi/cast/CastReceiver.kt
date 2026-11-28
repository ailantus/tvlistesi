package com.example.tvlistesi.cast

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.tvlistesi.MainActivity
import com.example.tvlistesi.PlayerActivity
import com.example.tvlistesi.data.PlaylistParser
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale

/**
 * TV kutusunda çalışan uygulama içi yayın alıcısı.
 *
 * Telefondaki aynı uygulama UDP yayını ile alıcıyı bulur, 6 haneli kodu girer
 * ve seçtiği kanalın adresini bu sunucuya POST eder. Alıcı adresi yerel
 * oynatıcıda açar; harici DLNA cihazı gerekmez.
 */
object CastReceiver {

    private const val SERVER_PORT = 8977
    private const val DISCOVERY_PORT = 8978
    private const val MAGIC = "TVListesiCast"
    private const val SOCKET_TIMEOUT = 1_000

    private val mainHandler = Handler(Looper.getMainLooper())
    private val random = SecureRandom()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var pin: String = ""
        private set

    @Volatile
    var deviceName: String = ""
        private set

    @Volatile
    var localIp: String = ""
        private set

    /** TV oynatıcısı açıkken durdurma isteğini karşılar. */
    @Volatile
    var onStopRequest: (() -> Unit)? = null

    /** TV oynatıcısı açıkken uzaktan kontrol komutlarını karşılar. */
    @Volatile
    var controlHandler: ((String) -> Unit)? = null

    private var appContext: Context? = null
    private var serverSocket: ServerSocket? = null
    private var discoverySocket: DatagramSocket? = null
    private var serverThread: Thread? = null
    private var discoveryThread: Thread? = null

    @Synchronized
    fun start(context: Context) {
        if (isRunning) return
        appContext = context.applicationContext
        localIp = currentIp()
        // Gönderici cihazlar alıcıyı uygulama adıyla görsün.
        deviceName = "TvListesi"
        pin = (100_000 + random.nextInt(900_000)).toString()

        val discovery = try {
            DatagramSocket(DISCOVERY_PORT).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = SOCKET_TIMEOUT
            }
        } catch (error: Exception) {
            return
        }
        val server = try {
            ServerSocket(SERVER_PORT).apply {
                reuseAddress = true
                soTimeout = SOCKET_TIMEOUT
            }
        } catch (error: Exception) {
            discovery.close()
            return
        }

        discoverySocket = discovery
        serverSocket = server
        isRunning = true

        discoveryThread = Thread({ discoveryLoop(discovery) }, "tvlistesi-cast-discovery")
            .apply { isDaemon = true; start() }
        serverThread = Thread({ serverLoop(server) }, "tvlistesi-cast-server")
            .apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        onStopRequest = null
        controlHandler = null
        try {
            discoverySocket?.close()
        } catch (_: Exception) {
        }
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        discoverySocket = null
        serverSocket = null
        discoveryThread = null
        serverThread = null
    }

    /** Yerel ağ adresi; Wi-Fi veya Ethernet üzerinden döner. */
    fun currentIp(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ""
        while (interfaces.hasMoreElements()) {
            val ni = interfaces.nextElement() ?: continue
            if (!ni.isUp || ni.isLoopback || ni.isVirtual || ni.name.startsWith("dummy")) continue
            val addresses = ni.inetAddresses ?: continue
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement() ?: continue
                if (address is Inet4Address && !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress
                ) {
                    return address.hostAddress ?: ""
                }
            }
        }
        return ""
    }

    private fun discoveryLoop(socket: DatagramSocket) {
        val buffer = ByteArray(512)
        while (isRunning) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val message = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                if (!message.startsWith("$MAGIC|DISCOVER")) continue

                val reply = listOf(
                    MAGIC, "DEVICE", localIp, deviceName.replace('|', ' '),
                    Build.MODEL.replace('|', ' ')
                ).joinToString("|")
                val bytes = reply.toByteArray(StandardCharsets.UTF_8)
                socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
            } catch (_: SocketTimeoutException) {
                // Döngü koşulunu yeniden kontrol et.
            } catch (_: Exception) {
                if (!isRunning) break
            }
        }
    }

    private fun serverLoop(socket: ServerSocket) {
        while (isRunning) {
            try {
                val client = socket.accept()
                Thread({ serve(client) }, "tvlistesi-cast-client").apply {
                    isDaemon = true
                    start()
                }
            } catch (_: SocketTimeoutException) {
                // Döngü koşulunu yeniden kontrol et.
            } catch (_: Exception) {
                if (!isRunning) break
            }
        }
    }

    /** HTTP başlık satırını bayt bayt okur; BufferedReader gövdeyi tamponlayabilir. */
    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return null
            if (value == '\n'.code) break
            if (value != '\r'.code && line.size() < 8_192) line.write(value)
        }
        return line.toByteArray().toString(StandardCharsets.UTF_8)
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 5_000
            // Gövdeyi bayt bazında oku: Content-Length bayt sayısıdır, karakter sayısı değil.
            val input = socket.getInputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val path = parts.getOrNull(1).orEmpty()

            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val index = line.indexOf(':')
                if (index > 0) {
                    headers[line.substring(0, index).lowercase(Locale.ROOT)] =
                        line.substring(index + 1).trim()
                }
            }

            val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 16_384) ?: 0
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = input.read(bytes, read, length - read)
                if (count < 0) break
                read += count
            }
            val body = String(bytes, 0, read, StandardCharsets.UTF_8)

            when {
                path.startsWith("/ping") -> respond(
                    socket, 200, JSONObject()
                        .put("ok", true)
                        .put("name", deviceName)
                        .put("model", Build.MODEL)
                        .toString()
                )

                path.startsWith("/status") -> respond(
                    socket, 200, JSONObject()
                        .put("running", isRunning)
                        .put("name", deviceName)
                        .toString()
                )

                path.startsWith("/play") && method == "POST" -> handlePlay(socket, body)

                path.startsWith("/control") && method == "POST" -> {
                    val json = runCatching { JSONObject(body) }.getOrNull()
                    val action = json?.optString("action").orEmpty()
                    if (json == null || json.optString("pin") != pin) {
                        respond(socket, 403, JSONObject().put("error", "PIN hatalı").toString())
                    } else if (action !in CastAction.ALL) {
                        respond(
                            socket, 400,
                            JSONObject().put("error", "Geçersiz komut").toString()
                        )
                    } else {
                        respond(socket, 200, JSONObject().put("ok", true).toString())
                        mainHandler.post { controlHandler?.invoke(action) }
                    }
                }

                path.startsWith("/stop") && method == "POST" -> {
                    val json = runCatching { JSONObject(body) }.getOrNull()
                    if (json == null || json.optString("pin") != pin) {
                        respond(socket, 403, JSONObject().put("error", "PIN hatalı").toString())
                    } else {
                        respond(socket, 200, JSONObject().put("ok", true).toString())
                        mainHandler.post {
                            appContext?.let { ctx ->
                                val intent = Intent(ctx, MainActivity::class.java)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(intent)
                            }
                            onStopRequest?.invoke()
                        }
                    }
                }

                else -> respond(
                    socket, 404, JSONObject().put("error", "Bilinmeyen istek").toString()
                )
            }
        } catch (_: Exception) {
            // Bozuk isteği sessizce kapat; sunucu çalışmaya devam etsin.
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handlePlay(socket: Socket, body: String) {
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (json == null || json.optString("pin") != pin) {
            respond(socket, 403, JSONObject().put("error", "PIN hatalı").toString())
            return
        }
        val url = json.optString("url").trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            respond(socket, 400, JSONObject().put("error", "Geçersiz yayın adresi").toString())
            return
        }
        val title = json.optString("title").ifBlank { "Yansıtılan yayın" }
        val mime = json.optString("mime").ifBlank { PlaylistParser.mediaMimeType(url) }

        respond(socket, 200, JSONObject().put("ok", true).toString())
        val context = appContext ?: return
        mainHandler.post {
            PlayerActivity.startUrl(
                context = context,
                url = url,
                title = title,
                mimeType = mime,
                hideList = true
            )
        }
    }

    private fun respond(socket: Socket, code: Int, body: String) {
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            else -> "Not Found"
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = (
            "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.UTF_8)
        val output = socket.getOutputStream()
        output.write(header)
        output.write(bytes)
        output.flush()
    }
}
