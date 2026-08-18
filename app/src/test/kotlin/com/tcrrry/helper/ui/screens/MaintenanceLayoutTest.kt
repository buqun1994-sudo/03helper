package com.tcrrry.helper.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class MaintenanceLayoutTest {
    @Test
    fun `maintenance grid changes exactly at 360 dp`() {
        assertEquals(1, maintenanceColumnCount(320f))
        assertEquals(1, maintenanceColumnCount(359.99f))
        assertEquals(2, maintenanceColumnCount(360f))
        assertEquals(2, maintenanceColumnCount(430f))
    }
}
