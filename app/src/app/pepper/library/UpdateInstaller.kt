package app.pepper.library

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

/**
 * Downloads an update APK (announced by Updater) and hands it to the
 * system installer. Shared by the home-screen banner and the Profile
 * screen's manual check. Not silent: the app is not a Device Owner,
 * so the installer asks the user once, and the very first run needs
 * the one-time "allow installs" toggle.
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
        val file = File(activity.getExternalFilesDir(null), "update.apk")
        file.delete()
        val req = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setTitle("Redowansky Library v${info.versionName}")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationInExternalFilesDir(activity, null, "update.apk")
        }
        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(req)
        Toast.makeText(activity, "Downloading update…", Toast.LENGTH_SHORT).show()
        io.execute {
            var done = false
            while (!done) {
                Thread.sleep(2000)
                val cursor = dm.query(DownloadManager.Query().setFilterById(id))
                if (cursor != null && cursor.moveToFirst()) {
                    val st = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    )
                    if (st == DownloadManager.STATUS_SUCCESSFUL) {
                        done = true
                        main.post { install(activity, file) }
                    } else if (st == DownloadManager.STATUS_FAILED) {
                        done = true
                        main.post {
                            Toast.makeText(
                                activity, "Update download failed", Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                cursor?.close()
            }
        }
    }

    private fun install(activity: Activity, file: File) {
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
    }
}
