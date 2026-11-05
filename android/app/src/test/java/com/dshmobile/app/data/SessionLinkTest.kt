package com.dshmobile.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionLinkTest {
    @Test
    fun keepsFullDshSessionId() {
        assertEquals(
            "session-81378a85-b746-48f7-b895-674b3fb92645",
            SessionLink.normalize("session-81378a85-b746-48f7-b895-674b3fb92645"),
        )
    }

    @Test
    fun prefixesBareSessionId() {
        assertEquals("session-abc", SessionLink.normalize("abc"))
    }

    @Test
    fun parsesDeepLinkWithFullId() {
        assertEquals(
            "session-81378a85-b746-48f7-b895-674b3fb92645",
            SessionLink.fromDeepLink(
                "dshmobile://session/session-81378a85-b746-48f7-b895-674b3fb92645",
            ),
        )
    }

    @Test
    fun parsesDeepLinkWithBareId() {
        assertEquals(
            "session-abc",
            SessionLink.fromDeepLink("dshmobile://session/abc"),
        )
    }

    @Test
    fun rejectsOtherSchemesAndHosts() {
        assertNull(SessionLink.fromDeepLink("https://session/abc"))
        assertNull(SessionLink.fromDeepLink("dshmobile://file/abc"))
        assertNull(SessionLink.fromDeepLink("dshmobile://session/"))
        assertNull(SessionLink.fromDeepLink(""))
    }
}
