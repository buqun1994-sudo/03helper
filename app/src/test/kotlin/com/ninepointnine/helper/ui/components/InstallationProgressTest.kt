package com.ninepointnine.helper.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallationProgressTest {
    @Test
    fun `predicted progress reaches milestones without claiming completion`() {
        assertEquals(0f, predictedInstallProgress(0L), 0.0001f)
        assertEquals(0.70f, predictedInstallProgress(1_600L), 0.0001f)
        assertEquals(0.90f, predictedInstallProgress(4_800L), 0.0001f)
        assertTrue(predictedInstallProgress(10_800L) > 0.93f)
        assertTrue(predictedInstallProgress(Long.MAX_VALUE) <= 0.96f)
        assertTrue(predictedInstallProgress(Long.MAX_VALUE) < 1f)
    }
}
