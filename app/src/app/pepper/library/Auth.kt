package app.pepper.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

/**
 * Google Sign-In (classic GoogleSignIn API from play-services-auth).
 * Console project: redowansky-library. The Android client is matched by
 * package + signing SHA-1 in the console; the WEB client id below is
 * what the app asks ID tokens for.
 *
 * Signing in links the Google identity on this device. Watch progress
 * itself lives in ProgressStore (on-device); sync hooks can read
 * account?.id / fresh ID tokens from here later.
 */
object Auth {

    const val WEB_CLIENT_ID =
        "401417620222-de0ve4jhi8vcqedihpo1n3gpsi7md4hi.apps.googleusercontent.com"

    const val RC_SIGN_IN = 9001

    private fun options(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(WEB_CLIENT_ID)
            .requestEmail()
            .requestProfile()
            .build()

    fun client(ctx: Context): GoogleSignInClient = GoogleSignIn.getClient(ctx, options())

    fun lastAccount(ctx: Context): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(ctx)

    fun signInIntent(ctx: Context): Intent = client(ctx).signInIntent

    /** Parse a sign-in activity result; throws ApiException on failure/cancel. */
    fun accountFromResult(data: Intent?): GoogleSignInAccount? {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        return task.getResult(ApiException::class.java)
    }

    fun signOut(activity: Activity, done: () -> Unit) {
        client(activity).signOut().addOnCompleteListener { done() }
    }
}
