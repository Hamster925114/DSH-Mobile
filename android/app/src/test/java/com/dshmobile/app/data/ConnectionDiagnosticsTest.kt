package com.dshmobile.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionDiagnosticsTest {
    @Test
    fun parsesHttpsTarget() {
        val target = parseDiagnosticTarget("https://hamster.tail52bb47.ts.net")
        assertEquals("https", target?.scheme)
        assertEquals("hamster.tail52bb47.ts.net", target?.host)
        assertEquals(443, target?.port)
        assertEquals(true, target?.secure)
    }

    @Test
    fun parsesLanTargetWithExplicitPort() {
        val target = parseDiagnosticTarget("http://192.168.1.10:53985")
        assertEquals("http", target?.scheme)
        assertEquals(53985, target?.port)
        assertEquals(false, target?.secure)
    }

    @Test
    fun rejectsUnsupportedScheme() {
        assertNull(parseDiagnosticTarget("ftp://example.com"))
    }

    @Test
    fun mapsHttpFailuresToHints() {
        assertTrue(hintForHttpStatus(401).orEmpty().contains("Cookie"))
        assertTrue(hintForHttpStatus(403).orEmpty().contains("trustedHosts"))
        assertTrue(hintForHttpStatus(404).orEmpty().contains("bridge"))
        assertNull(hintForHttpStatus(200))
    }

    @Test
    fun runsHttpAndApiStepsAgainstMockServer() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"version":"0.0.1","dsh":"0.2.1-rc.3"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"result":{"ok":true,"value":[]}}"""),
        )
        server.start()
        try {
            val diagnostics = ConnectionDiagnostics(cookieLookup = { "dsh-auth=test" })
            val steps = diagnostics.run(server.url("/").toString())
            val byId = steps.associateBy { it.id }
            assertEquals(DiagnosticStatus.PASS, byId.getValue("url").status)
            assertEquals(DiagnosticStatus.PASS, byId.getValue("dns").status)
            assertEquals(DiagnosticStatus.PASS, byId.getValue("tcp").status)
            assertEquals(DiagnosticStatus.SKIP, byId.getValue("tls").status)
            assertEquals(DiagnosticStatus.PASS, byId.getValue("http").status)
            assertEquals(DiagnosticStatus.PASS, byId.getValue("cookie").status)
            assertEquals(DiagnosticStatus.PASS, byId.getValue("api").status)
        } finally {
            server.shutdown()
        }
    }
}
