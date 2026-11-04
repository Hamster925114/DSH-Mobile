package com.dshmobile.app

import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dshmobile.app.ui.DshMobileApp
import com.dshmobile.app.ui.theme.DshMobileTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CookieManager.getInstance().setAcceptCookie(true)

        setContent {
            DshMobileTheme {
                DshMobileApp()
            }
        }
    }
}
