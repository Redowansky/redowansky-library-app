package app.pepper.library

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.mndiad.android.sdk.Mnd

/** Forwards FCM tokens and messages to the Mondiad SDK (push ads, zone 83417). */
class MessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        try {
            Mnd.push.onNewToken(token)
        } catch (t: Throwable) {
            // Push plumbing must never break the app.
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        try {
            Mnd.push.onMessageReceived(message)
        } catch (t: Throwable) {
            // Push plumbing must never break the app.
        }
    }
}
