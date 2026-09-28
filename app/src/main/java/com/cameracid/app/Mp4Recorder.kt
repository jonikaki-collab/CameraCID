package com.cameracid.app

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor

/**
 * Encodes incoming Bitmaps to H.264/MP4 using Android's built-in hardware encoder
 * (MediaCodec) and muxer (MediaMuxer) -- no third-party codec, no native blob.
 *
 * Replaces an earlier MJPEG-in-AVI muxer: while structurally valid (confirmed by
 * extracting and independently decoding its frames), that format has inconsistent
 * playback support across the Android app ecosystem (no support in Google Photos,
 * a chroma-subsampling color quirk in VLC's AVI/MJPEG codec path). MP4/H.264 plays
 * correctly everywhere.
 */
class Mp4Recorder(fd: FileDescriptor, private val width: Int, private val height: Int, fps: Int = 10, bitRate: Int = 2_000_000) {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var trackIndex = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private var frameIndex = 0L
    private val frameDurationUs = 1_000_000L / fps

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun encodeBitmap(bitmap: Bitmap) {
        val (yPlane, uPlane, vPlane) = bitmapToYuv420(bitmap)
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            val image: Image? = codec.getInputImage(inputIndex)
            if (image != null) {
                writePlane(image.planes[0], yPlane, width, height)
                writePlane(image.planes[1], uPlane, width / 2, height / 2)
                writePlane(image.planes[2], vPlane, width / 2, height / 2)
                val ptsUs = frameIndex * frameDurationUs
                codec.queueInputBuffer(inputIndex, 0, yPlane.size + uPlane.size + vPlane.size, ptsUs, 0)
                frameIndex++
            } else {
                codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
            }
        }
        drainEncoder(false)
    }

    private fun writePlane(plane: Image.Plane, src: ByteArray, planeWidth: Int, planeHeight: Int) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride == 1 && rowStride == planeWidth) {
            buffer.put(src, 0, planeWidth * planeHeight)
            return
        }
        var srcIndex = 0
        for (y in 0 until planeHeight) {
            val rowStart = y * rowStride
            if (pixelStride == 1) {
                buffer.position(rowStart)
                buffer.put(src, srcIndex, planeWidth)
                srcIndex += planeWidth
            } else {
                // Interleaved chroma (e.g. NV12/NV21): U and V planes are views onto the SAME
                // underlying buffer, offset by one byte. Writing a whole row back would stomp
                // the other plane's bytes at the positions in between, so each byte must be
                // written individually via an absolute put that never touches its neighbors.
                for (x in 0 until planeWidth) {
                    buffer.put(rowStart + x * pixelStride, src[srcIndex])
                    srcIndex++
                }
            }
        }
    }

    private fun bitmapToYuv420(bitmap: Bitmap): Triple<ByteArray, ByteArray, ByteArray> {
        val w = bitmap.width
        val h = bitmap.height
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)

        val yArr = ByteArray(w * h)
        val uArr = ByteArray(w / 2 * h / 2)
        val vArr = ByteArray(w / 2 * h / 2)

        var uvIndex = 0
        var j = 0
        while (j < h) {
            var i = 0
            while (i < w) {
                val p00 = argb[j * w + i]
                val p01 = argb[j * w + i + 1]
                val p10 = argb[(j + 1) * w + i]
                val p11 = argb[(j + 1) * w + i + 1]

                yArr[j * w + i] = rgbToY(p00)
                yArr[j * w + i + 1] = rgbToY(p01)
                yArr[(j + 1) * w + i] = rgbToY(p10)
                yArr[(j + 1) * w + i + 1] = rgbToY(p11)

                val avgR = (((p00 shr 16) and 0xFF) + ((p01 shr 16) and 0xFF) + ((p10 shr 16) and 0xFF) + ((p11 shr 16) and 0xFF)) / 4
                val avgG = (((p00 shr 8) and 0xFF) + ((p01 shr 8) and 0xFF) + ((p10 shr 8) and 0xFF) + ((p11 shr 8) and 0xFF)) / 4
                val avgB = ((p00 and 0xFF) + (p01 and 0xFF) + (p10 and 0xFF) + (p11 and 0xFF)) / 4

                uArr[uvIndex] = rgbToU(avgR, avgG, avgB)
                vArr[uvIndex] = rgbToV(avgR, avgG, avgB)
                uvIndex++
                i += 2
            }
            j += 2
        }
        return Triple(yArr, uArr, vArr)
    }

    private fun rgbToY(argb: Int): Byte {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255).toByte()
    }

    private fun rgbToU(r: Int, g: Int, b: Int): Byte =
        (-0.169 * r - 0.331 * g + 0.5 * b + 128).toInt().coerceIn(0, 255).toByte()

    private fun rgbToV(r: Int, g: Int, b: Int): Byte =
        (0.5 * r - 0.419 * g - 0.081 * b + 128).toInt().coerceIn(0, 255).toByte()

    private fun drainEncoder(endOfStream: Boolean) {
        if (endOfStream) {
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
            }
        }
    }

    fun finish() {
        try {
            drainEncoder(true)
        } finally {
            // muxer.stop() is what actually finalizes the file -- once that succeeds, the
            // recording is already saved correctly, so nothing after it should be able to
            // surface as a "save failed" error to the caller.
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }
    }
}
