package com.ninepointnine.helper.domain.session

import com.ninepointnine.helper.domain.artifact.ArchiveDownloadEvidence
import com.ninepointnine.helper.domain.artifact.ArchiveVerificationEvidence
import com.ninepointnine.helper.domain.artifact.ApkExtractionEvidence
import com.ninepointnine.helper.domain.artifact.ArtifactManifestValidator
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ArtifactVerification
import com.ninepointnine.helper.domain.artifact.InstallerSelfIdentity
import com.ninepointnine.helper.domain.artifact.ManifestValidation
import com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy
import com.ninepointnine.helper.domain.artifact.SourceFailureRecord
import com.ninepointnine.helper.domain.artifact.SourceSelectionEvidence
import com.ninepointnine.helper.domain.artifact.toComponentDescriptor
import com.ninepointnine.helper.domain.artifact.formatArtifactVersionLabel
import com.ninepointnine.helper.domain.artifact.formatArtifactSizeLabel
import com.ninepointnine.helper.domain.device.AuthorizationPlanBuildResult
import com.ninepointnine.helper.domain.device.AuthorizationPlanFactory
import com.ninepointnine.helper.domain.device.DeviceAvailabilityEvidence
import com.ninepointnine.helper.domain.device.InstalledArtifactEvidence

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
    private val sourcePolicy: ReleaseSourcePolicy = ReleaseSourcePolicy(),
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

            InstallationSessionCommand.RestartFromCheckpoint -> restartFromCheckpoint()

            InstallationSessionCommand.Reconnect -> startReconnect()
            InstallationSessionCommand.ReconnectKnownDevice -> startKnownReconnect()
            InstallationSessionCommand.DisconnectDevice -> disconnectDevice()
            InstallationSessionCommand.EnterMaintenance -> enterMaintenance()
            is InstallationSessionCommand.MaintenanceAction -> handleMaintenanceAction(command)
            is InstallationSessionCommand.MaintenanceApplicationAction ->
                handleMaintenanceApplicationAction(command)
            is InstallationSessionCommand.ToggleMaintenanceInstallationComponent ->
                toggleMaintenanceInstallationComponent(command)
            InstallationSessionCommand.StartMaintenanceInstallation -> startSelectedMaintenanceInstallation()
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
        val maintenanceReconnect = current.state == InstallationSessionState.MAINTENANCE
        val maintenanceDevice = if (maintenanceReconnect) {
            current.device?.copy(connectionStatus = DeviceConnectionStatus.DISCONNECTED)
        } else {
            null
        }
        val maintenance = if (maintenanceReconnect) {
            current.maintenance.copy(
                activeAction = null,
                lastAction = current.maintenance.activeAction?.let { actionId ->
                    MaintenanceActionRecord(
                        actionId = actionId,
                        status = MaintenanceActionStatus.FAILED,
                        reasonCode = "reconnect_started",
                        retryable = true,
                    )
                } ?: current.maintenance.lastAction,
            )
        } else {
            MaintenanceSnapshot()
        }
        startNewGeneration(
            current.copy(
                state = InstallationSessionState.DISCOVERING,
                device = maintenanceDevice,
                discoveredDevices = emptyList(),
                selectedOptionalComponentIds = if (maintenanceReconnect) {
                    current.selectedOptionalComponentIds
                } else {
                    emptySet()
                },
                currentComponentName = if (maintenanceReconnect) current.currentComponentName else null,
                progress = if (maintenanceReconnect) current.progress else null,
                componentProgress = if (maintenanceReconnect) current.componentProgress else emptyMap(),
                failedComponentIds = if (maintenanceReconnect) current.failedComponentIds else emptySet(),
                failure = null,
                checkpoint = null,
                evidence = if (maintenanceReconnect) current.evidence else SessionEvidence(),
                componentResults = if (maintenanceReconnect) current.componentResults else emptyList(),
                components = current.components.ifEmpty { catalog },
                selectedSources = if (maintenanceReconnect) current.selectedSources else emptyMap(),
                sourceFailures = if (maintenanceReconnect) current.sourceFailures else emptyList(),
                archiveDownloads = if (maintenanceReconnect) current.archiveDownloads else emptyMap(),
                archiveVerifications = if (maintenanceReconnect) current.archiveVerifications else emptyMap(),
                apkExtractions = if (maintenanceReconnect) current.apkExtractions else emptyMap(),
                maintenance = maintenance,
                maintenanceReconnectPending = maintenanceReconnect,
                installationReconnectPending = false,
            ),
        )
    }

    /** Reconnects either the maintenance lease or an interrupted install lease. */
    private fun startReconnect() {
        val current = _snapshot.value
        when {
            current.state == InstallationSessionState.MAINTENANCE -> startDiscovery()
            current.state == InstallationSessionState.CONNECTED &&
                current.checkpoint != null -> startInstallationReconnect(current)
            current.state in ACTIVE_INSTALL_STATES ||
                current.state in setOf(InstallationSessionState.PAUSED, InstallationSessionState.FAILED) &&
                current.checkpoint != null -> startInstallationReconnect(current)
            else -> startDiscovery()
        }
    }

    /**
     * Starts a reconnect handshake against the last verified endpoint. The
     * application runtime owns that endpoint; this state transition keeps the
     * same reconnect guards and evidence boundaries as a scanned candidate.
     */
    private fun startKnownReconnect() {
        val current = _snapshot.value
        val device = current.device ?: return
        val connectingDevice = device.copy(
            connectionStatus = DeviceConnectionStatus.CONNECTING,
            lastConfirmedLabel = null,
        )
        when {
            current.state == InstallationSessionState.MAINTENANCE -> {
                val maintenance = current.maintenance.copy(
                    activeAction = null,
                    lastAction = current.maintenance.activeAction?.let { actionId ->
                        MaintenanceActionRecord(
                            actionId = actionId,
                            status = MaintenanceActionStatus.FAILED,
                            reasonCode = "reconnect_started",
                            retryable = true,
                        )
                    } ?: current.maintenance.lastAction,
                )
                startNewGeneration(
                    current.copy(
                        state = InstallationSessionState.CONNECTING,
                        device = connectingDevice,
                        discoveredDevices = listOf(connectingDevice),
                        failure = null,
                        checkpoint = null,
                        maintenance = maintenance,
                        maintenanceReconnectPending = true,
                        installationReconnectPending = false,
                    ),
                )
            }

            current.state == InstallationSessionState.CONNECTED && current.checkpoint != null ->
                startKnownInstallationReconnect(current, connectingDevice)

            current.state in ACTIVE_INSTALL_STATES ||
                current.state in setOf(InstallationSessionState.PAUSED, InstallationSessionState.FAILED) &&
                current.checkpoint != null -> startKnownInstallationReconnect(current, connectingDevice)
        }
    }

    private fun startKnownInstallationReconnect(
        current: InstallationSessionSnapshot,
        connectingDevice: DeviceSummary,
    ) {
        startNewGeneration(
            current.copy(
                state = InstallationSessionState.CONNECTING,
                device = connectingDevice,
                discoveredDevices = listOf(connectingDevice),
                failure = null,
                maintenanceReconnectPending = false,
                installationReconnectPending = true,
            ),
        )
    }

    private fun startInstallationReconnect(current: InstallationSessionSnapshot) {
        val checkpoint = current.checkpoint ?: run {
            startDiscovery()
            return
        }
        startNewGeneration(
            current.copy(
                state = InstallationSessionState.DISCOVERING,
                device = current.device?.copy(connectionStatus = DeviceConnectionStatus.DISCONNECTED),
                discoveredDevices = emptyList(),
                failure = null,
                checkpoint = checkpoint,
                maintenanceReconnectPending = false,
                installationReconnectPending = true,
            ),
        )
    }

    private fun stopDiscovery() {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.DISCOVERING) {
            return
        }
        if (current.maintenanceReconnectPending) {
            returnToMaintenanceDisconnected(current)
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
                componentProgress = emptyMap(),
                failedComponentIds = emptySet(),
                failure = null,
                checkpoint = null,
                evidence = SessionEvidence(),
                componentResults = emptyList(),
                selectedSources = emptyMap(),
                sourceFailures = emptyList(),
                archiveDownloads = emptyMap(),
                archiveVerifications = emptyMap(),
                apkExtractions = emptyMap(),
                maintenanceReconnectPending = false,
            ),
        )
    }

    private fun selectDevice(deviceId: String) {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.DISCOVERING) {
            return
        }
        val device = current.discoveredDevices.firstOrNull { it.id == deviceId }
        val reconnectingMaintenance = current.maintenanceReconnectPending
        val reconnectingInstallation = current.installationReconnectPending
        when {
            device == null -> {
                if (reconnectingMaintenance) {
                    returnToMaintenanceDisconnected(current, reasonCode = "device_not_discovered")
                } else if (reconnectingInstallation) {
                    returnToInstallationPaused(current, reasonCode = "device_not_discovered")
                } else {
                    fail(
                        category = FailureCategory.CONNECTION,
                        reasonCode = "device_not_discovered",
                    )
                }
            }

            reconnectingMaintenance &&
                current.device?.id != null &&
                current.device?.id != device.id -> {
                returnToMaintenanceDisconnected(current, reasonCode = "maintenance_device_mismatch")
            }

            reconnectingInstallation &&
                current.device?.id != null &&
                current.device?.id != device.id -> {
                returnToInstallationPaused(current, reasonCode = "installation_device_mismatch")
            }

            device.connectionStatus != DeviceConnectionStatus.CONFIRMED -> {
                if (reconnectingMaintenance) {
                    returnToMaintenanceDisconnected(current, reasonCode = "device_not_confirmed")
                } else if (reconnectingInstallation) {
                    returnToInstallationPaused(current, reasonCode = "device_not_confirmed")
                } else {
                    fail(
                        category = FailureCategory.CONNECTION,
                        reasonCode = "device_not_confirmed",
                    )
                }
            }

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
                        checkpoint = current.checkpoint.takeIf { reconnectingInstallation },
                        maintenanceReconnectPending = reconnectingMaintenance,
                        installationReconnectPending = reconnectingInstallation,
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
        if (current.maintenanceReconnectPending) {
            returnToMaintenanceDisconnected(current, reasonCode = "connection_cancelled")
            return
        }
        if (current.installationReconnectPending) {
            returnToInstallationPaused(current, reasonCode = "connection_cancelled")
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
                componentProgress = emptyMap(),
                failedComponentIds = emptySet(),
                failure = null,
                checkpoint = null,
                evidence = SessionEvidence(),
                componentResults = emptyList(),
                selectedSources = emptyMap(),
                sourceFailures = emptyList(),
                archiveDownloads = emptyMap(),
                archiveVerifications = emptyMap(),
                apkExtractions = emptyMap(),
                maintenanceReconnectPending = false,
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

            isMandatory(component) -> {
                // The desktop is the only core invariant. Other required entries
                // are recommendations and remain user-selectable.
                return
            }

            !isSelectable(component) -> return

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
            componentProgress = selected.associate { component ->
                component.id to ComponentProgress(
                    componentId = component.id,
                    phase = InstallPhase.FETCH,
                    status = ComponentProgressStatus.PENDING,
                )
            },
            failedComponentIds = emptySet(),
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
        if (validationFailure != null && current.artifactManifests.isNotEmpty()) {
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
        if (current.state == InstallationSessionState.CONNECTED && current.failure != null) {
            // Catalog retry has no installation checkpoint. Clear the stale
            // preparation error so the UI can show the new load in progress.
            publish(current.copy(failure = null))
            return
        }
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
            componentProgress = checkpoint.componentProgress,
            failedComponentIds = checkpoint.failedComponentIds,
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

    /** Resets an uncertain device-write boundary to the verified selection. */
    private fun restartFromCheckpoint() {
        val current = _snapshot.value
        if (current.state !in setOf(
                InstallationSessionState.INSTALLING,
                InstallationSessionState.AUTHORIZING,
                InstallationSessionState.VERIFYING_DEVICE,
            ) || current.checkpoint == null
        ) {
            return
        }
        val selected = selectedComponents(current)
        if (selected.isEmpty()) return
        val next = current.copy(
            state = InstallationSessionState.SELECTION_CONFIRMED,
            currentComponentName = selected.first().displayName,
            progress = SessionProgress(
                completedCount = 0,
                totalCount = selected.size,
                indeterminate = true,
            ),
            failure = null,
            evidence = SessionEvidence(),
            componentResults = buildComponentResults(SessionEvidence(), current),
            failedComponentIds = emptySet(),
            checkpoint = null,
            selectedSources = emptyMap(),
            sourceFailures = emptyList(),
            archiveDownloads = emptyMap(),
            archiveVerifications = emptyMap(),
            apkExtractions = emptyMap(),
            installationReconnectPending = false,
        )
        startNewGeneration(withCheckpoint(next))
    }

    private fun enterMaintenance() {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.SUCCEEDED &&
            current.state != InstallationSessionState.COMPLETED_WITH_ERRORS
        ) {
            return
        }
        val desktopReady = AuthorizationPlanFactory.DESKTOP_COMPONENT_ID in current.evidence.available
        if (!desktopReady ||
            (current.state == InstallationSessionState.SUCCEEDED && !hasCompleteSuccessEvidence(current))
        ) {
            fail(
                category = FailureCategory.VERIFICATION,
                reasonCode = "success_evidence_incomplete",
            )
            return
        }
        publish(
            current.copy(
                state = InstallationSessionState.MAINTENANCE,
                checkpoint = null,
                maintenance = MaintenanceSnapshot(),
                maintenanceReconnectPending = false,
                installationReconnectPending = false,
            ),
        )
    }

    private fun disconnectDevice() {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.MAINTENANCE) {
            return
        }
        val activeAction = current.maintenance.activeAction
        publish(
            current.copy(
                device = current.device?.copy(connectionStatus = DeviceConnectionStatus.DISCONNECTED),
                maintenance = current.maintenance.copy(
                    activeAction = null,
                    lastAction = activeAction?.let { actionId ->
                        MaintenanceActionRecord(
                            actionId = actionId,
                            status = MaintenanceActionStatus.FAILED,
                            reasonCode = "device_disconnected",
                            retryable = true,
                        )
                    } ?: current.maintenance.lastAction,
                    applicationAction = current.maintenance.applicationAction
                        ?.takeIf { it.status == MaintenanceActionStatus.RUNNING }
                        ?.copy(
                            status = MaintenanceActionStatus.FAILED,
                            reasonCode = "device_disconnected",
                            retryable = true,
                        ),
                ),
            ),
        )
    }

    private fun handleMaintenanceAction(command: InstallationSessionCommand.MaintenanceAction) {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.MAINTENANCE) {
            return
        }
        if (current.maintenance.activeAction != null) {
            return
        }
        if (
            command.actionId.requiresConnectedDevice &&
            current.device?.connectionStatus != DeviceConnectionStatus.CONFIRMED
        ) {
            publish(
                current.copy(
                    maintenance = current.maintenance.copy(
                        lastAction = MaintenanceActionRecord(
                            actionId = command.actionId,
                            status = MaintenanceActionStatus.FAILED,
                            reasonCode = "device_disconnected",
                            retryable = true,
                        ),
                    ),
                ),
            )
            return
        }
        if (command.actionId == MaintenanceActionId.INSTALL_FILE_MANAGER) {
            beginMaintenanceInstallationSelection(command.actionId)
            return
        }
        if (command.actionId == MaintenanceActionId.REINSTALL) {
            startMaintenanceInstallation(command.actionId)
            return
        }
        publish(
            current.copy(
                maintenance = current.maintenance.copy(
                    activeAction = command.actionId,
                    lastAction = MaintenanceActionRecord(
                        actionId = command.actionId,
                        status = MaintenanceActionStatus.RUNNING,
                    ),
                ),
            ),
        )
    }

    private fun handleMaintenanceApplicationAction(
        command: InstallationSessionCommand.MaintenanceApplicationAction,
    ) {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.MAINTENANCE ||
            current.maintenance.activeAction != null ||
            current.maintenance.applicationAction?.status == MaintenanceActionStatus.RUNNING
        ) {
            return
        }
        if (current.device?.connectionStatus != DeviceConnectionStatus.CONFIRMED) {
            publish(
                current.copy(
                    maintenance = current.maintenance.copy(
                        applicationAction = MaintenanceApplicationActionRecord(
                            componentId = command.componentId,
                            actionId = command.actionId,
                            status = MaintenanceActionStatus.FAILED,
                            reasonCode = "device_disconnected",
                            retryable = true,
                        ),
                    ),
                ),
            )
            return
        }
        val known = current.components.any { it.id == command.componentId } ||
            current.artifactManifests.any { it.componentId == command.componentId } ||
            current.maintenance.availableManifests.any { it.componentId == command.componentId } ||
            current.maintenance.managedApplications.any { it.componentId == command.componentId }
        if (!known) {
            publish(
                current.copy(
                    maintenance = current.maintenance.copy(
                        applicationAction = MaintenanceApplicationActionRecord(
                            componentId = command.componentId,
                            actionId = command.actionId,
                            status = MaintenanceActionStatus.FAILED,
                            reasonCode = "maintenance_component_unavailable",
                            retryable = false,
                        ),
                    ),
                ),
            )
            return
        }
        publish(
            current.copy(
                maintenance = current.maintenance.copy(
                    applicationAction = MaintenanceApplicationActionRecord(
                        componentId = command.componentId,
                        actionId = command.actionId,
                        status = MaintenanceActionStatus.RUNNING,
                    ),
                ),
            ),
        )
    }

    private fun startMaintenanceInstallation(
        actionId: MaintenanceActionId,
        selectedOptionalOverride: Set<String>? = null,
    ) {
        val current = _snapshot.value
        val selectedOptional = selectedOptionalOverride ?: when (actionId) {
            MaintenanceActionId.REINSTALL -> current.maintenance.updateStatuses
                .filter {
                    !it.isSelf && it.state in setOf(
                        MaintenanceUpdateState.UPDATE_AVAILABLE,
                        MaintenanceUpdateState.NOT_INSTALLED,
                    )
                }
                .map { it.componentId }
                .filter { it != AuthorizationPlanFactory.DESKTOP_COMPONENT_ID }
                .toSet()
                .ifEmpty { current.selectedOptionalComponentIds }
            MaintenanceActionId.INSTALL_FILE_MANAGER ->
                current.selectedOptionalComponentIds + AuthorizationPlanFactory.FILE_MANAGER_COMPONENT_ID

            else -> return
        }
        val candidateManifests = (current.maintenance.availableManifests.takeIf { it.isNotEmpty() }
            ?: current.artifactManifests).filterNot {
                InstallerSelfIdentity.isSelfComponentId(it.componentId)
            }
        val candidateComponents = candidateManifests.map { it.toComponentDescriptor(current.device?.androidSdk) }
            .takeIf { it.isNotEmpty() }
            ?: current.components
        val candidate = current.copy(
            components = candidateComponents,
            artifactManifests = candidateManifests,
            catalogVersion = current.maintenance.availableCatalogVersion ?: current.catalogVersion,
            catalogKeyId = current.maintenance.availableCatalogKeyId ?: current.catalogKeyId,
            catalogSignatureAlgorithm = current.maintenance.availableCatalogSignatureAlgorithm
                ?: current.catalogSignatureAlgorithm,
            selectedOptionalComponentIds = selectedOptional,
            maintenance = current.maintenance.copy(installationSelection = null),
        )
        val validationFailure = validateSelection(candidate)
        if (validationFailure != null) {
            publish(
                current.copy(
                    maintenance = current.maintenance.copy(
                        activeAction = null,
                        lastAction = MaintenanceActionRecord(
                            actionId = actionId,
                            status = MaintenanceActionStatus.FAILED,
                            reasonCode = validationFailure,
                            retryable = false,
                        ),
                    ),
                ),
            )
            return
        }
        val selected = selectedComponents(candidate)
        val next = candidate.copy(
            state = InstallationSessionState.SELECTION_CONFIRMED,
            currentComponentName = selected.firstOrNull()?.displayName,
            progress = SessionProgress(
                completedCount = 0,
                totalCount = selected.size,
                indeterminate = true,
            ),
            componentProgress = selected.associate { component ->
                component.id to ComponentProgress(
                    componentId = component.id,
                    phase = InstallPhase.FETCH,
                    status = ComponentProgressStatus.PENDING,
                )
            },
            failedComponentIds = emptySet(),
            failure = null,
            evidence = SessionEvidence(),
            componentResults = buildComponentResults(SessionEvidence(), candidate),
            maintenance = current.maintenance.copy(installationSelection = null),
        )
        publish(withCheckpoint(next))
    }

    private fun beginMaintenanceInstallationSelection(actionId: MaintenanceActionId) {
        val current = _snapshot.value
        val manifests = (current.maintenance.availableManifests.takeIf { it.isNotEmpty() }
            ?: current.artifactManifests).filterNot {
                InstallerSelfIdentity.isSelfComponentId(it.componentId)
            }
        val existingInstalled = current.evidence.installed + current.maintenance.managedApplications
            .filter { it.installed }
            .map { it.componentId }
        val options = manifests.map { manifest ->
            MaintenanceInstallationOption(
                componentId = manifest.componentId,
                displayName = manifest.displayName,
                versionLabel = formatArtifactVersionLabel(manifest.version.name),
                sizeLabel = formatArtifactSizeLabel(manifest.apkSizeBytes),
                installed = manifest.componentId in existingInstalled,
                required = manifest.required || manifest.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID,
                iconKey = manifest.componentId,
            )
        }.ifEmpty {
            current.components.filterNot { InstallerSelfIdentity.isSelfComponentId(it.id) }.map { component ->
                MaintenanceInstallationOption(
                    componentId = component.id,
                    displayName = component.displayName,
                    versionLabel = component.versionLabel,
                    sizeLabel = component.sizeLabel,
                    installed = component.id in existingInstalled,
                    required = component.required || component.id == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID,
                    iconKey = component.iconKey,
                )
            }
        }
        val selected = options.filter { it.installed || it.required }.map { it.componentId }.toSet()
        publish(
            current.copy(
                maintenance = current.maintenance.copy(
                    installationSelection = MaintenanceInstallationSelection(
                        actionId = actionId,
                        options = options,
                        selectedComponentIds = selected,
                    ),
                    lastAction = MaintenanceActionRecord(
                        actionId = actionId,
                        status = MaintenanceActionStatus.RUNNING,
                    ),
                ),
            ),
        )
    }

    private fun toggleMaintenanceInstallationComponent(
        command: InstallationSessionCommand.ToggleMaintenanceInstallationComponent,
    ) {
        val current = _snapshot.value
        val selection = current.maintenance.installationSelection ?: return
        val option = selection.options.firstOrNull { it.componentId == command.componentId } ?: return
        if (!command.selected && option.required) return
        val selected = selection.selectedComponentIds.toMutableSet().apply {
            if (command.selected) add(command.componentId) else remove(command.componentId)
        }
        publish(
            current.copy(
                maintenance = current.maintenance.copy(
                    installationSelection = selection.copy(selectedComponentIds = selected),
                ),
            ),
        )
    }

    private fun startSelectedMaintenanceInstallation() {
        val current = _snapshot.value
        val selection = current.maintenance.installationSelection ?: return
        if (selection.selectedComponentIds.none { it == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID }) {
            publish(
                current.copy(
                    maintenance = current.maintenance.copy(
                        installationSelection = selection,
                        lastAction = MaintenanceActionRecord(
                            actionId = selection.actionId,
                            status = MaintenanceActionStatus.FAILED,
                            reasonCode = "maintenance_desktop_required",
                            retryable = false,
                        ),
                    ),
                ),
            )
            return
        }
        val selectedOptional = selection.selectedComponentIds
            .filterNot { it == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID }
            .toSet()
        startMaintenanceInstallation(
            actionId = selection.actionId,
            selectedOptionalOverride = selectedOptional,
        )
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
            is InstallationSessionEvent.DistributionConfigResolved -> handleDistributionConfigResolved(event)
            is InstallationSessionEvent.SelectedCatalogResolved -> handleSelectedCatalogResolved(event)
            is InstallationSessionEvent.CatalogFailed -> handleCatalogFailed(event.reasonCode)

            is InstallationSessionEvent.SourceResolved -> handleSourceResolved(event)
            is InstallationSessionEvent.SourceFailed -> handleSourceFailed(event)
            is InstallationSessionEvent.ArchiveDownloaded -> handleArchiveDownloaded(event)
            is InstallationSessionEvent.ArchiveVerified -> handleArchiveVerified(event)
            is InstallationSessionEvent.ApkExtracted -> handleApkExtracted(event)
            is InstallationSessionEvent.ArtifactsVerified -> handleArtifactsVerified(event)
            is InstallationSessionEvent.ArtifactUnavailable -> handleArtifactUnavailable(event)
            is InstallationSessionEvent.ComponentProgressUpdated -> handleComponentProgressUpdated(event)
            is InstallationSessionEvent.ComponentFailed -> handleComponentFailed(event)
            is InstallationSessionEvent.InstallationStarted -> handleInstallationStarted(event.componentIds)
            is InstallationSessionEvent.InstallationCompleted -> handleInstallationCompleted(event)
            is InstallationSessionEvent.AuthorizationCompleted -> handleAuthorizationCompleted(event)
            is InstallationSessionEvent.DeviceVerified -> handleDeviceVerified(event)
            is InstallationSessionEvent.DeviceDisconnected -> handleDeviceDisconnected(event)
            is InstallationSessionEvent.DeviceReconnected -> handleDeviceReconnected(event.device)
            is InstallationSessionEvent.MaintenanceActionCompleted -> handleMaintenanceActionCompleted(event)
            is InstallationSessionEvent.MaintenanceActionFailed -> handleMaintenanceActionFailed(event)
            is InstallationSessionEvent.MaintenanceApplicationsResolved ->
                handleMaintenanceApplicationsResolved(event)
            is InstallationSessionEvent.MaintenanceApplicationActionCompleted ->
                handleMaintenanceApplicationActionCompleted(event)
            is InstallationSessionEvent.MaintenanceApplicationActionFailed ->
                handleMaintenanceApplicationActionFailed(event)
            is InstallationSessionEvent.MaintenanceApplicationDetailsResolved ->
                handleMaintenanceApplicationDetailsResolved(event)
            is InstallationSessionEvent.MaintenanceAuthorizationCheckStarted ->
                handleMaintenanceAuthorizationCheckStarted(event)
            is InstallationSessionEvent.MaintenanceAuthorizationCheckProgress ->
                handleMaintenanceAuthorizationCheckProgress(event)
            is InstallationSessionEvent.MaintenanceAuthorizationChecked ->
                handleMaintenanceAuthorizationChecked(event)
            is InstallationSessionEvent.MaintenanceCatalogRefreshed -> handleMaintenanceCatalogRefreshed(event)
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
            if (_snapshot.value.maintenanceReconnectPending) {
                returnToMaintenanceDisconnected(_snapshot.value, reasonCode = "device_id_missing")
                return
            }
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
            if (_snapshot.value.maintenanceReconnectPending) {
                returnToMaintenanceDisconnected(_snapshot.value, reasonCode = "device_id_missing")
                return
            }
            fail(FailureCategory.CONNECTION, reasonCode = "device_id_missing")
            return
        }
        val current = _snapshot.value
        publish(current.copy(discoveredDevices = mergeDevices(current.discoveredDevices, devices)), acceptedEventSequence)
    }

    private fun handleDiscoveryFinished(event: InstallationSessionEvent.DiscoveryFinished) {
        if (!requireState(InstallationSessionState.DISCOVERING, "discovery_finished_out_of_order")) return
        if (event.scannedCount < 0 || event.confirmedCount < 0 || event.confirmedCount > event.scannedCount) {
            if (_snapshot.value.maintenanceReconnectPending) {
                returnToMaintenanceDisconnected(_snapshot.value, reasonCode = "discovery_result_invalid")
                return
            }
            fail(FailureCategory.CONNECTION, reasonCode = "discovery_result_invalid")
            return
        }
        val current = _snapshot.value
        if (current.discoveredDevices.isNotEmpty() || event.confirmedCount > 0) {
            publish(current, acceptedEventSequence)
            return
        }
        if (current.maintenanceReconnectPending) {
            returnToMaintenanceDisconnected(
                current,
                reasonCode = event.reasonCode ?: "no_devices_found",
            )
            return
        }
        if (current.installationReconnectPending) {
            returnToInstallationPaused(
                current,
                reasonCode = event.reasonCode ?: "no_devices_found",
            )
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
            pending == null -> {
                if (current.maintenanceReconnectPending) {
                    returnToMaintenanceDisconnected(current, reasonCode = "device_connection_target_missing")
                } else if (current.installationReconnectPending) {
                    returnToInstallationPaused(current, reasonCode = "device_connection_target_missing")
                } else {
                    fail(FailureCategory.CONNECTION, reasonCode = "device_connection_target_missing")
                }
            }

            pending.id != device.id -> {
                if (current.maintenanceReconnectPending) {
                    returnToMaintenanceDisconnected(current, reasonCode = "device_connection_mismatch")
                } else if (current.installationReconnectPending) {
                    returnToInstallationPaused(current, reasonCode = "device_connection_mismatch")
                } else {
                    fail(FailureCategory.CONNECTION, reasonCode = "device_connection_mismatch")
                }
            }

            device.connectionStatus != DeviceConnectionStatus.CONFIRMED -> {
                if (current.maintenanceReconnectPending) {
                    returnToMaintenanceDisconnected(current, reasonCode = "device_connection_confirmation_invalid")
                } else if (current.installationReconnectPending) {
                    returnToInstallationPaused(current, reasonCode = "device_connection_confirmation_invalid")
                } else {
                    fail(
                        FailureCategory.CONNECTION,
                        reasonCode = "device_connection_confirmation_invalid",
                    )
                }
            }

            else -> {
                val maintenanceReconnect = current.maintenanceReconnectPending
                val installationReconnect = current.installationReconnectPending
                val restoredState = if (installationReconnect) {
                    current.checkpoint?.state ?: InstallationSessionState.PAUSED
                } else {
                    InstallationSessionState.CONNECTED
                }
                val refreshedComponents = current.artifactManifests.map {
                    it.toComponentDescriptor(device.androidSdk)
                }
                val next = current.copy(
                    state = if (maintenanceReconnect) {
                        InstallationSessionState.MAINTENANCE
                    } else if (installationReconnect) {
                        restoredState
                    } else {
                        InstallationSessionState.CONNECTED
                    },
                    device = device,
                    discoveredDevices = mergeDevices(current.discoveredDevices, listOf(device)),
                    components = refreshedComponents.takeIf { it.isNotEmpty() } ?: current.components,
                    failure = null,
                    maintenanceReconnectPending = false,
                    installationReconnectPending = false,
                )
                publish(
                    if (maintenanceReconnect) next else withCheckpoint(next),
                    acceptedEventSequence,
                )
            }
        }
    }

    private fun handleDeviceConnectionFailed(event: InstallationSessionEvent.DeviceConnectionFailed) {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.CONNECTING) {
            fail(FailureCategory.CONNECTION, reasonCode = "device_connection_failure_out_of_order")
            return
        }
        if (event.deviceId.isBlank() || event.reasonCode.isBlank()) {
            if (current.maintenanceReconnectPending) {
                returnToMaintenanceDisconnected(current, reasonCode = "device_connection_failure_invalid")
                return
            }
            if (current.installationReconnectPending) {
                returnToInstallationPaused(current, reasonCode = "device_connection_failure_invalid")
                return
            }
            fail(FailureCategory.CONNECTION, reasonCode = "device_connection_failure_invalid")
            return
        }
        if (current.device?.id != event.deviceId) {
            if (current.maintenanceReconnectPending) {
                returnToMaintenanceDisconnected(current, reasonCode = "device_connection_failure_mismatch")
                return
            }
            fail(FailureCategory.CONNECTION, reasonCode = "device_connection_failure_mismatch")
            return
        }
        if (current.maintenanceReconnectPending) {
            returnToMaintenanceDisconnected(current, reasonCode = event.reasonCode)
            return
        }
        if (current.installationReconnectPending) {
            returnToInstallationPaused(current, reasonCode = event.reasonCode)
            return
        }
        fail(
            category = FailureCategory.CONNECTION,
            retryable = event.retryable,
            reasonCode = event.reasonCode,
            deviceOverride = current.device?.copy(connectionStatus = DeviceConnectionStatus.DISCONNECTED),
        )
    }

    private fun handleCatalogResolved(
        event: InstallationSessionEvent.CatalogResolved,
        allowSelectionConfirmed: Boolean = false,
    ) {
        // The helper APK may be published in the same signed Cloud snapshot for
        // maintenance updates, but it is never a first-install component.
        // Filter it at the session boundary so UI and selection code cannot
        // accidentally reintroduce it through a second path.
        val manifests = event.manifests.filterNot {
            InstallerSelfIdentity.isSelfComponentId(it.componentId)
        }
        val apps = event.apps.filterNot {
            InstallerSelfIdentity.isSelfComponentId(it.id)
        }
        val appFailures = event.appFailures.filterKeys {
            !InstallerSelfIdentity.isSelfComponentId(it)
        }
        val current = _snapshot.value
        if (current.state !in CATALOG_ACCEPTING_STATES &&
            !(allowSelectionConfirmed && current.state == InstallationSessionState.SELECTION_CONFIRMED)
        ) {
            fail(FailureCategory.UNKNOWN, reasonCode = "catalog_event_out_of_order")
            return
        }
        if (event.catalogVersion.isBlank() || event.keyId.isBlank() || event.signatureAlgorithm.isBlank()) {
            fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = "catalog_trust_evidence_missing")
            return
        }
        if (event.catalogRevision < _snapshot.value.catalogRevision) {
            fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = "catalog_rollback")
            return
        }
        if (event.signatureAlgorithm !in SUPPORTED_CATALOG_SIGNATURE_ALGORITHMS) {
            fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = "catalog_signature_algorithm_unsupported")
            return
        }
        when (val validation = ArtifactManifestValidator.validateCatalog(manifests)) {
            is ManifestValidation.Invalid -> {
                if (!(allowSelectionConfirmed && validation.reasonCode == "catalog_empty")) {
                    fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = validation.reasonCode)
                    return
                }
            }

            ManifestValidation.Valid -> Unit
        }
        manifests.forEach { manifest ->
            when (val plan = sourcePolicy.plan(manifest)) {
                is com.ninepointnine.helper.domain.artifact.SourcePlan.Rejected -> {
                    fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = plan.reasonCode)
                    return
                }

                is com.ninepointnine.helper.domain.artifact.SourcePlan.Accepted -> Unit
            }
        }
        val manifestDescriptors = manifests.map { it.toComponentDescriptor(current.device?.androidSdk) }
        val components = if (apps.isEmpty()) {
            manifestDescriptors.map { descriptor ->
                appFailures[descriptor.id]?.let { reason ->
                    descriptor.copy(status = catalogFailureStatus(reason), errorReason = reason)
                } ?: descriptor
            }
        } else {
            apps.map { app ->
                val manifest = manifestDescriptors.firstOrNull { it.id == app.id }
                val failure = appFailures[app.id]
                app.copy(
                    versionLabel = manifest?.versionLabel ?: app.versionLabel,
                    sizeLabel = manifest?.sizeLabel ?: app.sizeLabel,
                    compatibilityLabel = manifest?.compatibilityLabel ?: app.compatibilityLabel,
                    compatibilityState = manifest?.compatibilityState ?: app.compatibilityState,
                    status = failure?.let(::catalogFailureStatus) ?: app.status,
                    errorReason = failure ?: app.errorReason,
                )
            }
        }
        val componentIds = components.map { it.id }.toSet()
        val manifestIds = manifests.map { it.componentId }.toSet()
        if (
            componentIds.size != components.size ||
            components.none { it.id == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID } ||
            (!allowSelectionConfirmed && AuthorizationPlanFactory.DESKTOP_COMPONENT_ID !in manifestIds) ||
            manifests.any { it.componentId !in componentIds }
        ) {
            fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = "catalog_desktop_unavailable")
            return
        }
        val selectableIds = components.filter { component ->
            !isMandatory(component) && component.id in manifestIds && isSelectable(component)
        }.map { it.id }.toSet()
        val recommendedIds = components.filter { component ->
            !isMandatory(component) && component.required && component.id in selectableIds
        }.map { it.id }.toSet()
        val preservingSelection = allowSelectionConfirmed && current.state == InstallationSessionState.SELECTION_CONFIRMED
        val selectedBeforePreparation = selectedComponentIds(current)
        val failedDuringPreparation = if (preservingSelection) {
            selectedBeforePreparation.filterTo(mutableSetOf()) { componentId ->
                componentId !in manifestIds || componentId in appFailures
            }
        } else {
            emptySet()
        }
        val nextSelectedIds = if (allowSelectionConfirmed && current.state == InstallationSessionState.SELECTION_CONFIRMED) {
            // Keep the user's confirmed optional set even when one item failed
            // during preparation so the terminal result can explain that item.
            current.selectedOptionalComponentIds intersect componentIds
        } else {
            (current.selectedOptionalComponentIds intersect selectableIds) + recommendedIds
        }
        val next = current.copy(
            components = components,
            artifactManifests = manifests,
            catalogVersion = event.catalogVersion,
            catalogRevision = event.catalogRevision.coerceAtLeast(current.catalogRevision),
            catalogKeyId = event.keyId,
            catalogSignatureAlgorithm = event.signatureAlgorithm,
            selectedOptionalComponentIds = nextSelectedIds,
            currentComponentName = if (preservingSelection) {
                components.firstOrNull { isMandatory(it) || it.id in nextSelectedIds }?.displayName
            } else {
                null
            },
            progress = if (preservingSelection) current.progress else null,
            evidence = if (preservingSelection) current.evidence else SessionEvidence(),
            componentResults = if (preservingSelection) current.componentResults else emptyList(),
            failedComponentIds = if (preservingSelection) {
                current.failedComponentIds + failedDuringPreparation
            } else {
                emptySet()
            },
            checkpoint = if (preservingSelection) current.checkpoint else null,
            selectedSources = if (preservingSelection) current.selectedSources else emptyMap(),
            sourceFailures = if (preservingSelection) current.sourceFailures else emptyList(),
            archiveDownloads = if (preservingSelection) current.archiveDownloads else emptyMap(),
            archiveVerifications = if (preservingSelection) current.archiveVerifications else emptyMap(),
            apkExtractions = if (preservingSelection) current.apkExtractions else emptyMap(),
            failure = null,
        )
        publish(
            if (preservingSelection) {
                next.copy(
                    state = current.state,
                    componentProgress = current.componentProgress.filterKeys { it in nextSelectedIds ||
                        components.any { component -> isMandatory(component) && component.id == it }
                    },
                )
            } else {
                next.copy(componentProgress = emptyMap())
            },
            acceptedEventSequence,
        )
    }

    private fun handleSelectedCatalogResolved(event: InstallationSessionEvent.SelectedCatalogResolved) {
        handleCatalogResolved(
            InstallationSessionEvent.CatalogResolved(
                catalogVersion = event.catalogVersion,
                keyId = event.keyId,
                signatureAlgorithm = event.signatureAlgorithm,
                manifests = event.manifests,
                catalogRevision = event.catalogRevision,
                apps = event.apps,
                appFailures = event.appFailures,
            ),
            allowSelectionConfirmed = true,
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

    private fun handleDistributionConfigResolved(event: InstallationSessionEvent.DistributionConfigResolved) {
        val current = _snapshot.value
        if (current.state !in CATALOG_ACCEPTING_STATES) {
            fail(FailureCategory.UNKNOWN, reasonCode = "distribution_config_event_out_of_order")
            return
        }
        if (event.configVersion.isBlank() || event.keyId.isBlank() ||
            event.signatureAlgorithm !in SUPPORTED_CATALOG_SIGNATURE_ALGORITHMS ||
            event.catalogRevision < current.catalogRevision
        ) {
            fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = "distribution_config_metadata_invalid")
            return
        }
        val components = event.components
            .filter { it.status != ComponentStatus.UNLISTED }
            .filterNot { InstallerSelfIdentity.isSelfComponentId(it.id) }
        val ids = components.map { it.id }
        if (
            ids.size != ids.toSet().size ||
            components.none { it.id == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID } ||
            components.any { it.displayName.isBlank() }
        ) {
            fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = "distribution_config_components_invalid")
            return
        }
        val selectableIds = components.filterNot(::isMandatory).map { it.id }.toSet()
        val recommendedIds = components.filter { it.required && !isMandatory(it) }.map { it.id }.toSet()
        publish(
            current.copy(
                components = components,
                artifactManifests = emptyList(),
                catalogVersion = event.configVersion,
                catalogRevision = event.catalogRevision.coerceAtLeast(current.catalogRevision),
                catalogKeyId = event.keyId,
                catalogSignatureAlgorithm = event.signatureAlgorithm,
                selectedOptionalComponentIds =
                    (current.selectedOptionalComponentIds intersect selectableIds) + recommendedIds,
                currentComponentName = null,
                progress = null,
                failedComponentIds = emptySet(),
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
            markComponentFailure(
                current = current.copy(
                    sourceFailures = (current.sourceFailures + failureRecord).takeLast(MAX_SOURCE_FAILURE_RECORDS),
                ),
                componentId = event.componentId,
                phase = InstallPhase.FETCH,
                reasonCode = "all_sources_failed",
                retryable = event.retryable,
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

    private fun handleArtifactUnavailable(event: InstallationSessionEvent.ArtifactUnavailable) {
        val current = _snapshot.value
        if (current.state !in F2_PIPELINE_STATES && current.state != InstallationSessionState.VERIFYING_ARTIFACTS) {
            return
        }
        markComponentFailure(
            current = current,
            componentId = event.componentId,
            phase = InstallPhase.CHECK,
            reasonCode = event.reasonCode,
            retryable = false,
            sourceKind = event.sourceKind,
        )
    }

    private fun handleComponentFailed(event: InstallationSessionEvent.ComponentFailed) {
        val current = _snapshot.value
        if (current.state !in ACTIVE_INSTALL_STATES || event.componentId !in selectedComponentIds(current)) {
            return
        }
        markComponentFailure(
            current = current,
            componentId = event.componentId,
            phase = event.phase,
            reasonCode = event.reasonCode,
            retryable = event.retryable,
        )
    }

    private fun markComponentFailure(
        current: InstallationSessionSnapshot,
        componentId: String,
        phase: InstallPhase,
        reasonCode: String,
        retryable: Boolean,
        sourceKind: ArtifactSourceKind? = null,
    ) {
        if (componentId !in selectedComponentIds(current) || reasonCode.isBlank()) return
        val status = when {
            reasonCode.contains("certificate") -> ComponentStatus.APK_SIGNATURE_MISMATCH
            reasonCode.contains("archive") || reasonCode.contains("zip") -> ComponentStatus.ZIP_VALIDATION_FAILED
            reasonCode.contains("missing") -> ComponentStatus.DIRECTORY_MISSING
            else -> ComponentStatus.TEMPORARILY_UNAVAILABLE
        }
        val updatedProgress = current.componentProgress + (
            componentId to ComponentProgress(
                componentId = componentId,
                phase = phase,
                status = ComponentProgressStatus.FAILED,
                fraction = 0f,
                indeterminate = false,
            )
        )
        publish(
            current.copy(
                failedComponentIds = current.failedComponentIds + componentId,
                componentProgress = updatedProgress,
                components = current.components.map { item ->
                    if (item.id == componentId) item.copy(status = status, errorReason = reasonCode) else item
                },
                componentResults = buildComponentResults(current.evidence, current),
                sourceFailures = sourceKind?.let {
                    (current.sourceFailures + SourceFailureRecord(componentId, it, reasonCode, retryable))
                        .takeLast(MAX_SOURCE_FAILURE_RECORDS)
                } ?: current.sourceFailures,
            ),
            acceptedEventSequence,
        )
    }

    private fun handleComponentProgressUpdated(event: InstallationSessionEvent.ComponentProgressUpdated) {
        val current = _snapshot.value
        if (current.state !in ACTIVE_INSTALL_STATES) {
            return
        }
        if (event.componentId !in selectedComponentIds(current) || event.componentId in current.failedComponentIds) {
            return
        }
        val total = event.totalBytes.coerceAtLeast(0L)
        val written = event.bytesWritten.coerceIn(0L, total.takeIf { it > 0L } ?: Long.MAX_VALUE)
        val fraction = event.fraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            ?: total.takeIf { it > 0L }?.let { written.toFloat() / it.toFloat() }
        val status = event.status
        val updated = ComponentProgress(
            componentId = event.componentId,
            phase = event.phase,
            status = status,
            bytesWritten = written,
            totalBytes = total,
            fraction = if (status == ComponentProgressStatus.COMPLETED) 1f else fraction,
            indeterminate = status == ComponentProgressStatus.RUNNING &&
                event.indeterminate && fraction == null,
        )
        val progressByComponent = current.componentProgress + (event.componentId to updated)
        val selectedIds = selectedComponentIds(current)
        val completedCount = progressByComponent.values.count {
            it.componentId in selectedIds && it.status == ComponentProgressStatus.COMPLETED
        }
        val running = progressByComponent.values.firstOrNull {
            it.componentId in selectedIds && it.status == ComponentProgressStatus.RUNNING
        }
        val aggregateFraction = if (selectedIds.isEmpty()) {
            null
        } else {
            val completed = completedCount.toFloat()
            val activeFraction = running?.fraction ?: 0f
            ((completed + activeFraction) / selectedIds.size.toFloat()).coerceIn(0f, 1f)
        }
        publish(
            current.copy(
                componentProgress = progressByComponent,
                currentComponentName = componentName(current, event.componentId),
                progress = current.progress?.copy(
                    completedCount = completedCount,
                    totalCount = selectedIds.size,
                    fraction = aggregateFraction,
                    indeterminate = running?.indeterminate ?: false,
                ),
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
            componentProgress = markComponents(current, InstallPhase.FETCH, ComponentProgressStatus.RUNNING),
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
            componentProgress = markComponents(current, InstallPhase.CHECK, ComponentProgressStatus.RUNNING),
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
                current.archiveVerifications + verifications.associateBy { it.componentId }
            },
            componentProgress = markComponents(current, InstallPhase.CHECK, ComponentProgressStatus.RUNNING),
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
        if (current.artifactManifests.isEmpty() &&
            successfulComponentIds(current).isNotEmpty() &&
            !isValidLegacyApkExtraction(event)
        ) {
            fail(FailureCategory.ARCHIVE, reasonCode = "apk_identity_missing")
            return
        }
        transition(
            state = InstallationSessionState.VERIFYING_ARTIFACTS,
            progress = progress(0.55f, indeterminate = true),
            apkExtractions = if (extractions.isEmpty()) current.apkExtractions else extractions.associateBy { it.componentId },
            componentProgress = markComponents(current, InstallPhase.CHECK, ComponentProgressStatus.RUNNING),
        )
    }

    private fun handleArtifactsVerified(event: InstallationSessionEvent.ArtifactsVerified) {
        if (!requireState(InstallationSessionState.VERIFYING_ARTIFACTS, "artifact_event_out_of_order")) return
        val current = _snapshot.value
        val verified = validateChecks(event.checks, successfulComponentIds(current))
        if (verified == null) {
            fail(FailureCategory.VERIFICATION, reasonCode = "artifact_verification_failed")
            return
        }
        val verifications = event.verifications
        if (current.artifactManifests.isNotEmpty() && !validateArtifactVerifications(verifications, current)) {
            fail(FailureCategory.VERIFICATION, reasonCode = "artifact_verification_evidence_invalid")
            return
        }
        val evidence = current.evidence.copy(
            artifactsVerified = current.evidence.artifactsVerified + verified,
            artifactVerifications = if (verifications.isEmpty()) {
                current.evidence.artifactVerifications
            } else {
                current.evidence.artifactVerifications + verifications.associateBy { it.componentId }
            },
        )
        transition(
            state = InstallationSessionState.VERIFYING_ARTIFACTS,
            evidence = evidence,
            componentResults = buildComponentResults(evidence, current),
            progress = progress(0.6f, indeterminate = false),
            componentProgress = markComponents(current, InstallPhase.CHECK, ComponentProgressStatus.COMPLETED),
        )
    }

    private fun handleInstallationStarted(componentIds: List<String>) {
        if (!requireState(InstallationSessionState.VERIFYING_ARTIFACTS, "installation_start_out_of_order")) return
        val current = _snapshot.value
        val expected = successfulComponentIds(current)
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
            componentProgress = markComponents(current, InstallPhase.SEND, ComponentProgressStatus.RUNNING),
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
        val expected = successfulComponentIds(snapshot)
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
        val expected = successfulComponentIds(snapshot).filterTo(mutableSetOf()) { id ->
            snapshot.artifactManifests.firstOrNull { it.componentId == id }?.localOnly != true
        }
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
        val expected = successfulComponentIds(snapshot).filterTo(mutableSetOf()) { id ->
            snapshot.artifactManifests.firstOrNull { it.componentId == id }?.localOnly != true
        }
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
        val expected = successfulComponentIds(snapshot).filterTo(mutableSetOf()) { id ->
            snapshot.artifactManifests.firstOrNull { it.componentId == id }?.localOnly != true
        }
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
        val expected = successfulComponentIds(snapshot)
        if (verifications.size != verifications.map { it.componentId }.toSet().size) return false
        if (verifications.map { it.componentId }.toSet() != expected) return false
        val manifests = snapshot.artifactManifests.associateBy { it.componentId }
        return verifications.all { verification ->
            val manifest = manifests[verification.componentId] ?: return@all false
            verification.archiveDeleted &&
                snapshot.selectedSources[verification.componentId] == verification.sourceKind &&
                (if (manifest.localOnly) {
                    verification.localDownload &&
                        verification.sourceKind == ArtifactSourceKind.LOCAL_DOWNLOAD &&
                        verification.archiveSizeBytes == 0L &&
                        verification.archiveSha256.isBlank()
                } else {
                    verification.archiveSizeBytes == manifest.archiveSizeBytes &&
                        verification.archiveSha256.equals(manifest.archiveSha256, ignoreCase = true)
                }) &&
                verification.apkSizeBytes == manifest.apkSizeBytes &&
                verification.apkSha256.equals(manifest.apkSha256, ignoreCase = true) &&
                verification.packageName == manifest.packageName &&
                verification.apkVersion == manifest.apkVersion &&
                verification.certificateSha256.equals(manifest.certificateSha256, ignoreCase = true)
        }
    }

    private fun validateAuthorizationEvidence(
        evidence: List<com.ninepointnine.helper.domain.device.AuthorizationActionEvidence>,
        snapshot: InstallationSessionSnapshot,
        expectedIds: Set<String> = snapshot.evidence.installed - snapshot.failedComponentIds,
    ): Boolean {
        if (expectedIds.isEmpty()) return evidence.isEmpty()
        val manifests = snapshot.artifactManifests.filter { it.componentId in expectedIds }
        if (manifests.size != expectedIds.size) return false
        val plan = when (val result = AuthorizationPlanFactory.createForManifests(manifests)) {
            is AuthorizationPlanBuildResult.Ready -> result.plan
            is AuthorizationPlanBuildResult.Rejected -> return false
        }
        return AuthorizationPlanFactory.validateEvidence(plan, evidence)
    }

    private fun validateAvailabilityEvidence(
        evidence: List<DeviceAvailabilityEvidence>,
        snapshot: InstallationSessionSnapshot,
        expectedIds: Set<String> = snapshot.evidence.configured - snapshot.failedComponentIds,
    ): Boolean {
        if (evidence.size != expectedIds.size || evidence.map { it.componentId }.toSet() != expectedIds) {
            return false
        }
        val manifests = snapshot.artifactManifests.associateBy { it.componentId }
        return evidence.all { item ->
            val manifest = manifests[item.componentId] ?: return@all false
            val isLaunchTarget = item.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID
            item.packageName == manifest.packageName &&
                item.version == manifest.apkVersion &&
                item.installedArchiveVerified &&
                item.launchAttempted == isLaunchTarget &&
                if (isLaunchTarget) {
                    item.launcherResolved &&
                        item.processRunning &&
                        item.requiredServiceBound == true
                } else {
                    !item.launcherResolved &&
                        !item.processRunning &&
                        // Optional components are deliberately not launched during
                        // first install; their proof is package identity/install
                        // presence only, so no service-binding result is expected.
                        item.requiredServiceBound == null
                }
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
        snapshot.components.filter { isMandatory(it) || it.id in snapshot.selectedOptionalComponentIds }
            .map { it.id }
            .toSet()

    private fun successfulComponentIds(snapshot: InstallationSessionSnapshot): Set<String> =
        selectedComponentIds(snapshot) - snapshot.failedComponentIds

    private fun componentName(snapshot: InstallationSessionSnapshot, componentId: String): String? =
        snapshot.components.firstOrNull { it.id == componentId }?.displayName

    private fun handleInstallationCompleted(event: InstallationSessionEvent.InstallationCompleted) {
        if (!requireState(InstallationSessionState.INSTALLING, "installation_event_out_of_order")) return
        val current = _snapshot.value
        val installed = validateChecks(event.checks, successfulComponentIds(current))
        if (installed == null) {
            fail(FailureCategory.INSTALLATION, reasonCode = "installation_evidence_missing")
            return
        }
        if (current.artifactManifests.isNotEmpty() && !validateInstallationEvidence(event.evidence, current, successfulComponentIds(current))) {
            fail(FailureCategory.INSTALLATION, reasonCode = "installation_detail_invalid")
            return
        }
        val evidence = current.evidence.copy(
            installed = current.evidence.installed + installed,
            installation = current.evidence.installation + event.evidence.associateBy { it.componentId },
        )
        transition(
            state = InstallationSessionState.AUTHORIZING,
            evidence = evidence,
            componentResults = buildComponentResults(evidence, current),
            progress = progress(0.78f, indeterminate = true),
            componentProgress = markComponents(current, InstallPhase.SEND, ComponentProgressStatus.COMPLETED)
                .markPhase(InstallPhase.CONFIGURE, ComponentProgressStatus.RUNNING),
        )
    }

    private fun validateInstallationEvidence(
        evidence: List<InstalledArtifactEvidence>,
        snapshot: InstallationSessionSnapshot,
        expectedIds: Set<String> = successfulComponentIds(snapshot),
    ): Boolean {
        if (evidence.size != expectedIds.size || evidence.map { it.componentId }.toSet() != expectedIds) return false
        val manifests = snapshot.artifactManifests.associateBy { it.componentId }
        return evidence.all { item ->
            val manifest = manifests[item.componentId] ?: return@all false
            item.packageName == manifest.packageName &&
                item.version == manifest.apkVersion &&
                item.apkSizeBytes == manifest.apkSizeBytes &&
                item.apkSha256.equals(manifest.apkSha256, ignoreCase = true) &&
                item.certificateSha256.equals(manifest.certificateSha256, ignoreCase = true)
        }
    }

    private fun handleAuthorizationCompleted(event: InstallationSessionEvent.AuthorizationCompleted) {
        if (!requireState(InstallationSessionState.AUTHORIZING, "authorization_event_out_of_order")) return
        val current = _snapshot.value
        val configured = validateChecks(event.checks, current.evidence.installed - current.failedComponentIds)
        if (configured == null) {
            fail(FailureCategory.CONFIGURATION, reasonCode = "configuration_evidence_missing")
            return
        }
        if (current.artifactManifests.isNotEmpty() && !validateAuthorizationEvidence(event.evidence, current, configured)) {
            fail(FailureCategory.CONFIGURATION, reasonCode = "authorization_action_evidence_invalid")
            return
        }
        val evidence = current.evidence.copy(
            configured = current.evidence.configured + configured,
            authorizationActions = current.evidence.authorizationActions + event.evidence,
        )
        transition(
            state = InstallationSessionState.VERIFYING_DEVICE,
            evidence = evidence,
            componentResults = buildComponentResults(evidence, current),
            progress = progress(0.9f, indeterminate = true),
            componentProgress = markComponents(current, InstallPhase.CONFIGURE, ComponentProgressStatus.COMPLETED)
                .markPhase(InstallPhase.VERIFY, ComponentProgressStatus.RUNNING),
        )
    }

    private fun handleDeviceVerified(event: InstallationSessionEvent.DeviceVerified) {
        if (!requireState(InstallationSessionState.VERIFYING_DEVICE, "device_verification_out_of_order")) return
        val current = _snapshot.value
        val available = validateChecks(event.checks, current.evidence.configured - current.failedComponentIds)
        if (available == null) {
            fail(FailureCategory.VERIFICATION, reasonCode = "availability_evidence_missing")
            return
        }
        if (current.artifactManifests.isNotEmpty() && !validateAvailabilityEvidence(event.evidence, current, available)) {
            fail(FailureCategory.VERIFICATION, reasonCode = "availability_detail_invalid")
            return
        }
        val evidence = current.evidence.copy(
            available = current.evidence.available + available,
            availability = current.evidence.availability + event.evidence.associateBy { it.componentId },
        )
        val results = buildComponentResults(evidence, current)
        val completeEvidence = hasCompleteSuccessEvidence(evidence, current)
        val terminalState = when {
            current.failedComponentIds.isEmpty() && completeEvidence -> InstallationSessionState.SUCCEEDED
            current.failedComponentIds.isNotEmpty() && completeEvidence -> InstallationSessionState.COMPLETED_WITH_ERRORS
            else -> {
                fail(FailureCategory.VERIFICATION, reasonCode = "success_evidence_incomplete")
                return
            }
        }
        transition(
            state = terminalState,
            evidence = evidence,
            componentResults = results,
            progress = progress(1.0f, indeterminate = false, completedCount = selectedComponents(current).size),
            checkpoint = null,
            componentProgress = markComponents(current, InstallPhase.VERIFY, ComponentProgressStatus.COMPLETED),
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
                        maintenance = current.maintenance.copy(
                            activeAction = null,
                            lastAction = current.maintenance.activeAction?.let { actionId ->
                                MaintenanceActionRecord(
                                    actionId = actionId,
                                    status = MaintenanceActionStatus.FAILED,
                                    reasonCode = "device_disconnected",
                                    retryable = true,
                                )
                            } ?: current.maintenance.lastAction,
                        ),
                    ),
                    acceptedEventSequence,
                )
            } else {
                fail(FailureCategory.CONNECTION, reasonCode = "unknown_device_disconnected")
            }
            return
        }
        if (current.state == InstallationSessionState.CONNECTING) {
            if (current.maintenanceReconnectPending) {
                returnToMaintenanceDisconnected(current, reasonCode = event.reasonCode)
                return
            }
            if (current.installationReconnectPending) {
                returnToInstallationPaused(current, reasonCode = event.reasonCode)
                return
            }
            fail(
                category = FailureCategory.CONNECTION,
                reasonCode = event.reasonCode,
                deviceOverride = current.device?.copy(connectionStatus = DeviceConnectionStatus.DISCONNECTED),
            )
            return
        }
        if (current.state == InstallationSessionState.DISCOVERING && current.maintenanceReconnectPending) {
            returnToMaintenanceDisconnected(current, reasonCode = event.reasonCode)
            return
        }
        if (current.state == InstallationSessionState.DISCOVERING && current.installationReconnectPending) {
            returnToInstallationPaused(current, reasonCode = event.reasonCode)
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

    private fun handleMaintenanceActionCompleted(event: InstallationSessionEvent.MaintenanceActionCompleted) {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.MAINTENANCE ||
            current.maintenance.activeAction != event.actionId ||
            event.resultCode.isBlank()
        ) {
            return
        }
        publish(
            current.copy(
                maintenance = current.maintenance.copy(
                    activeAction = null,
                    lastAction = MaintenanceActionRecord(
                        actionId = event.actionId,
                        status = MaintenanceActionStatus.SUCCEEDED,
                        resultCode = event.resultCode,
                    ),
                ),
            ),
            acceptedEventSequence,
        )
    }

    private fun handleMaintenanceActionFailed(event: InstallationSessionEvent.MaintenanceActionFailed) {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.MAINTENANCE ||
            current.maintenance.activeAction != event.actionId ||
            event.reasonCode.isBlank()
        ) {
            return
        }
        publish(
            current.copy(
                maintenance = current.maintenance.copy(
                    activeAction = null,
                    lastAction = MaintenanceActionRecord(
                        actionId = event.actionId,
                        status = MaintenanceActionStatus.FAILED,
                        reasonCode = event.reasonCode,
                        retryable = event.retryable,
                    ),
                ),
            ),
            acceptedEventSequence,
        )
    }

    private fun handleMaintenanceApplicationActionCompleted(
        event: InstallationSessionEvent.MaintenanceApplicationActionCompleted,
    ) {
        val current = _snapshot.value
        val active = current.maintenance.applicationAction
        if (current.state != InstallationSessionState.MAINTENANCE ||
            active?.componentId != event.componentId ||
            active.actionId != event.actionId ||
            event.resultCode.isBlank()
        ) {
            return
        }
        publish(
            current.copy(
                maintenance = current.maintenance.copy(
                    applicationAction = active.copy(
                        status = MaintenanceActionStatus.SUCCEEDED,
                        resultCode = event.resultCode,
                        reasonCode = null,
                        retryable = false,
                    ),
                ),
            ),
            acceptedEventSequence,
        )
    }

    private fun handleMaintenanceApplicationActionFailed(
        event: InstallationSessionEvent.MaintenanceApplicationActionFailed,
    ) {
        val current = _snapshot.value
        val active = current.maintenance.applicationAction
        if (current.state != InstallationSessionState.MAINTENANCE ||
            active?.componentId != event.componentId ||
            active.actionId != event.actionId ||
            event.reasonCode.isBlank()
        ) {
            return
        }
        publish(
            current.copy(
                maintenance = current.maintenance.copy(
                    applicationAction = active.copy(
                        status = MaintenanceActionStatus.FAILED,
                        resultCode = null,
                        reasonCode = event.reasonCode,
                        retryable = event.retryable,
                    ),
                ),
            ),
            acceptedEventSequence,
        )
    }

    private fun handleMaintenanceApplicationDetailsResolved(
        event: InstallationSessionEvent.MaintenanceApplicationDetailsResolved,
    ) {
        val current = _snapshot.value
        val active = current.maintenance.applicationAction
        if (current.state != InstallationSessionState.MAINTENANCE ||
            active?.componentId != event.details.componentId ||
            active.actionId != MaintenanceApplicationActionId.DETAILS
        ) {
            return
        }
        publish(
            current.copy(
                maintenance = current.maintenance.copy(
                    applicationDetails = event.details,
                    applicationAction = active.copy(
                        status = MaintenanceActionStatus.SUCCEEDED,
                        resultCode = "application_details_ready",
                        reasonCode = null,
                        retryable = false,
                    ),
                ),
            ),
            acceptedEventSequence,
        )
    }

    private fun handleMaintenanceAuthorizationCheckStarted(
        event: InstallationSessionEvent.MaintenanceAuthorizationCheckStarted,
    ) {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.MAINTENANCE ||
            current.maintenance.activeAction != MaintenanceActionId.REPAIR_CONFIGURATION
        ) {
            return
        }
        publish(
            current.copy(
                maintenance = current.maintenance.copy(
                    authorization = MaintenanceAuthorizationSnapshot(
                        state = MaintenanceAuthorizationFlowState.CHECKING,
                        currentComponentId = event.componentIds.firstOrNull(),
                    ),
                ),
            ),
            acceptedEventSequence,
        )
    }

    private fun handleMaintenanceAuthorizationCheckProgress(
        event: InstallationSessionEvent.MaintenanceAuthorizationCheckProgress,
    ) {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.MAINTENANCE ||
            current.maintenance.activeAction != MaintenanceActionId.REPAIR_CONFIGURATION
        ) {
            return
        }
        val existing = current.maintenance.authorization.applications
            .filterNot { it.componentId == event.componentId }
        publish(
            current.copy(
                maintenance = current.maintenance.copy(
                    authorization = current.maintenance.authorization.copy(
                        state = MaintenanceAuthorizationFlowState.CHECKING,
                        currentComponentId = event.componentId,
                        applications = existing + event.status,
                    ),
                ),
            ),
            acceptedEventSequence,
        )
    }

    private fun handleMaintenanceAuthorizationChecked(
        event: InstallationSessionEvent.MaintenanceAuthorizationChecked,
    ) {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.MAINTENANCE ||
            current.maintenance.activeAction != MaintenanceActionId.REPAIR_CONFIGURATION
        ) {
            return
        }
        publish(
            current.copy(
                maintenance = current.maintenance.copy(
                    authorization = MaintenanceAuthorizationSnapshot(
                        state = MaintenanceAuthorizationFlowState.READY,
                        currentComponentId = null,
                        applications = event.applications,
                    ),
                ),
            ),
            acceptedEventSequence,
        )
    }

    private fun handleMaintenanceApplicationsResolved(
        event: InstallationSessionEvent.MaintenanceApplicationsResolved,
    ) {
        val current = _snapshot.value
        val expectedPackages = buildMap {
            current.artifactManifests.filterNot { InstallerSelfIdentity.isSelfComponentId(it.componentId) }
                .forEach { put(it.componentId, it.packageName) }
            current.maintenance.availableManifests.filterNot { InstallerSelfIdentity.isSelfComponentId(it.componentId) }
                .forEach { put(it.componentId, it.packageName) }
            current.maintenance.managedApplications.filterNot { InstallerSelfIdentity.isSelfComponentId(it.componentId) }
                .forEach { put(it.componentId, it.packageName) }
            if (isEmpty()) {
                current.components.forEach { descriptor ->
                    when (descriptor.id) {
                        AuthorizationPlanFactory.DESKTOP_COMPONENT_ID ->
                            put(descriptor.id, AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME)
                        AuthorizationPlanFactory.LYRICS_COMPONENT_ID ->
                            put(descriptor.id, AuthorizationPlanFactory.LYRICS_PACKAGE_NAME)
                        AuthorizationPlanFactory.FILE_MANAGER_COMPONENT_ID ->
                            put(descriptor.id, AuthorizationPlanFactory.FILE_MANAGER_PACKAGE_NAME)
                    }
                }
            }
        }
        if (current.state != InstallationSessionState.MAINTENANCE ||
            current.maintenance.activeAction != MaintenanceActionId.MANAGE_APPS ||
            event.applications.any { application ->
                val expectedPackage = expectedPackages[application.componentId]
                application.componentId !in expectedPackages ||
                    (expectedPackage != null && application.packageName != expectedPackage)
            }
        ) {
            if (
                current.state == InstallationSessionState.MAINTENANCE &&
                current.maintenance.activeAction == MaintenanceActionId.MANAGE_APPS
            ) {
                failMaintenanceAction("maintenance_applications_invalid", retryable = false)
            }
            return
        }
        if (
            event.applications.map { it.componentId }.toSet().size != event.applications.size ||
            event.applications.map { it.componentId }.toSet() != expectedPackages.keys
        ) {
            failMaintenanceAction("maintenance_applications_incomplete", retryable = false)
            return
        }
        publish(
            current.copy(
                maintenance = current.maintenance.copy(managedApplications = event.applications),
            ),
            acceptedEventSequence,
        )
    }

    private fun handleMaintenanceCatalogRefreshed(event: InstallationSessionEvent.MaintenanceCatalogRefreshed) {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.MAINTENANCE ||
            current.maintenance.activeAction != MaintenanceActionId.CHECK_UPDATES
        ) {
            return
        }
        if (
            event.catalogVersion.isBlank() ||
            event.keyId.isBlank() ||
            event.signatureAlgorithm !in SUPPORTED_CATALOG_SIGNATURE_ALGORITHMS
        ) {
            failMaintenanceAction("maintenance_catalog_metadata_invalid", retryable = false)
            return
        }
        val revisionFloor = maxOf(
            current.catalogRevision,
            current.maintenance.availableCatalogRevision,
        )
        if (event.catalogRevision < revisionFloor ||
            (event.catalogRevision > 0L && event.catalogRevision == current.catalogRevision &&
                current.catalogVersion != null && current.catalogVersion != event.catalogVersion) ||
            (event.catalogRevision > 0L && event.catalogRevision == current.maintenance.availableCatalogRevision &&
                current.maintenance.availableCatalogVersion != null &&
                current.maintenance.availableCatalogVersion != event.catalogVersion)
        ) {
            failMaintenanceAction("maintenance_catalog_rollback", retryable = false)
            return
        }
        when (val validation = ArtifactManifestValidator.validateCatalog(event.manifests)) {
            is ManifestValidation.Invalid -> {
                failMaintenanceAction(validation.reasonCode, retryable = false)
                return
            }
            ManifestValidation.Valid -> Unit
        }
        if (event.manifests.any { sourcePolicy.plan(it) is com.ninepointnine.helper.domain.artifact.SourcePlan.Rejected }) {
            failMaintenanceAction("maintenance_catalog_source_invalid", retryable = false)
            return
        }
        val installableManifests = event.manifests.filterNot {
            InstallerSelfIdentity.isSelfComponentId(it.componentId)
        }
        val manifestDescriptors = installableManifests.map { manifest ->
            val descriptor = manifest.toComponentDescriptor(current.device?.androidSdk)
            when {
                descriptor.compatibilityState == ComponentCompatibility.UNSUPPORTED ->
                    descriptor.copy(status = ComponentStatus.CLIENT_CAPABILITY_INSUFFICIENT, errorReason = "component_incompatible")
                current.artifactManifests.none { it.componentId == manifest.componentId } ->
                    descriptor.copy(status = ComponentStatus.NEW)
                current.artifactManifests.firstOrNull { it.componentId == manifest.componentId }?.let { old ->
                    old.version != manifest.version ||
                        !old.apkSha256.equals(manifest.apkSha256, ignoreCase = true)
                } == true -> descriptor.copy(status = ComponentStatus.UPDATE_AVAILABLE)
                else -> descriptor
            }
        }
        val components = if (event.apps.isEmpty()) {
            manifestDescriptors
        } else {
            val manifestsById = manifestDescriptors.associateBy { it.id }
            event.apps.filterNot { InstallerSelfIdentity.isSelfComponentId(it.id) }.map { app ->
                manifestsById[app.id]?.let { manifest ->
                    app.copy(
                        versionLabel = manifest.versionLabel,
                        sizeLabel = manifest.sizeLabel,
                        compatibilityLabel = manifest.compatibilityLabel,
                        compatibilityState = manifest.compatibilityState,
                        status = manifest.status,
                        errorReason = manifest.errorReason ?: app.errorReason,
                    )
                } ?: app.copy(
                    status = event.appFailures[app.id]?.let(::catalogFailureStatus)
                        ?: app.status,
                    errorReason = event.appFailures[app.id] ?: app.errorReason,
                )
            }
        }
        val componentIds = components.map { it.id }.toSet()
        if (componentIds.size != components.size ||
            components.none { it.id == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID } ||
            installableManifests.any { it.componentId !in componentIds }
        ) {
            failMaintenanceAction("maintenance_catalog_components_invalid", retryable = false)
            return
        }
        val selectableIds = components.filterNot(::isMandatory)
            .filter { isSelectable(it) }
            .map { it.id }.toSet()
        val availableIds = components.map { it.id }.toSet()
        val installedIds = current.evidence.installed + current.maintenance.managedApplications
            .filter { it.installed }
            .map { it.componentId }
        val retainedCurrentManifests = current.artifactManifests.filterNot {
            InstallerSelfIdentity.isSelfComponentId(it.componentId)
        }.filter {
            it.componentId in availableIds || it.componentId in installedIds
        }
        val currentIds = retainedCurrentManifests.map { it.componentId }.toSet()
        val retainedUnlistedIds = current.components
            .filter { it.id !in availableIds && it.id in installedIds }
            .map { it.id }
            .toSet()
        val unlisted = buildList {
            current.components
                .filter { it.id !in availableIds && it.id in installedIds }
                .forEach { add(it.copy(status = ComponentStatus.UNLISTED, errorReason = "unlisted")) }
            current.maintenance.managedApplications
                .filter { it.installed && it.componentId !in availableIds && it.componentId !in retainedUnlistedIds }
                .sortedBy { it.componentId }
                .forEach { application ->
                    add(
                        ComponentDescriptor(
                            id = application.componentId,
                            displayName = application.componentId,
                            required = false,
                            status = ComponentStatus.UNLISTED,
                            errorReason = "unlisted",
                        ),
                    )
                }
        }.distinctBy { it.id }
        val mergedComponents = components + unlisted
        publish(
            current.copy(
                maintenance = current.maintenance.copy(
                    availableManifests = event.manifests,
                    availableCatalogVersion = event.catalogVersion,
                    availableCatalogRevision = event.catalogRevision,
                    availableCatalogKeyId = event.keyId,
                    availableCatalogSignatureAlgorithm = event.signatureAlgorithm,
                    updateStatuses = event.updateStatuses,
                ),
                components = mergedComponents,
                artifactManifests = if (currentIds.isEmpty()) installableManifests else retainedCurrentManifests,
                catalogVersion = if (currentIds.isEmpty()) event.catalogVersion else current.catalogVersion,
                catalogRevision = if (currentIds.isEmpty()) event.catalogRevision else current.catalogRevision,
                catalogKeyId = if (currentIds.isEmpty()) event.keyId else current.catalogKeyId,
                catalogSignatureAlgorithm = if (currentIds.isEmpty()) event.signatureAlgorithm else current.catalogSignatureAlgorithm,
                selectedOptionalComponentIds = current.selectedOptionalComponentIds.filterTo(mutableSetOf()) {
                    it in selectableIds
                },
            ),
            acceptedEventSequence,
        )
    }

    private fun failMaintenanceAction(reasonCode: String, retryable: Boolean) {
        val current = _snapshot.value
        val actionId = current.maintenance.activeAction ?: return
        publish(
            current.copy(
                maintenance = current.maintenance.copy(
                    activeAction = null,
                    lastAction = MaintenanceActionRecord(
                        actionId = actionId,
                        status = MaintenanceActionStatus.FAILED,
                        reasonCode = reasonCode,
                        retryable = retryable,
                    ),
                ),
            ),
            acceptedEventSequence,
        )
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

    /** Returns a failed reconnect attempt to the persisted maintenance boundary. */
    private fun returnToMaintenanceDisconnected(
        current: InstallationSessionSnapshot,
        reasonCode: String? = null,
    ) {
        startNewGeneration(
            current.copy(
                state = InstallationSessionState.MAINTENANCE,
                device = current.device?.copy(connectionStatus = DeviceConnectionStatus.DISCONNECTED),
                discoveredDevices = emptyList(),
                failure = reasonCode?.let {
                    SessionFailure(
                        category = FailureCategory.CONNECTION,
                        retryable = true,
                        reasonCode = it,
                    )
                },
                checkpoint = null,
                maintenance = current.maintenance.copy(activeAction = null),
                maintenanceReconnectPending = false,
                installationReconnectPending = false,
            ),
        )
    }

    /** Returns a failed install reconnect to its checkpoint without rebuilding the task. */
    private fun returnToInstallationPaused(
        current: InstallationSessionSnapshot,
        reasonCode: String,
    ) {
        val checkpoint = current.checkpoint
        startNewGeneration(
            current.copy(
                state = InstallationSessionState.PAUSED,
                device = current.device?.copy(connectionStatus = DeviceConnectionStatus.DISCONNECTED),
                discoveredDevices = emptyList(),
                failure = SessionFailure(
                    category = FailureCategory.CONNECTION,
                    retryable = true,
                    reasonCode = reasonCode,
                ),
                checkpoint = checkpoint,
                maintenanceReconnectPending = false,
                installationReconnectPending = false,
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
        componentProgress: Map<String, ComponentProgress> = _snapshot.value.componentProgress,
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
            componentProgress = componentProgress,
            evidence = evidence,
            componentResults = componentResults,
            checkpoint = checkpoint,
            selectedSources = selectedSources,
            archiveDownloads = archiveDownloads,
            archiveVerifications = archiveVerifications,
            apkExtractions = apkExtractions,
            failure = null,
        )
        publish(
            if (state in setOf(
                    InstallationSessionState.SUCCEEDED,
                    InstallationSessionState.COMPLETED_WITH_ERRORS,
                )
            ) next else withCheckpoint(next),
            acceptedEventSequence,
        )
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

    private fun markComponents(
        snapshot: InstallationSessionSnapshot,
        phase: InstallPhase,
        status: ComponentProgressStatus,
    ): Map<String, ComponentProgress> = selectedComponents(snapshot).associate { component ->
        val previous = snapshot.componentProgress[component.id]
        if (component.id in snapshot.failedComponentIds) {
            component.id to (previous ?: ComponentProgress(
                componentId = component.id,
                phase = phase,
                status = ComponentProgressStatus.FAILED,
                indeterminate = false,
            ))
        } else {
        component.id to ComponentProgress(
            componentId = component.id,
            phase = phase,
            status = status,
            bytesWritten = if (status == ComponentProgressStatus.COMPLETED) {
                previous?.totalBytes ?: 0L
            } else {
                previous?.bytesWritten ?: 0L
            },
            totalBytes = previous?.totalBytes ?: 0L,
            fraction = if (status == ComponentProgressStatus.COMPLETED) 1f else previous?.fraction,
            indeterminate = status == ComponentProgressStatus.RUNNING && previous?.fraction == null,
        )
        }
    }

    private fun Map<String, ComponentProgress>.markPhase(
        phase: InstallPhase,
        status: ComponentProgressStatus,
    ): Map<String, ComponentProgress> = mapValues { (componentId, previous) ->
        if (previous.status == ComponentProgressStatus.FAILED) previous else previous.copy(
                componentId = componentId,
                phase = phase,
                status = status,
                fraction = if (status == ComponentProgressStatus.COMPLETED) 1f else null,
                indeterminate = status == ComponentProgressStatus.RUNNING,
            )
    }

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
            val manifestIds = snapshot.artifactManifests.map { it.componentId }.toSet()
            val componentIds = components.map { it.id }.toSet()
            if (!manifestIds.all { it in componentIds }) {
                return "catalog_component_mapping_invalid"
            }
            val device = snapshot.device ?: return "device_not_confirmed"
            val androidSdk = device.androidSdk ?: return "device_android_sdk_missing"
            val requiredCapabilities = setOf(
                com.ninepointnine.helper.domain.device.DeviceCapability.ADB_TCP,
                com.ninepointnine.helper.domain.device.DeviceCapability.IDENTITY_READ,
            )
            if (!device.capabilities.containsAll(requiredCapabilities)) {
                return "device_capability_missing"
            }
            val selectedIds = selectedComponents(snapshot).map { it.id }.toSet()
            val successfulSelectedIds = selectedIds - snapshot.failedComponentIds
            if (!successfulSelectedIds.all { it in manifestIds }) return "component_unavailable"
            val incompatible = snapshot.artifactManifests.any { manifest ->
                manifest.componentId in successfulSelectedIds &&
                    (androidSdk < manifest.compatibility.minAndroidSdk ||
                        manifest.compatibility.maxAndroidSdk?.let { androidSdk > it } == true)
            }
            if (incompatible) return "component_incompatible"
        }
        if (components.any { it.id.isBlank() || it.displayName.isBlank() }) return "component_identity_missing"
        if (components.map { it.id }.toSet().size != components.size) return "component_identity_duplicate"

        val desktop = components.firstOrNull { it.id == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID }
        if (desktop == null) return "required_components_missing"
        if (!desktop.required) return "required_component_unlocked"
        // Cloud controls installPolicy; multiple required apps are valid. The
        // desktop entry remains the only mandatory core invariant.

        val selectableIds = components.filterNot(::isMandatory).map { it.id }.toSet()
        if (!snapshot.selectedOptionalComponentIds.all { it in selectableIds }) return "unknown_component"

        val selected = selectedComponents(snapshot)
        if (selected.isEmpty()) return "required_components_missing"
        if (snapshot.artifactManifests.isEmpty()) {
            if (selected.any { component -> !isSelectable(component) }) {
                return "component_unavailable"
            }
        } else if (selected.any { component ->
                component.versionLabel.isNullOrBlank() ||
                    component.sizeLabel.isNullOrBlank() ||
                    component.compatibilityLabel.isNullOrBlank()
            }
        ) {
            return "component_metadata_incomplete"
        }
        return null
    }

    private fun validateChecks(
        checks: List<ComponentCheck>,
        expected: Set<String> = successfulComponentIds(_snapshot.value),
    ): Set<String>? {
        if (checks.size != checks.map { it.componentId }.toSet().size) return null
        if (expected.isEmpty()) return if (checks.isEmpty()) emptySet() else null
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
        snapshot.components.filter { isMandatory(it) || it.id in snapshot.selectedOptionalComponentIds }

    private fun isMandatory(component: ComponentDescriptor): Boolean =
        component.id == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID

    private fun isSelectable(component: ComponentDescriptor): Boolean = component.status in setOf(
        ComponentStatus.AVAILABLE,
        ComponentStatus.NEW,
        ComponentStatus.UPDATE_AVAILABLE,
        ComponentStatus.READING,
        // The remote folder is only one source. A component marked missing
        // may still be satisfied by a verified APK in public Download.
        ComponentStatus.DIRECTORY_MISSING,
    ) && component.compatibilityState != ComponentCompatibility.UNSUPPORTED

    private fun catalogFailureStatus(reasonCode: String): ComponentStatus = when {
        reasonCode.contains("missing") -> ComponentStatus.DIRECTORY_MISSING
        reasonCode.contains("certificate") -> ComponentStatus.APK_SIGNATURE_MISMATCH
        reasonCode.contains("archive") || reasonCode.contains("zip") -> ComponentStatus.ZIP_VALIDATION_FAILED
        reasonCode.contains("schema") || reasonCode.contains("capability") ->
            ComponentStatus.CLIENT_CAPABILITY_INSUFFICIENT
        else -> ComponentStatus.TEMPORARILY_UNAVAILABLE
    }

    private fun buildComponentResults(
        evidence: SessionEvidence,
        snapshot: InstallationSessionSnapshot,
    ): List<ComponentResult> = snapshot.components.filter { component ->
        isMandatory(component) ||
            component.id in snapshot.selectedOptionalComponentIds ||
            component.errorReason != null ||
            component.status in setOf(
                ComponentStatus.DIRECTORY_MISSING,
                ComponentStatus.ZIP_VALIDATION_FAILED,
                ComponentStatus.APK_SIGNATURE_MISMATCH,
                ComponentStatus.CLIENT_CAPABILITY_INSUFFICIENT,
                ComponentStatus.TEMPORARILY_UNAVAILABLE,
            )
    }.map { component ->
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
        val expected = successfulComponentIds(snapshot)
        if (expected.isEmpty()) return snapshot.failedComponentIds.isNotEmpty()
        val detailedEvidenceValid = snapshot.artifactManifests.isEmpty() || (
            evidence.installation.keys == expected &&
            evidence.authorizationActions.isNotEmpty() &&
                evidence.availability.keys == expected &&
                validateAuthorizationEvidence(evidence.authorizationActions, snapshot) &&
                validateAvailabilityEvidence(evidence.availability.values.toList(), snapshot)
            )
        return expected.isNotEmpty() &&
            expected.all { it in evidence.artifactsVerified } &&
            expected.all { it in evidence.installed } &&
            expected.all { it in evidence.configured } &&
            expected.all { it in evidence.available } &&
            detailedEvidenceValid
    }

    private fun checkpointOf(snapshot: InstallationSessionSnapshot): SessionCheckpoint = SessionCheckpoint(
        sessionId = snapshot.sessionId,
        state = snapshot.state,
        deviceId = snapshot.device?.id,
        selectedOptionalComponentIds = snapshot.selectedOptionalComponentIds,
        currentComponentName = snapshot.currentComponentName,
        progress = snapshot.progress,
        componentProgress = snapshot.componentProgress,
        failedComponentIds = snapshot.failedComponentIds,
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
