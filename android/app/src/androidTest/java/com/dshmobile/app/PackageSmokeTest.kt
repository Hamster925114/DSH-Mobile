package com.dshmobile.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Small device-side smoke test. It verifies the installed package identity and
 * version without requiring a network or DSH desktop.
 */
@RunWith(AndroidJUnit4::class)
class PackageSmokeTest {
    @Test
    fun packageIdentityMatchesBuild() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.dshmobile.app", context.packageName)
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals(BuildConfig.VERSION_NAME, info.versionName)
    }
}
