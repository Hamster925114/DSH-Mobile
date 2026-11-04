package com.dshmobile.app.data

import kotlinx.serialization.Serializable
import java.util.UUID

/** How a saved endpoint is expected to reach the desktop. */
@Serializable
enum class ConnectionKind {
    LAN,
    TAILSCALE,
    FRP,
    CUSTOM,
}

/** A saved phone-to-desktop endpoint. One profile is active at a time. */
@Serializable
data class ConnectionProfile(
    val id: String,
    val label: String,
    val kind: ConnectionKind,
    val baseUrl: String = "",
) {
    val kindLabel: String
        get() = when (kind) {
            ConnectionKind.LAN -> "局域网"
            ConnectionKind.TAILSCALE -> "Tailscale"
            ConnectionKind.FRP -> "frp + VPS"
            ConnectionKind.CUSTOM -> "自定义"
        }
}

fun newProfileId(): String = UUID.randomUUID().toString()

/**
 * Three empty slots make the three supported routes visible before the first
 * pairing. Empty slots are saved only after the user edits them.
 */
fun defaultProfiles(): List<ConnectionProfile> = listOf(
    ConnectionProfile(id = "lan", label = "局域网", kind = ConnectionKind.LAN),
    ConnectionProfile(id = "tailscale", label = "Tailscale", kind = ConnectionKind.TAILSCALE),
    ConnectionProfile(id = "frp", label = "frp + VPS", kind = ConnectionKind.FRP),
)

fun baseUrlHint(kind: ConnectionKind): String = when (kind) {
    ConnectionKind.LAN -> "http://192.168.1.10:53985"
    ConnectionKind.TAILSCALE -> "https://<machine>.<tailnet>.ts.net"
    ConnectionKind.FRP -> "https://dsh.example.com"
    ConnectionKind.CUSTOM -> "http://192.168.1.10:53985"
}

/**
 * Let users paste `192.168.1.10:53985` or `machine.ts.net` without a scheme.
 * LAN/custom default to plain HTTP; Tailscale/frp default to HTTPS.
 */
fun withDefaultScheme(kind: ConnectionKind, raw: String): String {
    val value = raw.trim()
    if (value.contains("://")) return value
    return when (kind) {
        ConnectionKind.LAN, ConnectionKind.CUSTOM -> "http://$value"
        ConnectionKind.TAILSCALE, ConnectionKind.FRP -> "https://$value"
    }
}
