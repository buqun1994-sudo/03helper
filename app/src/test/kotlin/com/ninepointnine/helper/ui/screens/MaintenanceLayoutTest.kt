package com.ninepointnine.helper.ui.screens

import com.ninepointnine.helper.R
import com.ninepointnine.helper.ui.buildIconRequests
import com.ninepointnine.helper.ui.maintenanceResultBackIntent
import com.ninepointnine.helper.ui.state.InstallUiIntent
import com.ninepointnine.helper.ui.state.InstallUiState
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.domain.session.MaintenanceActionStatus
import com.ninepointnine.helper.domain.session.MaintenanceApplicationActionId
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.domain.session.InstallationSessionState
import com.ninepointnine.helper.domain.session.ComponentDescriptor
import com.ninepointnine.helper.domain.session.MaintenanceSnapshot
import com.ninepointnine.helper.domain.session.ManagedApplicationStatus
import com.ninepointnine.helper.ui.state.MaintenanceApplicationDetailsRow
import com.ninepointnine.helper.ui.state.MaintenanceApplicationFeedback
import com.ninepointnine.helper.ui.state.MaintenanceApplicationRow
import com.ninepointnine.helper.domain.session.ResultKind
import com.ninepointnine.helper.domain.session.InstallationFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceLayoutTest {
    @Test
    fun `maintenance grid keeps compact phones single column`() {
        assertEquals(1, maintenanceColumnCount(320f))
        assertEquals(1, maintenanceColumnCount(360f))
        assertEquals(1, maintenanceColumnCount(390f))
        assertEquals(1, maintenanceColumnCount(412f))
        assertEquals(1, maintenanceColumnCount(430f))
        assertEquals(1, maintenanceColumnCount(599.99f))
        assertEquals(2, maintenanceColumnCount(600f))
        assertEquals(2, maintenanceColumnCount(840f))
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

    @Test
    fun `managed application card exposes six actions in the required order`() {
        assertEquals(
            listOf(
                MaintenanceApplicationActionId.START,
                MaintenanceApplicationActionId.FORCE_STOP,
                MaintenanceApplicationActionId.CLEAR_DATA,
                MaintenanceApplicationActionId.AUTHORIZE,
                MaintenanceApplicationActionId.UNINSTALL,
                MaintenanceApplicationActionId.DETAILS,
            ),
            MANAGED_APPLICATION_ACTIONS,
        )
    }

    @Test
    fun `managed application expansion toggles one identity at a time`() {
        assertEquals("com.example.first", nextExpandedApplicationId(null, "com.example.first"))
        assertEquals(null, nextExpandedApplicationId("com.example.first", "com.example.first"))
        assertEquals(
            "com.example.second",
            nextExpandedApplicationId("com.example.first", "com.example.second"),
        )
    }

    @Test
    fun `any running application action disables every application card`() {
        assertTrue(applicationActionsEnabled(null))
        assertTrue(
            applicationActionsEnabled(
                MaintenanceApplicationFeedback(
                    packageName = "com.example.app",
                    actionId = MaintenanceApplicationActionId.START,
                    status = MaintenanceActionStatus.SUCCEEDED,
                ),
            ),
        )
        assertFalse(
            applicationActionsEnabled(
                MaintenanceApplicationFeedback(
                    packageName = "com.example.app",
                    actionId = MaintenanceApplicationActionId.CLEAR_DATA,
                    status = MaintenanceActionStatus.RUNNING,
                ),
            ),
        )
    }

    @Test
    fun `authorization dialog closes only after the matching write action settles`() {
        val application = MaintenanceApplicationRow(
            componentId = "app-player",
            displayName = "Player",
            packageName = "com.example.player",
            installed = true,
        )
        val pending = application to MaintenanceApplicationActionId.AUTHORIZE

        assertFalse(
            shouldDismissAuthorizationDialog(
                pending,
                MaintenanceApplicationFeedback(
                    packageName = application.packageName,
                    actionId = MaintenanceApplicationActionId.INSPECT_AUTHORIZATION,
                    status = MaintenanceActionStatus.SUCCEEDED,
                ),
            ),
        )
        assertFalse(
            shouldDismissAuthorizationDialog(
                pending,
                MaintenanceApplicationFeedback(
                    packageName = application.packageName,
                    actionId = MaintenanceApplicationActionId.AUTHORIZE,
                    status = MaintenanceActionStatus.RUNNING,
                ),
            ),
        )
        assertTrue(
            shouldDismissAuthorizationDialog(
                pending,
                MaintenanceApplicationFeedback(
                    packageName = application.packageName,
                    actionId = MaintenanceApplicationActionId.AUTHORIZE,
                    status = MaintenanceActionStatus.SUCCEEDED,
                    resultCode = "authorization_partially_succeeded",
                ),
            ),
        )
        assertTrue(
            shouldDismissAuthorizationDialog(
                pending,
                MaintenanceApplicationFeedback(
                    packageName = application.packageName,
                    actionId = MaintenanceApplicationActionId.AUTHORIZE,
                    status = MaintenanceActionStatus.FAILED,
                    reasonCode = "authorization_runtime_permission_write_failed",
                ),
            ),
        )
    }

    @Test
    fun `third party inventory never enters the apk icon request chain`() {
        val requests = buildIconRequests(
            InstallationSessionSnapshot(
                state = InstallationSessionState.MAINTENANCE,
                components = listOf(
                    ComponentDescriptor(
                        id = "desktop",
                        displayName = "03桌面",
                        required = true,
                    ),
                ),
                maintenance = MaintenanceSnapshot(
                    managedApplications = listOf(
                        ManagedApplicationStatus(
                            componentId = "app-third-party",
                            packageName = "com.example.thirdparty",
                            installed = true,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf("desktop"), requests.map { it.componentId })
    }

    @Test
    fun `application details are matched by package identity`() {
        val application = MaintenanceApplicationRow(
            componentId = "app-live-row",
            displayName = "Player",
            packageName = "com.example.player",
            installed = true,
        )
        val details = MaintenanceApplicationDetailsRow(
            componentId = "app-details-row",
            displayName = "Player",
            packageName = "com.example.player",
            versionLabel = null,
            versionCode = null,
            fileSizeBytes = null,
            installTimeEpochMillis = null,
            updateTimeEpochMillis = null,
            filePath = null,
            uid = null,
        )

        assertTrue(applicationDetailsMatch(details, application))
        assertFalse(applicationDetailsMatch(details.copy(packageName = "com.example.other"), application))
    }

    @Test
    fun `successful uninstall uses the uninstall completion message`() {
        assertEquals(
            R.string.maintenance_uninstall_success,
            successfulApplicationActionMessage(
                MaintenanceApplicationFeedback(
                    packageName = "com.example.app",
                    actionId = MaintenanceApplicationActionId.UNINSTALL,
                    status = MaintenanceActionStatus.SUCCEEDED,
                ),
            ),
        )
    }
}
