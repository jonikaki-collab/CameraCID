package com.cameracid.app

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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

    private var mp4Recorder: Mp4Recorder? = null
    private var recordingPfd: ParcelFileDescriptor? = null
    @Volatile private var recording = false
    private lateinit var btnRecord: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewImage = findViewById(R.id.previewImage)
        statusText = findViewById(R.id.statusText)
        btnRecord = findViewById(R.id.btnRecord)

        findViewById<ImageButton>(R.id.btnRotate).setOnClickListener {
            // This camera's hardware doesn't support the BWSocket ROTATEIMG command (it
            // replies "501 Not Implemented"). The original app's Rotate button was actually a
            // purely local, client-side 180-degree flip of the rendered video, not a network
            // call -- so that's what this replicates.
            rotated180 = !rotated180
        }

        findViewById<ImageButton>(R.id.btnMirror).setOnClickListener {
            mirrored = !mirrored
        }

        findViewById<ImageButton>(R.id.btnPhoto).setOnClickListener {
            takePhoto()
        }

        findViewById<ImageButton>(R.id.btnInfo).setOnClickListener {
            showCameraInfo()
        }

        findViewById<ImageButton>(R.id.btnGallery).setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        btnRecord.setOnClickListener {
            if (!recording) {
                startRecording()
            } else {
                stopRecording()
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

    @Volatile private var connectionErrorDialogShowing = false

    private fun startStream() {
        rtspClient = RtspClient(
            context = this,
            host = CAM_HOST,
            port = CAM_PORT,
            path = CAM_PATH,
            onFrame = { jpeg -> handleFrame(jpeg) },
            onStatus = { status -> runOnUiThread { handleStreamStatus(status) } }
        )
        rtspClient?.start()
    }

    private fun handleStreamStatus(status: String) {
        statusText.text = status
        if (status.startsWith("Error:") && !connectionErrorDialogShowing && !isFinishing) {
            connectionErrorDialogShowing = true
            AlertDialog.Builder(this)
                .setTitle("Can't reach the camera")
                .setMessage(
                    "$status\n\n" +
                    "Things to try:\n" +
                    "• Make sure this device is connected to the camera's own WiFi network.\n" +
                    "• If this device also has mobile data (4G/5G) turned on, Android may be " +
                    "routing traffic over that instead of WiFi, since the camera's WiFi has no " +
                    "internet access. Try turning on Airplane Mode, then turn WiFi back on.\n" +
                    "• Then tap Retry below."
                )
                .setPositiveButton("Retry") { _, _ ->
                    connectionErrorDialogShowing = false
                    rtspClient?.stop()
                    startStream()
                }
                .setNegativeButton("Dismiss") { _, _ ->
                    connectionErrorDialogShowing = false
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun showCameraInfo() {
        Toast.makeText(this, "Querying camera...", Toast.LENGTH_SHORT).show()
        BwSocketClient.getInfo(this, CAM_HOST, CAM_PORT) { resp ->
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

        if (recording) {
            try {
                mp4Recorder?.encodeBitmap(bitmap)
            } catch (_: Exception) {
            }
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
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, timestampName("mp4"))
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CameraCID")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Toast.makeText(this, "Record start failed: could not create file", Toast.LENGTH_SHORT).show()
                return
            }
            val pfd = contentResolver.openFileDescriptor(uri, "rw")
            if (pfd == null) {
                Toast.makeText(this, "Record start failed: could not open file", Toast.LENGTH_SHORT).show()
                return
            }
            recordingPfd = pfd
            mp4Recorder = Mp4Recorder(pfd.fileDescriptor, opts.outWidth, opts.outHeight, fps = 10)
            recording = true
            btnRecord.setImageResource(R.drawable.ic_stop)
            btnRecord.setBackgroundResource(R.drawable.bg_circle_button_recording)
            Toast.makeText(this, "Recording...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Record start failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        recording = false
        btnRecord.setImageResource(R.drawable.ic_videocam)
        btnRecord.setBackgroundResource(R.drawable.bg_circle_button)
        try {
            mp4Recorder?.finish()
            Toast.makeText(this, "Recording saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            mp4Recorder = null
            try { recordingPfd?.close() } catch (_: Exception) {}
            recordingPfd = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (recording) stopRecording()
        rtspClient?.stop()
    }
}
