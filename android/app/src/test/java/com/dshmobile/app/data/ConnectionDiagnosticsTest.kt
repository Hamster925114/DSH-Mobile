package com.dshmobile.app.data

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
}
