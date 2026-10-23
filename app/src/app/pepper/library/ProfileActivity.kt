package app.pepper.library

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.pepper.library.Ui.dp
import java.io.File
import java.util.concurrent.Executors

/**
 * Profile tab: Google account (avatar, name, email, sign in/out),
 * app version, and a manual "check for updates" that runs the same
 * Updater + UpdateInstaller flow as the home banner.
 */
class ProfileActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var avatarImg: ImageView
    private lateinit var avatarInitial: TextView
    private lateinit var nameTv: TextView
    private lateinit var emailTv: TextView
    private lateinit var accountBtnHolder: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PosterLoader.init(File(cacheDir, "posters").apply { mkdirs() })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val back = Ui.text(this, "‹", 30f, Ui.TEXT, gravity = Gravity.CENTER).apply {
            setPadding(dp(2), 0, dp(14), dp(6))
            setOnClickListener { finish() }
        }
        header.addView(back)
        header.addView(Ui.text(this, "Profile", 22f, Ui.TEXT, bold = true))
        root.addView(header)
        root.addView(Ui.vspace(this, 24))

        // Avatar: photo when signed in, initial letter otherwise.
        val avatarBox = FrameLayout(this).apply {
            background = Ui.rounded(this@ProfileActivity, Ui.CARD, 42)
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(84)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        avatarInitial = Ui.text(this, "👤", 34f, Ui.TEXT, gravity = Gravity.CENTER).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        avatarBox.addView(avatarInitial)
        avatarImg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        avatarBox.addView(avatarImg)
        root.addView(avatarBox)
        root.addView(Ui.vspace(this, 14))

        nameTv = Ui.text(this, "", 19f, Ui.TEXT, bold = true, gravity = Gravity.CENTER)
        root.addView(nameTv)
        emailTv = Ui.text(this, "", 13f, Ui.DIM, gravity = Gravity.CENTER)
        root.addView(emailTv)
        root.addView(Ui.vspace(this, 18))

        accountBtnHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(accountBtnHolder)

        root.addView(Ui.vspace(this, 28))
        root.addView(
            View(this).apply {
                setBackgroundColor(Ui.CARD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                )
            }
        )
        root.addView(Ui.vspace(this, 18))

        val pi = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        val vname = pi.versionName ?: "?"
        root.addView(Ui.text(this, "Redowansky Library v$vname", 14f, Ui.TEXT, bold = true))
        root.addView(Ui.vspace(this, 4))
        root.addView(
            Ui.text(
                this,
                "Your movies and songs from redowansky.bond, in a native app.",
                12f, Ui.DIM
            )
        )
        root.addView(Ui.vspace(this, 14))
        root.addView(
            Ui.ghostButton(this, "🔄  Check for updates") { manualUpdateCheck() }
        )

        setContentView(root)
        refreshAccount()
    }

    override fun onResume() {
        super.onResume()
        refreshAccount()
    }

    private fun refreshAccount() {
        val acct = try {
            Auth.lastAccount(this)
        } catch (t: Throwable) {
            null
        }
        accountBtnHolder.removeAllViews()
        if (acct != null) {
            nameTv.text = acct.displayName ?: "Google account"
            emailTv.text = acct.email ?: ""
            val photo = acct.photoUrl
            if (photo != null) {
                avatarInitial.visibility = View.GONE
                avatarImg.visibility = View.VISIBLE
                PosterLoader.loadCircular(photo.toString(), avatarImg)
            } else {
                avatarImg.visibility = View.GONE
                avatarInitial.visibility = View.VISIBLE
                avatarInitial.text =
                    acct.displayName?.firstOrNull()?.uppercaseChar()?.toString() ?: "👤"
            }
            accountBtnHolder.addView(
                Ui.ghostButton(this, "Sign out") {
                    Auth.signOut(this) {
                        refreshAccount()
                        Toast.makeText(this, "Signed out", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } else {
            nameTv.text = "Not signed in"
            emailTv.text = "Sign in to link your Google account"
            avatarImg.visibility = View.GONE
            avatarInitial.visibility = View.VISIBLE
            avatarInitial.text = "👤"
            accountBtnHolder.addView(
                Ui.greenButton(this, "Sign in with Google") {
                    try {
                        @Suppress("DEPRECATION")
                        startActivityForResult(Auth.signInIntent(this), Auth.RC_SIGN_IN)
                    } catch (t: Throwable) {
                        Toast.makeText(
                            this,
                            "Sign-in start failed: ${t.javaClass.simpleName}: ${t.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != Auth.RC_SIGN_IN) return
        try {
            val acct = Auth.accountFromResult(data)
            refreshAccount()
            Toast.makeText(
                this, "Signed in as ${acct?.email ?: "Google account"}", Toast.LENGTH_SHORT
            ).show()
        } catch (e: com.google.android.gms.common.api.ApiException) {
            if (e.statusCode != 12501) {
                Toast.makeText(
                    this, "Sign-in failed (code ${e.statusCode})", Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun manualUpdateCheck() {
        Toast.makeText(this, "Checking…", Toast.LENGTH_SHORT).show()
        io.execute {
            val info = try {
                Updater.check(this)
            } catch (e: Exception) {
                null
            }
            main.post {
                if (info == null) {
                    Toast.makeText(
                        this, "You're on the latest version ✅", Toast.LENGTH_SHORT
                    ).show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Update available: v${info.versionName}")
                        .setMessage(info.notes.ifBlank { "A newer build is ready." })
                        .setNegativeButton("Later", null)
                        .setPositiveButton("Download & install") { _, _ ->
                            UpdateInstaller.start(this, info)
                        }
                        .show()
                }
            }
        }
    }
}
