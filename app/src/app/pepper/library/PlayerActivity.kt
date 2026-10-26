package app.pepper.library

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import `is`.xyz.mpv.MPVLib
import app.pepper.library.Ui.dp
import java.io.File
import kotlin.math.abs

/**
 * Fullscreen mpv player (Aniyomi's engine + Aniyomi-style integration).
 *
 * UI: top bar (back, title, decoder chip), bottom bar (seekbar with
 * buffered indicator, play/pause, ∓10s, speed, audio, subtitle buttons),
 * big center play button when paused, buffering % while loading.
 * Gestures: tap = toggle controls, double-tap sides = ∓10s,
 * horizontal drag = seek scrub, vertical drag left = brightness,
 * vertical drag right = volume.
 * Errors surface in an on-screen panel with mpv's own message.
 */
class PlayerActivity : Activity(), MPVLib.EventObserver, MPVLib.LogObserver {

    private lateinit var player: LibraryMPVView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var posLabel: TextView
    private lateinit var durLabel: TextView
    private lateinit var playBtn: TextView
    private lateinit var centerPlay: TextView
    private lateinit var channelsBtn: TextView
    private lateinit var subsBtn: TextView
    private lateinit var hwChip: TextView
    private lateinit var pill: LinearLayout
    private lateinit var pillIcon: TextView
    private lateinit var pillFill: View
    private lateinit var pillPct: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var bufferLabel: TextView
    private lateinit var flashLabel: TextView
    private lateinit var errorPanel: TextView

    private val main = Handler(Looper.getMainLooper())
    private var controlsVisible = true
    private var userScrubbing = false
    private var started = false

    // resume / progress bookkeeping
    private var movieId: String = ""
    private var movieTitle: String = ""
    private var moviePoster: String? = null
    private var movieType: String = "movie"
    private var playUrl: String = ""
    private var startAt: Double = 0.0
    private var seekApplied = false
    private var tickCount = 0
    private val recentLog = mutableListOf<String>()
    // Audio channel modes (Aniyomi's player has the same cycle):
    // label -> mpv audio-channels value ("reverse" = stereo + pan swap)
    private val channelModes = listOf(
        "Auto" to "auto", "Stereo" to "stereo", "Mono" to "mono", "Reverse" to "reverse"
    )
    private var channelIdx = 0
    private var subTrackCount = -1 // -1 = not probed yet

    // gesture state
    private var gMode = 0 // 0 none, 1 seek, 2 brightness, 3 volume
    private var gStartX = 0f
    private var gStartY = 0f
    private var gStartPos = 0.0
    private var gStartBright = 0.5f
    private var gStartVol = 0
    private var gPendingSeek: Double? = null

