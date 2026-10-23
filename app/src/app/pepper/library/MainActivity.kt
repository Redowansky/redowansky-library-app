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

    // Continue Watching card
    private lateinit var continueCard: LinearLayout
    private lateinit var continuePoster: ImageView
    private lateinit var continueTitle: TextView
    private lateinit var continueMeta: TextView
    private lateinit var continueFill: View
    private lateinit var continueRest: View
    private var continueEntry: ProgressStore.Entry? = null

    // In-app update banner
    private lateinit var updateBanner: TextView
    private var pendingUpdate: Updater.Info? = null

    // Google account chip (header, top-right)
    private lateinit var accountBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ads.init()
        PosterLoader.init(File(cacheDir, "posters").apply { mkdirs() })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(12), dp(12), dp(12), 0)
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(
            Ui.text(this, "🎬 Redowansky's Library", 20f, Ui.TEXT, bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        accountBtn = Ui.ghostButton(this, "Sign in") { onAccountTap() }
        header.addView(accountBtn)
        root.addView(header)
        root.addView(Ui.vspace(this, 8))

        updateBanner = Ui.text(this, "", 14f, android.graphics.Color.BLACK, bold = true).apply {
            visibility = View.GONE
            setBackgroundColor(Ui.GREEN)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { pendingUpdate?.let { startUpdateDownload(it) } }
        }
        root.addView(updateBanner)
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

        continueCard = buildContinueCard()
        continueCard.visibility = View.GONE
        root.addView(continueCard)

        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((key, label) in listOf(
            "all" to "All", "movie" to "🎬 Movies", "song" to "🎵 Songs", "recent" to "⏱ Recent"
        )) {
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
                if (this@MainActivity.filterType == "recent") {
                    // Recently Played: tapping resumes right away.
                    val entry = ProgressStore.get(this@MainActivity, movie.id)
                    if (entry != null && entry.url.isNotBlank()) {
                        startActivity(
                            Intent(this@MainActivity, PlayerActivity::class.java)
                                .putExtra("url", entry.url)
                                .putExtra("title", entry.title)
                                .putExtra("id", entry.id)
                                .putExtra("poster", entry.poster)
                                .putExtra("type", entry.type)
                                .putExtra("startAt", if (entry.done) 0.0 else entry.pos)
                        )
                        return@setOnItemClickListener
                    }
                }
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
        refreshContinue()
        refreshAccountChip()
        checkUpdate()
    }

    override fun onResume() {
        super.onResume()
        refreshContinue()
        refreshAccountChip()
        if (filterType == "recent") refresh()
    }

    // ---------- Google account ----------

    private fun refreshAccountChip() {
        if (!::accountBtn.isInitialized) return
        val acct = try {
            Auth.lastAccount(this)
        } catch (e: Exception) {
            null
        }
        accountBtn.text = if (acct != null) {
            "👤 " + (acct.displayName?.substringBefore(" ") ?: acct.email ?: "Account")
        } else {
            "Sign in"
        }
    }

    private fun onAccountTap() {
        val acct = try {
            Auth.lastAccount(this)
        } catch (e: Exception) {
            null
        }
        if (acct == null) {
            @Suppress("DEPRECATION")
            startActivityForResult(Auth.signInIntent(this), Auth.RC_SIGN_IN)
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(acct.displayName ?: "Google account")
            .setMessage(acct.email ?: "")
            .setNegativeButton("Close", null)
            .setPositiveButton("Sign out") { _, _ ->
                Auth.signOut(this) {
                    refreshAccountChip()
                    android.widget.Toast.makeText(this, "Signed out", android.widget.Toast.LENGTH_SHORT)
                        .show()
                }
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != Auth.RC_SIGN_IN) return
        try {
            val acct = Auth.accountFromResult(data)
            refreshAccountChip()
            android.widget.Toast.makeText(
                this, "Signed in as ${acct?.email ?: "Google account"}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (e: com.google.android.gms.common.api.ApiException) {
            // 12501 = user cancelled; anything else gets surfaced as-is.
            if (e.statusCode != 12501) {
                android.widget.Toast.makeText(
                    this, "Sign-in failed (code ${e.statusCode})",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                this, "Sign-in failed: ${e.message}", android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    // ---------- Continue Watching ----------

    private fun buildContinueCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.PANEL)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        continuePoster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(68))
        }
        card.addView(continuePoster)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(10) }
        }
        col.addView(Ui.text(this, "⏵ CONTINUE WATCHING", 11f, Ui.GREEN, bold = true))
        continueTitle = Ui.text(this, "", 15f, Ui.TEXT, bold = true).apply { maxLines = 1 }
        col.addView(continueTitle)
        continueMeta = Ui.text(this, "", 12f, Ui.DIM)
        col.addView(continueMeta)
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(5)
            ).apply { topMargin = dp(6) }
        }
        continueFill = View(this).apply { setBackgroundColor(Ui.GREEN) }
        continueRest = View(this).apply { setBackgroundColor(Ui.CARD) }
        track.addView(continueFill)
        track.addView(continueRest)
        col.addView(track)
        card.addView(col)
        val play = Ui.text(this, "▶", 26f, Ui.GREEN, gravity = Gravity.CENTER).apply {
            setPadding(dp(12), dp(8), dp(6), dp(8))
            setOnClickListener {
                continueEntry?.let { e ->
                    startActivity(
                        Intent(this@MainActivity, PlayerActivity::class.java)
                            .putExtra("url", e.url)
                            .putExtra("title", e.title)
                            .putExtra("id", e.id)
                            .putExtra("poster", e.poster)
                            .putExtra("type", e.type)
                            .putExtra("startAt", e.pos)
                    )
                }
            }
        }
        card.addView(play)
        val dismiss = Ui.text(this, "✕", 15f, Ui.DIM, gravity = Gravity.CENTER).apply {
            setPadding(dp(6), dp(8), dp(2), dp(8))
            setOnClickListener {
                continueEntry?.let { ProgressStore.remove(this@MainActivity, it.id) }
                refreshContinue()
                if (filterType == "recent") refresh()
            }
        }
        card.addView(dismiss)
        return card
    }

    private fun refreshContinue() {
        if (!::continueCard.isInitialized) return
        val e = ProgressStore.latestUnfinished(this)
        continueEntry = e
        if (e == null) {
            continueCard.visibility = View.GONE
            return
        }
        continueCard.visibility = View.VISIBLE
        PosterLoader.load(e.poster, continuePoster)
        continueTitle.text = e.title
        val leftSec = (e.dur - e.pos).coerceAtLeast(0.0).toInt()
        val leftText = when {
            leftSec >= 3600 -> "${leftSec / 3600}h ${(leftSec % 3600) / 60}m left"
            leftSec >= 60 -> "${leftSec / 60} min left"
            else -> "$leftSec sec left"
        }
        continueMeta.text = "$leftText · resume at ${fmtTime(e.pos)}"
        val frac = if (e.dur > 0) (e.pos / e.dur).toFloat().coerceIn(0f, 1f) else 0f
        continueFill.layoutParams = LinearLayout.LayoutParams(0, dp(5), frac)
        continueRest.layoutParams = LinearLayout.LayoutParams(0, dp(5), 1f - frac)
    }

    private fun fmtTime(sec: Double): String {
        val s = sec.toInt()
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
    }

    // ---------- In-app update ----------

    private fun checkUpdate() {
        io.execute {
            val info = try {
                Updater.check(this)
            } catch (e: Exception) {
                null
            }
            if (info != null) {
                main.post {
                    pendingUpdate = info
                    updateBanner.text = "🆕 Update available: v${info.versionName}" +
                        (if (info.notes.isNotBlank()) " — ${info.notes}" else "") +
                        "\nTap to download & install"
                    updateBanner.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun startUpdateDownload(info: Updater.Info) {
        if (android.os.Build.VERSION.SDK_INT >= 26 &&
            !packageManager.canRequestPackageInstalls()
        ) {
            android.widget.Toast.makeText(
                this,
                "Allow installs for this app once, then tap the update banner again",
                android.widget.Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:$packageName")
                )
            )
            return
        }
        val file = File(getExternalFilesDir(null), "update.apk")
        file.delete()
        val req = android.app.DownloadManager.Request(android.net.Uri.parse(info.apkUrl)).apply {
            setTitle("Redowansky Library v${info.versionName}")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationInExternalFilesDir(
                this@MainActivity, null, "update.apk"
            )
        }
        val dm = getSystemService(android.content.Context.DOWNLOAD_SERVICE)
            as android.app.DownloadManager
        val id = dm.enqueue(req)
        android.widget.Toast.makeText(this, "Downloading update…", android.widget.Toast.LENGTH_SHORT)
            .show()
        io.execute {
            var done = false
            while (!done) {
                Thread.sleep(2000)
                val cursor = dm.query(android.app.DownloadManager.Query().setFilterById(id))
                if (cursor != null && cursor.moveToFirst()) {
                    val st = cursor.getInt(
                        cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS)
                    )
                    if (st == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                        done = true
                        main.post { installUpdate(file) }
                    } else if (st == android.app.DownloadManager.STATUS_FAILED) {
                        done = true
                        main.post {
                            android.widget.Toast.makeText(
                                this@MainActivity, "Update download failed",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                cursor?.close()
            }
        }
    }

    private fun installUpdate(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.fileprovider", file
        )
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
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

    private var progressById: Map<String, ProgressStore.Entry> = emptyMap()

    private fun refresh() {
        val entries = ProgressStore.all(this)
        progressById = entries.associateBy { it.id }
        val visible: List<Movie>
        if (filterType == "recent") {
            val byId = all.associateBy { it.id }
            visible = entries.mapNotNull { e ->
                if (query.isNotEmpty() && !e.title.lowercase().contains(query)) return@mapNotNull null
                byId[e.id] ?: Movie(
                    id = e.id,
                    title = e.title,
                    year = null,
                    overview = null,
                    poster = e.poster,
                    backdrop = null,
                    vote = null,
                    genres = emptyList(),
                    type = e.type,
                    sources = listOf(Source("Play", e.url))
                )
            }
        } else {
            visible = all.filter { m ->
                (filterType == "all" || m.type == filterType) &&
                    (query.isEmpty() || m.title.lowercase().contains(query))
            }
        }
        adapter.visible = visible
        adapter.notifyDataSetChanged()
        status.visibility = if (visible.isEmpty() && (all.isNotEmpty() || filterType == "recent")) {
            View.VISIBLE
        } else {
            View.GONE
        }
        if (visible.isEmpty()) {
            status.text = if (filterType == "recent") {
                "Nothing here yet — play something and it will show up ⏱"
            } else {
                "Nothing matches."
            }
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
                val track = LinearLayout(this@MainActivity).apply {
                    id = 4
                    orientation = LinearLayout.HORIZONTAL
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(4)
                    )
                }
                val fill = View(this@MainActivity).apply {
                    id = 5
                    setBackgroundColor(Ui.GREEN)
                }
                val rest = View(this@MainActivity).apply { setBackgroundColor(Ui.CARD) }
                track.addView(fill)
                track.addView(rest)
                addView(track)
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
            val track = cell.findViewById<LinearLayout>(4)
            PosterLoader.load(movie.posterUrl(), iv)
            tv.text = movie.title
            val entry = progressById[movie.id]
            if (entry != null && entry.dur > 0) {
                track.visibility = View.VISIBLE
                val frac = (entry.pos / entry.dur).toFloat().coerceIn(0f, 1f)
                track.findViewById<View>(5).layoutParams =
                    LinearLayout.LayoutParams(0, dp(4), frac)
                (track.getChildAt(1)).layoutParams =
                    LinearLayout.LayoutParams(0, dp(4), 1f - frac)
            } else {
                track.visibility = View.GONE
            }
            yv.text = if (filterType == "recent" && entry != null) {
                if (entry.done) {
                    "✓ Watched"
                } else {
                    val left = (entry.dur - entry.pos).toInt()
                    "⏵ ${left / 60} min left"
                }
            } else {
                listOfNotNull(
                    movie.year,
                    movie.vote?.let { "★ $it" },
                    if (movie.type == "song") "🎵" else null
                ).joinToString(" · ")
            }
            return cell
        }
    }
}
