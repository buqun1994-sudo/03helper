package com.ninepointnine.helper.ui.screens

import com.ninepointnine.helper.domain.session.MaintenanceActionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceLayoutTest {
    @Test
    fun `maintenance grid changes exactly at 360 dp`() {
        assertEquals(1, maintenanceColumnCount(320f))
        assertEquals(1, maintenanceColumnCount(359.99f))
        assertEquals(2, maintenanceColumnCount(360f))
        assertEquals(2, maintenanceColumnCount(430f))
    }

    @Test
    fun `disconnected maintenance leaves only local actions enabled`() {
        assertTrue(maintenanceActionEnabled(MaintenanceActionId.CHECK_UPDATES, connected = false, busy = false))
        assertTrue(maintenanceActionEnabled(MaintenanceActionId.CLEANUP, connected = false, busy = false))
        assertTrue(maintenanceActionEnabled(MaintenanceActionId.EXPORT_DIAGNOSTICS, connected = false, busy = false))
        assertFalse(maintenanceActionEnabled(MaintenanceActionId.REPAIR_CONFIGURATION, connected = false, busy = false))
        assertFalse(maintenanceActionEnabled(MaintenanceActionId.LAUNCH_DESKTOP, connected = false, busy = false))
        assertFalse(maintenanceActionEnabled(MaintenanceActionId.CLEANUP, connected = true, busy = true))
    }
}
