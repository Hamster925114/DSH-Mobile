package com.dshmobile.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLDecoder

/**
 * QR / deep-link payload for pairing.
 *
 * Supported forms:
 * - `{"v":1,"base":"https://...","code":"ABCD..."}`
 * - `dshmobile://pair?base=https%3A%2F%2F...&code=ABCD...`
 */
data class PairingPayload(
    val baseUrl: String,
    val code: String,
    val kind: ConnectionKind? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private fun parseKind(raw: String?): ConnectionKind? {
            if (raw.isNullOrBlank()) return null
            return ConnectionKind.entries.firstOrNull {
                it.name.equals(raw, ignoreCase = true)
            }
        }

        fun parse(raw: String): PairingPayload? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            return if (text.startsWith("{")) parseJson(text) else parseDeepLink(text)
        }

        private fun parseJson(text: String): PairingPayload? = runCatching {
            val obj = json.parseToJsonElement(text).jsonObject
            val base = obj["base"]?.jsonPrimitive?.content
                ?: obj["baseUrl"]?.jsonPrimitive?.content
            val code = obj["code"]?.jsonPrimitive?.content
            if (base.isNullOrBlank() || code.isNullOrBlank()) {
                null
            } else {
                PairingPayload(
                    baseUrl = base,
                    code = code,
                    kind = parseKind(obj["kind"]?.jsonPrimitive?.content),
                )
            }
        }.getOrNull()

        private fun parseDeepLink(text: String): PairingPayload? = runCatching {
            val uri = URI(text)
            if (uri.scheme != "dshmobile") {
                null
            } else {
                val params = uri.rawQuery.orEmpty()
                    .split("&")
                    .mapNotNull { part ->
                        val index = part.indexOf('=')
                        if (index <= 0) {
                            null
                        } else {
                            URLDecoder.decode(part.substring(0, index), "UTF-8") to
                                URLDecoder.decode(part.substring(index + 1), "UTF-8")
                        }
                    }
                    .toMap()
                val base = params["base"]
                val code = params["code"]
                if (base.isNullOrBlank() || code.isNullOrBlank()) {
                    null
                } else {
                    PairingPayload(
                        baseUrl = base,
                        code = code,
                        kind = parseKind(params["kind"]),
                    )
                }
            }
        }.getOrNull()
    }
}
