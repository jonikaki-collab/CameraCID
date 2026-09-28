package com.cameracid.app

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The camera's plaintext control protocol (decoded from the original vendor app).
 * Commands are sent as "COMMAND /webcam APPO/1.0\r\n\r\n" to the same host:port as RTSP.
 */
object BwSocketClient {
    private const val PATH = "/webcam"
    private const val PROTO = "APPO/1.0"

    fun sendCommand(host: String, port: Int, command: String, onResult: (String?) -> Unit = {}) {
        thread(name = "BwSocketCmd") {
            try {
                Log.d("BwSocketClient", "connecting to $host:$port for command '$command'")
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(host, port), 3000)
                    Log.d("BwSocketClient", "connected, sending request")
                    val req = "$command $PATH $PROTO\r\n\r\n"
                    sock.getOutputStream().write(req.toByteArray(Charsets.US_ASCII))
                    sock.getOutputStream().flush()
                    sock.soTimeout = 3000
                    val buf = ByteArray(4096)
                    val n = sock.getInputStream().read(buf)
                    val resp = if (n > 0) String(buf, 0, n, Charsets.US_ASCII) else null
                    Log.d("BwSocketClient", "response (n=$n): ${resp?.replace("\r", "\\r")?.replace("\n", "\\n")}")
                    onResult(resp)
                }
            } catch (e: Exception) {
                Log.e("BwSocketClient", "exception: ${e.javaClass.simpleName}: ${e.message}")
                onResult(null)
            }
        }
    }

    fun rotateImage(host: String, port: Int, onResult: (String?) -> Unit = {}) =
        sendCommand(host, port, "ROTATEIMG", onResult)

    fun getInfo(host: String, port: Int, onResult: (String?) -> Unit = {}) =
        sendCommand(host, port, "GETINFO", onResult)

    /**
     * Parses the camera's response format (mirrors BWSocket.parseResponseString from the
     * original app): a status line ("APPO/1.0 200 OK"), then "KEY:VALUE" lines.
     */
    fun parseResponse(raw: String): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        val lines = raw.split("\r\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) return result

        val statusParts = lines[0].split(" ", limit = 3)
        if (statusParts.size >= 3) {
            val protoVer = statusParts[0].split("/")
            if (protoVer.size == 2) {
                result["protocol"] = protoVer[0]
                result["protocolVersion"] = protoVer[1]
            }
            result["statusCode"] = statusParts[1]
            result["status"] = statusParts[2]
        }

        for (i in 1 until lines.size) {
            val kv = lines[i].replace(" ", "").split(":", limit = 2)
            if (kv.size == 2) result[kv[0]] = kv[1]
        }
        return result
    }
}
