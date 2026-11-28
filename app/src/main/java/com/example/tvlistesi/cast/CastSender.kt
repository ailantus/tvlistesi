package com.example.tvlistesi.cast

import com.example.tvlistesi.data.Channel
import com.example.tvlistesi.data.PlaylistParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets

/** Telefondan aynı ağdaki TV Listesi alıcısına kanal gönderir. */
object CastSender {

    private const val SERVER_PORT = 8977
    private const val DISCOVERY_PORT = 8978
    private const val MAGIC = "TVListesiCast"

    /** UDP yayını ile ağdaki alıcıları bulur. */
    suspend fun discover(): List<CastDevice> = withContext(Dispatchers.IO) {
        val socket = try {
            DatagramSocket().apply {
                broadcast = true
                soTimeout = 250
            }
        } catch (_: Exception) {
            return@withContext emptyList()
        }

        try {
            val message = "$MAGIC|DISCOVER".toByteArray(StandardCharsets.UTF_8)
            val targets = mutableListOf(InetAddress.getByName("255.255.255.255"))
            val local = CastReceiver.currentIp()
            val octets = local.split('.')
            if (octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }) {
                targets += InetAddress.getByName("${octets[0]}.${octets[1]}.${octets[2]}.255")
            }
            targets.distinct().forEach { target ->
                runCatching {
                    socket.send(DatagramPacket(message, message.size, target, DISCOVERY_PORT))
                }
            }

            val deadline = System.currentTimeMillis() + 2_200
            val found = LinkedHashMap<String, CastDevice>()
            val buffer = ByteArray(512)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                    val device = parseDiscoveryReply(text, local) ?: continue
                    found[device.ip] = device
                } catch (_: Exception) {
                    // Zaman aşımı veya bozuk paket; taramaya devam et.
                }
            }
            found.values.toList()
        } finally {
            socket.close()
        }
    }

    internal fun parseDiscoveryReply(text: String, localIp: String): CastDevice? {
        if (!text.startsWith("$MAGIC|DEVICE|")) return null
        val parts = text.split("|", limit = 5)
        if (parts.size < 5) return null
        val ip = parts[2]
        if (ip.isBlank() || ip == localIp) return null
        return CastDevice(
            ip = ip,
            name = parts[3].ifBlank { "TvListesi" },
            model = parts[4]
        )
    }

    /** TV'deki oynatıcıya ses/sarma komutu gönderir. */
    suspend fun control(device: CastDevice, pin: String, action: String): String =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("http://${device.ip}:$SERVER_PORT/control").openConnection() as HttpURLConnection)
                    .apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 5_000
                        readTimeout = 5_000
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    }
                val payload = JSONObject()
                    .put("pin", pin)
                    .put("action", action)
                    .toString()
                connection.outputStream.use { stream ->
                    stream.write(payload.toByteArray(StandardCharsets.UTF_8))
                    stream.flush()
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                    .orEmpty()
                if (code in 200..299) {
                    ""
                } else {
                    val error = runCatching { JSONObject(body).optString("error") }
                        .getOrDefault("")
                    error.ifBlank { "HTTP $code" }
                }
            } catch (error: Exception) {
                error.message?.ifBlank { "Bağlantı kurulamadı" } ?: "Bağlantı kurulamadı"
            } finally {
                connection?.disconnect()
            }
        }

    /** Seçilen kanalı alıcıya gönderir. Hata mesajını döndürür; başarılıysa boş döner. */
    suspend fun send(device: CastDevice, pin: String, channel: Channel): String =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("http://${device.ip}:$SERVER_PORT/play").openConnection() as HttpURLConnection)
                    .apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 5_000
                        readTimeout = 5_000
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    }

                val payload = JSONObject()
                    .put("pin", pin)
                    .put("url", channel.url)
                    .put("title", channel.name)
                    .put("mime", PlaylistParser.mediaMimeType(channel.url))
                    .toString()

                connection.outputStream.use { stream ->
                    stream.write(payload.toByteArray(StandardCharsets.UTF_8))
                    stream.flush()
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                    .orEmpty()
                if (code in 200..299) {
                    ""
                } else {
                    val error = runCatching { JSONObject(body).optString("error") }
                        .getOrDefault("")
                    error.ifBlank { "HTTP $code" }
                }
            } catch (error: IOException) {
                error.message?.ifBlank { "Bağlantı kurulamadı" } ?: "Bağlantı kurulamadı"
            } catch (error: Exception) {
                error.message?.ifBlank { "Bilinmeyen hata" } ?: "Bilinmeyen hata"
            } finally {
                connection?.disconnect()
            }
        }
}
