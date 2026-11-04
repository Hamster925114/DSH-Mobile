package com.dshmobile.app.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionProfileTest {
    @Test
    fun defaultProfilesCoverTheThreeRoutes() {
        val profiles = defaultProfiles()
        assertEquals(3, profiles.size)
        assertEquals(
            listOf(ConnectionKind.LAN, ConnectionKind.TAILSCALE, ConnectionKind.FRP),
            profiles.map { it.kind },
        )
        assertTrue(profiles.all { it.baseUrl.isBlank() })
    }

    @Test
    fun schemesAreFilledByRoute() {
        assertEquals(
            "http://192.168.1.10:53985",
            withDefaultScheme(ConnectionKind.LAN, "192.168.1.10:53985"),
        )
        assertEquals(
            "https://machine.tailnet.ts.net",
            withDefaultScheme(ConnectionKind.TAILSCALE, "machine.tailnet.ts.net"),
        )
        assertEquals(
            "https://dsh.example.com",
            withDefaultScheme(ConnectionKind.FRP, "dsh.example.com"),
        )
        assertEquals(
            "http://localhost:53985",
            withDefaultScheme(ConnectionKind.CUSTOM, "localhost:53985"),
        )
        assertEquals(
            "https://already.example",
            withDefaultScheme(ConnectionKind.LAN, "https://already.example"),
        )
    }

    @Test
    fun profilesRoundTripThroughJson() {
        val json = Json { encodeDefaults = true }
        val serializer = ListSerializer(ConnectionProfile.serializer())
        val profiles = listOf(
            ConnectionProfile("lan", "家里", ConnectionKind.LAN, "http://192.168.1.10:53985"),
            ConnectionProfile("ts", "Tailscale", ConnectionKind.TAILSCALE, "https://phone.ts.net"),
            ConnectionProfile("frp", "备用", ConnectionKind.FRP, "https://dsh.example.com"),
        )
        val encoded = json.encodeToString(serializer, profiles)
        val decoded = json.decodeFromString(serializer, encoded)
        assertEquals(profiles, decoded)
    }
}
