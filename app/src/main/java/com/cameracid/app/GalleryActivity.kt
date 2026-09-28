package com.cameracid.app

import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class GalleryActivity : AppCompatActivity() {

    data class MediaItem(val uri: Uri, val id: Long, val isVideo: Boolean, val dateAdded: Long)

    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val thumbCache = LruCache<Long, Bitmap>(200)

    private lateinit var gridView: GridView
    private lateinit var emptyText: TextView
    private lateinit var adapter: GalleryAdapter
    private var items: List<MediaItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        gridView = findViewById(R.id.gridView)
        emptyText = findViewById(R.id.emptyText)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        adapter = GalleryAdapter()
        gridView.adapter = adapter

        gridView.setOnItemClickListener { _, _, position, _ ->
            openItem(items[position])
        }
        gridView.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(items[position])
            true
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        executor.execute {
            val loaded = queryMedia()
            mainHandler.post {
                items = loaded
                emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun queryMedia(): List<MediaItem> {
        val result = mutableListOf<MediaItem>()

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("Pictures/CameraCID%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                result.add(MediaItem(uri, id, false, c.getLong(dateCol)))
            }
        }

        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED),
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("Movies/CameraCID%"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                result.add(MediaItem(uri, id, true, c.getLong(dateCol)))
            }
        }

        return result.sortedByDescending { it.dateAdded }
    }

    private fun loadThumbnail(item: MediaItem): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.loadThumbnail(item.uri, Size(200, 200), null)
            } else if (item.isVideo) {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    contentResolver, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null
                )
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(
                    contentResolver, item.id, MediaStore.Images.Thumbnails.MINI_KIND, null
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun openItem(item: MediaItem) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, if (item.isVideo) "video/*" else "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(item: MediaItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete?")
            .setMessage(if (item.isVideo) "Delete this video?" else "Delete this photo?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    contentResolver.delete(item.uri, null, null)
                    thumbCache.remove(item.id)
                    reload()
                } catch (e: Exception) {
                    Toast.makeText(this, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private inner class GalleryAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@GalleryActivity)
                .inflate(R.layout.item_gallery, parent, false)
            val thumbImage = view.findViewById<ImageView>(R.id.thumbImage)
            val playOverlay = view.findViewById<ImageView>(R.id.playOverlay)
            val item = items[position]

            playOverlay.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            thumbImage.setTag(item.id)
            thumbImage.setImageDrawable(null)

            val cached = thumbCache.get(item.id)
            if (cached != null) {
                thumbImage.setImageBitmap(cached)
            } else {
                executor.execute {
                    val bmp = loadThumbnail(item)
                    if (bmp != null) thumbCache.put(item.id, bmp)
                    mainHandler.post {
                        if (thumbImage.getTag() == item.id && bmp != null) {
                            thumbImage.setImageBitmap(bmp)
                        }
                    }
                }
            }
            return view
        }
    }
}