    private val hideRunnable = Runnable { setControls(false) }
    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            main.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url")
        val title = intent.getStringExtra("title") ?: ""
        if (url == null) {
            finish()
            return
        }
        movieId = intent.getStringExtra("id") ?: ""
        movieTitle = title
        moviePoster = intent.getStringExtra("poster")
        movieType = intent.getStringExtra("type") ?: "movie"
        playUrl = url
        startAt = intent.getDoubleExtra("startAt", 0.0)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }

        player = LayoutInflater.from(this)
            .inflate(R.layout.player_view, root, false) as LibraryMPVView
        root.addView(player)

        val centerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        spinner = ProgressBar(this)
        centerBox.addView(spinner)
        bufferLabel = Ui.text(this, "", 12f, Ui.DIM, gravity = Gravity.CENTER)
        centerBox.addView(bufferLabel)
        root.addView(centerBox)

        centerPlay = Ui.text(this, "▶", 34f, Ui.TEXT, gravity = Gravity.CENTER).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x99000000.toInt())
                setStroke(dp(2), Ui.GREEN)
            }
            layoutParams = FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER)
            setOnClickListener { player.cyclePause() }
        }
        root.addView(centerPlay)

        flashLabel = Ui.text(this, "", 20f, Ui.TEXT, bold = true, gravity = Gravity.CENTER).apply {
            visibility = View.GONE
            setShadowLayer(8f, 0f, 0f, android.graphics.Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        root.addView(flashLabel)

        // Small indicator pill (Aniyomi-style) for volume / brightness drags.
        pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(0xCC222222.toInt())
                cornerRadius = dp(20).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(56) }
        }
        pillIcon = Ui.text(this, "🔊", 15f, Ui.TEXT)
        pill.addView(pillIcon)
        val track = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(110), dp(6)).apply {
                marginStart = dp(10)
                marginEnd = dp(10)
            }
            background = GradientDrawable().apply {
                setColor(0xFF555555.toInt())
                cornerRadius = dp(3).toFloat()
            }
        }
        pillFill = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(55), dp(6))
            background = GradientDrawable().apply {
                setColor(Ui.GREEN)
                cornerRadius = dp(3).toFloat()
            }
        }
        track.addView(pillFill)
        pill.addView(track)
        pillPct = Ui.text(this, "50%", 13f, Ui.TEXT, bold = true)
        pill.addView(pillPct)
        root.addView(pill)

        errorPanel = Ui.text(this, "", 13f, Ui.TEXT).apply {
            visibility = View.GONE
            setBackgroundColor(0xCC1A0000.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        root.addView(errorPanel)

        topBar = buildTopBar(title)
        root.addView(topBar)
        bottomBar = buildBottomBar()
        root.addView(bottomBar)

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                setControls(!controlsVisible)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!started) return true
                val pos = player.timePosSec ?: 0.0
                if (e.x < resources.displayMetrics.widthPixels / 2) {
                    player.timePosSec = (pos - 10).coerceAtLeast(0.0)
                    flash("⏪  −10s", left = true)
                } else {
                    player.timePosSec = pos + 10
                    flash("+10s  ⏩", left = false)
                }
                return true
            }
        })

        root.setOnTouchListener { v, ev ->
            gestures.onTouchEvent(ev)
            handleGestureTouch(v, ev)
            true
        }

        setContentView(root)

        try {
            prepareMpvConfigDir()
            player.initialize(mpvConfigDir().path, cacheDir.path, "warn")
            MPVLib.addLogObserver(this)
            MPVLib.addObserver(this)
            if (startAt > 5) {
                // mpv applies the start option when the file loads;
                // updateProgress also force-seeks if this gets ignored.
                MPVLib.setOptionString("start", startAt.toString())
            }
            player.playFile(url)
            main.post(tick)
            scheduleHide()
        } catch (t: Throwable) {
            showError("Player failed to start:\n${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---------- layout ----------

    private fun buildTopBar(title: String): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xB3000000.toInt())
            setPadding(dp(8), dp(8), dp(14), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }
        val back = Ui.text(this, "←", 22f, Ui.TEXT, gravity = Gravity.CENTER).apply {
            setPadding(dp(10), dp(4), dp(14), dp(4))
            setOnClickListener { finish() }
        }
        bar.addView(back)
        bar.addView(
            Ui.text(this, title, 15f, Ui.TEXT, bold = true).apply {
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        hwChip = Ui.text(this, "HW", 11f, Ui.GREEN, bold = true, gravity = Gravity.CENTER).apply {
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                setColor(Ui.CARD)
                cornerRadius = dp(10).toFloat()
            }
        }
        bar.addView(hwChip)
        return bar
    }

    private fun smallBtn(label: String, onClick: () -> Unit): TextView =
        Ui.text(this, label, 13f, Ui.TEXT, gravity = Gravity.CENTER).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { onClick() }
        }

    private fun buildBottomBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xB3000000.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }
        val seekRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        posLabel = Ui.text(this, "0:00", 12f, Ui.TEXT)
        seekRow.addView(posLabel)
        seekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(8); marginEnd = dp(8) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) posLabel.text = fmt(progress)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    userScrubbing = true
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    userScrubbing = false
                    player.timePosSec = sb?.progress?.toDouble() ?: 0.0
                }
            })
        }
        seekRow.addView(seekBar)
        durLabel = Ui.text(this, "0:00", 12f, Ui.DIM)
        seekRow.addView(durLabel)
        bar.addView(seekRow)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playBtn = Ui.text(this, "⏸", 24f, Ui.TEXT, gravity = Gravity.CENTER).apply {
            setPadding(dp(8), dp(2), dp(14), dp(2))
            setOnClickListener { player.cyclePause() }
        }
        btnRow.addView(playBtn)
        channelsBtn = smallBtn("🔊 Auto") { cycleChannels() }
        btnRow.addView(channelsBtn)
        subsBtn = smallBtn("💬 Subs") { cycleSubs() }
        btnRow.addView(subsBtn)
        bar.addView(btnRow)
        return bar
    }

    /**
     * Audio is a CHANNELS switch (Auto/Stereo/Mono/Reverse stereo) like
     * Aniyomi's — our files carry a single audio track, so cycling tracks
     * just muted playback. Reverse = stereo with L/R swapped (pan filter).
     */
    private fun cycleChannels() {
        channelIdx = (channelIdx + 1) % channelModes.size
        val (label, value) = channelModes[channelIdx]
        try {
            if (value == "reverse") {
                MPVLib.setPropertyString("audio-channels", "stereo")
                MPVLib.setPropertyString("af", "lavfi=[pan=stereo|c0=c1|c1=c0]")
            } else {
                MPVLib.setPropertyString("af", "")
                MPVLib.setPropertyString("audio-channels", value)
            }
        } catch (t: Throwable) { /* property rejected; toast still informs */ }
        channelsBtn.text = "🔊 $label"
        Toast.makeText(this, "Audio channels: $label", Toast.LENGTH_SHORT).show()
        setControls(true)
    }

    private fun cycleSubs() {
        if (subTrackCount == 0) {
            Toast.makeText(this, "No subtitles embedded in this file", Toast.LENGTH_SHORT).show()
            setControls(true)
            return
        }
        MPVLib.command(arrayOf("cycle", "sub"))
        main.postDelayed({
            try {
                val sid = MPVLib.getPropertyInt("sid")
                var label = if (sid == null) "off" else "#$sid"
                if (sid != null) {
                    val count = MPVLib.getPropertyInt("track-list/count") ?: 0
                    for (i in 0 until count) {
                        if (MPVLib.getPropertyString("track-list/$i/type") != "sub") continue
                        if (MPVLib.getPropertyInt("track-list/$i/id") != sid) continue
                        val lang = MPVLib.getPropertyString("track-list/$i/lang")
                        val title = MPVLib.getPropertyString("track-list/$i/title")
                        label = listOfNotNull(title, lang).joinToString(" ").ifBlank { label }
                    }
                }
                Toast.makeText(this, "Subtitle: $label", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) { /* best-effort */ }
        }, 250)
        setControls(true)
    }

    private fun probeSubTracks() {
        try {
            val count = MPVLib.getPropertyInt("track-list/count") ?: 0
            var subs = 0
            for (i in 0 until count) {
                if (MPVLib.getPropertyString("track-list/$i/type") == "sub") subs++
            }
            subTrackCount = subs
            subsBtn.alpha = if (subs == 0) 0.35f else 1f
        } catch (t: Throwable) { /* probe again next tick */ }
    }

    // ---------- gestures ----------

    private fun handleGestureTouch(v: View, ev: MotionEvent) {
        val w = v.width.toFloat().coerceAtLeast(1f)
        val h = v.height.toFloat().coerceAtLeast(1f)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gStartX = ev.x
                gStartY = ev.y
                gStartPos = player.timePosSec ?: 0.0
                gStartBright = window.attributes.screenBrightness.let {
                    if (it < 0) 0.5f else it
                }
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                gStartVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                gMode = 0
                gPendingSeek = null
            }

            MotionEvent.ACTION_MOVE -> {
                if (!started) return
                val dx = ev.x - gStartX
                val dy = gStartY - ev.y
                if (gMode == 0 && (abs(dx) > 36 || abs(dy) > 36)) {
                    gMode = if (abs(dx) > abs(dy)) 1 else if (gStartX < w / 2) 2 else 3
                }
                when (gMode) {
                    1 -> {
                        val dur = (player.durationSec ?: 0).toDouble()
                        if (dur > 0) {
                            val delta = (dx / w) * 240.0
                            gPendingSeek = (gStartPos + delta).coerceIn(0.0, dur)
                            flash("⏩ ${fmt(gPendingSeek!!.toInt())} / ${fmt(dur.toInt())}", null)
                        }
                    }

                    2 -> {
                        val b = (gStartBright + dy / h).coerceIn(0.05f, 1f)
                        window.attributes = window.attributes.apply { screenBrightness = b }
                        showPill("☀", (b * 100).toInt())
                    }

                    3 -> {
                        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val vol = (gStartVol + (dy / h) * max).toInt().coerceIn(0, max)
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                        showPill("🔊", vol * 100 / max.coerceAtLeast(1))
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gMode == 1 && gPendingSeek != null) {
                    player.timePosSec = gPendingSeek
                }
                gMode = 0
                gPendingSeek = null
            }
        }
    }

    // ---------- sync / state ----------

    private fun saveProgress() {
        if (movieId.isBlank() || !started) return
        try {
            val pos = player.timePosSec ?: return
            val dur = player.durationSec ?: return
            if (pos < 3 || dur <= 0) return
            val entry = ProgressStore.Entry(
                id = movieId,
                title = movieTitle,
                poster = moviePoster,
                type = movieType,
                url = playUrl,
                pos = pos,
                dur = dur.toDouble(),
                ts = System.currentTimeMillis(),
                done = pos >= dur - 45
            )
            ProgressStore.save(this, entry)
            // Cross-device sync (best-effort, throttled inside CloudSync).
            // Local offline files have no web URL to sync: a phone-local
            // path would be useless (and wrong) on the site/PC, so skip.
            if (playUrl.startsWith("http")) CloudSync.uploadEntry(this, entry)
        } catch (t: Throwable) { /* progress is best-effort */ }
    }

    private fun updateProgress() {
        if (!::player.isInitialized) return
        try {
            val dur = player.durationSec
            val pos = player.timePosSec
            if (dur != null && dur > 0) {
                // Resume fallback: if the mpv start option was ignored
                // and we're still near the beginning, seek once.
                if (!seekApplied && startAt > 5 && pos != null) {
                    if (pos < startAt - 15) player.timePosSec = startAt
                    seekApplied = true
                }
                tickCount++
                if (tickCount % 5 == 0) saveProgress()
                started = true
                if (subTrackCount == -1) probeSubTracks()
                spinner.visibility = View.GONE
                bufferLabel.text = ""
                seekBar.max = dur
                durLabel.text = fmt(dur)
                if (!userScrubbing && gMode != 1 && pos != null) {
                    seekBar.progress = pos.toInt()
                    posLabel.text = fmt(pos.toInt())
                }
                val cache = MPVLib.getPropertyInt("demuxer-cache-time")
                if (cache != null && pos != null) {
                    seekBar.secondaryProgress = (pos + cache).toInt().coerceAtMost(dur)
                }
                val hw = MPVLib.getPropertyString("hwdec-current")
                hwChip.text = if (hw.isNullOrBlank() || hw == "no") "SW" else "HW"
                hwChip.setTextColor(if (hw.isNullOrBlank() || hw == "no") Ui.DIM else Ui.GREEN)
            } else if (!started) {
                val pct = try {
                    MPVLib.getPropertyInt("cache-buffering-state")
                } catch (t: Throwable) {
                    null
                }
                bufferLabel.text = if (pct != null) "Buffering $pct%" else "Loading…"
            }
            val pausedNow = player.paused == true
            playBtn.text = if (pausedNow) "▶" else "⏸"
            centerPlay.visibility = if (pausedNow && started) View.VISIBLE else View.GONE
        } catch (t: Throwable) { /* mpv tearing down; next tick retries */ }
    }

    private fun setControls(show: Boolean) {
        controlsVisible = show
        val vis = if (show) View.VISIBLE else View.GONE
        topBar.visibility = vis
        bottomBar.visibility = vis
        if (show) scheduleHide()
    }

    private fun scheduleHide() {
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, 3500)
    }

    private fun flash(text: String, left: Boolean?) {
        flashLabel.text = text
        (flashLabel.layoutParams as FrameLayout.LayoutParams).gravity = when (left) {
            true -> Gravity.CENTER_VERTICAL or Gravity.START
            false -> Gravity.CENTER_VERTICAL or Gravity.END
            null -> Gravity.CENTER
        }
        if (left != null) {
            (flashLabel.layoutParams as FrameLayout.LayoutParams).apply {
                marginStart = dp(48)
                marginEnd = dp(48)
            }
        }
        flashLabel.visibility = View.VISIBLE
        main.postDelayed({ flashLabel.visibility = View.GONE }, 550)
        setControls(true)
    }

    private val pillHide = Runnable { if (::pill.isInitialized) pill.visibility = View.GONE }

    private fun showPill(icon: String, pct: Int) {
        pillIcon.text = icon
        pillPct.text = "$pct%"
        pillFill.layoutParams = FrameLayout.LayoutParams(
            (dp(110) * pct.coerceIn(0, 100) / 100).coerceAtLeast(dp(6)), dp(6)
        )
        pill.visibility = View.VISIBLE
        main.removeCallbacks(pillHide)
        main.postDelayed(pillHide, 900)
    }

    private fun showError(message: String) {
        spinner.visibility = View.GONE
        bufferLabel.text = ""
        val logs = synchronized(recentLog) { recentLog.takeLast(4).joinToString("\n") }
        errorPanel.text = if (logs.isBlank()) message else "$message\n\n$logs"
        errorPanel.visibility = View.VISIBLE
        setControls(true)
    }

    private fun fmt(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    // ---------- mpv config ----------

    private fun mpvConfigDir(): File = File(filesDir, "mpv")

    private fun prepareMpvConfigDir() {
        val dir = mpvConfigDir().apply { mkdirs() }
        for (conf in arrayOf("mpv.conf", "input.conf")) {
            val f = File(dir, conf)
            if (!f.exists()) f.writeText("")
        }
        for (name in arrayOf("cacert.pem", "subfont.ttf")) {
            val out = File(dir, name)
            val size = assets.open(name).use { it.available().toLong() }
            if (out.length() == size) continue
            assets.open(name).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    // ---------- MPVLib observers ----------

    override fun event(eventId: Int) {}

    override fun efEvent(err: String) {
        main.post { showError("Playback failed — mpv said:\n$err") }
    }

    override fun eventProperty(property: String) {}
    override fun eventProperty(property: String, value: Long) {}
    override fun eventProperty(property: String, value: Boolean) {}
    override fun eventProperty(property: String, value: String) {}
    override fun eventProperty(property: String, value: Double) {}

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (level <= MPVLib.mpvLogLevel.MPV_LOG_LEVEL_ERROR) {
            synchronized(recentLog) {
                recentLog.add("[$prefix] ${text.trim()}")
                while (recentLog.size > 12) recentLog.removeAt(0)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::player.isInitialized) {
            try {
                saveProgress()
                if (player.paused != true) player.paused = true
            } catch (t: Throwable) { /* already gone */ }
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (::player.isInitialized) {
            try {
                MPVLib.removeLogObserver(this)
                MPVLib.removeObserver(this)
                player.destroy()
            } catch (t: Throwable) { /* already gone */ }
        }
        super.onDestroy()
    }
}
