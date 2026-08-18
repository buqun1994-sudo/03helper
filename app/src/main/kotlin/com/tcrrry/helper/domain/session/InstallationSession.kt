package com.tcrrry.helper.domain.session

import com.tcrrry.helper.domain.artifact.ArchiveDownloadEvidence
import com.tcrrry.helper.domain.artifact.ArchiveVerificationEvidence
import com.tcrrry.helper.domain.artifact.ApkExtractionEvidence
import com.tcrrry.helper.domain.artifact.ArtifactManifestValidator
import com.tcrrry.helper.domain.artifact.ArtifactSourceKind
import com.tcrrry.helper.domain.artifact.ArtifactVerification
import com.tcrrry.helper.domain.artifact.ManifestValidation
import com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy
import com.tcrrry.helper.domain.artifact.SourceFailureRecord
import com.tcrrry.helper.domain.artifact.SourceSelectionEvidence
import com.tcrrry.helper.domain.artifact.toComponentDescriptor

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
            InstallationSessionCommand.CancelConnection -> cancelConnection()
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
            InstallationSessionCommand.DisconnectDevice -> disconnectDevice()
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
                selectedSources = emptyMap(),
                sourceFailures = emptyList(),
                archiveDownloads = emptyMap(),
                archiveVerifications = emptyMap(),
                apkExtractions = emptyMap(),
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
                selectedSources = emptyMap(),
                sourceFailures = emptyList(),
                archiveDownloads = emptyMap(),
                archiveVerifications = emptyMap(),
                apkExtractions = emptyMap(),
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
                val connectingDevice = device.copy(
                    connectionStatus = DeviceConnectionStatus.CONNECTING,
                    lastConfirmedLabel = null,
                )
                // Selecting a device starts a new generation; late discovery events cannot
                // overwrite the connection attempt.
                startNewGeneration(
                    current.copy(
                        state = InstallationSessionState.CONNECTING,
                        device = connectingDevice,
                        discoveredDevices = current.discoveredDevices.map { candidate ->
                            if (candidate.id == connectingDevice.id) connectingDevice else candidate
                        },
                        components = components,
                        failure = null,
                        checkpoint = null,
                    ),
                )
            }
        }
    }

    private fun cancelConnection() {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.CONNECTING) {
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
                selectedSources = emptyMap(),
                sourceFailures = emptyList(),
                archiveDownloads = emptyMap(),
                archiveVerifications = emptyMap(),
                apkExtractions = emptyMap(),
            ),
        )
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
            selectedSources = checkpoint.selectedSources,
            archiveDownloads = checkpoint.archiveDownloads,
            archiveVerifications = checkpoint.archiveVerifications,
            apkExtractions = checkpoint.apkExtractions,
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

    private fun disconnectDevice() {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.MAINTENANCE) {
            return
        }
        publish(
            current.copy(
                device = current.device?.copy(connectionStatus = DeviceConnectionStatus.DISCONNECTED),
            ),
        )
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
            is InstallationSessionEvent.DiscoveryFinished -> handleDiscoveryFinished(event)
            is InstallationSessionEvent.DeviceConnectionConfirmed -> handleDeviceConnectionConfirmed(event.device)
            is InstallationSessionEvent.DeviceConnectionFailed -> handleDeviceConnectionFailed(event)
            is InstallationSessionEvent.CatalogResolved -> handleCatalogResolved(event)
            is InstallationSessionEvent.CatalogFailed -> handleCatalogFailed(event.reasonCode)

            is InstallationSessionEvent.SourceResolved -> handleSourceResolved(event)
            is InstallationSessionEvent.SourceFailed -> handleSourceFailed(event)
            is InstallationSessionEvent.ArchiveDownloaded -> handleArchiveDownloaded(event)
            is InstallationSessionEvent.ArchiveVerified -> handleArchiveVerified(event)
            is InstallationSessionEvent.ApkExtracted -> handleApkExtracted(event)
            is InstallationSessionEvent.ArtifactsVerified -> handleArtifactsVerified(event)
            is InstallationSessionEvent.InstallationStarted -> handleInstallationStarted(event.componentIds)
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

    private fun handleDiscoveryFinished(event: InstallationSessionEvent.DiscoveryFinished) {
        if (!requireState(InstallationSessionState.DISCOVERING, "discovery_finished_out_of_order")) return
        if (event.scannedCount < 0 || event.confirmedCount < 0 || event.confirmedCount > event.scannedCount) {
            fail(FailureCategory.CONNECTION, reasonCode = "discovery_result_invalid")
            return
        }
        val current = _snapshot.value
        if (current.discoveredDevices.isNotEmpty() || event.confirmedCount > 0) {
            publish(current, acceptedEventSequence)
            return
        }
        startNewGeneration(
            current.copy(
                state = InstallationSessionState.IDLE,
                failure = SessionFailure(
                    category = FailureCategory.CONNECTION,
                    retryable = true,
                    reasonCode = event.reasonCode ?: "no_devices_found",
                ),
                checkpoint = null,
            ),
        )
    }

    private fun handleDeviceConnectionConfirmed(device: DeviceSummary) {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.CONNECTING) {
            fail(FailureCategory.CONNECTION, reasonCode = "device_connection_event_out_of_order")
            return
        }
        val pending = current.device
        when {
            pending == null -> fail(FailureCategory.CONNECTION, reasonCode = "device_connection_target_missing")
            pending.id != device.id -> fail(FailureCategory.CONNECTION, reasonCode = "device_connection_mismatch")
            device.connectionStatus != DeviceConnectionStatus.CONFIRMED -> fail(
                FailureCategory.CONNECTION,
                reasonCode = "device_connection_confirmation_invalid",
            )

            else -> publish(
                withCheckpoint(
                    current.copy(
                        state = InstallationSessionState.CONNECTED,
                        device = device,
                        discoveredDevices = mergeDevices(current.discoveredDevices, listOf(device)),
                        failure = null,
                    ),
                ),
                acceptedEventSequence,
            )
        }
    }

    private fun handleDeviceConnectionFailed(event: InstallationSessionEvent.DeviceConnectionFailed) {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.CONNECTING) {
            fail(FailureCategory.CONNECTION, reasonCode = "device_connection_failure_out_of_order")
            return
        }
        if (event.deviceId.isBlank() || event.reasonCode.isBlank()) {
            fail(FailureCategory.CONNECTION, reasonCode = "device_connection_failure_invalid")
            return
        }
        if (current.device?.id != event.deviceId) {
            fail(FailureCategory.CONNECTION, reasonCode = "device_connection_failure_mismatch")
            return
        }
        fail(
            category = FailureCategory.CONNECTION,
            retryable = event.retryable,
            reasonCode = event.reasonCode,
            deviceOverride = current.device?.copy(connectionStatus = DeviceConnectionStatus.DISCONNECTED),
        )
    }

    private fun handleCatalogResolved(event: InstallationSessionEvent.CatalogResolved) {
        val current = _snapshot.value
        if (current.state !in CATALOG_ACCEPTING_STATES) {
            fail(FailureCategory.UNKNOWN, reasonCode = "catalog_event_out_of_order")
            return
        }
        if (event.catalogVersion.isBlank() || event.keyId.isBlank() || event.signatureAlgorithm.isBlank()) {
            fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = "catalog_trust_evidence_missing")
            return
        }
        if (event.signatureAlgorithm !in SUPPORTED_CATALOG_SIGNATURE_ALGORITHMS) {
            fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = "catalog_signature_algorithm_unsupported")
            return
        }
        when (val validation = ArtifactManifestValidator.validateCatalog(event.manifests)) {
            is ManifestValidation.Invalid -> {
                fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = validation.reasonCode)
                return
            }

            ManifestValidation.Valid -> Unit
        }
        val sourcePolicy = ReleaseSourcePolicy()
        event.manifests.forEach { manifest ->
            when (val plan = sourcePolicy.plan(manifest)) {
                is com.tcrrry.helper.domain.artifact.SourcePlan.Rejected -> {
                    fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = plan.reasonCode)
                    return
                }

                is com.tcrrry.helper.domain.artifact.SourcePlan.Accepted -> Unit
            }
        }
        val components = event.manifests.map { it.toComponentDescriptor() }
        val optionalIds = components.filterNot { it.required }.map { it.id }.toSet()
        publish(
            current.copy(
                components = components,
                artifactManifests = event.manifests,
                catalogVersion = event.catalogVersion,
                catalogKeyId = event.keyId,
                catalogSignatureAlgorithm = event.signatureAlgorithm,
                selectedOptionalComponentIds = current.selectedOptionalComponentIds.filterTo(mutableSetOf()) {
                    it in optionalIds
                },
                currentComponentName = null,
                progress = null,
                evidence = SessionEvidence(),
                componentResults = emptyList(),
                checkpoint = null,
                selectedSources = emptyMap(),
                sourceFailures = emptyList(),
                archiveDownloads = emptyMap(),
                archiveVerifications = emptyMap(),
                apkExtractions = emptyMap(),
                failure = null,
            ),
            acceptedEventSequence,
        )
    }

    private fun handleCatalogFailed(reasonCode: String) {
        val current = _snapshot.value
        if (current.state == InstallationSessionState.CONNECTED) {
            // A connected device is still usable; only the remote installation data is unavailable.
            // Clear the connected checkpoint so this cannot be presented as resumable installation work.
            publish(
                current.copy(
                    failure = SessionFailure(
                        category = FailureCategory.VERIFICATION,
                        retryable = false,
                        reasonCode = reasonCode,
                    ),
                    checkpoint = null,
                ),
                acceptedEventSequence,
            )
            return
        }
        fail(
            category = FailureCategory.VERIFICATION,
            retryable = false,
            reasonCode = reasonCode,
        )
    }

    private fun handleSourceFailed(event: InstallationSessionEvent.SourceFailed) {
        val current = _snapshot.value
        if (current.state !in F2_PIPELINE_STATES) {
            fail(FailureCategory.UNKNOWN, reasonCode = "source_failure_out_of_order")
            return
        }
        if (current.state == InstallationSessionState.VERIFYING_ARTIFACTS &&
            current.evidence.artifactsVerified.isNotEmpty()
        ) {
            fail(FailureCategory.UNKNOWN, reasonCode = "source_failure_after_artifact_verification")
            return
        }
        if (event.componentId !in selectedComponentIds(current) || event.reasonCode.isBlank()) {
            fail(FailureCategory.DOWNLOAD, reasonCode = "source_failure_invalid")
            return
        }
        val failureRecord = SourceFailureRecord(
            componentId = event.componentId,
            sourceKind = event.sourceKind,
            reasonCode = event.reasonCode,
            retryable = event.retryable,
        )
        if (event.terminal) {
            publish(
                current.copy(
                    sourceFailures = (current.sourceFailures + failureRecord).takeLast(MAX_SOURCE_FAILURE_RECORDS),
                ),
                acceptedEventSequence,
            )
            fail(
                category = FailureCategory.DOWNLOAD,
                componentName = componentName(current, event.componentId),
                retryable = event.retryable,
                reasonCode = "all_sources_failed",
            )
            return
        }
        publish(
            current.copy(
                sourceFailures = (current.sourceFailures + failureRecord).takeLast(MAX_SOURCE_FAILURE_RECORDS),
            ),
            acceptedEventSequence,
        )
    }

    private fun handleSourceResolved(event: InstallationSessionEvent.SourceResolved) {
        if (!requireState(InstallationSessionState.RESOLVING_SOURCE, "source_event_out_of_order")) return
        if (event.sourceId.isBlank()) {
            fail(FailureCategory.DOWNLOAD, reasonCode = "source_missing")
            return
        }
        val current = _snapshot.value
        val selections = normalizeSourceSelections(event)
        if (current.artifactManifests.isNotEmpty() && !validateSourceSelections(selections, current)) {
            fail(FailureCategory.DOWNLOAD, reasonCode = "source_evidence_invalid")
            return
        }
        transition(
            state = InstallationSessionState.DOWNLOADING_ARCHIVE,
            progress = progress(0.0f, indeterminate = true),
            selectedSources = if (selections.isEmpty()) {
                current.selectedSources
            } else {
                selections.associate { it.componentId to it.sourceKind }
            },
        )
    }

    private fun handleArchiveDownloaded(event: InstallationSessionEvent.ArchiveDownloaded) {
        if (!requireState(InstallationSessionState.DOWNLOADING_ARCHIVE, "archive_event_out_of_order")) return
        val current = _snapshot.value
        val archives = normalizeArchiveDownloads(event)
        if (current.artifactManifests.isNotEmpty() && !validateArchiveDownloads(archives, current)) {
            fail(FailureCategory.DOWNLOAD, reasonCode = "archive_identity_invalid")
            return
        }
        if (current.artifactManifests.isEmpty() && (event.sizeBytes <= 0L || event.sha256.isBlank())) {
            fail(FailureCategory.DOWNLOAD, reasonCode = "archive_identity_missing")
            return
        }
        transition(
            state = InstallationSessionState.VERIFYING_ARCHIVE,
            progress = progress(0.25f, indeterminate = true),
            archiveDownloads = if (archives.isEmpty()) current.archiveDownloads else archives.associateBy { it.componentId },
        )
    }

    private fun handleArchiveVerified(event: InstallationSessionEvent.ArchiveVerified) {
        if (!requireState(InstallationSessionState.VERIFYING_ARCHIVE, "archive_verification_out_of_order")) return
        if (!event.verified) {
            fail(FailureCategory.ARCHIVE, reasonCode = "archive_verification_failed")
            return
        }
        val current = _snapshot.value
        val verifications = normalizeArchiveVerifications(event)
        if (current.artifactManifests.isNotEmpty() && !validateArchiveVerifications(verifications, current)) {
            fail(FailureCategory.ARCHIVE, reasonCode = "archive_verification_evidence_invalid")
            return
        }
        transition(
            state = InstallationSessionState.EXTRACTING_APK,
            progress = progress(0.4f, indeterminate = true),
            archiveVerifications = if (verifications.isEmpty()) {
                current.archiveVerifications
            } else {
                verifications.associateBy { it.componentId }
            },
        )
    }

    private fun handleApkExtracted(event: InstallationSessionEvent.ApkExtracted) {
        if (!requireState(InstallationSessionState.EXTRACTING_APK, "apk_event_out_of_order")) return
        val current = _snapshot.value
        val extractions = normalizeApkExtractions(event)
        if (current.artifactManifests.isNotEmpty() && !validateApkExtractions(extractions, current)) {
            fail(FailureCategory.ARCHIVE, reasonCode = "apk_extraction_evidence_invalid")
            return
        }
        if (current.artifactManifests.isEmpty() && !isValidLegacyApkExtraction(event)) {
            fail(FailureCategory.ARCHIVE, reasonCode = "apk_identity_missing")
            return
        }
        transition(
            state = InstallationSessionState.VERIFYING_ARTIFACTS,
            progress = progress(0.55f, indeterminate = true),
            apkExtractions = if (extractions.isEmpty()) current.apkExtractions else extractions.associateBy { it.componentId },
        )
    }

    private fun handleArtifactsVerified(event: InstallationSessionEvent.ArtifactsVerified) {
        if (!requireState(InstallationSessionState.VERIFYING_ARTIFACTS, "artifact_event_out_of_order")) return
        val verified = validateChecks(event.checks)
        if (verified == null) {
            fail(FailureCategory.VERIFICATION, reasonCode = "artifact_verification_failed")
            return
        }
        val current = _snapshot.value
        val verifications = event.verifications
        if (current.artifactManifests.isNotEmpty() && !validateArtifactVerifications(verifications, current)) {
            fail(FailureCategory.VERIFICATION, reasonCode = "artifact_verification_evidence_invalid")
            return
        }
        val evidence = current.evidence.copy(
            artifactsVerified = verified,
            artifactVerifications = if (verifications.isEmpty()) {
                current.evidence.artifactVerifications
            } else {
                verifications.associateBy { it.componentId }
            },
        )
        transition(
            state = InstallationSessionState.VERIFYING_ARTIFACTS,
            evidence = evidence,
            componentResults = buildComponentResults(evidence, current),
            progress = progress(0.6f, indeterminate = false),
        )
    }

    private fun handleInstallationStarted(componentIds: List<String>) {
        if (!requireState(InstallationSessionState.VERIFYING_ARTIFACTS, "installation_start_out_of_order")) return
        val current = _snapshot.value
        val expected = selectedComponentIds(current)
        val requested = componentIds.toSet().ifEmpty { expected }
        if (requested != expected || current.evidence.artifactsVerified != expected) {
            fail(FailureCategory.VERIFICATION, reasonCode = "artifact_evidence_incomplete")
            return
        }
        if (current.artifactManifests.isNotEmpty() && current.evidence.artifactVerifications.keys != expected) {
            fail(FailureCategory.VERIFICATION, reasonCode = "artifact_identity_evidence_missing")
            return
        }
        transition(
            state = InstallationSessionState.INSTALLING,
            progress = progress(0.65f, indeterminate = true),
        )
    }

    private fun normalizeSourceSelections(
        event: InstallationSessionEvent.SourceResolved,
    ): List<SourceSelectionEvidence> = when {
        event.selections.isNotEmpty() -> event.selections
        event.componentId != null && event.sourceKind != null -> listOf(
            SourceSelectionEvidence(event.componentId, event.sourceKind),
        )

        else -> emptyList()
    }

    private fun normalizeArchiveDownloads(
        event: InstallationSessionEvent.ArchiveDownloaded,
    ): List<ArchiveDownloadEvidence> = when {
        event.archives.isNotEmpty() -> event.archives
        event.componentId != null -> listOf(
            ArchiveDownloadEvidence(
                componentId = event.componentId,
                sizeBytes = event.sizeBytes,
                sha256 = event.sha256,
                resumed = event.resumed,
            ),
        )

        else -> emptyList()
    }

    private fun normalizeArchiveVerifications(
        event: InstallationSessionEvent.ArchiveVerified,
    ): List<ArchiveVerificationEvidence> = when {
        event.verifications.isNotEmpty() -> event.verifications
        event.verification != null -> listOf(event.verification)
        event.componentId != null -> listOf(
            ArchiveVerificationEvidence(
                componentId = event.componentId,
                sizeBytes = _snapshot.value.archiveDownloads[event.componentId]?.sizeBytes ?: 0L,
                sha256 = _snapshot.value.archiveDownloads[event.componentId]?.sha256.orEmpty(),
            ),
        )

        else -> emptyList()
    }

    private fun normalizeApkExtractions(
        event: InstallationSessionEvent.ApkExtracted,
    ): List<ApkExtractionEvidence> = when {
        event.extractions.isNotEmpty() -> event.extractions
        event.componentId != null -> listOf(
            ApkExtractionEvidence(
                componentId = event.componentId,
                entryName = event.entryName,
                sizeBytes = event.sizeBytes,
                sha256 = event.sha256,
            ),
        )

        else -> emptyList()
    }

    private fun validateSourceSelections(
        selections: List<SourceSelectionEvidence>,
        snapshot: InstallationSessionSnapshot,
    ): Boolean {
        val expected = selectedComponentIds(snapshot)
        if (selections.size != selections.map { it.componentId }.toSet().size) return false
        if (selections.map { it.componentId }.toSet() != expected) return false
        val manifests = snapshot.artifactManifests.associateBy { it.componentId }
        return selections.all { selection ->
            manifests[selection.componentId]?.sources?.any { it.kind == selection.sourceKind } == true
        }
    }

    private fun validateArchiveDownloads(
        downloads: List<ArchiveDownloadEvidence>,
        snapshot: InstallationSessionSnapshot,
    ): Boolean {
        val expected = selectedComponentIds(snapshot)
        if (downloads.size != downloads.map { it.componentId }.toSet().size) return false
        if (downloads.map { it.componentId }.toSet() != expected) return false
        val manifests = snapshot.artifactManifests.associateBy { it.componentId }
        return downloads.all { download ->
            val manifest = manifests[download.componentId] ?: return@all false
            download.sizeBytes == manifest.archiveSizeBytes &&
                download.sha256.equals(manifest.archiveSha256, ignoreCase = true)
        }
    }

    private fun validateArchiveVerifications(
        verifications: List<ArchiveVerificationEvidence>,
        snapshot: InstallationSessionSnapshot,
    ): Boolean {
        val expected = selectedComponentIds(snapshot)
        if (verifications.size != verifications.map { it.componentId }.toSet().size) return false
        if (verifications.map { it.componentId }.toSet() != expected) return false
        val manifests = snapshot.artifactManifests.associateBy { it.componentId }
        return verifications.all { verification ->
            val manifest = manifests[verification.componentId] ?: return@all false
            val downloaded = snapshot.archiveDownloads[verification.componentId] ?: return@all false
            verification.sizeBytes == manifest.archiveSizeBytes &&
                verification.sha256.equals(manifest.archiveSha256, ignoreCase = true) &&
                downloaded.sizeBytes == verification.sizeBytes &&
                downloaded.sha256.equals(verification.sha256, ignoreCase = true)
        }
    }

    private fun validateApkExtractions(
        extractions: List<ApkExtractionEvidence>,
        snapshot: InstallationSessionSnapshot,
    ): Boolean {
        val expected = selectedComponentIds(snapshot)
        if (extractions.size != extractions.map { it.componentId }.toSet().size) return false
        if (extractions.map { it.componentId }.toSet() != expected) return false
        val manifests = snapshot.artifactManifests.associateBy { it.componentId }
        return extractions.all { extraction ->
            val manifest = manifests[extraction.componentId] ?: return@all false
            extraction.entryName == manifest.apkEntryName &&
                extraction.sizeBytes == manifest.apkSizeBytes &&
                extraction.sha256.equals(manifest.apkSha256, ignoreCase = true) &&
                isSafeApkEntry(extraction.entryName)
        }
    }

    private fun validateArtifactVerifications(
        verifications: List<ArtifactVerification>,
        snapshot: InstallationSessionSnapshot,
    ): Boolean {
        val expected = selectedComponentIds(snapshot)
        if (verifications.size != verifications.map { it.componentId }.toSet().size) return false
        if (verifications.map { it.componentId }.toSet() != expected) return false
        val manifests = snapshot.artifactManifests.associateBy { it.componentId }
        return verifications.all { verification ->
            val manifest = manifests[verification.componentId] ?: return@all false
            verification.archiveDeleted &&
                snapshot.selectedSources[verification.componentId] == verification.sourceKind &&
                verification.archiveSizeBytes == manifest.archiveSizeBytes &&
                verification.archiveSha256.equals(manifest.archiveSha256, ignoreCase = true) &&
                verification.apkSizeBytes == manifest.apkSizeBytes &&
                verification.apkSha256.equals(manifest.apkSha256, ignoreCase = true) &&
                verification.packageName == manifest.packageName &&
                verification.apkVersion == manifest.apkVersion &&
                verification.certificateSha256.equals(manifest.certificateSha256, ignoreCase = true)
        }
    }

    private fun isValidLegacyApkExtraction(event: InstallationSessionEvent.ApkExtracted): Boolean =
        event.entryName.isNotBlank() &&
            event.entryName != "." &&
            event.entryName != ".." &&
            !event.entryName.contains('/') &&
            !event.entryName.contains('\\') &&
            !event.entryName.contains("..") &&
            !event.entryName.contains('\u0000') &&
            event.sizeBytes > 0L &&
            event.sha256.isNotBlank()

    private fun isSafeApkEntry(value: String): Boolean =
        value.isNotBlank() &&
            !value.startsWith('/') &&
            !value.startsWith('\\') &&
            !value.contains('/') &&
            !value.contains('\\') &&
            !value.contains("..") &&
            !value.contains('\u0000')

    private fun selectedComponentIds(snapshot: InstallationSessionSnapshot): Set<String> =
        snapshot.components.filter { it.required || it.id in snapshot.selectedOptionalComponentIds }
            .map { it.id }
            .toSet()

    private fun componentName(snapshot: InstallationSessionSnapshot, componentId: String): String? =
        snapshot.components.firstOrNull { it.id == componentId }?.displayName

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
        if (current.state == InstallationSessionState.CONNECTING) {
            fail(
                category = FailureCategory.CONNECTION,
                reasonCode = event.reasonCode,
                deviceOverride = current.device?.copy(connectionStatus = DeviceConnectionStatus.DISCONNECTED),
            )
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
        selectedSources: Map<String, ArtifactSourceKind> = _snapshot.value.selectedSources,
        archiveDownloads: Map<String, ArchiveDownloadEvidence> = _snapshot.value.archiveDownloads,
        archiveVerifications: Map<String, ArchiveVerificationEvidence> = _snapshot.value.archiveVerifications,
        apkExtractions: Map<String, ApkExtractionEvidence> = _snapshot.value.apkExtractions,
    ) {
        val current = _snapshot.value
        val next = current.copy(
            state = state,
            progress = progress,
            evidence = evidence,
            componentResults = componentResults,
            checkpoint = checkpoint,
            selectedSources = selectedSources,
            archiveDownloads = archiveDownloads,
            archiveVerifications = archiveVerifications,
            apkExtractions = apkExtractions,
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
        deviceOverride: DeviceSummary? = _snapshot.value.device,
    ) {
        val current = _snapshot.value
        val checkpoint = if (current.state in CHECKPOINT_STATES) checkpointOf(current) else current.checkpoint
        publish(
            current.copy(
                state = InstallationSessionState.FAILED,
                device = deviceOverride,
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
        if (snapshot.artifactManifests.isNotEmpty()) {
            when (val validation = ArtifactManifestValidator.validateCatalog(snapshot.artifactManifests)) {
                is ManifestValidation.Invalid -> return validation.reasonCode
                ManifestValidation.Valid -> Unit
            }
            if (snapshot.artifactManifests.map { it.componentId }.toSet() != components.map { it.id }.toSet()) {
                return "catalog_component_mapping_invalid"
            }
            val device = snapshot.device ?: return "device_not_confirmed"
            val androidSdk = device.androidSdk ?: return "device_android_sdk_missing"
            val requiredCapabilities = setOf(
                com.tcrrry.helper.domain.device.DeviceCapability.ADB_TCP,
                com.tcrrry.helper.domain.device.DeviceCapability.IDENTITY_READ,
            )
            if (!device.capabilities.containsAll(requiredCapabilities)) {
                return "device_capability_missing"
            }
            val selectedIds = selectedComponents(snapshot).map { it.id }.toSet()
            val incompatible = snapshot.artifactManifests.any { manifest ->
                manifest.componentId in selectedIds &&
                    (androidSdk < manifest.compatibility.minAndroidSdk ||
                        manifest.compatibility.maxAndroidSdk?.let { androidSdk > it } == true)
            }
            if (incompatible) return "component_incompatible"
        }
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
        selectedSources = snapshot.selectedSources,
        archiveDownloads = snapshot.archiveDownloads,
        archiveVerifications = snapshot.archiveVerifications,
        apkExtractions = snapshot.apkExtractions,
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
        const val MAX_SOURCE_FAILURE_RECORDS = 32
        val SUPPORTED_CATALOG_SIGNATURE_ALGORITHMS = setOf("SHA256withECDSA", "Ed25519")

        val CATALOG_ACCEPTING_STATES = setOf(
            InstallationSessionState.IDLE,
            InstallationSessionState.DISCOVERING,
            InstallationSessionState.CONNECTED,
        )

        val F2_PIPELINE_STATES = setOf(
            InstallationSessionState.RESOLVING_SOURCE,
            InstallationSessionState.DOWNLOADING_ARCHIVE,
            InstallationSessionState.VERIFYING_ARCHIVE,
            InstallationSessionState.EXTRACTING_APK,
            InstallationSessionState.VERIFYING_ARTIFACTS,
        )

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
