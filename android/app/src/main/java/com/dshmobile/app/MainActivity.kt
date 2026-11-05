package com.dshmobile.app

import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.dshmobile.app.data.SessionLink
import com.dshmobile.app.ui.DshMobileApp
import com.dshmobile.app.ui.theme.DshMobileTheme

class MainActivity : ComponentActivity() {
    private val pendingSessionId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CookieManager.getInstance().setAcceptCookie(true)
        pendingSessionId.value = sessionIdFrom(intent)

        setContent {
            DshMobileTheme {
                DshMobileApp(
                    openSessionId = pendingSessionId.value,
                    onOpenSessionHandled = { pendingSessionId.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingSessionId.value = sessionIdFrom(intent)
    }

    private fun sessionIdFrom(intent: Intent?): String? {
        if (intent == null) return null
        SessionLink.normalize(intent.getStringExtra(EXTRA_SESSION_ID))?.let { return it }
        return SessionLink.fromDeepLink(intent.dataString)
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }
}
