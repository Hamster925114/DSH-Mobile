package com.dshmobile.app.data

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import com.dshmobile.app.BuildConfig
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
    val deviceId: String? = null,
)

data class DeviceInfo(
    val id: String,
    val name: String,
    val platform: String,
    val appVersion: String,
    val createdAt: String,
    val lastSeenAt: String?,
    val expiresAt: String,
    val revoked: Boolean,
)

data class AuditEvent(
    val ts: String,
    val event: String,
    val deviceId: String?,
    val deviceName: String?,
    val remote: String?,
    val detail: String?,
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
class DshApi(private val context: Context) {
    private val deviceTokens = DeviceTokenStore(context)

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

            val pairBody = buildJsonObject {
                put("code", code)
                put("base", base)
                put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                put("platform", "android")
                put("appVersion", BuildConfig.VERSION_NAME)
            }
            val pairJson = postJson("$base/mobile/pair", pairBody)
            val tokenUrl = pairJson["url"]?.jsonPrimitive?.contentOrNull
                ?: throw IOException("配对响应缺少 url")
            val deviceToken = pairJson["deviceToken"]?.jsonPrimitive?.contentOrNull
            val deviceId = pairJson["deviceId"]?.jsonPrimitive?.contentOrNull

            if (!deviceToken.isNullOrBlank() && !deviceId.isNullOrBlank()) {
                // The proxy authenticates every subsequent request with this
                // cookie. Set it before the first DSH request so the pairing
                // redirect never hits the gate without a device identity.
                CookieManager.getInstance().setCookie(
                    "$base/",
                    "dsh_mobile_device=$deviceToken; Path=/; SameSite=Lax",
                )
                deviceTokens.save(base, deviceId, deviceToken)
            }

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
            PairResult.Success(PairOutcome(base, count, hello, deviceId))
        } catch (error: Exception) {
            val message = error.message ?: "配对失败"
            PairResult.Failure(
                if (message.contains("unknown-base")) {
                    "桌面 bridge 未配置这个地址。请在 profile 的 endpoints 里加入 $rawBaseUrl，或改用配对页列出的地址。"
                } else {
                    message
                },
            )
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

    fun hasDeviceToken(baseUrl: String): Boolean = runCatching {
        deviceTokens.load(DshUrl.normalize(baseUrl)) != null
    }.getOrDefault(false)

    fun currentDeviceId(baseUrl: String): String? = runCatching {
        deviceTokens.load(DshUrl.normalize(baseUrl))?.deviceId
    }.getOrNull()

    fun clearDeviceToken() = deviceTokens.clear()

    fun clearDeviceSession() {
        deviceTokens.clear()
        clearCookies()
    }

    /**
     * Exchange the encrypted device token for a fresh DSH browser session.
     * Used when the 30-day cookie expired or the app was reinstalled with a
     * restored token. A 401 means the device was revoked.
     */
    /**
     * Check a device token with the bridge. `null` means the bridge could not
     * be reached, not that the token is revoked.
     */
    suspend fun validateDeviceToken(baseUrl: String): Boolean? = withContext(Dispatchers.IO) {
        try {
            val base = DshUrl.normalize(baseUrl)
            val saved = deviceTokens.load(base) ?: return@withContext null
            val request = Request.Builder()
                .url("$base/mobile/device/me")
                .header("Authorization", "Bearer ${saved.token}")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> true
                    response.code == 401 -> false
                    else -> null
                }
            }
        } catch (error: Exception) {
            null
        }
    }

    suspend fun refreshSession(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val base = DshUrl.normalize(baseUrl)
            val saved = deviceTokens.load(base) ?: return@withContext false
            val body = buildJsonObject { put("base", base) }
            val jsonResponse = postJson(
                "$base/mobile/token/exchange",
                body,
                bearer = saved.token,
            )
            val tokenUrl = jsonResponse["url"]?.jsonPrimitive?.contentOrNull
                ?: return@withContext false
            CookieManager.getInstance().setCookie(
                "$base/",
                "dsh_mobile_device=${saved.token}; Path=/; SameSite=Lax",
            )
            client.newCall(Request.Builder().url(tokenUrl).get().build()).execute().use { response ->
                if (response.code !in 200..399) {
                    if (response.code == 401) deviceTokens.clear()
                    return@withContext false
                }
                response.headers.values("Set-Cookie").forEach { cookie ->
                    runCatching { CookieManager.getInstance().setCookie("$base/", cookie) }
                }
                CookieManager.getInstance().flush()
            }
            true
        } catch (error: Exception) {
            false
        }
    }

    suspend fun listDevices(baseUrl: String): Result<List<DeviceInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = DshUrl.normalize(baseUrl)
            val request = Request.Builder().url("$base/mobile/devices").get().build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${text.take(300)}")
                val root = json.parseToJsonElement(text).jsonObject
                val array = root["devices"] as? JsonArray ?: JsonArray(emptyList())
                array.mapNotNull { element ->
                    val item = element as? JsonObject ?: return@mapNotNull null
                    DeviceInfo(
                        id = item["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        name = item["name"]?.jsonPrimitive?.contentOrNull ?: "Android 设备",
                        platform = item["platform"]?.jsonPrimitive?.contentOrNull ?: "android",
                        appVersion = item["appVersion"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        createdAt = item["createdAt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        lastSeenAt = item["lastSeenAt"]?.jsonPrimitive?.contentOrNull,
                        expiresAt = item["expiresAt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        revoked = item["revoked"]?.jsonPrimitive?.booleanOrNull ?: false,
                    )
                }
            }
        }
    }

    suspend fun listAudit(baseUrl: String, limit: Int = 50): Result<List<AuditEvent>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = DshUrl.normalize(baseUrl)
                val request = Request.Builder()
                    .url("$base/mobile/audit?limit=${limit.coerceIn(1, 500)}")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}: ${text.take(300)}")
                    }
                    val root = json.parseToJsonElement(text).jsonObject
                    val array = root["events"] as? JsonArray ?: JsonArray(emptyList())
                    array.mapNotNull { element ->
                        val item = element as? JsonObject ?: return@mapNotNull null
                        AuditEvent(
                            ts = item["ts"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                            event = item["event"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                            deviceId = item["deviceId"]?.jsonPrimitive?.contentOrNull,
                            deviceName = item["deviceName"]?.jsonPrimitive?.contentOrNull,
                            remote = item["remote"]?.jsonPrimitive?.contentOrNull,
                            detail = item["detail"]?.jsonPrimitive?.contentOrNull,
                        )
                    }
                }
            }
        }

    suspend fun revokeDevice(baseUrl: String, deviceId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = DshUrl.normalize(baseUrl)
                val body = buildJsonObject { put("id", deviceId) }
                postJson("$base/mobile/devices/revoke", body)
                val own = deviceTokens.load(base)
                if (own?.deviceId == deviceId) {
                    deviceTokens.clear()
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                }
                Unit
            }
        }

    suspend fun renameDevice(baseUrl: String, deviceId: String, name: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = DshUrl.normalize(baseUrl)
                val body = buildJsonObject {
                    put("id", deviceId)
                    put("name", name)
                }
                postJson("$base/mobile/devices/rename", body)
                Unit
            }
        }

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

    private fun postJson(url: String, body: JsonObject, bearer: String? = null): JsonObject {
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        val request = builder.build()
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
