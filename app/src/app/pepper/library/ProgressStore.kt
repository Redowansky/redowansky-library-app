package app.pepper.library

import android.content.Context
import org.json.JSONObject

/**
 * On-device watch progress: which title, which source URL, how far in.
 * Saved continuously by the player, so a dead battery mid-movie loses
 * at most a couple of seconds. Powers the Continue Watching banner and
 * the Recently Played tab. (Per-device for now; a Google sign-in sync
 * can layer on top of this same store later.)
 */
object ProgressStore {

    data class Entry(
        val id: String,
        val title: String,
        val poster: String?,
        val type: String,
        val url: String,
        val pos: Double,
        val dur: Double,
        val ts: Long,
        val done: Boolean
    )

    private const val PREFS = "watch_progress"
    private const val KEY = "entries"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readAll(ctx: Context): JSONObject {
        val raw = prefs(ctx).getString(KEY, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    fun get(ctx: Context, id: String): Entry? {
        val o = readAll(ctx).optJSONObject(id) ?: return null
        return Entry(
            id = o.optString("id", id),
            title = o.optString("title"),
            poster = o.optString("poster").ifBlank { null },
            type = o.optString("type", "movie"),
            url = o.optString("url"),
            pos = o.optDouble("pos", 0.0),
            dur = o.optDouble("dur", 0.0),
            ts = o.optLong("ts", 0),
            done = o.optBoolean("done", false)
        )
    }

    fun all(ctx: Context): List<Entry> {
        val all = readAll(ctx)
        val out = mutableListOf<Entry>()
        val keys = all.keys()
        while (keys.hasNext()) {
            get(ctx, keys.next())?.let { out.add(it) }
        }
        return out.sortedByDescending { it.ts }
    }

    fun save(ctx: Context, entry: Entry) {
        val all = readAll(ctx)
        val o = JSONObject()
        o.put("id", entry.id)
        o.put("title", entry.title)
        o.put("poster", entry.poster ?: "")
        o.put("type", entry.type)
        o.put("url", entry.url)
        o.put("pos", entry.pos)
        o.put("dur", entry.dur)
        o.put("ts", entry.ts)
        o.put("done", entry.done)
        all.put(entry.id, o)
        prefs(ctx).edit().putString(KEY, all.toString()).apply()
    }

    fun remove(ctx: Context, id: String) {
        val all = readAll(ctx)
        all.remove(id)
        prefs(ctx).edit().putString(KEY, all.toString()).apply()
    }

    /** Most recent title that was started but not finished. */
    fun latestUnfinished(ctx: Context): Entry? =
        all(ctx).firstOrNull { !it.done && it.pos > 10 && it.url.isNotBlank() }
}
