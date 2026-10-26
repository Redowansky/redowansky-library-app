package app.pepper.library

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPVLib

/**
 * Our player view, modelled directly on Aniyomi's AniyomiMPVView
 * (Aniyomi subclasses BaseMPVView — it does NOT use the stock MPVView).
 *
 * The load-bearing options the stock view misses:
 *  - tls-ca-file pointing at the bundled cacert.pem (copied into the
 *    mpv config dir by PlayerActivity) — without it mpv's ffmpeg has
 *    no CA bundle on Android and https streams fail instantly.
 *  - config dir is a dedicated filesDir/mpv folder (mpv.conf lives there).
 *  - keep-open = yes so EOF doesn't tear the player down by itself.
 */
class LibraryMPVView(context: Context, attrs: AttributeSet) : BaseMPVView(context, attrs) {

    val durationSec: Int?
        get() = MPVLib.getPropertyInt("duration")

    var timePosSec: Double?
        get() = MPVLib.getPropertyDouble("time-pos/full")
        set(v) {
            if (v != null) MPVLib.setPropertyDouble("time-pos", v)
        }

    var paused: Boolean?
        get() = MPVLib.getPropertyBoolean("pause")
        set(v) {
            if (v != null) MPVLib.setPropertyBoolean("pause", v)
        }

    fun cyclePause() = MPVLib.command(arrayOf("cycle", "pause"))

    override fun initOptions(vo: String) {
        setVo(vo)
        MPVLib.setOptionString("profile", "fast")
        MPVLib.setOptionString("hwdec", "auto")
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        MPVLib.setOptionString("ytdl", "no")
        MPVLib.setOptionString("tls-verify", "yes")
        MPVLib.setOptionString(
            "tls-ca-file", "${context.filesDir.path}/mpv/cacert.pem"
        )
        MPVLib.setOptionString("keep-open", "yes")
        MPVLib.setOptionString("input-default-bindings", "yes")
        val cacheMegs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) 64 else 32
        MPVLib.setOptionString("demuxer-max-bytes", "${cacheMegs * 1024 * 1024}")
        MPVLib.setOptionString("demuxer-max-back-bytes", "${cacheMegs * 1024 * 1024}")
        MPVLib.setOptionString("vd-lavc-film-grain", "cpu")
    }

    override fun postInitOptions() {
        MPVLib.setOptionString("save-position-on-quit", "no")
    }

    override fun observeProperties() {
        val props = arrayOf(
            "time-pos" to MPVLib.mpvFormat.MPV_FORMAT_INT64,
            "duration" to MPVLib.mpvFormat.MPV_FORMAT_INT64,
            "pause" to MPVLib.mpvFormat.MPV_FORMAT_FLAG,
            "eof-reached" to MPVLib.mpvFormat.MPV_FORMAT_FLAG,
            "hwdec-current" to MPVLib.mpvFormat.MPV_FORMAT_STRING
        )
        for ((name, format) in props) MPVLib.observeProperty(name, format)
    }
}
