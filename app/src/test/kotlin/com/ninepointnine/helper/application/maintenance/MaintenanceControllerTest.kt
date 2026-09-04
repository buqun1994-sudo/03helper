package com.ninepointnine.helper.application.maintenance

import com.ninepointnine.helper.application.session.InstallationSessionEventPort
import com.ninepointnine.helper.data.catalog.DistributionConfigLoadResult
import com.ninepointnine.helper.data.catalog.InstallerComponentSource
import com.ninepointnine.helper.data.catalog.InstallerDistributionConfig
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
import com.ninepointnine.helper.domain.device.AuthorizationPlan
import com.ninepointnine.helper.domain.device.ApkDeclarationMetadata
import com.ninepointnine.helper.domain.device.ApkServiceDeclaration
import com.ninepointnine.helper.domain.device.ApplicationAuthorizationRequirement
import com.ninepointnine.helper.domain.device.ApplicationAuthorizationResult
import com.ninepointnine.helper.domain.device.ApplicationAuthorizationResultValue
import com.ninepointnine.helper.domain.device.InstalledArtifactEvidence
import com.ninepointnine.helper.domain.device.ManagedApplicationProbe
import com.ninepointnine.helper.domain.device.ManagedApplicationsResult
import com.ninepointnine.helper.domain.device.InstalledApplicationIconResult
import com.ninepointnine.helper.domain.device.MaintenanceCommandGateway
import com.ninepointnine.helper.domain.device.MaintenanceDeviceResult
import com.ninepointnine.helper.domain.device.InstallableArtifact
import com.ninepointnine.helper.domain.session.ComponentDescriptor
import com.ninepointnine.helper.domain.session.ArtifactCatalogStage
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.domain.session.InstallationSessionState
import com.ninepointnine.helper.domain.session.InstallationStrategy
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.domain.session.MaintenanceApplicationActionId
import com.ninepointnine.helper.domain.session.MaintenanceAuthorizationFlowState
import com.ninepointnine.helper.domain.session.MaintenanceAuthorizationSnapshot
import com.ninepointnine.helper.domain.session.MaintenanceSnapshot
import com.ninepointnine.helper.domain.session.ManagedApplicationStatus
import com.ninepointnine.helper.domain.session.SessionEvidence
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceControllerTest {
    @Test
    fun `single application authorization forwards progress before final result`() = runBlocking {
        val gateway = FakeGateway()
        val packageName = "com.example.player"
        val progress = ApplicationAuthorizationResultValue(
            componentId = "app-player",
            packageName = packageName,
            requirements = listOf(
                ApplicationAuthorizationRequirement("android.permission.CAMERA", false, true),
            ),
        )
        gateway.applicationAuthorizationProgress = listOf(progress)
        gateway.applicationAuthorizationResult = ApplicationAuthorizationResult.Completed(progress)
        val base = maintenanceSnapshot(emptyList())
        val snapshot = base.copy(
            maintenance = base.maintenance.copy(
                managedApplications = listOf(
                    ManagedApplicationStatus("app-player", packageName, installed = true),
                ),
            ),
        )
        val events = mutableListOf<InstallationSessionEvent>()

        MaintenanceController(tempCache(), tempDiagnostics()).executeApplicationAction(
            packageName = packageName,
            actionId = MaintenanceApplicationActionId.AUTHORIZE,
            snapshot = snapshot,
            connection = lease(gateway),
            eventPort = InstallationSessionEventPort(events::add),
        )

        assertTrue(events[0] is InstallationSessionEvent.MaintenanceApplicationAuthorizationProgress)
        assertTrue(events[1] is InstallationSessionEvent.MaintenanceApplicationAuthorizationResolved)
    }

    @Test
    fun `check updates reads the signed control plane and distinguishes changed versions`() = runBlocking {
        val current = manifest("desktop", versionCode = 1)
        val updated = manifest("desktop", versionCode = 2)
        val events = mutableListOf<InstallationSessionEvent>()
        val controller = MaintenanceController(
            artifactCache = tempCache(),
            diagnosticStore = tempDiagnostics(),
            loadDistributionConfig = { distributionConfig(updated) },
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
        assertTrue(refreshed.controlPlaneOnly)
        assertTrue(refreshed.manifests.isEmpty())
        assertEquals("v1.2", refreshed.apps.single { it.id == "desktop" }.versionLabel)
        assertEquals(
            InstallationSessionEvent.MaintenanceActionCompleted(
                actionId = MaintenanceActionId.CHECK_UPDATES,
                resultCode = "updates_available",
            ),
            events[1],
        )
    }

    @Test
    fun `control plane update check uses live version code without resolving artifacts`() = runBlocking {
        val current = manifest("desktop", versionCode = 1)
        val gateway = FakeGateway().apply {
            managedApplications = ManagedApplicationsResult.Completed(
                listOf(
                    ManagedApplicationProbe(
                        componentId = "desktop",
                        packageName = current.packageName,
                        installed = true,
                        versionCode = 1L,
                    ),
                ),
            )
        }
        val events = mutableListOf<InstallationSessionEvent>()
        val controller = MaintenanceController(
            artifactCache = tempCache(),
            diagnosticStore = tempDiagnostics(),
            loadDistributionConfig = {
                com.ninepointnine.helper.data.catalog.DistributionConfigLoadResult.Success(
                    com.ninepointnine.helper.data.catalog.InstallerDistributionConfig(
                        channel = "debug",
                        environment = "staging",
                        expiresAt = java.time.Instant.parse("2099-01-01T00:00:00Z"),
                        catalogVersion = "catalog-2",
                        catalogRevision = 2L,
                        keyId = "test-key",
                        signatureAlgorithm = "Ed25519",
                        apps = listOf(
                            com.ninepointnine.helper.data.catalog.InstallerComponentSource(
                                componentId = "desktop",
                                archiveFileName = "desktop.zip",
                                required = true,
                                displayName = "desktop",
                                versionCode = 1L,
                                versionName = "1.1",
                                apkSizeBytes = 50L,
                                trustProfileId = "nine-studio",
                            ),
                        ),
                    ),
                )
            },
        )

        controller.execute(
            actionId = MaintenanceActionId.CHECK_UPDATES,
            snapshot = maintenanceSnapshot(listOf(current)).copy(
                catalogVersion = "catalog-2",
                catalogRevision = 2L,
            ),
            connection = lease(gateway),
            eventPort = InstallationSessionEventPort { events += it },
        )

        val refreshed = events.filterIsInstance<InstallationSessionEvent.MaintenanceCatalogRefreshed>().single()
        assertTrue(refreshed.controlPlaneOnly)
        assertTrue(refreshed.manifests.isEmpty())
        assertEquals(
            com.ninepointnine.helper.domain.session.MaintenanceUpdateState.CURRENT,
            refreshed.updateStatuses.single { it.componentId == "desktop" }.state,
        )
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
        assertEquals(listOf("desktop", "file-manager"), resolved.applications.map { it.componentId })
        assertEquals(listOf(true, true), resolved.applications.map { it.installed })
        assertEquals("applications_checked", (events[1] as InstallationSessionEvent.MaintenanceActionCompleted).resultCode)
    }

    @Test
    fun `full inventory keeps trusted package variants as independent identities`() = runBlocking {
        val packages = listOf(
            "com.ninepointnine.desktop",
            "com.ninepointnine.desktop.test",
        )
        val events = mutableListOf<InstallationSessionEvent>()
        val gateway = FakeGateway().apply {
            managedApplications = ManagedApplicationsResult.Completed(
                packages.mapIndexed { index, packageName ->
                    ManagedApplicationProbe(
                        componentId = "app-${index + 1}",
                        packageName = packageName,
                        installed = true,
                    )
                },
            )
        }

        MaintenanceController(tempCache(), tempDiagnostics()).execute(
            actionId = MaintenanceActionId.MANAGE_APPS,
            snapshot = maintenanceSnapshot(emptyList()),
            connection = lease(gateway),
            eventPort = InstallationSessionEventPort(events::add),
        )

        val resolved = events.filterIsInstance<InstallationSessionEvent.MaintenanceApplicationsResolved>().single()
        assertEquals(packages, resolved.applications.map { it.packageName })
        assertEquals(2, resolved.applications.map { it.componentId }.distinct().size)
    }

    @Test
    fun `application action uses the live package and launcher instead of a stale catalog entry`() = runBlocking {
        val livePackage = "com.ninepointnine.desktop.test"
        val liveLauncher = "$livePackage/com.ninepointnine.desktop.MainActivity"
        val base = maintenanceSnapshot(listOf(manifest("desktop", 1)))
        val snapshot = base.copy(
            maintenance = base.maintenance.copy(
                managedApplications = listOf(
                    ManagedApplicationStatus(
                        componentId = "app-live-desktop",
                        packageName = livePackage,
                        installed = true,
                        launchComponent = liveLauncher,
                    ),
                ),
            ),
        )
        val gateway = FakeGateway()

        MaintenanceController(tempCache(), tempDiagnostics()).executeApplicationAction(
            packageName = livePackage,
            actionId = MaintenanceApplicationActionId.START,
            snapshot = snapshot,
            connection = lease(gateway),
            eventPort = InstallationSessionEventPort {},
        )

        assertEquals(livePackage, gateway.performedApplication?.packageName)
        assertEquals(liveLauncher, gateway.performedApplication?.launchComponent)
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
            managedApplications = ManagedApplicationsResult.Completed(
                listOf(
                    ManagedApplicationProbe("desktop", "com.tcrrry.desktop", true),
                    ManagedApplicationProbe("lyrics", "com.tcrrry.desktoplyrics", true),
                ),
            )
            authorizationStatuses = com.ninepointnine.helper.domain.device.MaintenanceAuthorizationResult.Completed(
                listOf(
                    com.ninepointnine.helper.domain.device.ManagedApplicationAuthorizationStatus(
                        componentId = "desktop",
                        packageName = "com.tcrrry.desktop",
                        authorized = false,
                    ),
                    com.ninepointnine.helper.domain.device.ManagedApplicationAuthorizationStatus(
                        componentId = "lyrics",
                        packageName = "com.tcrrry.desktoplyrics",
                        authorized = false,
                    ),
                ),
            )
            repairResult = MaintenanceDeviceResult.Completed("authorization_repaired")
        }
        val controller = MaintenanceController(tempCache(), tempDiagnostics())

        controller.execute(
            actionId = MaintenanceActionId.REPAIR_CONFIGURATION,
            snapshot = maintenanceSnapshot(manifests, selected = setOf("lyrics")).copy(
                maintenance = MaintenanceSnapshot(
                    authorization = MaintenanceAuthorizationSnapshot(
                        state = MaintenanceAuthorizationFlowState.REPAIRING,
                    ),
                ),
            ),
            connection = lease(gateway),
            eventPort = InstallationSessionEventPort { },
        )

        assertEquals(listOf("desktop", "lyrics"), gateway.repairManifests.map { it.componentId })
    }

    @Test
    fun `first authorization visit reads live installed subset without repairing`() = runBlocking {
        val events = mutableListOf<InstallationSessionEvent>()
        val gateway = FakeGateway().apply {
            managedApplications = ManagedApplicationsResult.Completed(
                listOf(ManagedApplicationProbe("desktop", "com.tcrrry.desktop", true)),
            )
            authorizationStatuses = com.ninepointnine.helper.domain.device.MaintenanceAuthorizationResult.Completed(
                listOf(
                    com.ninepointnine.helper.domain.device.ManagedApplicationAuthorizationStatus(
                        componentId = "desktop",
                        packageName = "com.tcrrry.desktop",
                        authorized = true,
                    ),
                ),
            )
        }

        MaintenanceController(tempCache(), tempDiagnostics()).execute(
            actionId = MaintenanceActionId.REPAIR_CONFIGURATION,
            snapshot = maintenanceSnapshot(listOf(manifest("desktop", 1), manifest("lyrics", 1))),
            connection = lease(gateway),
            eventPort = InstallationSessionEventPort { events += it },
        )

        val resolved = events.filterIsInstance<InstallationSessionEvent.MaintenanceApplicationsResolved>().single()
        assertEquals(listOf("desktop"), resolved.applications.map { it.componentId })
        assertTrue(events.any { it == InstallationSessionEvent.MaintenanceActionCompleted(MaintenanceActionId.REPAIR_CONFIGURATION, "authorization_checked") })
        assertTrue(gateway.repairManifests.isEmpty())
    }

    @Test
    fun `already authorized applications complete an explicit repair without a stale manifest baseline`() = runBlocking {
        val events = mutableListOf<InstallationSessionEvent>()
        val desktop = manifest("desktop", 1)
        val gateway = FakeGateway().apply {
            managedApplications = ManagedApplicationsResult.Completed(
                listOf(ManagedApplicationProbe("desktop", desktop.packageName, true)),
            )
            authorizationStatuses = com.ninepointnine.helper.domain.device.MaintenanceAuthorizationResult.Completed(
                listOf(
                    com.ninepointnine.helper.domain.device.ManagedApplicationAuthorizationStatus(
                        componentId = "desktop",
                        packageName = desktop.packageName,
                        authorized = true,
                    ),
                ),
            )
        }
        val base = maintenanceSnapshot(listOf(desktop))
        val snapshot = base.copy(
            artifactManifests = emptyList(),
            maintenance = base.maintenance.copy(
                installedManifests = emptyList(),
                availableManifests = emptyList(),
                authorization = MaintenanceAuthorizationSnapshot(
                    state = MaintenanceAuthorizationFlowState.REPAIRING,
                ),
            ),
        )

        MaintenanceController(tempCache(), tempDiagnostics()).execute(
            actionId = MaintenanceActionId.REPAIR_CONFIGURATION,
            snapshot = snapshot,
            connection = lease(gateway),
            eventPort = InstallationSessionEventPort { events += it },
        )

        assertTrue(
            events.any {
                it == InstallationSessionEvent.MaintenanceActionCompleted(
                    MaintenanceActionId.REPAIR_CONFIGURATION,
                    "authorization_repaired",
                )
            },
        )
        assertTrue(gateway.repairManifests.isEmpty())
        assertFalse(events.any { it is InstallationSessionEvent.MaintenanceActionFailed })
    }

    @Test
    fun `authorization check forwards the same live inventory used for displayed versions`() = runBlocking {
        val events = mutableListOf<InstallationSessionEvent>()
        val live = ManagedApplicationProbe(
            componentId = "desktop",
            packageName = "com.tcrrry.desktop",
            installed = true,
            versionLabel = "2.4.1",
            versionCode = 241L,
        )
        val gateway = FakeGateway().apply {
            managedApplications = ManagedApplicationsResult.Completed(listOf(live))
            authorizationStatuses = com.ninepointnine.helper.domain.device.MaintenanceAuthorizationResult.Completed(
                listOf(
                    com.ninepointnine.helper.domain.device.ManagedApplicationAuthorizationStatus(
                        componentId = "desktop",
                        packageName = live.packageName,
                        authorized = true,
                    ),
                ),
            )
        }

        MaintenanceController(tempCache(), tempDiagnostics()).execute(
            actionId = MaintenanceActionId.REPAIR_CONFIGURATION,
            snapshot = maintenanceSnapshot(listOf(manifest("desktop", 1))),
            connection = lease(gateway),
            eventPort = InstallationSessionEventPort { events += it },
        )

        val application = events
            .filterIsInstance<InstallationSessionEvent.MaintenanceApplicationsResolved>()
            .single()
            .applications
            .single()
        assertEquals("2.4.1", application.versionLabel)
        assertEquals(241L, application.versionCode)
        assertEquals(listOf(live), gateway.authorizationInventory)
    }

    @Test
    fun `authorization check forwards installed apk declarations to the device adapter`() = runBlocking {
        val desktop = manifest("desktop", 1)
        val declaration = ApkDeclarationMetadata(
            requestedPermissions = setOf(
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.REQUEST_INSTALL_PACKAGES",
            ),
            services = setOf(
                ApkServiceDeclaration(
                    "${desktop.packageName}/com.ninepointnine.desktop.debug.NavigationDemoAccessibilityService",
                    "android.permission.BIND_ACCESSIBILITY_SERVICE",
                ),
            ),
        )
        val gateway = FakeGateway().apply {
            val live = ManagedApplicationProbe("desktop", desktop.packageName, true)
            managedApplications = ManagedApplicationsResult.Completed(listOf(live))
            authorizationStatuses = com.ninepointnine.helper.domain.device.MaintenanceAuthorizationResult.Completed(
                listOf(
                    com.ninepointnine.helper.domain.device.ManagedApplicationAuthorizationStatus(
                        componentId = "desktop",
                        packageName = desktop.packageName,
                        authorized = true,
                    ),
                ),
            )
        }
        val snapshot = maintenanceSnapshot(listOf(desktop)).copy(
            evidence = SessionEvidence(
                installed = setOf("desktop"),
                installation = mapOf(
                    "desktop" to InstalledArtifactEvidence(
                        componentId = "desktop",
                        packageName = desktop.packageName,
                        version = desktop.apkVersion,
                        apkSizeBytes = desktop.apkSizeBytes,
                        apkSha256 = desktop.apkSha256,
                        certificateSha256 = desktop.certificateSha256,
                        declarations = declaration,
                    ),
                ),
            ),
        )

        MaintenanceController(tempCache(), tempDiagnostics()).execute(
            actionId = MaintenanceActionId.REPAIR_CONFIGURATION,
            snapshot = snapshot,
            connection = lease(gateway),
            eventPort = InstallationSessionEventPort { },
        )

        assertEquals(mapOf("desktop" to declaration), gateway.authorizationDeclarations)
    }

    @Test
    fun `update inspection does not call missing inventory a not-installed result`() = runBlocking {
        val events = mutableListOf<InstallationSessionEvent>()
        val gateway = FakeGateway().apply {
            managedApplications = ManagedApplicationsResult.Failed(
                com.ninepointnine.helper.domain.device.DeviceActionFailure(
                    "maintenance_package_inventory_failed",
                    retryable = true,
                ),
            )
        }
        val app = manifest("desktop", 2)
        MaintenanceController(
            artifactCache = tempCache(),
            diagnosticStore = tempDiagnostics(),
            loadDistributionConfig = { distributionConfig(app) },
        ).execute(
            actionId = MaintenanceActionId.CHECK_UPDATES,
            snapshot = maintenanceSnapshot(listOf(manifest("desktop", 1))),
            connection = lease(gateway),
            eventPort = InstallationSessionEventPort { events += it },
        )

        val refreshed = events.filterIsInstance<InstallationSessionEvent.MaintenanceCatalogRefreshed>().single()
        assertEquals(
            com.ninepointnine.helper.domain.session.MaintenanceUpdateState.UNAVAILABLE,
            refreshed.updateStatuses.single { !it.isSelf }.state,
        )
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
            artifactCatalogStage = if (manifests.isEmpty()) ArtifactCatalogStage.NOT_LOADED else ArtifactCatalogStage.PREPARED,
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

    private fun distributionConfig(manifest: ArtifactManifest): DistributionConfigLoadResult.Success =
        DistributionConfigLoadResult.Success(
            InstallerDistributionConfig(
                channel = "debug",
                environment = "staging",
                expiresAt = java.time.Instant.parse("2099-01-01T00:00:00Z"),
                catalogVersion = "catalog-2",
                catalogRevision = 2L,
                keyId = "test-key",
                signatureAlgorithm = "Ed25519",
                folderUrl = "https://wwatl.lanzouw.com/b0fqlrcyb",
                apps = listOf(
                    InstallerComponentSource(
                        componentId = manifest.componentId,
                        archiveFileName = manifest.archiveFileName,
                        required = manifest.required,
                        displayName = manifest.displayName,
                        versionCode = manifest.apkVersion.code,
                        versionName = manifest.apkVersion.name,
                        apkSizeBytes = manifest.apkSizeBytes,
                        packageName = manifest.packageName,
                        certificateSha256 = manifest.certificateSha256,
                        apkEntryName = manifest.apkEntryName,
                        trustProfileId = "nine-studio",
                    ),
                ),
            ),
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
        var authorizationStatuses: com.ninepointnine.helper.domain.device.MaintenanceAuthorizationResult =
            com.ninepointnine.helper.domain.device.MaintenanceAuthorizationResult.Completed(emptyList())
        var authorizationInventory: List<ManagedApplicationProbe> = emptyList()
        var authorizationDeclarations: Map<String, ApkDeclarationMetadata> = emptyMap()
        var repairResult: MaintenanceDeviceResult = MaintenanceDeviceResult.Completed("authorization_repaired")
        var repairManifests: List<ArtifactManifest> = emptyList()
        var applicationAuthorizationProgress: List<ApplicationAuthorizationResultValue> = emptyList()
        var applicationAuthorizationResult: ApplicationAuthorizationResult = ApplicationAuthorizationResult.Failed(
            com.ninepointnine.helper.domain.device.DeviceActionFailure("unused", retryable = false),
        )
        var performedApplication: com.ninepointnine.helper.domain.device.ManagedComponent? = null

        override suspend fun installBatch(
            artifacts: List<InstallableArtifact>,
            strategy: InstallationStrategy,
        ): DeviceInstallResult =
            DeviceInstallResult.Failed(com.ninepointnine.helper.domain.device.DeviceActionFailure("unused", retryable = false))

        override suspend fun runShortcut(
            shortcut: DeviceShortcut,
            selectedComponentIds: Set<String>,
            authorizationPlan: AuthorizationPlan,
        ): DeviceShortcutResult =
            DeviceShortcutResult.Failed(
                com.ninepointnine.helper.domain.device.DeviceShortcutFailureStage.AUTHORIZATION,
                com.ninepointnine.helper.domain.device.DeviceActionFailure("unused", retryable = false),
            )

        override suspend fun repairAuthorization(
            manifests: List<ArtifactManifest>,
            declarationsByComponent: Map<String, com.ninepointnine.helper.domain.device.ApkDeclarationMetadata>,
        ): MaintenanceDeviceResult {
            repairManifests = manifests
            return repairResult
        }

        override suspend fun inspectManagedApplications(
            components: List<com.ninepointnine.helper.domain.device.ManagedComponent>,
        ): ManagedApplicationsResult = managedApplications

        override suspend fun inspectInstalledApplicationInventory(
            components: List<com.ninepointnine.helper.domain.device.ManagedComponent>,
        ): ManagedApplicationsResult = managedApplications

        override suspend fun inspectAllInstalledApplications(): ManagedApplicationsResult = managedApplications

        override suspend fun inspectInstalledApplicationIcon(packageName: String): InstalledApplicationIconResult =
            InstalledApplicationIconResult.Completed(packageName, null)

        override suspend fun authorizeApplication(
            component: com.ninepointnine.helper.domain.device.ManagedComponent,
        ): ApplicationAuthorizationResult = applicationAuthorizationResult

        override suspend fun authorizeApplication(
            component: com.ninepointnine.helper.domain.device.ManagedComponent,
            onProgress: (ApplicationAuthorizationResultValue) -> Unit,
        ): ApplicationAuthorizationResult {
            applicationAuthorizationProgress.forEach(onProgress)
            return applicationAuthorizationResult
        }

        override suspend fun inspectComponentAuthorization(
            components: List<com.ninepointnine.helper.domain.device.ManagedComponent>,
            installedApplications: List<ManagedApplicationProbe>,
        ): com.ninepointnine.helper.domain.device.MaintenanceAuthorizationResult {
            authorizationInventory = installedApplications
            return authorizationStatuses
        }

        override suspend fun inspectComponentAuthorization(
            components: List<com.ninepointnine.helper.domain.device.ManagedComponent>,
            installedApplications: List<ManagedApplicationProbe>,
            declarationsByComponent: Map<String, ApkDeclarationMetadata>,
        ): com.ninepointnine.helper.domain.device.MaintenanceAuthorizationResult {
            authorizationInventory = installedApplications
            authorizationDeclarations = declarationsByComponent
            return authorizationStatuses
        }

        override suspend fun launchManagedComponent(
            component: com.ninepointnine.helper.domain.device.ManagedComponent,
        ): MaintenanceDeviceResult =
            MaintenanceDeviceResult.Completed("component_launched")

        override suspend fun performApplicationAction(
            component: com.ninepointnine.helper.domain.device.ManagedComponent,
            actionId: MaintenanceApplicationActionId,
        ): MaintenanceDeviceResult = when (actionId) {
            MaintenanceApplicationActionId.START -> {
                performedApplication = component
                MaintenanceDeviceResult.Completed("component_launched")
            }
            else -> MaintenanceDeviceResult.Failed(
                com.ninepointnine.helper.domain.device.DeviceActionFailure(
                    "unused",
                    component.componentId,
                    retryable = false,
                ),
            )
        }

        override suspend fun inspectManagedApplicationDetails(
            component: com.ninepointnine.helper.domain.device.ManagedComponent,
        ): com.ninepointnine.helper.domain.device.ManagedApplicationDetailsProbeResult =
            com.ninepointnine.helper.domain.device.ManagedApplicationDetailsProbeResult.Failed(
                com.ninepointnine.helper.domain.device.DeviceActionFailure(
                    "unused",
                    component.componentId,
                    retryable = false,
                ),
            )
    }

    private fun ConnectedDevice.toSummary() = com.ninepointnine.helper.domain.session.DeviceSummary(
        id = identity.stableId,
        displayName = identity.model,
        connectionStatus = DeviceConnectionStatus.CONFIRMED,
        androidSdk = identity.androidSdk,
        capabilities = capabilities,
    )
}
