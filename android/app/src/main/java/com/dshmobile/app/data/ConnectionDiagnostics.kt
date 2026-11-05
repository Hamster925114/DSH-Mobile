package com.dshmobile.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

enum class DiagnosticStatus {
    PASS,
    WARN,
    FAIL,
    SKIP,
}

data class DiagnosticStep(
    val id: String,
    val title: String,
    val status: DiagnosticStatus,
    val detail: String,
    val hint: String? = null,
)

data class DiagnosticTarget(
    val baseUrl: String,
    val scheme: String,
    val host: String,
    val port: Int,
    val secure: Boolean,
)

/** Parse a saved profile URL into the pieces used by the probes. */
fun parseDiagnosticTarget(raw: String): DiagnosticTarget? {
    val base = runCatching { DshUrl.normalize(raw) }.getOrNull() ?: return null
    val url = base.toHttpUrlOrNull() ?: return null
    return DiagnosticTarget(
        baseUrl = base,
        scheme = url.scheme,
        host = url.host,
        port = url.port,
        secure = url.isHttps,
    )
}

fun hintForHttpStatus(code: Int): String? = when (code) {
    401 -> "登录 Cookie 已失效或未配对：回到配对页重新扫码。"
    403 -> "Host 未受信任：桌面 connection.trustedHosts 缺少这个域名。"
    404 -> "bridge 未安装，或这个地址不是 DSH。"
    502, 503, 504 -> "反向代理 / Tailscale serve / frp 已连上，但后端 DSH 不可达。"
    else -> null
}

/**
 * Runs the M1 connection chain without changing the active connection.
 * Network probes are blocking; [run] moves them to Dispatchers.IO.
 */
