package com.ninepointnine.helper.domain.session

import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactSource
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.CompatibilityRange
import com.ninepointnine.helper.domain.artifact.toComponentDescriptor
import com.ninepointnine.helper.domain.device.AuthorizationAction
import com.ninepointnine.helper.domain.device.AuthorizationActionEvidence
import com.ninepointnine.helper.domain.device.AuthorizationPlanBuildResult
import com.ninepointnine.helper.domain.device.AuthorizationPlanFactory
import com.ninepointnine.helper.domain.device.AuthorizationValueState
import com.ninepointnine.helper.domain.device.DeviceAvailabilityEvidence
import com.ninepointnine.helper.domain.device.DeviceCapability
import com.ninepointnine.helper.domain.device.InstalledArtifactEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallationSessionTest {
    @Test
    fun `optional installed components do not require launch or service evidence`() {
        val manifests = listOf(
            evidenceManifest("desktop", AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME, required = true),
            evidenceManifest("lyrics", AuthorizationPlanFactory.LYRICS_PACKAGE_NAME, required = false),
            evidenceManifest("file-manager", AuthorizationPlanFactory.FILE_MANAGER_PACKAGE_NAME, required = false),
        )
        val selected = manifests.map { it.componentId }.toSet()
        val plan = (AuthorizationPlanFactory.createForManifests(manifests) as AuthorizationPlanBuildResult.Ready).plan
        val installation = manifests.associate { manifest ->
            manifest.componentId to InstalledArtifactEvidence(
                componentId = manifest.componentId,
                packageName = manifest.packageName,
                version = manifest.apkVersion,
                apkSizeBytes = manifest.apkSizeBytes,
                apkSha256 = manifest.apkSha256,
                certificateSha256 = manifest.certificateSha256,
            )
        }
        val availability = manifests.map { manifest ->
            val desktop = manifest.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID
            DeviceAvailabilityEvidence(
                componentId = manifest.componentId,
                packageName = manifest.packageName,
                version = manifest.apkVersion,
                installedArchiveVerified = true,
                launchAttempted = desktop,
                launcherResolved = desktop,
                processRunning = desktop,
                requiredServiceBound = if (desktop) true else null,
            )
        }
        val device = DeviceSummary(
            id = "evidence-device",
            displayName = "Evidence device",
            connectionStatus = DeviceConnectionStatus.CONFIRMED,
            androidSdk = 28,
            capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
        )
        val session = InstallationSession(
            initialSnapshot = InstallationSessionSnapshot(
                state = InstallationSessionState.VERIFYING_DEVICE,
                device = device,
                components = manifests.map { it.toComponentDescriptor() },
                selectedOptionalComponentIds = setOf("lyrics", "file-manager"),
                artifactManifests = manifests,
                evidence = SessionEvidence(
                    artifactsVerified = selected,
                    installed = selected,
                    configured = selected,
                    available = selected,
                    installation = installation,
                    authorizationActions = validAuthorizationEvidence(plan),
                    availability = availability.associateBy { it.componentId },
                ),
            ),
        )

        session.dispatchEvent(
            InstallationSessionEvent.DeviceVerified(
                checks = selected.map { ComponentCheck(it, passed = true) },
                evidence = availability,
            ),
        )

        assertEquals(InstallationSessionState.SUCCEEDED, session.currentSnapshot().state)
        assertTrue(session.currentSnapshot().componentResults.all { it.installed && it.configured && it.available })
    }

    @Test
    fun `normal path follows every guarded stage and requires all evidence`() {
        val session = connectedSession(includeOptional = true)

        assertEquals(InstallationSessionState.CONNECTED, session.currentSnapshot().state)
        session.dispatch(InstallationSessionCommand.StartInstallation)
        assertEquals(InstallationSessionState.SELECTION_CONFIRMED, session.currentSnapshot().state)
        session.dispatch(InstallationSessionCommand.BeginPipeline)
        assertEquals(InstallationSessionState.RESOLVING_SOURCE, session.currentSnapshot().state)

        session.dispatchEvent(InstallationSessionEvent.SourceResolved("fixture"))
        assertEquals(InstallationSessionState.DOWNLOADING_ARCHIVE, session.currentSnapshot().state)
        session.dispatchEvent(InstallationSessionEvent.ArchiveDownloaded(1024L, "archive-sha"))
        assertEquals(InstallationSessionState.VERIFYING_ARCHIVE, session.currentSnapshot().state)
        session.dispatchEvent(InstallationSessionEvent.ArchiveVerified(true))
        assertEquals(InstallationSessionState.EXTRACTING_APK, session.currentSnapshot().state)
        session.dispatchEvent(InstallationSessionEvent.ApkExtracted("component.apk", 512L, "apk-sha"))
        assertEquals(InstallationSessionState.VERIFYING_ARTIFACTS, session.currentSnapshot().state)
        session.dispatchEvent(InstallationSessionEvent.ArtifactsVerified(checks(session)))
        assertEquals(InstallationSessionState.VERIFYING_ARTIFACTS, session.currentSnapshot().state)
        session.dispatchEvent(InstallationSessionEvent.InstallationStarted())
        assertEquals(InstallationSessionState.INSTALLING, session.currentSnapshot().state)
        session.dispatchEvent(InstallationSessionEvent.InstallationCompleted(checks(session)))
        assertEquals(InstallationSessionState.AUTHORIZING, session.currentSnapshot().state)
        session.dispatchEvent(InstallationSessionEvent.AuthorizationCompleted(checks(session)))
        assertEquals(InstallationSessionState.VERIFYING_DEVICE, session.currentSnapshot().state)
        session.dispatchEvent(InstallationSessionEvent.DeviceVerified(checks(session)))

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
        session.dispatchEvent(InstallationSessionEvent.SourceResolved("fixture"))
        val oldSessionId = session.currentSnapshot().sessionId

        session.dispatch(InstallationSessionCommand.CancelInstallation)
        val paused = session.currentSnapshot()
        assertEquals(InstallationSessionState.PAUSED, paused.state)
        assertEquals(InstallationSessionState.DOWNLOADING_ARCHIVE, paused.checkpoint?.state)
        assertNotEquals(oldSessionId, paused.sessionId)

        session.dispatch(
            InstallationSessionCommand.AdapterEvent(
                event = InstallationSessionEvent.ArchiveDownloaded(1024L, "old"),
                sessionId = oldSessionId,
                sequence = 99L,
            ),
        )
        assertEquals(paused, session.currentSnapshot())

        session.dispatch(InstallationSessionCommand.ContinueInstallation)
        assertEquals(InstallationSessionState.DOWNLOADING_ARCHIVE, session.currentSnapshot().state)
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
        session.dispatchEvent(InstallationSessionEvent.SourceResolved("fixture"))
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
        assertEquals(InstallationSessionState.DOWNLOADING_ARCHIVE, session.currentSnapshot().state)
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
    fun `out of order and failed verification never advance the pipeline`() {
        val session = connectedSession(includeOptional = false)
        session.dispatch(InstallationSessionCommand.StartInstallation)
        session.dispatch(InstallationSessionCommand.BeginPipeline)
        session.dispatchEvent(InstallationSessionEvent.ArchiveDownloaded(1024L, "too-early"))

        assertEquals(InstallationSessionState.FAILED, session.currentSnapshot().state)
        assertEquals("archive_event_out_of_order", session.currentSnapshot().failure?.reasonCode)

        val archiveFailure = connectedSession(includeOptional = false)
        archiveFailure.dispatch(InstallationSessionCommand.StartInstallation)
        archiveFailure.dispatch(InstallationSessionCommand.BeginPipeline)
        archiveFailure.dispatchEvent(InstallationSessionEvent.SourceResolved("fixture"))
        archiveFailure.dispatchEvent(InstallationSessionEvent.ArchiveDownloaded(1024L, "archive-sha"))
        archiveFailure.dispatchEvent(InstallationSessionEvent.ArchiveVerified(false))
        assertEquals(InstallationSessionState.FAILED, archiveFailure.currentSnapshot().state)
        assertEquals("archive_verification_failed", archiveFailure.currentSnapshot().failure?.reasonCode)
    }

    @Test
    fun `malformed extracted entry fails closed`() {
        val session = connectedSession(includeOptional = false)
        session.dispatch(InstallationSessionCommand.StartInstallation)
        session.dispatch(InstallationSessionCommand.BeginPipeline)
        session.dispatchEvent(InstallationSessionEvent.SourceResolved("fixture"))
        session.dispatchEvent(InstallationSessionEvent.ArchiveDownloaded(1024L, "archive-sha"))
        session.dispatchEvent(InstallationSessionEvent.ArchiveVerified(true))
        session.dispatchEvent(InstallationSessionEvent.ApkExtracted("../component.apk", 512L, "apk-sha"))

        assertEquals(InstallationSessionState.FAILED, session.currentSnapshot().state)
        assertEquals("apk_identity_missing", session.currentSnapshot().failure?.reasonCode)
    }

    @Test
    fun `success evidence is rejected when any category is missing`() {
        val expected = setOf("desktop")
        val session = InstallationSession(
            componentCatalog = components,
            initialSnapshot = InstallationSessionSnapshot(
                state = InstallationSessionState.VERIFYING_DEVICE,
                device = confirmedDevice,
                components = components,
                progress = SessionProgress(completedCount = 0, totalCount = 2, indeterminate = true),
                evidence = SessionEvidence(
                    installed = expected,
                    configured = expected,
                ),
            ),
        )
        session.dispatchEvent(
            InstallationSessionEvent.DeviceVerified(
                checks(session),
            ),
        )
        assertEquals(InstallationSessionState.FAILED, session.currentSnapshot().state)
        assertEquals("success_evidence_incomplete", session.currentSnapshot().failure?.reasonCode)
    }

    @Test
    fun `one failed component still reaches a partial result after the remaining app completes`() {
        val session = connectedSession(includeOptional = false)
        session.dispatch(InstallationSessionCommand.ToggleOptionalComponent("lyrics", selected = true))
        startToDeviceVerification(session)

        session.dispatchEvent(
            InstallationSessionEvent.ComponentFailed(
                componentId = "lyrics",
                phase = InstallPhase.VERIFY,
                reasonCode = "availability_check_failed",
            ),
        )
        session.dispatchEvent(
            InstallationSessionEvent.DeviceVerified(
                checks = listOf(ComponentCheck("desktop", passed = true)),
            ),
        )

        val snapshot = session.currentSnapshot()
        assertEquals(InstallationSessionState.COMPLETED_WITH_ERRORS, snapshot.state)
        assertEquals(setOf("lyrics"), snapshot.failedComponentIds)
        assertTrue(snapshot.componentResults.first { it.componentId == "desktop" }.available)
        assertFalse(snapshot.componentResults.first { it.componentId == "lyrics" }.available)
    }

    @Test
    fun `failed optional component history does not invalidate the remaining success proof`() {
        val desktop = evidenceManifest("desktop", AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME, required = true)
        val lyrics = evidenceManifest("lyrics", AuthorizationPlanFactory.LYRICS_PACKAGE_NAME, required = false)
        val manifests = listOf(desktop, lyrics)
        val selected = manifests.map { it.componentId }.toSet()
        val plan = (AuthorizationPlanFactory.createForManifests(manifests) as AuthorizationPlanBuildResult.Ready).plan
        val installation = manifests.associate { manifest ->
            manifest.componentId to InstalledArtifactEvidence(
                componentId = manifest.componentId,
                packageName = manifest.packageName,
                version = manifest.apkVersion,
                apkSizeBytes = manifest.apkSizeBytes,
                apkSha256 = manifest.apkSha256,
                certificateSha256 = manifest.certificateSha256,
            )
        }
        val desktopAvailability = DeviceAvailabilityEvidence(
            componentId = desktop.componentId,
            packageName = desktop.packageName,
            version = desktop.apkVersion,
            installedArchiveVerified = true,
            launchAttempted = true,
            launcherResolved = true,
            processRunning = true,
            requiredServiceBound = true,
        )
        val session = InstallationSession(
            initialSnapshot = InstallationSessionSnapshot(
                state = InstallationSessionState.VERIFYING_DEVICE,
                device = confirmedDevice,
                components = manifests.map { it.toComponentDescriptor() },
                selectedOptionalComponentIds = setOf("lyrics"),
                failedComponentIds = setOf("lyrics"),
                artifactManifests = manifests,
                evidence = SessionEvidence(
                    artifactsVerified = selected,
                    installed = selected,
                    configured = setOf("desktop"),
                    available = setOf("desktop"),
                    installation = installation,
                    authorizationActions = validAuthorizationEvidence(plan)
                        .filter { it.componentId == "desktop" },
                    availability = mapOf("desktop" to desktopAvailability),
                ),
            ),
        )

        session.dispatchEvent(
            InstallationSessionEvent.DeviceVerified(
                checks = listOf(ComponentCheck("desktop", passed = true)),
                evidence = listOf(desktopAvailability),
            ),
        )

        val snapshot = session.currentSnapshot()
        assertEquals(InstallationSessionState.COMPLETED_WITH_ERRORS, snapshot.state)
        assertEquals(setOf("lyrics"), snapshot.failedComponentIds)
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
                event = InstallationSessionEvent.SourceResolved("new"),
                sessionId = sessionId,
                sequence = 10L,
                eventId = "new-source",
            ),
        )
        val newer = session.currentSnapshot()
        session.dispatch(
            InstallationSessionCommand.AdapterEvent(
                event = InstallationSessionEvent.SourceResolved("old"),
                sessionId = sessionId,
                sequence = 9L,
                eventId = "old-source",
            ),
        )
        session.dispatch(
            InstallationSessionCommand.AdapterEvent(
                event = InstallationSessionEvent.ArchiveDownloaded(1024L, "archive-sha"),
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
        assertEquals(InstallationSessionState.MAINTENANCE, session.currentSnapshot().state)
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
    fun `successful uninstall removes the application from the maintenance inventory`() {
        val base = maintenanceSession().currentSnapshot()
        val session = InstallationSession(
            initialSnapshot = base.copy(
                maintenance = base.maintenance.copy(
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
    fun `selected catalog preparation never shrinks the retained full component configuration`() {
        val desktop = fullManifest("desktop", versionCode = 2)
        val session = InstallationSession(
            initialSnapshot = InstallationSessionSnapshot(
                state = InstallationSessionState.SELECTION_CONFIRMED,
                device = confirmedDevice.copy(
                    androidSdk = 28,
                    capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
                ),
                components = components,
                selectedOptionalComponentIds = setOf("lyrics", "file-manager"),
            ),
        )

        session.dispatchEvent(
            InstallationSessionEvent.SelectedCatalogResolved(
                catalogVersion = "catalog-2",
                keyId = "key-1",
                signatureAlgorithm = "Ed25519",
                manifests = listOf(desktop),
                apps = emptyList(),
            ),
        )

        assertEquals(
            components.map { it.id }.toSet(),
            session.currentSnapshot().components.map { it.id }.toSet(),
        )
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

        assertEquals(installed, session.currentSnapshot().artifactManifests.single())
        assertEquals(available, session.currentSnapshot().maintenance.availableManifests.single())
        assertEquals("catalog-2", session.currentSnapshot().maintenance.availableCatalogVersion)
        assertFalse(session.currentSnapshot().maintenance.availableManifests.isEmpty())
    }

    @Test
    fun `control plane refresh retains usable manifests until a full catalog is prepared`() {
        val installed = fullManifest("desktop", versionCode = 1)
        val updated = fullManifest("desktop", versionCode = 2)
        val session = maintenanceSession(manifests = listOf(installed), catalogRevision = 1L)

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
        assertEquals(listOf(installed), controlPlaneSnapshot.artifactManifests)
        assertEquals(listOf(installed), controlPlaneSnapshot.maintenance.availableManifests)

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
        assertEquals(listOf(installed), snapshot.artifactManifests)
        assertEquals(listOf(installed), snapshot.maintenance.availableManifests)
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
            InstallationSessionEvent.DeviceConnectionConfirmed(confirmedDevice),
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

    private fun startToDeviceVerification(session: InstallationSession) {
        session.dispatch(InstallationSessionCommand.StartInstallation)
        session.dispatch(InstallationSessionCommand.BeginPipeline)
        session.dispatchEvent(InstallationSessionEvent.SourceResolved("fixture"))
        session.dispatchEvent(InstallationSessionEvent.ArchiveDownloaded(1024L, "archive-sha"))
        session.dispatchEvent(InstallationSessionEvent.ArchiveVerified(true))
        session.dispatchEvent(InstallationSessionEvent.ApkExtracted("component.apk", 512L, "apk-sha"))
        session.dispatchEvent(InstallationSessionEvent.ArtifactsVerified(checks(session)))
        session.dispatchEvent(InstallationSessionEvent.InstallationStarted())
        session.dispatchEvent(InstallationSessionEvent.InstallationCompleted(checks(session)))
        session.dispatchEvent(InstallationSessionEvent.AuthorizationCompleted(checks(session)))
    }

    private fun complete(session: InstallationSession) {
        startToDeviceVerification(session)
        session.dispatchEvent(InstallationSessionEvent.DeviceVerified(checks(session)))
    }

    private fun checks(session: InstallationSession): List<ComponentCheck> =
        session.currentSnapshot().components
            .filter { it.required || it.id in session.currentSnapshot().selectedOptionalComponentIds }
            .map { ComponentCheck(it.id, passed = true) }

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
