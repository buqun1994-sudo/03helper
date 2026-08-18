package com.tcrrry.helper.domain.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The only mutable owner of the installation lifecycle.
 *
 * External adapters inject structured events through [InstallationSessionCommand.AdapterEvent].
 * They never mutate the snapshot or Compose state directly.
 */
class InstallationSession(
    componentCatalog: List<ComponentDescriptor> = emptyList(),
    initialSnapshot: InstallationSessionSnapshot = InstallationSessionSnapshot(InstallationSessionState.IDLE),
) : AutoCloseable {
    constructor(initialSnapshot: InstallationSessionSnapshot) : this(
        componentCatalog = initialSnapshot.components,
        initialSnapshot = initialSnapshot,
    )

    private val lock = Any()
    private val catalog = componentCatalog.toList()
    private val _snapshot = MutableStateFlow(initialSnapshot)
    private val acceptedEventIds = linkedSetOf<String>()
    private var acceptedEventSequence = initialSnapshot.lastEventSequence
    private var closed = false

    val snapshots: StateFlow<InstallationSessionSnapshot> = _snapshot.asStateFlow()
    val snapshot: StateFlow<InstallationSessionSnapshot> = snapshots

    fun currentSnapshot(): InstallationSessionSnapshot = _snapshot.value

    /** Applies one command synchronously and returns the resulting immutable snapshot. */
    fun dispatch(command: InstallationSessionCommand): InstallationSessionSnapshot = synchronized(lock) {
        if (closed) {
            return@synchronized _snapshot.value
        }
        when (command) {
            InstallationSessionCommand.StartDiscovery -> startDiscovery()
            InstallationSessionCommand.StopDiscovery -> stopDiscovery()
            is InstallationSessionCommand.SelectDevice -> selectDevice(command.deviceId)
            is InstallationSessionCommand.ToggleOptionalComponent -> toggleOptional(command)
            InstallationSessionCommand.StartInstallation,
            InstallationSessionCommand.ConfirmSelection,
            -> confirmSelection()

            InstallationSessionCommand.BeginPipeline -> beginPipeline()
            InstallationSessionCommand.CancelInstallation -> cancelInstallation()
            InstallationSessionCommand.ContinueInstallation,
            InstallationSessionCommand.ResumeInstallation,
            InstallationSessionCommand.RetryInstallation,
            InstallationSessionCommand.ReconfigureInstallation,
            -> resumeFromCheckpoint()

            InstallationSessionCommand.Reconnect -> startDiscovery()
            InstallationSessionCommand.EnterMaintenance -> enterMaintenance()
            is InstallationSessionCommand.MaintenanceAction -> handleMaintenanceAction(command)
            is InstallationSessionCommand.AdapterEvent -> applyAdapterEvent(command)
        }
        _snapshot.value
    }

    fun send(command: InstallationSessionCommand): InstallationSessionSnapshot = dispatch(command)

    fun handle(command: InstallationSessionCommand): InstallationSessionSnapshot = dispatch(command)

    /** Convenience entry point for fake ports and deterministic adapter tests. */
    fun dispatchEvent(
        event: InstallationSessionEvent,
        sessionId: Long? = null,
        sequence: Long? = null,
        eventId: String? = null,
    ): InstallationSessionSnapshot = synchronized(lock) {
        val current = _snapshot.value
        val resolvedSessionId = sessionId ?: current.sessionId
        val resolvedSequence = sequence ?: (current.lastEventSequence + 1L)
        dispatch(
            InstallationSessionCommand.AdapterEvent(
                event = event,
                sessionId = resolvedSessionId,
                sequence = resolvedSequence,
                eventId = eventId ?: "$resolvedSessionId:$resolvedSequence",
            ),
        )
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            acceptedEventIds.clear()
        }
    }

    private fun startDiscovery() {
        val current = _snapshot.value
        if (current.state !in DISCOVERY_ENTRY_STATES) {
            return
        }
        startNewGeneration(
            current.copy(
                state = InstallationSessionState.DISCOVERING,
                device = null,
                discoveredDevices = emptyList(),
                selectedOptionalComponentIds = emptySet(),
                currentComponentName = null,
                progress = null,
                failure = null,
                checkpoint = null,
                evidence = SessionEvidence(),
                componentResults = emptyList(),
                components = current.components.ifEmpty { catalog },
            ),
        )
    }

    private fun stopDiscovery() {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.DISCOVERING) {
            return
        }
        startNewGeneration(
            current.copy(
                state = InstallationSessionState.IDLE,
                device = null,
                discoveredDevices = emptyList(),
                selectedOptionalComponentIds = emptySet(),
                currentComponentName = null,
                progress = null,
                failure = null,
                checkpoint = null,
                evidence = SessionEvidence(),
                componentResults = emptyList(),
            ),
        )
    }

    private fun selectDevice(deviceId: String) {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.DISCOVERING) {
            return
        }
        val device = current.discoveredDevices.firstOrNull { it.id == deviceId }
        when {
            device == null -> fail(
                category = FailureCategory.CONNECTION,
                reasonCode = "device_not_discovered",
            )

            device.connectionStatus != DeviceConnectionStatus.CONFIRMED -> fail(
                category = FailureCategory.CONNECTION,
                reasonCode = "device_not_confirmed",
            )

            else -> {
                val components = current.components.ifEmpty { catalog }
                publish(
                    withCheckpoint(
                        current.copy(
                            state = InstallationSessionState.CONNECTED,
                            device = device,
                            components = components,
                            failure = null,
                            checkpoint = null,
                        ),
                    ),
                )
            }
        }
    }

    private fun toggleOptional(command: InstallationSessionCommand.ToggleOptionalComponent) {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.CONNECTED) {
            return
        }
        val component = current.components.firstOrNull { it.id == command.componentId }
        when {
            component == null -> fail(
                category = FailureCategory.UNKNOWN,
                reasonCode = "unknown_component",
            )

            component.required -> {
                // Required components are an invariant, not a UI-only checkbox rule.
                return
            }

            command.selected -> publish(
                current.copy(
                    selectedOptionalComponentIds = current.selectedOptionalComponentIds + component.id,
                ),
            )

            else -> publish(
                current.copy(
                    selectedOptionalComponentIds = current.selectedOptionalComponentIds - component.id,
                ),
            )
        }
    }

    private fun confirmSelection() {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.CONNECTED) {
            return
        }
        val validationFailure = validateSelection(current)
        if (validationFailure != null) {
            fail(
                category = FailureCategory.VERIFICATION,
                reasonCode = validationFailure,
            )
            return
        }

        val selected = selectedComponents(current)
        val next = current.copy(
            state = InstallationSessionState.SELECTION_CONFIRMED,
            currentComponentName = selected.firstOrNull()?.displayName,
            progress = SessionProgress(
                completedCount = 0,
                totalCount = selected.size,
                indeterminate = true,
            ),
            failure = null,
            evidence = SessionEvidence(),
            componentResults = buildComponentResults(SessionEvidence(), current),
        )
        publish(withCheckpoint(next))
    }

    private fun beginPipeline() {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.SELECTION_CONFIRMED) {
            return
        }
        val validationFailure = validateSelection(current)
        if (validationFailure != null) {
            fail(
                category = FailureCategory.VERIFICATION,
                reasonCode = validationFailure,
            )
            return
        }
        publish(
            withCheckpoint(
                current.copy(
                    state = InstallationSessionState.RESOLVING_SOURCE,
                    progress = current.progress?.copy(indeterminate = true, fraction = null),
                ),
            ),
        )
    }

    private fun cancelInstallation() {
        val current = _snapshot.value
        if (current.state !in ACTIVE_INSTALL_STATES) {
            return
        }
        val checkpoint = checkpointOf(current)
        startNewGeneration(
            current.copy(
                state = InstallationSessionState.PAUSED,
                failure = SessionFailure(
                    category = FailureCategory.CONNECTION,
                    retryable = true,
                    reasonCode = "cancelled",
                ),
                checkpoint = checkpoint,
            ),
        )
    }

    private fun resumeFromCheckpoint() {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.PAUSED && current.state != InstallationSessionState.FAILED) {
            return
        }
        val checkpoint = current.checkpoint
        if (checkpoint == null || checkpoint.state !in RESUMABLE_STATES) {
            fail(
                category = FailureCategory.UNKNOWN,
                reasonCode = "checkpoint_missing",
            )
            return
        }
        val validationFailure = validateSelection(current)
        if (validationFailure != null) {
            fail(
                category = FailureCategory.VERIFICATION,
                reasonCode = validationFailure,
            )
            return
        }
        val restored = current.copy(
            state = checkpoint.state,
            selectedOptionalComponentIds = checkpoint.selectedOptionalComponentIds,
            currentComponentName = checkpoint.currentComponentName,
            progress = checkpoint.progress,
            failure = null,
            evidence = checkpoint.evidence,
            componentResults = buildComponentResults(checkpoint.evidence, current),
            checkpoint = checkpoint,
        )
        startNewGeneration(restored)
    }

    private fun enterMaintenance() {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.SUCCEEDED) {
            return
        }
        if (!hasCompleteSuccessEvidence(current)) {
            fail(
                category = FailureCategory.VERIFICATION,
                reasonCode = "success_evidence_incomplete",
            )
            return
        }
        publish(current.copy(state = InstallationSessionState.MAINTENANCE, checkpoint = null))
    }

    @Suppress("UNUSED_PARAMETER")
    private fun handleMaintenanceAction(command: InstallationSessionCommand.MaintenanceAction) {
        if (_snapshot.value.state != InstallationSessionState.MAINTENANCE) {
            return
        }
        // F1 only owns the session boundary. F4 will attach the whitelisted action port.
    }

    private fun applyAdapterEvent(command: InstallationSessionCommand.AdapterEvent) {
        val current = _snapshot.value
        if (command.sessionId != current.sessionId) {
            return
        }
        if (command.sequence <= acceptedEventSequence || !acceptedEventIds.add(command.eventId)) {
            return
        }
        acceptedEventSequence = command.sequence

        when (val event = command.event) {
            is InstallationSessionEvent.DeviceDiscovered -> handleDeviceDiscovered(event.device)
            is InstallationSessionEvent.DiscoverySnapshot -> handleDiscoverySnapshot(event.devices)
            is InstallationSessionEvent.SourceResolved -> handleSourceResolved(event.sourceId)
            is InstallationSessionEvent.ArchiveDownloaded -> handleArchiveDownloaded(event)
            is InstallationSessionEvent.ArchiveVerified -> handleArchiveVerified(event.verified)
            is InstallationSessionEvent.ApkExtracted -> handleApkExtracted(event)
            is InstallationSessionEvent.ArtifactsVerified -> handleArtifactsVerified(event.checks)
            is InstallationSessionEvent.InstallationCompleted -> handleInstallationCompleted(event.checks)
            is InstallationSessionEvent.AuthorizationCompleted -> handleAuthorizationCompleted(event.checks)
            is InstallationSessionEvent.DeviceVerified -> handleDeviceVerified(event.checks)
            is InstallationSessionEvent.DeviceDisconnected -> handleDeviceDisconnected(event)
            is InstallationSessionEvent.DeviceReconnected -> handleDeviceReconnected(event.device)
            is InstallationSessionEvent.RecoverableError -> pause(
                category = event.category,
                componentName = event.componentName,
                reasonCode = event.reasonCode,
            )

            is InstallationSessionEvent.FatalError -> fail(
                category = event.category,
                componentName = event.componentName,
                retryable = false,
                reasonCode = event.reasonCode,
            )

            InstallationSessionEvent.Unknown -> fail(
                category = FailureCategory.UNKNOWN,
                reasonCode = "unknown_event",
            )
        }
    }

    private fun handleDeviceDiscovered(device: DeviceSummary) {
        if (_snapshot.value.state != InstallationSessionState.DISCOVERING) {
            fail(FailureCategory.UNKNOWN, reasonCode = "device_event_out_of_order")
            return
        }
        if (device.id.isBlank()) {
            fail(FailureCategory.CONNECTION, reasonCode = "device_id_missing")
            return
        }
        val current = _snapshot.value
        val merged = mergeDevices(current.discoveredDevices, listOf(device))
        publish(current.copy(discoveredDevices = merged), acceptedEventSequence)
    }

    private fun handleDiscoverySnapshot(devices: List<DeviceSummary>) {
        if (_snapshot.value.state != InstallationSessionState.DISCOVERING) {
            fail(FailureCategory.UNKNOWN, reasonCode = "discovery_event_out_of_order")
            return
        }
        if (devices.any { it.id.isBlank() }) {
            fail(FailureCategory.CONNECTION, reasonCode = "device_id_missing")
            return
        }
        val current = _snapshot.value
        publish(current.copy(discoveredDevices = mergeDevices(current.discoveredDevices, devices)), acceptedEventSequence)
    }

    private fun handleSourceResolved(sourceId: String) {
        if (!requireState(InstallationSessionState.RESOLVING_SOURCE, "source_event_out_of_order")) return
        if (sourceId.isBlank()) {
            fail(FailureCategory.DOWNLOAD, reasonCode = "source_missing")
            return
        }
        transition(
            state = InstallationSessionState.DOWNLOADING_ARCHIVE,
            progress = progress(0.0f, indeterminate = true),
        )
    }

    private fun handleArchiveDownloaded(event: InstallationSessionEvent.ArchiveDownloaded) {
        if (!requireState(InstallationSessionState.DOWNLOADING_ARCHIVE, "archive_event_out_of_order")) return
        if (event.sizeBytes <= 0L || event.sha256.isBlank()) {
            fail(FailureCategory.DOWNLOAD, reasonCode = "archive_identity_missing")
            return
        }
        transition(
            state = InstallationSessionState.VERIFYING_ARCHIVE,
            progress = progress(0.25f, indeterminate = true),
        )
    }

    private fun handleArchiveVerified(verified: Boolean) {
        if (!requireState(InstallationSessionState.VERIFYING_ARCHIVE, "archive_verification_out_of_order")) return
        if (!verified) {
            fail(FailureCategory.ARCHIVE, reasonCode = "archive_verification_failed")
            return
        }
        transition(
            state = InstallationSessionState.EXTRACTING_APK,
            progress = progress(0.4f, indeterminate = true),
        )
    }

    private fun handleApkExtracted(event: InstallationSessionEvent.ApkExtracted) {
        if (!requireState(InstallationSessionState.EXTRACTING_APK, "apk_event_out_of_order")) return
        if (
            event.entryName.isBlank() ||
            event.entryName.contains('/') ||
            event.entryName.contains('\\') ||
            event.entryName.contains("..") ||
            event.sizeBytes <= 0L ||
            event.sha256.isBlank()
        ) {
            fail(FailureCategory.ARCHIVE, reasonCode = "apk_identity_missing")
            return
        }
        transition(
            state = InstallationSessionState.VERIFYING_ARTIFACTS,
            progress = progress(0.55f, indeterminate = true),
        )
    }

    private fun handleArtifactsVerified(checks: List<ComponentCheck>) {
        if (!requireState(InstallationSessionState.VERIFYING_ARTIFACTS, "artifact_event_out_of_order")) return
        val verified = validateChecks(checks)
        if (verified == null) {
            fail(FailureCategory.VERIFICATION, reasonCode = "artifact_verification_failed")
            return
        }
        val current = _snapshot.value
        val evidence = current.evidence.copy(artifactsVerified = verified)
        transition(
            state = InstallationSessionState.INSTALLING,
            evidence = evidence,
            componentResults = buildComponentResults(evidence, current),
            progress = progress(0.65f, indeterminate = true),
        )
    }

    private fun handleInstallationCompleted(checks: List<ComponentCheck>) {
        if (!requireState(InstallationSessionState.INSTALLING, "installation_event_out_of_order")) return
        val installed = validateChecks(checks)
        if (installed == null) {
            fail(FailureCategory.INSTALLATION, reasonCode = "installation_evidence_missing")
            return
        }
        val current = _snapshot.value
        val evidence = current.evidence.copy(installed = installed)
        transition(
            state = InstallationSessionState.AUTHORIZING,
            evidence = evidence,
            componentResults = buildComponentResults(evidence, current),
            progress = progress(0.78f, indeterminate = true),
        )
    }

    private fun handleAuthorizationCompleted(checks: List<ComponentCheck>) {
        if (!requireState(InstallationSessionState.AUTHORIZING, "authorization_event_out_of_order")) return
        val configured = validateChecks(checks)
        if (configured == null) {
            fail(FailureCategory.CONFIGURATION, reasonCode = "configuration_evidence_missing")
            return
        }
        val current = _snapshot.value
        val evidence = current.evidence.copy(configured = configured)
        transition(
            state = InstallationSessionState.VERIFYING_DEVICE,
            evidence = evidence,
            componentResults = buildComponentResults(evidence, current),
            progress = progress(0.9f, indeterminate = true),
        )
    }

    private fun handleDeviceVerified(checks: List<ComponentCheck>) {
        if (!requireState(InstallationSessionState.VERIFYING_DEVICE, "device_verification_out_of_order")) return
        val available = validateChecks(checks)
        if (available == null) {
            fail(FailureCategory.VERIFICATION, reasonCode = "availability_evidence_missing")
            return
        }
        val current = _snapshot.value
        val evidence = current.evidence.copy(available = available)
        val results = buildComponentResults(evidence, current)
        if (!hasCompleteSuccessEvidence(evidence, current)) {
            fail(FailureCategory.VERIFICATION, reasonCode = "success_evidence_incomplete")
            return
        }
        transition(
            state = InstallationSessionState.SUCCEEDED,
            evidence = evidence,
            componentResults = results,
            progress = progress(1.0f, indeterminate = false, completedCount = selectedComponents(current).size),
            checkpoint = null,
        )
    }

    private fun handleDeviceDisconnected(event: InstallationSessionEvent.DeviceDisconnected) {
        val current = _snapshot.value
        if (current.state == InstallationSessionState.MAINTENANCE) {
            val device = current.device
            if (device == null || event.deviceId == null || event.deviceId == device.id) {
                publish(
                    current.copy(
                        device = device?.copy(connectionStatus = DeviceConnectionStatus.DISCONNECTED),
                    ),
                    acceptedEventSequence,
                )
            } else {
                fail(FailureCategory.CONNECTION, reasonCode = "unknown_device_disconnected")
            }
            return
        }
        if (current.state == InstallationSessionState.DISCOVERING ||
            current.state == InstallationSessionState.CONNECTED ||
            current.state in ACTIVE_INSTALL_STATES
        ) {
            pause(
                category = FailureCategory.CONNECTION,
                reasonCode = event.reasonCode,
                deviceOverride = current.device?.copy(connectionStatus = DeviceConnectionStatus.DISCONNECTED),
            )
            return
        }
        fail(FailureCategory.CONNECTION, reasonCode = event.reasonCode)
    }

    private fun handleDeviceReconnected(device: DeviceSummary) {
        val current = _snapshot.value
        if ((current.state != InstallationSessionState.PAUSED && current.state != InstallationSessionState.FAILED) ||
            current.checkpoint == null ||
            device.connectionStatus != DeviceConnectionStatus.CONFIRMED
        ) {
            fail(FailureCategory.CONNECTION, reasonCode = "reconnect_event_invalid")
            return
        }
        val expectedDeviceId = current.checkpoint.deviceId ?: current.device?.id
        if (expectedDeviceId != null && expectedDeviceId != device.id) {
            fail(FailureCategory.CONNECTION, reasonCode = "reconnect_device_mismatch")
            return
        }
        val checkpoint = current.checkpoint
        startNewGeneration(
            current.copy(
                state = checkpoint.state,
                device = device,
                failure = null,
                checkpoint = checkpoint,
            ),
        )
    }

    private fun requireState(expected: InstallationSessionState, reasonCode: String): Boolean {
        if (_snapshot.value.state == expected) return true
        fail(FailureCategory.UNKNOWN, reasonCode = reasonCode)
        return false
    }

    private fun transition(
        state: InstallationSessionState,
        progress: SessionProgress? = _snapshot.value.progress,
        evidence: SessionEvidence = _snapshot.value.evidence,
        componentResults: List<ComponentResult> = _snapshot.value.componentResults,
        checkpoint: SessionCheckpoint? = _snapshot.value.checkpoint,
    ) {
        val current = _snapshot.value
        val next = current.copy(
            state = state,
            progress = progress,
            evidence = evidence,
            componentResults = componentResults,
            checkpoint = checkpoint,
            failure = null,
        )
        publish(if (state == InstallationSessionState.SUCCEEDED) next else withCheckpoint(next), acceptedEventSequence)
    }

    private fun progress(
        fraction: Float,
        indeterminate: Boolean,
        completedCount: Int = _snapshot.value.progress?.completedCount ?: 0,
    ): SessionProgress = SessionProgress(
        completedCount = completedCount,
        totalCount = selectedComponents(_snapshot.value).size,
        fraction = fraction.coerceIn(0f, 1f),
        indeterminate = indeterminate,
    )

    private fun pause(
        category: FailureCategory,
        componentName: String? = null,
        reasonCode: String,
        deviceOverride: DeviceSummary? = _snapshot.value.device,
    ) {
        val current = _snapshot.value
        val checkpoint = checkpointOf(current)
        startNewGeneration(
            current.copy(
                state = InstallationSessionState.PAUSED,
                device = deviceOverride,
                failure = SessionFailure(
                    category = category,
                    componentName = componentName,
                    retryable = true,
                    reasonCode = reasonCode,
                ),
                checkpoint = checkpoint,
            ),
        )
    }

    private fun fail(
        category: FailureCategory,
        componentName: String? = null,
        retryable: Boolean = true,
        reasonCode: String,
    ) {
        val current = _snapshot.value
        val checkpoint = if (current.state in CHECKPOINT_STATES) checkpointOf(current) else current.checkpoint
        publish(
            current.copy(
                state = InstallationSessionState.FAILED,
                failure = SessionFailure(
                    category = category,
                    componentName = componentName,
                    retryable = retryable,
                    reasonCode = reasonCode,
                ),
                checkpoint = checkpoint,
            ),
            acceptedEventSequence,
        )
    }

    private fun validateSelection(snapshot: InstallationSessionSnapshot): String? {
        val components = snapshot.components
        if (snapshot.device?.connectionStatus != DeviceConnectionStatus.CONFIRMED) {
            return "device_not_confirmed"
        }
        if (components.isEmpty()) return "component_catalog_missing"
        if (components.any { it.id.isBlank() || it.displayName.isBlank() }) return "component_identity_missing"
        if (components.map { it.id }.toSet().size != components.size) return "component_identity_duplicate"

        val lyrics = components.firstOrNull { it.id == "lyrics" }
        val desktop = components.firstOrNull { it.id == "desktop" }
        if (lyrics == null || desktop == null) return "required_components_missing"
        if (!lyrics.required || !desktop.required) return "required_component_unlocked"

        val optionalIds = components.filterNot { it.required }.map { it.id }.toSet()
        if (!snapshot.selectedOptionalComponentIds.all { it in optionalIds }) return "unknown_component"

        val selected = selectedComponents(snapshot)
        if (selected.isEmpty()) return "required_components_missing"
        if (selected.any { component ->
                component.versionLabel.isNullOrBlank() ||
                    component.sizeLabel.isNullOrBlank() ||
                    component.compatibilityLabel.isNullOrBlank()
            }
        ) {
            return "component_metadata_incomplete"
        }
        return null
    }

    private fun validateChecks(checks: List<ComponentCheck>): Set<String>? {
        val expected = selectedComponents(_snapshot.value).map { it.id }.toSet()
        if (expected.isEmpty() || checks.size != checks.map { it.componentId }.toSet().size) return null
        val normalized = mutableMapOf<String, Boolean>()
        checks.forEach { check ->
            val component = _snapshot.value.components.firstOrNull {
                it.id == check.componentId || it.displayName == check.componentId
            } ?: return null
            normalized[component.id] = check.passed
        }
        if (normalized.keys != expected || normalized.values.any { !it }) return null
        return expected
    }

    private fun selectedComponents(snapshot: InstallationSessionSnapshot): List<ComponentDescriptor> =
        snapshot.components.filter { it.required || it.id in snapshot.selectedOptionalComponentIds }

    private fun buildComponentResults(
        evidence: SessionEvidence,
        snapshot: InstallationSessionSnapshot,
    ): List<ComponentResult> = selectedComponents(snapshot).map { component ->
        ComponentResult(
            componentName = component.displayName,
            installed = component.id in evidence.installed,
            configured = component.id in evidence.configured,
            available = component.id in evidence.available,
            componentId = component.id,
        )
    }

    private fun hasCompleteSuccessEvidence(snapshot: InstallationSessionSnapshot): Boolean =
        hasCompleteSuccessEvidence(snapshot.evidence, snapshot)

    private fun hasCompleteSuccessEvidence(
        evidence: SessionEvidence,
        snapshot: InstallationSessionSnapshot,
    ): Boolean {
        val expected = selectedComponents(snapshot).map { it.id }.toSet()
        return expected.isNotEmpty() &&
            expected.all { it in evidence.artifactsVerified } &&
            expected.all { it in evidence.installed } &&
            expected.all { it in evidence.configured } &&
            expected.all { it in evidence.available }
    }

    private fun checkpointOf(snapshot: InstallationSessionSnapshot): SessionCheckpoint = SessionCheckpoint(
        sessionId = snapshot.sessionId,
        state = snapshot.state,
        deviceId = snapshot.device?.id,
        selectedOptionalComponentIds = snapshot.selectedOptionalComponentIds,
        currentComponentName = snapshot.currentComponentName,
        progress = snapshot.progress,
        evidence = snapshot.evidence,
    )

    private fun withCheckpoint(snapshot: InstallationSessionSnapshot): InstallationSessionSnapshot =
        if (snapshot.state in CHECKPOINT_STATES) {
            snapshot.copy(checkpoint = checkpointOf(snapshot))
        } else {
            snapshot
        }

    private fun startNewGeneration(snapshot: InstallationSessionSnapshot) {
        acceptedEventIds.clear()
        acceptedEventSequence = 0L
        publish(
            snapshot.copy(
                sessionId = nextSessionId(_snapshot.value.sessionId),
                lastEventSequence = 0L,
            ),
            sequence = 0L,
        )
    }

    private fun publish(
        next: InstallationSessionSnapshot,
        sequence: Long = _snapshot.value.lastEventSequence,
    ) {
        val current = _snapshot.value
        val candidate = next.copy(
            revision = current.revision,
            lastEventSequence = sequence,
        )
        if (candidate == current) return
        _snapshot.value = candidate.copy(revision = current.revision + 1L)
    }

    private fun mergeDevices(
        existing: List<DeviceSummary>,
        incoming: List<DeviceSummary>,
    ): List<DeviceSummary> {
        val merged = linkedMapOf<String, DeviceSummary>()
        (existing + incoming).forEach { device ->
            val previous = merged[device.id]
            merged[device.id] = if (
                previous?.connectionStatus == DeviceConnectionStatus.CONFIRMED &&
                device.connectionStatus != DeviceConnectionStatus.CONFIRMED
            ) {
                previous.copy(
                    displayName = device.displayName.ifBlank { previous.displayName },
                    lastConfirmedLabel = previous.lastConfirmedLabel,
                )
            } else {
                device
            }
        }
        return merged.values.toList()
    }

    private fun nextSessionId(current: Long): Long = if (current == Long.MAX_VALUE) 1L else current + 1L

    private companion object {
        val DISCOVERY_ENTRY_STATES = setOf(
            InstallationSessionState.IDLE,
            InstallationSessionState.FAILED,
            InstallationSessionState.PAUSED,
            InstallationSessionState.MAINTENANCE,
        )

        val ACTIVE_INSTALL_STATES = setOf(
            InstallationSessionState.SELECTION_CONFIRMED,
            InstallationSessionState.RESOLVING_SOURCE,
            InstallationSessionState.DOWNLOADING_ARCHIVE,
            InstallationSessionState.VERIFYING_ARCHIVE,
            InstallationSessionState.EXTRACTING_APK,
            InstallationSessionState.VERIFYING_ARTIFACTS,
            InstallationSessionState.INSTALLING,
            InstallationSessionState.AUTHORIZING,
            InstallationSessionState.VERIFYING_DEVICE,
        )

        val CHECKPOINT_STATES = ACTIVE_INSTALL_STATES + setOf(
            InstallationSessionState.DISCOVERING,
            InstallationSessionState.CONNECTED,
        )

        val RESUMABLE_STATES = ACTIVE_INSTALL_STATES + setOf(
            InstallationSessionState.DISCOVERING,
            InstallationSessionState.CONNECTED,
        )
    }
}
