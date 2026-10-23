package app.pepper.library

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * LockShield-style in-app updates, minus the Device Owner part:
 * a tiny version.json hosted on R2 next to the APK. The home screen
 * checks it on launch; when a newer build exists a banner offers
 * download + install (the system installer still asks the user once,
 * because this app is not a device owner).
 */
object Updater {

    const val VERSION_URL =
        "https://pub-89e47025e7844f1eaf672a00cfdbba12.r2.dev/app/version.json"

    data class Info(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val notes: String
    )

    fun currentCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        return if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode
        else pi.versionCode.toLong()
    }

    /** Returns update info only when the hosted build is newer. Blocking: call off the UI thread. */
    fun check(ctx: Context): Info? {
        val conn = URL(VERSION_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "RedowanskyLibraryApp")
        // Never serve a stale version file from any cache.
        conn.setRequestProperty("Cache-Control", "no-cache")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val o = JSONObject(text)
        val remote = o.optLong("versionCode", 0)
        if (remote <= currentCode(ctx)) return null
        val apk = o.optString("apk")
        if (apk.isBlank()) return null
        return Info(remote, o.optString("versionName", ""), apk, o.optString("notes", ""))
    }
}
