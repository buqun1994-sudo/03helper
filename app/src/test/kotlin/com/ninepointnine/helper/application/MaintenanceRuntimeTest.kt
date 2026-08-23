package com.ninepointnine.helper.application

import com.ninepointnine.helper.application.maintenance.MaintenanceController
import com.ninepointnine.helper.application.maintenance.MaintenanceDiagnosticStore
import com.ninepointnine.helper.data.download.ArtifactCache
import com.ninepointnine.helper.domain.device.DeviceCapability
import com.ninepointnine.helper.domain.session.ComponentDescriptor
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.session.DeviceSummary
import com.ninepointnine.helper.domain.session.InstallationSession
import com.ninepointnine.helper.domain.session.InstallationSessionCommand
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.domain.session.InstallationSessionState
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.domain.session.SessionEvidence
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MaintenanceRuntimeTest {
    @Test
    fun `runtime drives a local maintenance action through structured completion`() = runTest {
        val root = Files.createTempDirectory("maintenance-runtime").toFile()
        val artifacts = root.resolve("artifacts")
        artifacts.mkdirs()
        artifacts.resolve("stale.zip.part").writeText("stale")
        val controller = MaintenanceController(
            artifactCache = ArtifactCache(artifacts),
            diagnosticStore = MaintenanceDiagnosticStore(root.resolve("diagnostics")),
        )
        val runtime = runtime(maintenanceSnapshot(), controller)

        runtime.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.CLEANUP))
        advanceUntilIdle()

        val action = runtime.session.currentSnapshot().maintenance.lastAction
        assertEquals(MaintenanceActionId.CLEANUP, action?.actionId)
        assertEquals(com.ninepointnine.helper.domain.session.MaintenanceActionStatus.SUCCEEDED, action?.status)
        assertFalse(artifacts.resolve("stale.zip.part").exists())
        runtime.close()
    }

    @Test
    fun `runtime reports missing maintenance controller instead of leaving action running`() = runTest {
        val runtime = runtime(maintenanceSnapshot(), controller = null)

        runtime.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.EXPORT_DIAGNOSTICS))
        advanceUntilIdle()

        val action = runtime.session.currentSnapshot().maintenance.lastAction
        assertEquals(com.ninepointnine.helper.domain.session.MaintenanceActionStatus.FAILED, action?.status)
        assertEquals("maintenance_controller_unavailable", action?.reasonCode)
        runtime.close()
    }

    @Test
    fun `runtime persists maintenance boundaries without exposing transient action data`() = runTest {
        val saved = mutableListOf<InstallationSessionSnapshot>()
        val runtime = runtime(
            maintenanceSnapshot(),
            controller = MaintenanceController(
                artifactCache = ArtifactCache(Files.createTempDirectory("maintenance-persist").toFile()),
                diagnosticStore = MaintenanceDiagnosticStore(
                    Files.createTempDirectory("maintenance-persist-diagnostics").toFile(),
                ),
            ),
            persist = { saved += it },
        )

        runtime.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.CLEANUP))
        advanceUntilIdle()

        assertTrue(saved.isNotEmpty())
        assertTrue(saved.all { it.state == InstallationSessionState.MAINTENANCE })
        assertTrue(saved.last().maintenance.activeAction == null)
        runtime.close()
    }

    private fun runtime(
        snapshot: InstallationSessionSnapshot,
        controller: MaintenanceController?,
        persist: (suspend (InstallationSessionSnapshot) -> Unit)? = null,
    ): InstallerRuntime = InstallerRuntime(
        session = InstallationSession(initialSnapshot = snapshot),
        createDiscoveryAdapter = { error("discovery is outside this test") },
        createConnectionAdapter = { error("connection is outside this test") },
        loadCatalog = {},
        maintenanceController = controller,
        persistMaintenanceSnapshot = persist,
        coroutineContext = UnconfinedTestDispatcher(),
    )

    private fun maintenanceSnapshot(): InstallationSessionSnapshot = InstallationSessionSnapshot(
        state = InstallationSessionState.MAINTENANCE,
        device = DeviceSummary(
            id = "vehicle-1",
            displayName = "S56_HQX",
            connectionStatus = DeviceConnectionStatus.CONFIRMED,
            androidSdk = 28,
            capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
        ),
        components = listOf(
            ComponentDescriptor("desktop", "Desktop", required = true, "1", "1 MB", "compatible"),
        ),
        evidence = SessionEvidence(installed = setOf("desktop")),
    )
}
