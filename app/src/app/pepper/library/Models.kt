package app.pepper.library

import org.json.JSONObject

data class Source(val label: String, val url: String)

data class Movie(
    val id: String,
    val title: String,
    val year: String?,
    val overview: String?,
    val poster: String?,
    val backdrop: String?,
    val vote: String?,
    val genres: List<String>,
    val type: String,
    val sources: List<Source>
) {
    fun posterUrl(width: String = "w342"): String? = resolveImage(poster, width)
    fun backdropUrl(): String? = resolveImage(backdrop, "w780")

    private fun resolveImage(path: String?, width: String): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("http")) path
        else "https://image.tmdb.org/t/p/$width$path"
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("title", title)
        o.put("year", year ?: JSONObject.NULL)
        o.put("overview", overview ?: JSONObject.NULL)
        o.put("poster", poster ?: JSONObject.NULL)
        o.put("backdrop", backdrop ?: JSONObject.NULL)
        o.put("vote", vote ?: JSONObject.NULL)
        o.put("genres", org.json.JSONArray(genres))
        o.put("type", type)
        val srcs = org.json.JSONArray()
        for (s in sources) {
            val so = JSONObject()
            so.put("label", s.label)
            so.put("url", s.url)
            srcs.put(so)
        }
        o.put("sources", srcs)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Movie {
            val sources = mutableListOf<Source>()
            val arr = o.optJSONArray("sources")
            if (arr != null && arr.length() > 0) {
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val u = s.optString("url")
                    if (u.isNotBlank()) sources.add(Source(s.optString("label", "Play"), u))
                }
            }
            val single = o.optString("url")
            if (sources.isEmpty() && single.isNotBlank()) {
                sources.add(Source("Play", single))
            }
            val genres = mutableListOf<String>()
            val g = o.optJSONArray("genres")
            if (g != null) for (i in 0 until g.length()) genres.add(g.optString(i))
            fun strOrNull(key: String): String? {
                val v = o.optString(key, "")
                return if (v.isBlank() || v == "null") null else v
            }
            return Movie(
                id = o.optString("id"),
                title = o.optString("title", "Untitled"),
                year = strOrNull("year"),
                overview = strOrNull("overview"),
                poster = strOrNull("poster"),
                backdrop = strOrNull("backdrop"),
                vote = strOrNull("vote"),
                genres = genres,
                type = o.optString("type", "movie"),
                sources = sources
            )
        }
    }
}
