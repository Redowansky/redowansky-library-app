package app.pepper.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Tiny async poster loader: memory LruCache + disk cache, no libraries. */
object PosterLoader {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4)
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    @Volatile private var diskDir: File? = null

    fun init(dir: File) {
        diskDir = dir
    }

    fun load(url: String?, into: ImageView) {
        into.tag = url
        if (url == null) {
            into.setImageBitmap(null)
            return
        }
        cache.get(url)?.let {
            into.setImageBitmap(it)
            return
        }
        into.setImageBitmap(null)
        pool.execute {
            val bmp = fetch(url)
            if (bmp != null) cache.put(url, bmp)
            main.post {
                if (into.tag == url && bmp != null) into.setImageBitmap(bmp)
            }
        }
    }

    /** Load an image cropped into a circle (avatars). */
    fun loadCircular(url: String?, into: ImageView) {
        into.tag = url?.let { "$it#circle" }
        if (url == null) {
            into.setImageBitmap(null)
            return
        }
        val key = "$url#circle"
        cache.get(key)?.let {
            into.setImageBitmap(it)
            return
        }
        cache.get(url)?.let { src ->
            val c = circular(src)
            cache.put(key, c)
            into.setImageBitmap(c)
            return
        }
        into.setImageBitmap(null)
        pool.execute {
            val bmp = fetch(url)
            if (bmp != null) {
                val c = circular(bmp)
                cache.put(key, c)
                main.post { if (into.tag == key) into.setImageBitmap(c) }
            }
        }
    }

    private fun circular(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val squared = Bitmap.createBitmap(
            src, (src.width - size) / 2, (src.height - size) / 2, size, size
        )
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.shader = android.graphics.BitmapShader(
            squared, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return out
    }

    private fun fetch(url: String): Bitmap? {
        val file = diskDir?.let { File(it, hash(url) + ".img") }
        if (file != null && file.exists() && file.length() > 0) {
            BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
        }
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "RedowanskyLibraryApp/0.2")
            conn.inputStream.use { input ->
                val bytes = input.readBytes()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null && file != null) {
                    try { file.writeBytes(bytes) } catch (e: Exception) { /* cache is best-effort */ }
                }
                bmp
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun hash(s: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
