package app.foscal.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VendorSettingsTest {

    // The bug this guards: every candidate counted as present, so every phone got the
    // "This phone kills background apps" warning.
    @Test
    fun `a phone with none of the vendor screens has no autostart screen`() {
        assertNull(VendorSettings.firstExisting(VendorSettings.CANDIDATES) { _, _ -> false })
    }

    @Test
    fun `the first screen that exists is the one offered`() {
        val found = VendorSettings.firstExisting(VendorSettings.CANDIDATES) { pkg, _ ->
            pkg == "com.coloros.safecenter"
        }
        assertEquals(
            "com.coloros.safecenter" to
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            found,
        )
    }
}
