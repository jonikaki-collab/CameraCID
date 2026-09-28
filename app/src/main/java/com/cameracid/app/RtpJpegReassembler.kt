package com.cameracid.app

import java.io.ByteArrayOutputStream

/**
 * Reassembles RFC 2435 JPEG-over-RTP fragments into standalone JPEG images.
 *
 * Ground truth for the header fields (chroma sampling direction, and the
 * "sentinel" detection that splices the camera's own embedded Huffman
 * tables) was reverse engineered from the vendor's own working decoder
 * (FUN_002c418c in their libijkffmpeg.so) and validated against real
 * captured packets from this exact camera.
 */
class RtpJpegReassembler {

    private val frameBuffer = ByteArrayOutputStream()
    private var width = 0
    private var height = 0
    private var jpegType = 0
    private var qTables: Pair<ByteArray, ByteArray>? = null
    private var rawDht: ByteArray? = null
    private var haveFrameStart = false
    private var frameTimestamp: Long = -1
    private var lastSeq: Int = -1
    private var frameCorrupt = false

    /**
     * Feed one RTP payload (the JPEG/RTP header + fragment data, RTP 12-byte header already
     * stripped). [timestamp] is the RTP packet's 32-bit timestamp field, which is what actually
     * identifies which frame a fragment belongs to (fragOffset is only the position *within*
     * that frame) -- without checking it, a reordered or stray packet from a different frame
     * gets silently concatenated into the wrong buffer, producing a torn/corrupted image.
     */
    fun onRtpPayload(payload: ByteArray, marker: Boolean, timestamp: Long, seq: Int): ByteArray? {
        if (payload.size < 8) return null

        val fragOffset = ((payload[1].toInt() and 0xFF) shl 16) or
                ((payload[2].toInt() and 0xFF) shl 8) or
                (payload[3].toInt() and 0xFF)
        var type = payload[4].toInt() and 0xFF
        val q = payload[5].toInt() and 0xFF
        val w = (payload[6].toInt() and 0xFF) * 8
        val h = (payload[7].toInt() and 0xFF) * 8

        var off = 8
        if (type and 0x40 != 0) {
            off += 4 // restart-marker extension header (unused by this camera, but skip if present)
            type = type and 0xBF
        }

        var result: ByteArray? = null
        val isNewFrameStart = fragOffset == 0

        if (isNewFrameStart) {
            // Starts a new frame regardless of what was in progress -- if the previous frame
            // never got its marker bit, it's abandoned here rather than risking a torn merge.
            frameBuffer.reset()
            width = w
            height = h
            jpegType = type
            haveFrameStart = true
            frameTimestamp = timestamp
            lastSeq = seq
            frameCorrupt = false

            if (q >= 128) {
                if (off + 4 <= payload.size) {
                    val qlen = ((payload[off + 2].toInt() and 0xFF) shl 8) or (payload[off + 3].toInt() and 0xFF)
                    val qdataStart = off + 4
                    val qdataEnd = minOf(qdataStart + qlen, payload.size)
                    val qdata = payload.copyOfRange(qdataStart, qdataEnd)
                    val lqt = if (qdata.size >= 64) qdata.copyOfRange(0, 64) else ByteArray(64)
                    val cqt = if (qdata.size >= 128) qdata.copyOfRange(64, 128) else lqt
                    qTables = Pair(lqt, cqt)
                    rawDht = findDhtSegment(qdata)
                    off = qdataStart + qlen
                }
            } else {
                qTables = JpegTables.makeTablesForQ(q)
                rawDht = null
            }
        }

        if (!haveFrameStart) return null

        // A fragment that doesn't belong to the frame currently being assembled (reordered or
        // stray packet) must never be appended -- that's exactly what causes torn frames.
        if (timestamp != frameTimestamp) {
            return null
        }

        // A gap in the RTP sequence number means a fragment was lost -- the frame is now
        // incomplete/corrupt. Keep tracking it (so a later marker doesn't leak into the next
        // frame) but never emit it as a finished image. Skip this check for the frame's own
        // first packet, which has nothing meaningful to be contiguous with.
        if (!isNewFrameStart) {
            val expectedSeq = (lastSeq + 1) and 0xFFFF
            if (seq != expectedSeq) {
                frameCorrupt = true
            }
        }
        lastSeq = seq

        if (off <= payload.size) {
            frameBuffer.write(payload, off, payload.size - off)
        }

        if (marker && haveFrameStart) {
            val qt = qTables
            if (!frameCorrupt && qt != null && width > 0 && height > 0) {
                result = buildJpeg(width, height, qt, frameBuffer.toByteArray(), jpegType, rawDht)
            }
            frameBuffer.reset()
            haveFrameStart = false
        }

        return result
    }