class ConnectionDiagnostics(
    private val cookieLookup: (String) -> String?,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(rawBaseUrl: String): List<DiagnosticStep> = withContext(Dispatchers.IO) {
        val steps = mutableListOf<DiagnosticStep>()
        val target = parseDiagnosticTarget(rawBaseUrl)
        if (target == null) {
            steps += DiagnosticStep(
                id = "url",
                title = "地址格式",
                status = DiagnosticStatus.FAIL,
                detail = "无法解析地址：${rawBaseUrl.ifBlank { "（空）" }}",
                hint = "填写完整的 http(s)://主机:端口，例如 https://hamster.tail52bb47.ts.net",
            )
            return@withContext steps
        }
        steps += DiagnosticStep(
            id = "url",
            title = "地址格式",
            status = DiagnosticStatus.PASS,
            detail = "${target.scheme}://${target.host}:${target.port}",
        )

        val addresses = probeDns(target)
        steps += addresses.step
        steps += probeTcp(target)
        steps += probeTls(target)
        steps += probeHttp(target)
        steps += probeCookie(target)
        steps += probeApi(target)
        steps += probeWebSocket(target)
        steps
    }

    private fun probeDns(target: DiagnosticTarget): DnsResult {
        return try {
            val addresses = InetAddress.getAllByName(target.host)
            if (addresses.isEmpty()) {
                DnsResult(
                    DiagnosticStep(
                        id = "dns",
                        title = "DNS 解析",
                        status = DiagnosticStatus.FAIL,
                        detail = "没有解析到地址",
                        hint = "检查 Tailscale MagicDNS / 域名拼写 / 手机网络。",
                    ),
                    emptyList(),
                )
            } else {
                DnsResult(
                    DiagnosticStep(
                        id = "dns",
                        title = "DNS 解析",
                        status = DiagnosticStatus.PASS,
                        detail = addresses.joinToString(" · ") { it.hostAddress ?: "?" },
                    ),
                    addresses.toList(),
                )
            }
        } catch (error: UnknownHostException) {
            DnsResult(
                DiagnosticStep(
                    id = "dns",
                    title = "DNS 解析",
                    status = DiagnosticStatus.FAIL,
                    detail = "无法解析 ${target.host}",
                    hint = "Tailscale 未连接 / MagicDNS 关闭，或域名写错。",
                ),
                emptyList(),
            )
        } catch (error: Exception) {
            DnsResult(
                DiagnosticStep(
                    id = "dns",
                    title = "DNS 解析",
                    status = DiagnosticStatus.FAIL,
                    detail = error.message ?: error.javaClass.simpleName,
                    hint = "检查手机网络和 DNS。",
                ),
                emptyList(),
            )
        }
    }

    private data class DnsResult(val step: DiagnosticStep, val addresses: List<InetAddress>)

    private fun probeTcp(target: DiagnosticTarget): DiagnosticStep {
        val started = System.currentTimeMillis()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(target.host, target.port), 8000)
                DiagnosticStep(
                    id = "tcp",
                    title = "TCP 连接",
                    status = DiagnosticStatus.PASS,
                    detail = "${target.host}:${target.port} 可达 · ${System.currentTimeMillis() - started} ms",
                )
            }
        } catch (error: Exception) {
            DiagnosticStep(
                id = "tcp",
                title = "TCP 连接",
                status = DiagnosticStatus.FAIL,
                detail = "${target.host}:${target.port} 不可达：${error.message ?: error.javaClass.simpleName}",
                hint = "检查 DSH 是否运行、端口是否正确、防火墙 / Tailscale serve / frp 是否放行。",
            )
        }
    }

    private fun probeTls(target: DiagnosticTarget): DiagnosticStep {
        if (!target.secure) {
            return DiagnosticStep(
                id = "tls",
                title = "TLS 证书",
                status = DiagnosticStatus.SKIP,
                detail = "HTTP 连接，不适用",
            )
        }
        return try {
            val plain = Socket()
            plain.connect(InetSocketAddress(target.host, target.port), 8000)
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(
                plain,
                target.host,
                target.port,
                true,
            ) as SSLSocket
            ssl.use {
                val params = it.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                if (!target.host.contains(":") && !target.host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) {
                    params.serverNames = listOf(SNIHostName(target.host))
                }
                it.sslParameters = params
                it.startHandshake()
                val cert = it.session.peerCertificates.firstOrNull() as? X509Certificate
                DiagnosticStep(
                    id = "tls",
                    title = "TLS 证书",
                    status = DiagnosticStatus.PASS,
                    detail = cert?.subjectDN?.name ?: "TLS 握手成功",
                )
            }
        } catch (error: Exception) {
            DiagnosticStep(
                id = "tls",
                title = "TLS 证书",
                status = DiagnosticStatus.FAIL,
                detail = error.message ?: error.javaClass.simpleName,
                hint = "检查系统时间、Tailscale HTTPS 证书、frp/Nginx 证书链。",
            )
        }
    }

    private fun probeHttp(target: DiagnosticTarget): DiagnosticStep {
        val cookie = cookieLookup(target.baseUrl)
        return try {
            val request = Request.Builder()
                .url("${target.baseUrl}/mobile/hello")
                .apply { cookie?.let { header("Cookie", it) } }
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> {
                        val bridge = runCatching {
                            json.parseToJsonElement(body).jsonObject["version"]
                                ?.jsonPrimitive?.contentOrNull
                        }.getOrNull()
                        DiagnosticStep(
                            id = "http",
                            title = "HTTP / bridge",
                            status = DiagnosticStatus.PASS,
                            detail = "HTTP ${response.code} · bridge ${bridge ?: "?"}",
                        )
                    }

                    else -> DiagnosticStep(
                        id = "http",
                        title = "HTTP / bridge",
                        status = DiagnosticStatus.FAIL,
                        detail = "HTTP ${response.code}",
                        hint = hintForHttpStatus(response.code) ?: "检查代理路径和 Host 头。",
                    )
                }
            }
        } catch (error: Exception) {
            DiagnosticStep(
                id = "http",
                title = "HTTP / bridge",
                status = DiagnosticStatus.FAIL,
                detail = error.message ?: error.javaClass.simpleName,
                hint = "TCP/TLS 已通过但 HTTP 失败时，重点检查反代路径和 Host 配置。",
            )
        }
    }

    private fun probeCookie(target: DiagnosticTarget): DiagnosticStep {
        val cookie = cookieLookup(target.baseUrl)
        return if (cookie.isNullOrBlank()) {
            DiagnosticStep(
                id = "cookie",
                title = "本地 Cookie",
                status = DiagnosticStatus.FAIL,
                detail = "没有找到登录 Cookie",
                hint = "在 App 里重新配对；每个连接地址需要单独配对一次。",
            )
        } else {
            DiagnosticStep(
                id = "cookie",
                title = "本地 Cookie",
                status = DiagnosticStatus.PASS,
                detail = "已保存（长度 ${cookie.length}）",
            )
        }
    }

    private fun probeApi(target: DiagnosticTarget): DiagnosticStep {
        val cookie = cookieLookup(target.baseUrl)
        val body = buildJsonObject {
            put("type", "client-request")
            put("rpcId", UUID.randomUUID().toString())
            put("method", "session/list")
            put("payload", buildJsonObject { put("args", buildJsonObject { put("_request", buildJsonObject { }) }) })
        }
        return try {
            val request = Request.Builder()
                .url("${target.baseUrl}/api/session/list")
                .apply { cookie?.let { header("Cookie", it) } }
                .post(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    DiagnosticStep(
                        id = "api",
                        title = "API 认证",
                        status = DiagnosticStatus.PASS,
                        detail = "session/list HTTP ${response.code}",
                    )
                } else {
                    DiagnosticStep(
                        id = "api",
                        title = "API 认证",
                        status = DiagnosticStatus.FAIL,
                        detail = "HTTP ${response.code}",
                        hint = hintForHttpStatus(response.code) ?: "检查 Cookie 和 connection.trustedHosts。",
                    )
                }
            }
        } catch (error: Exception) {
            DiagnosticStep(
                id = "api",
                title = "API 认证",
                status = DiagnosticStatus.FAIL,
                detail = error.message ?: error.javaClass.simpleName,
                hint = "HTTP 通了但 API 失败时，优先检查登录 Cookie。",
            )
        }
    }

    private fun probeWebSocket(target: DiagnosticTarget): DiagnosticStep {
        val wsUrl = target.baseUrl.toHttpUrlOrNull()
            ?.newBuilder()
            ?.scheme(if (target.secure) "wss" else "ws")
            ?.addPathSegments("api/remote.mux")
            ?.build()
            ?: return DiagnosticStep(
                id = "websocket",
                title = "WebSocket",
                status = DiagnosticStatus.FAIL,
                detail = "无法生成 WebSocket 地址",
            )

        val cookie = cookieLookup(target.baseUrl)
        val latch = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val opened = AtomicReference(false)
        val webSocketRef = AtomicReference<WebSocket?>()

        val request = Request.Builder()
            .url(wsUrl)
            .header("Origin", target.baseUrl)
            .apply { cookie?.let { header("Cookie", it) } }
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.set(true)
                webSocketRef.set(webSocket)
                latch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure.set(t.message ?: "WebSocket 握手失败")
                webSocketRef.set(webSocket)
                latch.countDown()
            }
        }

        return try {
            webSocketRef.set(client.newWebSocket(request, listener))
            val completed = latch.await(9, TimeUnit.SECONDS)
            val ws = webSocketRef.get()
            if (!completed) {
                ws?.cancel()
                DiagnosticStep(
                    id = "websocket",
                    title = "WebSocket",
                    status = DiagnosticStatus.FAIL,
                    detail = "9 秒内没有完成握手",
                    hint = "反代必须转发 Upgrade/Connection 头；Tailscale serve 需正常运行。",
                )
            } else if (opened.get()) {
                ws?.close(1000, "diagnostics")
                DiagnosticStep(
                    id = "websocket",
                    title = "WebSocket",
                    status = DiagnosticStatus.PASS,
                    detail = "101 Switching Protocols",
                )
            } else {
                ws?.cancel()
                DiagnosticStep(
                    id = "websocket",
                    title = "WebSocket",
                    status = DiagnosticStatus.FAIL,
                    detail = failure.get() ?: "握手失败",
                    hint = "常见原因：Cookie 过期、反代未升级 WebSocket、Host 未信任。",
                )
            }
        } catch (error: Exception) {
            DiagnosticStep(
                id = "websocket",
                title = "WebSocket",
                status = DiagnosticStatus.FAIL,
                detail = error.message ?: error.javaClass.simpleName,
                hint = "检查 /api/remote.mux 和代理的 Upgrade 支持。",
            )
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
