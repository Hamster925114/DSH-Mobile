package com.dshmobile.app.data

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class DshHello(
    val version: String,
    val dshVersion: String?,
)

data class PairOutcome(
    val baseUrl: String,
    val sessionCount: Int,
    val hello: DshHello,
)

sealed interface PairResult {
    data class Success(val outcome: PairOutcome) : PairResult
    data class Failure(val message: String) : PairResult
}

/**
 * Minimal DSH HTTP client for M1:
 * - pairing code -> tokenized URL -> browser cookie
 * - /mobile/hello health check
 * - /api/session/list RPC
 *
 * The cookie lives in Android's CookieManager so the WebView and OkHttp share
 * the same login state.
 */
class DshApi(private val settings: SettingsRepository) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(AndroidCookieJar())
        .build()

    suspend fun pair(rawBaseUrl: String, rawCode: String): PairResult = withContext(Dispatchers.IO) {
        try {
            val base = DshUrl.normalize(rawBaseUrl)
            val code = rawCode.trim().uppercase()
            if (code.isEmpty()) throw IllegalArgumentException("配对码不能为空")

            val pairBody = buildJsonObject { put("code", code) }
            val pairJson = postJson("$base/mobile/pair", pairBody)
            val tokenUrl = pairJson["url"]?.jsonPrimitive?.contentOrNull
                ?: throw IOException("配对响应缺少 url")

            client.newCall(Request.Builder().url(tokenUrl).get().build()).execute().use { response ->
                if (response.code !in 200..399) {
                    throw IOException("换取 Cookie 失败：HTTP ${response.code}")
                }
                val cookieUrl = "$base/"
                val setCookies = response.headers.values("Set-Cookie")
                if (setCookies.isEmpty()) {
                    val existing = CookieManager.getInstance().getCookie(cookieUrl)
                    if (existing.isNullOrBlank()) throw IOException("没有拿到登录 Cookie")
                } else {
                    setCookies.forEach { cookie ->
                        runCatching { CookieManager.getInstance().setCookie(cookieUrl, cookie) }
                    }
                }
                CookieManager.getInstance().flush()
            }

            val hello = getHello(base)
            val count = sessionCountBlocking(base)
            settings.setBaseUrl(base)
            PairResult.Success(PairOutcome(base, count, hello))
        } catch (error: Exception) {
            PairResult.Failure(error.message ?: "配对失败")
        }
    }

    suspend fun testConnection(baseUrl: String): Result<DshHello> = withContext(Dispatchers.IO) {
        runCatching { getHello(DshUrl.normalize(baseUrl)) }
    }

    suspend fun sessionCount(baseUrl: String): Int = withContext(Dispatchers.IO) {
        sessionCountBlocking(DshUrl.normalize(baseUrl))
    }

    fun hasCookie(baseUrl: String): Boolean = runCatching {
        val base = DshUrl.normalize(baseUrl)
        !CookieManager.getInstance().getCookie("$base/").isNullOrBlank()
    }.getOrDefault(false)

    fun clearCookies() {
        val manager = CookieManager.getInstance()
        manager.removeAllCookies(null)
        manager.flush()
    }

    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun getHello(base: String): DshHello {
        val request = Request.Builder().url("$base/mobile/hello").get().build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("健康检查失败：HTTP ${response.code}")
            }
            val obj = json.parseToJsonElement(text).jsonObject
            return DshHello(
                version = obj["version"]?.jsonPrimitive?.contentOrNull ?: "?",
                dshVersion = obj["dsh"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    private fun sessionCountBlocking(base: String): Int {
        val value = rpc(
            base = base,
            method = "session/list",
            args = buildJsonObject { put("_request", buildJsonObject { }) },
        )
        val items = when (value) {
            is JsonArray -> value
            is JsonObject -> value["items"] as? JsonArray
            else -> null
        }
        return items?.size ?: 0
    }

    private fun rpc(base: String, method: String, args: JsonObject): JsonElement {
        val envelope = buildJsonObject {
            put("type", "client-request")
            put("rpcId", UUID.randomUUID().toString())
            put("method", method)
            put("payload", buildJsonObject { put("args", args) })
        }
        val request = Request.Builder()
            .url("$base/api/$method")
            .post(envelope.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${text.take(300)}")
            }
            val root = json.parseToJsonElement(text).jsonObject
            val result = root["result"]?.jsonObject ?: throw IOException("RPC 响应缺少 result")
            if (result["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                val error = result["error"]?.jsonObject
                val code = error?.get("code")?.jsonPrimitive?.contentOrNull ?: "unknown"
                val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: "RPC 失败"
                throw IOException("$code: $message")
            }
            return result["value"] ?: JsonNull
        }
    }

    private fun postJson(url: String, body: JsonObject): JsonObject {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val hint = when (response.code) {
                    403 -> "配对码错误"
                    410 -> "配对码已过期，请在桌面刷新配对页"
                    429 -> "尝试过于频繁，请等 1 分钟"
                    else -> "HTTP ${response.code}"
                }
                throw IOException("$hint: ${text.take(200)}")
            }
            return json.parseToJsonElement(text).jsonObject
        }
    }

    private class AndroidCookieJar : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val manager = CookieManager.getInstance()
            cookies.forEach { cookie ->
                runCatching { manager.setCookie(url.toString(), cookie.toString()) }
            }
            manager.flush()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val header = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
            return header.split(";").mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) null else runCatching { Cookie.parse(url, trimmed) }.getOrNull()
            }
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
