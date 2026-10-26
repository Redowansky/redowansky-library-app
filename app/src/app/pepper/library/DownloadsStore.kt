package app.pepper.library

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File

/**
 * In-app downloads (from v0.6.11): movies/songs downloaded through
 * DetailActivity go to the app's OWN private storage — NOT the public
 * Downloads folder — and are listed from here.
 *
 * Actual files live in getExternalFilesDir(DIRECTORY_DOWNLOADS), i.e.
 * Android/data/app.pepper.library/files/Download/. That is app-private
 * (no storage permission, invisible to file managers / gallery, and
 * removed if the app is uninstalled), which is the whole point: a
 * download belongs to the library, not to the phone's shared storage.
 * This store only records the metadata; a row is shown only while its
 * file still exists on disk.
 */
object DownloadsStore {

    data class Entry(
        val id: String,
        val title: String,
        val poster: String?,
        val type: String,
        val fileName: String,
        val sourceUrl: String,
        val dmId: Long,
        val ts: Long
    )

    private const val PREFS = "downloads"
    private const val KEY = "entries"

    /** The app-private Downloads folder (created on demand). */
    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "").apply { mkdirs() }

    fun file(ctx: Context, e: Entry): File = File(dir(ctx), e.fileName)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readAll(ctx: Context): JSONObject {
        val raw = prefs(ctx).getString(KEY, null) ?: return JSONObject()
        return try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
    }

    fun all(ctx: Context): List<Entry> {
        val all = readAll(ctx)
        val out = mutableListOf<Entry>()
        val keys = all.keys()
        while (keys.hasNext()) {
            val o = all.optJSONObject(keys.next()) ?: continue
            out.add(
                Entry(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    poster = o.optString("poster").ifBlank { null },
                    type = o.optString("type", "movie"),
                    fileName = o.optString("fileName"),
                    sourceUrl = o.optString("sourceUrl"),
                    dmId = o.optLong("dmId", -1),
                    ts = o.optLong("ts", 0)
                )
            )
        }
        return out.sortedByDescending { it.ts }
    }

    /** Only entries whose file is actually on disk (finished downloads). */
    fun ready(ctx: Context): List<Entry> = all(ctx).filter { file(ctx, it).exists() }

    fun save(ctx: Context, e: Entry) {
        val all = readAll(ctx)
        val o = JSONObject()
        o.put("id", e.id)
        o.put("title", e.title)
        o.put("poster", e.poster ?: "")
        o.put("type", e.type)
        o.put("fileName", e.fileName)
        o.put("sourceUrl", e.sourceUrl)
        o.put("dmId", e.dmId)
        o.put("ts", e.ts)
        all.put(e.id + "|" + e.fileName, o)
        prefs(ctx).edit().putString(KEY, all.toString()).apply()
    }

    fun remove(ctx: Context, e: Entry) {
        val all = readAll(ctx)
        all.remove(e.id + "|" + e.fileName)
        prefs(ctx).edit().putString(KEY, all.toString()).apply()
    }
}
