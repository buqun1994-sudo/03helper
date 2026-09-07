package com.ninepointnine.helper.domain.session

import com.ninepointnine.helper.domain.artifact.ArtifactSource
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactVerification
import com.ninepointnine.helper.domain.artifact.ArchiveDownloadEvidence
import com.ninepointnine.helper.domain.artifact.ArchiveVerificationEvidence
import com.ninepointnine.helper.domain.artifact.ApkExtractionEvidence
import com.ninepointnine.helper.domain.artifact.SourceSelectionEvidence
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.CompatibilityRange
import com.ninepointnine.helper.domain.artifact.InstallerSelfIdentity
import com.ninepointnine.helper.domain.artifact.toComponentDescriptor
import com.ninepointnine.helper.domain.device.DeviceCapability
import com.ninepointnine.helper.domain.device.AuthorizationPlanFactory
import com.ninepointnine.helper.domain.device.InstalledArtifactEvidence
import com.ninepointnine.helper.ui.state.InstallUiState
import com.ninepointnine.helper.ui.state.InstallUiStateMapper
import com.ninepointnine.helper.domain.session.ResultKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceComponentUpdateTest {
    @Test
    fun `third party update and maintenance entry do not depend on desktop`() {
        val app = manifest("player", 2L, required = false).copy(packageName = "org.independent.player")
        val descriptor = app.toComponentDescriptor()
        val base = InstallationSessionSnapshot(
            state = InstallationSessionState.MAINTENANCE,
            device = DeviceSummary("car", "Car", DeviceConnectionStatus.CONFIRMED, androidSdk = 28,
                capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ)),
            components = listOf(descriptor),
            maintenance = MaintenanceSnapshot(
                availableComponents = listOf(descriptor), availableManifests = listOf(app),
                managedApplicationsState = MaintenanceInventoryState.READY,
                managedApplications = listOf(ManagedApplicationStatus("player", app.packageName, true, versionCode = 1L)),
                updateStatuses = listOf(MaintenanceUpdateStatus("player", "Player", "v2", "v1", MaintenanceUpdateState.UPDATE_AVAILABLE)),
            ),
        )
        val session = InstallationSession(base)
        session.dispatch(InstallationSessionCommand.StartMaintenanceComponentUpdate("player"))
        assertEquals(InstallationSessionState.SELECTION_CONFIRMED, session.currentSnapshot().state)
        assertEquals(setOf("player"), session.currentSnapshot().installationBatch?.selectedComponentIds)
        assertTrue(session.currentSnapshot().installationBatch?.preinstalledComponentIds.isNullOrEmpty())

        val completed = InstallationSession(base.copy(
            state = InstallationSessionState.COMPLETED_WITH_ERRORS,
            artifactManifests = listOf(app),
            evidence = SessionEvidence(installed = setOf("player")),
        ))
        completed.dispatch(InstallationSessionCommand.EnterMaintenance)
        assertEquals(InstallationSessionState.MAINTENANCE, completed.currentSnapshot().state)
    }

    @Test
    fun `optional update freezes only target while reusing trusted desktop prerequisite`() {
        val desktop = manifest("desktop", 1L, required = true)
        val lyrics = manifest("lyrics", 1L, required = false)
        val session = InstallationSession(initialSnapshot(desktop, lyrics).copy(
            maintenance = initialSnapshot(desktop, lyrics).maintenance.copy(
                updateStatuses = listOf(
                    MaintenanceUpdateStatus(
                        componentId = "desktop",
                        displayName = "03桌面",
                        versionLabel = "v1",
                        installedVersionLabel = "v1",
                        state = MaintenanceUpdateState.CURRENT,
                    ),
                    MaintenanceUpdateStatus(
                        componentId = "lyrics",
                        displayName = "03歌词",
                        versionLabel = "v2",
                        installedVersionLabel = "v1",
                        state = MaintenanceUpdateState.UPDATE_AVAILABLE,
                    ),
                ),
            ),
        ))

        session.dispatch(InstallationSessionCommand.StartMaintenanceComponentUpdate("lyrics"))

        val batch = session.currentSnapshot().installationBatch
        assertEquals(InstallationSessionState.SELECTION_CONFIRMED, session.currentSnapshot().state)
        assertEquals(InstallationFlow.MAINTENANCE_INSTALL, batch?.flow)
        assertEquals(setOf("lyrics"), batch?.selectedComponentIds)
        assertTrue(batch?.reusableComponentIds.isNullOrEmpty())
        assertEquals(setOf("desktop"), batch?.preinstalledComponentIds)
        assertEquals(setOf("lyrics"), batch?.preparationComponentIds)
        assertEquals(setOf("lyrics"), batch?.resultComponentIds)
    }

    @Test
    fun `desktop update is never classified as a reusable prerequisite`() {
        val desktop = manifest("desktop", 1L, required = true)
        val lyrics = manifest("lyrics", 1L, required = false)
        val base = initialSnapshot(desktop, lyrics)
        val session = InstallationSession(
            initialSnapshot = base.copy(
                maintenance = base.maintenance.copy(
                    updateStatuses = listOf(
                        MaintenanceUpdateStatus(
                            componentId = "desktop",
                            displayName = "03桌面",
                            versionLabel = "v2",
                            installedVersionLabel = "v1",
                            state = MaintenanceUpdateState.UPDATE_AVAILABLE,
                        ),
                    ),
                ),
            ),
        )

        session.dispatch(InstallationSessionCommand.StartMaintenanceComponentUpdate("desktop"))

        val batch = session.currentSnapshot().installationBatch
        assertEquals(setOf("desktop"), batch?.selectedComponentIds)
        assertTrue(batch?.reusableComponentIds.isNullOrEmpty())
        assertEquals(setOf("desktop"), batch?.preparationComponentIds)
        assertEquals(setOf("desktop"), batch?.resultComponentIds)
    }

    @Test
    fun `helper update uses the shared preparation states and waits for an install command`() {
        val desktop = manifest("desktop", 1L, required = true)
        val lyrics = manifest("lyrics", 1L, required = false)
        val base = initialSnapshot(desktop, lyrics)
        val self = manifest(InstallerSelfIdentity.COMPONENT_ID, 2L, required = false).copy(
            packageName = InstallerSelfIdentity.PACKAGE_NAME,
            displayName = "03车机助手",
        )
        val session = InstallationSession(
            initialSnapshot = base.copy(
                maintenance = base.maintenance.copy(
                    updateStatuses = listOf(
                        MaintenanceUpdateStatus(
                            componentId = InstallerSelfIdentity.COMPONENT_ID,
                            displayName = "03车机助手",
                            versionLabel = "v2",
                            installedVersionLabel = "v1",
                            state = MaintenanceUpdateState.UPDATE_AVAILABLE,
                            isSelf = true,
                        ),
                    ),
                ),
            ),
        )

        session.dispatch(InstallationSessionCommand.StartMaintenanceComponentUpdate(InstallerSelfIdentity.COMPONENT_ID))
        session.dispatch(InstallationSessionCommand.BeginPipeline)
        val batch = checkNotNull(session.currentSnapshot().installationBatch)
        session.dispatchEvent(
            InstallationSessionEvent.ArtifactBatchPrepared(
                batchId = batch.batchId,
                manifests = listOf(self),
                sourceSelections = listOf(SourceSelectionEvidence(self.componentId, ArtifactSourceKind.LANZOU_SHARE)),
                archives = listOf(ArchiveDownloadEvidence(self.componentId, self.archiveSizeBytes, self.archiveSha256)),
                archiveVerifications = listOf(ArchiveVerificationEvidence(self.componentId, self.archiveSizeBytes, self.archiveSha256)),
                extractions = listOf(ApkExtractionEvidence(self.componentId, self.apkEntryName, self.apkSizeBytes, self.apkSha256)),
                verifications = listOf(
                    ArtifactVerification(
                        componentId = self.componentId,
                        sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                        archiveSizeBytes = self.archiveSizeBytes,
                        archiveSha256 = self.archiveSha256,
                        apkSizeBytes = self.apkSizeBytes,
                        apkSha256 = self.apkSha256,
                        packageName = self.packageName,
                        apkVersion = self.apkVersion,
                        certificateSha256 = self.certificateSha256,
                        archiveDeleted = true,
                    ),
                ),
            ),
        )

        assertEquals(
            "state=${session.currentSnapshot().state}, failure=${session.currentSnapshot().failure}",
            InstallationSessionState.ARTIFACTS_READY,
            session.currentSnapshot().state,
        )
        session.dispatch(InstallationSessionCommand.InstallPreparedSelfUpdate)
        assertEquals(InstallationSessionState.INSTALLING, session.currentSnapshot().state)
        assertEquals(InstallationFlow.SELF_UPDATE, session.currentSnapshot().installationFlow)
    }

    @Test
    fun `self update success restores the maintenance component projection`() {
        val desktop = manifest("desktop", 1L, required = true)
        val lyrics = manifest("lyrics", 1L, required = false)
        val self = manifest(InstallerSelfIdentity.COMPONENT_ID, 2L, required = false).copy(
            packageName = InstallerSelfIdentity.PACKAGE_NAME,
            displayName = "03车机助手",
        )
        val base = initialSnapshot(desktop, lyrics).copy(
            maintenance = initialSnapshot(desktop, lyrics).maintenance.copy(
                updateStatuses = listOf(
                    MaintenanceUpdateStatus(
                        componentId = InstallerSelfIdentity.COMPONENT_ID,
                        displayName = "03车机助手",
                        versionLabel = "v2",
                        installedVersionLabel = "v1",
                        state = MaintenanceUpdateState.UPDATE_AVAILABLE,
                        isSelf = true,
                    ),
                ),
            ),
        )
        val session = InstallationSession(initialSnapshot = base)

        session.dispatch(InstallationSessionCommand.StartMaintenanceComponentUpdate(InstallerSelfIdentity.COMPONENT_ID))
        session.dispatch(InstallationSessionCommand.BeginPipeline)
        val batch = checkNotNull(session.currentSnapshot().installationBatch)
        session.dispatchEvent(selfPreparedEvent(batch.batchId, self))
        session.dispatch(InstallationSessionCommand.InstallPreparedSelfUpdate)
        session.dispatchEvent(selfCompletedEvent(batch.batchId, self))
        assertEquals(InstallationSessionState.SUCCEEDED, session.currentSnapshot().state)

        session.dispatch(InstallationSessionCommand.EnterMaintenance)

        val restored = session.currentSnapshot()
        assertEquals(InstallationSessionState.MAINTENANCE, restored.state)
        assertEquals(emptySet<String>(), restored.selectedOptionalComponentIds)
        assertEquals(
            setOf("desktop", "lyrics"),
            restored.maintenance.availableComponents.map { it.id }.toSet(),
        )
        assertTrue(restored.components.any { it.id == "desktop" })
        assertTrue(restored.components.any { it.id == "lyrics" })
        assertTrue(
            restored.components
                .filter { InstallerSelfIdentity.isSelfComponentId(it.id) }
                .all { it.status == ComponentStatus.UNLISTED },
        )
    }

    @Test
    fun `self update failure restores the maintenance component projection`() {
        val desktop = manifest("desktop", 1L, required = true)
        val lyrics = manifest("lyrics", 1L, required = false)
        val self = manifest(InstallerSelfIdentity.COMPONENT_ID, 2L, required = false).copy(
            packageName = InstallerSelfIdentity.PACKAGE_NAME,
            displayName = "03车机助手",
        )
        val base = initialSnapshot(desktop, lyrics).copy(
            maintenance = initialSnapshot(desktop, lyrics).maintenance.copy(
                updateStatuses = listOf(
                    MaintenanceUpdateStatus(
                        componentId = InstallerSelfIdentity.COMPONENT_ID,
                        displayName = "03车机助手",
                        versionLabel = "v2",
                        installedVersionLabel = "v1",
                        state = MaintenanceUpdateState.UPDATE_AVAILABLE,
                        isSelf = true,
                    ),
                ),
            ),
        )
        val session = InstallationSession(initialSnapshot = base)

        session.dispatch(InstallationSessionCommand.StartMaintenanceComponentUpdate(InstallerSelfIdentity.COMPONENT_ID))
        session.dispatch(InstallationSessionCommand.BeginPipeline)
        val batch = checkNotNull(session.currentSnapshot().installationBatch)
        session.dispatchEvent(selfPreparedEvent(batch.batchId, self))
        session.dispatch(InstallationSessionCommand.InstallPreparedSelfUpdate)
        session.dispatchEvent(
            InstallationSessionEvent.FatalError(
                category = FailureCategory.INSTALLATION,
                reasonCode = "self_update_cancelled",
            ),
        )
        assertEquals(InstallationSessionState.FAILED, session.currentSnapshot().state)

        session.dispatch(InstallationSessionCommand.EnterMaintenance)

        val restored = session.currentSnapshot()
        assertEquals(InstallationSessionState.MAINTENANCE, restored.state)
        assertEquals(emptySet<String>(), restored.selectedOptionalComponentIds)
        assertEquals(
            setOf("desktop", "lyrics"),
            restored.maintenance.availableComponents.map { it.id }.toSet(),
        )
        assertTrue(restored.components.any { it.id == "desktop" })
        assertTrue(restored.components.any { it.id == "lyrics" })
        assertFalse(restored.components.any { it.id == InstallerSelfIdentity.COMPONENT_ID && it.status != ComponentStatus.UNLISTED })
    }

    @Test
    fun `maintenance result hides reusable desktop when target update fails`() {
        val desktop = manifest("desktop", 1L, required = true)
        val lyrics = manifest("lyrics", 2L, required = false).copy(displayName = "03歌词")
        val base = initialSnapshot(desktop, manifest("lyrics", 1L, required = false)).copy(
            maintenance = initialSnapshot(desktop, manifest("lyrics", 1L, required = false)).maintenance.copy(
                updateStatuses = listOf(
                    MaintenanceUpdateStatus(
                        componentId = "desktop",
                        displayName = "03桌面",
                        versionLabel = "v1",
                        installedVersionLabel = "v1",
                        state = MaintenanceUpdateState.CURRENT,
                    ),
                    MaintenanceUpdateStatus(
                        componentId = "lyrics",
                        displayName = "03歌词",
                        versionLabel = "v2",
                        installedVersionLabel = "v1",
                        state = MaintenanceUpdateState.UPDATE_AVAILABLE,
                    ),
                ),
            ),
        )
        val session = InstallationSession(initialSnapshot = base)

        session.dispatch(InstallationSessionCommand.StartMaintenanceComponentUpdate("lyrics"))
        val batch = checkNotNull(session.currentSnapshot().installationBatch)
        assertEquals(setOf("lyrics"), batch.selectedComponentIds)
        assertTrue(batch.reusableComponentIds.isEmpty())
        assertEquals(setOf("desktop"), batch.preinstalledComponentIds)
        assertEquals(setOf("lyrics"), batch.resultComponentIds)

        session.dispatch(InstallationSessionCommand.BeginPipeline)
        session.dispatchEvent(
            InstallationSessionEvent.ArtifactBatchPrepared(
                batchId = batch.batchId,
                manifests = listOf(lyrics),
                sourceSelections = listOf(SourceSelectionEvidence("lyrics", ArtifactSourceKind.LANZOU_SHARE)),
                archives = listOf(ArchiveDownloadEvidence("lyrics", lyrics.archiveSizeBytes, lyrics.archiveSha256)),
                archiveVerifications = listOf(
                    ArchiveVerificationEvidence("lyrics", lyrics.archiveSizeBytes, lyrics.archiveSha256),
                ),
                extractions = listOf(
                    ApkExtractionEvidence("lyrics", lyrics.apkEntryName, lyrics.apkSizeBytes, lyrics.apkSha256),
                ),
                verifications = listOf(
                    ArtifactVerification(
                        componentId = "lyrics",
                        sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                        archiveSizeBytes = lyrics.archiveSizeBytes,
                        archiveSha256 = lyrics.archiveSha256,
                        apkSizeBytes = lyrics.apkSizeBytes,
                        apkSha256 = lyrics.apkSha256,
                        packageName = lyrics.packageName,
                        apkVersion = lyrics.apkVersion,
                        certificateSha256 = lyrics.certificateSha256,
                        archiveDeleted = true,
                    ),
                ),
            ),
        )
        session.dispatchEvent(InstallationSessionEvent.InstallationStarted(listOf("lyrics")))
        session.dispatchEvent(
            InstallationSessionEvent.InstallationBatchCompleted(
                InstallationBatchReceipt(
                    batchId = batch.batchId,
                    components = listOf(
                        InstallationComponentReceipt(
                            componentId = "lyrics",
                            installation = InstallationStageReceipt(
                                status = InstallationStageReceiptStatus.FAILED,
                                reasonCode = "adb_pm_install_failed",
                                retryable = true,
                            ),
                            authorization = AuthorizationStageReceipt(
                                status = AuthorizationStageReceiptStatus.NOT_ATTEMPTED,
                                reasonCode = "installation_failed",
                                retryable = true,
                            ),
                            availability = AvailabilityStageReceipt(
                                status = AvailabilityStageReceiptStatus.NOT_ATTEMPTED,
                                reasonCode = "installation_failed",
                                retryable = true,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val state = InstallUiStateMapper.map(session.currentSnapshot()) as InstallUiState.Result
        assertEquals(ResultKind.INSTALLATION_FAILED, state.kind)
        assertEquals(listOf("03歌词"), state.componentResults.map { it.componentName })
        assertTrue(state.componentResults.none { it.componentName == "03桌面" })
        assertEquals(ComponentResultStatus.NOT_INSTALLED, state.componentResults.single().status)
        assertEquals("03歌词：车机拒绝安装 APK：检查车机存储和版本", state.componentResults.single().errorReason)
    }

    private fun selfPreparedEvent(
        batchId: Long,
        self: ArtifactManifest,
    ): InstallationSessionEvent.ArtifactBatchPrepared = InstallationSessionEvent.ArtifactBatchPrepared(
        batchId = batchId,
        manifests = listOf(self),
        sourceSelections = listOf(SourceSelectionEvidence(self.componentId, ArtifactSourceKind.LANZOU_SHARE)),
        archives = listOf(ArchiveDownloadEvidence(self.componentId, self.archiveSizeBytes, self.archiveSha256)),
        archiveVerifications = listOf(
            ArchiveVerificationEvidence(self.componentId, self.archiveSizeBytes, self.archiveSha256),
        ),
        extractions = listOf(ApkExtractionEvidence(self.componentId, self.apkEntryName, self.apkSizeBytes, self.apkSha256)),
        verifications = listOf(
            ArtifactVerification(
                componentId = self.componentId,
                sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                archiveSizeBytes = self.archiveSizeBytes,
                archiveSha256 = self.archiveSha256,
                apkSizeBytes = self.apkSizeBytes,
                apkSha256 = self.apkSha256,
                packageName = self.packageName,
                apkVersion = self.apkVersion,
                certificateSha256 = self.certificateSha256,
                archiveDeleted = true,
            ),
        ),
    )

    private fun selfCompletedEvent(
        batchId: Long,
        self: ArtifactManifest,
    ): InstallationSessionEvent.InstallationBatchCompleted = InstallationSessionEvent.InstallationBatchCompleted(
        InstallationBatchReceipt(
            batchId = batchId,
            components = listOf(
                InstallationComponentReceipt(
                    componentId = self.componentId,
                    installation = InstallationStageReceipt(
                        status = InstallationStageReceiptStatus.VERIFIED,
                        evidence = InstalledArtifactEvidence(
                            componentId = self.componentId,
                            packageName = self.packageName,
                            version = self.apkVersion,
                            apkSizeBytes = self.apkSizeBytes,
                            apkSha256 = self.apkSha256,
                            certificateSha256 = self.certificateSha256,
                        ),
                        writeConfirmed = true,
                        operationConfirmed = true,
                    ),
                    authorization = AuthorizationStageReceipt(
                        status = AuthorizationStageReceiptStatus.NOT_REQUIRED,
                    ),
                    availability = AvailabilityStageReceipt(
                        status = AvailabilityStageReceiptStatus.NOT_REQUIRED,
                    ),
                ),
            ),
        ),
    )

    private fun initialSnapshot(desktop: ArtifactManifest, lyrics: ArtifactManifest): InstallationSessionSnapshot {
        val device = DeviceSummary(
            id = "car",
            displayName = "车机",
            connectionStatus = DeviceConnectionStatus.CONFIRMED,
            androidSdk = 28,
            capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
        )
        val components = listOf(desktop.toComponentDescriptor(), lyrics.toComponentDescriptor())
        val applications = listOf(
            ManagedApplicationStatus("desktop", desktop.packageName, true, versionCode = 1L),
            ManagedApplicationStatus("lyrics", lyrics.packageName, true, versionCode = 1L),
        )
        return InstallationSessionSnapshot(
            state = InstallationSessionState.MAINTENANCE,
            device = device,
            components = components,
            catalogVersion = "catalog-1",
            catalogRevision = 1L,
            catalogKeyId = "key-1",
            catalogSignatureAlgorithm = "Ed25519",
            evidence = SessionEvidence(
                installed = setOf("desktop", "lyrics"),
                configured = setOf("desktop", "lyrics"),
                available = setOf("desktop", "lyrics"),
            ),
            maintenance = MaintenanceSnapshot(
                managedApplicationsState = MaintenanceInventoryState.READY,
                managedApplications = applications,
                availableComponents = components,
                installedManifests = listOf(desktop, lyrics),
                availableCatalogVersion = "catalog-1",
                availableCatalogRevision = 1L,
                availableCatalogKeyId = "key-1",
                availableCatalogSignatureAlgorithm = "Ed25519",
            ),
        )
    }

    private fun manifest(componentId: String, code: Long, required: Boolean): ArtifactManifest = ArtifactManifest(
        schemaVersion = 1,
        componentId = componentId,
        displayName = componentId,
        required = required,
        version = ArtifactVersion("1.$code", code),
        compatibility = CompatibilityRange(minAndroidSdk = 26),
        archiveFileName = "$componentId.zip",
        archiveSizeBytes = 100L,
        archiveSha256 = "11".repeat(32),
        apkEntryName = "$componentId.apk",
        apkSizeBytes = 50L,
        apkSha256 = "22".repeat(32),
        packageName = when (componentId) {
            AuthorizationPlanFactory.DESKTOP_COMPONENT_ID -> "com.tcrrry.desktop"
            AuthorizationPlanFactory.LYRICS_COMPONENT_ID -> "com.tcrrry.desktoplyrics"
            else -> InstallerSelfIdentity.PACKAGE_NAME
        },
        apkVersion = ArtifactVersion("1.$code", code),
        certificateSha256 = "33".repeat(32),
        sources = listOf(
            ArtifactSource(ArtifactSourceKind.LANZOU_SHARE, "https://wwatl.lanzouw.com/ix$componentId"),
            ArtifactSource(ArtifactSourceKind.R2, "https://assets.r2.dev/$componentId.zip"),
            ArtifactSource(
                ArtifactSourceKind.GITHUB_RELEASES,
                "https://github.com/example/repo/releases/download/v1/$componentId.zip",
            ),
        ),
    )
}
