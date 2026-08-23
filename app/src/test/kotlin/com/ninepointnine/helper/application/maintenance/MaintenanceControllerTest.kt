package com.ninepointnine.helper.application.maintenance

import com.ninepointnine.helper.application.session.InstallationSessionEventPort
import com.ninepointnine.helper.data.catalog.CatalogLoadResult
import com.ninepointnine.helper.data.catalog.TrustedArtifactCatalog
import com.ninepointnine.helper.data.download.ArtifactCache
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactSource
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.CompatibilityRange
import com.ninepointnine.helper.domain.device.AdbCommandGateway
import com.ninepointnine.helper.domain.device.ConnectedDevice
import com.ninepointnine.helper.domain.device.DeviceActionConnectionLease
import com.ninepointnine.helper.domain.device.DeviceCapability
import com.ninepointnine.helper.domain.device.DeviceConnectionCheck
import com.ninepointnine.helper.domain.device.DeviceEndpoint
import com.ninepointnine.helper.domain.device.DeviceIdentity
import com.ninepointnine.helper.domain.device.DeviceInstallResult
import com.ninepointnine.helper.domain.device.DeviceShortcut
import com.ninepointnine.helper.domain.device.DeviceShortcutResult
import com.ninepointnine.helper.domain.device.ManagedApplicationProbe
import com.ninepointnine.helper.domain.device.ManagedApplicationsResult
import com.ninepointnine.helper.domain.device.MaintenanceCommandGateway
import com.ninepointnine.helper.domain.device.MaintenanceDeviceResult
import com.ninepointnine.helper.domain.device.InstallableArtifact
import com.ninepointnine.helper.domain.session.ComponentDescriptor
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.domain.session.InstallationSessionState
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.domain.session.SessionEvidence
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceControllerTest {
    @Test
    fun `check updates emits a trusted catalog event and distinguishes changed versions`() = runBlocking {
        val current = manifest("desktop", versionCode = 1)
        val updated = manifest("desktop", versionCode = 2)
        val events = mutableListOf<InstallationSessionEvent>()
        val controller = MaintenanceController(
            artifactCache = tempCache(),
            diagnosticStore = tempDiagnostics(),
            loadCatalog = {
                CatalogLoadResult.Success(
                    TrustedArtifactCatalog(
                        catalogVersion = "catalog-2",
                        keyId = "test-key",
                        signatureAlgorithm = "Ed25519",
                        manifests = listOf(updated),
                    ),
                )
            },
        )

        controller.execute(
            actionId = MaintenanceActionId.CHECK_UPDATES,
            snapshot = maintenanceSnapshot(listOf(current)),
            connection = null,
            eventPort = InstallationSessionEventPort { events += it },
        )

        assertEquals(2, events.size)
        val refreshed = events[0] as InstallationSessionEvent.MaintenanceCatalogRefreshed
        assertEquals("catalog-2", refreshed.catalogVersion)
        assertEquals(updated, refreshed.manifests.single())
        assertEquals(
            InstallationSessionEvent.MaintenanceActionCompleted(
                actionId = MaintenanceActionId.CHECK_UPDATES,
                resultCode = "updates_available",
            ),
            events[1],
        )
    }

    @Test
    fun `check updates detects archive or APK size changes even when hashes and version stay equal`() = runBlocking {
        val current = manifest("desktop", versionCode = 1)
        val updated = manifest("desktop", versionCode = 1, archiveSizeBytes = 101, apkSizeBytes = 51)
        val events = mutableListOf<InstallationSessionEvent>()
        val controller = MaintenanceController(
            artifactCache = tempCache(),
            diagnosticStore = tempDiagnostics(),
            loadCatalog = {
                CatalogLoadResult.Success(
                    TrustedArtifactCatalog(
                        catalogVersion = "catalog-2",
                        keyId = "test-key",
                        signatureAlgorithm = "Ed25519",
                        manifests = listOf(updated),
                    ),
                )
            },
        )

        controller.execute(
            actionId = MaintenanceActionId.CHECK_UPDATES,
            snapshot = maintenanceSnapshot(listOf(current)),
            connection = null,
            eventPort = InstallationSessionEventPort { events += it },
        )

        assertEquals("updates_available", (events[1] as InstallationSessionEvent.MaintenanceActionCompleted).resultCode)
    }

    @Test
    fun `device action without a retained lease fails before gateway access`() = runBlocking {
        val events = mutableListOf<InstallationSessionEvent>()
        val controller = MaintenanceController(tempCache(), tempDiagnostics())

        controller.execute(
            actionId = MaintenanceActionId.LAUNCH_DESKTOP,
            snapshot = maintenanceSnapshot(listOf(manifest("desktop", 1))),
            connection = null,
            eventPort = InstallationSessionEventPort { events += it },
        )

        assertEquals(
            InstallationSessionEvent.MaintenanceActionFailed(
                actionId = MaintenanceActionId.LAUNCH_DESKTOP,
                reasonCode = "device_action_gateway_unavailable",
                retryable = true,
            ),
            events.single(),
        )
    }

    @Test
    fun `managed application inspection emits all managed component statuses`() = runBlocking {
        val events = mutableListOf<InstallationSessionEvent>()
        val gateway = FakeGateway().apply {
            managedApplications = ManagedApplicationsResult.Completed(
                listOf(
                    ManagedApplicationProbe("desktop", "com.tcrrry.desktop", true),
                    ManagedApplicationProbe("lyrics", "com.tcrrry.desktoplyrics", false),
                    ManagedApplicationProbe("file-manager", "org.fossify.filemanager.debug", true),
                ),
            )
        }
        val controller = MaintenanceController(tempCache(), tempDiagnostics())

        controller.execute(
            actionId = MaintenanceActionId.MANAGE_APPS,
            snapshot = maintenanceSnapshot(emptyList()),
            connection = lease(gateway),
            eventPort = InstallationSessionEventPort { events += it },
        )

        val resolved = events[0] as InstallationSessionEvent.MaintenanceApplicationsResolved
        assertEquals(listOf("desktop", "lyrics", "file-manager"), resolved.applications.map { it.componentId })
        assertEquals(listOf(true, false, true), resolved.applications.map { it.installed })
        assertEquals("applications_checked", (events[1] as InstallationSessionEvent.MaintenanceActionCompleted).resultCode)
    }

    @Test
    fun `cleanup removes only the private artifact cache and diagnostics stay redacted`() = runBlocking {
        val root = Files.createTempDirectory("maintenance-cache").toFile()
        val cache = ArtifactCache(root.resolve("artifacts"))
        root.resolve("artifacts/partial.zip.part").writeText("partial")
        root.resolve("artifacts/installed-verification/old.apk").apply {
            parentFile?.mkdirs()
            writeText("verified-but-expired")
        }
        val diagnostics = MaintenanceDiagnosticStore(root.resolve("diagnostics"))
        val controller = MaintenanceController(cache, diagnostics)
        val events = mutableListOf<InstallationSessionEvent>()

        controller.execute(
            actionId = MaintenanceActionId.CLEANUP,
            snapshot = maintenanceSnapshot(emptyList()),
            connection = null,
            eventPort = InstallationSessionEventPort { events += it },
        )
        assertFalse(root.resolve("artifacts/partial.zip.part").exists())
        assertFalse(root.resolve("artifacts/installed-verification/old.apk").exists())
        assertEquals("cache_cleared", (events.single() as InstallationSessionEvent.MaintenanceActionCompleted).resultCode)

        assertTrue(diagnostics.export(maintenanceSnapshot(emptyList())))
        val text = root.resolve("diagnostics/diagnostics.txt").readText()
        assertTrue(text.contains("schema=1"))
        assertFalse(text.contains("Authorization:"))
        assertFalse(text.contains("shell"))
    }

    @Test
    fun `repair authorization forwards only the selected verified manifests`() = runBlocking {
        val manifests = listOf(
            manifest("desktop", 1),
            manifest("lyrics", 1),
        )
        val gateway = FakeGateway().apply {
            repairResult = MaintenanceDeviceResult.Completed("authorization_repaired")
        }
        val controller = MaintenanceController(tempCache(), tempDiagnostics())

        controller.execute(
            actionId = MaintenanceActionId.REPAIR_CONFIGURATION,
            snapshot = maintenanceSnapshot(manifests, selected = setOf("lyrics")),
            connection = lease(gateway),
            eventPort = InstallationSessionEventPort { },
        )

        assertEquals(listOf("desktop", "lyrics"), gateway.repairManifests.map { it.componentId })
    }

    private fun maintenanceSnapshot(
        manifests: List<ArtifactManifest>,
        selected: Set<String> = emptySet(),
    ): InstallationSessionSnapshot {
        val components = if (manifests.isEmpty()) {
            listOf(
                ComponentDescriptor("desktop", "Desktop", true, "1", "1 MB", "compatible"),
                ComponentDescriptor("lyrics", "Lyrics", false, "1", "1 MB", "compatible"),
            )
        } else {
            manifests.map {
                ComponentDescriptor(
                    id = it.componentId,
                    displayName = it.displayName,
                    required = it.required,
                    versionLabel = it.version.name,
                    sizeLabel = "1 MB",
                    compatibilityLabel = "compatible",
                )
            }
        }
        val installed = manifests.map { it.componentId }.toSet()
        return InstallationSessionSnapshot(
            state = InstallationSessionState.MAINTENANCE,
            device = ConnectedDevice(
                endpoint = DeviceEndpoint("192.0.2.1"),
                identity = DeviceIdentity("vehicle-1", "S56_HQX", 28),
                capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
            ).toSummary(),
            components = components,
            selectedOptionalComponentIds = selected,
            artifactManifests = manifests,
            evidence = SessionEvidence(installed = installed),
        )
    }

    private fun manifest(
        componentId: String,
        versionCode: Long,
        archiveSizeBytes: Long = 100,
        apkSizeBytes: Long = 50,
    ): ArtifactManifest = ArtifactManifest(
        schemaVersion = 1,
        componentId = componentId,
        displayName = componentId,
        required = componentId == "desktop",
        version = ArtifactVersion("1.$versionCode", versionCode),
        compatibility = CompatibilityRange(26, 30),
        archiveFileName = "$componentId.zip",
        archiveSizeBytes = archiveSizeBytes,
        archiveSha256 = ("1" + versionCode).repeat(64).take(64),
        apkEntryName = "$componentId.apk",
        apkSizeBytes = apkSizeBytes,
        apkSha256 = ("2" + versionCode).repeat(64).take(64),
        packageName = when (componentId) {
            "desktop" -> "com.tcrrry.desktop"
            "lyrics" -> "com.tcrrry.desktoplyrics"
            else -> "org.fossify.filemanager.debug"
        },
        apkVersion = ArtifactVersion("1.$versionCode", versionCode),
        certificateSha256 = "3".repeat(64),
        sources = listOf(ArtifactSource(ArtifactSourceKind.LANZOU_SHARE, "https://wwatl.lanzouw.com/i$componentId")),
    )

    private fun tempCache(): ArtifactCache =
        ArtifactCache(Files.createTempDirectory("maintenance-artifacts").toFile())

    private fun tempDiagnostics(): MaintenanceDiagnosticStore =
        MaintenanceDiagnosticStore(Files.createTempDirectory("maintenance-diagnostics").toFile())

    private fun lease(gateway: FakeGateway): DeviceActionConnectionLease = object : DeviceActionConnectionLease {
        override val device = ConnectedDevice(
            endpoint = DeviceEndpoint("192.0.2.1"),
            identity = DeviceIdentity("vehicle-1", "S56_HQX", 28),
            capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
        )
        override val commandGateway: AdbCommandGateway = gateway
        override suspend fun check(): DeviceConnectionCheck = DeviceConnectionCheck(true)
        override fun close() = Unit
    }

    private class FakeGateway : AdbCommandGateway, MaintenanceCommandGateway {
        var managedApplications: ManagedApplicationsResult = ManagedApplicationsResult.Completed(emptyList())
        var repairResult: MaintenanceDeviceResult = MaintenanceDeviceResult.Completed("authorization_repaired")
        var repairManifests: List<ArtifactManifest> = emptyList()

        override suspend fun install(artifacts: List<InstallableArtifact>): DeviceInstallResult =
            DeviceInstallResult.Failed(com.ninepointnine.helper.domain.device.DeviceActionFailure("unused", retryable = false))

        override suspend fun runShortcut(
            shortcut: DeviceShortcut,
            selectedComponentIds: Set<String>,
        ): DeviceShortcutResult =
            DeviceShortcutResult.Failed(
                com.ninepointnine.helper.domain.device.DeviceShortcutFailureStage.AUTHORIZATION,
                com.ninepointnine.helper.domain.device.DeviceActionFailure("unused", retryable = false),
            )

        override suspend fun repairAuthorization(manifests: List<ArtifactManifest>): MaintenanceDeviceResult {
            repairManifests = manifests
            return repairResult
        }

        override suspend fun inspectManagedApplications(): ManagedApplicationsResult = managedApplications

        override suspend fun launchManagedComponent(componentId: String): MaintenanceDeviceResult =
            MaintenanceDeviceResult.Completed("component_launched")
    }

    private fun ConnectedDevice.toSummary() = com.ninepointnine.helper.domain.session.DeviceSummary(
        id = identity.stableId,
        displayName = identity.model,
        connectionStatus = DeviceConnectionStatus.CONFIRMED,
        androidSdk = identity.androidSdk,
        capabilities = capabilities,
    )
}
