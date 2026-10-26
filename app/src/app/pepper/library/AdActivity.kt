package app.pepper.library

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mndiad.android.sdk.banners.BannerCallbacks
import com.mndiad.android.sdk.banners.BannerPosition
import com.mndiad.android.sdk.banners.BannerSize
import com.mndiad.android.sdk.banners.BannerView
import app.pepper.library.Ui.dp

/**
 * Fullscreen ad shown before a download starts — the app equivalent of
 * a video/interstitial ad: dark screen, big centered ad, and a close (✕)
 * button in the very top corner that appears after a short countdown.
 * Closing it starts the download (RESULT_OK); backing out cancels.
 *
 * Ad source: a Mondiad 300x250 BannerView. If Mondiad has no fill
 * (e.g. no real app zone yet), a house card for the library itself is
 * shown in the same slot so the flow still works end-to-end.
 */
class AdActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var closeBtn: TextView
    private lateinit var countdownLabel: TextView
    private lateinit var adSlot: FrameLayout
    private var canClose = false
    private var adSettled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ads.init()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(10, 10, 10)) }

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            setPadding(dp(20), 0, dp(20), 0)
        }
        center.addView(
            Ui.text(this, "ADVERTISEMENT", 11f, Ui.DIM, gravity = Gravity.CENTER)
        )
        center.addView(Ui.vspace(this, 10))

        adSlot = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(300), dp(250))
            setBackgroundColor(Color.rgb(20, 20, 20))
        }
        center.addView(adSlot)
        center.addView(Ui.vspace(this, 14))
        center.addView(
            Ui.text(
                this, "Your download starts right after this ad.",
                13f, Ui.DIM, gravity = Gravity.CENTER
            )
        )
        root.addView(center)

        countdownLabel = Ui.text(this, "3", 13f, Ui.TEXT, gravity = Gravity.CENTER).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
        }
        root.addView(countdownLabel)

        closeBtn = Ui.text(this, "✕", 20f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
            visibility = View.GONE
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
            setOnClickListener {
                if (canClose) {
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
        root.addView(closeBtn)

        setContentView(root)
        loadAd()
        startCountdown()
    }

    private fun loadAd() {
        val banner = BannerView(this).apply {
            zoneId = Ads.FULLSCREEN_ZONE_ID
            size = BannerSize.MEDIUM_RECTANGLE
            position = BannerPosition.VIEW
            bannerCallbacks = object : BannerCallbacks {
                override fun onAdShown() {
                    adSettled = true
                }

                override fun onAdFailedToLoad(error: String) {
                    // DIAGNOSTIC (0.6.10 only): show the SDK error instead
                    // of the house card, so it can be read off a screenshot.
                    showErrorAd(error)
                }
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        adSlot.addView(banner)
        // No fill and no failure callback within 6s -> timeout message.
        main.postDelayed({ if (!adSettled) showErrorAd("TIMEOUT: no callback in 6s (no response / no fill)") }, 6000)
    }

    private fun showErrorAd(error: String) {
        if (adSettled) return
        adSettled = true
        main.post {
            adSlot.removeAllViews()
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Ui.PANEL)
                setPadding(dp(18), dp(18), dp(18), dp(18))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                addView(Ui.text(this@AdActivity, "⚠️ Mondiad ad failed", 16f, Ui.GREEN, bold = true, gravity = Gravity.CENTER))
                addView(Ui.vspace(this@AdActivity, 8))
                addView(Ui.text(this@AdActivity, "Zone ${Ads.FULLSCREEN_ZONE_ID}", 13f, Ui.TEXT, gravity = Gravity.CENTER))
                addView(Ui.vspace(this@AdActivity, 6))
                addView(Ui.text(this@AdActivity, error, 12f, Ui.TEXT, gravity = Gravity.CENTER))
            }
            adSlot.addView(card)
        }
    }

    private fun showHouseAd() {
        if (adSettled) return
        adSettled = true
        main.post {
            adSlot.removeAllViews()
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Ui.PANEL)
                setPadding(dp(18), dp(18), dp(18), dp(18))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                addView(Ui.text(this@AdActivity, "🎬", 40f, Ui.TEXT, gravity = Gravity.CENTER))
                addView(Ui.vspace(this@AdActivity, 8))
                addView(
                    Ui.text(
                        this@AdActivity, "Redowansky's Library",
                        18f, Ui.GREEN, bold = true, gravity = Gravity.CENTER
                    )
                )
                addView(Ui.vspace(this@AdActivity, 6))
                addView(
                    Ui.text(
                        this@AdActivity,
                        "Movies & video songs, streaming free.\nredowansky.bond",
                        13f, Ui.TEXT, gravity = Gravity.CENTER
                    )
                )
            }
            adSlot.addView(card)
        }
    }

    private fun startCountdown() {
        var left = 3
        val tick = object : Runnable {
            override fun run() {
                if (left > 0) {
                    countdownLabel.text = left.toString()
                    left--
                    main.postDelayed(this, 1000)
                } else {
                    countdownLabel.visibility = View.GONE
                    closeBtn.visibility = View.VISIBLE
                    canClose = true
                }
            }
        }
        main.post(tick)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Backing out of the ad cancels the download.
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
