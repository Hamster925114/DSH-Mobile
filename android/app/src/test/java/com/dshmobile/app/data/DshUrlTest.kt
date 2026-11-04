package com.dshmobile.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DshUrlTest {
    @Test
    fun normalizesTrailingSlash() {
        assertEquals("https://example.com", DshUrl.normalize("https://example.com/"))
    }

    @Test
    fun keepsPort() {
        assertEquals("http://127.0.0.1:53985", DshUrl.normalize("http://127.0.0.1:53985"))
    }

    @Test
    fun trimsWhitespace() {
        assertEquals(
            "https://machine.tailnet.ts.net",
            DshUrl.normalize("  https://machine.tailnet.ts.net/  "),
        )
    }

    @Test
    fun keepsPathPrefix() {
        assertEquals("https://example.com/dsh", DshUrl.normalize("https://example.com/dsh/"))
    }

    @Test
    fun rejectsMissingScheme() {
        assertThrows(IllegalArgumentException::class.java) {
            DshUrl.normalize("machine.tailnet.ts.net")
        }
    }

    @Test
    fun rejectsQueryString() {
        assertThrows(IllegalArgumentException::class.java) {
            DshUrl.normalize("https://example.com?token=1")
        }
    }
}
