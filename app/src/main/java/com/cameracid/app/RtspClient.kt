package com.cameracid.app

import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.regex.Pattern
import kotlin.concurrent.thread

class RtspClient(
    private val host: String,
    private val port: Int,
    private val path: String,
    private val onFrame: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit
) {
    @Volatile private var running = false
    private var tcpSocket: Socket? = null
    private var rtpSocket: DatagramSocket? = null
    private var rtcpSocket: DatagramSocket? = null
    private var cseq = 1

    fun start() {
        if (running) return
        running = true
        thread(name = "RtspClient") { runSession() }
    }

    fun stop() {
        running = false
        try { rtpSocket?.close() } catch (_: Exception) {}
        try { rtcpSocket?.close() } catch (_: Exception) {}
        try { tcpSocket?.close() } catch (_: Exception) {}
    }

    private fun url(suffix: String = "") = "rtsp://$host:$port$path$suffix"

    private fun runSession() {
        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), 5000)
            tcpSocket = sock
            val out = BufferedOutputStream(sock.getOutputStream())
            val input = sock.getInputStream()

            sendRequest(out, url(), "OPTIONS")
            readResponse(input)

            val (describeHead, describeBody) = run {
                sendRequest(out, url(), "DESCRIBE", "Accept: application/sdp\r\n")
                readResponseWithBody(input)
            }

            var contentBase = url("/")
            headerValue(describeHead, "Content-Base")?.let { contentBase = it }

            var control = "track0"
            val sdpText = String(describeBody, Charsets.US_ASCII)
            val m = Pattern.compile("a=control:(\\S+)").matcher(sdpText)
            if (m.find()) control = m.group(1) ?: control

            val trackUrl = if (control.startsWith("rtsp://")) control
                else contentBase.trimEnd('/') + "/" + control.trimStart('/')

            rtpSocket = DatagramSocket(0).apply { receiveBufferSize = 4 * 1024 * 1024 }
            val rtpPort = rtpSocket!!.localPort
            var rtcpPort = rtpPort + 1
            rtcpSocket = try {
                DatagramSocket(rtcpPort)
            } catch (e: Exception) {
                DatagramSocket(0).also { rtcpPort = it.localPort }
            }
            rtcpSocket!!.receiveBufferSize = 1 * 1024 * 1024

            onStatus("Setting up transport...")
            sendRequest(out, trackUrl, "SETUP", "Transport: RTP/AVP;unicast;client_port=$rtpPort-$rtcpPort\r\n")
            val (setupHead, _) = readResponseWithBody(input)
            if (!setupHead.startsWith("RTSP/1.0 200")) {
                onStatus("SETUP failed: ${setupHead.lineSequence().first()}")
                return
            }
            val sessionId = headerValue(setupHead, "Session")?.substringBefore(';')

            val playHeaders = if (sessionId != null) "Session: $sessionId\r\n" else ""
            sendRequest(out, contentBase, "PLAY", playHeaders)
            val (playHead, _) = readResponseWithBody(input)
            if (!playHead.startsWith("RTSP/1.0 200")) {
                onStatus("PLAY failed: ${playHead.lineSequence().first()}")
                return
            }

            onStatus("Connected")

            val reassembler = RtpJpegReassembler()
            val rtpThread = thread(name = "RtpReader") { readRtpLoop(reassembler) }
            val rtcpThread = thread(name = "RtcpReader") { readRtcpLoop() }

            rtpThread.join()
            rtcpThread.join()

            try {
                sendRequest(out, contentBase, "TEARDOWN", playHeaders)
            } catch (_: Exception) {}

        } catch (e: Exception) {
            onStatus("Error: ${e.message}")
        } finally {
            stop()
        }
    }

    private fun readRtpLoop(reassembler: RtpJpegReassembler) {
        val buf = ByteArray(65536)
        while (running) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                rtpSocket?.receive(packet) ?: break
                if (packet.length < 12) continue
                val b0 = packet.data[0].toInt() and 0xFF
                val b1 = packet.data[1].toInt() and 0xFF
                val cc = b0 and 0x0F
                val marker = (b1 and 0x80) != 0
                val seq = (((packet.data[2].toInt() and 0xFF) shl 8) or (packet.data[3].toInt() and 0xFF))
                val timestamp = (((packet.data[4].toLong() and 0xFF) shl 24) or
                        ((packet.data[5].toLong() and 0xFF) shl 16) or
                        ((packet.data[6].toLong() and 0xFF) shl 8) or
                        (packet.data[7].toLong() and 0xFF))
                val headerLen = 12 + 4 * cc
                if (packet.length <= headerLen) continue
                val payload = packet.data.copyOfRange(headerLen, packet.length)
                val jpeg = reassembler.onRtpPayload(payload, marker, timestamp, seq)
                if (jpeg != null) onFrame(jpeg)
            } catch (e: Exception) {
                if (running) { /* socket closed or transient error */ }
                break
            }
        }
    }

    private fun readRtcpLoop() {
        val buf = ByteArray(4096)
        while (running) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                rtcpSocket?.receive(packet) ?: break
                // RTCP packets are not needed for JPEG reassembly; discard.
            } catch (e: Exception) {
                break
            }
        }
    }

    private fun sendRequest(out: BufferedOutputStream, targetUrl: String, method: String, extraHeaders: String = "") {
        val req = "$method $targetUrl RTSP/1.0\r\nCSeq: $cseq\r\nUser-Agent: CameraCID\r\n$extraHeaders\r\n"
        cseq++
        out.write(req.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    /** Reads only the header block (up to \r\n\r\n), discarding any body. */
    private fun readResponse(input: InputStream): String {
        val (head, _) = readResponseWithBody(input)
        return head
    }

    /** Reads header block, then reads Content-Length bytes of body if present. */
    private fun readResponseWithBody(input: InputStream): Pair<String, ByteArray> {
        val headBuf = java.io.ByteArrayOutputStream()
        var prev4 = ByteArray(4)
        while (true) {
            val b = input.read()
            if (b == -1) break
            headBuf.write(b)
            prev4[0] = prev4[1]; prev4[1] = prev4[2]; prev4[2] = prev4[3]; prev4[3] = b.toByte()
            if (prev4[0] == '\r'.code.toByte() && prev4[1] == '\n'.code.toByte() &&
                prev4[2] == '\r'.code.toByte() && prev4[3] == '\n'.code.toByte()) {
                break
            }
        }
        val head = headBuf.toString(Charsets.US_ASCII.name())
        val lenStr = headerValue(head, "Content-Length")
        val body = if (lenStr != null) {
            val len = lenStr.trim().toIntOrNull() ?: 0
            val bodyBuf = ByteArray(len)
            var read = 0
            while (read < len) {
                val n = input.read(bodyBuf, read, len - read)
                if (n < 0) break
                read += n
            }
            bodyBuf
        } else ByteArray(0)
        return Pair(head, body)
    }

    private fun headerValue(headText: String, name: String): String? {
        val m = Pattern.compile("$name:\\s*(.+)", Pattern.CASE_INSENSITIVE).matcher(headText)
        return if (m.find()) m.group(1)?.trim() else null
    }
}
