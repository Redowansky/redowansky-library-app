package app.pepper.library

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.pepper.library.Ui.dp
import java.io.File
import java.util.concurrent.Executors

/**
 * Redowansky Library — native home screen.
 * Catalog comes from the site's own JSON API (GET /api/movies, the same
 * feed the Aniyomi extension uses); posters, search and filtering are
 * all local/native. Not a WebView.
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var all: List<Movie> = emptyList()
    private var filterType: String = "all"
    private var query: String = ""

    private lateinit var grid: GridView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var adapter: PosterAdapter
    private val filterButtons = mutableMapOf<String, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ads.init()
        PosterLoader.init(File(cacheDir, "posters").apply { mkdirs() })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(12), dp(12), dp(12), 0)
        }

        root.addView(Ui.text(this, "🎬 Redowansky's Library", 20f, Ui.TEXT, bold = true))
        root.addView(Ui.vspace(this, 10))

        val search = EditText(this).apply {
            hint = "Search titles…"
            setHintTextColor(Ui.DIM)
            setTextColor(Ui.TEXT)
            setBackgroundColor(Ui.PANEL)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim()?.lowercase() ?: ""
                    refresh()
                }

                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(search)
        root.addView(Ui.vspace(this, 10))

        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((key, label) in listOf("all" to "All", "movie" to "🎬 Movies", "song" to "🎵 Songs")) {
            val b = Ui.ghostButton(this, label) {
                filterType = key
                styleFilters()
                refresh()
            }
            b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(6) }
            filterButtons[key] = b
            filters.addView(b)
        }
        root.addView(filters)
        styleFilters()
        root.addView(Ui.vspace(this, 10))

        progress = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(progress)

        status = Ui.text(this, "Loading library…", 14f, Ui.DIM, gravity = Gravity.CENTER)
        root.addView(status)

        adapter = PosterAdapter()
        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(8)
            verticalSpacing = dp(10)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                val movie = this@MainActivity.adapter.visible[pos]
                startActivity(
                    Intent(this@MainActivity, DetailActivity::class.java)
                        .putExtra("movie", movie.toJson().toString())
                )
            }
        }
        root.addView(grid)
        root.addView(Ads.banner(this))

        setContentView(root)
        loadCatalog()
    }

    private fun styleFilters() {
        for ((key, b) in filterButtons) {
            val active = key == filterType
            b.setTextColor(if (active) android.graphics.Color.BLACK else Ui.TEXT)
            (b.background as? android.graphics.drawable.GradientDrawable)?.setColor(
                if (active) Ui.GREEN else Ui.CARD
            )
        }
    }

    private fun loadCatalog() {
        progress.visibility = View.VISIBLE
        status.text = "Loading library…"
        io.execute {
            try {
                val movies = Catalog.fetch()
                main.post {
                    all = movies
                    progress.visibility = View.GONE
                    refresh()
                }
            } catch (e: Exception) {
                main.post {
                    progress.visibility = View.GONE
                    status.text = "⚠️ Couldn't load the library (${e.message ?: "network error"}).\nTap to retry."
                    status.setOnClickListener { loadCatalog() }
                }
            }
        }
    }

    private fun refresh() {
        val visible = all.filter { m ->
            (filterType == "all" || m.type == filterType) &&
                (query.isEmpty() || m.title.lowercase().contains(query))
        }
        adapter.visible = visible
        adapter.notifyDataSetChanged()
        status.visibility = if (visible.isEmpty() && all.isNotEmpty()) View.VISIBLE else View.GONE
        if (visible.isEmpty() && all.isNotEmpty()) {
            status.text = "Nothing matches."
            status.setOnClickListener(null)
        }
    }

    inner class PosterAdapter : BaseAdapter() {
        var visible: List<Movie> = emptyList()
        override fun getCount(): Int = visible.size
        override fun getItem(pos: Int): Movie = visible[pos]
        override fun getItemId(pos: Int): Long = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
            val movie = visible[pos]
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                val iv = ImageView(this@MainActivity).apply {
                    id = 1
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Ui.CARD)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(150)
                    )
                }
                addView(iv)
                val tv = Ui.text(this@MainActivity, "", 12f, Ui.TEXT).apply {
                    id = 2
                    maxLines = 2
                    setPadding(0, dp(4), 0, 0)
                }
                addView(tv)
                val yv = Ui.text(this@MainActivity, "", 11f, Ui.DIM).apply { id = 3 }
                addView(yv)
            }
            val iv = cell.findViewById<ImageView>(1)
            val tv = cell.findViewById<TextView>(2)
            val yv = cell.findViewById<TextView>(3)
            PosterLoader.load(movie.posterUrl(), iv)
            tv.text = movie.title
            yv.text = listOfNotNull(
                movie.year,
                movie.vote?.let { "★ $it" },
                if (movie.type == "song") "🎵" else null
            ).joinToString(" · ")
            return cell
        }
    }
}
