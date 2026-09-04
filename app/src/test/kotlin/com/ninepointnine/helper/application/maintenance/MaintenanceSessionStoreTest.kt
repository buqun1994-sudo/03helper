package com.ninepointnine.helper.application.maintenance

import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactSource
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.AppIconAsset
import com.ninepointnine.helper.domain.artifact.CompatibilityRange
import com.ninepointnine.helper.domain.artifact.InstallerComponentTrustRegistry
import com.ninepointnine.helper.domain.artifact.toComponentDescriptor
import com.ninepointnine.helper.domain.device.AuthorizationPlanFactory
import com.ninepointnine.helper.domain.device.AuthorizationSetupDeclaration
import com.ninepointnine.helper.domain.device.DeviceCapability
import com.ninepointnine.helper.domain.device.ManagedAppOp
import com.ninepointnine.helper.domain.device.ManagedSecureComponentList
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.session.DeviceSummary
import com.ninepointnine.helper.domain.session.ComponentDescriptor
import com.ninepointnine.helper.domain.session.ComponentStatus
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.domain.session.InstallationSessionState
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.domain.session.MaintenanceActionRecord
import com.ninepointnine.helper.domain.session.MaintenanceActionStatus
import com.ninepointnine.helper.domain.session.MaintenanceInventoryState
import com.ninepointnine.helper.domain.session.ManagedApplicationStatus
import com.ninepointnine.helper.domain.session.MaintenanceSnapshot
import com.ninepointnine.helper.domain.session.SessionEvidence
import com.ninepointnine.helper.domain.session.ArtifactCatalogStage
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceSessionStoreTest {
    @Test
    fun `maintenance snapshot survives a cold start as disconnected maintenance`() {
        val file = Files.createTempDirectory("maintenance-store").resolve("session.json").toFile()
        val store = MaintenanceSessionStore(file)
        val installed = manifests(versionCode = 1L)
        val available = manifests(versionCode = 2L)
        val icon = AppIconAsset(
            assetId = "03desktop-staging-logo-app-icon",
            assetVersion = 1,
            url = "https://download.9.9studio.fun/03-apps/logos/03desktop/sha256-" + "a".repeat(64) + ".png",
            mimeType = "image/png",
            width = 216,
            height = 216,
            sizeBytes = 44_699L,
            sha256 = "a".repeat(64),
        )
        val base = maintenanceSnapshot(installed, available)
        val snapshot = base.copy(
            components = base.components.map { component ->
                if (component.id == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID) {
                    component.copy(iconAsset = icon)
                } else {
                    component
                }
            },
        )

        assertTrue(store.save(snapshot))
        val restored = store.load()

        assertEquals(InstallationSessionState.MAINTENANCE, restored?.state)
        assertEquals(DeviceConnectionStatus.DISCONNECTED, restored?.device?.connectionStatus)
        assertEquals(snapshot.device?.id, restored?.device?.id)
        assertTrue(restored?.artifactManifests.orEmpty().isEmpty())
        assertEquals(installed, restored?.maintenance?.installedManifests)
        assertEquals(available, restored?.maintenance?.availableManifests)
        assertEquals(snapshot.evidence, restored?.evidence)
        assertEquals(
            snapshot.maintenance.managedApplications.map { it.componentId },
            restored?.maintenance?.managedApplications?.map { it.componentId },
        )
        assertNull(restored?.maintenance?.lastAction)
        assertNull(restored?.installationBatch)
        assertNull(restored?.checkpoint)
        assertEquals(icon, restored?.components?.first { it.id == "desktop" }?.iconAsset)
    }

    @Test
    fun `cold start returns to maintenance home when a secondary route has no page payload`() {
        val file = Files.createTempDirectory("maintenance-store-route").resolve("session.json").toFile()
        val base = maintenanceSnapshot(manifests(1L), manifests(2L))
        val snapshot = base.copy(
            maintenance = base.maintenance.copy(
                routeAction = MaintenanceActionId.INSTALL_APPLICATIONS,
            ),
        )
        val store = MaintenanceSessionStore(file)

        assertTrue(store.save(snapshot))
        val restored = store.load() ?: error("snapshot_not_restored")

        assertNull(restored.maintenance.routeAction)
        assertNull(restored.maintenance.installationSelection)
    }

    @Test
    fun `new durable record does not serialize maintenance route or action history`() {
        val file = Files.createTempDirectory("maintenance-store-no-history")
            .resolve("session.json")
            .toFile()
        val base = maintenanceSnapshot(manifests(1L), manifests(2L))
        val snapshot = base.copy(
            maintenance = base.maintenance.copy(
                routeAction = MaintenanceActionId.MANAGE_APPS,
                lastAction = MaintenanceActionRecord(
                    actionId = MaintenanceActionId.MANAGE_APPS,
                    status = MaintenanceActionStatus.SUCCEEDED,
                    resultCode = "applications_checked",
                ),
            ),
        )
        val store = MaintenanceSessionStore(file)

        assertTrue(store.save(snapshot))
        val wire = file.readText()
        assertFalse(wire.contains("\"routeAction\""))
        assertFalse(wire.contains("\"lastAction\""))
    }

    @Test
    fun `legacy route and action fields are ignored on cold start`() {
        val file = Files.createTempDirectory("maintenance-store-legacy-route")
            .resolve("session.json")
            .toFile()
        val base = maintenanceSnapshot(manifests(1L), manifests(2L))
        val store = MaintenanceSessionStore(file)
        assertTrue(store.save(base))
        val legacyAction = "\"lastAction\":{" +
            "\"actionId\":\"MANAGE_APPS\",\"status\":\"SUCCEEDED\",\"resultCode\":\"applications_checked\"," +
            "\"retryable\":false},\"routeAction\":\"MANAGE_APPS\"," +
            ""
        val legacyWire = file.readText().replace(
            "\"managedApplicationsState\"",
            legacyAction + "\"managedApplicationsState\"",
        )
        file.writeText(legacyWire)

        val restored = store.load() ?: error("legacy_snapshot_not_restored")
        assertNull(restored.maintenance.routeAction)
        assertNull(restored.maintenance.lastAction)
    }

    @Test
    fun `confirmed empty inventory is not persisted as a maintenance baseline`() {
        val file = Files.createTempDirectory("maintenance-store-empty-inventory").resolve("session.json").toFile()
        val base = maintenanceSnapshot(manifests(1L), manifests(2L))
        val snapshot = base.copy(
            maintenance = base.maintenance.copy(
                managedApplications = emptyList(),
                managedApplicationsState = MaintenanceInventoryState.READY,
                availableComponents = base.components.filterNot { it.id == "desktop" },
            ),
        )
        val store = MaintenanceSessionStore(file)

        assertFalse(store.save(snapshot))
        assertTrue(MaintenanceBaselineProjector.shouldClear(snapshot))
        assertNull(store.load())
    }

    @Test
    fun `installed identity baseline survives after the transient batch catalog is cleared`() {
        val file = Files.createTempDirectory("maintenance-store-installed-baseline")
            .resolve("session.json")
            .toFile()
        val desktop = manifest("desktop", 1L, required = true)
        val base = maintenanceSnapshot(listOf(desktop), emptyList())
        val snapshot = base.copy(
            selectedOptionalComponentIds = emptySet(),
            artifactManifests = emptyList(),
            artifactCatalogStage = ArtifactCatalogStage.NOT_LOADED,
            evidence = SessionEvidence(installed = setOf(desktop.componentId)),
            maintenance = base.maintenance.copy(
                installedManifests = listOf(desktop),
                availableManifests = emptyList(),
            ),
        )
        val store = MaintenanceSessionStore(file)

        assertTrue(store.save(snapshot))
        val restored = store.load() ?: error("snapshot_not_restored")

        assertTrue(restored.artifactManifests.isEmpty())
        assertEquals(listOf(desktop), restored.maintenance.installedManifests)
        assertEquals(setOf(desktop.componentId), restored.evidence.installed)
    }

    @Test
    fun `runtime projected baseline is written without a second projection`() {
        val file = Files.createTempDirectory("maintenance-store-projected")
            .resolve("session.json")
            .toFile()
        val store = MaintenanceSessionStore(file)
        val baseline = checkNotNull(
            MaintenanceBaselineProjector.project(maintenanceSnapshot(manifests(1L), manifests(2L))),
        )

        assertTrue(store.saveProjectedBaseline(baseline))
        assertEquals(baseline.evidence.installed, store.load()?.evidence?.installed)
    }

    @Test
    fun `legacy artifact identity backed by installation evidence is migrated before empty identity rejection`() {
        val file = Files.createTempDirectory("maintenance-store-legacy-identity")
            .resolve("session.json")
            .toFile()
        val desktop = manifest("desktop", 1L, required = true)
        val store = MaintenanceSessionStore(file)
        val snapshot = maintenanceSnapshot(listOf(desktop), listOf(desktop)).copy(
            maintenance = maintenanceSnapshot(listOf(desktop), listOf(desktop)).maintenance.copy(
                installedManifests = listOf(desktop),
            ),
        )

        assertTrue(store.save(snapshot))
        val stored = file.readText()
        val installedJson = Regex("\"installedManifests\":(\\[.*?\\]),\"availableManifests\"")
            .find(stored)
            ?.groupValues
            ?.get(1)
            ?: error("installed_manifest_wire_missing")
        file.writeText(
            stored
                .replace("\"artifactManifests\":[]", "\"artifactManifests\":$installedJson")
                .replace("\"installedManifests\":$installedJson", "\"installedManifests\":[]"),
        )

        val restored = store.load() ?: error("legacy_snapshot_not_restored")
        assertEquals(listOf(desktop), restored.maintenance.installedManifests)
        assertEquals(setOf(desktop.componentId), restored.evidence.installed)
    }

    @Test
    fun `empty identity baseline is neither saved nor restored as maintenance`() {
        val file = Files.createTempDirectory("maintenance-store-empty-identity")
            .resolve("session.json")
            .toFile()
        val desktop = manifest("desktop", 1L, required = true)
        val base = maintenanceSnapshot(listOf(desktop), listOf(desktop))
        val emptyIdentity = base.copy(
            evidence = SessionEvidence(),
            maintenance = base.maintenance.copy(
                installedManifests = emptyList(),
                managedApplications = emptyList(),
                managedApplicationsState = MaintenanceInventoryState.READY,
            ),
        )
        val store = MaintenanceSessionStore(file)

        assertFalse(store.save(emptyIdentity))
        file.writeText("{\"schemaVersion\":1}")
        assertNull(store.load())
        assertFalse(file.exists())
    }

    @Test
    fun `initial completion marker survives without inventing installed apk identities`() {
        val file = Files.createTempDirectory("maintenance-store-initial-complete")
            .resolve("session.json")
            .toFile()
        val base = maintenanceSnapshot(manifests(1L), emptyList()).copy(artifactManifests = emptyList())
        val snapshot = base.copy(
            evidence = SessionEvidence(
                installed = setOf("desktop", "lyrics"),
                configured = setOf("desktop", "lyrics"),
                available = setOf("desktop", "lyrics"),
            ),
            maintenance = base.maintenance.copy(
                managedApplicationsState = MaintenanceInventoryState.READY,
                managedApplications = listOf(
                    ManagedApplicationStatus(
                        "desktop",
                        InstallerComponentTrustRegistry.CURRENT_DESKTOP_TEST_PACKAGE_NAME,
                        installed = true,
                        versionCode = 2L,
                    ),
                    ManagedApplicationStatus(
                        "lyrics",
                        InstallerComponentTrustRegistry.CURRENT_LYRICS_TEST_PACKAGE_NAME,
                        installed = true,
                        versionCode = 2L,
                    ),
                ),
                availableComponents = base.components,
                initialInstallationCompleted = true,
            ),
        )
        val store = MaintenanceSessionStore(file)

        assertTrue(store.save(snapshot))
        val restored = store.load() ?: error("initial_completion_not_restored")
        assertTrue(restored.maintenance.initialInstallationCompleted)
        assertTrue(restored.maintenance.installedManifests.isEmpty())
        assertEquals(MaintenanceInventoryState.READY, restored.maintenance.managedApplicationsState)
        assertEquals(setOf("desktop", "lyrics"), restored.evidence.installed)
    }

    @Test
    fun `single update after initial completion preserves the complete installed inventory`() {
        val file = Files.createTempDirectory("maintenance-store-initial-update")
            .resolve("session.json")
            .toFile()
        val desktop = manifest("desktop", 2L, required = true)
        val cast = manifest("cast", 4L, required = false)
        val base = maintenanceSnapshot(listOf(desktop), listOf(desktop, cast))
        val snapshot = base.copy(
            state = InstallationSessionState.SUCCEEDED,
            components = listOf(desktop, cast).map { it.toComponentDescriptor() },
            artifactManifests = listOf(cast),
            artifactCatalogStage = ArtifactCatalogStage.PREPARED,
            evidence = SessionEvidence(
                installed = setOf("desktop", "cast"),
                configured = setOf("desktop", "cast"),
                available = setOf("desktop", "cast"),
            ),
            maintenance = base.maintenance.copy(
                managedApplicationsState = MaintenanceInventoryState.READY,
                managedApplications = listOf(
                    ManagedApplicationStatus(
                        componentId = "desktop",
                        packageName = desktop.packageName,
                        installed = true,
                        versionCode = desktop.apkVersion.code,
                    ),
                    ManagedApplicationStatus(
                        componentId = "cast",
                        packageName = cast.packageName,
                        installed = true,
                        versionCode = cast.apkVersion.code,
                    ),
                ),
                initialInstallationCompleted = true,
                availableComponents = listOf(desktop, cast).map { it.toComponentDescriptor() },
                installedManifests = emptyList(),
                availableManifests = listOf(desktop, cast),
            ),
        )
        val store = MaintenanceSessionStore(file)

        val baseline = checkNotNull(MaintenanceBaselineProjector.project(snapshot))
        assertEquals(setOf("desktop", "cast"), baseline.evidence.installed)
        assertEquals(listOf("cast"), baseline.maintenance.installedManifests.map { it.componentId })
        assertTrue(store.saveProjectedBaseline(baseline))

        val restored = store.load() ?: error("initial_update_not_restored")
        assertTrue(restored.maintenance.initialInstallationCompleted)
        assertEquals(setOf("desktop", "cast"), restored.evidence.installed)
        assertEquals(
            setOf("desktop", "cast"),
            restored.maintenance.managedApplications.map { it.componentId }.toSet(),
        )
        assertEquals(2L, restored.maintenance.managedApplications.single { it.componentId == "desktop" }.versionCode)
        assertEquals(4L, restored.maintenance.managedApplications.single { it.componentId == "cast" }.versionCode)
        assertEquals(listOf("cast"), restored.maintenance.installedManifests.map { it.componentId })
    }

    @Test
    fun `partial missing-only batch persists only its merged installed baseline`() {
        val file = Files.createTempDirectory("maintenance-store-partial-batch").resolve("session.json").toFile()
        val desktop = manifest("desktop", 1L, required = true)
        val lyrics = manifest("lyrics", 2L, required = false)
        val base = maintenanceSnapshot(listOf(desktop), listOf(desktop, lyrics))
        val snapshot = base.copy(
            components = listOf(desktop, lyrics).map { it.toComponentDescriptor() },
            artifactManifests = listOf(lyrics),
            artifactCatalogStage = ArtifactCatalogStage.PREPARED,
            selectedOptionalComponentIds = setOf("lyrics"),
            evidence = SessionEvidence(installed = setOf("desktop", "lyrics")),
            maintenance = base.maintenance.copy(
                installedManifests = listOf(desktop),
                availableManifests = listOf(desktop, lyrics),
                managedApplications = listOf(
                    ManagedApplicationStatus(
                        componentId = "desktop",
                        packageName = desktop.packageName,
                        installed = true,
                        versionCode = desktop.apkVersion.code,
                    ),
                    ManagedApplicationStatus(
                        componentId = "lyrics",
                        packageName = lyrics.packageName,
                        installed = true,
                        versionCode = lyrics.apkVersion.code,
                    ),
                ),
            ),
        )
        val store = MaintenanceSessionStore(file)

        assertTrue(store.save(snapshot))
        val restored = store.load() ?: error("snapshot_not_restored")

        assertTrue(restored.artifactManifests.isEmpty())
        assertEquals(
            setOf("desktop", "lyrics"),
            restored.maintenance.installedManifests.map { it.componentId }.toSet(),
        )
        assertEquals(setOf("desktop", "lyrics"), restored.maintenance.availableManifests.map { it.componentId }.toSet())
        assertTrue(restored.selectedOptionalComponentIds.isEmpty())
        assertNull(restored.installationBatch)
        assertNull(restored.checkpoint)
    }

    @Test
    fun `corrupt records and non maintenance states are rejected`() {
        val root = Files.createTempDirectory("maintenance-store-invalid").toFile()
        val file = root.resolve("session.json")
        val store = MaintenanceSessionStore(file)

        file.writeText("{not-json")
        assertNull(store.load())
        assertFalse(store.save(InstallationSessionSnapshot(InstallationSessionState.IDLE)))
    }

    @Test
    fun `dynamic app identity and typed setup survive a cold start`() {
        val file = Files.createTempDirectory("maintenance-store-dynamic").resolve("session.json").toFile()
        val setup = AuthorizationSetupDeclaration(
            appOps = setOf(ManagedAppOp.SYSTEM_ALERT_WINDOW),
            secureComponents = setOf(
                AuthorizationSetupDeclaration.SecureComponent(
                    setting = ManagedSecureComponentList.ENABLED_ACCESSIBILITY_SERVICES,
                    targetComponent = "com.tcrrry.notes/com.tcrrry.notes.Service",
                ),
            ),
            launchComponent = "com.tcrrry.notes/com.tcrrry.notes.MainActivity",
        )
        val desktop = manifest("desktop", 1L, required = true)
        val notes = manifest("notes", 1L, required = false).copy(
            packageName = "com.tcrrry.notes",
            deviceSetup = setup,
            sortOrder = 30,
        )
        val snapshot = maintenanceSnapshot(listOf(desktop, notes), listOf(desktop, notes)).copy(
            selectedOptionalComponentIds = setOf("notes"),
            maintenance = maintenanceSnapshot(listOf(desktop, notes), listOf(desktop, notes)).maintenance.copy(
                managedApplications = listOf(
                    ManagedApplicationStatus("desktop", AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME, true),
                    ManagedApplicationStatus("notes", "com.tcrrry.notes", true),
                ),
            ),
        )
        val store = MaintenanceSessionStore(file)

        assertTrue(store.save(snapshot))
        val restored = store.load()

        assertTrue(restored?.artifactManifests.orEmpty().isEmpty())
        assertEquals(
            "com.tcrrry.notes",
            restored?.maintenance?.availableManifests?.single { it.componentId == "notes" }?.packageName,
        )
        assertEquals(
            setup,
            restored?.maintenance?.availableManifests?.single { it.componentId == "notes" }?.deviceSetup,
        )
        assertTrue(restored?.selectedOptionalComponentIds.orEmpty().isEmpty())
    }

    @Test
    fun `invalid stored status or typed setup is rejected instead of downgraded`() {
        val root = Files.createTempDirectory("maintenance-store-strict").toFile()
        val file = root.resolve("session.json")
        try {
            val snapshot = maintenanceSnapshot(manifests(1L), manifests(2L))
            val store = MaintenanceSessionStore(file)
            assertTrue(store.save(snapshot))
            file.writeText(file.readText().replace("\"status\":\"AVAILABLE\"", "\"status\":\"NOT_A_STATUS\""))
            assertNull(store.load())

            val setup = AuthorizationSetupDeclaration(appOps = setOf(ManagedAppOp.SYSTEM_ALERT_WINDOW))
            val dynamic = manifest("notes", 1L, required = false).copy(
                packageName = "com.tcrrry.notes",
                deviceSetup = setup,
            )
            val setupSnapshot = maintenanceSnapshot(
                listOf(manifest("desktop", 1L, required = true), dynamic),
                listOf(manifest("desktop", 2L, required = true), dynamic),
            ).copy(selectedOptionalComponentIds = setOf("notes"))
            assertTrue(store.save(setupSnapshot))
            file.writeText(file.readText().replace("SYSTEM_ALERT_WINDOW", "NOT_A_TYPED_ACTION"))
            assertNull(store.load())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `installed unlisted component is restored only once`() {
        val file = Files.createTempDirectory("maintenance-store-unlisted").resolve("session.json").toFile()
        val desktop = manifest("desktop", 1L, required = true)
        val base = maintenanceSnapshot(listOf(desktop), listOf(desktop)).copy(
            selectedOptionalComponentIds = emptySet(),
        )
        val legacy = ComponentDescriptor(
            id = "legacy",
            displayName = "legacy",
            required = false,
            status = ComponentStatus.UNLISTED,
            errorReason = "unlisted",
        )
        val snapshot = base.copy(
            components = base.components + legacy,
            evidence = base.evidence.copy(installed = setOf("desktop", "legacy")),
            maintenance = base.maintenance.copy(
                managedApplications = listOf(
                    ManagedApplicationStatus("desktop", AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME, true),
                    ManagedApplicationStatus("legacy", "com.tcrrry.legacy", true),
                ),
            ),
        )
        val store = MaintenanceSessionStore(file)

        assertTrue(store.save(snapshot))
        val restored = store.load() ?: error("snapshot_not_restored")
        assertEquals(1, restored.components.count { it.id == "legacy" })
    }

    private fun maintenanceSnapshot(
        installed: List<ArtifactManifest>,
        available: List<ArtifactManifest>,
    ): InstallationSessionSnapshot {
        val ids = installed.map { it.componentId }.toSet()
        return InstallationSessionSnapshot(
            state = InstallationSessionState.MAINTENANCE,
            device = DeviceSummary(
                id = "vehicle-1",
                displayName = "S56_HQX",
                connectionStatus = DeviceConnectionStatus.CONFIRMED,
                androidSdk = 28,
                capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
            ),
            components = installed.map { it.toComponentDescriptor() },
            selectedOptionalComponentIds = setOf(AuthorizationPlanFactory.LYRICS_COMPONENT_ID),
            artifactManifests = installed,
            catalogVersion = "catalog-1",
            catalogKeyId = "key-1",
            catalogSignatureAlgorithm = "Ed25519",
            evidence = SessionEvidence(
                installed = ids,
                configured = ids,
                available = ids,
            ),
            maintenance = MaintenanceSnapshot(
                lastAction = MaintenanceActionRecord(
                    actionId = MaintenanceActionId.CHECK_UPDATES,
                    status = MaintenanceActionStatus.SUCCEEDED,
                    resultCode = "up_to_date",
                ),
                managedApplications = listOf(
                    ManagedApplicationStatus(
                        AuthorizationPlanFactory.DESKTOP_COMPONENT_ID,
                        AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME,
                        true,
                    ),
                    ManagedApplicationStatus(
                        AuthorizationPlanFactory.LYRICS_COMPONENT_ID,
                        AuthorizationPlanFactory.LYRICS_PACKAGE_NAME,
                        true,
                    ),
                    ManagedApplicationStatus(
                        AuthorizationPlanFactory.FILE_MANAGER_COMPONENT_ID,
                        AuthorizationPlanFactory.FILE_MANAGER_PACKAGE_NAME,
                        false,
                    ),
                ),
                availableManifests = available,
                availableCatalogVersion = "catalog-2",
                availableCatalogKeyId = "key-2",
                availableCatalogSignatureAlgorithm = "Ed25519",
            ),
        )
    }

    private fun manifests(versionCode: Long): List<ArtifactManifest> = listOf(
        manifest(AuthorizationPlanFactory.DESKTOP_COMPONENT_ID, versionCode, required = true),
        manifest(AuthorizationPlanFactory.LYRICS_COMPONENT_ID, versionCode, required = false),
        manifest(AuthorizationPlanFactory.FILE_MANAGER_COMPONENT_ID, versionCode, required = false),
    )

    private fun manifest(componentId: String, versionCode: Long, required: Boolean): ArtifactManifest {
        val packageName = when (componentId) {
            AuthorizationPlanFactory.DESKTOP_COMPONENT_ID -> AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME
            AuthorizationPlanFactory.LYRICS_COMPONENT_ID -> AuthorizationPlanFactory.LYRICS_PACKAGE_NAME
            AuthorizationPlanFactory.FILE_MANAGER_COMPONENT_ID -> AuthorizationPlanFactory.FILE_MANAGER_PACKAGE_NAME
            else -> "com.tcrrry.$componentId"
        }
        return ArtifactManifest(
            schemaVersion = 1,
            componentId = componentId,
            displayName = componentId,
            required = required,
            version = ArtifactVersion("1.$versionCode", versionCode),
            compatibility = CompatibilityRange(minAndroidSdk = 26, maxAndroidSdk = 30),
            archiveFileName = "$componentId.zip",
            archiveSizeBytes = 100L,
            archiveSha256 = "11".repeat(32),
            apkEntryName = "$componentId.apk",
            apkSizeBytes = 50L,
            apkSha256 = "22".repeat(32),
            packageName = packageName,
            apkVersion = ArtifactVersion("1.$versionCode", versionCode),
            certificateSha256 = "33".repeat(32),
            sources = listOf(
                ArtifactSource(ArtifactSourceKind.LANZOU_SHARE, "https://wwatl.lanzouw.com/i$componentId"),
                ArtifactSource(ArtifactSourceKind.R2, "https://assets.r2.dev/$componentId.zip"),
                ArtifactSource(
                    ArtifactSourceKind.GITHUB_RELEASES,
                    "https://github.com/example/repo/releases/download/v1/$componentId.zip",
                ),
            ),
        )
    }
}
