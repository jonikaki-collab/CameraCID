package com.cameracid.app

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
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(host, port), 3000)
                    val req = "$command $PATH $PROTO\r\n\r\n"
                    sock.getOutputStream().write(req.toByteArray(Charsets.US_ASCII))
                    sock.getOutputStream().flush()
                    sock.soTimeout = 3000
                    val buf = ByteArray(4096)
                    val n = sock.getInputStream().read(buf)
                    val resp = if (n > 0) String(buf, 0, n, Charsets.US_ASCII) else null
                    onResult(resp)
                }
            } catch (e: Exception) {
                onResult(null)
            }
        }
    }

    fun rotateImage(host: String, port: Int, onResult: (String?) -> Unit = {}) =
        sendCommand(host, port, "ROTATEIMG", onResult)
}
