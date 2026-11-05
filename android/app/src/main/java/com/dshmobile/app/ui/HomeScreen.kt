package com.dshmobile.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.dshmobile.app.service.DshEventService

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HomeScreen(
    baseUrl: String,
    profileLabel: String?,
    sessionCount: Int,
    version: String,
    notificationsEnabled: Boolean,
    onToggleNotifications: (Boolean) -> Unit,
    onManageConnections: () -> Unit,
    onUnpair: () -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val currentBaseUrl by rememberUpdatedState(baseUrl)

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        fileCallback?.onReceiveValue(uri?.let { arrayOf(it) })
        fileCallback = null
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) onToggleNotifications(true)
    }

    val webView = remember {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setDatabaseEnabled(true)
            settings.allowFileAccess = false
            // Keep content:// access so the file chooser can hand URIs to the SPA.
            settings.allowContentAccess = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.setSupportZoom(false)
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

            setWebViewClient(object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val url = request?.url ?: return false
                    val baseHost = runCatching { Uri.parse(currentBaseUrl).host }.getOrNull()
                    return if (url.host == baseHost) {
                        false
                    } else {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, url))
                        }
                        true
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loading = false
                    canGoBack = view?.canGoBack() == true
                    injectMobileCss(view)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        loading = false
                        pageError = error?.description?.toString() ?: "页面加载失败"
                    }
                }
            })

            setWebChromeClient(object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    if (consoleMessage != null) {
                        Log.i(
                            "DSHMobileWeb",
                            "${consoleMessage.messageLevel()} ${consoleMessage.message()} @ ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}",
                        )
                    }
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: WebChromeClient.FileChooserParams?,
                ): Boolean {
                    if (filePathCallback == null) return false
                    fileCallback?.onReceiveValue(null)
                    fileCallback = filePathCallback
                    fileLauncher.launch("*/*")
                    return true
                }
            })

            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                runCatching {
                    val request = DownloadManager.Request(Uri.parse(url))
                    val cookie = CookieManager.getInstance().getCookie(url)
                    if (!cookie.isNullOrBlank()) request.addRequestHeader("Cookie", cookie)
                    if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
                    if (!mimeType.isNullOrBlank()) request.setMimeType(mimeType)
                    request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                    )
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    request.setDestinationInExternalFilesDir(
                        context,
                        Environment.DIRECTORY_DOWNLOADS,
                        fileName,
                    )
                    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    manager.enqueue(request)
                    Toast.makeText(context, "开始下载：$fileName", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "下载失败：${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(baseUrl) {
        loading = true
        pageError = null
        webView.loadUrl("$baseUrl/")
    }

    LaunchedEffect(notificationsEnabled, baseUrl) {
        if (notificationsEnabled) {
            val intent = Intent(context, DshEventService::class.java)
                .putExtra(DshEventService.EXTRA_BASE_URL, baseUrl)
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.stopService(Intent(context, DshEventService::class.java))
        }
    }

    DisposableEffect(webView) {
        onDispose {
            context.stopService(Intent(context, DshEventService::class.java))
            webView.stopLoading()
            webView.destroy()
        }
    }

    BackHandler(enabled = canGoBack) {
        webView.goBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = profileLabel ?: "DSH Mobile",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            IconButton(onClick = { showSettings = true }) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "设置",
                )
            }
        }

        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            if (pageError == null) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("页面加载失败", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = pageError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            pageError = null
                            loading = true
                            webView.reload()
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Text("重试")
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            baseUrl = baseUrl,
            profileLabel = profileLabel,
            sessionCount = sessionCount,
            version = version,
            notificationsEnabled = notificationsEnabled,
            onManageConnections = {
                showSettings = false
                onManageConnections()
            },
            onToggleNotifications = { enabled ->
                if (enabled &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    onToggleNotifications(enabled)
                }
            },
            onReload = { webView.reload() },
            onRefresh = onRefresh,
            onUnpair = {
                context.stopService(Intent(context, DshEventService::class.java))
                showSettings = false
                onUnpair()
            },
            onDismiss = { showSettings = false },
        )
    }
}

private fun injectMobileCss(view: WebView?) {
    val script = """
        (function() {
          if (document.getElementById('dsh-mobile-css')) return;
          var style = document.createElement('style');
          style.id = 'dsh-mobile-css';
          style.textContent = `
            html, body { overscroll-behavior-y: none; }
            body { padding-bottom: env(safe-area-inset-bottom); }
            /* Fairy-style skins paint body::after at z-index:0, which can cover the
               DSH WebView on Android. Keep decorative layers behind the app. */
            body::before { z-index: -2 !important; }
            body::after { z-index: -1 !important; }
            /* Once the skin reaches its dock phase, make sure its full-screen
               stage cannot keep covering the app if a WebView transition stalls. */
            .fz3-layer[data-phase="dock"] .fz3-screen { display: none !important; }
            * { -webkit-tap-highlight-color: transparent; }
            ::-webkit-scrollbar { width: 0; height: 0; }
            textarea, input { font-size: 16px !important; }
          `;
          document.head.appendChild(style);
        })();
    """.trimIndent()
    view?.evaluateJavascript(script, null)
}
