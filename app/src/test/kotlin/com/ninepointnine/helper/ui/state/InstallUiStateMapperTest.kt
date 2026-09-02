package com.ninepointnine.helper.ui.state

import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactSource
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.CompatibilityRange
import com.ninepointnine.helper.domain.device.InstalledArtifactEvidence
import com.ninepointnine.helper.domain.session.AuthorizationStageReceipt
import com.ninepointnine.helper.domain.session.AuthorizationStageReceiptStatus
import com.ninepointnine.helper.domain.session.AvailabilityStageReceipt
import com.ninepointnine.helper.domain.session.AvailabilityStageReceiptStatus
import com.ninepointnine.helper.domain.session.ComponentDescriptor
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.session.DeviceSummary
import com.ninepointnine.helper.domain.session.FailureCategory
import com.ninepointnine.helper.domain.session.InstallPhase
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.domain.session.InstallationSessionState
import com.ninepointnine.helper.domain.session.InstallationBatchPlan
import com.ninepointnine.helper.domain.session.InstallationBatchReceipt
import com.ninepointnine.helper.domain.session.InstallationComponentReceipt
import com.ninepointnine.helper.domain.session.InstallationFlow
import com.ninepointnine.helper.domain.session.InstallationStageReceipt
import com.ninepointnine.helper.domain.session.InstallationStageReceiptStatus
import com.ninepointnine.helper.domain.session.InstallationStrategy
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.domain.session.MaintenanceActionRecord
import com.ninepointnine.helper.domain.session.MaintenanceActionStatus
import com.ninepointnine.helper.domain.session.MaintenanceSnapshot
import com.ninepointnine.helper.domain.session.MaintenanceInventoryState
import com.ninepointnine.helper.domain.session.MaintenanceInstallationSelection
import com.ninepointnine.helper.domain.session.MaintenanceInstallationOption
import com.ninepointnine.helper.domain.session.ArtifactCatalogStage
import com.ninepointnine.helper.domain.session.ManagedApplicationStatus
import com.ninepointnine.helper.domain.session.ThirdPartyApplicationStatus
import com.ninepointnine.helper.domain.session.ResultKind
import com.ninepointnine.helper.domain.session.SessionFailure
import com.ninepointnine.helper.domain.session.SessionProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallUiStateMapperTest {
    @Test
    fun `idle and discovery map to one connection screen`() {
        val idle = InstallUiStateMapper.map(InstallationSessionSnapshot(InstallationSessionState.IDLE))
        val searching = InstallUiStateMapper.map(InstallationSessionSnapshot(InstallationSessionState.DISCOVERING))
        val found = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.DISCOVERING,
                discoveredDevices = listOf(device),
            ),
        )
        val connecting = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.CONNECTING,
                device = device.copy(connectionStatus = DeviceConnectionStatus.CONNECTING),
                discoveredDevices = listOf(device.copy(connectionStatus = DeviceConnectionStatus.CONNECTING)),
            ),
        )

        assertEquals(ConnectionVariant.NOT_FOUND, (idle as InstallUiState.Connection).variant)
        assertEquals(ConnectionVariant.SEARCHING, (searching as InstallUiState.Connection).variant)
        assertEquals(ConnectionVariant.FOUND, (found as InstallUiState.Connection).variant)
        assertEquals(ConnectionVariant.CONNECTING, (connecting as InstallUiState.Connection).variant)
        assertEquals("icar-03", found.devices.single().id)
    }

    @Test
    fun `selection keeps desktop locked and gates missing selected size`() {
        val ready = InstallUiStateMapper.map(
            selectionSnapshot(
                listOf(
                    component("lyrics", required = false, size = "18 MB"),
                    component("desktop", required = true, size = "12 MB"),
                    component("files", required = false, size = null),
                ),
                selectedOptionalIds = setOf("lyrics"),
            ),
        ) as InstallUiState.Selection

        assertTrue(ready.components[0].selected)
        assertTrue(ready.components[1].selected)
        assertFalse(ready.components[2].selected)
        assertEquals(2, ready.summaryCount)
        assertEquals("18 MB + 12 MB", ready.summarySizeLabel)
        assertTrue(ready.canStart)

        val blocked = InstallUiStateMapper.map(
            selectionSnapshot(
                components = ready.components.map {
                    ComponentDescriptor(
                        it.id,
                        it.displayName,
                        it.required,
                        it.versionLabel,
                        it.sizeLabel,
                        it.compatibilityLabel,
                        status = if (it.id == "files") {
                            com.ninepointnine.helper.domain.session.ComponentStatus.DIRECTORY_MISSING
                        } else {
                            com.ninepointnine.helper.domain.session.ComponentStatus.READING
                        },
                    )
                },
                selectedOptionalIds = setOf("files"),
            ),
        ) as InstallUiState.Selection

        assertFalse(blocked.canStart)
    }

    @Test
    fun `selection treats a non-desktop required recommendation as optional`() {
        val state = InstallUiStateMapper.map(
            selectionSnapshot(
                listOf(
                    component("desktop", required = true, size = "12 MB"),
                    component("lyrics", required = true, size = "18 MB"),
                ),
            ),
        ) as InstallUiState.Selection

        assertTrue(state.components[0].selected)
        assertTrue(state.components[0].isMandatory())
        assertFalse(state.components[1].selected)
        assertFalse(state.components[1].isMandatory())
    }

    @Test
    fun `installation state maps phase completion and bounded progress`() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.AUTHORIZING,
                device = device,
                currentComponentName = "03歌词",
                progress = SessionProgress(completedCount = 1, totalCount = 3, fraction = 1.4f),
            ),
        ) as InstallUiState.Installing

        assertEquals(InstallPhase.CONFIGURE, state.currentPhase)
        assertEquals(setOf(InstallPhase.FETCH, InstallPhase.CHECK, InstallPhase.SEND), state.completedStages)
        assertEquals(1f, state.progress.fraction)
    }

    @Test
    fun `failure and maintenance remain structured states`() {
        val failed = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.FAILED,
                failure = SessionFailure(FailureCategory.CONFIGURATION),
            ),
        ) as InstallUiState.Result
        val maintenance = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.MAINTENANCE,
                device = device,
            ),
        ) as InstallUiState.Maintenance

        assertEquals(ResultKind.CONFIGURATION_FAILED, failed.kind)
        assertTrue(maintenance.connected)
        assertEquals("iCAR 03", maintenance.deviceName)
    }

    @Test
    fun `partial installation maps to the result page and keeps maintenance available when desktop is ready`() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.COMPLETED_WITH_ERRORS,
                device = device,
                components = listOf(
                    component("desktop", required = true, size = "12 MB"),
                    component("lyrics", required = false, size = "18 MB"),
                ),
                failedComponentIds = setOf("lyrics"),
                evidence = com.ninepointnine.helper.domain.session.SessionEvidence(
                    available = setOf("desktop"),
                ),
                componentResults = listOf(
                    com.ninepointnine.helper.domain.session.ComponentResult("Desktop", true, true, true, "desktop"),
                    com.ninepointnine.helper.domain.session.ComponentResult("Lyrics", false, false, false, "lyrics"),
                ),
            ),
        ) as InstallUiState.Result

        assertEquals(ResultKind.PARTIAL_FAILURE, state.kind)
        assertTrue(state.canEnterMaintenance)
        assertEquals(2, state.componentResults.size)
    }

    @Test
    fun `installed component keeps status facts alongside a post install failure reason`() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.COMPLETED_WITH_ERRORS,
                device = device,
                components = listOf(component("cast", required = false, size = null)),
                failedComponentIds = setOf("cast"),
                componentResults = listOf(
                    com.ninepointnine.helper.domain.session.ComponentResult(
                        componentName = "03投屏",
                        installed = true,
                        configured = true,
                        available = false,
                        componentId = "cast",
                        failureReason = "desktop_service_not_bound",
                    ),
                ),
            ),
        ) as InstallUiState.Result

        val row = state.componentResults.single()
        assertTrue(row.installed)
        assertTrue(row.configured)
        assertFalse(row.available)
        assertEquals("03桌面未通过可用性检查", row.errorReason)
    }

    @Test
    fun `maintenance result excludes a hidden prerequisite reason outside the target rows`() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.COMPLETED_WITH_ERRORS,
                device = device,
                components = listOf(
                    component("desktop", required = true, size = null).copy(errorReason = "desktop_service_not_bound"),
                    component("cast", required = false, size = null),
                ),
                failedComponentIds = setOf("desktop"),
                installationFlow = com.ninepointnine.helper.domain.session.InstallationFlow.MAINTENANCE_INSTALL,
                installationBatch = com.ninepointnine.helper.domain.session.InstallationBatchPlan(
                    batchId = 1L,
                    flow = com.ninepointnine.helper.domain.session.InstallationFlow.MAINTENANCE_INSTALL,
                    strategy = com.ninepointnine.helper.domain.session.InstallationStrategy.INSTALL_MISSING_ONLY,
                    selectedComponentIds = setOf("desktop", "cast"),
                    reusableComponentIds = setOf("desktop"),
                    preparationComponentIds = setOf("cast"),
                    resultComponentIds = setOf("cast"),
                ),
                componentResults = listOf(
                    com.ninepointnine.helper.domain.session.ComponentResult(
                        componentName = "03投屏",
                        installed = true,
                        configured = true,
                        available = true,
                        componentId = "cast",
                    ),
                ),
            ),
        ) as InstallUiState.Result

        assertEquals(1, state.componentResults.size)
        assertNull(state.failureReason)
    }

    @Test
    fun `successful install result has no phone local persistence state`() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.SUCCEEDED,
                installationBatch = singleResultBatch("cast"),
                components = listOf(component("cast", required = false, size = null)),
                evidence = com.ninepointnine.helper.domain.session.SessionEvidence(
                    installed = setOf("cast"),
                    configured = setOf("cast"),
                    available = setOf("cast"),
                ),
                componentResults = listOf(
                    com.ninepointnine.helper.domain.session.ComponentResult(
                        componentName = "03投屏",
                        installed = true,
                        configured = true,
                        available = true,
                        componentId = "cast",
                    ),
                ),
            ),
        ) as InstallUiState.Result

        assertEquals(ResultKind.SUCCESS, state.kind)
        assertTrue(state.componentResults.single().available)
    }

    @Test
    fun declaredSuccessWithoutInstalledEvidenceIsDowngraded() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.SUCCEEDED,
                installationBatch = singleResultBatch("cast"),
                components = listOf(component("cast", required = false, size = null)),
                componentResults = listOf(
                    com.ninepointnine.helper.domain.session.ComponentResult(
                        componentName = "03投屏",
                        installed = false,
                        configured = false,
                        available = false,
                        componentId = "cast",
                    ),
                ),
            ),
        ) as InstallUiState.Result

        assertFalse(state.kind == ResultKind.SUCCESS)
        assertEquals(ResultKind.INSTALLATION_FAILED, state.kind)
        assertEquals("安装结果未能确认，请重新检查车机状态", state.failureReason)
    }

    @Test
    fun declaredSuccessWithIncompleteResultRowsIsNeverShownAsSuccess() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.SUCCEEDED,
                installationBatch = singleResultBatch("cast"),
                components = listOf(component("cast", required = false, size = null)),
                evidence = com.ninepointnine.helper.domain.session.SessionEvidence(
                    installed = setOf("cast"),
                    configured = setOf("cast"),
                    available = setOf("cast"),
                ),
                componentResults = emptyList(),
            ),
        ) as InstallUiState.Result

        assertFalse(state.kind == ResultKind.SUCCESS)
        assertEquals(ResultKind.INSTALLATION_FAILED, state.kind)
        assertEquals("安装结果未能确认，请重新检查车机状态", state.failureReason)
    }

    @Test
    fun declaredSuccessCarryingComponentFailureIsDowngraded() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.SUCCEEDED,
                installationBatch = singleResultBatch("cast"),
                components = listOf(component("cast", required = false, size = null)),
                failedComponentIds = setOf("cast"),
                evidence = com.ninepointnine.helper.domain.session.SessionEvidence(
                    installed = setOf("cast"),
                    configured = setOf("cast"),
                    available = setOf("cast"),
                ),
                componentResults = listOf(
                    com.ninepointnine.helper.domain.session.ComponentResult(
                        componentName = "03投屏",
                        installed = true,
                        configured = true,
                        available = true,
                        componentId = "cast",
                        failureReason = "authorization_component_not_present",
                    ),
                ),
            ),
        ) as InstallUiState.Result

        assertFalse(state.kind == ResultKind.SUCCESS)
        assertEquals(ResultKind.PARTIAL_FAILURE, state.kind)
        assertEquals(ResultFailureStage.POST_INSTALL, state.failureStage)
        assertEquals(
            com.ninepointnine.helper.domain.session.ComponentResultStatus.AUTHORIZATION_INCOMPLETE,
            state.componentResults.single().status,
        )
        assertEquals("车机授权未完成，请重新授权", state.componentResults.single().errorReason)
    }

    @Test
    fun stale_constructor_status_cannot_override_evidence_projection() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.SUCCEEDED,
                installationBatch = singleResultBatch("cast"),
                components = listOf(component("cast", required = false, size = null)),
                evidence = com.ninepointnine.helper.domain.session.SessionEvidence(
                    installed = setOf("cast"),
                    configured = setOf("cast"),
                    available = setOf("cast"),
                ),
                componentResults = listOf(
                    com.ninepointnine.helper.domain.session.ComponentResult(
                        componentName = "03投屏",
                        installed = true,
                        configured = true,
                        available = true,
                        componentId = "cast",
                        // Older snapshots could carry a stale cached status.
                        status = com.ninepointnine.helper.domain.session.ComponentResultStatus.NOT_INSTALLED,
                    ),
                ),
            ),
        ) as InstallUiState.Result

        assertEquals(ResultKind.SUCCESS, state.kind)
        assertEquals(
            com.ninepointnine.helper.domain.session.ComponentResultStatus.READY,
            state.componentResults.single().status,
        )
    }

    @Test
    fun `committed batch receipt is the only UI result source`() {
        val manifest = resultManifest("cast", required = false)
        val receipt = InstallationBatchReceipt(
            batchId = 12L,
            components = listOf(
                InstallationComponentReceipt(
                    componentId = "cast",
                    installation = InstallationStageReceipt(
                        status = InstallationStageReceiptStatus.VERIFIED,
                        evidence = InstalledArtifactEvidence(
                            componentId = "cast",
                            packageName = manifest.packageName,
                            version = manifest.apkVersion,
                            apkSizeBytes = manifest.apkSizeBytes,
                            apkSha256 = manifest.apkSha256,
                            certificateSha256 = manifest.certificateSha256,
                        ),
                    ),
                    authorization = AuthorizationStageReceipt(
                        status = AuthorizationStageReceiptStatus.NOT_REQUIRED,
                    ),
                    availability = AvailabilityStageReceipt(
                        status = AvailabilityStageReceiptStatus.NOT_REQUIRED,
                    ),
                ),
            ),
        )
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.COMPLETED_WITH_ERRORS,
                installationBatch = singleResultBatch("cast"),
                installationBatchReceipt = receipt,
                components = listOf(component("cast", required = false, size = null)),
                artifactManifests = listOf(manifest),
                failedComponentIds = setOf("cast"),
                evidence = com.ninepointnine.helper.domain.session.SessionEvidence(),
                failure = SessionFailure(
                    category = FailureCategory.INSTALLATION,
                    reasonCode = "installation_failed",
                ),
                componentResults = listOf(
                    com.ninepointnine.helper.domain.session.ComponentResult(
                        componentName = "03投屏",
                        installed = false,
                        configured = false,
                        available = false,
                        componentId = "cast",
                        failureReason = "authorization_component_not_present",
                    ),
                ),
            ),
        ) as InstallUiState.Result

        assertEquals(ResultKind.SUCCESS, state.kind)
        assertEquals(
            com.ninepointnine.helper.domain.session.ComponentResultStatus.READY,
            state.componentResults.single().status,
        )
        assertTrue(state.componentResults.single().installed)
        assertTrue(state.componentResults.single().configured)
        assertTrue(state.componentResults.single().available)
        assertNull(state.componentResults.single().errorReason)
        assertNull(state.failureReason)
    }

    @Test
    fun `confirmation pending is mapped to one non failure result row`() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.COMPLETED_WITH_ERRORS,
                installationBatch = singleResultBatch("cast"),
                components = listOf(component("cast", required = false, size = null)),
                evidence = com.ninepointnine.helper.domain.session.SessionEvidence(
                    writeConfirmed = setOf("cast"),
                    confirmationPending = setOf("cast"),
                ),
                componentResults = listOf(
                    com.ninepointnine.helper.domain.session.ComponentResult(
                        componentName = "03投屏",
                        installed = false,
                        configured = false,
                        available = false,
                        componentId = "cast",
                        writeConfirmed = true,
                        confirmationPending = true,
                    ),
                ),
            ),
        ) as InstallUiState.Result

        assertEquals(ResultKind.CONFIRMATION_PENDING, state.kind)
        assertEquals(
            com.ninepointnine.helper.domain.session.ComponentResultStatus.INSTALLATION_PENDING_CONFIRMATION,
            state.componentResults.single().status,
        )
        assertNull(state.componentResults.single().errorReason)
        assertNull(state.failureReason)
    }

    @Test
    fun reusableOnlyMaintenanceBatchCanSucceedWithoutVisibleResultRows() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.SUCCEEDED,
                installationFlow = InstallationFlow.MAINTENANCE_INSTALL,
                installationBatch = InstallationBatchPlan(
                    batchId = 20L,
                    flow = InstallationFlow.MAINTENANCE_INSTALL,
                    strategy = InstallationStrategy.INSTALL_MISSING_ONLY,
                    selectedComponentIds = setOf("desktop"),
                    reusableComponentIds = setOf("desktop"),
                    preparationComponentIds = emptySet(),
                    resultComponentIds = emptySet(),
                ),
                components = listOf(component("desktop", required = true, size = null)),
                evidence = com.ninepointnine.helper.domain.session.SessionEvidence(
                    installed = setOf("desktop"),
                    configured = setOf("desktop"),
                    available = setOf("desktop"),
                ),
            ),
        ) as InstallUiState.Result

        assertEquals(ResultKind.SUCCESS, state.kind)
        assertTrue(state.componentResults.isEmpty())
        assertTrue(state.canEnterMaintenance)
    }

    @Test
    fun `mixed maintenance batch uses reusable installed identity and fresh artifact proof`() {
        val cast = resultManifest("cast", required = false)
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.SUCCEEDED,
                installationFlow = InstallationFlow.MAINTENANCE_INSTALL,
                installationBatch = InstallationBatchPlan(
                    batchId = 21L,
                    flow = InstallationFlow.MAINTENANCE_INSTALL,
                    strategy = InstallationStrategy.INSTALL_MISSING_ONLY,
                    selectedComponentIds = setOf("desktop", "cast"),
                    reusableComponentIds = setOf("desktop"),
                    preparationComponentIds = setOf("cast"),
                    resultComponentIds = setOf("cast"),
                ),
                components = listOf(
                    component("desktop", required = true, size = null),
                    component("cast", required = false, size = null),
                ),
                artifactManifests = listOf(cast),
                evidence = com.ninepointnine.helper.domain.session.SessionEvidence(
                    artifactsVerified = setOf("cast"),
                    installed = setOf("desktop", "cast"),
                    configured = setOf("desktop", "cast"),
                    available = setOf("desktop", "cast"),
                ),
                componentResults = listOf(
                    com.ninepointnine.helper.domain.session.ComponentResult(
                        componentName = "03投屏",
                        installed = true,
                        configured = true,
                        available = true,
                        componentId = "cast",
                    ),
                ),
            ),
        ) as InstallUiState.Result

        assertEquals(ResultKind.SUCCESS, state.kind)
        assertEquals(listOf("03投屏"), state.componentResults.map { it.componentName })
    }

    @Test
    fun `fresh maintenance component without artifact proof cannot be shown as success`() {
        val cast = resultManifest("cast", required = false)
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.SUCCEEDED,
                installationFlow = InstallationFlow.MAINTENANCE_INSTALL,
                installationBatch = InstallationBatchPlan(
                    batchId = 22L,
                    flow = InstallationFlow.MAINTENANCE_INSTALL,
                    strategy = InstallationStrategy.INSTALL_MISSING_ONLY,
                    selectedComponentIds = setOf("cast"),
                    reusableComponentIds = emptySet(),
                    preparationComponentIds = setOf("cast"),
                    resultComponentIds = setOf("cast"),
                ),
                components = listOf(component("cast", required = false, size = null)),
                artifactManifests = listOf(cast),
                evidence = com.ninepointnine.helper.domain.session.SessionEvidence(
                    installed = setOf("cast"),
                    configured = setOf("cast"),
                    available = setOf("cast"),
                ),
                componentResults = listOf(
                    com.ninepointnine.helper.domain.session.ComponentResult(
                        componentName = "03投屏",
                        installed = true,
                        configured = true,
                        available = true,
                        componentId = "cast",
                    ),
                ),
            ),
        ) as InstallUiState.Result

        assertEquals(ResultKind.INSTALLATION_FAILED, state.kind)
        assertEquals("安装结果未能确认，请重新检查车机状态", state.failureReason)
    }

    @Test
    fun `reusable maintenance baseline does not reclassify a current install failure`() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.FAILED,
                installationFlow = InstallationFlow.MAINTENANCE_INSTALL,
                installationBatch = InstallationBatchPlan(
                    batchId = 9L,
                    flow = InstallationFlow.MAINTENANCE_INSTALL,
                    strategy = InstallationStrategy.INSTALL_MISSING_ONLY,
                    selectedComponentIds = setOf("desktop", "cast"),
                    reusableComponentIds = setOf("desktop"),
                    preparationComponentIds = setOf("cast"),
                    resultComponentIds = setOf("cast"),
                ),
                components = listOf(
                    component("desktop", required = true, size = null),
                    component("cast", required = false, size = null),
                ),
                evidence = com.ninepointnine.helper.domain.session.SessionEvidence(
                    installed = setOf("desktop"),
                    configured = setOf("desktop"),
                    available = setOf("desktop"),
                ),
                failure = SessionFailure(
                    category = FailureCategory.CONFIGURATION,
                    reasonCode = "authorization_failed",
                ),
            ),
        ) as InstallUiState.Result

        assertEquals(ResultKind.CONFIGURATION_FAILED, state.kind)
        assertEquals(ResultFailureStage.INSTALLATION, state.failureStage)
        assertTrue(state.componentResults.isEmpty())
    }

    @Test
    fun `installed target with incomplete authorization is a post install result`() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.FAILED,
                installationBatch = singleResultBatch("cast"),
                installationFlow = InstallationFlow.INITIAL_INSTALL,
                components = listOf(component("cast", required = false, size = null)),
                evidence = com.ninepointnine.helper.domain.session.SessionEvidence(
                    installed = setOf("cast"),
                ),
                componentResults = listOf(
                    com.ninepointnine.helper.domain.session.ComponentResult(
                        componentName = "03投屏",
                        installed = true,
                        configured = false,
                        available = false,
                        componentId = "cast",
                    ),
                ),
                failure = SessionFailure(
                    category = FailureCategory.CONFIGURATION,
                    reasonCode = "authorization_failed",
                ),
            ),
        ) as InstallUiState.Result

        assertEquals(ResultKind.PARTIAL_FAILURE, state.kind)
        assertEquals(ResultFailureStage.POST_INSTALL, state.failureStage)
        assertEquals(1, state.componentResults.size)
    }

    @Test
    fun `installed and authorized target with incomplete availability is post install`() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.COMPLETED_WITH_ERRORS,
                installationBatch = singleResultBatch("cast"),
                components = listOf(component("cast", required = false, size = null)),
                evidence = com.ninepointnine.helper.domain.session.SessionEvidence(
                    installed = setOf("cast"),
                    configured = setOf("cast"),
                ),
                componentResults = listOf(
                    com.ninepointnine.helper.domain.session.ComponentResult(
                        componentName = "03投屏",
                        installed = true,
                        configured = true,
                        available = false,
                        componentId = "cast",
                    ),
                ),
            ),
        ) as InstallUiState.Result

        assertEquals(ResultKind.PARTIAL_FAILURE, state.kind)
        assertEquals(ResultFailureStage.POST_INSTALL, state.failureStage)
    }

    @Test
    fun `result rows do not inherit catalog descriptor diagnostics`() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.SUCCEEDED,
                installationBatch = singleResultBatch("cast"),
                components = listOf(
                    component("cast", required = false, size = null).copy(
                        errorReason = "lanzou_folder_missing_cast",
                    ),
                ),
                evidence = com.ninepointnine.helper.domain.session.SessionEvidence(
                    artifactsVerified = setOf("cast"),
                    installed = setOf("cast"),
                    configured = setOf("cast"),
                    available = setOf("cast"),
                ),
                componentResults = listOf(
                    com.ninepointnine.helper.domain.session.ComponentResult(
                        componentName = "03投屏",
                        installed = true,
                        configured = true,
                        available = true,
                        componentId = "cast",
                    ),
                ),
            ),
        ) as InstallUiState.Result

        assertEquals(ResultKind.SUCCESS, state.kind)
        assertEquals(null, state.componentResults.single().errorReason)
    }

    @Test
    fun `not installed target keeps installation failure stage`() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.COMPLETED_WITH_ERRORS,
                installationBatch = singleResultBatch("cast"),
                components = listOf(component("cast", required = false, size = null)),
                componentResults = listOf(
                    com.ninepointnine.helper.domain.session.ComponentResult(
                        componentName = "03投屏",
                        installed = false,
                        configured = false,
                        available = false,
                        componentId = "cast",
                    ),
                ),
            ),
        ) as InstallUiState.Result

        assertEquals(ResultFailureStage.INSTALLATION, state.failureStage)
    }

    @Test
    fun `connected catalog failure remains an unavailable selection state`() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.CONNECTED,
                device = device,
                failure = SessionFailure(
                    category = FailureCategory.VERIFICATION,
                    retryable = false,
                    reasonCode = "catalog_android_profile_missing",
                ),
            ),
        ) as InstallUiState.Selection

        assertTrue(state.components.isEmpty())
        assertFalse(state.canStart)
        assertFalse(state.preparing)
    }

    @Test
    fun `connected catalog load is shown as preparation instead of failure`() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.CONNECTED,
                device = device,
            ),
        ) as InstallUiState.Selection

        assertTrue(state.components.isEmpty())
        assertTrue(state.preparing)
        assertFalse(state.canStart)
    }

    @Test
    fun `every internal state maps to one of the five stable screens`() {
        val installingStates = listOf(
            InstallationSessionState.SELECTION_CONFIRMED,
            InstallationSessionState.PREPARING_ARTIFACTS,
            InstallationSessionState.ARTIFACTS_READY,
            InstallationSessionState.INSTALLING,
            InstallationSessionState.AUTHORIZING,
            InstallationSessionState.VERIFYING_DEVICE,
        )
        installingStates.forEach { state ->
            assertTrue(
                "Expected installing projection for $state",
                InstallUiStateMapper.map(
                    InstallationSessionSnapshot(state = state, device = device),
                ) is InstallUiState.Installing,
            )
        }
        assertTrue(InstallUiStateMapper.map(InstallationSessionSnapshot(InstallationSessionState.SUCCEEDED)) is InstallUiState.Result)
        assertTrue(InstallUiStateMapper.map(InstallationSessionSnapshot(InstallationSessionState.PAUSED)) is InstallUiState.Result)
        assertTrue(InstallUiStateMapper.map(InstallationSessionSnapshot(InstallationSessionState.FAILED)) is InstallUiState.Result)
        assertTrue(InstallUiStateMapper.map(InstallationSessionSnapshot(InstallationSessionState.MAINTENANCE)) is InstallUiState.Maintenance)
    }

    @Test
    fun `selection start requires confirmed device while lightweight metadata may be pending`() {
        val unconfirmed = InstallUiStateMapper.map(
            selectionSnapshot(
                components = listOf(
                    component("lyrics", required = false, size = "18 MB"),
                    component("desktop", required = true, size = "12 MB"),
                ).map { it.copy(compatibilityLabel = null) },
            ).copy(device = device.copy(connectionStatus = DeviceConnectionStatus.CONNECTING)),
        ) as InstallUiState.Selection
        assertFalse(unconfirmed.canStart)

        val incomplete = InstallUiStateMapper.map(
            selectionSnapshot(
                components = listOf(
                    component("lyrics", required = false, size = "18 MB"),
                    component("desktop", required = true, size = "12 MB"),
                ).map { it.copy(versionLabel = null) },
            ),
        ) as InstallUiState.Selection
        assertTrue(incomplete.canStart)
    }

    @Test
    fun `maintenance feedback and managed application rows come from the session snapshot`() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.MAINTENANCE,
                device = device.copy(connectionStatus = DeviceConnectionStatus.DISCONNECTED),
                components = listOf(
                    component("desktop", required = true, size = "12 MB"),
                    component("lyrics", required = false, size = "18 MB"),
                ),
                maintenance = MaintenanceSnapshot(
                    routeAction = MaintenanceActionId.MANAGE_APPS,
                    lastAction = MaintenanceActionRecord(
                        actionId = MaintenanceActionId.MANAGE_APPS,
                        status = MaintenanceActionStatus.SUCCEEDED,
                        resultCode = "applications_checked",
                    ),
                    managedApplications = listOf(
                        ManagedApplicationStatus("desktop", "com.tcrrry.desktop", true),
                        ManagedApplicationStatus("lyrics", "com.tcrrry.desktoplyrics", false),
                    ),
                ),
            ),
        ) as InstallUiState.Maintenance

        assertEquals(MaintenanceActionId.MANAGE_APPS, state.feedback?.actionId)
        assertEquals(MaintenanceActionStatus.SUCCEEDED, state.feedback?.status)
        assertEquals(listOf("desktop", "lyrics"), state.applications.map { it.componentId })
        assertEquals(listOf("desktop", "lyrics"), state.applications.map { it.displayName })
        assertEquals(listOf(true, false), state.applications.map { it.installed })
        assertFalse(state.connected)
    }

    @Test
    fun `maintenance applications are flat, newest first, and third party rows are actionable`() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.MAINTENANCE,
                device = device,
                components = listOf(component("desktop", required = true, size = "12 MB")),
                maintenance = MaintenanceSnapshot(
                    managedApplications = listOf(
                        ManagedApplicationStatus(
                            componentId = "desktop",
                            packageName = "com.tcrrry.desktop",
                            installed = true,
                            installTimeEpochMillis = 100L,
                        ),
                    ),
                    thirdPartyApplications = listOf(
                        ThirdPartyApplicationStatus(
                            packageName = "com.example.newest",
                            versionLabel = "3.0",
                            installTimeEpochMillis = 300L,
                            filePath = "/data/app/com.example.newest-x/base.apk",
                        ),
                        // The controlled row wins when the same package appears
                        // in both inventories, even if the observed copy is newer.
                        ThirdPartyApplicationStatus(
                            packageName = "com.tcrrry.desktop",
                            versionLabel = "untrusted",
                            installTimeEpochMillis = 999L,
                            filePath = "/data/app/com.tcrrry.desktop-y/base.apk",
                        ),
                        ThirdPartyApplicationStatus(
                            packageName = "com.example.unknown",
                            filePath = "/data/app/com.example.unknown-z/base.apk",
                        ),
                    ),
                ),
            ),
        ) as InstallUiState.Maintenance

        assertEquals(
            listOf("com.example.newest", "com.tcrrry.desktop", "com.example.unknown"),
            state.applications.map { it.packageName },
        )
        assertEquals(listOf(false, true, false), state.applications.map { it.isControlled })
        assertEquals("3.0", state.applications.first().versionLabel)
        assertTrue(state.applications.last().componentId.startsWith("third-party:"))
    }

    @Test
    fun `maintenance mapper distinguishes loaded empty inventory from not started`() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.MAINTENANCE,
                device = device,
                maintenance = MaintenanceSnapshot(
                    managedApplicationsState = MaintenanceInventoryState.READY,
                    managedApplications = emptyList(),
                ),
            ),
        ) as InstallUiState.Maintenance

        assertEquals(MaintenanceInventoryState.READY, state.applicationsState)
        assertTrue(state.applications.isEmpty())
    }

    @Test
    fun `maintenance mapper does not infer inventory readiness from a historical action`() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.MAINTENANCE,
                device = device,
                maintenance = MaintenanceSnapshot(
                    lastAction = MaintenanceActionRecord(
                        actionId = MaintenanceActionId.MANAGE_APPS,
                        status = MaintenanceActionStatus.SUCCEEDED,
                    ),
                    managedApplicationsState = MaintenanceInventoryState.NOT_STARTED,
                    managedApplications = emptyList(),
                ),
            ),
        ) as InstallUiState.Maintenance

        assertEquals(MaintenanceInventoryState.NOT_STARTED, state.applicationsState)
        assertNull(state.feedback)
        assertNull(state.routeAction)
    }

    @Test
    fun `maintenance selection projects a failed start back into the same page`() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.MAINTENANCE,
                device = device,
                maintenance = MaintenanceSnapshot(
                    lastAction = MaintenanceActionRecord(
                        actionId = MaintenanceActionId.INSTALL_FILE_MANAGER,
                        status = MaintenanceActionStatus.FAILED,
                        reasonCode = "artifact_catalog_not_prepared",
                        retryable = true,
                    ),
                    installationSelection = MaintenanceInstallationSelection(
                        actionId = MaintenanceActionId.INSTALL_FILE_MANAGER,
                        options = listOf(
                            MaintenanceInstallationOption(
                                componentId = "desktop",
                                displayName = "desktop",
                                installed = true,
                                required = true,
                            ),
                            MaintenanceInstallationOption(
                                componentId = "lyrics",
                                displayName = "lyrics",
                                installed = false,
                            ),
                        ),
                        selectedComponentIds = setOf("lyrics"),
                    ),
                ),
                artifactCatalogStage = ArtifactCatalogStage.CONTROL_PLANE_READY,
            ),
        ) as InstallUiState.Maintenance

        assertEquals(MaintenanceActionStatus.FAILED, state.installationSelection?.feedback?.status)
        assertEquals("artifact_catalog_not_prepared", state.installationSelection?.feedback?.reasonCode)
    }

    @Test
    fun `maintenance application failure keeps a concrete archive message after retry`() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.MAINTENANCE,
                device = device,
                maintenance = MaintenanceSnapshot(
                    lastAction = MaintenanceActionRecord(
                        actionId = MaintenanceActionId.INSTALL_APPLICATIONS,
                        status = MaintenanceActionStatus.FAILED,
                        reasonCode = "distribution_archive_invalid",
                        retryable = true,
                    ),
                    routeAction = MaintenanceActionId.INSTALL_APPLICATIONS,
                    installationSelection = MaintenanceInstallationSelection(
                        actionId = MaintenanceActionId.INSTALL_APPLICATIONS,
                        options = listOf(
                            MaintenanceInstallationOption(
                                componentId = "cast",
                                displayName = "03投屏",
                                installed = false,
                            ),
                        ),
                        selectedComponentIds = setOf("cast"),
                    ),
                ),
            ),
        ) as InstallUiState.Maintenance

        assertEquals("distribution_archive_invalid", state.feedback?.reasonCode)
        assertEquals("压缩包或安装包校验失败", state.feedback?.message)
    }

    @Test
    fun `connected catalog failure is projected as visible selection feedback`() {
        val state = InstallUiStateMapper.map(
            InstallationSessionSnapshot(
                state = InstallationSessionState.CONNECTED,
                device = device,
                components = emptyList(),
                failure = SessionFailure(
                    category = FailureCategory.VERIFICATION,
                    retryable = false,
                    reasonCode = "catalog_android_profile_missing",
                ),
                artifactCatalogStage = ArtifactCatalogStage.NOT_LOADED,
            ),
        ) as InstallUiState.Selection

        assertFalse(state.canStart)
        assertEquals("暂时无法读取安装配置，请重试", state.failureReason)
    }

    private fun selectionSnapshot(
        components: List<ComponentDescriptor>,
        selectedOptionalIds: Set<String> = emptySet(),
    ) = InstallationSessionSnapshot(
        state = InstallationSessionState.CONNECTED,
        device = device,
        components = components,
        selectedOptionalComponentIds = selectedOptionalIds,
    )

    private fun component(id: String, required: Boolean, size: String?) = ComponentDescriptor(
        id = id,
        displayName = id,
        required = required,
        versionLabel = "1.0",
        sizeLabel = size,
        compatibilityLabel = "适用于当前车机",
    )

    private fun singleResultBatch(componentId: String) = InstallationBatchPlan(
        batchId = 12L,
        flow = InstallationFlow.INITIAL_INSTALL,
        strategy = InstallationStrategy.INSTALL_MISSING_ONLY,
        selectedComponentIds = setOf(componentId),
        reusableComponentIds = emptySet(),
        preparationComponentIds = setOf(componentId),
        resultComponentIds = setOf(componentId),
    )

    private fun resultManifest(componentId: String, required: Boolean): ArtifactManifest = ArtifactManifest(
        schemaVersion = 1,
        componentId = componentId,
        displayName = componentId,
        required = required,
        version = ArtifactVersion("1.0.0", 1L),
        compatibility = CompatibilityRange(minAndroidSdk = 26, maxAndroidSdk = 35),
        archiveFileName = "$componentId.zip",
        archiveSizeBytes = 100L,
        archiveSha256 = "11".repeat(32),
        apkEntryName = "$componentId.apk",
        apkSizeBytes = 50L,
        apkSha256 = "22".repeat(32),
        packageName = "com.example.$componentId",
        apkVersion = ArtifactVersion("1.0.0", 1L),
        certificateSha256 = "33".repeat(32),
        sources = listOf(
            ArtifactSource(ArtifactSourceKind.LANZOU_SHARE, "https://example.com/$componentId.zip"),
        ),
    )

    private companion object {
        val device = DeviceSummary(
            id = "icar-03",
            displayName = "iCAR 03",
            connectionStatus = DeviceConnectionStatus.CONFIRMED,
        )
    }
}
