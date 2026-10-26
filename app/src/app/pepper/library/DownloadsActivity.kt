package app.pepper.library

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import app.pepper.library.Ui.dp
import java.io.File

/**
 * The Downloads folder: every title downloaded into the app's own
 * private storage (see DownloadsStore), playable offline from here.
 * Nothing here lives in the phone's public Downloads folder.
 */
class DownloadsActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var empty: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PosterLoader.init(File(cacheDir, "posters").apply { mkdirs() })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(8))
        }
        header.addView(Ui.text(this, "‹", 28f, Color.WHITE, gravity = Gravity.CENTER).apply {
            setPadding(dp(11), dp(2), dp(13), dp(7))
            setOnClickListener { finish() }
        })
        header.addView(Ui.text(this, "📁 Downloads", 20f, Color.WHITE, bold = true).apply {
            setPadding(dp(10), 0, 0, 0)
        })
        root.addView(header)
        root.addView(Ui.text(this, "Saved inside the app — not in the phone's Downloads folder.", 12.5f, Ui.DIM).apply {
            setPadding(dp(16), 0, dp(16), dp(12))
        })

        empty = Ui.text(this, "Nothing downloaded yet.\nDownload a title and it will appear here.", 14f, Ui.DIM, gravity = Gravity.CENTER).apply {
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(16))
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(empty)
        col.addView(list)
        scroll.addView(col)
        root.addView(scroll)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun dmStatus(dmId: Long): Int? {
        if (dmId < 0) return null
        return try {
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val c: Cursor = dm.query(DownloadManager.Query().setFilterById(dmId))
            c.use {
                if (it.moveToFirst()) it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun refresh() {
        list.removeAllViews()
        val entries = DownloadsStore.all(this)
        val ready = entries.filter { DownloadsStore.file(this, it).exists() }
        val downloading = entries.filter { !DownloadsStore.file(this, it).exists() }
        empty.visibility = if (entries.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        fun addRow(e: DownloadsStore.Entry, isReady: Boolean) {
            val file = DownloadsStore.file(this, e)
            val status = when {
                isReady -> "Ready offline"
                dmStatus(e.dmId) == DownloadManager.STATUS_FAILED -> "Download failed"
                else -> "Downloading…"
            }
            val size = if (file.exists()) " · %.0f MB".format(file.length() / 1024.0 / 1024.0) else ""
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.rounded(this@DownloadsActivity, Ui.CARD, 12)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            val thumb = ImageView(this).apply {
                setBackgroundColor(Ui.CARD)
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(74))
            }
            PosterLoader.load(e.poster, thumb)
            row.addView(thumb)
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(10); marginEnd = dp(8)
                }
            }
            col.addView(Ui.text(this, e.title, 15f, Ui.TEXT, bold = true))
            col.addView(Ui.text(this, status + size, 12f, if (isReady) Ui.GREEN else Ui.DIM).apply {
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(col)
            if (isReady) {
                row.addView(Ui.greenButton(this, "▶") {
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putExtra("url", file.absolutePath)
                            .putExtra("title", e.title)
                            .putExtra("id", e.id)
                            .putExtra("poster", e.poster)
                            .putExtra("type", e.type)
                            .putExtra("startAt", 0.0)
                    )
                })
            }
            val del = Ui.ghostButton(this, "🗑") {
                try { file.delete() } catch (ignored: Exception) { }
                DownloadsStore.remove(this, e)
                refresh()
                Toast.makeText(this, "Deleted: ${e.title}", Toast.LENGTH_SHORT).show()
            }
            del.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            row.addView(del)
            list.addView(row)
        }
        for (e in ready) addRow(e, true)
        for (e in downloading) addRow(e, false)
        if (entries.isNotEmpty() && ready.isEmpty()) {
            empty.visibility = android.view.View.VISIBLE
            empty.text = "Downloads in progress — they will appear as Ready here when finished."
        }
    }
}
