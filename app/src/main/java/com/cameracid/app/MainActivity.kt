package com.cameracid.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val CAM_HOST = "192.168.1.1"
        private const val CAM_PORT = 7070
        private const val CAM_PATH = "/webcam"
    }

    private lateinit var previewImage: ImageView
    private lateinit var statusText: TextView
    private var rtspClient: RtspClient? = null

    @Volatile private var mirrored = false
    @Volatile private var latestJpeg: ByteArray? = null

    private var aviMuxer: AviMuxer? = null
    @Volatile private var recording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewImage = findViewById(R.id.previewImage)
        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.btnRotate).setOnClickListener {
            BwSocketClient.rotateImage(CAM_HOST, CAM_PORT) { resp ->
                runOnUiThread {
                    Toast.makeText(this, if (resp != null) "Rotated" else "Rotate failed", Toast.LENGTH_SHORT).show()
                }
            }
        }

        findViewById<Button>(R.id.btnMirror).setOnClickListener {
            mirrored = !mirrored
        }

        findViewById<Button>(R.id.btnPhoto).setOnClickListener {
            takePhoto()
        }

        findViewById<Button>(R.id.btnRecord).setOnClickListener { btn ->
            if (!recording) {
                startRecording()
                (btn as Button).text = "Stop"
            } else {
                stopRecording()
                (btn as Button).text = "Record"
            }
        }

    }

    override fun onResume() {
        super.onResume()
        startStream()
    }

    override fun onPause() {
        super.onPause()
        // Android can freeze/throttle a backgrounded app's threads, which stalls the socket
        // reader without cleanly closing it. Rather than leave it in a stale half-dead state,
        // tear the session down here and reconnect fresh in onResume.
        rtspClient?.stop()
        rtspClient = null
        if (recording) stopRecording()
    }

    private fun startStream() {
        rtspClient = RtspClient(
            host = CAM_HOST,
            port = CAM_PORT,
            path = CAM_PATH,
            onFrame = { jpeg -> handleFrame(jpeg) },
            onStatus = { status -> runOnUiThread { statusText.text = status } }
        )
        rtspClient?.start()
    }

    private val uiBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun handleFrame(jpeg: ByteArray) {
        latestJpeg = jpeg

        if (recording) {
            try {
                aviMuxer?.writeFrame(jpeg)
            } catch (_: Exception) {
            }
        }

        // Drop this frame if the UI thread hasn't finished drawing the previous one yet,
        // so we always show the freshest frame instead of backlogging stale ones.
        if (!uiBusy.compareAndSet(false, true)) return

        val bitmap = try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        } catch (e: Exception) {
            null
        }
        if (bitmap == null) {
            uiBusy.set(false)
            return
        }

        val toShow = if (mirrored) {
            val m = Matrix().apply { preScale(-1f, 1f) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        } else bitmap

        runOnUiThread {
            previewImage.setImageBitmap(toShow)
            statusText.text = "Live (${toShow.width}x${toShow.height})"
            uiBusy.set(false)
        }
    }

    private fun timestampName(ext: String): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "CameraCID_${fmt.format(java.util.Date())}.$ext"
    }

    private fun takePhoto() {
        val jpeg = latestJpeg
        if (jpeg == null) {
            Toast.makeText(this, "No frame yet", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, timestampName("jpg"))
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CameraCID")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(jpeg) }
                Toast.makeText(this, "Photo saved", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRecording() {
        val jpeg = latestJpeg ?: return
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "")
        dir.mkdirs()
        val file = File(dir, timestampName("avi"))
        try {
            aviMuxer = AviMuxer(file.absolutePath, opts.outWidth, opts.outHeight, fps = 10)
            recording = true
            Toast.makeText(this, "Recording to ${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Record start failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        recording = false
        try {
            aviMuxer?.finish()
            Toast.makeText(this, "Recording saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            aviMuxer = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (recording) stopRecording()
        rtspClient?.stop()
    }
}
