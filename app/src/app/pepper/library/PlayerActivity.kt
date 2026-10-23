package app.pepper.library

import android.app.Activity
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
import `is`.xyz.mpv.MPVLib
import app.pepper.library.Ui.dp
import java.io.File

/**
 * Fullscreen mpv player — integration modelled on Aniyomi's own player:
 * a BaseMPVView subclass (LibraryMPVView), a dedicated filesDir/mpv
 * config dir holding mpv.conf + cacert.pem + subfont.ttf, and
 * tls-ca-file set inside initOptions. Playback errors are shown
 * on-screen (mpv's own message) instead of crashing or bouncing back.
 *
 * Controls: tap toggles the bar, double-tap left/right seeks ∓10s.
 */
class PlayerActivity : Activity(), MPVLib.EventObserver, MPVLib.LogObserver {

    private lateinit var player: LibraryMPVView
    private lateinit var controls: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var playBtn: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var seekFlash: TextView
    private lateinit var errorPanel: TextView

    private val main = Handler(Looper.getMainLooper())
    private var controlsVisible = true
    private var userScrubbing = false
    private var started = false
    private val recentLog = mutableListOf<String>()

    private val hideRunnable = Runnable { setControls(false) }
    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            main.postDelayed(this, 500)
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

        spinner = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        root.addView(spinner)

        seekFlash = Ui.text(this, "", 22f, Ui.TEXT, bold = true, gravity = Gravity.CENTER).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
        }
        root.addView(seekFlash)

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

        controls = buildControls(title)
        root.addView(controls)

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
                    flash("⏪ 10s")
                } else {
                    player.timePosSec = pos + 10
                    flash("10s ⏩")
                }
                return true
            }
        })
        root.setOnTouchListener { _, ev ->
            gestures.onTouchEvent(ev)
            true
        }

        setContentView(root)

        try {
            prepareMpvConfigDir()
            player.initialize(mpvConfigDir().path, cacheDir.path, "warn")
            MPVLib.addLogObserver(this)
            MPVLib.addObserver(this)
            player.playFile(url)
            main.post(tick)
            scheduleHide()
        } catch (t: Throwable) {
            showError("Player failed to start:\n${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun mpvConfigDir(): File = File(filesDir, "mpv")

    /** Aniyomi layout: config dir holds mpv.conf/input.conf + assets. */
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

    private fun buildControls(title: String): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xB3000000.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(12))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }
        bar.addView(Ui.text(this, title, 14f, Ui.TEXT, bold = true).apply { maxLines = 1 })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        playBtn = Ui.text(this, "⏸", 24f, Ui.TEXT, gravity = Gravity.CENTER).apply {
            setPadding(dp(6), dp(2), dp(12), dp(2))
            setOnClickListener {
                player.cyclePause()
            }
        }
        row.addView(playBtn)

        seekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = player.durationSec ?: 0
                        timeLabel.text = "${fmt(progress)} / ${fmt(dur)}"
                    }
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
        row.addView(seekBar)

        timeLabel = Ui.text(this, "0:00 / 0:00", 12f, Ui.TEXT).apply {
            setPadding(dp(10), 0, 0, 0)
        }
        row.addView(timeLabel)
        bar.addView(row)
        return bar
    }

    private fun updateProgress() {
        if (!::player.isInitialized) return
        try {
            val dur = player.durationSec
            val pos = player.timePosSec
            if (dur != null && dur > 0) {
                started = true
                spinner.visibility = View.GONE
                seekBar.max = dur
                if (!userScrubbing && pos != null) {
                    seekBar.progress = pos.toInt()
                    timeLabel.text = "${fmt(pos.toInt())} / ${fmt(dur)}"
                }
            }
            playBtn.text = if (player.paused == true) "▶" else "⏸"
        } catch (t: Throwable) {
            // mpv is tearing down or not ready; the next tick retries.
        }
    }

    private fun setControls(show: Boolean) {
        controlsVisible = show
        controls.visibility = if (show) View.VISIBLE else View.GONE
        if (show) scheduleHide()
    }

    private fun scheduleHide() {
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, 3500)
    }

    private fun flash(text: String) {
        seekFlash.text = text
        seekFlash.visibility = View.VISIBLE
        main.postDelayed({ seekFlash.visibility = View.GONE }, 600)
        setControls(true)
    }

    private fun showError(message: String) {
        spinner.visibility = View.GONE
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

    // MPVLib.EventObserver
    override fun event(eventId: Int) {}

    override fun efEvent(err: String) {
        main.post { showError("Playback failed — mpv said:\n$err") }
    }

    override fun eventProperty(property: String) {}
    override fun eventProperty(property: String, value: Long) {}
    override fun eventProperty(property: String, value: Boolean) {}
    override fun eventProperty(property: String, value: String) {}
    override fun eventProperty(property: String, value: Double) {}

    // MPVLib.LogObserver
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
