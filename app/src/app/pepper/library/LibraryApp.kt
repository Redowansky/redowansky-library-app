package app.pepper.library

import android.app.Application
import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Installs a crash trap: any uncaught exception (including linkage
 * errors from the hand-integrated libraries) is written to prefs
 * before the process dies, and MainActivity shows the trace on the
 * next launch. Built after the mpv error-panel lesson: instrument
 * first, theorize second.
 */
class LibraryApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashTrap.install(this)
    }
}

object CrashTrap {

    private const val PREFS = "crash_trap"
    private const val KEY = "last_trace"

    fun install(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val trace = "Thread: ${thread.name}\n$sw"
                prefs.edit().putString(KEY, trace).commit()
            } catch (t: Throwable) {
                // Trapping must never make a crash worse.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun peek(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
