package com.dshmobile.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingPayloadTest {
    @Test
    fun parsesJsonPayload() {
        val payload = PairingPayload.parse(
            """{"v":1,"base":"https://machine.tailnet.ts.net","code":"ABCD1234EF567890"}""",
        )
        assertEquals("https://machine.tailnet.ts.net", payload?.baseUrl)
        assertEquals("ABCD1234EF567890", payload?.code)
    }

    @Test
    fun parsesJsonBaseUrlAlias() {
        val payload = PairingPayload.parse(
            """{"baseUrl":"http://192.168.1.10:53985","code":"0011223344556677"}""",
        )
        assertEquals("http://192.168.1.10:53985", payload?.baseUrl)
    }

    @Test
    fun parsesDeepLink() {
        val payload = PairingPayload.parse(
            "dshmobile://pair?base=https%3A%2F%2Fmachine.tailnet.ts.net&code=ABCD1234&kind=tailscale",
        )
        assertEquals("https://machine.tailnet.ts.net", payload?.baseUrl)
        assertEquals("ABCD1234", payload?.code)
        assertEquals(ConnectionKind.TAILSCALE, payload?.kind)
    }

    @Test
    fun parsesKindFromJson() {
        val payload = PairingPayload.parse(
            """{"base":"http://192.168.1.10:53985","code":"ABCD1234","kind":"lan"}""",
        )
        assertEquals(ConnectionKind.LAN, payload?.kind)
    }

    @Test
    fun ignoresUnknownKind() {
        val payload = PairingPayload.parse(
            """{"base":"https://example.com","code":"ABCD1234","kind":"unknown"}""",
        )
        assertEquals("https://example.com", payload?.baseUrl)
        assertNull(payload?.kind)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(PairingPayload.parse("not a pairing payload"))
    }

    @Test
    fun rejectsWrongScheme() {
        assertNull(PairingPayload.parse("https://example.com/?base=x&code=y"))
    }

    @Test
    fun rejectsMissingCode() {
        assertNull(PairingPayload.parse("""{"base":"https://example.com"}"""))
    }
}
