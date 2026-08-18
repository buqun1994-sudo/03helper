package com.tcrrry.helper.domain.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallationSessionTest {
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
    fun `required components remain selected and incomplete optional metadata blocks start`() {
        val session = connectedSession(
            includeOptional = false,
            catalog = components.map { component ->
                if (component.id == "file-manager") component.copy(sizeLabel = null) else component
            },
        )
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
        val expected = setOf("lyrics", "desktop")
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

    private fun connectedSession(
        includeOptional: Boolean,
        catalog: List<ComponentDescriptor> = components,
    ): InstallationSession {
        val session = InstallationSession(catalog)
        session.dispatch(InstallationSessionCommand.StartDiscovery)
        session.dispatchEvent(InstallationSessionEvent.DeviceDiscovered(confirmedDevice))
        session.dispatch(InstallationSessionCommand.SelectDevice(confirmedDevice.id))
        if (includeOptional) {
            session.dispatch(
                InstallationSessionCommand.ToggleOptionalComponent("file-manager", selected = true),
            )
        }
        return session
    }

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

    private companion object {
        val components = listOf(
            ComponentDescriptor(
                id = "lyrics",
                displayName = "Lyrics",
                required = true,
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
