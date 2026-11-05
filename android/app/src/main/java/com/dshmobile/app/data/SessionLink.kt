package com.dshmobile.app.data

import java.net.URI

/**
 * Deep links and notification payloads use DSH's full session id, which already
 * starts with `session-`. Keep the parser tolerant of both forms so older
 * notifications that omitted the prefix still open the right conversation.
 */
object SessionLink {
    private const val SCHEME = "dshmobile"
    private const val HOST = "session"
    private const val PREFIX = "session-"

    fun normalize(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (value.startsWith(PREFIX)) value else PREFIX + value
    }

    fun fromDeepLink(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        if (!uri.host.equals(HOST, ignoreCase = true)) return null
        val path = uri.path?.trim('/')?.takeIf { it.isNotEmpty() } ?: return null
        return normalize(path.substringAfterLast('/'))
    }
}
