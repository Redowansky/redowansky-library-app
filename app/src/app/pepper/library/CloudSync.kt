package app.pepper.library

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cross-device watch-progress sync with the Library Worker.
 *
 * Identity = the Google account signed into the app. The app asks for an
 * ID token for the Web client (Auth.WEB_CLIENT_ID); the Worker verifies
 * it against Google's tokeninfo endpoint and keys progress by the
 * Google subject id, never by email. No token is ever stored: a fresh
 * (or cached) ID token is fetched per sync and sent only as a Bearer
 * header to redowansky.bond.
 *
 * Sync rule (both directions): last-write-wins by updatedAt. Pull first,
 * merge cloud-newer entries into the local store, then push local
 * entries that are newer than (or absent from) the cloud. Uploads from
 * the player are throttled to at most once per 30s per title, plus a
 * forced upload on pause.
 */
object CloudSync {
    private const val API = "https://redowansky.bond/api/progress"
    private const val THROTTLE_MS = 30_000L
    private val lastUpload = mutableMapOf<String, Long>()

    private fun idToken(ctx: Context): String? {
        return try {
            Auth.lastAccount(ctx)?.idToken
        } catch (e: Exception) {
            null
        }
    }

    private fun post(token: String, body: JSONObject): Int {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "RedowanskyLibraryApp/0.6.8")
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
        val code = conn.responseCode
        try { conn.inputStream.use { it.readBytes() } } catch (e: Exception) {}
        try { conn.errorStream?.use { it.readBytes() } } catch (e: Exception) {}
        return code
    }

    private fun get(token: String): JSONObject? {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("User-Agent", "RedowanskyLibraryApp/0.6.8")
        if (conn.responseCode != 200) return null
        return try {
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            null
        }
    }

    /** Upload one entry now, in a background thread. Best-effort, never throws. */
    fun uploadEntry(ctx: Context, entry: ProgressStore.Entry, force: Boolean = false) {
        if (entry.id.isBlank() || entry.url.isBlank()) return
        val now = System.currentTimeMillis()
        if (!force) {
            val last = lastUpload[entry.id] ?: 0L
            if (now - last < THROTTLE_MS) return
        }
        lastUpload[entry.id] = now
        val appCtx = ctx.applicationContext
        Thread {
            try {
                val token = idToken(appCtx) ?: return@Thread
                val body = JSONObject()
                body.put("id", entry.id)
                body.put("title", entry.title)
                body.put("poster", entry.poster ?: "")
                body.put("type", entry.type)
                body.put("url", entry.url)
                body.put("viewUrl", "/view/ext/" + entry.id)
                body.put("pos", entry.pos)
                body.put("dur", entry.dur)
                body.put("updatedAt", entry.ts)
                post(token, body)
            } catch (e: Exception) {
                // Sync is best-effort; local progress always survives.
            }
        }.start()
    }

    /**
     * Full sync: pull cloud, merge cloud-newer into local, push
     * local-newer to cloud. Runs on a background thread; [onDone] is
     * called on that same thread when finished (callers post to UI).
     */
    fun syncAll(ctx: Context, onDone: (() -> Unit)? = null) {
        val appCtx = ctx.applicationContext
        Thread {
            try {
                val token = idToken(appCtx) ?: return@Thread
                val remote = get(token)
                val cloud = mutableMapOf<String, JSONObject>()
                if (remote != null) {
                    val arr = remote.optJSONArray("entries")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            cloud[o.optString("id")] = o
                        }
                    }
                }
                // Merge: cloud newer (or local missing, and cloud has a playable url) wins locally.
                for ((id, o) in cloud) {
                    val local = ProgressStore.get(appCtx, id)
                    val cloudTs = o.optLong("updatedAt", 0)
                    if (local == null) {
                        val url = o.optString("url")
                        if (url.isBlank()) continue
                        ProgressStore.save(
                            appCtx,
                            ProgressStore.Entry(
                                id = id,
                                title = o.optString("title"),
                                poster = o.optString("poster").ifBlank { null },
                                type = o.optString("type", "movie"),
                                url = url,
                                pos = o.optDouble("pos", 0.0),
                                dur = o.optDouble("dur", 0.0),
                                ts = cloudTs,
                                done = o.optBoolean("done", false)
                            )
                        )
                    } else if (cloudTs > local.ts) {
                        ProgressStore.save(
                            appCtx,
                            local.copy(
                                title = o.optString("title", local.title),
                                poster = o.optString("poster").ifBlank { local.poster },
                                url = o.optString("url", local.url),
                                pos = o.optDouble("pos", local.pos),
                                dur = o.optDouble("dur", local.dur),
                                ts = cloudTs,
                                done = o.optBoolean("done", local.done)
                            )
                        )
                    }
                }
                // Push: local entries that are newer than the cloud (or absent there).
                val toPush = JSONArray()
                for (e in ProgressStore.all(appCtx)) {
                    if (e.url.isBlank()) continue
                    val c = cloud[e.id]
                    if (c == null || e.ts > c.optLong("updatedAt", 0)) {
                        val o = JSONObject()
                        o.put("id", e.id)
                        o.put("title", e.title)
                        o.put("poster", e.poster ?: "")
                        o.put("type", e.type)
                        o.put("url", e.url)
                        o.put("viewUrl", "/view/ext/" + e.id)
                        o.put("pos", e.pos)
                        o.put("dur", e.dur)
                        o.put("updatedAt", e.ts)
                        toPush.put(o)
                    }
                }
                if (toPush.length() > 0) {
                    val body = JSONObject()
                    body.put("entries", toPush)
                    post(token, body)
                }
            } catch (e: Exception) {
                // Best-effort only.
            } finally {
                try { onDone?.invoke() } catch (e: Exception) {}
            }
        }.start()
    }

}
