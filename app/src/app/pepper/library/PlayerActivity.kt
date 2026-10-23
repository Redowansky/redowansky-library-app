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
import android.widget.Toast
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVView
import app.pepper.library.Ui.dp
import java.io.File

/**
 * Fullscreen player on the mpv engine — the same lib Aniyomi uses
 * (aniyomi-mpv-lib). HW decode with software fallback, so HEVC/hvc1,
 * AV1 and VP9 titles that the stock VideoView can't render play here.
 *
 * Controls: tap toggles the bar, double-tap left/right seeks ∓10s,
 * seekbar scrubbing, play/pause. Polls mpv properties 2x/sec.
 */
class PlayerActivity : Activity(), MPVLib.EventObserver {

    private lateinit var player: MPVView
    private lateinit var controls: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var titleLabel: TextView
    private lateinit var playBtn: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var seekFlash: TextView

    private val main = Handler(Looper.getMainLooper())
    private var controlsVisible = true
    private var userScrubbing = false
    private var finished = false

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

        copyMpvAssets()

        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }

        player = LayoutInflater.from(this)
            .inflate(R.layout.player_view, root, false) as MPVView
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

        controls = buildControls(title)
        root.addView(controls)

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                setControls(!controlsVisible)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val pos = (player.timePos ?: 0.0)
                if (e.x < resources.displayMetrics.widthPixels / 2) {
                    player.timePos = (pos - 10).coerceAtLeast(0.0)
                    flash("⏪ 10s")
                } else {
                    player.timePos = pos + 10
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

        player.initialize(filesDir.path, cacheDir.path)
        player.addObserver(this)
        player.playFile(url)
        main.post(tick)
        scheduleHide()
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
        titleLabel = Ui.text(this, title, 14f, Ui.TEXT, bold = true).apply { maxLines = 1 }
        bar.addView(titleLabel)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        playBtn = Ui.text(this, "⏸", 24f, Ui.TEXT, gravity = Gravity.CENTER).apply {
            setPadding(dp(6), dp(2), dp(12), dp(2))
            setOnClickListener {
                player.cyclePause()
                playBtn.text = if (player.paused == true) "▶" else "⏸"
            }
        }
        row.addView(playBtn)

        seekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = player.duration ?: 0
                        timeLabel.text = "${fmt(progress)} / ${fmt(dur)}"
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    userScrubbing = true
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    userScrubbing = false
                    player.timePos = sb?.progress?.toDouble() ?: 0.0
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
        if (finished || !::player.isInitialized) return
        val dur = player.duration
        val pos = player.timePos
        if (dur != null && dur > 0) {
            spinner.visibility = View.GONE
            seekBar.max = dur
            if (!userScrubbing && pos != null) {
                seekBar.progress = pos.toInt()
                timeLabel.text = "${fmt(pos.toInt())} / ${fmt(dur)}"
            }
            playBtn.text = if (player.paused == true) "▶" else "⏸"
        }
        if (MPVLib.getPropertyBoolean("eof-reached") == true) {
            finished = true
            Toast.makeText(this, "Finished", Toast.LENGTH_SHORT).show()
            finish()
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

    private fun fmt(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Same asset copy Aniyomi does (cacert.pem for https, subfont for subs). */
    private fun copyMpvAssets() {
        for (name in arrayOf("cacert.pem", "subfont.ttf")) {
            try {
                val out = File(filesDir, name)
                val size = assets.open(name).use { it.available().toLong() }
                if (out.length() == size) continue
                assets.open(name).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                android.util.Log.w("LibraryApp", "mpv asset copy failed: $name", e)
            }
        }
    }

    // MPVLib.EventObserver — most UI sync is done by polling; we only
    // need the load-failure signal here.
    override fun event(eventId: Int) {
        // MPV_EVENT_END_FILE = 7; reasons are delivered via efEvent.
    }

    override fun efEvent(err: String) {
        main.post {
            if (!finished) {
                Toast.makeText(this, "Playback failed: $err", Toast.LENGTH_LONG).show()
                spinner.visibility = View.GONE
            }
        }
    }

    override fun eventProperty(property: String) {}
    override fun eventProperty(property: String, value: Long) {}
    override fun eventProperty(property: String, value: Boolean) {}
    override fun eventProperty(property: String, value: String) {}
    override fun eventProperty(property: String, value: Double) {}

    override fun onPause() {
        super.onPause()
        if (::player.isInitialized && player.paused != true) player.paused = true
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (::player.isInitialized) {
            player.removeObserver(this)
            player.destroy()
        }
        super.onDestroy()
    }
}
