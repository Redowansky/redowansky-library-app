package app.pepper.library

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import app.pepper.library.Ui.dp
import org.json.JSONObject
import java.io.File

class DetailActivity : Activity() {

    private lateinit var movie: Movie

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
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Ui.CARD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190)
            )
        }
        PosterLoader.load(movie.backdropUrl() ?: movie.posterUrl("w500"), backdrop)
        col.addView(backdrop)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        body.addView(Ui.text(this, movie.title, 20f, Ui.TEXT, bold = true))
        val meta = listOfNotNull(
            movie.year,
            movie.vote?.let { "★ $it" },
            movie.genres.joinToString(", ").ifBlank { null },
            if (movie.type == "song") "🎵 Video song" else "🎬 Movie"
        ).joinToString("  ·  ")
        if (meta.isNotBlank()) {
            body.addView(Ui.vspace(this, 6))
            body.addView(Ui.text(this, meta, 13f, Ui.DIM))
        }
        if (!movie.overview.isNullOrBlank()) {
            body.addView(Ui.vspace(this, 12))
            body.addView(Ui.text(this, movie.overview!!, 14f, Ui.TEXT))
        }

        body.addView(Ui.vspace(this, 18))
        if (movie.sources.size <= 1) {
            val src = movie.sources.firstOrNull()
            if (src != null) {
                body.addView(Ui.greenButton(this, "▶  Play") { play(src) })
                body.addView(Ui.vspace(this, 10))
                body.addView(Ui.ghostButton(this, "⬇  Download") { download(src) })
            } else {
                body.addView(Ui.text(this, "No playable source for this title.", 14f, Ui.DIM))
            }
        } else {
            body.addView(Ui.text(this, "Versions", 15f, Ui.TEXT, bold = true))
            for (src in movie.sources) {
                body.addView(Ui.vspace(this, 8))
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                val play = Ui.greenButton(this, "▶ ${src.label}") { play(src) }
                play.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(8) }
                row.addView(play)
                val dl = Ui.ghostButton(this, "⬇") { download(src) }
                row.addView(dl)
                body.addView(row)
            }
        }

        scroll.addView(col)
        // body is inside scroll flow: attach under backdrop via col
        col.addView(body)
        root.addView(scroll)
        root.addView(Ads.banner(this))
        setContentView(root)
    }

    private fun play(src: Source) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("url", src.url)
                .putExtra("title", movie.title)
        )
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
