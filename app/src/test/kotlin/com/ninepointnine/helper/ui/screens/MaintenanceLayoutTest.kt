package com.ninepointnine.helper.ui.screens

import com.ninepointnine.helper.ui.maintenanceResultBackIntent
import com.ninepointnine.helper.ui.state.InstallUiIntent
import com.ninepointnine.helper.ui.state.InstallUiState
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.domain.session.ResultKind
import com.ninepointnine.helper.domain.session.InstallationFlow
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

    @Test
    fun `maintenance application result back stays on its selection boundary`() {
        val partial = InstallUiState.Result(
            kind = ResultKind.PARTIAL_FAILURE,
            componentResults = emptyList(),
            canContinue = true,
            canEnterMaintenance = true,
            installationFlow = InstallationFlow.MAINTENANCE_INSTALL,
        )
        val failed = partial.copy(kind = ResultKind.INSTALLATION_FAILED)
        val success = partial.copy(kind = ResultKind.SUCCESS)

        assertEquals(
            InstallUiIntent.ReturnToMaintenanceInstallationSelection,
            maintenanceResultBackIntent(MaintenanceActionId.INSTALL_APPLICATIONS, partial),
        )
        assertEquals(
            InstallUiIntent.ReturnToMaintenanceInstallationSelection,
            maintenanceResultBackIntent(MaintenanceActionId.INSTALL_APPLICATIONS, failed),
        )
        assertEquals(
            InstallUiIntent.EnterMaintenance,
            maintenanceResultBackIntent(MaintenanceActionId.INSTALL_APPLICATIONS, success),
        )
    }

    @Test
    fun `initial result cannot be routed by a stale maintenance action`() {
        val failed = InstallUiState.Result(
            kind = ResultKind.INSTALLATION_FAILED,
            componentResults = emptyList(),
            canContinue = true,
            installationFlow = InstallationFlow.INITIAL_INSTALL,
        )

        assertEquals(
            InstallUiIntent.ReturnToSelection,
            maintenanceResultBackIntent(MaintenanceActionId.INSTALL_APPLICATIONS, failed),
        )
    }
}