    private fun findDhtSegment(qdata: ByteArray): ByteArray? {
        var j = 0
        while (j < qdata.size - 3) {
            if ((qdata[j].toInt() and 0xFF) == 0xFF && (qdata[j + 1].toInt() and 0xFF) == 0xC4) {
                val length = ((qdata[j + 2].toInt() and 0xFF) shl 8) or (qdata[j + 3].toInt() and 0xFF)
                val end = minOf(j + 2 + length, qdata.size)
                return qdata.copyOfRange(j, end)
            }
            j++
        }
        return null
    }

    private fun buildJpeg(
        w: Int, h: Int,
        qt: Pair<ByteArray, ByteArray>,
        scan: ByteArray,
        type: Int,
        dht: ByteArray?
    ): ByteArray {
        val out = ByteArrayOutputStream(scan.size + 1024)
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) // SOI
        out.write(dqtMarker(qt.first, 0))
        out.write(dqtMarker(qt.second, 1))
        out.write(sof0Marker(w, h, type))
        if (dht != null) {
            out.write(dht)
        } else {
            out.write(JpegTables.dhtMarker(JpegTables.LUM_DC_CODELENS, JpegTables.LUM_DC_SYMBOLS, 0, 0))
            out.write(JpegTables.dhtMarker(JpegTables.LUM_AC_CODELENS, JpegTables.LUM_AC_SYMBOLS, 1, 0))
            out.write(JpegTables.dhtMarker(JpegTables.CHM_DC_CODELENS, JpegTables.CHM_DC_SYMBOLS, 0, 1))
            out.write(JpegTables.dhtMarker(JpegTables.CHM_AC_CODELENS, JpegTables.CHM_AC_SYMBOLS, 1, 1))
        }
        out.write(SOS)
        out.write(scan)
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte())) // EOI
        return out.toByteArray()
    }

    private fun dqtMarker(table: ByteArray, id: Int): ByteArray {
        val out = ByteArrayOutputStream(69)
        out.write(byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0x00, 67, id.toByte()))
        out.write(table)
        return out.toByteArray()
    }

    private fun sof0Marker(w: Int, h: Int, type: Int): ByteArray {
        // Ground truth from decompiled vendor code: type==0 -> Y sampling 0x21, else 0x22
        val ySample = if (type == 0) 0x21 else 0x22
        val out = ByteArrayOutputStream(19)
        out.write(byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0x00, 17, 8))
        out.write(byteArrayOf((h shr 8).toByte(), (h and 0xFF).toByte()))
        out.write(byteArrayOf((w shr 8).toByte(), (w and 0xFF).toByte()))
        out.write(3)
        out.write(byteArrayOf(1, ySample.toByte(), 0))
        out.write(byteArrayOf(2, 0x11, 1))
        out.write(byteArrayOf(3, 0x11, 1))
        return out.toByteArray()
    }

    companion object {
        private val SOS = byteArrayOf(
            0xFF.toByte(), 0xDA.toByte(), 0x00, 12, 3,
            1, 0x00,
            2, 0x11,
            3, 0x11,
            0, 63, 0
        )
    }
}
