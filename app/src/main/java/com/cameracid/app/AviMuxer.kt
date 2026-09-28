package com.cameracid.app

import java.io.RandomAccessFile

/**
 * Minimal Motion-JPEG AVI muxer: single video stream, no audio.
 * Writes placeholder sizes up front and patches them in [finish].
 */
class AviMuxer(path: String, private val width: Int, private val height: Int, private val fps: Int = 10) {

    private val raf = RandomAccessFile(path, "rw")
    private var frameCount = 0
    private val frameOffsets = ArrayList<Pair<Int, Int>>() // (offset within movi data, size)
    private var moviListStart = 0L
    private var moviDataStart = 0L

    init {
        writeHeaders()
    }

    private fun u32(v: Long) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
    private fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun fourCC(s: String) = s.toByteArray(Charsets.US_ASCII)

    private fun writeHeaders() {
        raf.write(fourCC("RIFF"))
        raf.write(u32(0)) // placeholder: total file size - 8
        raf.write(fourCC("AVI "))

        // LIST hdrl
        raf.write(fourCC("LIST"))
        raf.write(u32(0)) // placeholder hdrl size
        val hdrlSizePos = raf.filePointer - 4
        raf.write(fourCC("hdrl"))

        // avih
        raf.write(fourCC("avih"))
        raf.write(u32(56))
        val usPerFrame = 1_000_000L / fps
        raf.write(u32(usPerFrame))
        raf.write(u32(0)) // max bytes per sec
        raf.write(u32(0)) // padding granularity
        raf.write(u32(0x10)) // flags: AVIF_HASINDEX
        val totalFramesPos = raf.filePointer
        raf.write(u32(0)) // placeholder total frames
        raf.write(u32(0)) // initial frames
        raf.write(u32(1)) // streams
        raf.write(u32(0)) // suggested buffer size
        raf.write(u32(width.toLong()))
        raf.write(u32(height.toLong()))
        raf.write(u32(0)); raf.write(u32(0)); raf.write(u32(0)); raf.write(u32(0)) // reserved

        // LIST strl
        raf.write(fourCC("LIST"))
        raf.write(u32(0))
        val strlSizePos = raf.filePointer - 4
        raf.write(fourCC("strl"))

        // strh
        raf.write(fourCC("strh"))
        raf.write(u32(56))
        raf.write(fourCC("vids"))
        raf.write(fourCC("MJPG"))
        raf.write(u32(0)) // flags
        raf.write(u16(0)); raf.write(u16(0)) // priority, language
        raf.write(u32(0)) // initial frames
        raf.write(u32(1)) // scale
        raf.write(u32(fps.toLong())) // rate
        raf.write(u32(0)) // start
        val strhLengthPos = raf.filePointer
        raf.write(u32(0)) // placeholder length (frame count)
        raf.write(u32(0)) // suggested buffer size
        raf.write(u32(-1L)) // quality
        raf.write(u32(0)) // sample size
        raf.write(u16(0)); raf.write(u16(0)); raf.write(u16(width)); raf.write(u16(height)) // frame rect

        // strf (BITMAPINFOHEADER)
        raf.write(fourCC("strf"))
        raf.write(u32(40))
        raf.write(u32(40)) // biSize
        raf.write(u32(width.toLong()))
        raf.write(u32(height.toLong()))
        raf.write(u16(1)) // planes
        raf.write(u16(24)) // bit count
        raf.write(fourCC("MJPG")) // compression
        raf.write(u32((width * height * 3).toLong())) // image size
        raf.write(u32(0)); raf.write(u32(0)); raf.write(u32(0)); raf.write(u32(0))

        val strlEnd = raf.filePointer
        val strlSize = strlEnd - (strlSizePos + 4)
        patchU32(strlSizePos, strlSize)

        val hdrlEnd = raf.filePointer
        val hdrlSize = hdrlEnd - (hdrlSizePos + 4)
        patchU32(hdrlSizePos, hdrlSize)

        this.totalFramesPos = totalFramesPos
        this.strhLengthPos = strhLengthPos

        // LIST movi
        raf.write(fourCC("LIST"))
        moviListStart = raf.filePointer
        raf.write(u32(0)) // placeholder movi size
        raf.write(fourCC("movi"))
        moviDataStart = raf.filePointer
    }

    private var totalFramesPos: Long = 0
    private var strhLengthPos: Long = 0

    @Synchronized
    fun writeFrame(jpeg: ByteArray) {
        val offsetInMovi = (raf.filePointer - moviDataStart).toInt()
        raf.write(fourCC("00dc"))
        raf.write(u32(jpeg.size.toLong()))
        raf.write(jpeg)
        if (jpeg.size % 2 != 0) raf.write(0) // pad to even
        frameOffsets.add(Pair(offsetInMovi, jpeg.size))
        frameCount++
    }

    private fun patchU32(pos: Long, value: Long) {
        val cur = raf.filePointer
        raf.seek(pos)
        raf.write(u32(value))
        raf.seek(cur)
    }

    @Synchronized
    fun finish() {
        val moviEnd = raf.filePointer
        val moviSize = moviEnd - (moviListStart + 4)

        // idx1
        raf.write(fourCC("idx1"))
        raf.write(u32((frameOffsets.size * 16).toLong()))
        for ((offset, size) in frameOffsets) {
            raf.write(fourCC("00dc"))
            raf.write(u32(0x10)) // AVIIF_KEYFRAME
            raf.write(u32((offset + 8).toLong())) // offset relative to movi list data start (incl 00dc header)
            raf.write(u32(size.toLong()))
        }

        val fileEnd = raf.filePointer
        patchU32(4, fileEnd - 8) // RIFF size
        patchU32(moviListStart, moviSize)
        patchU32(totalFramesPos, frameCount.toLong())
        patchU32(strhLengthPos, frameCount.toLong())

        raf.close()
    }
}
