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
import androidx.appcompat.app.AlertDialog
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
    @Volatile private var rotated180 = false
    @Volatile private var latestJpeg: ByteArray? = null

    private var aviMuxer: AviMuxer? = null
    @Volatile private var recording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewImage = findViewById(R.id.previewImage)
        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.btnRotate).setOnClickListener {
            // This camera's hardware doesn't support the BWSocket ROTATEIMG command (it
            // replies "501 Not Implemented"). The original app's Rotate button was actually a
            // purely local, client-side 180-degree flip of the rendered video, not a network
            // call -- so that's what this replicates.
            rotated180 = !rotated180
        }

        findViewById<Button>(R.id.btnMirror).setOnClickListener {
            mirrored = !mirrored
        }

        findViewById<Button>(R.id.btnPhoto).setOnClickListener {
            takePhoto()
        }

        findViewById<Button>(R.id.btnInfo).setOnClickListener {
            showCameraInfo()
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

    private fun showCameraInfo() {
        Toast.makeText(this, "Querying camera...", Toast.LENGTH_SHORT).show()
        BwSocketClient.getInfo(CAM_HOST, CAM_PORT) { resp ->
            runOnUiThread {
                if (resp == null) {
                    AlertDialog.Builder(this)
                        .setTitle("Camera Info")
                        .setMessage("Failed to reach the camera (no response).")
                        .setPositiveButton("OK", null)
                        .show()
                    return@runOnUiThread
                }
                val fields = BwSocketClient.parseResponse(resp)
                val niceNames = linkedMapOf(
                    "VENDOR" to "Vendor",
                    "CHIP" to "Chip",
                    "VERSION" to "Firmware version",
                    "SSID" to "SSID",
                    "status" to "Status"
                )
                val message = StringBuilder()
                for ((key, label) in niceNames) {
                    fields[key]?.let { message.append("$label: $it\n") }
                }
                for ((key, value) in fields) {
                    if (key !in niceNames.keys && key !in setOf("protocol", "protocolVersion", "statusCode")) {
                        message.append("$key: $value\n")
                    }
                }
                if (message.isEmpty()) message.append(resp)

                AlertDialog.Builder(this)
                    .setTitle("Camera Info")
                    .setMessage(message.toString().trim())
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
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

        val toShow = if (mirrored || rotated180) {
            val m = Matrix().apply {
                if (mirrored) preScale(-1f, 1f)
                if (rotated180) postRotate(180f)
            }
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
