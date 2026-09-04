package com.ninepointnine.helper.application

import com.ninepointnine.helper.application.maintenance.MaintenanceController
import com.ninepointnine.helper.application.maintenance.MaintenanceDiagnosticStore
import com.ninepointnine.helper.application.device.DeviceConnectionSessionAdapter
import com.ninepointnine.helper.application.device.DeviceDiscoverySessionAdapter
import com.ninepointnine.helper.data.download.ArtifactCache
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactSource
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.CompatibilityRange
import com.ninepointnine.helper.domain.device.DeviceCapability
import com.ninepointnine.helper.domain.device.AdbCommandGateway
import com.ninepointnine.helper.domain.device.ApkDeclarationMetadata
import com.ninepointnine.helper.domain.device.AuthorizationPlan
import com.ninepointnine.helper.domain.device.ConnectedDevice
import com.ninepointnine.helper.domain.device.DeviceActionConnectionLease
import com.ninepointnine.helper.domain.device.DeviceActionFailure
import com.ninepointnine.helper.domain.device.DeviceConnectionAttempt
import com.ninepointnine.helper.domain.device.DeviceConnectionCheck
import com.ninepointnine.helper.domain.device.DeviceConnectionFactory
import com.ninepointnine.helper.domain.device.DeviceDiscovery
import com.ninepointnine.helper.domain.device.DeviceDiscoveryResult
import com.ninepointnine.helper.domain.device.DeviceEndpoint
import com.ninepointnine.helper.domain.device.DeviceIdentity
import com.ninepointnine.helper.domain.device.DeviceInstallResult
import com.ninepointnine.helper.domain.device.DeviceShortcut
import com.ninepointnine.helper.domain.device.DeviceShortcutResult
import com.ninepointnine.helper.domain.device.InstallableArtifact
import com.ninepointnine.helper.domain.device.InstalledApplicationIconResult
import com.ninepointnine.helper.domain.device.MaintenanceAuthorizationResult
import com.ninepointnine.helper.domain.device.MaintenanceCommandGateway
import com.ninepointnine.helper.domain.device.MaintenanceDeviceResult
import com.ninepointnine.helper.domain.device.ManagedApplicationDetailsProbeResult
import com.ninepointnine.helper.domain.device.ManagedApplicationProbe
import com.ninepointnine.helper.domain.device.ManagedApplicationsResult
import com.ninepointnine.helper.domain.device.ManagedComponent
import com.ninepointnine.helper.domain.session.ComponentDescriptor
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.session.DeviceSummary
import com.ninepointnine.helper.domain.session.InstallationSession
import com.ninepointnine.helper.domain.session.InstallationSessionCommand
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.domain.session.InstallationSessionState
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.domain.session.MaintenanceActionStatus
import com.ninepointnine.helper.domain.session.MaintenanceApplicationActionId
import com.ninepointnine.helper.domain.session.MaintenanceInventoryState
import com.ninepointnine.helper.domain.session.MaintenanceSnapshot
import com.ninepointnine.helper.domain.session.ManagedApplicationStatus
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import com.ninepointnine.helper.domain.session.InstallationStrategy
import com.ninepointnine.helper.domain.session.SessionEvidence
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MaintenanceRuntimeTest {
    @Test
    fun `foreground application action cancels the remaining icon hydration queue`() = runTest {
        val device = ConnectedDevice(
            endpoint = DeviceEndpoint("192.0.2.1"),
            identity = DeviceIdentity("vehicle-1", "S56_HQX", 28),
            capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
        )
        val applications = listOf(
            ManagedApplicationProbe("app-player", "com.example.player", installed = true),
            ManagedApplicationProbe("app-radio", "com.example.radio", installed = true),
        )
        val sharedAdbLock = Mutex()
        val firstIconStarted = CompletableDeferred<Unit>()
        val firstIconCancelled = CompletableDeferred<Unit>()
        val foregroundActionCompleted = CompletableDeferred<Unit>()
        val iconRequests = mutableListOf<String>()
        val gateway = object : AdbCommandGateway, MaintenanceCommandGateway {
            override suspend fun installBatch(
                artifacts: List<InstallableArtifact>,
                strategy: InstallationStrategy,
            ): DeviceInstallResult = error("installation is outside this test")

            override suspend fun runShortcut(
                shortcut: DeviceShortcut,
                selectedComponentIds: Set<String>,
                authorizationPlan: AuthorizationPlan,
            ): DeviceShortcutResult = error("shortcut is outside this test")

            override suspend fun repairAuthorization(
                manifests: List<ArtifactManifest>,
                declarationsByComponent: Map<String, ApkDeclarationMetadata>,
            ): MaintenanceDeviceResult = error("authorization repair is outside this test")

            override suspend fun inspectManagedApplications(
                components: List<ManagedComponent>,
            ): ManagedApplicationsResult = ManagedApplicationsResult.Completed(applications)

            override suspend fun inspectInstalledApplicationInventory(
                components: List<ManagedComponent>,
            ): ManagedApplicationsResult = ManagedApplicationsResult.Completed(applications)

            override suspend fun inspectAllInstalledApplications(): ManagedApplicationsResult =
                ManagedApplicationsResult.Completed(applications)

            override suspend fun inspectInstalledApplicationIcon(
                packageName: String,
            ): InstalledApplicationIconResult = sharedAdbLock.withLock {
                iconRequests += packageName
                firstIconStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    firstIconCancelled.complete(Unit)
                }
            }

            override suspend fun launchManagedComponent(component: ManagedComponent): MaintenanceDeviceResult =
                error("managed launch is outside this test")

            override suspend fun inspectComponentAuthorization(
                components: List<ManagedComponent>,
                installedApplications: List<ManagedApplicationProbe>,
            ): MaintenanceAuthorizationResult = MaintenanceAuthorizationResult.Completed(emptyList())

            override suspend fun performApplicationAction(
                component: ManagedComponent,
                actionId: MaintenanceApplicationActionId,
            ): MaintenanceDeviceResult = sharedAdbLock.withLock {
                foregroundActionCompleted.complete(Unit)
                MaintenanceDeviceResult.Completed("component_stopped")
            }

            override suspend fun inspectManagedApplicationDetails(
                component: ManagedComponent,
            ): ManagedApplicationDetailsProbeResult = ManagedApplicationDetailsProbeResult.Failed(
                DeviceActionFailure("details_are_outside_this_test", retryable = false),
            )
        }
        val lease = object : DeviceActionConnectionLease {
            override val device: ConnectedDevice = device
            override val commandGateway: AdbCommandGateway = gateway
            override suspend fun check(): DeviceConnectionCheck = DeviceConnectionCheck(true)
            override fun close() = Unit
        }
        val controller = MaintenanceController(
            artifactCache = ArtifactCache(Files.createTempDirectory("maintenance-icon-hydration").toFile()),
            diagnosticStore = MaintenanceDiagnosticStore(
                Files.createTempDirectory("maintenance-icon-diagnostics").toFile(),
            ),
        )
        val runtime = InstallerRuntime(
            session = InstallationSession(
                initialSnapshot = maintenanceSnapshot().copy(
                    device = maintenanceSnapshot().device?.copy(
                        connectionStatus = DeviceConnectionStatus.DISCONNECTED,
                    ),
                ),
            ),
            createDiscoveryAdapter = { port ->
                DeviceDiscoverySessionAdapter(
                    discovery = object : DeviceDiscovery {
                        override suspend fun discover(
                            onDevice: suspend (ConnectedDevice) -> Unit,
                        ): DeviceDiscoveryResult {
                            onDevice(device)
                            return DeviceDiscoveryResult(scannedCount = 1, confirmedCount = 1)
                        }

                        override fun cancel() = Unit
                    },
                    eventPort = port,
                )
            },
            createConnectionAdapter = { port ->
                DeviceConnectionSessionAdapter(
                    connectionFactory = DeviceConnectionFactory {
                        DeviceConnectionAttempt.Connected(lease)
                    },
                    eventPort = port,
                )
            },
            loadCatalog = {},
            maintenanceController = controller,
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.dispatch(InstallationSessionCommand.Reconnect)
        advanceUntilIdle()
        runtime.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.MANAGE_APPS))
        runCurrent()

        val inventory = runtime.session.currentSnapshot()
        assertEquals(MaintenanceInventoryState.READY, inventory.maintenance.managedApplicationsState)
        assertEquals(MaintenanceActionStatus.SUCCEEDED, inventory.maintenance.lastAction?.status)
        assertTrue(firstIconStarted.isCompleted)
        assertEquals(listOf("com.example.player"), iconRequests)

        runtime.dispatch(
            InstallationSessionCommand.MaintenanceApplicationAction(
                packageName = "com.example.player",
                actionId = MaintenanceApplicationActionId.FORCE_STOP,
            ),
        )
        runCurrent()

        assertTrue(firstIconCancelled.isCompleted)
        assertTrue(foregroundActionCompleted.isCompleted)
        assertEquals(listOf("com.example.player"), iconRequests)
        assertEquals(
            MaintenanceActionStatus.SUCCEEDED,
            runtime.session.currentSnapshot().maintenance.applicationAction?.status,
        )
        runtime.close()
    }

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
        assertEquals(InstallationSessionState.MAINTENANCE, runtime.session.currentSnapshot().state)
        assertEquals(
            setOf("desktop"),
            saved.last().maintenance.installedManifests.map { it.componentId }.toSet(),
        )
        runtime.close()
    }

    @Test
    fun `failed baseline remains retryable on the next equivalent business snapshot`() = runTest {
        var attempts = 0
        val runtime = runtime(
            maintenanceSnapshot(),
            controller = MaintenanceController(
                artifactCache = ArtifactCache(Files.createTempDirectory("maintenance-retry").toFile()),
                diagnosticStore = MaintenanceDiagnosticStore(
                    Files.createTempDirectory("maintenance-retry-diagnostics").toFile(),
                ),
            ),
            persist = {
                attempts += 1
                if (attempts == 1) error("disk unavailable")
            },
        )
        advanceUntilIdle()

        assertEquals(1, attempts)
        assertEquals(InstallationSessionState.MAINTENANCE, runtime.session.currentSnapshot().state)

        runtime.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.CLEANUP))
        advanceUntilIdle()

        assertEquals(2, attempts)
        assertEquals(InstallationSessionState.MAINTENANCE, runtime.session.currentSnapshot().state)
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
        maintenance = com.ninepointnine.helper.domain.session.MaintenanceSnapshot(
            installedManifests = listOf(desktopManifest()),
        ),
    )

    private fun desktopManifest(): ArtifactManifest = ArtifactManifest(
        schemaVersion = 1,
        componentId = "desktop",
        displayName = "Desktop",
        required = true,
        version = ArtifactVersion("1.0", 1L),
        compatibility = CompatibilityRange(minAndroidSdk = 26, maxAndroidSdk = 30),
        archiveFileName = "desktop.zip",
        archiveSizeBytes = 100L,
        archiveSha256 = "11".repeat(32),
        apkEntryName = "desktop.apk",
        apkSizeBytes = 50L,
        apkSha256 = "22".repeat(32),
        packageName = "com.ninepointnine.desktop",
        apkVersion = ArtifactVersion("1.0", 1L),
        certificateSha256 = "33".repeat(32),
        sources = listOf(
            ArtifactSource(ArtifactSourceKind.LANZOU_SHARE, "https://wwatl.lanzouw.com/idesktop"),
            ArtifactSource(ArtifactSourceKind.R2, "https://assets.r2.dev/desktop.zip"),
            ArtifactSource(
                ArtifactSourceKind.GITHUB_RELEASES,
                "https://github.com/example/repo/releases/download/v1/desktop.zip",
            ),
        ),
    )
}
