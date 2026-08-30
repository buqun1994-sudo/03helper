package com.ninepointnine.helper.domain.session

import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactFailure
import com.ninepointnine.helper.domain.artifact.ArtifactFailurePhase
import com.ninepointnine.helper.domain.artifact.ArchiveDownloadEvidence
import com.ninepointnine.helper.domain.artifact.ArchiveVerificationEvidence
import com.ninepointnine.helper.domain.artifact.ApkExtractionEvidence
import com.ninepointnine.helper.domain.artifact.ArtifactSource
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.CompatibilityRange
import com.ninepointnine.helper.domain.artifact.ArtifactVerification
import com.ninepointnine.helper.domain.artifact.SourceSelectionEvidence
import com.ninepointnine.helper.domain.artifact.toComponentDescriptor
import com.ninepointnine.helper.domain.device.AuthorizationAction
import com.ninepointnine.helper.domain.device.AuthorizationActionEvidence
import com.ninepointnine.helper.domain.device.AuthorizationPlanBuildResult
import com.ninepointnine.helper.domain.device.AuthorizationPlanFactory
import com.ninepointnine.helper.domain.device.AuthorizationValueState
import com.ninepointnine.helper.domain.device.DeviceAvailabilityEvidence
import com.ninepointnine.helper.domain.device.DeviceCapability
import com.ninepointnine.helper.domain.device.InstalledArtifactEvidence
import com.ninepointnine.helper.domain.device.MaintenanceAuthorizationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallationSessionTest {
    @Test
    fun reasonCodesMapToStableComponentAndPhaseSemanticsWithoutSubstringGuesses() {
        assertEquals(
            ComponentStatus.DIRECTORY_MISSING,
            componentStatusForReasonCode("lanzou_folder_missing_lyrics"),
        )
        assertEquals(
            ComponentStatus.TEMPORARILY_UNAVAILABLE,
            componentStatusForReasonCode("installation_package_path_missing"),
        )
        assertEquals(
            ComponentStatus.TEMPORARILY_UNAVAILABLE,
            componentStatusForReasonCode("installation_installed_apk_read_failed"),
        )
        assertEquals(
            ComponentStatus.ZIP_VALIDATION_FAILED,
            componentStatusForReasonCode("distribution_archive_invalid"),
        )
        assertEquals(
            ComponentStatus.ZIP_VALIDATION_FAILED,
            componentStatusForReasonCode("download_not_zip"),
        )
        assertEquals(
            ComponentStatus.CLIENT_CAPABILITY_INSUFFICIENT,
            componentStatusForReasonCode("component_incompatible"),
        )
        assertEquals(
            InstallPhase.CONFIGURE,
            installPhaseForReasonCode("authorization_appop_not_allowed"),
        )
        assertEquals(
            InstallPhase.VERIFY,
            installPhaseForReasonCode("installation_installed_package_mismatch"),
        )
        assertEquals(InstallPhase.FETCH, installPhaseForReasonCode("download_not_zip"))
        assertEquals(FailureCategory.ARCHIVE, failureCategoryForReasonCode("download_not_zip"))
    }

    @Test
    fun `component with no authorization actions accepts empty authorization evidence`() {
        val cast = evidenceManifest(
            componentId = "cast",
            packageName = "com.ninepointnine.desktopcast",
            required = false,
        )
        val session = batchReceiptSession(listOf(cast))
        val receipt = successfulReceipt(session)

        session.dispatchEvent(
            InstallationSessionEvent.InstallationBatchCompleted(receipt),
        )

        assertEquals(InstallationSessionState.SUCCEEDED, session.currentSnapshot().state)
        assertEquals(
            AuthorizationStageReceiptStatus.NOT_REQUIRED,
            receipt.components.single().authorization.status,
        )
        assertTrue(session.currentSnapshot().evidence.authorizationActions.isEmpty())
    }

    @Test
    fun `catalog diagnostic does not contaminate successful installation result`() {
        val cast = evidenceManifest(
            componentId = "cast",
            packageName = "com.ninepointnine.desktopcast",
            required = false,
        )
        val descriptor = cast.toComponentDescriptor().copy(
            status = ComponentStatus.DIRECTORY_MISSING,
            errorReason = "lanzou_folder_missing_cast",
        )
        val session = batchReceiptSession(listOf(cast), descriptors = listOf(descriptor))

        session.dispatchEvent(
            InstallationSessionEvent.InstallationBatchCompleted(successfulReceipt(session)),
        )

        val snapshot = session.currentSnapshot()
        assertEquals(InstallationSessionState.SUCCEEDED, snapshot.state)
        assertEquals("lanzou_folder_missing_cast", snapshot.components.single().errorReason)
        assertEquals(ComponentResultStatus.READY, snapshot.componentResults.single().status)
        assertEquals(null, snapshot.componentResults.single().failureReason)
    }

    @Test
    fun `batch receipt atomically retains installed manifests when authorization fails`() {
        val manifests = listOf(
            evidenceManifest(
                componentId = "desktop",
                packageName = AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME,
                required = true,
            ),
            evidenceManifest(
                componentId = "lyrics",
                packageName = AuthorizationPlanFactory.LYRICS_PACKAGE_NAME,
                required = false,
            ),
        )
        val componentIds = manifests.mapTo(linkedSetOf()) { it.componentId }
        val session = batchReceiptSession(manifests)
        val receipt = successfulReceipt(session).mapComponent("lyrics") { component ->
            component.copy(
                authorization = AuthorizationStageReceipt(
                    status = AuthorizationStageReceiptStatus.FAILED,
                    reasonCode = "authorization_failed",
                    retryable = true,
                ),
                availability = AvailabilityStageReceipt(
                    status = AvailabilityStageReceiptStatus.NOT_ATTEMPTED,
                    reasonCode = "availability_not_attempted_authorization_incomplete",
                ),
            )
        }

        session.dispatchEvent(
            InstallationSessionEvent.InstallationBatchCompleted(receipt),
        )

        val snapshot = session.currentSnapshot()
        assertEquals(InstallationSessionState.COMPLETED_WITH_ERRORS, snapshot.state)
        assertEquals(componentIds, snapshot.maintenance.installedManifests.map { it.componentId }.toSet())
    }

    @Test
    fun `installed readback treats version size and digest as observations`() {
        val manifest = evidenceManifest(
            componentId = "desktop",
            packageName = AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME,
            required = true,
        )
        val session = batchReceiptSession(listOf(manifest))
        val observed = InstalledArtifactEvidence(
            componentId = manifest.componentId,
            packageName = manifest.packageName,
            // Vehicle-side package storage may expose a transformed base APK;
            // these values must remain observations, not a second identity gate.
            version = ArtifactVersion("9.9.9", 999),
            apkSizeBytes = manifest.apkSizeBytes + 7L,
            apkSha256 = "44".repeat(32),
            certificateSha256 = manifest.certificateSha256,
        )

        val receipt = successfulReceipt(session).mapComponent("desktop") { component ->
            component.copy(installation = component.installation.copy(evidence = observed))
        }
        session.dispatchEvent(InstallationSessionEvent.InstallationBatchCompleted(receipt))

        val snapshot = session.currentSnapshot()
        assertEquals(InstallationSessionState.SUCCEEDED, snapshot.state)
        assertEquals(observed, snapshot.evidence.installation.getValue("desktop"))
        assertTrue(snapshot.componentResults.single().installed)
    }

    @Test
    fun `optional installed components do not require launch or service evidence`() {
        val manifests = listOf(
            evidenceManifest("desktop", AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME, required = true),
            evidenceManifest("lyrics", AuthorizationPlanFactory.LYRICS_PACKAGE_NAME, required = false),
            evidenceManifest("file-manager", AuthorizationPlanFactory.FILE_MANAGER_PACKAGE_NAME, required = false),
        )
        val session = batchReceiptSession(manifests)

        session.dispatchEvent(
            InstallationSessionEvent.InstallationBatchCompleted(successfulReceipt(session)),
        )

        assertEquals(InstallationSessionState.SUCCEEDED, session.currentSnapshot().state)
        assertTrue(session.currentSnapshot().componentResults.all { it.installed && it.configured && it.available })
    }

    @Test
    fun `normal path reaches one terminal receipt commit after all guarded preparation stages`() {
        val session = connectedSession(includeOptional = true)

        assertEquals(InstallationSessionState.CONNECTED, session.currentSnapshot().state)
        resolveTrustedCatalog(session)
        session.dispatch(InstallationSessionCommand.StartInstallation)
        assertEquals(InstallationSessionState.SELECTION_CONFIRMED, session.currentSnapshot().state)
        session.dispatch(InstallationSessionCommand.BeginPipeline)
        assertEquals(InstallationSessionState.PREPARING_ARTIFACTS, session.currentSnapshot().state)

        dispatchArtifactBatchPrepared(session)
        assertEquals(InstallationSessionState.ARTIFACTS_READY, session.currentSnapshot().state)
        session.dispatchEvent(InstallationSessionEvent.InstallationStarted())
        assertEquals(InstallationSessionState.INSTALLING, session.currentSnapshot().state)
        session.dispatchEvent(
            InstallationSessionEvent.InstallationBatchCompleted(successfulReceipt(session)),
        )

        val snapshot = session.currentSnapshot()
        assertEquals(InstallationSessionState.SUCCEEDED, snapshot.state)
        assertEquals(3, snapshot.componentResults.size)
        assertTrue(snapshot.componentResults.all { it.installed && it.configured && it.available })
    }

    @Test
    fun `discovery starts only from an explicit command and deduplicates devices`() {
        val session = InstallationSession(components)
        session.dispatchEvent(InstallationSessionEvent.DeviceDiscovered(confirmedDevice))
        assertEquals(InstallationSessionState.FAILED, session.currentSnapshot().state)
        assertEquals("device_event_out_of_order", session.currentSnapshot().failure?.reasonCode)

        session.dispatch(InstallationSessionCommand.StartDiscovery)
        val sessionId = session.currentSnapshot().sessionId
        session.dispatch(
            InstallationSessionCommand.AdapterEvent(
                event = InstallationSessionEvent.DeviceDiscovered(confirmedDevice),
                sessionId = sessionId,
                sequence = 1L,
            ),
        )
        session.dispatch(
            InstallationSessionCommand.AdapterEvent(
                event = InstallationSessionEvent.DeviceDiscovered(confirmedDevice.copy(displayName = "iCAR 03 renamed")),
                sessionId = sessionId,
                sequence = 2L,
            ),
        )

        assertEquals(1, session.currentSnapshot().discoveredDevices.size)
        assertEquals("iCAR 03 renamed", session.currentSnapshot().discoveredDevices.single().displayName)
        assertEquals(InstallationSessionState.DISCOVERING, session.currentSnapshot().state)
    }

    @Test
    fun `selecting a device starts a new generation and rejects late discovery events`() {
        val session = InstallationSession(components)
        session.dispatch(InstallationSessionCommand.StartDiscovery)
        val discoveryGeneration = session.currentSnapshot().sessionId
        session.dispatch(
            InstallationSessionCommand.AdapterEvent(
                event = InstallationSessionEvent.DeviceDiscovered(confirmedDevice),
                sessionId = discoveryGeneration,
                sequence = 1L,
            ),
        )

        session.dispatch(InstallationSessionCommand.SelectDevice(confirmedDevice.id))
        val connecting = session.currentSnapshot()
        assertEquals(InstallationSessionState.CONNECTING, connecting.state)
        session.dispatchEvent(
            InstallationSessionEvent.DeviceConnectionConfirmed(confirmedDevice),
            sessionId = connecting.sessionId,
            sequence = connecting.lastEventSequence + 1L,
        )
        val connected = session.currentSnapshot()
        assertEquals(InstallationSessionState.CONNECTED, connected.state)
        assertNotEquals(discoveryGeneration, connected.sessionId)

        session.dispatch(
            InstallationSessionCommand.AdapterEvent(
                event = InstallationSessionEvent.DiscoveryFinished(1, 1),
                sessionId = discoveryGeneration,
                sequence = 2L,
            ),
        )
        assertEquals(connected, session.currentSnapshot())
    }

    @Test
    fun `unconfirmed device cannot become connected`() {
        val session = InstallationSession(components)
        session.dispatch(InstallationSessionCommand.StartDiscovery)
        session.dispatchEvent(
            InstallationSessionEvent.DeviceDiscovered(
                confirmedDevice.copy(connectionStatus = DeviceConnectionStatus.CONNECTING),
            ),
        )
        session.dispatch(InstallationSessionCommand.SelectDevice(confirmedDevice.id))

        assertEquals(InstallationSessionState.FAILED, session.currentSnapshot().state)
        assertEquals("device_not_confirmed", session.currentSnapshot().failure?.reasonCode)
        assertTrue(session.currentSnapshot().device == null)
    }

    @Test
    fun `connection failure stays outside the selection page and is retryable`() {
        val session = InstallationSession(components)
        session.dispatch(InstallationSessionCommand.StartDiscovery)
        session.dispatchEvent(InstallationSessionEvent.DeviceDiscovered(confirmedDevice))
        session.dispatch(InstallationSessionCommand.SelectDevice(confirmedDevice.id))
        val connecting = session.currentSnapshot()

        assertEquals(InstallationSessionState.CONNECTING, connecting.state)
        session.dispatchEvent(
            InstallationSessionEvent.DeviceConnectionFailed(
                deviceId = confirmedDevice.id,
                reasonCode = "adb_connect_failed",
            ),
            sessionId = connecting.sessionId,
            sequence = connecting.lastEventSequence + 1L,
        )

        assertEquals(InstallationSessionState.FAILED, session.currentSnapshot().state)
        assertEquals(FailureCategory.CONNECTION, session.currentSnapshot().failure?.category)
        assertEquals(DeviceConnectionStatus.DISCONNECTED, session.currentSnapshot().device?.connectionStatus)
    }

    @Test
    fun `only maintenance user action releases the connection state`() {
        val session = connectedSession(includeOptional = false)
        complete(session)
        session.dispatch(InstallationSessionCommand.EnterMaintenance)
        session.dispatch(InstallationSessionCommand.DisconnectDevice)

        assertEquals(InstallationSessionState.MAINTENANCE, session.currentSnapshot().state)
        assertEquals(DeviceConnectionStatus.DISCONNECTED, session.currentSnapshot().device?.connectionStatus)

        session.dispatch(InstallationSessionCommand.DisconnectDevice)
        assertEquals(InstallationSessionState.MAINTENANCE, session.currentSnapshot().state)
    }

    @Test
    fun `required components remain selected while lightweight catalog defers optional metadata`() {
        val session = connectedSession(
            includeOptional = false,
            catalog = components.map { component ->
                if (component.id == "file-manager") component.copy(sizeLabel = null) else component
            },
        )
        session.dispatch(InstallationSessionCommand.ToggleOptionalComponent("lyrics", selected = true))
        assertEquals(setOf("lyrics"), session.currentSnapshot().selectedOptionalComponentIds)
        session.dispatch(InstallationSessionCommand.ToggleOptionalComponent("lyrics", selected = false))
        assertTrue(session.currentSnapshot().selectedOptionalComponentIds.isEmpty())

        session.dispatch(InstallationSessionCommand.ToggleOptionalComponent("file-manager", selected = true))
        session.dispatch(InstallationSessionCommand.StartInstallation)

        assertEquals(InstallationSessionState.SELECTION_CONFIRMED, session.currentSnapshot().state)
        assertEquals(null, session.currentSnapshot().failure)
    }

    @Test
    fun `initial catalog selects every optional component and keeps explicit opt outs on refresh`() {
        val cast = ComponentDescriptor(
            id = "cast",
            displayName = "Cast",
            // A non-desktop `required` value is only a catalog recommendation;
            // it must not make the row mandatory in the initial flow.
            required = true,
            versionLabel = "1.0",
            sizeLabel = "7 MB",
            compatibilityLabel = "optional",
        )
        val catalog = components + cast
        val session = connectedSession(includeOptional = false, catalog = catalog)

        fun resolvedCatalog(revision: Long) = InstallationSessionEvent.DistributionConfigResolved(
            configVersion = "catalog-$revision",
            keyId = "fixture-key",
            signatureAlgorithm = "Ed25519",
            components = catalog,
            catalogRevision = revision,
        )

        session.dispatchEvent(resolvedCatalog(revision = 1L))
        assertEquals(
            setOf("lyrics", "file-manager", "cast"),
            session.currentSnapshot().selectedOptionalComponentIds,
        )

        session.dispatch(
            InstallationSessionCommand.ToggleOptionalComponent("lyrics", selected = false),
        )
        session.dispatchEvent(resolvedCatalog(revision = 2L))
        assertEquals(
            setOf("file-manager", "cast"),
            session.currentSnapshot().selectedOptionalComponentIds,
        )

        session.dispatch(
            InstallationSessionCommand.ToggleOptionalComponent("cast", selected = false),
        )

        // The desktop remains part of the frozen batch even though it is not
        // represented in selectedOptionalComponentIds.
        session.dispatch(
            InstallationSessionCommand.ToggleOptionalComponent("desktop", selected = false),
        )
        session.dispatch(InstallationSessionCommand.StartInstallation)
        assertEquals(
            setOf("desktop", "file-manager"),
            session.currentSnapshot().installationBatch?.selectedComponentIds,
        )
    }

    @Test
    fun `catalog retry returns to all optional defaults after a failed load`() {
        val session = connectedSession(includeOptional = false)
        val catalog = session.currentSnapshot().components

        fun resolvedCatalog(revision: Long) = InstallationSessionEvent.DistributionConfigResolved(
            configVersion = "catalog-$revision",
            keyId = "fixture-key",
            signatureAlgorithm = "Ed25519",
            components = catalog,
            catalogRevision = revision,
        )

        session.dispatchEvent(resolvedCatalog(revision = 1L))
        session.dispatchEvent(
            InstallationSessionEvent.CatalogFailed(
                reasonCode = "catalog_load_failed",
                retryable = true,
            ),
        )
        session.dispatch(InstallationSessionCommand.RetryInstallation)
        assertTrue(session.currentSnapshot().selectedOptionalComponentIds.isEmpty())
        assertTrue(session.currentSnapshot().components.isEmpty())

        session.dispatchEvent(resolvedCatalog(revision = 2L))
        assertEquals(
            setOf("lyrics", "file-manager"),
            session.currentSnapshot().selectedOptionalComponentIds,
        )
    }

    @Test
    fun `selection cannot start without both locked core components`() {
        val session = connectedSession(
            includeOptional = false,
            catalog = components.filterNot { it.id == "desktop" },
        )
        session.dispatch(InstallationSessionCommand.StartInstallation)

        assertEquals(InstallationSessionState.FAILED, session.currentSnapshot().state)
        assertEquals("required_components_missing", session.currentSnapshot().failure?.reasonCode)
    }

    @Test
    fun `cancel and disconnect pause with a checkpoint and stale events are ignored`() {
        val session = connectedSession(includeOptional = false)
        session.dispatch(InstallationSessionCommand.StartInstallation)
        session.dispatch(InstallationSessionCommand.BeginPipeline)
        val oldSessionId = session.currentSnapshot().sessionId

        session.dispatch(InstallationSessionCommand.CancelInstallation)
        val paused = session.currentSnapshot()
        assertEquals(InstallationSessionState.PAUSED, paused.state)
        assertEquals(InstallationSessionState.PREPARING_ARTIFACTS, paused.checkpoint?.state)
        assertNotEquals(oldSessionId, paused.sessionId)

        session.dispatch(
            InstallationSessionCommand.AdapterEvent(
                event = InstallationSessionEvent.ComponentProgressUpdated(
                    componentId = "desktop",
                    phase = InstallPhase.FETCH,
                    status = ComponentProgressStatus.COMPLETED,
                    fraction = 1f,
                    indeterminate = false,
                ),
                sessionId = oldSessionId,
                sequence = 99L,
            ),
        )
        assertEquals(paused, session.currentSnapshot())

        session.dispatch(InstallationSessionCommand.ContinueInstallation)
        assertEquals(InstallationSessionState.PREPARING_ARTIFACTS, session.currentSnapshot().state)
        assertNotEquals(paused.sessionId, session.currentSnapshot().sessionId)
    }

    @Test
    fun `recoverable disconnect pauses and fatal or unknown events fail closed`() {
        val session = connectedSession(includeOptional = false)
        session.dispatch(InstallationSessionCommand.StartInstallation)
        session.dispatch(InstallationSessionCommand.BeginPipeline)
        session.dispatchEvent(InstallationSessionEvent.DeviceDisconnected(confirmedDevice.id))
        assertEquals(InstallationSessionState.PAUSED, session.currentSnapshot().state)

        val failed = InstallationSession(components)
        failed.dispatch(InstallationSessionCommand.StartDiscovery)
        failed.dispatchEvent(InstallationSessionEvent.Unknown)
        assertEquals(InstallationSessionState.FAILED, failed.currentSnapshot().state)
        assertEquals(FailureCategory.UNKNOWN, failed.currentSnapshot().failure?.category)
    }

    @Test
    fun `disconnected checkpoint resumes only after confirmed reconnect event`() {
        val session = connectedSession(includeOptional = false)
        session.dispatch(InstallationSessionCommand.StartInstallation)
        session.dispatch(InstallationSessionCommand.BeginPipeline)
        session.dispatchEvent(InstallationSessionEvent.DeviceDisconnected(confirmedDevice.id))
        val paused = session.currentSnapshot()
        assertEquals(InstallationSessionState.PAUSED, paused.state)
        assertEquals(DeviceConnectionStatus.DISCONNECTED, paused.device?.connectionStatus)

        session.dispatch(InstallationSessionCommand.ContinueInstallation)
        assertEquals(InstallationSessionState.FAILED, session.currentSnapshot().state)
        assertEquals("device_not_confirmed", session.currentSnapshot().failure?.reasonCode)

        val reconnecting = session.currentSnapshot()
        session.dispatchEvent(
            InstallationSessionEvent.DeviceReconnected(
                confirmedDevice,
            ),
            sessionId = reconnecting.sessionId,
            sequence = reconnecting.lastEventSequence + 1L,
        )
        assertEquals(InstallationSessionState.PREPARING_ARTIFACTS, session.currentSnapshot().state)
        assertEquals(DeviceConnectionStatus.CONFIRMED, session.currentSnapshot().device?.connectionStatus)
    }

    @Test
    fun `connected checkpoint uses installation reconnect instead of resetting to discovery`() {
        val session = connectedSession(includeOptional = false)
        val before = session.currentSnapshot()

        assertEquals(InstallationSessionState.CONNECTED, before.state)
        assertEquals(InstallationSessionState.CONNECTED, before.checkpoint?.state)

        session.dispatch(InstallationSessionCommand.Reconnect)
        val reconnecting = session.currentSnapshot()
        assertEquals(InstallationSessionState.DISCOVERING, reconnecting.state)
        assertTrue(reconnecting.installationReconnectPending)
        assertEquals(before.checkpoint, reconnecting.checkpoint)
        assertEquals(before.device?.id, reconnecting.device?.id)

        session.dispatchEvent(InstallationSessionEvent.DeviceDiscovered(confirmedDevice))
        session.dispatch(InstallationSessionCommand.SelectDevice(confirmedDevice.id))
        val connecting = session.currentSnapshot()
        assertEquals(InstallationSessionState.CONNECTING, connecting.state)
        assertTrue(connecting.installationReconnectPending)

        session.dispatchEvent(
            InstallationSessionEvent.DeviceConnectionConfirmed(confirmedDevice),
            sessionId = connecting.sessionId,
            sequence = connecting.lastEventSequence + 1L,
        )
        val restored = session.currentSnapshot()
        assertEquals(InstallationSessionState.CONNECTED, restored.state)
        assertEquals(DeviceConnectionStatus.CONFIRMED, restored.device?.connectionStatus)
        assertFalse(restored.installationReconnectPending)
        assertEquals(before.checkpoint?.state, restored.checkpoint?.state)
    }

    @Test
    fun `installation reconnect advances event generations without rebuilding the maintenance batch`() {
        val desktop = fullManifest("desktop", versionCode = 1)
        val cast = evidenceManifest("cast", "com.ninepointnine.desktopcast", required = false)
        val descriptors = listOf(desktop, cast).map { it.toComponentDescriptor() }
        val vehicle = confirmedDevice.copy(
            androidSdk = 28,
            capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
        )
        val session = InstallationSession(
            initialSnapshot = InstallationSessionSnapshot(
                state = InstallationSessionState.MAINTENANCE,
                device = vehicle,
                components = descriptors,
                artifactCatalogStage = ArtifactCatalogStage.PREPARED,
                maintenance = MaintenanceSnapshot(
                    managedApplicationsState = MaintenanceInventoryState.READY,
                    managedApplications = listOf(
                        ManagedApplicationStatus(
                            componentId = desktop.componentId,
                            packageName = desktop.packageName,
                            installed = true,
                            versionCode = desktop.apkVersion.code,
                        ),
                    ),
                    availableComponents = descriptors,
                    installedManifests = listOf(desktop),
                    availableManifests = listOf(desktop, cast),
                    installationSelection = MaintenanceInstallationSelection(
                        actionId = MaintenanceActionId.INSTALL_APPLICATIONS,
                        options = descriptors.map { descriptor ->
                            MaintenanceInstallationOption(
                                componentId = descriptor.id,
                                displayName = descriptor.displayName,
                                installed = descriptor.id == desktop.componentId,
                                required = descriptor.required,
                            )
                        },
                        selectedComponentIds = setOf(cast.componentId),
                    ),
                ),
                evidence = SessionEvidence(
                    installed = setOf(desktop.componentId),
                    configured = setOf(desktop.componentId),
                    available = setOf(desktop.componentId),
                ),
            ),
        )

        session.dispatch(InstallationSessionCommand.StartMaintenanceInstallation)
        session.dispatch(InstallationSessionCommand.BeginPipeline)
        val beforeDisconnect = session.currentSnapshot()
        val batch = checkNotNull(beforeDisconnect.installationBatch)
        assertEquals(InstallationSessionState.PREPARING_ARTIFACTS, beforeDisconnect.state)

        session.dispatchEvent(InstallationSessionEvent.DeviceDisconnected(vehicle.id))
        val paused = session.currentSnapshot()
        assertNotEquals(beforeDisconnect.sessionId, paused.sessionId)
        assertEquals(batch, paused.installationBatch)
        assertEquals(batch, paused.checkpoint?.installationBatch)
        assertEquals(batch.preparationComponentIds, setOf(cast.componentId))
        assertEquals(batch.resultComponentIds, paused.componentResults.mapNotNull { it.componentId }.toSet())

        session.dispatch(InstallationSessionCommand.Reconnect)
        val discovering = session.currentSnapshot()
        assertTrue(discovering.installationReconnectPending)
        assertEquals(batch, discovering.installationBatch)
        session.dispatchEvent(InstallationSessionEvent.DeviceDiscovered(vehicle))
        session.dispatch(InstallationSessionCommand.SelectDevice(vehicle.id))
        val connecting = session.currentSnapshot()
        session.dispatchEvent(
            InstallationSessionEvent.DeviceConnectionConfirmed(vehicle),
            sessionId = connecting.sessionId,
            sequence = connecting.lastEventSequence + 1L,
        )

        val restored = session.currentSnapshot()
        assertEquals(InstallationSessionState.PREPARING_ARTIFACTS, restored.state)
        assertNotEquals(beforeDisconnect.sessionId, restored.sessionId)
        assertEquals(batch, restored.installationBatch)
        assertEquals(batch, restored.checkpoint?.installationBatch)
        assertEquals(batch.resultComponentIds, restored.componentResults.mapNotNull { it.componentId }.toSet())
        val beforeStaleEvent = restored
        session.dispatch(
            InstallationSessionCommand.AdapterEvent(
                event = InstallationSessionEvent.ComponentProgressUpdated(
                    componentId = cast.componentId,
                    phase = InstallPhase.FETCH,
                    status = ComponentProgressStatus.COMPLETED,
                    fraction = 1f,
                    indeterminate = false,
                ),
                sessionId = beforeDisconnect.sessionId,
                sequence = 999L,
            ),
        )
        assertEquals(beforeStaleEvent, session.currentSnapshot())
    }

    @Test
    fun `restart from uncertain write keeps the immutable batch and result boundary`() {
        val desktop = fullManifest("desktop", versionCode = 1)
        val cast = fullManifest("cast", versionCode = 1)
        val batch = InstallationBatchPlan(
            batchId = 7L,
            flow = InstallationFlow.MAINTENANCE_INSTALL,
            strategy = InstallationStrategy.INSTALL_MISSING_ONLY,
            selectedComponentIds = setOf(desktop.componentId, cast.componentId),
            reusableComponentIds = setOf(desktop.componentId),
            preparationComponentIds = setOf(cast.componentId),
            resultComponentIds = setOf(cast.componentId),
        )
        val checkpoint = SessionCheckpoint(
            sessionId = 21L,
            state = InstallationSessionState.INSTALLING,
            deviceId = confirmedDevice.id,
            selectedOptionalComponentIds = setOf(cast.componentId),
            currentComponentName = cast.displayName,
            progress = SessionProgress(totalCount = 2, indeterminate = true),
            evidence = SessionEvidence(installed = setOf(desktop.componentId)),
            installationStrategy = batch.strategy,
            artifactCatalogStage = ArtifactCatalogStage.PREPARED,
            installationFlow = batch.flow,
            installationBatch = batch,
        )
        val session = InstallationSession(
            initialSnapshot = InstallationSessionSnapshot(
                state = InstallationSessionState.INSTALLING,
                sessionId = 21L,
                device = confirmedDevice,
                components = listOf(desktop, cast).map { it.toComponentDescriptor() },
                selectedOptionalComponentIds = setOf(cast.componentId),
                artifactManifests = listOf(cast),
                artifactCatalogStage = ArtifactCatalogStage.PREPARED,
                installationStrategy = batch.strategy,
                installationFlow = batch.flow,
                installationBatch = batch,
                componentResults = listOf(
                    ComponentResult(
                        componentName = cast.displayName,
                        installed = false,
                        configured = false,
                        available = false,
                        componentId = cast.componentId,
                    ),
                ),
                evidence = checkpoint.evidence,
                checkpoint = checkpoint,
            ),
        )

        session.dispatch(InstallationSessionCommand.RestartFromCheckpoint)

        val restarted = session.currentSnapshot()
        assertEquals(InstallationSessionState.SELECTION_CONFIRMED, restarted.state)
        assertNotEquals(21L, restarted.sessionId)
        assertEquals(batch, restarted.installationBatch)
        assertEquals(batch, restarted.checkpoint?.installationBatch)
        assertEquals(setOf(cast.componentId), restarted.installationBatch?.preparationComponentIds)
        assertEquals(setOf(cast.componentId), restarted.componentResults.mapNotNull { it.componentId }.toSet())
        val beforeStaleEvent = restarted
        session.dispatch(
            InstallationSessionCommand.AdapterEvent(
                event = InstallationSessionEvent.InstallationStarted(listOf(cast.componentId)),
                sessionId = 21L,
                sequence = 999L,
            ),
        )
        assertEquals(beforeStaleEvent, session.currentSnapshot())
    }

    @Test
    fun `out of order and malformed atomic preparation never advance the pipeline`() {
        val session = connectedSession(includeOptional = false)
        session.dispatch(InstallationSessionCommand.StartInstallation)
        session.dispatchEvent(InstallationSessionEvent.InstallationStarted())

        assertEquals(InstallationSessionState.FAILED, session.currentSnapshot().state)
        assertEquals("installation_start_out_of_order", session.currentSnapshot().failure?.reasonCode)

        val malformed = connectedSession(includeOptional = false)
        malformed.dispatch(InstallationSessionCommand.StartInstallation)
        malformed.dispatch(InstallationSessionCommand.BeginPipeline)
        malformed.dispatchEvent(
            InstallationSessionEvent.ArtifactBatchPrepared(
                batchId = checkNotNull(malformed.currentSnapshot().installationBatch).batchId,
            ),
        )
        assertEquals(InstallationSessionState.FAILED, malformed.currentSnapshot().state)
        assertEquals("artifact_batch_component_set_mismatch", malformed.currentSnapshot().failure?.reasonCode)
    }

    @Test
    fun `terminal receipt missing a selected component is rejected`() {
        val session = batchReceiptSession(
            listOf(fullManifest("desktop", versionCode = 1), fullManifest("lyrics", versionCode = 1)),
        )
        val incomplete = successfulReceipt(session).copy(
            components = successfulReceipt(session).components.filter { it.componentId == "desktop" },
        )

        session.dispatchEvent(InstallationSessionEvent.InstallationBatchCompleted(incomplete))

        assertEquals(InstallationSessionState.FAILED, session.currentSnapshot().state)
        assertEquals(
            "installation_batch_receipt_component_set_mismatch",
            session.currentSnapshot().failure?.reasonCode,
        )
    }

    @Test
    fun `one failed component still reaches a partial result after the remaining app completes`() {
        val session = connectedSession(includeOptional = false)
        session.dispatch(InstallationSessionCommand.ToggleOptionalComponent("lyrics", selected = true))
        startToInstalling(session)
        val receipt = successfulReceipt(session).mapComponent("lyrics") { component ->
            component.copy(
                availability = AvailabilityStageReceipt(
                    status = AvailabilityStageReceiptStatus.FAILED,
                    reasonCode = "availability_check_failed",
                    retryable = true,
                ),
            )
        }

        session.dispatchEvent(
            InstallationSessionEvent.InstallationBatchCompleted(receipt),
        )

        val snapshot = session.currentSnapshot()
        assertEquals(InstallationSessionState.COMPLETED_WITH_ERRORS, snapshot.state)
        assertTrue(snapshot.failedComponentIds.isEmpty())
        assertTrue(snapshot.componentResults.first { it.componentId == "desktop" }.available)
        assertFalse(snapshot.componentResults.first { it.componentId == "lyrics" }.available)
        assertEquals(
            ComponentResultStatus.AVAILABILITY_INCOMPLETE,
            snapshot.componentResults.first { it.componentId == "lyrics" }.status,
        )

        session.dispatch(InstallationSessionCommand.EnterMaintenance)
        val maintenance = session.currentSnapshot()
        assertEquals(InstallationSessionState.MAINTENANCE, maintenance.state)
        assertEquals(null, maintenance.installationBatch)
    }

    @Test
    fun `written but unverified component is confirmation pending and not an install failure`() {
        val session = connectedSession(includeOptional = false)
        session.dispatch(InstallationSessionCommand.ToggleOptionalComponent("lyrics", selected = true))
        startToInstalling(session)
        val receipt = successfulReceipt(session).mapComponent("lyrics") { component ->
            component.copy(
                installation = InstallationStageReceipt(
                    status = InstallationStageReceiptStatus.WRITE_CONFIRMED_PENDING_IDENTITY,
                    reasonCode = "installation_identity_confirmation_pending",
                    retryable = true,
                    writeConfirmed = true,
                ),
                authorization = AuthorizationStageReceipt(
                    status = AuthorizationStageReceiptStatus.NOT_ATTEMPTED,
                    reasonCode = "authorization_not_attempted_installation_unverified",
                ),
                availability = AvailabilityStageReceipt(
                    status = AvailabilityStageReceiptStatus.NOT_ATTEMPTED,
                    reasonCode = "availability_not_attempted_installation_unverified",
                ),
            )
        }

        session.dispatchEvent(
            InstallationSessionEvent.InstallationBatchCompleted(receipt),
        )

        val snapshot = session.currentSnapshot()
        assertEquals(InstallationSessionState.COMPLETED_WITH_ERRORS, snapshot.state)
        assertTrue(snapshot.failedComponentIds.isEmpty())
        assertEquals(ResultKind.CONFIRMATION_PENDING, snapshot.resolveInstallationResult().kind)
        assertEquals(
            ComponentResultStatus.INSTALLATION_PENDING_CONFIRMATION,
            snapshot.resolveInstallationResult().componentResults.first { it.componentId == "lyrics" }.status,
        )
    }

    @Test
    fun `reused maintenance component is preserved without a fresh write or authorization`() {
        val fixture = maintenanceReceiptFixture(fullManifest("lyrics", versionCode = 1))
        val receipt = successfulMaintenanceReceipt(fixture)

        fixture.session.dispatchEvent(InstallationSessionEvent.InstallationBatchCompleted(receipt))

        val desktop = receipt.components.single { it.componentId == "desktop" }
        val snapshot = fixture.session.currentSnapshot()
        assertEquals(InstallationStageReceiptStatus.VERIFIED, desktop.installation.status)
        assertFalse(desktop.installation.writeConfirmed)
        assertEquals(AuthorizationStageReceiptStatus.PRESERVED, desktop.authorization.status)
        assertEquals(AvailabilityStageReceiptStatus.PRESERVED, desktop.availability.status)
        assertTrue(snapshot.evidence.installed.contains("desktop"))
        assertTrue(snapshot.maintenance.installedManifests.any { it.componentId == "desktop" })
    }

    @Test
    fun `reused component with unknown identity is accepted as batch pending`() {
        val fixture = maintenanceReceiptFixture(fullManifest("lyrics", versionCode = 1))
        val receipt = successfulMaintenanceReceipt(fixture).mapComponent("desktop") { component ->
            component.copy(
                installation = InstallationStageReceipt(
                    status = InstallationStageReceiptStatus.WRITE_CONFIRMED_PENDING_IDENTITY,
                    reasonCode = "installation_installed_apk_read_failed",
                    retryable = true,
                    operationConfirmed = true,
                ),
                authorization = AuthorizationStageReceipt(
                    status = AuthorizationStageReceiptStatus.NOT_ATTEMPTED,
                    reasonCode = "authorization_not_attempted_installation_unverified",
                ),
                availability = AvailabilityStageReceipt(
                    status = AvailabilityStageReceiptStatus.NOT_ATTEMPTED,
                    reasonCode = "availability_not_attempted_installation_unverified",
                ),
            )
        }

        fixture.session.dispatchEvent(InstallationSessionEvent.InstallationBatchCompleted(receipt))

        val snapshot = fixture.session.currentSnapshot()
        assertEquals(InstallationSessionState.COMPLETED_WITH_ERRORS, snapshot.state)
        assertEquals(ResultKind.CONFIRMATION_PENDING, snapshot.resolveInstallationResult().kind)
        assertFalse(snapshot.resolveInstallationResult().canEnterMaintenance)
        assertTrue(snapshot.failedComponentIds.isEmpty())
    }

    @Test
    fun `batch receipt rejects a pending identity without a write receipt`() {
        val session = batchReceiptSession(listOf(fullManifest("desktop", versionCode = 1)))
        val invalid = successfulReceipt(session).mapComponent("desktop") { component ->
            component.copy(
                installation = InstallationStageReceipt(
                    status = InstallationStageReceiptStatus.WRITE_CONFIRMED_PENDING_IDENTITY,
                    reasonCode = "installation_identity_confirmation_pending",
                ),
                authorization = AuthorizationStageReceipt(
                    status = AuthorizationStageReceiptStatus.NOT_ATTEMPTED,
                    reasonCode = "authorization_not_attempted_installation_unverified",
                ),
                availability = AvailabilityStageReceipt(
                    status = AvailabilityStageReceiptStatus.NOT_ATTEMPTED,
                    reasonCode = "availability_not_attempted_installation_unverified",
                ),
            )
        }

        session.dispatchEvent(InstallationSessionEvent.InstallationBatchCompleted(invalid))

        assertEquals(InstallationSessionState.FAILED, session.currentSnapshot().state)
        assertEquals(
            "installation_batch_receipt_pending_without_write",
            session.currentSnapshot().failure?.reasonCode,
        )
    }

    @Test
    fun `batch receipt rejects post-install stages after pending identity`() {
        val session = batchReceiptSession(listOf(fullManifest("desktop", versionCode = 1)))
        val invalid = successfulReceipt(session).mapComponent("desktop") { component ->
            component.copy(
                installation = InstallationStageReceipt(
                    status = InstallationStageReceiptStatus.WRITE_CONFIRMED_PENDING_IDENTITY,
                    reasonCode = "installation_identity_confirmation_pending",
                    writeConfirmed = true,
                ),
                authorization = AuthorizationStageReceipt(AuthorizationStageReceiptStatus.NOT_REQUIRED),
                availability = AvailabilityStageReceipt(AvailabilityStageReceiptStatus.NOT_REQUIRED),
            )
        }

        session.dispatchEvent(InstallationSessionEvent.InstallationBatchCompleted(invalid))

        assertEquals(InstallationSessionState.FAILED, session.currentSnapshot().state)
        assertEquals(
            "installation_batch_receipt_post_install_without_identity",
            session.currentSnapshot().failure?.reasonCode,
        )
    }

    @Test
    fun `batch receipt rejects authorization evidence for an unconfirmed installation`() {
        val session = batchReceiptSession(
            listOf(fullManifest("desktop", versionCode = 1), fullManifest("lyrics", versionCode = 1)),
        )
        val invalid = successfulReceipt(session).mapComponent("lyrics") { component ->
            val evidence = component.authorization.evidence.firstOrNull()
                ?: error("fixture must contain authorization evidence")
            component.copy(
                installation = InstallationStageReceipt(
                    status = InstallationStageReceiptStatus.NOT_ATTEMPTED,
                    reasonCode = "lyrics_download_failed",
                ),
                authorization = AuthorizationStageReceipt(
                    status = AuthorizationStageReceiptStatus.NOT_ATTEMPTED,
                    evidence = listOf(evidence),
                    reasonCode = "authorization_not_attempted_installation_unverified",
                ),
                availability = AvailabilityStageReceipt(
                    status = AvailabilityStageReceiptStatus.NOT_ATTEMPTED,
                    reasonCode = "availability_not_attempted_installation_unverified",
                ),
            )
        }

        session.dispatchEvent(InstallationSessionEvent.InstallationBatchCompleted(invalid))

        assertEquals(InstallationSessionState.FAILED, session.currentSnapshot().state)
        assertEquals(
            "installation_batch_receipt_post_install_without_identity",
            session.currentSnapshot().failure?.reasonCode,
        )
    }

    @Test
    fun `batch receipt rejects a reusable component carrying a fresh write flag`() {
        val fixture = maintenanceReceiptFixture(fullManifest("lyrics", versionCode = 1))
        val invalid = successfulMaintenanceReceipt(fixture).mapComponent("desktop") { component ->
            component.copy(
                installation = component.installation.copy(writeConfirmed = true),
            )
        }

        fixture.session.dispatchEvent(InstallationSessionEvent.InstallationBatchCompleted(invalid))

        assertEquals(InstallationSessionState.FAILED, fixture.session.currentSnapshot().state)
        assertEquals(
            "installation_batch_receipt_reusable_write_confirmed",
            fixture.session.currentSnapshot().failure?.reasonCode,
        )
    }

    @Test
    fun `identity mismatch remains a concrete failure even when the device write was confirmed`() {
        val session = batchReceiptSession(
            listOf(fullManifest("desktop", versionCode = 1), fullManifest("lyrics", versionCode = 1)),
        )
        val receipt = successfulReceipt(session).mapComponent("lyrics") { component ->
            component.copy(
                installation = InstallationStageReceipt(
                    status = InstallationStageReceiptStatus.FAILED,
                    reasonCode = "installation_installed_certificate_mismatch",
                    writeConfirmed = true,
                ),
                authorization = AuthorizationStageReceipt(
                    status = AuthorizationStageReceiptStatus.NOT_ATTEMPTED,
                    reasonCode = "authorization_not_attempted_installation_failed",
                ),
                availability = AvailabilityStageReceipt(
                    status = AvailabilityStageReceiptStatus.NOT_ATTEMPTED,
                    reasonCode = "availability_not_attempted_installation_failed",
                ),
            )
        }
        session.dispatchEvent(InstallationSessionEvent.InstallationBatchCompleted(receipt))

        val result = session.currentSnapshot().componentResults.first { it.componentId == "lyrics" }
        assertEquals(ComponentResultStatus.NOT_INSTALLED, result.status)
        assertEquals("installation_installed_certificate_mismatch", result.failureReason)
        assertTrue(session.currentSnapshot().failedComponentIds.isEmpty())
    }

    @Test
    fun `entering maintenance clears the previous secondary route and feedback`() {
        val snapshot = InstallationSessionSnapshot(
            state = InstallationSessionState.COMPLETED_WITH_ERRORS,
            device = confirmedDevice,
            components = listOf(
                ComponentDescriptor("desktop", "Desktop", required = true),
            ),
            evidence = SessionEvidence(
                installed = setOf("desktop"),
                configured = setOf("desktop"),
                available = setOf("desktop"),
            ),
            maintenance = MaintenanceSnapshot(
                routeAction = MaintenanceActionId.INSTALL_APPLICATIONS,
                lastAction = MaintenanceActionRecord(
                    actionId = MaintenanceActionId.INSTALL_APPLICATIONS,
                    status = MaintenanceActionStatus.SUCCEEDED,
                ),
                installationSelection = MaintenanceInstallationSelection(
                    actionId = MaintenanceActionId.INSTALL_APPLICATIONS,
                    options = emptyList(),
                    selectedComponentIds = emptySet(),
                ),
            ),
        )
        val session = InstallationSession(snapshot)
        session.dispatch(InstallationSessionCommand.EnterMaintenance)

        val maintenance = session.currentSnapshot().maintenance
        assertEquals(InstallationSessionState.MAINTENANCE, session.currentSnapshot().state)
        assertEquals(null, maintenance.routeAction)
        assertEquals(null, maintenance.lastAction)
        assertEquals(null, maintenance.installationSelection)
        assertEquals(null, maintenance.applicationAction)
        assertEquals(null, maintenance.applicationDetails)
    }

    @Test
    fun `failed optional component receipt does not invalidate the remaining success proof`() {
        val desktop = evidenceManifest("desktop", AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME, required = true)
        val lyrics = evidenceManifest("lyrics", AuthorizationPlanFactory.LYRICS_PACKAGE_NAME, required = false)
        val manifests = listOf(desktop, lyrics)
        val session = batchReceiptSession(manifests)
        val receipt = successfulReceipt(session).mapComponent("lyrics") { component ->
            component.copy(
                authorization = AuthorizationStageReceipt(
                    status = AuthorizationStageReceiptStatus.FAILED,
                    reasonCode = "authorization_action_failed",
                    retryable = true,
                ),
                availability = AvailabilityStageReceipt(
                    status = AvailabilityStageReceiptStatus.NOT_ATTEMPTED,
                    reasonCode = "availability_not_attempted_authorization_incomplete",
                ),
            )
        }

        session.dispatchEvent(
            InstallationSessionEvent.InstallationBatchCompleted(receipt),
        )

        val snapshot = session.currentSnapshot()
        assertEquals(InstallationSessionState.COMPLETED_WITH_ERRORS, snapshot.state)
        assertTrue(snapshot.failedComponentIds.isEmpty())
        assertTrue(snapshot.componentResults.first { it.componentId == "desktop" }.available)
        assertTrue(snapshot.componentResults.first { it.componentId == "lyrics" }.installed)
        assertFalse(snapshot.componentResults.first { it.componentId == "lyrics" }.configured)
    }

    @Test
    fun `duplicate and older sequence events cannot overwrite a newer snapshot`() {
        val session = connectedSession(includeOptional = false)
        session.dispatch(InstallationSessionCommand.StartInstallation)
        session.dispatch(InstallationSessionCommand.BeginPipeline)
        val sessionId = session.currentSnapshot().sessionId

        session.dispatch(
            InstallationSessionCommand.AdapterEvent(
                event = InstallationSessionEvent.ComponentProgressUpdated(
                    componentId = "desktop",
                    phase = InstallPhase.FETCH,
                    status = ComponentProgressStatus.RUNNING,
                    fraction = 0.5f,
                    indeterminate = false,
                ),
                sessionId = sessionId,
                sequence = 10L,
                eventId = "new-progress",
            ),
        )
        val newer = session.currentSnapshot()
        session.dispatch(
            InstallationSessionCommand.AdapterEvent(
                event = InstallationSessionEvent.ComponentProgressUpdated(
                    componentId = "desktop",
                    phase = InstallPhase.FETCH,
                    status = ComponentProgressStatus.RUNNING,
                    fraction = 0.2f,
                    indeterminate = false,
                ),
                sessionId = sessionId,
                sequence = 9L,
                eventId = "old-progress",
            ),
        )
        session.dispatch(
            InstallationSessionCommand.AdapterEvent(
                event = InstallationSessionEvent.ComponentProgressUpdated(
                    componentId = "desktop",
                    phase = InstallPhase.FETCH,
                    status = ComponentProgressStatus.COMPLETED,
                    fraction = 1f,
                    indeterminate = false,
                ),
                sessionId = sessionId,
                sequence = 10L,
                eventId = "duplicate-sequence",
            ),
        )
        assertEquals(newer, session.currentSnapshot())
    }

    @Test
    fun `successful session can enter maintenance through the same owner`() {
        val session = connectedSession(includeOptional = false)
        complete(session)
        assertEquals(InstallationSessionState.SUCCEEDED, session.currentSnapshot().state)
        session.dispatch(InstallationSessionCommand.EnterMaintenance)
        val maintenance = session.currentSnapshot()
        assertEquals(InstallationSessionState.MAINTENANCE, maintenance.state)
        assertEquals(null, maintenance.installationBatch)
    }

    @Test
    fun `disconnected maintenance keeps only local actions available`() {
        val session = maintenanceSession(connected = false)

        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.LAUNCH_DESKTOP))
        assertEquals(MaintenanceActionStatus.FAILED, session.currentSnapshot().maintenance.lastAction?.status)
        assertEquals("device_disconnected", session.currentSnapshot().maintenance.lastAction?.reasonCode)

        val local = maintenanceSession(connected = false)
        local.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.CLEANUP))
        assertEquals(MaintenanceActionId.CLEANUP, local.currentSnapshot().maintenance.activeAction)
    }

    @Test
    fun `maintenance reconnect returns to maintenance and preserves installation evidence`() {
        val session = maintenanceSession(connected = false)
        val before = session.currentSnapshot()

        session.dispatch(InstallationSessionCommand.Reconnect)
        assertEquals(InstallationSessionState.DISCOVERING, session.currentSnapshot().state)
        assertTrue(session.currentSnapshot().maintenanceReconnectPending)
        assertEquals(before.components, session.currentSnapshot().components)
        assertEquals(before.evidence, session.currentSnapshot().evidence)

        session.dispatchEvent(InstallationSessionEvent.DeviceDiscovered(confirmedDevice))
        session.dispatch(InstallationSessionCommand.SelectDevice(confirmedDevice.id))
        val connecting = session.currentSnapshot()
        assertEquals(InstallationSessionState.CONNECTING, connecting.state)
        assertTrue(connecting.maintenanceReconnectPending)

        session.dispatchEvent(
            InstallationSessionEvent.DeviceConnectionConfirmed(confirmedDevice),
            sessionId = connecting.sessionId,
            sequence = connecting.lastEventSequence + 1L,
        )
        val restored = session.currentSnapshot()
        assertEquals(InstallationSessionState.MAINTENANCE, restored.state)
        assertEquals(DeviceConnectionStatus.CONFIRMED, restored.device?.connectionStatus)
        assertFalse(restored.maintenanceReconnectPending)
        assertEquals(before.components, restored.components)
        assertEquals(before.evidence, restored.evidence)
    }

    @Test
    fun `maintenance reconnect with no device stays on disconnected maintenance page`() {
        val session = maintenanceSession(connected = false)
        session.dispatch(InstallationSessionCommand.StartDiscovery)
        session.dispatchEvent(InstallationSessionEvent.DiscoveryFinished(4, 0, "no_devices_found"))

        val snapshot = session.currentSnapshot()
        assertEquals(InstallationSessionState.MAINTENANCE, snapshot.state)
        assertEquals(DeviceConnectionStatus.DISCONNECTED, snapshot.device?.connectionStatus)
        assertFalse(snapshot.maintenanceReconnectPending)
        assertEquals("no_devices_found", snapshot.failure?.reasonCode)
        assertEquals(3, snapshot.components.size)
    }

    @Test
    fun `maintenance action is serialized and disconnect closes the running action`() {
        val session = maintenanceSession()
        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.CHECK_UPDATES))
        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.EXPORT_DIAGNOSTICS))
        assertEquals(MaintenanceActionId.CHECK_UPDATES, session.currentSnapshot().maintenance.activeAction)

        session.dispatch(InstallationSessionCommand.DisconnectDevice)
        assertEquals(null, session.currentSnapshot().maintenance.activeAction)
        assertEquals(MaintenanceActionStatus.FAILED, session.currentSnapshot().maintenance.lastAction?.status)
        assertEquals("device_disconnected", session.currentSnapshot().maintenance.lastAction?.reasonCode)
    }

    @Test
    fun `application action without managed apps route fails closed without inventing a route`() {
        val session = maintenanceSession()

        session.dispatch(
            InstallationSessionCommand.MaintenanceApplicationAction(
                componentId = "desktop",
                actionId = MaintenanceApplicationActionId.START,
            ),
        )

        val maintenance = session.currentSnapshot().maintenance
        assertEquals(null, maintenance.routeAction)
        assertEquals(MaintenanceActionStatus.FAILED, maintenance.applicationAction?.status)
        assertEquals("maintenance_route_invalid", maintenance.applicationAction?.reasonCode)
    }

    @Test
    fun `application action on another maintenance route does not leak into that route`() {
        val base = maintenanceSession().currentSnapshot()
        val session = InstallationSession(
            initialSnapshot = base.copy(
                maintenance = base.maintenance.copy(routeAction = MaintenanceActionId.CHECK_UPDATES),
            ),
        )

        session.dispatch(
            InstallationSessionCommand.MaintenanceApplicationAction(
                componentId = "desktop",
                actionId = MaintenanceApplicationActionId.START,
            ),
        )

        val maintenance = session.currentSnapshot().maintenance
        assertEquals(MaintenanceActionId.CHECK_UPDATES, maintenance.routeAction)
        assertEquals(MaintenanceActionStatus.FAILED, maintenance.applicationAction?.status)
        assertEquals("maintenance_route_invalid", maintenance.applicationAction?.reasonCode)
    }

    @Test
    fun `application action enters running only on managed apps route`() {
        val base = maintenanceSession().currentSnapshot()
        val session = InstallationSession(
            initialSnapshot = base.copy(
                maintenance = base.maintenance.copy(
                    routeAction = MaintenanceActionId.MANAGE_APPS,
                    managedApplications = listOf(
                        ManagedApplicationStatus(
                            componentId = "desktop",
                            packageName = AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME,
                            installed = true,
                        ),
                    ),
                ),
            ),
        )

        session.dispatch(
            InstallationSessionCommand.MaintenanceApplicationAction(
                componentId = "desktop",
                actionId = MaintenanceApplicationActionId.START,
            ),
        )

        assertEquals(
            MaintenanceActionStatus.RUNNING,
            session.currentSnapshot().maintenance.applicationAction?.status,
        )
        assertEquals(MaintenanceActionId.MANAGE_APPS, session.currentSnapshot().maintenance.routeAction)
    }

    @Test
    fun `new maintenance action clears stale application payload and selection`() {
        val base = maintenanceSession().currentSnapshot()
        val session = InstallationSession(
            initialSnapshot = base.copy(
                maintenance = base.maintenance.copy(
                    routeAction = MaintenanceActionId.MANAGE_APPS,
                    applicationAction = MaintenanceApplicationActionRecord(
                        componentId = "desktop",
                        actionId = MaintenanceApplicationActionId.DETAILS,
                        status = MaintenanceActionStatus.SUCCEEDED,
                        resultCode = "application_details_ready",
                    ),
                    applicationDetails = ManagedApplicationDetails(
                        componentId = "desktop",
                        displayName = "desktop",
                        packageName = AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME,
                    ),
                    installationSelection = MaintenanceInstallationSelection(
                        actionId = MaintenanceActionId.INSTALL_APPLICATIONS,
                    ),
                ),
            ),
        )

        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.CHECK_UPDATES))

        val maintenance = session.currentSnapshot().maintenance
        assertEquals(MaintenanceActionId.CHECK_UPDATES, maintenance.routeAction)
        assertEquals(null, maintenance.applicationAction)
        assertEquals(null, maintenance.applicationDetails)
        assertEquals(null, maintenance.installationSelection)
    }

    @Test
    fun `late inventory from a completed maintenance action cannot enter the next action generation`() {
        val session = maintenanceSession(manifests = listOf(fullManifest("desktop", versionCode = 1)))

        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.CHECK_UPDATES))
        val firstGeneration = session.currentSnapshot()
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceActionCompleted(
                actionId = MaintenanceActionId.CHECK_UPDATES,
                resultCode = "up_to_date",
            ),
            sessionId = firstGeneration.sessionId,
            sequence = firstGeneration.lastEventSequence + 1L,
        )

        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.MANAGE_APPS))
        val secondGeneration = session.currentSnapshot()
        assertNotEquals(firstGeneration.sessionId, secondGeneration.sessionId)
        assertEquals(MaintenanceInventoryState.LOADING, secondGeneration.maintenance.managedApplicationsState)

        session.dispatch(
            InstallationSessionCommand.AdapterEvent(
                event = InstallationSessionEvent.MaintenanceApplicationsResolved(
                    applications = listOf(
                        ManagedApplicationStatus(
                            componentId = "desktop",
                            packageName = AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME,
                            installed = true,
                        ),
                    ),
                ),
                sessionId = firstGeneration.sessionId,
                sequence = 999L,
            ),
        )

        assertEquals(secondGeneration, session.currentSnapshot())
    }

    @Test
    fun `late maintenance callback after leaving a route cannot repopulate the home page`() {
        val session = maintenanceSession(manifests = listOf(fullManifest("desktop", versionCode = 1)))
        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.MANAGE_APPS))
        val actionGeneration = session.currentSnapshot()

        session.dispatch(InstallationSessionCommand.LeaveMaintenanceAction)
        val home = session.currentSnapshot()
        assertNotEquals(actionGeneration.sessionId, home.sessionId)
        assertEquals(null, home.maintenance.routeAction)
        assertEquals(null, home.maintenance.lastAction)
        assertEquals(null, home.maintenance.applicationAction)
        assertEquals(MaintenanceInventoryState.NOT_STARTED, home.maintenance.managedApplicationsState)

        session.dispatch(
            InstallationSessionCommand.AdapterEvent(
                event = InstallationSessionEvent.MaintenanceApplicationsResolved(emptyList()),
                sessionId = actionGeneration.sessionId,
                sequence = 999L,
            ),
        )

        assertEquals(home, session.currentSnapshot())
    }

    @Test
    fun `late application action completion cannot overwrite a newer row action`() {
        val base = maintenanceSession().currentSnapshot()
        val session = InstallationSession(
            initialSnapshot = base.copy(
                maintenance = base.maintenance.copy(
                    routeAction = MaintenanceActionId.MANAGE_APPS,
                    managedApplications = listOf(
                        ManagedApplicationStatus(
                            componentId = "desktop",
                            packageName = AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME,
                            installed = true,
                        ),
                    ),
                ),
            ),
        )

        session.dispatch(
            InstallationSessionCommand.MaintenanceApplicationAction(
                componentId = "desktop",
                actionId = MaintenanceApplicationActionId.START,
            ),
        )
        val firstGeneration = session.currentSnapshot()
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceApplicationActionCompleted(
                componentId = "desktop",
                actionId = MaintenanceApplicationActionId.START,
                resultCode = "component_launched",
            ),
            sessionId = firstGeneration.sessionId,
            sequence = firstGeneration.lastEventSequence + 1L,
        )

        session.dispatch(
            InstallationSessionCommand.MaintenanceApplicationAction(
                componentId = "desktop",
                actionId = MaintenanceApplicationActionId.FORCE_STOP,
            ),
        )
        val secondGeneration = session.currentSnapshot()
        assertNotEquals(firstGeneration.sessionId, secondGeneration.sessionId)
        assertEquals(MaintenanceApplicationActionId.FORCE_STOP, secondGeneration.maintenance.applicationAction?.actionId)
        assertEquals(MaintenanceActionStatus.RUNNING, secondGeneration.maintenance.applicationAction?.status)

        session.dispatch(
            InstallationSessionCommand.AdapterEvent(
                event = InstallationSessionEvent.MaintenanceApplicationActionCompleted(
                    componentId = "desktop",
                    actionId = MaintenanceApplicationActionId.START,
                    resultCode = "late_start",
                ),
                sessionId = firstGeneration.sessionId,
                sequence = 999L,
            ),
        )

        assertEquals(secondGeneration, session.currentSnapshot())
    }

    @Test
    fun `starting the install applications scan discards the previous selection snapshot`() {
        val base = maintenanceSession(
            manifests = listOf(
                fullManifest("desktop", versionCode = 1),
                fullManifest("file-manager", versionCode = 1),
            ),
        ).currentSnapshot()
        val session = InstallationSession(
            initialSnapshot = base.copy(
                maintenance = base.maintenance.copy(
                    installationSelection = MaintenanceInstallationSelection(
                        actionId = MaintenanceActionId.INSTALL_FILE_MANAGER,
                        options = listOf(
                            MaintenanceInstallationOption(
                                componentId = "file-manager",
                                displayName = "文件管理器",
                                installed = true,
                            ),
                        ),
                        selectedComponentIds = setOf("file-manager"),
                    ),
                ),
            ),
        )

        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.INSTALL_FILE_MANAGER))

        assertEquals(null, session.currentSnapshot().maintenance.installationSelection)
        assertEquals(MaintenanceActionId.INSTALL_FILE_MANAGER, session.currentSnapshot().maintenance.activeAction)
        assertTrue(session.currentSnapshot().maintenance.managedApplications.isEmpty())
    }

    @Test
    fun `maintenance install selection excludes installed rows but keeps installed desktop as prerequisite`() {
        val desktop = fullManifest("desktop", versionCode = 1)
        val fileManager = fullManifest("file-manager", versionCode = 1)
        val descriptors = listOf(desktop, fileManager).map { it.toComponentDescriptor() }
        val session = InstallationSession(
            initialSnapshot = InstallationSessionSnapshot(
                state = InstallationSessionState.MAINTENANCE,
                device = confirmedDevice.copy(
                    androidSdk = 28,
                    capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
                ),
                components = descriptors,
                artifactManifests = listOf(desktop, fileManager),
                artifactCatalogStage = ArtifactCatalogStage.PREPARED,
                maintenance = MaintenanceSnapshot(
                    availableComponents = descriptors,
                    availableManifests = listOf(desktop, fileManager),
                ),
            ),
        )

        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.INSTALL_FILE_MANAGER))
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceApplicationsResolved(
                applications = listOf(
                    ManagedApplicationStatus(
                        componentId = "desktop",
                        packageName = desktop.packageName,
                        installed = true,
                    ),
                ),
            ),
        )

        val selection = session.currentSnapshot().maintenance.installationSelection
            ?: error("maintenance selection was not created")
        assertFalse(selection.selectedComponentIds.contains("desktop"))
        assertFalse(selection.selectedComponentIds.contains("file-manager"))
        assertTrue(selection.options.first { it.componentId == "desktop" }.installed)
        assertFalse(selection.options.first { it.componentId == "file-manager" }.installed)

        session.dispatch(
            InstallationSessionCommand.ToggleMaintenanceInstallationComponent(
                componentId = "file-manager",
                selected = true,
            ),
        )
        session.dispatch(InstallationSessionCommand.StartMaintenanceInstallation)

        val started = session.currentSnapshot()
        assertEquals(InstallationSessionState.SELECTION_CONFIRMED, started.state)
        assertEquals(InstallationStrategy.INSTALL_MISSING_ONLY, started.installationStrategy)
        assertEquals(setOf("file-manager"), started.selectedOptionalComponentIds)
        assertEquals(InstallationStrategy.INSTALL_MISSING_ONLY, started.checkpoint?.installationStrategy)
    }

    @Test
    fun `failed maintenance install returns to maintenance selection and retains installed evidence`() {
        val desktop = fullManifest("desktop", versionCode = 1)
        val lyrics = fullManifest("lyrics", versionCode = 1)
        val descriptors = listOf(desktop, lyrics).map { it.toComponentDescriptor() }
        val session = InstallationSession(
            initialSnapshot = InstallationSessionSnapshot(
                state = InstallationSessionState.MAINTENANCE,
                device = confirmedDevice.copy(
                    androidSdk = 28,
                    capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
                ),
                components = descriptors,
                maintenance = MaintenanceSnapshot(
                    managedApplicationsState = MaintenanceInventoryState.READY,
                    managedApplications = listOf(
                        ManagedApplicationStatus(
                            componentId = desktop.componentId,
                            packageName = desktop.packageName,
                            installed = true,
                            versionCode = desktop.apkVersion.code,
                        ),
                    ),
                    availableComponents = descriptors,
                    availableManifests = listOf(desktop, lyrics),
                    installedManifests = listOf(desktop),
                    installationSelection = MaintenanceInstallationSelection(
                        actionId = MaintenanceActionId.INSTALL_FILE_MANAGER,
                        options = listOf(
                            MaintenanceInstallationOption(
                                componentId = desktop.componentId,
                                displayName = desktop.displayName,
                                installed = true,
                                required = true,
                            ),
                            MaintenanceInstallationOption(
                                componentId = lyrics.componentId,
                                displayName = lyrics.displayName,
                                installed = false,
                            ),
                        ),
                        selectedComponentIds = setOf(lyrics.componentId),
                    ),
                ),
                evidence = SessionEvidence(
                    installed = setOf(desktop.componentId),
                    configured = setOf(desktop.componentId),
                    available = setOf(desktop.componentId),
                ),
            ),
        )

        session.dispatch(InstallationSessionCommand.StartMaintenanceInstallation)

        val started = session.currentSnapshot()
        assertEquals(InstallationFlow.MAINTENANCE_INSTALL, started.installationFlow)
        assertEquals(listOf(lyrics.componentId), started.componentResults.map { it.componentId })
        assertFalse(started.componentResults.any { it.componentId == desktop.componentId })
        assertEquals(started.sessionId, started.installationBatch?.batchId)
        assertEquals(started.sessionId, started.checkpoint?.sessionId)
        val firstBatchSessionId = started.sessionId
        session.dispatch(InstallationSessionCommand.BeginPipeline)

        session.dispatchEvent(
            artifactBatchFailureEvent(session, lyrics.componentId, "distribution_archive_invalid"),
        )
        session.dispatchEvent(
            InstallationSessionEvent.FatalError(
                category = FailureCategory.INSTALLATION,
                reasonCode = "installation_batch_failed",
            ),
        )
        assertEquals(
            "distribution_archive_invalid",
            session.currentSnapshot().componentResults.single().failureReason,
        )
        session.dispatch(InstallationSessionCommand.ReturnToMaintenanceInstallationSelection)

        val restored = session.currentSnapshot()
        assertEquals(InstallationSessionState.MAINTENANCE, restored.state)
        assertEquals(InstallationFlow.MAINTENANCE_INSTALL, restored.installationFlow)
        assertEquals(setOf(lyrics.componentId), restored.maintenance.installationSelection?.selectedComponentIds)
        assertEquals(MaintenanceActionStatus.FAILED, restored.maintenance.lastAction?.status)
        assertEquals("distribution_archive_invalid", restored.maintenance.lastAction?.reasonCode)

        // A second attempt keeps the same selection and must project the new
        // batch with the same concrete reason, without reintroducing desktop.
        session.dispatch(InstallationSessionCommand.StartMaintenanceInstallation)
        val secondStarted = session.currentSnapshot()
        assertEquals(listOf(lyrics.componentId), secondStarted.componentResults.map { it.componentId })
        assertNotEquals(firstBatchSessionId, secondStarted.sessionId)
        assertEquals(secondStarted.sessionId, secondStarted.installationBatch?.batchId)
        assertEquals(secondStarted.sessionId, secondStarted.checkpoint?.sessionId)
        session.dispatch(InstallationSessionCommand.BeginPipeline)
        val beforeStaleEvent = session.currentSnapshot()
        session.dispatch(
            InstallationSessionCommand.AdapterEvent(
                event = artifactBatchFailureEvent(session, lyrics.componentId, "stale_batch_event"),
                sessionId = firstBatchSessionId,
                sequence = 999L,
            ),
        )
        assertEquals(beforeStaleEvent, session.currentSnapshot())
        session.dispatchEvent(
            artifactBatchFailureEvent(session, lyrics.componentId, "distribution_archive_invalid"),
        )
        session.dispatchEvent(
            InstallationSessionEvent.FatalError(
                category = FailureCategory.INSTALLATION,
                reasonCode = "installation_batch_failed",
            ),
        )
        session.dispatch(InstallationSessionCommand.ReturnToMaintenanceInstallationSelection)
        assertEquals("distribution_archive_invalid", session.currentSnapshot().maintenance.lastAction?.reasonCode)
        assertEquals(
            setOf(lyrics.componentId),
            session.currentSnapshot().maintenance.installationSelection?.selectedComponentIds,
        )
    }

    @Test
    fun `reused maintenance prerequisite can complete from persisted aggregate evidence`() {
        val cast = evidenceManifest("cast", "com.ninepointnine.desktopcast", required = false)
        val fixture = maintenanceReceiptFixture(cast)
        val receipt = successfulMaintenanceReceipt(fixture).mapComponent("cast") { component ->
            component.copy(
                installation = InstallationStageReceipt(
                    status = InstallationStageReceiptStatus.FAILED,
                    reasonCode = "installation_failed",
                    retryable = true,
                ),
                authorization = AuthorizationStageReceipt(
                    status = AuthorizationStageReceiptStatus.NOT_ATTEMPTED,
                    reasonCode = "authorization_not_attempted_installation_failed",
                ),
                availability = AvailabilityStageReceipt(
                    status = AvailabilityStageReceiptStatus.NOT_ATTEMPTED,
                    reasonCode = "availability_not_attempted_installation_failed",
                ),
            )
        }

        fixture.session.dispatchEvent(InstallationSessionEvent.InstallationBatchCompleted(receipt))

        val snapshot = fixture.session.currentSnapshot()
        assertEquals(InstallationSessionState.COMPLETED_WITH_ERRORS, snapshot.state)
        assertTrue(snapshot.evidence.installed.contains("desktop"))
        assertEquals(listOf("cast"), snapshot.resolveInstallationResult().componentResults.map { it.componentId })
    }

    @Test
    fun `control plane maintenance selection advances into selected catalog preparation`() {
        val desktop = fullManifest("desktop", versionCode = 1)
        val fileManager = fullManifest("file-manager", versionCode = 1)
        val descriptors = listOf(desktop, fileManager).map { it.toComponentDescriptor() }
        val session = InstallationSession(
            initialSnapshot = InstallationSessionSnapshot(
                state = InstallationSessionState.MAINTENANCE,
                device = confirmedDevice.copy(
                    androidSdk = 28,
                    capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
                ),
                components = descriptors,
                maintenance = MaintenanceSnapshot(
                    availableComponents = descriptors,
                    managedApplicationsState = MaintenanceInventoryState.NOT_STARTED,
                ),
            ),
        )

        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.INSTALL_FILE_MANAGER))
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceCatalogRefreshed(
                catalogVersion = "config-2",
                keyId = "key-1",
                signatureAlgorithm = "Ed25519",
                manifests = emptyList(),
                apps = descriptors,
                controlPlaneOnly = true,
            ),
        )
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceApplicationsResolved(
                applications = listOf(
                    ManagedApplicationStatus(
                        componentId = "desktop",
                        packageName = desktop.packageName,
                        installed = true,
                        versionCode = desktop.apkVersion.code,
                    ),
                ),
            ),
        )

        val selection = checkNotNull(session.currentSnapshot().maintenance.installationSelection)
        assertTrue(selection.options.first { it.componentId == "desktop" }.installed)
        assertFalse(selection.options.first { it.componentId == "file-manager" }.installed)
        session.dispatch(
            InstallationSessionCommand.ToggleMaintenanceInstallationComponent(
                componentId = "file-manager",
                selected = true,
            ),
        )
        session.dispatch(InstallationSessionCommand.StartMaintenanceInstallation)

        val started = session.currentSnapshot()
        assertEquals(InstallationSessionState.SELECTION_CONFIRMED, started.state)
        assertEquals(ArtifactCatalogStage.CONTROL_PLANE_READY, started.artifactCatalogStage)
        assertEquals(setOf("file-manager"), started.selectedOptionalComponentIds)
        assertEquals(null, started.failure)
    }

    @Test
    fun `stale partial maintenance manifests do not block a newly selected component`() {
        val desktop = fullManifest("desktop", versionCode = 1)
        val lyrics = fullManifest("lyrics", versionCode = 1)
        val fileManager = fullManifest("file-manager", versionCode = 1)
        val descriptors = listOf(
            desktop.toComponentDescriptor(),
            lyrics.toComponentDescriptor(),
            ComponentDescriptor(
                id = fileManager.componentId,
                displayName = fileManager.displayName,
                required = false,
                versionLabel = "v1.0",
                sizeLabel = "30M",
                compatibilityState = ComponentCompatibility.UNKNOWN,
                status = ComponentStatus.READING,
            ),
        )
        val session = InstallationSession(
            initialSnapshot = InstallationSessionSnapshot(
                state = InstallationSessionState.MAINTENANCE,
                device = confirmedDevice.copy(
                    androidSdk = 28,
                    capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
                ),
                components = descriptors,
                maintenance = MaintenanceSnapshot(
                    availableComponents = descriptors,
                    availableManifests = listOf(desktop, lyrics),
                    installedManifests = listOf(desktop),
                    managedApplicationsState = MaintenanceInventoryState.READY,
                    managedApplications = listOf(
                        ManagedApplicationStatus(
                            componentId = desktop.componentId,
                            packageName = desktop.packageName,
                            installed = true,
                            versionCode = desktop.apkVersion.code,
                        ),
                    ),
                    installationSelection = MaintenanceInstallationSelection(
                        actionId = MaintenanceActionId.INSTALL_APPLICATIONS,
                        options = listOf(
                            MaintenanceInstallationOption(
                                componentId = desktop.componentId,
                                displayName = desktop.displayName,
                                installed = true,
                                required = true,
                            ),
                            MaintenanceInstallationOption(
                                componentId = fileManager.componentId,
                                displayName = fileManager.displayName,
                            ),
                        ),
                        selectedComponentIds = setOf(fileManager.componentId),
                    ),
                ),
            ),
        )

        session.dispatch(InstallationSessionCommand.StartMaintenanceInstallation)

        val snapshot = session.currentSnapshot()
        assertEquals(InstallationSessionState.SELECTION_CONFIRMED, snapshot.state)
        assertEquals(ArtifactCatalogStage.CONTROL_PLANE_READY, snapshot.artifactCatalogStage)
        assertEquals(null, snapshot.failure)
        assertEquals(setOf("desktop", "file-manager"), snapshot.installationBatch?.selectedComponentIds)
        assertEquals(setOf("file-manager"), snapshot.installationBatch?.preparationComponentIds)
    }

    @Test
    fun `installed optional with unknown version stays non-selectable but enters verification batch`() {
        val desktop = fullManifest("desktop", versionCode = 1)
        val lyrics = fullManifest("lyrics", versionCode = 1)
        val descriptors = listOf(desktop, lyrics).map { it.toComponentDescriptor() }
        val session = InstallationSession(
            initialSnapshot = InstallationSessionSnapshot(
                state = InstallationSessionState.MAINTENANCE,
                device = confirmedDevice.copy(
                    androidSdk = 28,
                    capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
                ),
                components = descriptors,
                maintenance = MaintenanceSnapshot(
                    availableComponents = descriptors,
                    availableManifests = listOf(desktop, lyrics),
                    installedManifests = listOf(desktop, lyrics),
                    managedApplicationsState = MaintenanceInventoryState.READY,
                ),
            ),
        )

        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.INSTALL_FILE_MANAGER))
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceApplicationsResolved(
                applications = listOf(
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
                        versionCode = null,
                    ),
                ),
            ),
        )

        val selection = checkNotNull(session.currentSnapshot().maintenance.installationSelection)
        assertTrue(selection.options.first { it.componentId == "lyrics" }.installed)
        assertFalse(selection.selectedComponentIds.contains("lyrics"))

        session.dispatch(InstallationSessionCommand.StartMaintenanceInstallation)

        val started = session.currentSnapshot()
        assertEquals(InstallationSessionState.SELECTION_CONFIRMED, started.state)
        assertTrue(
            "an installed component with unknown version must be verified in the batch",
            "lyrics" in started.selectedOptionalComponentIds,
        )
    }

    @Test
    fun `maintenance reinstall keeps explicit overwrite strategy`() {
        val session = maintenanceSession(
            manifests = listOf(
                fullManifest("desktop", versionCode = 1),
                fullManifest("file-manager", versionCode = 1),
            ),
        )

        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.REINSTALL))

        assertEquals(InstallationSessionState.SELECTION_CONFIRMED, session.currentSnapshot().state)
        assertEquals(InstallationStrategy.REINSTALL_SELECTED, session.currentSnapshot().installationStrategy)
        assertEquals(InstallationStrategy.REINSTALL_SELECTED, session.currentSnapshot().checkpoint?.installationStrategy)
    }

    @Test
    fun `successful uninstall removes the application from the maintenance inventory`() {
        val base = maintenanceSession().currentSnapshot()
        val session = InstallationSession(
            initialSnapshot = base.copy(
                maintenance = base.maintenance.copy(
                    routeAction = MaintenanceActionId.MANAGE_APPS,
                    managedApplications = listOf(
                        ManagedApplicationStatus(
                            componentId = "desktop",
                            packageName = AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME,
                            installed = true,
                        ),
                    ),
                ),
            ),
        )

        session.dispatch(
            InstallationSessionCommand.MaintenanceApplicationAction(
                componentId = "desktop",
                actionId = MaintenanceApplicationActionId.UNINSTALL,
            ),
        )
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceApplicationActionCompleted(
                componentId = "desktop",
                actionId = MaintenanceApplicationActionId.UNINSTALL,
                resultCode = "component_uninstalled",
            ),
        )

        assertTrue(session.currentSnapshot().maintenance.managedApplications.isEmpty())
    }

    @Test
    fun `empty maintenance inventory is a loaded state rather than an implicit loading state`() {
        val session = maintenanceSession()
        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.MANAGE_APPS))
        assertEquals(
            MaintenanceInventoryState.LOADING,
            session.currentSnapshot().maintenance.managedApplicationsState,
        )

        session.dispatchEvent(InstallationSessionEvent.MaintenanceApplicationsResolved(emptyList()))

        assertEquals(
            MaintenanceInventoryState.READY,
            session.currentSnapshot().maintenance.managedApplicationsState,
        )
        assertTrue(session.currentSnapshot().maintenance.managedApplications.isEmpty())
    }

    @Test
    fun `uninstall completion applies the refreshed car inventory before leaving the page`() {
        val base = maintenanceSession().currentSnapshot().copy(
                maintenance = maintenanceSession().currentSnapshot().maintenance.copy(
                    routeAction = MaintenanceActionId.MANAGE_APPS,
                    managedApplications = listOf(
                    ManagedApplicationStatus(
                        componentId = "desktop",
                        packageName = AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME,
                        installed = true,
                    ),
                    ManagedApplicationStatus(
                        componentId = "lyrics",
                        packageName = AuthorizationPlanFactory.LYRICS_PACKAGE_NAME,
                        installed = true,
                    ),
                ),
                managedApplicationsState = MaintenanceInventoryState.READY,
            ),
        )
        val session = InstallationSession(initialSnapshot = base)
        session.dispatch(
            InstallationSessionCommand.MaintenanceApplicationAction(
                componentId = "desktop",
                actionId = MaintenanceApplicationActionId.UNINSTALL,
            ),
        )
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceApplicationActionCompleted(
                componentId = "desktop",
                actionId = MaintenanceApplicationActionId.UNINSTALL,
                resultCode = "component_uninstalled",
                refreshedApplications = listOf(
                    ManagedApplicationStatus(
                        componentId = "lyrics",
                        packageName = AuthorizationPlanFactory.LYRICS_PACKAGE_NAME,
                        installed = true,
                    ),
                ),
            ),
        )

        assertEquals(
            listOf("lyrics"),
            session.currentSnapshot().maintenance.managedApplications.map { it.componentId },
        )
        assertEquals(
            MaintenanceInventoryState.READY,
            session.currentSnapshot().maintenance.managedApplicationsState,
        )
    }

    @Test
    fun `control plane refresh invalidates the previous installation batch`() {
        val old = fullManifest("desktop", versionCode = 1)
        val session = InstallationSession(
            initialSnapshot = InstallationSessionSnapshot(
                state = InstallationSessionState.CONNECTED,
                device = confirmedDevice.copy(
                    androidSdk = 28,
                    capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
                ),
                components = listOf(old.toComponentDescriptor()),
                artifactManifests = listOf(old),
                artifactCatalogStage = ArtifactCatalogStage.PREPARED,
            ),
        )

        session.dispatchEvent(
            InstallationSessionEvent.DistributionConfigResolved(
                configVersion = "config-2",
                keyId = "key-1",
                signatureAlgorithm = "Ed25519",
                components = listOf(old.toComponentDescriptor()),
                catalogRevision = 2L,
            ),
        )

        assertTrue(session.currentSnapshot().artifactManifests.isEmpty())
        assertEquals(ArtifactCatalogStage.CONTROL_PLANE_READY, session.currentSnapshot().artifactCatalogStage)
    }

    @Test
    fun `missing-only maintenance accepts device proof for installed component without preparation evidence`() {
        val desktop = fullManifest("desktop", versionCode = 1)
        val lyrics = fullManifest("lyrics", versionCode = 1)
        val selected = setOf("desktop", "lyrics")
        val batch = InstallationBatchPlan(
            batchId = 7L,
            flow = InstallationFlow.MAINTENANCE_INSTALL,
            strategy = InstallationStrategy.INSTALL_MISSING_ONLY,
            selectedComponentIds = selected,
            reusableComponentIds = setOf("desktop"),
            preparationComponentIds = setOf("lyrics"),
            resultComponentIds = setOf("lyrics"),
        )
        val session = InstallationSession(
            initialSnapshot = InstallationSessionSnapshot(
                state = InstallationSessionState.SELECTION_CONFIRMED,
                device = confirmedDevice.copy(
                    androidSdk = 28,
                    capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
                ),
                components = components.filter { it.id in selected },
                selectedOptionalComponentIds = setOf("lyrics"),
                // Only the missing app crosses artifact preparation; the
                // installed desktop identity remains in maintenance evidence.
                artifactManifests = listOf(lyrics),
                maintenance = MaintenanceSnapshot(
                    availableManifests = listOf(desktop, lyrics),
                    installedManifests = listOf(desktop),
                    managedApplicationsState = MaintenanceInventoryState.READY,
                    managedApplications = listOf(
                        ManagedApplicationStatus(
                            componentId = "desktop",
                            packageName = desktop.packageName,
                            installed = true,
                            versionCode = desktop.apkVersion.code,
                        ),
                    ),
                ),
                installationFlow = InstallationFlow.MAINTENANCE_INSTALL,
                installationStrategy = InstallationStrategy.INSTALL_MISSING_ONLY,
                installationBatch = batch,
                evidence = SessionEvidence(
                    installed = setOf("desktop"),
                    configured = setOf("desktop"),
                    available = setOf("desktop"),
                    installation = mapOf("desktop" to installedEvidence(desktop)),
                ),
            ),
        )

        session.dispatch(InstallationSessionCommand.BeginPipeline)
        dispatchArtifactBatchPrepared(session)
        session.dispatchEvent(
            InstallationSessionEvent.InstallationStarted(listOf("lyrics", "desktop")),
        )
        session.dispatchEvent(
            InstallationSessionEvent.InstallationBatchCompleted(successfulReceipt(session)),
        )

        val result = session.currentSnapshot()
        assertEquals(InstallationSessionState.SUCCEEDED, result.state)
        assertEquals(setOf("lyrics"), result.evidence.artifactsVerified)
        assertTrue(result.evidence.installed.containsAll(selected))
        assertTrue(result.failure == null)
    }

    @Test
    fun `invalid managed application evidence fails closed instead of completing`() {
        val session = maintenanceSession()
        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.MANAGE_APPS))
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceApplicationsResolved(
                applications = listOf(
                    ManagedApplicationStatus(
                        componentId = AuthorizationPlanFactory.DESKTOP_COMPONENT_ID,
                        packageName = "com.attacker.app",
                        installed = true,
                    ),
                ),
            ),
        )

        assertEquals(null, session.currentSnapshot().maintenance.activeAction)
        assertEquals("maintenance_applications_invalid", session.currentSnapshot().maintenance.lastAction?.reasonCode)
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceActionCompleted(
                MaintenanceActionId.MANAGE_APPS,
                "applications_checked",
            ),
        )
        assertEquals(MaintenanceActionStatus.FAILED, session.currentSnapshot().maintenance.lastAction?.status)
    }

    @Test
    fun `invalid inventory refresh leaves an explicit failed state instead of loading forever`() {
        val session = maintenanceSession()
        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.MANAGE_APPS))
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceApplicationsResolved(
                applications = listOf(
                    ManagedApplicationStatus(
                        componentId = AuthorizationPlanFactory.DESKTOP_COMPONENT_ID,
                        packageName = "com.attacker.app",
                        installed = true,
                    ),
                ),
            ),
        )

        assertEquals(MaintenanceInventoryState.FAILED, session.currentSnapshot().maintenance.managedApplicationsState)
        assertEquals("maintenance_applications_invalid", session.currentSnapshot().maintenance.managedApplicationsFailureReason)
    }

    @Test
    fun `authorization failure after inventory resolution keeps the loaded application list`() {
        val desktop = fullManifest("desktop", versionCode = 1)
        val session = maintenanceSession(manifests = listOf(desktop))
        val application = ManagedApplicationStatus(
            componentId = desktop.componentId,
            packageName = desktop.packageName,
            installed = true,
            versionCode = desktop.apkVersion.code,
        )
        val authorization = com.ninepointnine.helper.domain.device.ManagedApplicationAuthorizationStatus(
            componentId = desktop.componentId,
            packageName = desktop.packageName,
            authorized = false,
        )

        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.REPAIR_CONFIGURATION))
        session.dispatchEvent(InstallationSessionEvent.MaintenanceApplicationsResolved(listOf(application)))
        session.dispatchEvent(InstallationSessionEvent.MaintenanceAuthorizationCheckStarted(listOf(desktop.componentId)))
        session.dispatchEvent(InstallationSessionEvent.MaintenanceAuthorizationChecked(listOf(authorization)))
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceActionFailed(
                actionId = MaintenanceActionId.REPAIR_CONFIGURATION,
                reasonCode = "maintenance_manifest_selection_mismatch",
                retryable = false,
            ),
        )

        val snapshot = session.currentSnapshot().maintenance
        assertEquals(MaintenanceInventoryState.READY, snapshot.managedApplicationsState)
        assertEquals(listOf(application), snapshot.managedApplications)
        assertEquals(null, snapshot.managedApplicationsFailureReason)
        assertEquals(MaintenanceAuthorizationFlowState.FAILED, snapshot.authorization.state)
        assertEquals(listOf(authorization), snapshot.authorization.applications)
    }

    @Test
    fun `authorization inventory failure before resolution remains an inventory failure`() {
        val session = maintenanceSession()

        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.REPAIR_CONFIGURATION))
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceActionFailed(
                actionId = MaintenanceActionId.REPAIR_CONFIGURATION,
                reasonCode = "maintenance_package_inventory_failed",
                retryable = true,
            ),
        )

        val snapshot = session.currentSnapshot().maintenance
        assertEquals(MaintenanceInventoryState.FAILED, snapshot.managedApplicationsState)
        assertEquals("maintenance_package_inventory_failed", snapshot.managedApplicationsFailureReason)
        assertEquals(MaintenanceAuthorizationFlowState.FAILED, snapshot.authorization.state)
    }

    @Test
    fun `successful authorization repair completes the authorization flow`() {
        val desktop = fullManifest("desktop", versionCode = 1)
        val application = ManagedApplicationStatus(
            componentId = desktop.componentId,
            packageName = desktop.packageName,
            installed = true,
            versionCode = desktop.apkVersion.code,
        )
        val authorization = com.ninepointnine.helper.domain.device.ManagedApplicationAuthorizationStatus(
            componentId = desktop.componentId,
            packageName = desktop.packageName,
            authorized = false,
        )
        val checked = maintenanceSession(manifests = listOf(desktop)).currentSnapshot()
        val session = InstallationSession(
            initialSnapshot = checked.copy(
                maintenance = checked.maintenance.copy(
                    managedApplicationsState = MaintenanceInventoryState.READY,
                    managedApplications = listOf(application),
                    authorization = MaintenanceAuthorizationSnapshot(
                        state = MaintenanceAuthorizationFlowState.READY,
                        applications = listOf(authorization),
                    ),
                ),
            ),
        )

        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.REPAIR_CONFIGURATION))
        session.dispatchEvent(InstallationSessionEvent.MaintenanceApplicationsResolved(listOf(application)))
        session.dispatchEvent(InstallationSessionEvent.MaintenanceAuthorizationCheckStarted(listOf(desktop.componentId)))
        session.dispatchEvent(InstallationSessionEvent.MaintenanceAuthorizationChecked(listOf(authorization)))
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceActionCompleted(
                actionId = MaintenanceActionId.REPAIR_CONFIGURATION,
                resultCode = "authorization_repaired",
            ),
        )

        val snapshot = session.currentSnapshot().maintenance
        assertEquals(MaintenanceAuthorizationFlowState.COMPLETED, snapshot.authorization.state)
        assertTrue(snapshot.authorization.applications.all { it.authorized == true })
        assertTrue(snapshot.authorization.applications.all {
            it.state == MaintenanceAuthorizationState.AUTHORIZED
        })
        assertEquals(MaintenanceInventoryState.READY, snapshot.managedApplicationsState)
    }

    @Test
    fun `update candidate is retained separately from the installed manifest`() {
        val installed = fullManifest("desktop", versionCode = 1)
        val available = fullManifest("desktop", versionCode = 2)
        val session = maintenanceSession(manifests = listOf(installed))
        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.CHECK_UPDATES))
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceCatalogRefreshed(
                catalogVersion = "catalog-2",
                keyId = "key-1",
                signatureAlgorithm = "Ed25519",
                manifests = listOf(available),
            ),
        )

        assertTrue(session.currentSnapshot().artifactManifests.isEmpty())
        assertEquals(available, session.currentSnapshot().maintenance.availableManifests.single())
        assertEquals(ArtifactCatalogStage.NOT_LOADED, session.currentSnapshot().artifactCatalogStage)
        assertEquals("catalog-2", session.currentSnapshot().maintenance.availableCatalogVersion)
        assertFalse(session.currentSnapshot().maintenance.availableManifests.isEmpty())
    }

    @Test
    fun `control plane refresh retains usable manifests until a full catalog is prepared`() {
        val installed = fullManifest("desktop", versionCode = 1)
        val updated = fullManifest("desktop", versionCode = 2)
        val session = InstallationSession(
            initialSnapshot = maintenanceSession(manifests = listOf(installed), catalogRevision = 1L)
                .currentSnapshot()
                .copy(
                    artifactManifests = emptyList(),
                    artifactCatalogStage = ArtifactCatalogStage.NOT_LOADED,
                    maintenance = MaintenanceSnapshot(
                        installedManifests = listOf(installed),
                        availableComponents = listOf(installed.toComponentDescriptor()),
                    ),
                ),
        )

        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.CHECK_UPDATES))
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceCatalogRefreshed(
                catalogVersion = "catalog-2",
                keyId = "key-1",
                signatureAlgorithm = "Ed25519",
                catalogRevision = 2L,
                manifests = emptyList(),
                apps = listOf(
                    ComponentDescriptor(
                        id = "desktop",
                        displayName = "Desktop",
                        required = true,
                        status = ComponentStatus.READING,
                    ),
                ),
                controlPlaneOnly = true,
            ),
        )

        val controlPlaneSnapshot = session.currentSnapshot()
        assertTrue(controlPlaneSnapshot.maintenance.catalogControlPlaneOnly)
        assertTrue(controlPlaneSnapshot.artifactManifests.isEmpty())
        assertTrue(controlPlaneSnapshot.maintenance.availableManifests.isEmpty())
        assertEquals(listOf(installed), controlPlaneSnapshot.maintenance.installedManifests)
        assertEquals(ArtifactCatalogStage.CONTROL_PLANE_READY, controlPlaneSnapshot.artifactCatalogStage)

        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceActionCompleted(
                actionId = MaintenanceActionId.CHECK_UPDATES,
                resultCode = "updates_available",
            ),
        )
        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.CHECK_UPDATES))
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceCatalogRefreshed(
                catalogVersion = "catalog-3",
                keyId = "key-1",
                signatureAlgorithm = "Ed25519",
                catalogRevision = 3L,
                manifests = listOf(updated),
                controlPlaneOnly = false,
            ),
        )

        assertFalse(session.currentSnapshot().maintenance.catalogControlPlaneOnly)
        assertEquals(listOf(updated), session.currentSnapshot().maintenance.availableManifests)
    }

    @Test
    fun `leaving authorization route resets the next visit to a read-only check`() {
        val manifest = fullManifest("desktop", versionCode = 1)
        val session = maintenanceSession(manifests = listOf(manifest))

        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.REPAIR_CONFIGURATION))
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceApplicationsResolved(
                listOf(
                    ManagedApplicationStatus(
                        componentId = "desktop",
                        packageName = manifest.packageName,
                        installed = true,
                    ),
                ),
            ),
        )
        session.dispatchEvent(InstallationSessionEvent.MaintenanceAuthorizationCheckStarted(listOf("desktop")))
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceAuthorizationChecked(
                listOf(
                    com.ninepointnine.helper.domain.device.ManagedApplicationAuthorizationStatus(
                        componentId = "desktop",
                        packageName = manifest.packageName,
                        authorized = true,
                    ),
                ),
            ),
        )
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceActionCompleted(
                actionId = MaintenanceActionId.REPAIR_CONFIGURATION,
                resultCode = "authorization_checked",
            ),
        )
        assertEquals(MaintenanceAuthorizationFlowState.READY, session.currentSnapshot().maintenance.authorization.state)

        session.dispatch(InstallationSessionCommand.LeaveMaintenanceAction)
        assertEquals(
            MaintenanceAuthorizationFlowState.NOT_STARTED,
            session.currentSnapshot().maintenance.authorization.state,
        )
        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.REPAIR_CONFIGURATION))
        assertEquals(
            MaintenanceAuthorizationFlowState.CHECKING,
            session.currentSnapshot().maintenance.authorization.state,
        )
    }

    @Test
    fun `starting or failing an update check removes stale update rows`() {
        val session = maintenanceSession().let { source ->
            InstallationSession(
                initialSnapshot = source.currentSnapshot().copy(
                    maintenance = source.currentSnapshot().maintenance.copy(
                        updateStatuses = listOf(
                            MaintenanceUpdateStatus(
                                componentId = "desktop",
                                displayName = "Desktop",
                                versionLabel = "v2",
                                state = MaintenanceUpdateState.UPDATE_AVAILABLE,
                            ),
                        ),
                    ),
                ),
            )
        }

        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.CHECK_UPDATES))
        assertTrue(session.currentSnapshot().maintenance.updateStatuses.isEmpty())
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceActionFailed(
                actionId = MaintenanceActionId.CHECK_UPDATES,
                reasonCode = "distribution_config_transport_failed",
                retryable = true,
            ),
        )
        assertTrue(session.currentSnapshot().maintenance.updateStatuses.isEmpty())
    }

    @Test
    fun `maintenance refresh exposes a newly declared missing app without replacing installed baseline`() {
        val installed = fullManifest("desktop", versionCode = 1)
        val session = maintenanceSession(manifests = listOf(installed))
        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.CHECK_UPDATES))
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceCatalogRefreshed(
                catalogVersion = "catalog-3",
                keyId = "key-1",
                signatureAlgorithm = "Ed25519",
                manifests = listOf(installed),
                apps = listOf(
                    ComponentDescriptor("desktop", "Desktop", required = true, status = ComponentStatus.AVAILABLE),
                    ComponentDescriptor("lyrics", "Lyrics", required = false, status = ComponentStatus.DIRECTORY_MISSING),
                ),
                appFailures = mapOf("lyrics" to "lanzou_folder_missing_lyrics"),
                catalogRevision = 3L,
            ),
        )

        val snapshot = session.currentSnapshot()
        assertTrue(snapshot.artifactManifests.isEmpty())
        assertEquals(listOf(installed), snapshot.maintenance.availableManifests)
        assertEquals(ArtifactCatalogStage.NOT_LOADED, snapshot.artifactCatalogStage)
        assertEquals(ComponentStatus.DIRECTORY_MISSING, snapshot.components.single { it.id == "lyrics" }.status)
        assertEquals("catalog-3", snapshot.maintenance.availableCatalogVersion)
    }

    @Test
    fun `maintenance refresh rejects a lower catalog revision`() {
        val installed = fullManifest("desktop", versionCode = 1)
        val session = maintenanceSession(manifests = listOf(installed), catalogRevision = 5L)
        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.CHECK_UPDATES))
        session.dispatchEvent(
            InstallationSessionEvent.MaintenanceCatalogRefreshed(
                catalogVersion = "catalog-old",
                keyId = "key-1",
                signatureAlgorithm = "Ed25519",
                manifests = listOf(installed),
                catalogRevision = 4L,
            ),
        )

        assertEquals(null, session.currentSnapshot().maintenance.activeAction)
        assertEquals("maintenance_catalog_rollback", session.currentSnapshot().maintenance.lastAction?.reasonCode)
    }

    @Test
    fun `cast batch receipt commits ready while preserving reusable desktop baseline`() {
        val cast = evidenceManifest("cast", "com.ninepointnine.desktopcast", required = false)
        val fixture = maintenanceReceiptFixture(cast)
        val receipt = successfulMaintenanceReceipt(fixture)

        fixture.session.dispatchEvent(InstallationSessionEvent.InstallationBatchCompleted(receipt))

        val snapshot = fixture.session.currentSnapshot()
        assertEquals(InstallationSessionState.SUCCEEDED, snapshot.state)
        assertEquals(setOf("desktop", "cast"), snapshot.evidence.installed)
        assertEquals(setOf("desktop", "cast"), snapshot.evidence.configured)
        assertEquals(setOf("desktop", "cast"), snapshot.evidence.available)
        assertEquals(ComponentResultStatus.READY, snapshot.componentResults.single { it.componentId == "cast" }.status)
        assertEquals(ResultKind.SUCCESS, snapshot.resolveInstallationResult().kind)
    }

    @Test
    fun `unknown file manager authorization remains installed and never becomes installation failure`() {
        val fileManager = fullManifest("file-manager", versionCode = 1)
        val fixture = maintenanceReceiptFixture(fileManager)
        val plan = (AuthorizationPlanFactory.createForManifests(
            listOf(fileManager),
            requireDesktop = false,
        ) as AuthorizationPlanBuildResult.Ready).plan
        val receipt = successfulMaintenanceReceipt(fixture).copy(
            components = successfulMaintenanceReceipt(fixture).components.map { component ->
                if (component.componentId != fileManager.componentId) {
                    component
                } else {
                    component.copy(
                        authorization = AuthorizationStageReceipt(
                            status = AuthorizationStageReceiptStatus.UNKNOWN,
                            evidence = validAuthorizationEvidence(plan),
                            reasonCode = "authorization_confirmation_unavailable",
                            retryable = true,
                        ),
                        availability = AvailabilityStageReceipt(
                            status = AvailabilityStageReceiptStatus.NOT_ATTEMPTED,
                            reasonCode = "availability_not_attempted_authorization_incomplete",
                        ),
                    )
                }
            },
        )

        fixture.session.dispatchEvent(InstallationSessionEvent.InstallationBatchCompleted(receipt))

        val snapshot = fixture.session.currentSnapshot()
        val result = snapshot.componentResults.single { it.componentId == fileManager.componentId }
        assertEquals(InstallationSessionState.COMPLETED_WITH_ERRORS, snapshot.state)
        assertTrue(result.installed)
        assertEquals(ComponentResultStatus.AUTHORIZATION_INCOMPLETE, result.status)
        assertEquals(ResultKind.PARTIAL_FAILURE, snapshot.resolveInstallationResult().kind)
        assertFalse(snapshot.resolveInstallationResult().kind == ResultKind.INSTALLATION_FAILED)
    }

    @Test
    fun `unknown authorization requires a complete command receipt`() {
        val fileManager = fullManifest("file-manager", versionCode = 1)
        val fixture = maintenanceReceiptFixture(fileManager)
        val plan = (AuthorizationPlanFactory.createForManifests(
            listOf(fileManager),
            requireDesktop = false,
        ) as AuthorizationPlanBuildResult.Ready).plan
        val incompleteEvidence = validAuthorizationEvidence(plan).drop(1)
        val valid = successfulMaintenanceReceipt(fixture)
        val invalid = valid.copy(
            components = valid.components.map { component ->
                if (component.componentId != fileManager.componentId) {
                    component
                } else {
                    component.copy(
                        authorization = AuthorizationStageReceipt(
                            status = AuthorizationStageReceiptStatus.UNKNOWN,
                            evidence = incompleteEvidence,
                            reasonCode = "authorization_confirmation_unavailable",
                            retryable = true,
                        ),
                        availability = AvailabilityStageReceipt(
                            status = AvailabilityStageReceiptStatus.NOT_ATTEMPTED,
                            reasonCode = "availability_not_attempted_authorization_incomplete",
                        ),
                    )
                }
            },
        )

        fixture.session.dispatchEvent(InstallationSessionEvent.InstallationBatchCompleted(invalid))

        val snapshot = fixture.session.currentSnapshot()
        assertEquals(InstallationSessionState.FAILED, snapshot.state)
        assertEquals(
            "installation_batch_receipt_unknown_authorization_evidence_incomplete",
            snapshot.failure?.reasonCode,
        )
        assertEquals(null, snapshot.installationBatchReceipt)
    }

    @Test
    fun `preparation omission is committed in the same receipt without erasing successful evidence`() {
        val desktop = fullManifest("desktop", versionCode = 1)
        val lyrics = fullManifest("lyrics", versionCode = 1)
        val selected = setOf(desktop.componentId, lyrics.componentId)
        val plan = InstallationBatchPlan(
            batchId = 52L,
            flow = InstallationFlow.INITIAL_INSTALL,
            strategy = InstallationStrategy.INSTALL_MISSING_ONLY,
            selectedComponentIds = selected,
            reusableComponentIds = emptySet(),
            preparationComponentIds = selected,
            resultComponentIds = selected,
        )
        val session = InstallationSession(
            initialSnapshot = InstallationSessionSnapshot(
                state = InstallationSessionState.INSTALLING,
                device = confirmedDevice.copy(
                    androidSdk = 28,
                    capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
                ),
                components = listOf(desktop, lyrics).map { it.toComponentDescriptor() },
                selectedOptionalComponentIds = setOf(lyrics.componentId),
                // The preparation adapter omitted lyrics, so only desktop has
                // a trusted manifest at the receipt boundary.
                artifactManifests = listOf(desktop),
                artifactCatalogStage = ArtifactCatalogStage.PREPARED,
                installationBatch = plan,
                evidence = SessionEvidence(artifactsVerified = setOf(desktop.componentId)),
            ),
        )
        val desktopPlan = (
            AuthorizationPlanFactory.createForManifests(
                listOf(desktop),
                requireDesktop = false,
            ) as AuthorizationPlanBuildResult.Ready
            ).plan
        val receipt = InstallationBatchReceipt(
            batchId = plan.batchId,
            components = listOf(
                InstallationComponentReceipt(
                    componentId = desktop.componentId,
                    installation = InstallationStageReceipt(
                        status = InstallationStageReceiptStatus.VERIFIED,
                        evidence = installedEvidence(desktop),
                        writeConfirmed = true,
                    ),
                    authorization = AuthorizationStageReceipt(
                        status = AuthorizationStageReceiptStatus.VERIFIED,
                        evidence = validAuthorizationEvidence(desktopPlan),
                    ),
                    availability = AvailabilityStageReceipt(
                        status = AvailabilityStageReceiptStatus.VERIFIED,
                        evidence = verifiedDesktopAvailability(desktop),
                    ),
                ),
                InstallationComponentReceipt(
                    componentId = lyrics.componentId,
                    installation = InstallationStageReceipt(
                        status = InstallationStageReceiptStatus.NOT_ATTEMPTED,
                        reasonCode = "lyrics_download_failed",
                        retryable = true,
                    ),
                    authorization = AuthorizationStageReceipt(
                        status = AuthorizationStageReceiptStatus.NOT_ATTEMPTED,
                        reasonCode = "authorization_not_attempted_preparation_failed",
                    ),
                    availability = AvailabilityStageReceipt(
                        status = AvailabilityStageReceiptStatus.NOT_ATTEMPTED,
                        reasonCode = "availability_not_attempted_preparation_failed",
                    ),
                ),
            ),
        )

        session.dispatchEvent(InstallationSessionEvent.InstallationBatchCompleted(receipt))

        val snapshot = session.currentSnapshot()
        assertEquals(InstallationSessionState.COMPLETED_WITH_ERRORS, snapshot.state)
        assertEquals(
            ComponentResultStatus.NOT_INSTALLED,
            snapshot.componentResults.single { it.componentId == lyrics.componentId }.status,
        )
        assertTrue(snapshot.evidence.installed.contains(desktop.componentId))
        assertEquals(
            InstallationStageReceiptStatus.VERIFIED,
            snapshot.installationBatchReceipt?.components?.single {
                it.componentId == desktop.componentId
            }?.installation?.status,
        )
    }

    @Test
    fun `preparation omission cannot carry installation or post-install facts`() {
        val desktop = fullManifest("desktop", versionCode = 1)
        val lyrics = fullManifest("lyrics", versionCode = 1)
        val plan = InstallationBatchPlan(
            batchId = 53L,
            flow = InstallationFlow.INITIAL_INSTALL,
            strategy = InstallationStrategy.INSTALL_MISSING_ONLY,
            selectedComponentIds = setOf(desktop.componentId, lyrics.componentId),
            reusableComponentIds = emptySet(),
            preparationComponentIds = setOf(desktop.componentId, lyrics.componentId),
            resultComponentIds = setOf(desktop.componentId, lyrics.componentId),
        )
        val session = InstallationSession(
            initialSnapshot = InstallationSessionSnapshot(
                state = InstallationSessionState.INSTALLING,
                device = confirmedDevice.copy(
                    androidSdk = 28,
                    capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
                ),
                components = listOf(desktop, lyrics).map { it.toComponentDescriptor() },
                selectedOptionalComponentIds = setOf(lyrics.componentId),
                artifactManifests = listOf(desktop),
                artifactCatalogStage = ArtifactCatalogStage.PREPARED,
                installationBatch = plan,
                evidence = SessionEvidence(artifactsVerified = setOf(desktop.componentId)),
            ),
        )
        val desktopPlan = (
            AuthorizationPlanFactory.createForManifests(
                listOf(desktop),
                requireDesktop = false,
            ) as AuthorizationPlanBuildResult.Ready
            ).plan
        val invalid = InstallationBatchReceipt(
            batchId = plan.batchId,
            components = listOf(
                InstallationComponentReceipt(
                    componentId = desktop.componentId,
                    installation = InstallationStageReceipt(
                        status = InstallationStageReceiptStatus.VERIFIED,
                        evidence = installedEvidence(desktop),
                        writeConfirmed = true,
                    ),
                    authorization = AuthorizationStageReceipt(
                        status = AuthorizationStageReceiptStatus.VERIFIED,
                        evidence = validAuthorizationEvidence(desktopPlan),
                    ),
                    availability = AvailabilityStageReceipt(
                        status = AvailabilityStageReceiptStatus.VERIFIED,
                        evidence = verifiedDesktopAvailability(desktop),
                    ),
                ),
                InstallationComponentReceipt(
                    componentId = lyrics.componentId,
                    installation = InstallationStageReceipt(
                        status = InstallationStageReceiptStatus.NOT_ATTEMPTED,
                        // A missing-manifest component cannot carry any device
                        // identity or write fact into the batch receipt.
                        evidence = installedEvidence(lyrics),
                        reasonCode = "lyrics_download_failed",
                    ),
                    authorization = AuthorizationStageReceipt(
                        status = AuthorizationStageReceiptStatus.NOT_ATTEMPTED,
                        reasonCode = "authorization_not_attempted_preparation_failed",
                    ),
                    availability = AvailabilityStageReceipt(
                        status = AvailabilityStageReceiptStatus.NOT_ATTEMPTED,
                        reasonCode = "availability_not_attempted_preparation_failed",
                    ),
                ),
            ),
        )

        session.dispatchEvent(InstallationSessionEvent.InstallationBatchCompleted(invalid))

        assertEquals(InstallationSessionState.FAILED, session.currentSnapshot().state)
        assertEquals(
            "installation_batch_receipt_manifest_missing",
            session.currentSnapshot().failure?.reasonCode,
        )
        assertEquals(null, session.currentSnapshot().installationBatchReceipt)
    }

    @Test
    fun `preparation omission cannot carry an operation confirmation`() {
        val desktop = fullManifest("desktop", versionCode = 1)
        val lyrics = fullManifest("lyrics", versionCode = 1)
        val selected = setOf(desktop.componentId, lyrics.componentId)
        val plan = InstallationBatchPlan(
            batchId = 54L,
            flow = InstallationFlow.INITIAL_INSTALL,
            strategy = InstallationStrategy.INSTALL_MISSING_ONLY,
            selectedComponentIds = selected,
            reusableComponentIds = emptySet(),
            preparationComponentIds = selected,
            resultComponentIds = selected,
        )
        val notAttempted = InstallationStageReceipt(
            status = InstallationStageReceiptStatus.NOT_ATTEMPTED,
            reasonCode = "preparation_failed",
        )
        val invalid = InstallationBatchReceipt(
            batchId = plan.batchId,
            components = listOf(
                InstallationComponentReceipt(
                    componentId = desktop.componentId,
                    installation = notAttempted,
                    authorization = AuthorizationStageReceipt(
                        status = AuthorizationStageReceiptStatus.NOT_ATTEMPTED,
                        reasonCode = "authorization_not_attempted",
                    ),
                    availability = AvailabilityStageReceipt(
                        status = AvailabilityStageReceiptStatus.NOT_ATTEMPTED,
                        reasonCode = "availability_not_attempted",
                    ),
                ),
                InstallationComponentReceipt(
                    componentId = lyrics.componentId,
                    installation = notAttempted.copy(operationConfirmed = true),
                    authorization = AuthorizationStageReceipt(
                        status = AuthorizationStageReceiptStatus.NOT_ATTEMPTED,
                        reasonCode = "authorization_not_attempted",
                    ),
                    availability = AvailabilityStageReceipt(
                        status = AvailabilityStageReceiptStatus.NOT_ATTEMPTED,
                        reasonCode = "availability_not_attempted",
                    ),
                ),
            ),
        )

        assertEquals(
            "installation_batch_receipt_manifest_missing",
            invalid.validationFailure(
                plan = plan,
                manifests = mapOf(desktop.componentId to desktop),
                baseline = SessionEvidence(),
            ),
        )
    }

    @Test
    fun `post-install failure cannot erase verified package identity`() {
        val fileManager = fullManifest("file-manager", versionCode = 1)
        val fixture = maintenanceReceiptFixture(fileManager)
        val receipt = successfulMaintenanceReceipt(fixture).copy(
            components = successfulMaintenanceReceipt(fixture).components.map { component ->
                if (component.componentId != fileManager.componentId) {
                    component
                } else {
                    component.copy(
                        authorization = AuthorizationStageReceipt(
                            status = AuthorizationStageReceiptStatus.FAILED,
                            reasonCode = "authorization_runtime_permission_not_granted",
                            retryable = true,
                        ),
                        availability = AvailabilityStageReceipt(
                            status = AvailabilityStageReceiptStatus.NOT_ATTEMPTED,
                            reasonCode = "availability_not_attempted_authorization_incomplete",
                        ),
                    )
                }
            },
        )

        fixture.session.dispatchEvent(InstallationSessionEvent.InstallationBatchCompleted(receipt))

        val snapshot = fixture.session.currentSnapshot()
        val result = snapshot.componentResults.single { it.componentId == fileManager.componentId }
        assertTrue(fileManager.componentId in snapshot.evidence.installed)
        assertTrue(result.installed)
        assertEquals(ComponentResultStatus.AUTHORIZATION_INCOMPLETE, result.status)
    }

    @Test
    fun `rejected optional authorization setup is isolated from a ready desktop`() {
        val desktop = fullManifest("desktop", versionCode = 1)
        val optional = fullManifest("notes", versionCode = 1).copy(
            deviceSetup = com.ninepointnine.helper.domain.device.AuthorizationSetupDeclaration(
                profileId = "desktop-default",
            ),
        )
        val plan = InstallationBatchPlan(
            batchId = 55L,
            flow = InstallationFlow.INITIAL_INSTALL,
            strategy = InstallationStrategy.INSTALL_MISSING_ONLY,
            selectedComponentIds = setOf("desktop", "notes"),
            reusableComponentIds = emptySet(),
            preparationComponentIds = setOf("desktop", "notes"),
            resultComponentIds = setOf("desktop", "notes"),
        )
        val session = InstallationSession(
            initialSnapshot = InstallationSessionSnapshot(
                state = InstallationSessionState.INSTALLING,
                device = confirmedDevice.copy(
                    androidSdk = 28,
                    capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
                ),
                components = listOf(desktop, optional).map { it.toComponentDescriptor() },
                selectedOptionalComponentIds = setOf("notes"),
                artifactManifests = listOf(desktop, optional),
                artifactCatalogStage = ArtifactCatalogStage.PREPARED,
                installationBatch = plan,
                evidence = SessionEvidence(artifactsVerified = setOf("desktop", "notes")),
            ),
        )
        val desktopPlan = (
            AuthorizationPlanFactory.createForManifests(listOf(desktop), requireDesktop = false)
                as AuthorizationPlanBuildResult.Ready
            ).plan
        val receipt = InstallationBatchReceipt(
            batchId = plan.batchId,
            components = listOf(
                InstallationComponentReceipt(
                    componentId = "desktop",
                    installation = InstallationStageReceipt(
                        status = InstallationStageReceiptStatus.VERIFIED,
                        evidence = installedEvidence(desktop),
                        writeConfirmed = true,
                    ),
                    authorization = AuthorizationStageReceipt(
                        status = AuthorizationStageReceiptStatus.VERIFIED,
                        evidence = validAuthorizationEvidence(desktopPlan),
                    ),
                    availability = AvailabilityStageReceipt(
                        status = AvailabilityStageReceiptStatus.VERIFIED,
                        evidence = verifiedDesktopAvailability(desktop),
                    ),
                ),
                InstallationComponentReceipt(
                    componentId = "notes",
                    installation = InstallationStageReceipt(
                        status = InstallationStageReceiptStatus.VERIFIED,
                        evidence = installedEvidence(optional),
                        writeConfirmed = true,
                    ),
                    authorization = AuthorizationStageReceipt(
                        status = AuthorizationStageReceiptStatus.FAILED,
                        reasonCode = "authorization_component_unapproved",
                    ),
                    availability = AvailabilityStageReceipt(
                        status = AvailabilityStageReceiptStatus.NOT_ATTEMPTED,
                        reasonCode = "availability_not_attempted_authorization_incomplete",
                    ),
                ),
            ),
        )

        session.dispatchEvent(InstallationSessionEvent.InstallationBatchCompleted(receipt))

        val snapshot = session.currentSnapshot()
        assertEquals(InstallationSessionState.COMPLETED_WITH_ERRORS, snapshot.state)
        assertEquals(ComponentResultStatus.READY, snapshot.componentResults.single { it.componentId == "desktop" }.status)
        assertEquals(ComponentResultStatus.AUTHORIZATION_INCOMPLETE, snapshot.componentResults.single { it.componentId == "notes" }.status)
    }

    @Test
    fun `receipt rejects mismatched identity before committing terminal state`() {
        val cast = evidenceManifest("cast", "com.ninepointnine.desktopcast", required = false)
        val fixture = maintenanceReceiptFixture(cast)
        val valid = successfulMaintenanceReceipt(fixture)
        val invalid = valid.copy(
            components = valid.components.map { component ->
                if (component.componentId != cast.componentId) {
                    component
                } else {
                    component.copy(
                        installation = component.installation.copy(
                            evidence = checkNotNull(component.installation.evidence).copy(
                                packageName = "com.example.untrusted",
                            ),
                        ),
                    )
                }
            },
        )

        fixture.session.dispatchEvent(InstallationSessionEvent.InstallationBatchCompleted(invalid))

        val snapshot = fixture.session.currentSnapshot()
        assertEquals(InstallationSessionState.FAILED, snapshot.state)
        assertEquals("installation_batch_receipt_installation_package_mismatch", snapshot.failure?.reasonCode)
        assertEquals(null, snapshot.installationBatchReceipt)
    }

    @Test
    fun `late progress event cannot change a committed receipt`() {
        val cast = evidenceManifest("cast", "com.ninepointnine.desktopcast", required = false)
        val fixture = maintenanceReceiptFixture(cast)
        fixture.session.dispatchEvent(
            InstallationSessionEvent.InstallationBatchCompleted(successfulMaintenanceReceipt(fixture)),
        )
        val committed = fixture.session.currentSnapshot()

        fixture.session.dispatchEvent(
            event = InstallationSessionEvent.ComponentProgressUpdated(
                componentId = cast.componentId,
                phase = InstallPhase.CONFIGURE,
                status = ComponentProgressStatus.FAILED,
                fraction = 0f,
                indeterminate = false,
            ),
            sequence = committed.lastEventSequence + 1L,
        )

        val afterLateEvent = fixture.session.currentSnapshot()
        assertEquals(committed.revision, afterLateEvent.revision)
        assertEquals(InstallationSessionState.SUCCEEDED, afterLateEvent.state)
        assertEquals(committed.installationBatchReceipt, afterLateEvent.installationBatchReceipt)
        assertTrue(afterLateEvent.failedComponentIds.isEmpty())
    }

    @Test
    fun `receipt from another batch is rejected`() {
        val cast = evidenceManifest("cast", "com.ninepointnine.desktopcast", required = false)
        val fixture = maintenanceReceiptFixture(cast)
        val foreign = successfulMaintenanceReceipt(fixture).copy(batchId = fixture.plan.batchId + 1L)

        fixture.session.dispatchEvent(InstallationSessionEvent.InstallationBatchCompleted(foreign))

        assertEquals(InstallationSessionState.FAILED, fixture.session.currentSnapshot().state)
        assertEquals(
            "installation_batch_receipt_batch_mismatch",
            fixture.session.currentSnapshot().failure?.reasonCode,
        )
    }

    private fun connectedSession(
        includeOptional: Boolean,
        catalog: List<ComponentDescriptor> = components,
    ): InstallationSession {
        val session = InstallationSession(catalog)
        session.dispatch(InstallationSessionCommand.StartDiscovery)
        session.dispatchEvent(InstallationSessionEvent.DeviceDiscovered(confirmedDevice))
        session.dispatch(InstallationSessionCommand.SelectDevice(confirmedDevice.id))
        val connecting = session.currentSnapshot()
        session.dispatchEvent(
            InstallationSessionEvent.DeviceConnectionConfirmed(
                confirmedDevice.copy(
                    androidSdk = 28,
                    capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
                ),
            ),
            sessionId = connecting.sessionId,
            sequence = connecting.lastEventSequence + 1L,
        )
        if (includeOptional) {
            session.dispatch(
                InstallationSessionCommand.ToggleOptionalComponent("lyrics", selected = true),
            )
            session.dispatch(
                InstallationSessionCommand.ToggleOptionalComponent("file-manager", selected = true),
            )
        }
        return session
    }

    private fun batchReceiptSession(
        manifests: List<ArtifactManifest>,
        descriptors: List<ComponentDescriptor> = manifests.map { it.toComponentDescriptor() },
    ): InstallationSession {
        val selectedIds = manifests.mapTo(linkedSetOf()) { it.componentId }
        val plan = InstallationBatchPlan(
            batchId = 41L,
            flow = InstallationFlow.INITIAL_INSTALL,
            strategy = InstallationStrategy.INSTALL_MISSING_ONLY,
            selectedComponentIds = selectedIds,
            reusableComponentIds = emptySet(),
            preparationComponentIds = selectedIds,
            resultComponentIds = selectedIds,
        )
        return InstallationSession(
            initialSnapshot = InstallationSessionSnapshot(
                state = InstallationSessionState.INSTALLING,
                device = confirmedDevice.copy(
                    androidSdk = 28,
                    capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
                ),
                components = descriptors,
                selectedOptionalComponentIds = selectedIds - AuthorizationPlanFactory.DESKTOP_COMPONENT_ID,
                artifactManifests = manifests,
                artifactCatalogStage = ArtifactCatalogStage.PREPARED,
                installationFlow = InstallationFlow.INITIAL_INSTALL,
                installationStrategy = InstallationStrategy.INSTALL_MISSING_ONLY,
                installationBatch = plan,
                evidence = SessionEvidence(artifactsVerified = selectedIds),
            ),
        )
    }

    private data class MaintenanceReceiptFixture(
        val session: InstallationSession,
        val desktop: ArtifactManifest,
        val optional: ArtifactManifest,
        val plan: InstallationBatchPlan,
    )

    private fun maintenanceReceiptFixture(optional: ArtifactManifest): MaintenanceReceiptFixture {
        val desktop = fullManifest("desktop", versionCode = 1)
        val plan = InstallationBatchPlan(
            batchId = 41L,
            flow = InstallationFlow.MAINTENANCE_INSTALL,
            strategy = InstallationStrategy.INSTALL_MISSING_ONLY,
            selectedComponentIds = setOf(desktop.componentId, optional.componentId),
            reusableComponentIds = setOf(desktop.componentId),
            preparationComponentIds = setOf(optional.componentId),
            resultComponentIds = setOf(optional.componentId),
        )
        val session = InstallationSession(
            initialSnapshot = InstallationSessionSnapshot(
                state = InstallationSessionState.INSTALLING,
                device = confirmedDevice.copy(
                    androidSdk = 28,
                    capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
                ),
                components = listOf(desktop, optional).map { it.toComponentDescriptor() },
                selectedOptionalComponentIds = setOf(optional.componentId),
                artifactManifests = listOf(desktop, optional),
                artifactCatalogStage = ArtifactCatalogStage.PREPARED,
                installationFlow = InstallationFlow.MAINTENANCE_INSTALL,
                installationStrategy = InstallationStrategy.INSTALL_MISSING_ONLY,
                installationBatch = plan,
                evidence = SessionEvidence(
                    artifactsVerified = setOf(optional.componentId),
                    installed = setOf(desktop.componentId),
                    configured = setOf(desktop.componentId),
                    available = setOf(desktop.componentId),
                ),
                maintenance = MaintenanceSnapshot(
                    managedApplicationsState = MaintenanceInventoryState.READY,
                    managedApplications = listOf(
                        ManagedApplicationStatus(
                            componentId = desktop.componentId,
                            packageName = desktop.packageName,
                            installed = true,
                            versionCode = desktop.apkVersion.code,
                        ),
                    ),
                    installedManifests = listOf(desktop),
                ),
            ),
        )
        return MaintenanceReceiptFixture(session, desktop, optional, plan)
    }

    private fun successfulMaintenanceReceipt(fixture: MaintenanceReceiptFixture): InstallationBatchReceipt {
        return successfulReceipt(fixture.session)
    }

    private fun successfulReceipt(session: InstallationSession): InstallationBatchReceipt {
        val snapshot = session.currentSnapshot()
        val batch = checkNotNull(snapshot.installationBatch)
        val manifests = (
            snapshot.maintenance.availableManifests +
                snapshot.maintenance.installedManifests +
                snapshot.artifactManifests
            ).associateBy { it.componentId }
        val selectedManifests = batch.selectedComponentIds.map { componentId ->
            checkNotNull(manifests[componentId])
        }
        val authorizationPlan = (
            AuthorizationPlanFactory.createForManifests(selectedManifests, requireDesktop = false)
                as AuthorizationPlanBuildResult.Ready
            ).plan
        val authorizationEvidence = validAuthorizationEvidence(authorizationPlan)
        val orderedIds = snapshot.components.map { it.id }.filter { it in batch.selectedComponentIds } +
            (batch.selectedComponentIds - snapshot.components.map { it.id }.toSet())
        return InstallationBatchReceipt(
            batchId = batch.batchId,
            components = orderedIds.map { componentId ->
                val manifest = checkNotNull(manifests[componentId])
                if (componentId in batch.reusableComponentIds) {
                    InstallationComponentReceipt(
                        componentId = componentId,
                        installation = InstallationStageReceipt(
                            status = InstallationStageReceiptStatus.VERIFIED,
                            evidence = installedEvidence(manifest),
                        ),
                        authorization = AuthorizationStageReceipt(AuthorizationStageReceiptStatus.PRESERVED),
                        availability = AvailabilityStageReceipt(AvailabilityStageReceiptStatus.PRESERVED),
                    )
                } else {
                    val componentAuthorizationEvidence = authorizationEvidence.filter {
                        it.componentId == componentId
                    }
                    InstallationComponentReceipt(
                        componentId = componentId,
                        installation = InstallationStageReceipt(
                            status = InstallationStageReceiptStatus.VERIFIED,
                            evidence = installedEvidence(manifest),
                            writeConfirmed = true,
                        ),
                        authorization = if (componentAuthorizationEvidence.isEmpty()) {
                            AuthorizationStageReceipt(AuthorizationStageReceiptStatus.NOT_REQUIRED)
                        } else {
                            AuthorizationStageReceipt(
                                status = AuthorizationStageReceiptStatus.VERIFIED,
                                evidence = componentAuthorizationEvidence,
                            )
                        },
                        availability = if (componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID) {
                            AvailabilityStageReceipt(
                                status = AvailabilityStageReceiptStatus.VERIFIED,
                                evidence = verifiedDesktopAvailability(manifest),
                            )
                        } else {
                            AvailabilityStageReceipt(AvailabilityStageReceiptStatus.NOT_REQUIRED)
                        },
                    )
                }
            },
        )
    }

    private fun InstallationBatchReceipt.mapComponent(
        componentId: String,
        transform: (InstallationComponentReceipt) -> InstallationComponentReceipt,
    ): InstallationBatchReceipt = copy(
        components = components.map { component ->
            if (component.componentId == componentId) transform(component) else component
        },
    )

    private fun installedEvidence(manifest: ArtifactManifest): InstalledArtifactEvidence =
        InstalledArtifactEvidence(
            componentId = manifest.componentId,
            packageName = manifest.packageName,
            version = manifest.apkVersion,
            apkSizeBytes = manifest.apkSizeBytes,
            apkSha256 = manifest.apkSha256,
            certificateSha256 = manifest.certificateSha256,
        )

    private fun verifiedDesktopAvailability(manifest: ArtifactManifest): DeviceAvailabilityEvidence =
        DeviceAvailabilityEvidence(
            componentId = manifest.componentId,
            packageName = manifest.packageName,
            version = manifest.apkVersion,
            installedArchiveVerified = true,
            launchAttempted = true,
            launcherResolved = true,
            processRunning = true,
            requiredServiceBound = true,
        )

    private fun maintenanceSession(
        connected: Boolean = true,
        manifests: List<ArtifactManifest> = emptyList(),
        catalogRevision: Long = 0L,
    ): InstallationSession {
        val device = confirmedDevice.copy(
            connectionStatus = if (connected) DeviceConnectionStatus.CONFIRMED else DeviceConnectionStatus.DISCONNECTED,
            androidSdk = 28,
            capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
        )
        return InstallationSession(
            initialSnapshot = InstallationSessionSnapshot(
                state = InstallationSessionState.MAINTENANCE,
                device = device,
                components = if (manifests.isEmpty()) components else manifests.map { it.toComponentDescriptor() },
                selectedOptionalComponentIds = emptySet(),
                artifactManifests = manifests,
                artifactCatalogStage = if (manifests.isEmpty()) ArtifactCatalogStage.NOT_LOADED else ArtifactCatalogStage.PREPARED,
                catalogRevision = catalogRevision,
                evidence = SessionEvidence(installed = manifests.map { it.componentId }.toSet()),
            ),
        )
    }

    private fun fullManifest(componentId: String, versionCode: Long): ArtifactManifest = ArtifactManifest(
        schemaVersion = 1,
        componentId = componentId,
        displayName = componentId,
        required = componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID,
        version = ArtifactVersion("1.$versionCode", versionCode),
        compatibility = CompatibilityRange(minAndroidSdk = 26, maxAndroidSdk = 30),
        archiveFileName = "$componentId.zip",
        archiveSizeBytes = 100L,
        archiveSha256 = "11".repeat(32),
        apkEntryName = "$componentId.apk",
        apkSizeBytes = 50L,
        apkSha256 = "22".repeat(32),
        packageName = when (componentId) {
            AuthorizationPlanFactory.DESKTOP_COMPONENT_ID -> AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME
            AuthorizationPlanFactory.LYRICS_COMPONENT_ID -> AuthorizationPlanFactory.LYRICS_PACKAGE_NAME
            else -> AuthorizationPlanFactory.FILE_MANAGER_PACKAGE_NAME
        },
        apkVersion = ArtifactVersion("1.$versionCode", versionCode),
        certificateSha256 = "33".repeat(32),
        sources = listOf(
            ArtifactSource(ArtifactSourceKind.LANZOU_SHARE, "https://wwatl.lanzouw.com/i$componentId"),
            ArtifactSource(ArtifactSourceKind.R2, "https://assets.r2.dev/$componentId.zip"),
            ArtifactSource(ArtifactSourceKind.GITHUB_RELEASES, "https://github.com/example/repo/releases/download/v1/$componentId.zip"),
        ),
    )

    private fun resolveTrustedCatalog(session: InstallationSession) {
        if (session.currentSnapshot().artifactCatalogStage == ArtifactCatalogStage.PREPARED) return
        val current = session.currentSnapshot()
        session.dispatchEvent(
            InstallationSessionEvent.DistributionConfigResolved(
                configVersion = "fixture-catalog",
                keyId = "fixture-key",
                signatureAlgorithm = "Ed25519",
                components = current.components,
                catalogRevision = 1L,
            ),
        )
    }

    private fun preparationManifests(session: InstallationSession): List<ArtifactManifest> {
        val snapshot = session.currentSnapshot()
        val preparationIds = checkNotNull(snapshot.installationBatch).preparationComponentIds
        return snapshot.artifactManifests
            .filter { it.componentId in preparationIds }
            .ifEmpty { preparationIds.map { fullManifest(it, versionCode = 1) } }
    }

    private fun dispatchArtifactBatchPrepared(session: InstallationSession) {
        val manifests = preparationManifests(session)
        val batch = checkNotNull(session.currentSnapshot().installationBatch)
        session.dispatchEvent(
            InstallationSessionEvent.ArtifactBatchPrepared(
                batchId = batch.batchId,
                manifests = manifests,
                sourceSelections = manifests.map {
                    SourceSelectionEvidence(it.componentId, ArtifactSourceKind.LANZOU_SHARE)
                },
                archives = manifests.map {
                    ArchiveDownloadEvidence(it.componentId, it.archiveSizeBytes, it.archiveSha256)
                },
                archiveVerifications = manifests.map {
                    ArchiveVerificationEvidence(it.componentId, it.archiveSizeBytes, it.archiveSha256)
                },
                extractions = manifests.map {
                    ApkExtractionEvidence(it.componentId, it.apkEntryName, it.apkSizeBytes, it.apkSha256)
                },
                verifications = manifests.map { manifest ->
                    ArtifactVerification(
                        componentId = manifest.componentId,
                        sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                        archiveSizeBytes = manifest.archiveSizeBytes,
                        archiveSha256 = manifest.archiveSha256,
                        apkSizeBytes = manifest.apkSizeBytes,
                        apkSha256 = manifest.apkSha256,
                        packageName = manifest.packageName,
                        apkVersion = manifest.apkVersion,
                        certificateSha256 = manifest.certificateSha256,
                        archiveDeleted = true,
                    )
                },
            ),
        )
    }

    private fun artifactBatchFailureEvent(
        session: InstallationSession,
        componentId: String,
        reasonCode: String,
    ): InstallationSessionEvent.ArtifactBatchPrepared =
        InstallationSessionEvent.ArtifactBatchPrepared(
            batchId = checkNotNull(session.currentSnapshot().installationBatch).batchId,
            failures = listOf(
                ArtifactFailure(
                    phase = ArtifactFailurePhase.ARCHIVE_VERIFICATION,
                    componentId = componentId,
                    sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                    reasonCode = reasonCode,
                    retryable = false,
                ),
            ),
        )

    private fun startToInstalling(session: InstallationSession) {
        resolveTrustedCatalog(session)
        session.dispatch(InstallationSessionCommand.StartInstallation)
        session.dispatch(InstallationSessionCommand.BeginPipeline)
        dispatchArtifactBatchPrepared(session)
        session.dispatchEvent(InstallationSessionEvent.InstallationStarted())
    }

    private fun complete(session: InstallationSession) {
        startToInstalling(session)
        session.dispatchEvent(
            InstallationSessionEvent.InstallationBatchCompleted(successfulReceipt(session)),
        )
    }

    private fun validAuthorizationEvidence(
        plan: com.ninepointnine.helper.domain.device.AuthorizationPlan,
    ): List<AuthorizationActionEvidence> = plan.actions.map { action ->
        when (action) {
            is AuthorizationAction.EnsureAppOpAllowed -> AuthorizationActionEvidence(
                componentId = action.componentId,
                actionId = action.id,
                before = AuthorizationValueState.DEFAULT,
                writeApplied = true,
                after = AuthorizationValueState.ALLOWED,
            )

            is AuthorizationAction.EnsureRuntimePermissionGranted -> AuthorizationActionEvidence(
                componentId = action.componentId,
                actionId = action.id,
                before = AuthorizationValueState.DENIED,
                writeApplied = true,
                after = AuthorizationValueState.GRANTED,
            )

            is AuthorizationAction.EnsureSecureSettingEnabled -> AuthorizationActionEvidence(
                componentId = action.componentId,
                actionId = action.id,
                before = AuthorizationValueState.DISABLED,
                writeApplied = true,
                after = AuthorizationValueState.ENABLED,
            )

            is AuthorizationAction.AppendSecureComponent -> AuthorizationActionEvidence(
                componentId = action.componentId,
                actionId = action.id,
                before = AuthorizationValueState.COMPONENT_ABSENT,
                writeApplied = true,
                after = AuthorizationValueState.COMPONENT_PRESENT,
                preservedEntryCount = 0,
            )
        }
    }

    private fun evidenceManifest(
        componentId: String,
        packageName: String,
        required: Boolean,
    ): ArtifactManifest = ArtifactManifest(
        schemaVersion = 1,
        componentId = componentId,
        displayName = componentId,
        required = required,
        version = ArtifactVersion("1.0.0", 1),
        compatibility = CompatibilityRange(minAndroidSdk = 26, maxAndroidSdk = 30),
        archiveFileName = "$componentId.zip",
        archiveSizeBytes = 100L,
        archiveSha256 = "11".repeat(32),
        apkEntryName = "$componentId.apk",
        apkSizeBytes = 50L,
        apkSha256 = "22".repeat(32),
        packageName = packageName,
        apkVersion = ArtifactVersion("1.0.0", 1),
        certificateSha256 = "33".repeat(32),
        sources = listOf(
            ArtifactSource(ArtifactSourceKind.LANZOU_SHARE, "https://wwatl.lanzouw.com/i$componentId"),
        ),
    )

    private companion object {
        val components = listOf(
            ComponentDescriptor(
                id = "lyrics",
                displayName = "Lyrics",
                required = false,
                versionLabel = "1.14",
                sizeLabel = "18 MB",
                compatibilityLabel = "compatible",
            ),
            ComponentDescriptor(
                id = "desktop",
                displayName = "Desktop",
                required = true,
                versionLabel = "0.1",
                sizeLabel = "12 MB",
                compatibilityLabel = "compatible",
            ),
            ComponentDescriptor(
                id = "file-manager",
                displayName = "File Manager",
                required = false,
                versionLabel = "1.6.1",
                sizeLabel = "9 MB",
                compatibilityLabel = "optional",
            ),
        )

        val confirmedDevice = DeviceSummary(
            id = "icar-03",
            displayName = "iCAR 03",
            connectionStatus = DeviceConnectionStatus.CONFIRMED,
        )
    }
}
