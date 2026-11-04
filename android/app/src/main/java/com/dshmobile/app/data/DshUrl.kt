package com.dshmobile.app.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Normalization and validation for the DSH base URL. */
object DshUrl {
    /**
     * Accepts `https://host`, `https://host/`, `http://127.0.0.1:53985`, etc.
     * Rejects schemes other than http(s), query strings and fragments.
     */
    fun normalize(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            "地址必须以 http:// 或 https:// 开头"
        }
        val parsed = trimmed.toHttpUrlOrNull() ?: throw IllegalArgumentException("地址格式不正确")
        require(parsed.query == null && parsed.fragment == null) {
            "地址不能带查询参数或片段"
        }
        return parsed.toString().trimEnd('/')
    }
}
