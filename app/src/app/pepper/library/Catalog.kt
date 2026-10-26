package app.pepper.library

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.mndiad.android.sdk.Mnd
import com.mndiad.android.sdk.banners.BannerCallbacks
import com.mndiad.android.sdk.banners.BannerPosition
import com.mndiad.android.sdk.banners.BannerSize
import com.mndiad.android.sdk.banners.BannerView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Catalog {
    const val API_URL = "https://redowansky.bond/api/movies"

    fun fetch(): List<Movie> {
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "RedowanskyLibraryApp/0.2")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val arr = JSONObject(text).optJSONArray("movies")
            ?: throw IllegalStateException("Bad catalog response")
        val out = mutableListOf<Movie>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(Movie.fromJson(it)) }
        }
        return out
    }
}

object Ads {
    // LIVE 2026-10-26: app 585 accepted, Banner zone 83566 "Library Banner"
    // (Active, Mainstream; Mondiad's Banner form offers no size selector,
    // so the same zone serves both the 320x50 strip and the 300x250 slot).
    // Push ads use the separate zone 83417 in LibraryApp.
    const val DEMO_MODE = false
    const val ZONE_ID = "83566" // home/detail 320x50 banner zone
    const val FULLSCREEN_ZONE_ID = "83566" // download ad 300x250 zone (same Banner zone)

    fun init() {
        Mnd.banners.demoMode = DEMO_MODE
        Mnd.banners.logLevel = android.util.Log.INFO
    }

    fun banner(context: Context): BannerView {
        return BannerView(context).apply {
            zoneId = ZONE_ID
            size = BannerSize.SMARTPHONE_BANNER
            position = BannerPosition.VIEW
            bannerCallbacks = object : BannerCallbacks {
                override fun onAdShown() {
                    android.util.Log.i("LibraryApp", "Mondiad banner shown (demo=$DEMO_MODE)")
                }

                override fun onAdFailedToLoad(error: String) {
                    android.util.Log.w("LibraryApp", "Mondiad banner failed: $error")
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (50 * context.resources.displayMetrics.density).toInt()
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
            setBackgroundColor(Color.BLACK)
        }
    }
}
