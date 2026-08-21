package com.tcrrry.helper.application.maintenance

import com.tcrrry.helper.domain.artifact.ArtifactManifest
import com.tcrrry.helper.domain.artifact.ArtifactSource
import com.tcrrry.helper.domain.artifact.ArtifactSourceKind
import com.tcrrry.helper.domain.artifact.ArtifactVersion
import com.tcrrry.helper.domain.artifact.CompatibilityRange
import com.tcrrry.helper.domain.artifact.toComponentDescriptor
import com.tcrrry.helper.domain.device.AuthorizationPlanFactory
import com.tcrrry.helper.domain.device.DeviceCapability
import com.tcrrry.helper.domain.session.DeviceConnectionStatus
import com.tcrrry.helper.domain.session.DeviceSummary
import com.tcrrry.helper.domain.session.InstallationSessionSnapshot
import com.tcrrry.helper.domain.session.InstallationSessionState
import com.tcrrry.helper.domain.session.MaintenanceActionId
import com.tcrrry.helper.domain.session.MaintenanceActionRecord
import com.tcrrry.helper.domain.session.MaintenanceActionStatus
import com.tcrrry.helper.domain.session.ManagedApplicationStatus
import com.tcrrry.helper.domain.session.MaintenanceSnapshot
import com.tcrrry.helper.domain.session.SessionEvidence
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
        val snapshot = maintenanceSnapshot(installed, available)

        assertTrue(store.save(snapshot))
        val restored = store.load()

        assertEquals(InstallationSessionState.MAINTENANCE, restored?.state)
        assertEquals(DeviceConnectionStatus.DISCONNECTED, restored?.device?.connectionStatus)
        assertEquals(snapshot.device?.id, restored?.device?.id)
        assertEquals(installed, restored?.artifactManifests)
        assertEquals(available, restored?.maintenance?.availableManifests)
        assertEquals(snapshot.evidence, restored?.evidence)
        assertEquals(snapshot.maintenance.managedApplications, restored?.maintenance?.managedApplications)
        assertEquals(snapshot.maintenance.lastAction, restored?.maintenance?.lastAction)
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
            else -> error("unexpected component")
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
