package com.dshmobile.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import com.dshmobile.app.MainActivity
import com.dshmobile.app.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.jvm.Volatile

/**
 * Foreground service that keeps the `$events` WebSocket open and posts local
 * notifications for approvals and session errors.
 *
 * Deliberately not a push service: if Android kills the app/process, events
 * stop. The settings UI says so.
 */
class DshEventService : Service() {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var currentBaseUrl: String? = null

    private var clientId: String? = null
    private var retryCount = 0

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            retryCount = 0
            val frame = buildJsonObject {
                put("type", "open")
                put("streamId", "events")
                put("endpoint", "\$events")
                put("payload", buildJsonObject { })
            }
            webSocket.send(frame.toString())
            updateServiceNotification("已连接，等待事件")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { handleFrame(text) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket !== webSocket) return
            socket = null
            if (response?.code == 401) {
                notifyEvent("登录已失效", "请重新配对 DSH Mobile")
                stopSelf()
                return
            }
            updateServiceNotification("连接断开，准备重连…")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket !== webSocket) return
            socket = null
            scheduleReconnect()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val baseUrl = intent?.getStringExtra(EXTRA_BASE_URL)?.trim()?.trimEnd('/')
        if (baseUrl.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (currentBaseUrl == baseUrl && socket != null) return START_NOT_STICKY

        currentBaseUrl = baseUrl
        retryCount = 0
        startForeground(SERVICE_NOTIFICATION_ID, serviceNotification("正在连接 DSH…"))
        connect(baseUrl)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        currentBaseUrl = null
        handler.removeCallbacksAndMessages(null)
        socket?.close(1000, "service stopped")
        socket = null
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connect(baseUrl: String) {
        socket?.close(1000, "reconnect")
        val wsBase = when {
            baseUrl.startsWith("https://") -> "wss://" + baseUrl.removePrefix("https://")
            baseUrl.startsWith("http://") -> "ws://" + baseUrl.removePrefix("http://")
            else -> {
                stopSelf()
                return
            }
        }
        val origin = runCatching {
            val uri = java.net.URI(baseUrl)
            "${uri.scheme}://${uri.rawAuthority}"
        }.getOrDefault(baseUrl)
        val request = Request.Builder()
            .url("$wsBase/api/remote.mux")
            .header("Cookie", CookieManager.getInstance().getCookie("$baseUrl/").orEmpty())
            .header("Origin", origin)
            .build()
        socket = client.newWebSocket(request, listener)
    }

    private fun scheduleReconnect() {
        val base = currentBaseUrl ?: return
        retryCount += 1
        val delayMs = minOf(30_000L, 1_000L * (1 shl minOf(retryCount, 5)))
        handler.postDelayed({
            if (currentBaseUrl == base) connect(base)
        }, delayMs)
    }

    private fun handleFrame(text: String) {
        val root = json.parseToJsonElement(text).jsonObject
        when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "item" -> {
                val item = root["item"]?.jsonObject ?: return
                when (item["type"]?.jsonPrimitive?.contentOrNull) {
                    "ready" -> {
                        clientId = item["clientId"]?.jsonPrimitive?.contentOrNull
                        updateServiceNotification("已连接 DSH")
                    }

                    "waterfall" -> {
                        val event = item["event"]?.jsonPrimitive?.contentOrNull
                        if (event == "approval/request") {
                            notifyEvent("需要审批", "DSH 正在等待你的审批")
                        }
                    }

                    "emit" -> {
                        val event = item["event"]?.jsonPrimitive?.contentOrNull
                        if (event == "api-session/error") {
                            notifyEvent("任务出错", "DSH 会话出现错误")
                        }
                    }
                }
            }

            "error" -> {
                val message = root["error"]?.jsonObject?.get("message")
                    ?.jsonPrimitive?.contentOrNull
                    ?: "事件通道错误"
                notifyEvent("连接错误", message)
            }
        }
    }

    private fun notifyEvent(title: String, message: String) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            val id = EVENT_NOTIFICATION_ID + (System.currentTimeMillis() % 1000).toInt()
            manager.notify(id, eventNotification(title, message))
        }
    }

    private fun updateServiceNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(SERVICE_NOTIFICATION_ID, serviceNotification(text))
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "DSH 连接",
                NotificationManager.IMPORTANCE_LOW,
            )
            val eventChannel = NotificationChannel(
                CHANNEL_EVENTS,
                "DSH 事件",
                NotificationManager.IMPORTANCE_HIGH,
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannels(listOf(serviceChannel, eventChannel))
        }
    }

    private fun serviceNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle("DSH Mobile")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun eventNotification(title: String, message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val EXTRA_BASE_URL = "base_url"

        private const val CHANNEL_SERVICE = "dsh_service"
        private const val CHANNEL_EVENTS = "dsh_events"
        private const val SERVICE_NOTIFICATION_ID = 1001
        private const val EVENT_NOTIFICATION_ID = 2000
    }
}
