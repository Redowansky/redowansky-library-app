package app.pepper.library

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import app.pepper.library.Ui.dp
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads an update APK (announced by Updater) and hands it to the
 * system installer. Shared by the home-screen banner and the Profile
 * screen's manual check. Not silent: the app is not a Device Owner,
 * so the installer asks the user once, and the very first run needs
 * the one-time "allow installs" toggle.
 *
 * v0.6.6 rewrite — why the old code is gone:
 * The old version handed the download to DownloadManager and then
 * polled it in a loop. On Redowan's phone that path failed silently:
 * the "Downloading update…" toast fired, but no DownloadManager
 * notification ever appeared in the shade, no traffic moved, and no
 * failure toast ever surfaced (the poll loop just kept waiting on a
 * download that never progressed). So this version downloads
 * directly with HttpURLConnection — the same network path the app
 * already uses for the catalog and the version check — shows a real
 * progress dialog, and on ANY failure reports the exact error in a
 * toast the user can screenshot, instead of going quiet.
 */
object UpdateInstaller {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    fun start(activity: Activity, info: Updater.Info) {
        if (android.os.Build.VERSION.SDK_INT >= 26 &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(
                activity,
                "Allow installs for this app once, then tap update again",
                Toast.LENGTH_LONG
            ).show()
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            return
        }

        // Progress dialog on the UI thread; download on a worker thread.
        val status = TextView(activity).apply {
            text = "Connecting…"
            setTextColor(Ui.TEXT)
            textSize = 13f
            setPadding(activity.dp(20), activity.dp(8), activity.dp(20), 0)
        }
        val bar = ProgressBar(
            activity, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            isIndeterminate = true
            setPadding(activity.dp(20), activity.dp(12), activity.dp(20), activity.dp(4))
        }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(bar)
        }
        val cancelled = AtomicBoolean(false)
        var connRef: HttpURLConnection? = null
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Downloading v${info.versionName}…")
            .setView(col)
            .setNegativeButton("Cancel") { _, _ ->
                cancelled.set(true)
                try { connRef?.disconnect() } catch (_: Exception) {}
            }
            .setCancelable(false)
            .create()
        dialog.show()

        io.execute {
            val dir = activity.getExternalFilesDir(null)
            val tmp = File(dir, "update.apk.tmp")
            val file = File(dir, "update.apk")
            try {
                val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
                connRef = conn
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "RedowanskyLibraryApp")
                conn.setRequestProperty("Cache-Control", "no-cache")
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP $code from server")
                }
                val total = conn.contentLengthLong
                if (total > 0) {
                    main.post {
                        bar.isIndeterminate = false
                        bar.max = 100
                    }
                }
                var downloaded = 0L
                var lastPosted = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled.get()) throw IOException("cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            downloaded += n
                            if (total > 0 && downloaded - lastPosted >= 256 * 1024) {
                                lastPosted = downloaded
                                val pct = (downloaded * 100 / total).toInt()
                                val mbDone = downloaded / 1024 / 1024
                                val mbTotal = total / 1024 / 1024
                                main.post {
                                    bar.progress = pct
                                    status.text = "$pct% · $mbDone / $mbTotal MB"
                                }
                            }
                        }
                    }
                }
                if (cancelled.get()) throw IOException("cancelled")
                if (total > 0 && downloaded != total) {
                    throw IOException("incomplete download ($downloaded / $total bytes)")
                }
                if (downloaded == 0L) throw IOException("empty download (0 bytes)")
                tmp.renameTo(file)
                main.post {
                    try { dialog.dismiss() } catch (_: Exception) {}
                    install(activity, file)
                }
            } catch (e: Exception) {
                try { tmp.delete() } catch (_: Exception) {}
                val msg = if (cancelled.get()) "Download cancelled"
                    else "Update download failed: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}"
                main.post {
                    try { dialog.dismiss() } catch (_: Exception) {}
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun install(activity: Activity, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", file
            )
            activity.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            Toast.makeText(
                activity,
                "Install failed to start: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
