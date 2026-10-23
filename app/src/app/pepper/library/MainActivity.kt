package app.pepper.library

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.pepper.library.Ui.dp
import java.io.File
import java.util.concurrent.Executors

/**
 * Redowansky Library — native home screen (modern UI, v0.6).
 * Catalog from the site's own JSON API (GET /api/movies — same feed
 * the Aniyomi extension uses). Header with Google avatar, a hero
 * Continue Watching banner, badge poster cards on a RecyclerView
 * grid, and a bottom nav: Home / Recent / Profile. Not a WebView.
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var all: List<Movie> = emptyList()
    private var section: String = "home" // home | recent
    private var filterType: String = "all" // within home: all | movie | song
    private var query: String = ""
    private var progressById: Map<String, ProgressStore.Entry> = emptyMap()

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: PosterRvAdapter
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var chipsRow: LinearLayout
    private lateinit var sectionTitle: TextView
    private val chipViews = mutableMapOf<String, TextView>()

    // Hero Continue Watching
    private lateinit var hero: FrameLayout
    private lateinit var heroImg: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroFill: View
    private lateinit var heroRest: View
    private var heroEntry: ProgressStore.Entry? = null

    // Update banner
    private lateinit var updateBanner: TextView
    private var pendingUpdate: Updater.Info? = null

    // Header account
    private lateinit var signInBtn: Button
    private lateinit var avatarBox: FrameLayout
    private lateinit var avatarImg: ImageView
    private lateinit var avatarInitial: TextView

    // Bottom nav
    private val navCols = mutableMapOf<String, Pair<TextView, TextView>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ads.init()
        PosterLoader.init(File(cacheDir, "posters").apply { mkdirs() })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        // ---------- Header: logo · search pill · account ----------
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(4))
        }
        val logo = FrameLayout(this).apply {
            background = Ui.rounded(this@MainActivity, Ui.GREEN, 10)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        }
        logo.addView(
            Ui.text(this, "🎬", 17f, Color.BLACK, gravity = Gravity.CENTER).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        )
        header.addView(logo)

        val search = EditText(this).apply {
            hint = "🔍  Search titles…"
            setHintTextColor(Ui.DIM)
            setTextColor(Ui.TEXT)
            textSize = 14f
            isSingleLine = true
            background = Ui.rounded(this@MainActivity, Ui.PANEL, 20)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(10); marginEnd = dp(10) }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim()?.lowercase() ?: ""
                    refresh()
                }

                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        header.addView(search)

        signInBtn = Ui.ghostButton(this, "Sign in") { onSignInTap() }
        header.addView(signInBtn)

        avatarBox = FrameLayout(this).apply {
            background = Ui.rounded(this@MainActivity, Ui.CARD, 19)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ProfileActivity::class.java))
            }
        }
        avatarInitial = Ui.text(this, "", 15f, Ui.TEXT, bold = true, gravity = Gravity.CENTER)
            .apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        avatarBox.addView(avatarInitial)
        avatarImg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        avatarBox.addView(avatarImg)
        header.addView(avatarBox)
        root.addView(header)

        // ---------- Update banner ----------
        updateBanner = Ui.text(this, "", 13.5f, Color.BLACK, bold = true).apply {
            visibility = View.GONE
            background = Ui.rounded(this@MainActivity, Ui.GREEN, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(14), dp(8), dp(14), 0) }
            setOnClickListener {
                pendingUpdate?.let { UpdateInstaller.start(this@MainActivity, it) }
            }
        }
        root.addView(updateBanner)

        // ---------- Hero: Continue Watching ----------
        hero = buildHero()
        root.addView(hero)

        // ---------- Filter chips (home section) ----------
        chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(12), dp(14), 0)
        }
        for ((key, label) in listOf(
            "all" to "All", "movie" to "🎬 Movies", "song" to "🎵 Songs"
        )) {
            val chip = Ui.text(this, label, 13f, Ui.TEXT, bold = true).apply {
                setPadding(dp(14), dp(7), dp(14), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    filterType = key
                    styleChips()
                    refresh()
                }
            }
            chipViews[key] = chip
            chipsRow.addView(chip)
        }
        root.addView(chipsRow)
        styleChips()

        sectionTitle = Ui.text(this, "Latest Updates", 17f, Ui.TEXT, bold = true).apply {
            setPadding(dp(14), dp(14), dp(14), dp(2))
        }
        root.addView(sectionTitle)

        progress = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(progress)
        status = Ui.text(this, "Loading library…", 14f, Ui.DIM, gravity = Gravity.CENTER).apply {
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        root.addView(status)

        // ---------- Poster grid ----------
        adapter = PosterRvAdapter()
        recycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            adapter = this@MainActivity.adapter
            setPadding(dp(10), dp(2), dp(10), dp(8))
            clipToPadding = false
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State
                ) {
                    outRect.set(dp(4), dp(4), dp(4), dp(12))
                }
            })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(recycler)

        // ---------- Bottom nav ----------
        root.addView(buildNav())

        // ---------- Mondiad banner ----------
        root.addView(Ads.banner(this))

        setContentView(root)
        loadCatalog()
        refreshHero()
        refreshAccountChip()
        checkUpdate()
        showCrashTraceIfAny()
    }

    /** If the previous run died, show exactly why (screenshot-friendly). */
    private fun showCrashTraceIfAny() {
        val trace = CrashTrap.peek(this) ?: return
        val scroll = android.widget.ScrollView(this)
        val tv = Ui.text(this, trace, 11f, Ui.TEXT).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
        }
        scroll.addView(tv)
        android.app.AlertDialog.Builder(this)
            .setTitle("⚠️ Last crash — send Pepper a screenshot")
            .setView(scroll)
            .setPositiveButton("Clear") { _, _ -> CrashTrap.clear(this) }
            .setNegativeButton("Later", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (section == "home") refreshHero()
        refreshAccountChip()
        refresh()
    }

    // ---------- Header account ----------

    private fun refreshAccountChip() {
        if (!::signInBtn.isInitialized) return
        val acct = try {
            Auth.lastAccount(this)
        } catch (t: Throwable) {
            null
        }
        if (acct == null) {
            signInBtn.visibility = View.VISIBLE
            avatarBox.visibility = View.GONE
            return
        }
        signInBtn.visibility = View.GONE
        avatarBox.visibility = View.VISIBLE
        val photo = acct.photoUrl
        if (photo != null) {
            avatarInitial.visibility = View.GONE
            avatarImg.visibility = View.VISIBLE
            PosterLoader.loadCircular(photo.toString(), avatarImg)
        } else {
            avatarImg.visibility = View.GONE
            avatarInitial.visibility = View.VISIBLE
            avatarInitial.text =
                acct.displayName?.firstOrNull()?.uppercaseChar()?.toString() ?: "👤"
        }
    }

    private fun onSignInTap() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Auth.signInIntent(this), Auth.RC_SIGN_IN)
        } catch (t: Throwable) {
            android.widget.Toast.makeText(
                this, "Sign-in start failed: ${t.javaClass.simpleName}: ${t.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
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

    // ---------- Hero: Continue Watching ----------

    private fun buildHero(): FrameLayout {
        val frame = FrameLayout(this).apply {
            visibility = View.GONE
            background = Ui.rounded(this@MainActivity, Ui.CARD, 14)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150)
            ).apply { setMargins(dp(14), dp(10), dp(14), 0) }
            setOnClickListener { heroEntry?.let { resumeEntry(it) } }
        }
        heroImg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        frame.addView(heroImg)
        val fade = Ui.fade(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(96)
            ).apply { gravity = Gravity.BOTTOM }
        }
        frame.addView(fade)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(76), dp(12))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
        }
        col.addView(Ui.text(this, "⏵ CONTINUE WATCHING", 10f, Ui.GREEN, bold = true))
        heroTitle = Ui.text(this, "", 16f, Color.WHITE, bold = true).apply { maxLines = 1 }
        col.addView(heroTitle)
        heroMeta = Ui.text(this, "", 11.5f, Color.argb(215, 255, 255, 255))
        col.addView(heroMeta)
        frame.addView(col)
        val play = Ui.text(this, "▶", 20f, Color.BLACK, gravity = Gravity.CENTER).apply {
            background = Ui.rounded(this@MainActivity, Ui.GREEN, 24)
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                setMargins(0, 0, dp(12), dp(12))
            }
            setOnClickListener { heroEntry?.let { resumeEntry(it) } }
        }
        frame.addView(play)
        val dismiss = Ui.text(this, "✕", 13f, Color.argb(200, 255, 255, 255), gravity = Gravity.CENTER)
            .apply {
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.END or Gravity.TOP }
                setOnClickListener {
                    heroEntry?.let { ProgressStore.remove(this@MainActivity, it.id) }
                    refreshHero()
                    refresh()
                }
            }
        frame.addView(dismiss)
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4)
            ).apply { gravity = Gravity.BOTTOM }
        }
        heroFill = View(this).apply { setBackgroundColor(Ui.GREEN) }
        heroRest = View(this).apply {
            setBackgroundColor(Color.argb(70, 255, 255, 255))
        }
        track.addView(heroFill)
        track.addView(heroRest)
        frame.addView(track)
        return frame
    }

    private fun resumeEntry(e: ProgressStore.Entry) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("url", e.url)
                .putExtra("title", e.title)
                .putExtra("id", e.id)
                .putExtra("poster", e.poster)
                .putExtra("type", e.type)
                .putExtra("startAt", e.pos)
        )
    }

    private fun refreshHero() {
        if (!::hero.isInitialized) return
        val e = ProgressStore.latestUnfinished(this)
        heroEntry = e
        if (e == null || section != "home") {
            hero.visibility = View.GONE
            return
        }
        hero.visibility = View.VISIBLE
        val wide = all.find { it.id == e.id }?.backdropUrl()
        PosterLoader.load(wide ?: e.poster, heroImg)
        heroTitle.text = e.title
        val leftSec = (e.dur - e.pos).coerceAtLeast(0.0).toInt()
        val leftText = when {
            leftSec >= 3600 -> "${leftSec / 3600}h ${(leftSec % 3600) / 60}m left"
            leftSec >= 60 -> "${leftSec / 60} min left"
            else -> "$leftSec sec left"
        }
        heroMeta.text = "$leftText · resume at ${fmtTime(e.pos)}"
        val frac = if (e.dur > 0) (e.pos / e.dur).toFloat().coerceIn(0f, 1f) else 0f
        heroFill.layoutParams = LinearLayout.LayoutParams(0, dp(4), frac)
        heroRest.layoutParams = LinearLayout.LayoutParams(0, dp(4), 1f - frac)
    }

    private fun fmtTime(sec: Double): String {
        val s = sec.toInt()
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
    }

    // ---------- Bottom nav ----------

    private fun buildNav(): LinearLayout {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.PANEL)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        for ((key, icon, label) in listOf(
            Triple("home", "🏠", "Home"),
            Triple("recent", "⏱", "Recent"),
            Triple("profile", "👤", "Profile")
        )) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, dp(3), 0, dp(3))
                setOnClickListener { onNavTap(key) }
            }
            val iconTv = Ui.text(this, icon, 19f, Ui.DIM, gravity = Gravity.CENTER)
            val labelTv = Ui.text(this, label, 10.5f, Ui.DIM, bold = true, gravity = Gravity.CENTER)
            item.addView(iconTv)
            item.addView(labelTv)
            navCols[key] = iconTv to labelTv
            nav.addView(item)
        }
        styleNav()
        return nav
    }

    private fun onNavTap(key: String) {
        if (key == "profile") {
            startActivity(Intent(this, ProfileActivity::class.java))
            return
        }
        section = key
        styleNav()
        chipsRow.visibility = if (section == "home") View.VISIBLE else View.GONE
        sectionTitle.text = if (section == "home") "Latest Updates" else "Recently Played"
        refreshHero()
        refresh()
    }

    private fun styleNav() {
        for ((key, pair) in navCols) {
            val active = key == section
            pair.first.setTextColor(if (active) Ui.GREEN else Ui.DIM)
            pair.second.setTextColor(if (active) Ui.GREEN else Ui.DIM)
        }
    }

    private fun styleChips() {
        for ((key, chip) in chipViews) {
            val active = key == filterType
            chip.setTextColor(if (active) Color.BLACK else Ui.TEXT)
            chip.background = Ui.rounded(this, if (active) Ui.GREEN else Ui.CARD, 18)
        }
    }

    // ---------- Updates ----------

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

    // ---------- Catalog + grid ----------

    private fun loadCatalog() {
        progress.visibility = View.VISIBLE
        status.text = "Loading library…"
        status.visibility = View.VISIBLE
        io.execute {
            try {
                val movies = Catalog.fetch()
                main.post {
                    all = movies
                    progress.visibility = View.GONE
                    refreshHero()
                    refresh()
                }
            } catch (e: Exception) {
                main.post {
                    progress.visibility = View.GONE
                    status.visibility = View.VISIBLE
                    status.text =
                        "⚠️ Couldn't load the library (${e.message ?: "network error"}).\nTap to retry."
                    status.setOnClickListener { loadCatalog() }
                }
            }
        }
    }

    private fun refresh() {
        if (!::adapter.isInitialized) return
        val entries = ProgressStore.all(this)
        progressById = entries.associateBy { it.id }
        val visible: List<Movie>
        if (section == "recent") {
            val byId = all.associateBy { it.id }
            visible = entries.mapNotNull { e ->
                if (query.isNotEmpty() && !e.title.lowercase().contains(query)) {
                    return@mapNotNull null
                }
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
        val showStatus = visible.isEmpty() && progress.visibility != View.VISIBLE
        status.visibility = if (showStatus) View.VISIBLE else View.GONE
        if (visible.isEmpty()) {
            status.text = if (section == "recent") {
                "Nothing here yet — play something and it will show up ⏱"
            } else {
                "Nothing matches."
            }
            status.setOnClickListener(null)
        }
    }

    private fun onPosterClick(movie: Movie) {
        if (section == "recent") {
            val entry = ProgressStore.get(this, movie.id)
            if (entry != null && entry.url.isNotBlank()) {
                startActivity(
                    Intent(this, PlayerActivity::class.java)
                        .putExtra("url", entry.url)
                        .putExtra("title", entry.title)
                        .putExtra("id", entry.id)
                        .putExtra("poster", entry.poster)
                        .putExtra("type", entry.type)
                        .putExtra("startAt", if (entry.done) 0.0 else entry.pos)
                )
                return
            }
        }
        startActivity(
            Intent(this, DetailActivity::class.java)
                .putExtra("movie", movie.toJson().toString())
        )
    }

    inner class PosterRvAdapter : RecyclerView.Adapter<PosterRvAdapter.VH>() {

        var visible: List<Movie> = emptyList()

        inner class VH(val cell: LinearLayout) : RecyclerView.ViewHolder(cell) {
            val frame: FrameLayout = cell.findViewById(1)
            val iv: ImageView = cell.findViewById(2)
            val typeBadge: TextView = cell.findViewById(3)
            val yearBadge: TextView = cell.findViewById(4)
            val progTrack: LinearLayout = cell.findViewById(5)
            val progFill: View = cell.findViewById(6)
            val title: TextView = cell.findViewById(7)
            val meta: TextView = cell.findViewById(8)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val cell = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val colW = (resources.displayMetrics.widthPixels - dp(28)) / 3
            val posterH = (colW * 1.42f).toInt()
            val frame = FrameLayout(this@MainActivity).apply {
                id = 1
                background = Ui.rounded(this@MainActivity, Ui.CARD, 12)
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, posterH
                )
            }
            val iv = ImageView(this@MainActivity).apply {
                id = 2
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            frame.addView(iv)
            val fade = Ui.fade(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(38)
                ).apply { gravity = Gravity.BOTTOM }
            }
            frame.addView(fade)
            val typeBadge = Ui.scrimBadge(this@MainActivity, "", Ui.GREEN).apply {
                id = 3
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.END or Gravity.TOP
                    setMargins(0, dp(6), dp(6), 0)
                }
            }
            frame.addView(typeBadge)
            val yearBadge = Ui.badge(this@MainActivity, "", Ui.GREEN, Color.BLACK).apply {
                id = 4
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.START or Gravity.BOTTOM
                    setMargins(dp(6), 0, 0, dp(6))
                }
            }
            frame.addView(yearBadge)
            val progTrack = LinearLayout(this@MainActivity).apply {
                id = 5
                orientation = LinearLayout.HORIZONTAL
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(4)
                ).apply { gravity = Gravity.BOTTOM }
            }
            val fill = View(this@MainActivity).apply {
                id = 6
                setBackgroundColor(Ui.GREEN)
            }
            val rest = View(this@MainActivity).apply {
                setBackgroundColor(Color.argb(70, 255, 255, 255))
            }
            progTrack.addView(fill)
            progTrack.addView(rest)
            frame.addView(progTrack)
            cell.addView(frame)

            val title = Ui.text(this@MainActivity, "", 12f, Ui.TEXT, bold = true).apply {
                id = 7
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, 0)
            }
            cell.addView(title)
            val meta = Ui.text(this@MainActivity, "", 10.5f, Ui.DIM).apply {
                id = 8
                maxLines = 1
            }
            cell.addView(meta)
            return VH(cell)
        }

        override fun getItemCount(): Int = visible.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val movie = visible[position]
            PosterLoader.load(movie.posterUrl(), holder.iv)
            holder.typeBadge.text = if (movie.type == "song") "SONG" else "MOVIE"
            if (!movie.year.isNullOrBlank()) {
                holder.yearBadge.visibility = View.VISIBLE
                holder.yearBadge.text = movie.year
            } else {
                holder.yearBadge.visibility = View.GONE
            }
            holder.title.text = movie.title
            val entry = progressById[movie.id]
            if (entry != null && entry.dur > 0) {
                holder.progTrack.visibility = View.VISIBLE
                val frac = (entry.pos / entry.dur).toFloat().coerceIn(0f, 1f)
                holder.progFill.layoutParams = LinearLayout.LayoutParams(0, dp(4), frac)
                (holder.progTrack.getChildAt(1)).layoutParams =
                    LinearLayout.LayoutParams(0, dp(4), 1f - frac)
            } else {
                holder.progTrack.visibility = View.GONE
            }
            holder.meta.text = if (section == "recent" && entry != null) {
                if (entry.done) {
                    "✓ Watched"
                } else {
                    val left = (entry.dur - entry.pos).toInt()
                    "⏵ ${left / 60} min left"
                }
            } else {
                listOfNotNull(
                    movie.vote?.let { "★ $it" },
                    if (movie.type == "song") "🎵 Song" else "🎬 Movie"
                ).joinToString(" · ")
            }
            holder.cell.setOnClickListener { onPosterClick(movie) }
        }
    }
}
