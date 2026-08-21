package com.tcrrry.helper.domain.session

import com.tcrrry.helper.domain.artifact.ArtifactManifest
import com.tcrrry.helper.domain.artifact.ArtifactSource
import com.tcrrry.helper.domain.artifact.ArtifactSourceKind
import com.tcrrry.helper.domain.artifact.ArtifactVersion
import com.tcrrry.helper.domain.artifact.CompatibilityRange
import com.tcrrry.helper.domain.artifact.toComponentDescriptor
import com.tcrrry.helper.domain.device.AuthorizationAction
import com.tcrrry.helper.domain.device.AuthorizationActionEvidence
import com.tcrrry.helper.domain.device.AuthorizationPlanBuildResult
import com.tcrrry.helper.domain.device.AuthorizationPlanFactory
import com.tcrrry.helper.domain.device.AuthorizationValueState
import com.tcrrry.helper.domain.device.DeviceAvailabilityEvidence
import com.tcrrry.helper.domain.device.DeviceCapability
import com.tcrrry.helper.domain.device.InstalledArtifactEvidence
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
    fun `required components remain selected and incomplete optional metadata blocks start`() {
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

        assertEquals(InstallationSessionState.FAILED, session.currentSnapshot().state)
        assertEquals("component_metadata_incomplete", session.currentSnapshot().failure?.reasonCode)
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
        plan: com.tcrrry.helper.domain.device.AuthorizationPlan,
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
