package app.pepper.library

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.mndiad.android.sdk.Mnd
import com.mndiad.android.sdk.banners.BannerCallbacks
import com.mndiad.android.sdk.banners.BannerPosition
import com.mndiad.android.sdk.banners.BannerSize
import com.mndiad.android.sdk.banners.BannerView

/**
 * Redowansky Library — a thin WebView wrapper for https://redowansky.bond
 * with a Mondiad banner pinned to the bottom.
 *
 * PILOT BUILD: Mondiad demo mode is ON and the zone id is a placeholder.
 * Demo mode shows Mondiad test ads without a real zone, so the banner
 * integration can be device-tested before the app is registered.
 * When the real app banner zone exists, set DEMO_MODE = false and
 * ZONE_ID to the zone id from the Mondiad dashboard, then rebuild.
 */
class MainActivity : Activity() {

    companion object {
        private const val HOME_URL = "https://redowansky.bond"
        private const val DEMO_MODE = true
        private const val ZONE_ID = "0" // placeholder — replaced by the real Mondiad app banner zone id
        private const val TAG = "LibraryApp"
    }

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mondiad global config must be set before any banner loads.
        Mnd.banners.demoMode = DEMO_MODE
        Mnd.banners.logLevel = Log.INFO

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)
            )
        }
        root.addView(progress)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            CookieManager.getInstance().also { cm ->
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(this, true)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    this@MainActivity.progress.progress = newProgress
                    this@MainActivity.progress.visibility = if (newProgress >= 100)
                        android.view.View.GONE else android.view.View.VISIBLE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    return handleUri(uri)
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    if (url == null) return false
                    return handleUri(Uri.parse(url))
                }
            }
            // Downloads (movies etc.) go to the real browser, which can
            // save/stream them properly — WebView itself cannot.
            setDownloadListener { url, _, _, _, _ ->
                openExternal(Uri.parse(url))
            }
        }
        root.addView(webView)

        // Mondiad banner, 320x50, pinned under the WebView.
        val banner = BannerView(this).apply {
            zoneId = ZONE_ID
            size = BannerSize.SMARTPHONE_BANNER
            position = BannerPosition.VIEW
            bannerCallbacks = object : BannerCallbacks {
                override fun onAdShown() {
                    Log.i(TAG, "Mondiad banner shown (demo=$DEMO_MODE)")
                }

                override fun onAdFailedToLoad(error: String) {
                    Log.w(TAG, "Mondiad banner failed to load: $error")
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        root.addView(banner)

        setContentView(root)

        if (savedInstanceState == null) {
            webView.loadUrl(HOME_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    /** Keep library pages inside the app; send everything else out. */
    private fun handleUri(uri: Uri): Boolean {
        val host = uri.host ?: return false
        val scheme = uri.scheme ?: return false
        if ((scheme == "https" || scheme == "http") &&
            (host == "redowansky.bond" || host.endsWith(".redowansky.bond"))
        ) {
            return false // WebView loads it
        }
        openExternal(uri)
        return true
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Log.w(TAG, "No app can open $uri", e)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
