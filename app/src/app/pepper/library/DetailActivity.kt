package app.pepper.library

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import app.pepper.library.Ui.dp
import org.json.JSONObject
import java.io.File

/**
 * Title detail (modern UI, v0.6): backdrop with a fade into the page,
 * title overlaid on the art, meta chips, and pill Play/Download
 * actions. Downloads still pass through the fullscreen ad first.
 */
class DetailActivity : Activity() {

    companion object { private const val REQ_AD = 42 }

    private lateinit var movie: Movie
    private val playButtons = mutableListOf<Pair<Button, Source>>()
    private var pendingSource: Source? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ads.init()
        PosterLoader.init(File(cacheDir, "posters").apply { mkdirs() })

        val json = intent.getStringExtra("movie")
        if (json == null) {
            finish()
            return
        }
        movie = Movie.fromJson(JSONObject(json))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---------- Backdrop with fade + overlaid title ----------
        val hero = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(218)
            )
        }
        val backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Ui.CARD)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        PosterLoader.load(movie.backdropUrl() ?: movie.posterUrl("w500"), backdrop)
        hero.addView(backdrop)
        val fade = android.view.View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Ui.BG)
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(130)
            ).apply { gravity = Gravity.BOTTOM }
        }
        hero.addView(fade)
        val back = Ui.text(this, "‹", 28f, Color.WHITE, gravity = Gravity.CENTER).apply {
            background = Ui.rounded(
                this@DetailActivity, Color.argb(160, 0, 0, 0), 16
            )
            setPadding(dp(11), dp(2), dp(13), dp(7))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(dp(12), dp(12), 0, 0)
            }
            setOnClickListener { finish() }
        }
        hero.addView(back)
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
        }
        titleCol.addView(Ui.text(this, movie.title, 21f, Color.WHITE, bold = true))
        val metaLine = listOfNotNull(
            movie.year,
            movie.vote?.let { "★ $it" },
            if (movie.type == "song") "🎵 Video song" else "🎬 Movie"
        ).joinToString("  ·  ")
        if (metaLine.isNotBlank()) {
            titleCol.addView(
                Ui.text(this, metaLine, 12.5f, Color.argb(215, 255, 255, 255)).apply {
                    setPadding(0, dp(3), 0, 0)
                }
            )
        }
        hero.addView(titleCol)
        col.addView(hero)

        // ---------- Body ----------
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        // Meta chips: genres + source count.
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var chipCount = 0
        fun addChip(label: String) {
            val chip = Ui.text(this@DetailActivity, label, 11.5f, Ui.TEXT, bold = true).apply {
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = Ui.rounded(this@DetailActivity, Ui.CARD, 12)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (chipCount > 0) marginStart = dp(7) }
            }
            chips.addView(chip)
            chipCount++
        }
        for (g in movie.genres.take(3)) addChip(g)
        if (movie.sources.size > 1) addChip("${movie.sources.size} versions")
        if (chipCount > 0) body.addView(chips)

        if (!movie.overview.isNullOrBlank()) {
            body.addView(Ui.vspace(this, 12))
            body.addView(Ui.text(this, movie.overview!!, 14f, Ui.TEXT))
        }

        body.addView(Ui.vspace(this, 20))
        if (movie.sources.size <= 1) {
            val src = movie.sources.firstOrNull()
            if (src != null) {
                val playBtn = pill(Ui.greenButton(this, "▶  Play") { play(src) })
                playButtons.add(playBtn to src)
                body.addView(playBtn)
                body.addView(Ui.vspace(this, 10))
                body.addView(pill(Ui.ghostButton(this, "⬇  Download") { requestDownload(src) }))
            } else {
                body.addView(Ui.text(this, "No playable source for this title.", 14f, Ui.DIM))
            }
        } else {
            body.addView(Ui.text(this, "Versions", 15f, Ui.TEXT, bold = true))
            for (src in movie.sources) {
                body.addView(Ui.vspace(this, 8))
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = Ui.rounded(this@DetailActivity, Ui.CARD, 12)
                    setPadding(dp(12), dp(8), dp(8), dp(8))
                }
                row.addView(
                    Ui.text(this, src.label, 14.5f, Ui.TEXT, bold = true).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                        )
                    }
                )
                val play = Ui.greenButton(this, "▶") { play(src) }.apply {
                    (background as? GradientDrawable)?.cornerRadius = dp(18).toFloat()
                }
                playButtons.add(play to src)
                row.addView(play)
                val dl = Ui.ghostButton(this, "⬇") { requestDownload(src) }.apply {
                    (background as? GradientDrawable)?.cornerRadius = dp(18).toFloat()
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(8) }
                }
                row.addView(dl)
                body.addView(row)
            }
        }

        col.addView(body)
        scroll.addView(col)
        root.addView(scroll)
        root.addView(Ads.banner(this))
        setContentView(root)
    }

    /** Make a stock button a full-width pill. */
    private fun pill(b: Button): Button {
        (b.background as? GradientDrawable)?.cornerRadius = dp(24).toFloat()
        b.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50)
        )
        b.textSize = 15f
        return b
    }

    private fun play(src: Source) {
        var startAt = 0.0
        val entry = ProgressStore.get(this, movie.id)
        if (entry != null && !entry.done && entry.pos > 10 && entry.url == src.url) {
            startAt = entry.pos
        }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("url", src.url)
                .putExtra("title", movie.title)
                .putExtra("id", movie.id)
                .putExtra("poster", movie.posterUrl())
                .putExtra("type", movie.type)
                .putExtra("startAt", startAt)
        )
    }

    override fun onResume() {
        super.onResume()
        // Label the button for the last-played source with its resume point.
        val entry = ProgressStore.get(this, movie.id)
        for ((btn, src) in playButtons) {
            btn.text = if (entry != null && !entry.done && entry.pos > 10 && entry.url == src.url) {
                if (movie.sources.size <= 1) {
                    "▶  Resume ${fmtTime(entry.pos)}"
                } else {
                    "⏵ ${fmtTime(entry.pos)}"
                }
            } else if (movie.sources.size <= 1) {
                "▶  Play"
            } else {
                "▶"
            }
        }
    }

    private fun fmtTime(sec: Double): String {
        val s = sec.toInt()
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
    }

    /** Show the fullscreen ad first; the download starts when it closes. */
    private fun requestDownload(src: Source) {
        pendingSource = src
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, AdActivity::class.java), REQ_AD)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_AD && resultCode == RESULT_OK) {
            pendingSource?.let { download(it) }
        }
        pendingSource = null
    }

    private fun download(src: Source) {
        val fileName = (movie.title.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim()
            .ifBlank { "movie" }) + ".mp4"
        val req = DownloadManager.Request(Uri.parse(src.url)).apply {
            setTitle(movie.title)
            setDescription("Redowansky Library")
            setMimeType("video/mp4")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            try {
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            } catch (e: Exception) {
                setDestinationInExternalFilesDir(
                    this@DetailActivity, Environment.DIRECTORY_MOVIES, fileName
                )
            }
        }
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(req)
        Toast.makeText(this, "Downloading: ${movie.title}", Toast.LENGTH_SHORT).show()
    }
}
