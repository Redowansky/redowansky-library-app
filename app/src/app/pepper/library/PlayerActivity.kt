package app.pepper.library

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView

/** Native fullscreen player (VideoView/MediaPlayer) streaming the direct mp4. */
class PlayerActivity : Activity() {

    private lateinit var video: VideoView

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

        video = VideoView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        setContentView(video)

        val controller = MediaController(this)
        controller.setAnchorView(video)
        video.setMediaController(controller)
        video.setOnErrorListener { _, what, extra ->
            Toast.makeText(
                this, "Playback error ($what/$extra) — check connection",
                Toast.LENGTH_LONG
            ).show()
            true
        }
        video.setOnPreparedListener { mp ->
            mp.setOnVideoSizeChangedListener { _, _, _ -> }
            video.start()
        }
        if (title.isNotBlank()) this.title = title
        video.setVideoURI(android.net.Uri.parse(url))
        video.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        if (::video.isInitialized && video.isPlaying) video.pause()
    }

    override fun onDestroy() {
        if (::video.isInitialized) video.stopPlayback()
        super.onDestroy()
    }
}
