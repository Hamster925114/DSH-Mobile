package com.dshmobile.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.dshmobile.app.data.BlobDownloadBridge
import com.dshmobile.app.data.DshApi
import com.dshmobile.app.service.DshEventService
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

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
    openSessionId: String? = null,
    onOpenSessionHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val darkTheme = isSystemInDarkTheme()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showDevices by rememberSaveable { mutableStateOf(false) }
    var terminalMode by rememberSaveable { mutableStateOf(false) }
    var terminalCtrl by rememberSaveable { mutableStateOf(false) }
    var terminalAlt by rememberSaveable { mutableStateOf(false) }
    var networkAvailable by remember { mutableStateOf(true) }
    var dshReachable by remember { mutableStateOf(true) }
    var wasOffline by remember { mutableStateOf(false) }
    val healthApi = remember { DshApi(context.applicationContext) }
    var loading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val currentBaseUrl by rememberUpdatedState(baseUrl)

    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingFileChooser by remember { mutableStateOf(false) }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uris = mutableListOf<Uri>()
        val data = result.data
        data?.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index)?.uri?.let { uris.add(it) }
            }
        }
        data?.data?.let { uris.add(it) }
        if (uris.isEmpty() && result.resultCode == Activity.RESULT_OK) {
            cameraUri?.let { uris.add(it) }
        }
        val staged = uris.mapNotNull { uri ->
            runCatching { stageForWebView(context, uri) }.getOrNull()
        }
        fileCallback?.onReceiveValue(staged.takeIf { it.isNotEmpty() }?.toTypedArray())
        fileCallback = null
        cameraUri = null
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            pendingFileChooser = true
        } else {
            fileCallback?.onReceiveValue(null)
            fileCallback = null
            Toast.makeText(context, "需要相机权限才能拍照上传", Toast.LENGTH_SHORT).show()
        }
    }

    fun openFileChooser() {
        val cameraFile = File(context.cacheDir, "shared").apply { mkdirs() }
            .resolve("camera-${System.currentTimeMillis()}.jpg")
        val captureUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            cameraFile,
        )
        cameraUri = captureUri

        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, captureUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(picker, "选择文件或拍照").apply {
            if (camera.resolveActivity(context.packageManager) != null) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
            }
        }
        fileChooserLauncher.launch(chooser)
    }

    LaunchedEffect(pendingFileChooser) {
        if (pendingFileChooser) {
            pendingFileChooser = false
            openFileChooser()
        }
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
            addJavascriptInterface(
                BlobDownloadBridge(context.applicationContext),
                "AndroidDownloader",
            )
            setBackgroundColor(if (darkTheme) 0xFF151517.toInt() else 0xFFF7F8FA.toInt())
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
                    Log.i(
                        "DSHMobileWeb",
                        "page_finished url=$url cookie_len=${CookieManager.getInstance().getCookie(baseUrl)?.length ?: 0}",
                    )
                    logWebViewDiagnostics(view)
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
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        openFileChooser()
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                    return true
                }
            })

            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                if (url.startsWith("blob:")) {
                    val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    val script = """
                        (function() {
                          if (typeof window.__DSH_MOBILE_SAVE_BLOB__ !== 'function') return false;
                          window.__DSH_MOBILE_SAVE_BLOB__(
                            ${org.json.JSONObject.quote(url)},
                            ${org.json.JSONObject.quote(name)}
                          );
                          return true;
                        })();
                    """.trimIndent()
                    evaluateJavascript(script) { result ->
                        if (result != "true") {
                            Toast.makeText(
                                context,
                                "当前页面无法保存 blob 下载，请重新加载后再试",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                    return@setDownloadListener
                }
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Public Downloads: visible in Files/Downloads and shareable.
                        request.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            fileName,
                        )
                    } else {
                        request.setDestinationInExternalFilesDir(
                            context,
                            Environment.DIRECTORY_DOWNLOADS,
                            fileName,
                        )
                    }
                    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    manager.enqueue(request)
                    val location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        "系统下载目录"
                    } else {
                        "应用下载目录"
                    }
                    Toast.makeText(context, "开始下载到$location：$fileName", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "下载失败：${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    DisposableEffect(Unit) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkAvailable = connectivity.activeNetwork?.let { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkAvailable = true
            }

            override fun onLost(network: Network) {
                networkAvailable = false
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                networkAvailable = networkCapabilities
                    .hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        onDispose {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }

    LaunchedEffect(networkAvailable) {
        if (!networkAvailable) {
            wasOffline = true
        } else if (wasOffline) {
            wasOffline = false
            if (pageError != null) {
                pageError = null
                loading = true
                webView.reload()
            }
        }
    }

    LaunchedEffect(baseUrl) {
        while (isActive) {
            val reachable = withContext(Dispatchers.IO) {
                healthApi.testConnection(baseUrl).isSuccess
            }
            dshReachable = reachable
            if (!reachable) {
                wasOffline = true
            } else if (wasOffline) {
                wasOffline = false
                if (pageError != null) {
                    pageError = null
                    loading = true
                    webView.reload()
                }
            }
            delay(5_000)
        }
    }

    LaunchedEffect(darkTheme) {
        webView.setBackgroundColor(if (darkTheme) 0xFF151517.toInt() else 0xFFF7F8FA.toInt())
    }

    LaunchedEffect(baseUrl) {
        loading = true
        pageError = null
        webView.loadUrl("$baseUrl/")
    }

    LaunchedEffect(openSessionId) {
        val sessionId = openSessionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@LaunchedEffect
        val quoted = JSONObject.quote(sessionId)
        val script = """
            (function() {
              try {
                if (typeof window.__DSH_MOBILE_OPEN_SESSION__ !== 'function') return false;
                window.__DSH_MOBILE_OPEN_SESSION__($quoted);
                return true;
              } catch (error) {
                return false;
              }
            })();
        """.trimIndent()
        // The Tailscale page can take 20–30s to boot its plugin bundle.
        // Keep trying until the DSH workspace bridge is ready.
        repeat(120) {
            var handled = false
            webView.evaluateJavascript(script) { result ->
                handled = result == "true"
            }
            delay(500)
            if (handled) {
                onOpenSessionHandled()
                return@LaunchedEffect
            }
        }
        Toast.makeText(context, "未能定位到目标会话，请手动打开", Toast.LENGTH_SHORT).show()
        onOpenSessionHandled()
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
            healthApi.close()
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
        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (!networkAvailable || !dshReachable) {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (!networkAvailable) {
                            "网络已断开，正在等待恢复…"
                        } else {
                            "DSH 不可达，检查电脑和 Tailscale…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            pageError = null
                            loading = true
                            webView.reload()
                        },
                    ) {
                        Text("重试")
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
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
                    Text(
                        text = "检查手机网络和 Tailscale 是否连接，然后重试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
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

        if (terminalMode) {
            TerminalKeyBar(
                compact = landscape,
                ctrl = terminalCtrl,
                alt = terminalAlt,
                onToggleCtrl = { terminalCtrl = !terminalCtrl },
                onToggleAlt = { terminalAlt = !terminalAlt },
                onKey = { key -> sendTerminalKey(webView, key, terminalCtrl, terminalAlt) },
                onCtrlC = { sendTerminalKey(webView, "c", true, false) },
            )
        }

        MobileBottomBar(
            compact = landscape,
            onChat = {
                terminalMode = false
                clickMobileTarget(webView, "chat")
            },
            onFiles = {
                terminalMode = false
                clickMobileTarget(webView, "files")
            },
            onTerminal = {
                terminalMode = true
                clickMobileTarget(webView, "terminal")
            },
            onPasteImage = {
                terminalMode = false
                clickMobileTarget(webView, "chat")
                scope.launch {
                    delay(250)
                    val pasted = pasteClipboardImage(context, webView)
                    Toast.makeText(
                        context,
                        if (pasted) "已粘贴剪贴板图片" else "剪贴板里没有图片",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onSettings = {
                terminalMode = false
                showSettings = true
            },
        )
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
            onManageDevices = {
                showSettings = false
                showDevices = true
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

    if (showDevices) {
        DevicesDialog(
            baseUrl = baseUrl,
            onDismiss = { showDevices = false },
            onCurrentDeviceRevoked = {
                context.stopService(Intent(context, DshEventService::class.java))
                showDevices = false
                onUnpair()
            },
        )
    }
}

@Composable
private fun TerminalKeyBar(
    compact: Boolean,
    ctrl: Boolean,
    alt: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onKey: (String) -> Unit,
    onCtrlC: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = if (compact) 3.dp else 6.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
        ) {
            TerminalKey("Esc", compact = compact) { onKey("Escape") }
            TerminalKey("Tab", compact = compact) { onKey("Tab") }
            TerminalKey("Enter", compact = compact) { onKey("Enter") }
            TerminalKey("Ctrl", compact = compact, selected = ctrl, onClick = onToggleCtrl)
            TerminalKey("Alt", compact = compact, selected = alt, onClick = onToggleAlt)
            TerminalKey("Ctrl+C", compact = compact, onClick = onCtrlC)
            TerminalKey("↑", compact = compact) { onKey("ArrowUp") }
            TerminalKey("↓", compact = compact) { onKey("ArrowDown") }
            TerminalKey("←", compact = compact) { onKey("ArrowLeft") }
            TerminalKey("→", compact = compact) { onKey("ArrowRight") }
        }
    }
}

@Composable
private fun TerminalKey(
    label: String,
    compact: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier
            .height(if (compact) 30.dp else 36.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = if (compact) 9.dp else 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = if (compact) {
                    MaterialTheme.typography.labelSmall
                } else {
                    MaterialTheme.typography.labelMedium
                },
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun MobileBottomBar(
    compact: Boolean,
    onChat: () -> Unit,
    onFiles: () -> Unit,
    onTerminal: () -> Unit,
    onPasteImage: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(if (compact) 42.dp else 58.dp),
        ) {
            MobileNavItem(
                icon = Icons.Default.Chat,
                label = "会话",
                compact = compact,
                onClick = onChat,
                modifier = Modifier.weight(1f),
            )
            MobileNavItem(
                icon = Icons.Default.Folder,
                label = "文件",
                compact = compact,
                onClick = onFiles,
                modifier = Modifier.weight(1f),
            )
            MobileNavItem(
                icon = Icons.Default.Terminal,
                label = "终端",
                compact = compact,
                onClick = onTerminal,
                modifier = Modifier.weight(1f),
            )
            MobileNavItem(
                icon = Icons.Default.ContentPaste,
                label = "粘贴",
                compact = compact,
                onClick = onPasteImage,
                modifier = Modifier.weight(1f),
            )
            MobileNavItem(
                icon = Icons.Default.Settings,
                label = "设置",
                compact = compact,
                onClick = onSettings,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MobileNavItem(
    icon: ImageVector,
    label: String,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(if (compact) 20.dp else 21.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!compact) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun clickMobileTarget(view: WebView?, target: String) {
    val script = """
        (function() {
          var wanted = '$target';
          function clickText(text) {
            var leaves = [];
            document.querySelectorAll('*').forEach(function(el) {
              if (el.children.length === 0 && (el.textContent || '').trim() === text) {
                leaves.push(el);
              }
            });
            for (var i = 0; i < leaves.length; i++) {
              var button = leaves[i].closest('button,[role="button"]');
              if (button) {
                button.click();
                return true;
              }
            }
            return false;
          }
          // Prefer the client-ui bridge: it calls DSH's sidebarRight service
          // directly, so the collapsed/hidden DOM cannot swallow the click.
          if (wanted === 'files' || wanted === 'terminal') {
            var direct = window.__DSH_MOBILE_OPEN_PANEL__;
            if (typeof direct === 'function' && direct(wanted)) return;
          }
          if (wanted === 'chat') {
            function clickChatTab() {
              var buttons = document.querySelectorAll('button');
              for (var i = 0; i < buttons.length; i++) {
                var text = (buttons[i].innerText || '').trim();
                var rect = buttons[i].getBoundingClientRect();
                if (text === 'Chat' && rect.width > 0 && rect.height > 0) {
                  buttons[i].click();
                  return true;
                }
              }
              return false;
            }
            // A fullscreen/right-side Files or Terminal panel must be closed
            // before the main conversation Chat tab is reachable. Use the
            // locale-independent bridge/data attributes; DSH labels are
            // localized (Chinese on this device).
            var closeBridge = window.__DSH_MOBILE_CLOSE_PANEL__;
            var closed = typeof closeBridge === 'function' && closeBridge();
            if (!closed) {
              var openPanel = document.querySelector(
                '[data-sidebar-right-panel][data-sidebar-right-open="true"]'
              );
              if (openPanel) {
                var toggle = document.querySelector('[data-sidebar-right-toggle]');
                if (toggle) toggle.click();
              }
            }
            setTimeout(clickChatTab, 400);
            return;
          }
          function clickTarget() {
            if (wanted === 'files') {
              var files = document.querySelector(
                '[data-sidebar-right-guide-entry="files"] button'
              ) || document.querySelector('[aria-label^="Workspace files"]');
              if (files) {
                files.click();
                return true;
              }
              return clickText('Workspace files');
            }
            var terminal = document.querySelector(
              '[data-sidebar-right-guide-entry="terminal"] button'
            ) || document.querySelector('[aria-label^="New terminal"]');
            if (terminal) {
              terminal.click();
              return true;
            }
            return false;
          }
          function ensureExpanded() {
            var openPanel = document.querySelector(
              '[data-sidebar-right-panel][data-sidebar-right-open="true"]'
            );
            if (openPanel) return;
            var expand = document.querySelector('[data-sidebar-right-expand]') ||
              document.querySelector('[data-sidebar-right-toggle]') ||
              document.querySelector('[aria-label="Open right sidebar"]');
            if (expand) expand.click();
          }
          if (clickTarget()) {
            setTimeout(ensureExpanded, 120);
            return;
          }
          var newTab = document.querySelector('[data-dockkit-add-tab]');
          if (newTab) {
            newTab.click();
          }
          ensureExpanded();
          var attempts = 0;
          function retryTarget() {
            if (clickTarget() || ++attempts >= 4) {
              ensureExpanded();
              return;
            }
            setTimeout(retryTarget, 400);
          }
          setTimeout(retryTarget, 350);
        })();
    """.trimIndent()
    view?.evaluateJavascript(script, null)
}

private fun sendTerminalKey(
    view: WebView?,
    key: String,
    ctrl: Boolean,
    alt: Boolean,
) {
    val code: String
    val keyCode: Int
    when (key) {
        "Escape" -> {
            code = "Escape"
            keyCode = 27
        }
        "Tab" -> {
            code = "Tab"
            keyCode = 9
        }
        "Enter" -> {
            code = "Enter"
            keyCode = 13
        }
        "ArrowUp" -> {
            code = "ArrowUp"
            keyCode = 38
        }
        "ArrowDown" -> {
            code = "ArrowDown"
            keyCode = 40
        }
        "ArrowLeft" -> {
            code = "ArrowLeft"
            keyCode = 37
        }
        "ArrowRight" -> {
            code = "ArrowRight"
            keyCode = 39
        }
        else -> {
            code = "Key${key.uppercase()}"
            keyCode = key.uppercase().first().code
        }
    }
    val script = """
        (function() {
          var textarea = document.querySelector('.xterm-helper-textarea');
          if (!textarea) return;
          textarea.focus();
          var options = {
            key: '${key}',
            code: '$code',
            keyCode: $keyCode,
            which: $keyCode,
            ctrlKey: $ctrl,
            altKey: $alt,
            bubbles: true,
            cancelable: true
          };
          textarea.dispatchEvent(new KeyboardEvent('keydown', options));
          if ('${key}'.length === 1) {
            textarea.dispatchEvent(new KeyboardEvent('keypress', options));
          }
          textarea.dispatchEvent(new KeyboardEvent('keyup', options));
        })();
    """.trimIndent()
    view?.evaluateJavascript(script, null)
}

private fun logWebViewDiagnostics(view: WebView?) {
    val script = """
        (function() {
          function snap(label) {
            try {
              var q = function(s) { return document.querySelector(s); };
              var layer = q('.fz3-layer');
              var screen = q('.fz3-screen');
              var frame = q('[class*="_frame"]');
              var sidebar = q('[class*="_sidebarCol"]');
              var center = q('[class*="_centerCol"]');
              var root = document.getElementById('root');
              console.log('DSH_MOBILE_DIAG ' + label + ' ' + JSON.stringify({
                href: location.href,
                ready: document.readyState,
                inner: [innerWidth, innerHeight, devicePixelRatio],
                phase: layer && layer.getAttribute('data-phase'),
                screenOpacity: screen && getComputedStyle(screen).opacity,
                screenDisplay: screen && getComputedStyle(screen).display,
                frame: frame && frame.getBoundingClientRect().toJSON(),
                sidebar: sidebar && sidebar.getBoundingClientRect().toJSON(),
                center: center && center.getBoundingClientRect().toJSON(),
                rootChildren: root && root.children.length,
                shell: {
                  html: document.documentElement.getBoundingClientRect().height,
                  body: document.body.getBoundingClientRect().height,
                  root: root && root.getBoundingClientRect().height
                },
                afterZ: getComputedStyle(document.body, '::after').zIndex,
                text: (document.body.innerText || '').slice(0, 300)
              }));
            } catch (e) {
              console.log('DSH_MOBILE_DIAG ' + label + ' ERROR ' + e);
            }
          }
          setTimeout(function() { snap('5s'); }, 5000);
          setTimeout(function() { snap('12s'); }, 12000);
        })();
    """.trimIndent()
    view?.evaluateJavascript(script, null)
}

private fun injectMobileCss(view: WebView?) {
    val script = """
        (function() {
          if (!document.body) return;
          if (document.getElementById('dsh-mobile-css')) return;
          var style = document.createElement('style');
          style.id = 'dsh-mobile-css';
          style.textContent = `
            html, body { overscroll-behavior-y: none; }
            /* Android WebView may resolve the SPA's height:100% against a
               zero-height root. Give the shell an explicit viewport height. */
            html, body, #root {
              height: 100% !important;
              min-height: 100% !important;
            }
            #root { min-height: 100dvh !important; }
            body { padding-bottom: env(safe-area-inset-bottom); box-sizing: border-box; }
            /* Fairy-style skins paint body::after at z-index:0, which can cover the
               DSH WebView on Android. Keep decorative layers behind the app. */
            body::before { z-index: -2 !important; }
            body::after { z-index: -1 !important; }
            /* Once the skin reaches its dock phase, make sure its full-screen
               stage cannot keep covering the app if a WebView transition stalls. */
            .fz3-layer[data-phase="dock"] .fz3-screen { display: none !important; }
            /* DSH question/choice overlays can also collapse to a zero-height
               line in Android WebView; give them a usable scroll area. */
            [data-conversation-composer-overlay],
            [class*="M4PAiq"],
            [class*="Fu0XRq"] {
              max-height: calc(100% - 96px) !important;
              overflow-y: auto !important;
            }
            /* ask-user-question composer card: max-height:min(60vh,520px)
               also becomes 0 in this WebView. */
            .wnLwla_card {
              max-height: 520px !important;
            }
            @media (max-height: 620px) {
              .wnLwla_card { max-height: 300px !important; }
            }
            /* Other DSH popup/menu/confirmation surfaces that also use 100vh. */
            .Ag-ohG_menu,
            .Luqt-a_menu,
            .rezYhW_menu,
            .eze_pW_panel,
            .olCj9W_zoneMenu,
            .St8tea_dialog,
            ._confirmation_cjd8z_1,
            ._scrollable_4ub78_19,
            .js5r2G_scrollable {
              max-height: 520px !important;
              overflow-y: auto !important;
            }
            @media (max-height: 620px) {
              .Ag-ohG_menu,
              .Luqt-a_menu,
              .rezYhW_menu,
              .eze_pW_panel,
              .olCj9W_zoneMenu,
              .St8tea_dialog,
              ._confirmation_cjd8z_1 {
                max-height: 300px !important;
              }
            }
            /* DSH settings on a phone: stack nav above content instead of
               squeezing the 800px desktop panel into 372px. */
            @media (max-width: 700px) {
              .AnXs9G_panel { flex-direction: column !important; }
              .AnXs9G_nav {
                width: 100% !important;
                padding: 12px !important;
                gap: 8px !important;
              }
              .AnXs9G_navList {
                flex-direction: row !important;
                overflow-x: auto !important;
                overflow-y: hidden !important;
              }
              .AnXs9G_navCell {
                height: 36px !important;
                white-space: nowrap;
              }
              .AnXs9G_content {
                width: 100% !important;
                min-width: 0 !important;
              }
            }
            * { -webkit-tap-highlight-color: transparent; }
            ::-webkit-scrollbar { width: 0; height: 0; }
            textarea, input { font-size: 16px !important; }
          `;
          if (document.head) document.head.appendChild(style);

          function fitShell() {
            var h = window.innerHeight + 'px';
            [document.documentElement, document.body, document.getElementById('root')].forEach(function(el) {
              if (!el) return;
              el.style.setProperty('height', h, 'important');
              el.style.setProperty('min-height', h, 'important');
            });
          }
          fitShell();
          window.addEventListener('resize', fitShell);
          setTimeout(fitShell, 800);

          /* Android WebView can report 100vh as 0. Menus sized with
             max-height:min(360px, 100vh - 96px) then collapse to 0px.
             Give such menus a real height and keep them anchored to the trigger. */
          function positionMenu(menu) {
            var trigger = document.querySelector('[aria-expanded="true"]');
            var h = menu.getBoundingClientRect().height;
            if (!trigger || h < 20) return;
            var tr = trigger.getBoundingClientRect();
            var top = tr.top - h - 8;
            if (top < 8) top = tr.bottom + 8;
            if (top + h > window.innerHeight - 8) top = window.innerHeight - h - 8;
            menu.style.setProperty('top', Math.max(8, top) + 'px', 'important');
          }

          function scanMenus() {
            document.querySelectorAll('[role="menu"]').forEach(function(menu) {
              if (menu.dataset.dshMobileMenuFixed === '1') return;
              var cs = getComputedStyle(menu);
              if (parseFloat(cs.maxHeight) !== 0) return;
              menu.dataset.dshMobileMenuFixed = '1';
              menu.style.setProperty('max-height', '360px', 'important');
              requestAnimationFrame(function() {
                requestAnimationFrame(function() { positionMenu(menu); });
              });
              if (window.ResizeObserver) {
                var ro = new ResizeObserver(function() { positionMenu(menu); });
                ro.observe(menu);
                menu.__dshMobileMenuRO = ro;
              }
            });
          }
          setInterval(scanMenus, 300);
          new MutationObserver(scanMenus).observe(document.body, { childList: true, subtree: true });

          /* DSH dialogs use height:min(800px, calc(100vh - ...)), which also
             becomes 0 in this WebView. Give visible zero-height dialogs a real
             viewport height; this covers DSH Settings and Add Workspace. */
          function fitDialogs() {
            var h = Math.min(800, window.innerHeight - 48);
            document.querySelectorAll('[role="dialog"], [aria-modal="true"]').forEach(function(dialog) {
              if (dialog.dataset.dshMobileDialogFixed === '1') {
                dialog.style.setProperty('height', h + 'px', 'important');
                dialog.style.setProperty('max-height', h + 'px', 'important');
                return;
              }
              var rect = dialog.getBoundingClientRect();
              if (rect.width < 20 || rect.height > 20) return;
              var cs = getComputedStyle(dialog);
              if (cs.display === 'none' || cs.visibility === 'hidden') return;
              dialog.dataset.dshMobileDialogFixed = '1';
              dialog.style.setProperty('height', h + 'px', 'important');
              dialog.style.setProperty('max-height', h + 'px', 'important');
            });
          }
          fitDialogs();
          window.addEventListener('resize', fitDialogs);
          setInterval(fitDialogs, 300);
          new MutationObserver(fitDialogs).observe(document.body, { childList: true, subtree: true });

          /* The ask-user-question panel is a composer overlay. It can be
             present but zero-height before its content is painted. */
          function fitQuestionOverlay() {
            var overlays = document.querySelectorAll(
              '[data-conversation-composer-overlay], .wnLwla_card, [class*="M4PAiq"], [class*="Fu0XRq"]'
            );
            overlays.forEach(function(el) {
              if (el.dataset.dshMobileQuestionFixed === '1') return;
              var rect = el.getBoundingClientRect();
              if (rect.width < 20 || rect.height > 20) return;
              if (!(el.innerText || '').trim()) return;
              el.dataset.dshMobileQuestionFixed = '1';
              el.style.setProperty('min-height', '160px', 'important');
              el.style.setProperty('max-height', Math.min(560, window.innerHeight - 120) + 'px', 'important');
              el.style.setProperty('overflow-y', 'auto', 'important');
            });
          }
          fitQuestionOverlay();
          window.addEventListener('resize', fitQuestionOverlay);
          setInterval(fitQuestionOverlay, 300);
          new MutationObserver(fitQuestionOverlay).observe(document.body, { childList: true, subtree: true });

          /* Last-resort sweep: any visible fixed/absolute popup with text but
             zero height is almost certainly a 100vh collapse. */
          function fitCollapsedPopups() {
            var maxH = Math.min(520, window.innerHeight - 96);
            document.querySelectorAll(
              '[class*="_menu"], [class*="_Menu"], [class*="Menu"], ' +
              '[class*="_panel"], [class*="_popover"], [class*="_dialog"], ' +
              '[class*="_confirmation"], [role="menu"]'
            ).forEach(function(el) {
              if (el.dataset.dshMobilePopupFixed === '1') return;
              var cs = getComputedStyle(el);
              if (cs.display === 'none' || cs.visibility === 'hidden') return;
              if (cs.position !== 'fixed' && cs.position !== 'absolute') return;
              var rect = el.getBoundingClientRect();
              if (rect.width < 80 || rect.height > 20) return;
              if (!(el.innerText || '').trim()) return;
              if (parseFloat(cs.maxHeight) !== 0 && parseFloat(cs.height) !== 0) return;
              el.dataset.dshMobilePopupFixed = '1';
              el.style.setProperty('min-height', '60px', 'important');
              el.style.setProperty('max-height', maxH + 'px', 'important');
              el.style.setProperty('overflow-y', 'auto', 'important');
            });
          }
          fitCollapsedPopups();
          window.addEventListener('resize', fitCollapsedPopups);
          setInterval(fitCollapsedPopups, 300);
          new MutationObserver(fitCollapsedPopups).observe(document.body, { childList: true, subtree: true });
        })();
    """.trimIndent()
    view?.evaluateJavascript(script, null)
}

/**
 * WebView can read content:// URIs directly, but some providers only grant a
 * short-lived read and the DSH uploader reads the File after the picker task
 * has gone. Stage a stable copy in our own cache and hand back a FileProvider
 * URI so the upload cannot lose its grant mid-flight.
 */
private fun stageForWebView(context: Context, uri: Uri): Uri {
    if (uri.authority == "${context.packageName}.fileprovider") return uri
    val displayName = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    val safeName = (displayName ?: "upload-${System.currentTimeMillis()}")
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(80)
        .ifBlank { "upload" }
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val target = File(dir, safeName).let { candidate ->
        if (candidate.exists()) {
            File(dir, "${System.currentTimeMillis()}-$safeName")
        } else {
            candidate
        }
    }
    return runCatching {
        val input = context.contentResolver.openInputStream(uri) ?: return uri
        input.use { source ->
            target.outputStream().use { sink -> source.copyTo(sink) }
        }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
    }.getOrElse {
        target.delete()
        uri
    }
}

private suspend fun pasteClipboardImage(context: Context, webView: WebView): Boolean {
    val base64 = withContext(Dispatchers.IO) {
        runCatching {
            val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return@runCatching null
            val clip = clipboard.primaryClip ?: return@runCatching null
            val uri = (0 until clip.itemCount)
                .mapNotNull { clip.getItemAt(it).uri }
                .firstOrNull { candidate ->
                    val type = context.contentResolver.getType(candidate)
                    type == null || type.startsWith("image/")
                } ?: return@runCatching null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            var sample = 1
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return@runCatching null
            val bitmap = if (decoded.width > 1600 || decoded.height > 1600) {
                val scale = minOf(1600f / decoded.width, 1600f / decoded.height)
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true,
                ).also { if (it !== decoded) decoded.recycle() }
            } else {
                decoded
            }
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            bitmap.recycle()
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    } ?: return false

    val script = """
        (function() {
          try {
            const binary = atob('$base64');
            const bytes = new Uint8Array(binary.length);
            for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
            const file = new File([bytes], 'clipboard.jpg', { type: 'image/jpeg' });
            const transfer = new DataTransfer();
            transfer.items.add(file);
            const target = document.querySelector('[contenteditable="true"]') ||
              document.querySelector('.GRgSLq_input');
            if (!target) return false;
            target.focus();
            target.dispatchEvent(new ClipboardEvent('paste', {
              clipboardData: transfer,
              bubbles: true,
              cancelable: true
            }));
            return true;
          } catch (error) {
            return false;
          }
        })();
    """.trimIndent()

    var handled = false
    withContext(Dispatchers.Main) {
        webView.evaluateJavascript(script) { result -> handled = result == "true" }
        delay(250)
    }
    return handled
}
