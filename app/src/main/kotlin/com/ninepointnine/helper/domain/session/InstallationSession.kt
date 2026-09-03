package com.ninepointnine.helper.domain.session

import com.ninepointnine.helper.domain.artifact.ArchiveDownloadEvidence
import com.ninepointnine.helper.domain.artifact.ArchiveVerificationEvidence
import com.ninepointnine.helper.domain.artifact.ApkExtractionEvidence
import com.ninepointnine.helper.domain.artifact.ArtifactFailure
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
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
import com.ninepointnine.helper.domain.device.ManagedApplicationAuthorizationStatus
import com.ninepointnine.helper.domain.device.MaintenanceAuthorizationState

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

    /**
     * Resolves the installed components that this session may reuse for the
     * current batch. The decision is intentionally owned by the domain
     * session so the runtime and device adapters cannot drift into different
     * definitions of "already installed".
     */
    fun reusableInstalledComponentIds(snapshot: InstallationSessionSnapshot = currentSnapshot()): Set<String> =
        snapshot.installationBatch?.reusableComponentIds
            ?: if (
            snapshot.installationStrategy != InstallationStrategy.INSTALL_MISSING_ONLY ||
            snapshot.maintenance.managedApplicationsState != MaintenanceInventoryState.READY
        ) {
            emptySet()
        } else {
            snapshot.maintenance.managedApplications
                .asSequence()
                .filter { it.installed }
                .filter { application ->
                    val manifest = installedIdentityManifest(snapshot, application.componentId)
                        ?: return@filter false
                    application.packageName == manifest.packageName &&
                        application.versionCode == manifest.apkVersion.code
                }
                .map { it.componentId }
                .toSet()
        }

    /** Returns the frozen decisions for the active installation attempt. */
    fun installationBatchPlan(snapshot: InstallationSessionSnapshot = currentSnapshot()): InstallationBatchPlan? =
        snapshot.installationBatch

    /** Returns the last trusted manifest for a component that is being reused. */
    fun installedIdentityManifest(
        snapshot: InstallationSessionSnapshot,
        componentId: String,
    ): ArtifactManifest? = snapshot.maintenance.installedManifests.firstOrNull {
        it.componentId == componentId
    } ?: snapshot.maintenance.availableManifests.firstOrNull {
        it.componentId == componentId
    } ?: if (snapshot.artifactCatalogStage == ArtifactCatalogStage.PREPARED) {
        snapshot.artifactManifests.firstOrNull { it.componentId == componentId }
    } else {
        null
    }

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
            is InstallationSessionCommand.StartMaintenanceComponentUpdate ->
                startMaintenanceComponentUpdate(command.componentId)
            InstallationSessionCommand.InstallPreparedSelfUpdate -> startPreparedSelfUpdate()
            InstallationSessionCommand.CancelInstallation -> cancelInstallation()
            InstallationSessionCommand.ContinueInstallation,
            InstallationSessionCommand.ResumeInstallation,
            InstallationSessionCommand.RetryInstallation,
            InstallationSessionCommand.ReconfigureInstallation,
            -> resumeFromCheckpoint()

            InstallationSessionCommand.ReturnToSelection -> returnToSelection()

            InstallationSessionCommand.ReturnToMaintenanceInstallationSelection ->
                returnToMaintenanceInstallationSelection()

            InstallationSessionCommand.RestartFromCheckpoint -> restartFromCheckpoint()

            InstallationSessionCommand.Reconnect -> startReconnect()
            InstallationSessionCommand.ReconnectKnownDevice -> startKnownReconnect()
            InstallationSessionCommand.DisconnectDevice -> disconnectDevice()
            InstallationSessionCommand.EnterMaintenance -> enterMaintenance()
            InstallationSessionCommand.LeaveMaintenanceAction -> leaveMaintenanceAction()
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
        // Some offline callers resolve a signed catalog before starting the
        // discovery handshake. Keep that immutable catalog; only a fresh
        // session without one starts at NOT_LOADED.
        val preserveResolvedCatalog = !maintenanceReconnect &&
            current.state == InstallationSessionState.IDLE &&
            current.artifactCatalogStage != ArtifactCatalogStage.NOT_LOADED
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
                initialInventory = if (maintenanceReconnect) {
                    current.initialInventory
                } else {
                    InitialApplicationInventory()
                },
                selectedSources = if (maintenanceReconnect) current.selectedSources else emptyMap(),
                sourceFailures = if (maintenanceReconnect) current.sourceFailures else emptyList(),
                archiveDownloads = if (maintenanceReconnect) current.archiveDownloads else emptyMap(),
                archiveVerifications = if (maintenanceReconnect) current.archiveVerifications else emptyMap(),
                apkExtractions = if (maintenanceReconnect) current.apkExtractions else emptyMap(),
                artifactManifests = if (maintenanceReconnect || preserveResolvedCatalog) {
                    current.artifactManifests
                } else {
                    emptyList()
                },
                artifactCatalogStage = if (maintenanceReconnect || preserveResolvedCatalog) {
                    current.artifactCatalogStage
                } else {
                    ArtifactCatalogStage.NOT_LOADED
                },
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
                initialInventory = InitialApplicationInventory(),
                selectedOptionalComponentIds = emptySet(),
                currentComponentName = null,
                progress = null,
                componentProgress = emptyMap(),
                failedComponentIds = emptySet(),
                componentFailureRetryable = emptyMap(),
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
                initialInventory = InitialApplicationInventory(),
                selectedOptionalComponentIds = emptySet(),
                currentComponentName = null,
                progress = null,
                componentProgress = emptyMap(),
                failedComponentIds = emptySet(),
                componentFailureRetryable = emptyMap(),
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

            component.id in initialInstalledComponentIds(current) -> return

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
        val selectedIds = selected.map { it.id }.toSet()
        val preinstalledIds = initialInstalledComponentIds(current) intersect selectedIds
        val batchSelectedIds = selectedIds - preinstalledIds
        if (batchSelectedIds.isEmpty()) {
            // There is no device write to perform. The explicit completion
            // action can move this connected, already-installed session into
            // maintenance without manufacturing an empty batch receipt.
            enterMaintenance()
            return
        }
        val batchPlan = InstallationBatchPlan(
            batchId = current.sessionId,
            flow = InstallationFlow.INITIAL_INSTALL,
            strategy = InstallationStrategy.INSTALL_MISSING_ONLY,
            selectedComponentIds = batchSelectedIds,
            reusableComponentIds = emptySet(),
            preinstalledComponentIds = preinstalledIds,
            preparationComponentIds = batchSelectedIds,
            resultComponentIds = batchSelectedIds,
            catalogIdentity = catalogIdentity(current),
        )
        val batchSnapshot = current.copy(installationBatch = batchPlan)
        val next = current.copy(
            state = InstallationSessionState.SELECTION_CONFIRMED,
            installationStrategy = InstallationStrategy.INSTALL_MISSING_ONLY,
            installationFlow = InstallationFlow.INITIAL_INSTALL,
            currentComponentName = selected.firstOrNull { it.id in batchSelectedIds }?.displayName,
            progress = SessionProgress(
                completedCount = 0,
                totalCount = batchSelectedIds.size,
                indeterminate = true,
            ),
            componentProgress = selected.filter { it.id in batchSelectedIds }.associate { component ->
                component.id to ComponentProgress(
                    componentId = component.id,
                    phase = InstallPhase.FETCH,
                    status = ComponentProgressStatus.PENDING,
                )
            },
            failedComponentIds = emptySet(),
            componentFailureRetryable = emptyMap(),
            failure = null,
            evidence = SessionEvidence(),
            componentResults = buildComponentResults(SessionEvidence(), batchSnapshot),
            installationBatch = batchPlan,
            installationBatchReceipt = null,
        )
        publish(withCheckpoint(next))
    }

    private fun beginPipeline() {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.SELECTION_CONFIRMED) {
            return
        }
        val validationFailure = if (current.installationFlow == InstallationFlow.SELF_UPDATE) {
            validateSelfUpdateSelection(current)
        } else {
            validateSelection(current)
        }
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
                    state = InstallationSessionState.PREPARING_ARTIFACTS,
                    progress = current.progress?.copy(indeterminate = true, fraction = null),
                ),
            ),
        )
    }

    private fun validateSelfUpdateSelection(snapshot: InstallationSessionSnapshot): String? {
        val batch = snapshot.installationBatch ?: return "self_update_batch_missing"
        if (batch.flow != InstallationFlow.SELF_UPDATE) return "self_update_flow_invalid"
        if (batch.selectedComponentIds != setOf(InstallerSelfIdentity.COMPONENT_ID) ||
            batch.preparationComponentIds != batch.selectedComponentIds ||
            batch.resultComponentIds != batch.selectedComponentIds ||
            batch.reusableComponentIds.isNotEmpty()
        ) return "self_update_batch_invalid"
        val descriptor = snapshot.components.singleOrNull {
            InstallerSelfIdentity.isSelfComponentId(it.id)
        } ?: return "self_update_component_missing"
        if (descriptor.compatibilityState == ComponentCompatibility.UNSUPPORTED) {
            return "self_update_client_incompatible"
        }
        return null
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
            // preparation error and invalidate the old batch before asking
            // the runtime to load a fresh signed control-plane snapshot.
            val retryInitialInventory = current.initialInventory.state == InitialApplicationInventoryState.FAILED &&
                current.failure?.reasonCode?.startsWith("initial_inventory") == true
            publish(
                current.copy(
                    components = emptyList(),
                    selectedOptionalComponentIds = emptySet(),
                    initialInventory = if (retryInitialInventory) {
                        InitialApplicationInventory()
                    } else {
                        current.initialInventory
                    },
                    artifactManifests = emptyList(),
                    artifactCatalogStage = ArtifactCatalogStage.NOT_LOADED,
                    installationBatch = null,
                    installationBatchReceipt = null,
                    failure = null,
                    checkpoint = null,
                ),
            )
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
            installationStrategy = checkpoint.installationStrategy,
            installationFlow = checkpoint.installationFlow,
            artifactCatalogStage = checkpoint.artifactCatalogStage,
            installationBatch = checkpoint.installationBatch,
            currentComponentName = checkpoint.currentComponentName,
            progress = checkpoint.progress,
            componentProgress = checkpoint.componentProgress,
            failedComponentIds = checkpoint.failedComponentIds,
            componentFailureRetryable = checkpoint.componentFailureRetryable,
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

    /**
     * Drops only the failed installation attempt. The confirmed device, remote
     * catalog and the user's optional selections remain available to the
     * selection screen so a new attempt can start without redoing discovery.
     */
    private fun returnToSelection() {
        val current = _snapshot.value
        if (
            current.state !in setOf(
                InstallationSessionState.COMPLETED_WITH_ERRORS,
                InstallationSessionState.FAILED,
            )
        ) {
            return
        }
        val resetComponents = current.components.map { component ->
            val resetStatus = when (component.status) {
                ComponentStatus.ZIP_VALIDATION_FAILED,
                ComponentStatus.APK_SIGNATURE_MISMATCH,
                ComponentStatus.TEMPORARILY_UNAVAILABLE,
                -> ComponentStatus.READING

                else -> component.status
            }
            component.copy(status = resetStatus, errorReason = null)
        }
        startNewGeneration(
            current.copy(
                state = InstallationSessionState.CONNECTED,
                components = resetComponents,
                currentComponentName = null,
                progress = null,
                componentProgress = emptyMap(),
                failedComponentIds = emptySet(),
                componentFailureRetryable = emptyMap(),
                failure = null,
                checkpoint = null,
                evidence = SessionEvidence(),
                componentResults = emptyList(),
                artifactManifests = emptyList(),
                artifactCatalogStage = ArtifactCatalogStage.NOT_LOADED,
                installationBatch = null,
                installationBatchReceipt = null,
                selectedSources = emptyMap(),
                sourceFailures = emptyList(),
                archiveDownloads = emptyMap(),
                archiveVerifications = emptyMap(),
                apkExtractions = emptyMap(),
                installationReconnectPending = false,
            ),
        )
    }

    /**
     * Restores the maintenance application-selection page after a failed
     * maintenance batch. The selection is retained while the batch is active
     * so this transition does not need to reconstruct UI state from results.
     */
    private fun returnToMaintenanceInstallationSelection() {
        val current = _snapshot.value
        if (
            current.installationFlow != InstallationFlow.MAINTENANCE_INSTALL ||
            current.state !in setOf(
                InstallationSessionState.COMPLETED_WITH_ERRORS,
                InstallationSessionState.FAILED,
            )
        ) {
            return
        }
        // The explicit route is the only owner of this secondary page. A
        // selection or last-action record is historical data and must never
        // reconstruct navigation after a flow transition or process restore.
        val actionId = current.maintenance.routeAction
            ?.takeIf { it.isApplicationInstallation }
        if (actionId?.isApplicationInstallation != true) {
            // The same terminal command is also used by legacy maintenance
            // installation actions (for example REINSTALL). They must return
            // to the maintenance home, never to the initial-install flow.
            returnToMaintenanceHomeFromTerminal(current)
            return
        }
        // Prefer the concrete component failure from this batch. Aggregate
        // session failures (for example an authorization wrapper error) can
        // otherwise hide the actionable archive/install reason on retry.
        val reasonCode = current.componentResults.firstNotNullOfOrNull { it.failureReason }
            ?: current.failure?.reasonCode
            ?: "maintenance_install_failed"
        val resetComponents = current.components.map { component ->
            val resetStatus = when (component.status) {
                ComponentStatus.ZIP_VALIDATION_FAILED,
                ComponentStatus.APK_SIGNATURE_MISMATCH,
                ComponentStatus.TEMPORARILY_UNAVAILABLE,
                -> ComponentStatus.READING

                else -> component.status
            }
            component.copy(status = resetStatus, errorReason = null)
        }
        startNewGeneration(
            current.copy(
                state = InstallationSessionState.MAINTENANCE,
                components = resetComponents,
                currentComponentName = null,
                progress = null,
                componentProgress = emptyMap(),
                failedComponentIds = emptySet(),
                componentFailureRetryable = emptyMap(),
                failure = null,
                checkpoint = null,
                evidence = current.evidence.copy(
                    writeConfirmed = emptySet(),
                    confirmationPending = emptySet(),
                ),
                componentResults = emptyList(),
                artifactManifests = emptyList(),
                artifactCatalogStage = ArtifactCatalogStage.NOT_LOADED,
                installationBatch = null,
                selectedSources = emptyMap(),
                sourceFailures = emptyList(),
                archiveDownloads = emptyMap(),
                archiveVerifications = emptyMap(),
                apkExtractions = emptyMap(),
                maintenanceReconnectPending = false,
                installationReconnectPending = false,
                maintenance = current.maintenance.copy(
                    activeAction = null,
                    routeAction = actionId,
                    lastAction = MaintenanceActionRecord(
                        actionId = actionId,
                        status = MaintenanceActionStatus.FAILED,
                        reasonCode = reasonCode,
                        retryable = current.componentResults.any { it.retryable } ||
                            current.failure?.retryable == true,
                    ),
                ),
            ),
        )
    }

    private fun returnToMaintenanceHomeFromTerminal(current: InstallationSessionSnapshot) {
        startNewGeneration(
            current.copy(
                state = InstallationSessionState.MAINTENANCE,
                currentComponentName = null,
                progress = null,
                componentProgress = emptyMap(),
                failedComponentIds = emptySet(),
                componentFailureRetryable = emptyMap(),
                failure = null,
                checkpoint = null,
                componentResults = emptyList(),
                evidence = current.evidence.copy(
                    writeConfirmed = emptySet(),
                    confirmationPending = emptySet(),
                ),
                artifactManifests = emptyList(),
                artifactCatalogStage = ArtifactCatalogStage.NOT_LOADED,
                installationBatch = null,
                selectedSources = emptyMap(),
                sourceFailures = emptyList(),
                archiveDownloads = emptyMap(),
                archiveVerifications = emptyMap(),
                apkExtractions = emptyMap(),
                maintenanceReconnectPending = false,
                installationReconnectPending = false,
                maintenance = current.maintenance.copy(
                    activeAction = null,
                    routeAction = null,
                    installationSelection = null,
                ),
            ),
        )
    }

    /** Resets an in-memory artifact/device boundary to the frozen selection. */
    private fun restartFromCheckpoint() {
        val current = _snapshot.value
        if (current.state !in setOf(
                InstallationSessionState.ARTIFACTS_READY,
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
            componentFailureRetryable = emptyMap(),
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
        if (
            current.state == InstallationSessionState.CONNECTED &&
            current.installationFlow == InstallationFlow.INITIAL_INSTALL &&
            current.initialInventory.state == InitialApplicationInventoryState.READY &&
            selectedComponents(current).isNotEmpty() &&
            selectedComponents(current).all { it.id in initialInstalledComponentIds(current) }
        ) {
            enterMaintenanceFromInitialInventory(current)
            return
        }
        if (current.state != InstallationSessionState.SUCCEEDED &&
            current.state != InstallationSessionState.COMPLETED_WITH_ERRORS &&
            !(current.installationFlow == InstallationFlow.SELF_UPDATE &&
                current.state == InstallationSessionState.FAILED)
        ) {
            return
        }
        if (current.installationFlow == InstallationFlow.SELF_UPDATE) {
            // The helper update has no vehicle-side desktop prerequisite. Keep
            // the same session owner and return directly to the update page
            // after Android's installer result has been classified.
            returnToMaintenanceAfterSelfUpdate(current)
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
        val installedBaseline = current.artifactManifests
            .filter { it.componentId in current.evidence.installed }
        val availableComponents = current.maintenance.availableComponents.ifEmpty {
            current.components.filter { component ->
                !InstallerSelfIdentity.isSelfComponentId(component.id) &&
                    component.status != ComponentStatus.UNLISTED
            }
        }
        val maintenanceBaseline = current.maintenance
            .copy(availableComponents = availableComponents)
            .withVerifiedInstallations(installedBaseline)
            .toDurableMaintenanceBaseline()
        publish(
            current.copy(
                state = InstallationSessionState.MAINTENANCE,
                selectedOptionalComponentIds = emptySet(),
                currentComponentName = null,
                progress = null,
                componentProgress = emptyMap(),
                failedComponentIds = emptySet(),
                componentFailureRetryable = emptyMap(),
                failure = null,
                componentResults = emptyList(),
                checkpoint = null,
                // A batch belongs only to the completed attempt. The
                // maintenance home must not expose it as an active plan.
                installationBatch = null,
                installationBatchReceipt = null,
                artifactManifests = emptyList(),
                artifactCatalogStage = if (catalogIdentity(current) != null) {
                    ArtifactCatalogStage.CONTROL_PLANE_READY
                } else {
                    ArtifactCatalogStage.NOT_LOADED
                },
                // PackageManager write receipts are scoped to the completed
                // batch and are not part of the durable maintenance baseline.
                evidence = SessionEvidence(
                    installed = current.evidence.installed,
                    configured = current.evidence.configured,
                    available = current.evidence.available,
                ),
                selectedSources = emptyMap(),
                sourceFailures = emptyList(),
                archiveDownloads = emptyMap(),
                archiveVerifications = emptyMap(),
                apkExtractions = emptyMap(),
                // Entering the home page is a hard route boundary. Clear all
                // secondary-page payloads and action feedback so a completed
                // installation cannot resurrect the previous install page or
                // its warning on the next visit.
                maintenance = maintenanceBaseline.copy(
                    activeAction = null,
                    routeAction = null,
                    lastAction = null,
                    applicationAction = null,
                    applicationDetails = null,
                    installationSelection = null,
                ),
                maintenanceReconnectPending = false,
                installationReconnectPending = false,
            ),
        )
    }

    /**
     * Completes the first-install route when the live inventory already
     * contains every catalog component. No synthetic APK receipt is created;
     * the inventory remains the source of truth for this in-memory maintenance
     * session and will be read again after a cold start.
     */
    private fun enterMaintenanceFromInitialInventory(
        current: InstallationSessionSnapshot,
    ) {
        val inventory = current.initialInventory
        val installedIds = initialInstalledComponentIds(current)
        val availableComponents = current.components
            .filterNot { InstallerSelfIdentity.isSelfComponentId(it.id) }
            .filter { it.status != ComponentStatus.UNLISTED }
        val maintenance = current.maintenance
            .withInitialInventory(inventory)
            .copy(availableComponents = availableComponents)
        publish(
            current.copy(
                state = InstallationSessionState.MAINTENANCE,
                selectedOptionalComponentIds = emptySet(),
                currentComponentName = null,
                progress = null,
                componentProgress = emptyMap(),
                failedComponentIds = emptySet(),
                componentFailureRetryable = emptyMap(),
                failure = null,
                componentResults = emptyList(),
                checkpoint = null,
                installationBatch = null,
                installationBatchReceipt = null,
                artifactManifests = emptyList(),
                artifactCatalogStage = if (catalogIdentity(current) != null) {
                    ArtifactCatalogStage.CONTROL_PLANE_READY
                } else {
                    ArtifactCatalogStage.NOT_LOADED
                },
                evidence = SessionEvidence(
                    installed = installedIds,
                    configured = installedIds,
                    available = installedIds,
                ),
                selectedSources = emptyMap(),
                sourceFailures = emptyList(),
                archiveDownloads = emptyMap(),
                archiveVerifications = emptyMap(),
                apkExtractions = emptyMap(),
                maintenance = maintenance.copy(
                    activeAction = null,
                    routeAction = null,
                    lastAction = null,
                    applicationAction = null,
                    applicationDetails = null,
                    installationSelection = null,
                ),
                maintenanceReconnectPending = false,
                installationReconnectPending = false,
            ),
        )
    }

    private fun returnToMaintenanceAfterSelfUpdate(current: InstallationSessionSnapshot) {
        val successful = current.state == InstallationSessionState.SUCCEEDED
        val restoredMaintenanceComponents = maintenanceComponentsAfterSelfUpdate(current)
        val restoredAvailableComponents = restoredMaintenanceComponents.filterNot {
            it.status == ComponentStatus.UNLISTED || InstallerSelfIdentity.isSelfComponentId(it.id)
        }
        val action = current.maintenance.lastAction?.copy(
            actionId = MaintenanceActionId.CHECK_UPDATES,
            status = if (successful) MaintenanceActionStatus.SUCCEEDED else MaintenanceActionStatus.FAILED,
            resultCode = if (successful) "self_update_completed" else null,
            reasonCode = if (successful) null else current.failure?.reasonCode,
            retryable = !successful,
        ) ?: MaintenanceActionRecord(
            actionId = MaintenanceActionId.CHECK_UPDATES,
            status = if (successful) MaintenanceActionStatus.SUCCEEDED else MaintenanceActionStatus.FAILED,
            resultCode = if (successful) "self_update_completed" else null,
            reasonCode = if (successful) null else current.failure?.reasonCode,
            retryable = !successful,
        )
        startNewGeneration(
            current.copy(
                state = InstallationSessionState.MAINTENANCE,
                components = restoredMaintenanceComponents,
                selectedOptionalComponentIds = emptySet(),
                currentComponentName = null,
                progress = null,
                componentProgress = emptyMap(),
                failedComponentIds = emptySet(),
                componentFailureRetryable = emptyMap(),
                failure = null,
                checkpoint = null,
                componentResults = emptyList(),
                installationBatch = null,
                installationBatchReceipt = null,
                artifactManifests = emptyList(),
                artifactCatalogStage = ArtifactCatalogStage.CONTROL_PLANE_READY,
                evidence = SessionEvidence(
                    installed = current.evidence.installed,
                    configured = current.evidence.configured,
                    available = current.evidence.available,
                ),
                selectedSources = emptyMap(),
                sourceFailures = emptyList(),
                archiveDownloads = emptyMap(),
                archiveVerifications = emptyMap(),
                apkExtractions = emptyMap(),
                maintenance = current.maintenance.copy(
                    availableComponents = restoredAvailableComponents,
                    activeAction = null,
                    routeAction = MaintenanceActionId.CHECK_UPDATES,
                    lastAction = action,
                    applicationAction = null,
                    applicationDetails = null,
                    installationSelection = null,
                ),
                maintenanceReconnectPending = false,
                installationReconnectPending = false,
            ),
        )
    }

    /**
     * Restores the maintenance component projection after the self-update
     * batch temporarily replaced [InstallationSessionSnapshot.components] with
     * the helper-only preparation descriptor. The signed maintenance catalog
     * remains the preferred source; installed entries absent from that catalog
     * stay visible as explicitly unlisted rows.
     */
    private fun maintenanceComponentsAfterSelfUpdate(
        snapshot: InstallationSessionSnapshot,
    ): List<ComponentDescriptor> {
        val available = (
            snapshot.maintenance.availableComponents
                .ifEmpty {
                    snapshot.components.filterNot {
                        InstallerSelfIdentity.isSelfComponentId(it.id) || it.status == ComponentStatus.UNLISTED
                    }
                }
            ).filterNot { InstallerSelfIdentity.isSelfComponentId(it.id) || it.status == ComponentStatus.UNLISTED }
            .distinctBy { it.id }
        val availableIds = available.mapTo(mutableSetOf()) { it.id }
        val installedIds = installedMaintenanceComponentIds(snapshot)
        val retainedUnlistedIds = snapshot.components
            .filter { it.id !in availableIds && it.id in installedIds }
            .mapTo(mutableSetOf()) { it.id }
        val unlisted = buildList {
            snapshot.components
                .filter { it.id !in availableIds && it.id in installedIds }
                .forEach { component ->
                    add(component.copy(status = ComponentStatus.UNLISTED, errorReason = "unlisted"))
                }
            snapshot.maintenance.installedManifests
                .filter { it.componentId !in availableIds && it.componentId !in retainedUnlistedIds }
                .forEach { manifest ->
                    add(
                        manifest.toComponentDescriptor(snapshot.device?.androidSdk).copy(
                            status = ComponentStatus.UNLISTED,
                            errorReason = "unlisted",
                        ),
                    )
                }
            snapshot.maintenance.managedApplications
                .filter { it.installed && it.componentId !in availableIds && it.componentId !in retainedUnlistedIds }
                .forEach { application ->
                    add(
                        ComponentDescriptor(
                            id = application.componentId,
                            displayName = application.componentId,
                            required = false,
                            versionLabel = application.versionLabel,
                            status = ComponentStatus.UNLISTED,
                            errorReason = "unlisted",
                        ),
                    )
                }
        }
        return (available + unlisted).distinctBy { it.id }
    }

    private fun installedMaintenanceComponentIds(
        snapshot: InstallationSessionSnapshot,
    ): Set<String> = buildSet {
        snapshot.maintenance.managedApplications
            .filter { it.installed }
            .mapTo(this) { it.componentId }
        snapshot.maintenance.installedManifests.mapTo(this) { it.componentId }
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

    /**
     * The page route is UI-owned, but an in-flight maintenance operation is
     * session-owned. Leaving a secondary page must therefore clear both in one
     * synchronous transition so the home page cannot remain permanently busy.
     */
    private fun leaveMaintenanceAction() {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.MAINTENANCE) return
        // Leaving a secondary page ends the operation generation as well as
        // clearing its route payload. Any adapter callback that was already
        // in flight must not be able to repopulate the next home-page visit.
        startNewGeneration(
            current.copy(
                evidence = current.evidence.copy(
                    writeConfirmed = emptySet(),
                    confirmationPending = emptySet(),
                ),
                maintenance = current.maintenance.copy(
                        activeAction = null,
                        routeAction = null,
                        lastAction = null,
                    applicationAction = null,
                    applicationDetails = null,
                    managedApplicationsState = MaintenanceInventoryState.NOT_STARTED,
                    managedApplicationsFailureReason = null,
                    managedApplicationsFailureRetryable = false,
                    installationSelection = null,
                    // Every visit to the authorization page starts with a
                    // fresh read-only car probe. The explicit reauthorize
                    // button can still transition READY -> REPAIRING while
                    // the route remains open.
                    authorization = MaintenanceAuthorizationSnapshot(),
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
            startNewGeneration(
                current.copy(
                    maintenance = current.maintenance.copy(
                        routeAction = command.actionId,
                        applicationAction = null,
                        applicationDetails = null,
                        installationSelection = null,
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
        if (command.actionId == MaintenanceActionId.REINSTALL) {
            startMaintenanceInstallation(command.actionId)
            return
        }
        val authorization = if (command.actionId == MaintenanceActionId.REPAIR_CONFIGURATION) {
            val firstCheck = current.maintenance.authorization.state == MaintenanceAuthorizationFlowState.NOT_STARTED &&
                current.maintenance.authorization.applications.isEmpty()
            MaintenanceAuthorizationSnapshot(
                state = if (firstCheck) {
                    MaintenanceAuthorizationFlowState.CHECKING
                } else {
                    MaintenanceAuthorizationFlowState.REPAIRING
                },
                currentComponentId = null,
                applications = emptyList(),
            )
        } else {
            current.maintenance.authorization
        }
        val refreshInventory = command.actionId in setOf(
            MaintenanceActionId.MANAGE_APPS,
            MaintenanceActionId.REPAIR_CONFIGURATION,
            MaintenanceActionId.INSTALL_APPLICATIONS,
            MaintenanceActionId.INSTALL_FILE_MANAGER,
        )
        // A new maintenance action owns a new generation. This is the
        // boundary that invalidates callbacks from the previous action.
        startNewGeneration(
            current.copy(
                maintenance = current.maintenance.copy(
                    routeAction = command.actionId,
                    activeAction = command.actionId,
                    lastAction = MaintenanceActionRecord(
                        actionId = command.actionId,
                        status = MaintenanceActionStatus.RUNNING,
                    ),
                    updateStatuses = if (command.actionId == MaintenanceActionId.CHECK_UPDATES) {
                        emptyList()
                    } else {
                        current.maintenance.updateStatuses
                    },
                    authorization = authorization,
                    managedApplicationsState = if (refreshInventory) {
                        MaintenanceInventoryState.LOADING
                    } else {
                        current.maintenance.managedApplicationsState
                    },
                    managedApplicationsFailureReason = if (refreshInventory) null else {
                        current.maintenance.managedApplicationsFailureReason
                    },
                    managedApplicationsFailureRetryable = if (refreshInventory) false else {
                        current.maintenance.managedApplicationsFailureRetryable
                    },
                    managedApplications = if (refreshInventory) emptyList() else current.maintenance.managedApplications,
                    // A new route owns a new page payload. Never let a prior
                    // application detail/selection leak into this action.
                    applicationAction = null,
                    applicationDetails = null,
                    installationSelection = null,
                ),
            ),
        )
    }

    /**
     * Creates a frozen batch for exactly one row marked UPDATE_AVAILABLE.
     * Vehicle updates retain the desktop prerequisite only when the existing
     * trusted-inventory rule proves it reusable; the target row is the only
     * visible result row. The helper update uses the same preparation states but
     * is routed to Android's package installer by the runtime.
     */
    private fun startMaintenanceComponentUpdate(rawComponentId: String) {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.MAINTENANCE ||
            current.maintenance.activeAction != null
        ) return
        val status = current.maintenance.updateStatuses.firstOrNull { candidate ->
            candidate.componentId == rawComponentId ||
                (InstallerSelfIdentity.isSelfComponentId(candidate.componentId) &&
                    InstallerSelfIdentity.isSelfComponentId(rawComponentId))
        }
        if (status == null || status.state != MaintenanceUpdateState.UPDATE_AVAILABLE) {
            recordMaintenanceUpdateFailure(current, rawComponentId, "maintenance_update_target_unavailable")
            return
        }
        val targetId = if (status.isSelf || InstallerSelfIdentity.isSelfComponentId(status.componentId)) {
            InstallerSelfIdentity.COMPONENT_ID
        } else {
            status.componentId
        }
        if (status.isSelf || InstallerSelfIdentity.isSelfComponentId(status.componentId)) {
            startSelfUpdatePreparation(current, status)
        } else {
            startMaintenanceInstallation(
                actionId = MaintenanceActionId.CHECK_UPDATES,
                selectedOptionalOverride = setOf(targetId),
                targetComponentId = targetId,
            )
        }
    }

    private fun recordMaintenanceUpdateFailure(
        current: InstallationSessionSnapshot,
        componentId: String,
        reasonCode: String,
    ) {
        publish(
            current.copy(
                maintenance = current.maintenance.copy(
                    routeAction = MaintenanceActionId.CHECK_UPDATES,
                    activeAction = null,
                    lastAction = MaintenanceActionRecord(
                        actionId = MaintenanceActionId.CHECK_UPDATES,
                        status = MaintenanceActionStatus.FAILED,
                        reasonCode = reasonCode,
                        retryable = false,
                    ),
                ),
            ),
        )
    }

    private fun startSelfUpdatePreparation(
        current: InstallationSessionSnapshot,
        status: MaintenanceUpdateStatus,
    ) {
        val identity = catalogIdentity(current)
        if (identity == null) {
            recordMaintenanceUpdateFailure(current, status.componentId, "selected_catalog_identity_missing")
            return
        }
        val descriptor = ComponentDescriptor(
            id = InstallerSelfIdentity.COMPONENT_ID,
            displayName = status.displayName.ifBlank { "03车机助手" },
            required = false,
            versionLabel = status.versionLabel,
            compatibilityState = ComponentCompatibility.SUPPORTED,
            iconKey = InstallerSelfIdentity.COMPONENT_ID,
            status = ComponentStatus.UPDATE_AVAILABLE,
        )
        val batchId = nextSessionId(current.sessionId)
        val batch = InstallationBatchPlan(
            batchId = batchId,
            flow = InstallationFlow.SELF_UPDATE,
            strategy = InstallationStrategy.REINSTALL_SELECTED,
            selectedComponentIds = setOf(InstallerSelfIdentity.COMPONENT_ID),
            reusableComponentIds = emptySet(),
            preparationComponentIds = setOf(InstallerSelfIdentity.COMPONENT_ID),
            resultComponentIds = setOf(InstallerSelfIdentity.COMPONENT_ID),
            catalogIdentity = identity,
        )
        val next = current.copy(
            state = InstallationSessionState.SELECTION_CONFIRMED,
            components = listOf(descriptor),
            selectedOptionalComponentIds = setOf(InstallerSelfIdentity.COMPONENT_ID),
            currentComponentName = descriptor.displayName,
            progress = SessionProgress(totalCount = 1, indeterminate = true),
            componentProgress = mapOf(
                InstallerSelfIdentity.COMPONENT_ID to ComponentProgress(
                    componentId = InstallerSelfIdentity.COMPONENT_ID,
                    phase = InstallPhase.FETCH,
                    status = ComponentProgressStatus.PENDING,
                ),
            ),
            failedComponentIds = emptySet(),
            componentFailureRetryable = emptyMap(),
            failure = null,
            componentResults = emptyList(),
            artifactManifests = emptyList(),
            artifactCatalogStage = ArtifactCatalogStage.CONTROL_PLANE_READY,
            installationStrategy = InstallationStrategy.REINSTALL_SELECTED,
            installationFlow = InstallationFlow.SELF_UPDATE,
            installationBatch = batch,
            installationBatchReceipt = null,
            evidence = SessionEvidence(),
            selectedSources = emptyMap(),
            sourceFailures = emptyList(),
            archiveDownloads = emptyMap(),
            archiveVerifications = emptyMap(),
            apkExtractions = emptyMap(),
            maintenance = current.maintenance.copy(
                routeAction = MaintenanceActionId.CHECK_UPDATES,
                activeAction = null,
                lastAction = MaintenanceActionRecord(
                    actionId = MaintenanceActionId.CHECK_UPDATES,
                    status = MaintenanceActionStatus.RUNNING,
                ),
                applicationAction = null,
                applicationDetails = null,
                installationSelection = null,
            ),
        )
        startNewGeneration(withCheckpoint(next.copy(sessionId = batchId)))
    }

    private fun startPreparedSelfUpdate() {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.ARTIFACTS_READY ||
            current.installationFlow != InstallationFlow.SELF_UPDATE
        ) return
        val batch = current.installationBatch
        val manifest = current.artifactManifests.singleOrNull {
            InstallerSelfIdentity.isSelfComponentId(it.componentId) &&
                it.packageName == InstallerSelfIdentity.PACKAGE_NAME
        }
        if (batch == null || manifest == null || current.failedComponentIds.isNotEmpty()) {
            fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = "self_update_artifact_unavailable")
            return
        }
        transition(
            state = InstallationSessionState.INSTALLING,
            progress = progress(0.75f, indeterminate = true),
            componentProgress = markComponents(current, InstallPhase.SEND, ComponentProgressStatus.RUNNING),
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
        // Application actions are valid only from the managed-applications
        // route. A stale callback must fail closed and must never invent a
        // route that the UI did not select.
        if (current.maintenance.routeAction != MaintenanceActionId.MANAGE_APPS) {
            startNewGeneration(
                current.copy(
                    maintenance = current.maintenance.copy(
                        applicationAction = MaintenanceApplicationActionRecord(
                            componentId = command.componentId,
                            actionId = command.actionId,
                            status = MaintenanceActionStatus.FAILED,
                            reasonCode = "maintenance_route_invalid",
                            retryable = false,
                        ),
                        applicationDetails = null,
                    ),
                ),
            )
            return
        }
        if (current.device?.connectionStatus != DeviceConnectionStatus.CONFIRMED) {
            startNewGeneration(
                current.copy(
                    maintenance = current.maintenance.copy(
                        applicationDetails = null,
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
            startNewGeneration(
                current.copy(
                    maintenance = current.maintenance.copy(
                        applicationAction = MaintenanceApplicationActionRecord(
                            componentId = command.componentId,
                            actionId = command.actionId,
                            status = MaintenanceActionStatus.FAILED,
                            reasonCode = "maintenance_component_unavailable",
                            retryable = false,
                        ),
                        applicationDetails = null,
                    ),
                ),
            )
            return
        }
        // Keep the application action and its adapter callbacks in one
        // generation. A late callback from a previous row action is thereby
        // rejected before it can change the current row.
        startNewGeneration(
            current.copy(
                maintenance = current.maintenance.copy(
                    applicationDetails = null,
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
        targetComponentId: String? = null,
    ) {
        val current = _snapshot.value
        val strategy = when (actionId) {
            MaintenanceActionId.REINSTALL -> InstallationStrategy.REINSTALL_SELECTED
            MaintenanceActionId.INSTALL_APPLICATIONS,
            MaintenanceActionId.INSTALL_FILE_MANAGER -> InstallationStrategy.INSTALL_MISSING_ONLY
            else -> if (targetComponentId != null) {
                InstallationStrategy.REINSTALL_SELECTED
            } else {
                return
            }
        }
        val requestedOptional = (selectedOptionalOverride ?: when (actionId) {
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
            MaintenanceActionId.INSTALL_APPLICATIONS ->
                current.selectedOptionalComponentIds
            MaintenanceActionId.INSTALL_FILE_MANAGER ->
                current.selectedOptionalComponentIds + AuthorizationPlanFactory.FILE_MANAGER_COMPONENT_ID

            else -> targetComponentId?.let { setOf(it) } ?: return
        }).filterNot { it == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID }.toSet()
        // The maintenance catalog is the only source for a new maintenance
        // batch. The current batch manifests are not a catalog cache and must
        // never silently repopulate a newer control-plane selection.
        val candidateManifests = current.maintenance.availableManifests.filterNot {
                InstallerSelfIdentity.isSelfComponentId(it.componentId)
            }
        val controlPlaneOnly = current.maintenance.catalogControlPlaneOnly
        val effectiveCandidateManifests = if (controlPlaneOnly) emptyList() else candidateManifests
        val configuredComponents = current.maintenance.availableComponents
            .ifEmpty { current.components }
        val candidateComponents = effectiveCandidateManifests.map { it.toComponentDescriptor(current.device?.androidSdk) }
            .takeIf { it.isNotEmpty() }
            ?.let { mergeComponentDescriptors(configuredComponents, it) }
            ?: configuredComponents
        val candidateBase = current.copy(
            components = candidateComponents,
            artifactManifests = effectiveCandidateManifests,
            artifactCatalogStage = if (controlPlaneOnly) {
                ArtifactCatalogStage.CONTROL_PLANE_READY
            } else {
                ArtifactCatalogStage.NOT_LOADED
            },
            installationStrategy = strategy,
            installationFlow = InstallationFlow.MAINTENANCE_INSTALL,
            catalogVersion = current.maintenance.availableCatalogVersion ?: current.catalogVersion,
            catalogKeyId = current.maintenance.availableCatalogKeyId ?: current.catalogKeyId,
            catalogSignatureAlgorithm = current.maintenance.availableCatalogSignatureAlgorithm
                ?: current.catalogSignatureAlgorithm,
            selectedOptionalComponentIds = requestedOptional,
            maintenance = current.maintenance.copy(
                routeAction = actionId,
                activeAction = null,
                applicationAction = null,
                applicationDetails = null,
                // Keep the selection that launched this batch so a failed
                // attempt can return to the same user choice. A direct
                // action (for example REINSTALL) has no matching selection
                // and therefore starts without stale page data.
                installationSelection = current.maintenance.installationSelection
                    ?.takeIf { it.actionId == actionId },
                lastAction = MaintenanceActionRecord(
                    actionId = actionId,
                    status = MaintenanceActionStatus.RUNNING,
                ),
            ),
            installationBatch = null,
        )
        // An installed row is intentionally not selectable in the UI. If its
        // version is unavailable, however, presence alone is not enough to
        // reuse it safely. Keep that component in the internal batch so the
        // unified preparation path can prepare a verified APK; exact identity
        // matches are still removed later by [reusableInstalledComponentIds].
        val unverifiedInstalledOptionalIds = if (
            strategy == InstallationStrategy.INSTALL_MISSING_ONLY && targetComponentId == null
        ) {
            val availableIds = candidateBase.components
                .filterNot { it.status == ComponentStatus.UNLISTED }
                .map { it.id }
                .toSet()
            current.maintenance.managedApplications
                .asSequence()
                .filter { it.installed }
                .map { it.componentId }
                .filter { it != AuthorizationPlanFactory.DESKTOP_COMPONENT_ID && it in availableIds }
                .toSet() - reusableInstalledComponentIds(candidateBase)
        } else {
            emptySet()
        }
        val selectedOptional = requestedOptional + unverifiedInstalledOptionalIds
        val candidateWithSelection = candidateBase.copy(selectedOptionalComponentIds = selectedOptional)
        val selectedIdsForCoverage = selectedComponents(candidateWithSelection).map { it.id }.toSet()
        val reusableIdsForCoverage = if (targetComponentId != null) {
            reusableMaintenancePrerequisiteIds(candidateWithSelection, setOf(targetComponentId))
        } else {
            reusableInstalledComponentIds(candidateWithSelection)
        }
        val unresolvedManifestIds = (selectedIdsForCoverage - reusableIdsForCoverage) -
            effectiveCandidateManifests.map { it.componentId }.toSet()
        val candidateStage = when {
            controlPlaneOnly -> ArtifactCatalogStage.CONTROL_PLANE_READY
            unresolvedManifestIds.isEmpty() -> ArtifactCatalogStage.PREPARED
            effectiveCandidateManifests.isNotEmpty() -> ArtifactCatalogStage.CONTROL_PLANE_READY
            else -> ArtifactCatalogStage.NOT_LOADED
        }
        val candidate = candidateWithSelection.copy(artifactCatalogStage = candidateStage)
        val validationFailure = validateSelection(candidate)
        if (validationFailure != null) {
            publish(
                current.copy(
                    maintenance = candidate.maintenance.copy(
                        activeAction = null,
                        routeAction = actionId,
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
        val reusableIds = if (targetComponentId != null) {
            reusableMaintenancePrerequisiteIds(candidate, setOf(targetComponentId))
        } else {
            reusableInstalledComponentIds(candidate)
        }
        val selectedIds = selected.map { it.id }.toSet()
        // Starting a maintenance install is a new device-work generation. The
        // batch id and the checkpoint token must be created from that same
        // next generation so events from the previous maintenance action
        // cannot be accepted by the new batch.
        val nextBatchId = nextSessionId(current.sessionId)
        val batchPlan = InstallationBatchPlan(
            batchId = nextBatchId,
            flow = InstallationFlow.MAINTENANCE_INSTALL,
            strategy = strategy,
            selectedComponentIds = selectedIds,
            reusableComponentIds = reusableIds intersect selectedIds,
            preparationComponentIds = selectedIds - reusableIds,
            resultComponentIds = selectedIds - reusableIds,
            catalogIdentity = catalogIdentity(candidate),
        )
        val baselineEvidence = current.evidence.copy(
            artifactsVerified = current.evidence.artifactsVerified intersect reusableIds,
            artifactVerifications = current.evidence.artifactVerifications.filterKeys { it in reusableIds },
            installed = current.evidence.installed intersect reusableIds,
            // A write receipt proves only the previous attempt's device call;
            // it cannot be reused as identity proof for this new batch.
            writeConfirmed = emptySet(),
            confirmationPending = emptySet(),
            installation = current.evidence.installation.filterKeys { it in reusableIds },
            configured = current.evidence.configured intersect reusableIds,
            available = current.evidence.available intersect reusableIds,
            authorizationActions = current.evidence.authorizationActions.filter {
                it.componentId in reusableIds
            },
            availability = current.evidence.availability.filterKeys { it in reusableIds },
        )
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
            componentFailureRetryable = emptyMap(),
            failure = null,
            evidence = baselineEvidence,
            componentResults = buildComponentResults(baselineEvidence, candidate),
            maintenance = candidate.maintenance,
            installationBatch = batchPlan,
            installationBatchReceipt = null,
        )
        // [startNewGeneration] increments the live session id. Seed the
        // snapshot with the same value before creating its checkpoint so the
        // persisted checkpoint, batch plan and event port all agree.
        startNewGeneration(withCheckpoint(next.copy(sessionId = nextBatchId)))
    }

    private fun beginMaintenanceInstallationSelection(actionId: MaintenanceActionId) {
        val current = _snapshot.value
        val manifests = current.maintenance.availableManifests.filterNot {
                InstallerSelfIdentity.isSelfComponentId(it.componentId)
            }
        val existingInstalled = if (current.maintenance.managedApplicationsState == MaintenanceInventoryState.READY) {
            current.maintenance.managedApplications.filter { it.installed }
        } else {
            emptyList()
        }
            .map { it.componentId }
        val configuredComponents = current.maintenance.availableComponents
            .ifEmpty { current.components }
        val manifestById = manifests.associateBy { it.componentId }
        val options = configuredComponents
            .filterNot { InstallerSelfIdentity.isSelfComponentId(it.id) }
            .map { component ->
            val manifest = manifestById[component.id]
            MaintenanceInstallationOption(
                componentId = component.id,
                displayName = component.displayName,
                versionLabel = manifest?.let { formatArtifactVersionLabel(it.version.name) } ?: component.versionLabel,
                sizeLabel = manifest?.let { formatArtifactSizeLabel(it.apkSizeBytes) } ?: component.sizeLabel,
                installed = component.id in existingInstalled,
                required = component.required || component.id == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID,
                iconKey = component.iconKey,
            )
        }.ifEmpty {
            manifests.map { manifest ->
                MaintenanceInstallationOption(
                    componentId = manifest.componentId,
                    displayName = manifest.displayName,
                    versionLabel = formatArtifactVersionLabel(manifest.version.name),
                    sizeLabel = formatArtifactSizeLabel(manifest.apkSizeBytes),
                    installed = manifest.componentId in existingInstalled,
                    required = manifest.required || manifest.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID,
                    iconKey = manifest.componentId,
                )
            }
        }
        // Installed rows are informational in this flow. Only missing required
        // applications enter the default install selection; users can opt into
        // missing optional applications explicitly below.
        val selected = options.filter { !it.installed && it.required }.map { it.componentId }.toSet()
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
                    routeAction = actionId,
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
        // Installed components are informational in this flow and never enter
        // the next install batch.
        if (option.installed || (!command.selected && option.required)) return
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
        val desktop = selection.options.firstOrNull {
            it.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID
        }
        val desktopSelected = AuthorizationPlanFactory.DESKTOP_COMPONENT_ID in selection.selectedComponentIds
        val desktopAlreadyInstalled = desktop?.installed == true
        if (!desktopSelected && !desktopAlreadyInstalled) {
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
            .filter { componentId ->
                selection.options.firstOrNull { it.componentId == componentId }?.installed != true
            }
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

        if (current.installationBatchReceipt != null) {
            // The immutable receipt is the terminal commit for this generation.
            // A connection observation may update only the device badge; any
            // late progress or duplicate terminal receipt is stale by definition.
            when (val event = command.event) {
                is InstallationSessionEvent.DeviceDisconnected -> {
                    val device = current.device
                    if (device != null && (event.deviceId == null || event.deviceId == device.id)) {
                        publish(
                            current.copy(
                                device = device.copy(connectionStatus = DeviceConnectionStatus.DISCONNECTED),
                            ),
                            acceptedEventSequence,
                        )
                    }
                }

                is InstallationSessionEvent.DeviceReconnected -> {
                    if (current.device?.id == event.device.id) {
                        publish(current.copy(device = event.device), acceptedEventSequence)
                    }
                }

                else -> Unit
            }
            return
        }

        when (val event = command.event) {
            is InstallationSessionEvent.DeviceDiscovered -> handleDeviceDiscovered(event.device)
            is InstallationSessionEvent.DiscoverySnapshot -> handleDiscoverySnapshot(event.devices)
            is InstallationSessionEvent.DiscoveryFinished -> handleDiscoveryFinished(event)
            is InstallationSessionEvent.DeviceConnectionConfirmed -> handleDeviceConnectionConfirmed(event.device)
            is InstallationSessionEvent.InitialInstalledApplicationsResolved ->
                handleInitialInstalledApplicationsResolved(event)
            is InstallationSessionEvent.InitialInstalledApplicationsFailed ->
                handleInitialInstalledApplicationsFailed(event)
            is InstallationSessionEvent.DeviceConnectionFailed -> handleDeviceConnectionFailed(event)
            is InstallationSessionEvent.DistributionConfigResolved -> handleDistributionConfigResolved(event)
            is InstallationSessionEvent.ArtifactBatchPrepared -> handleArtifactBatchPrepared(event)
            is InstallationSessionEvent.CatalogFailed -> handleCatalogFailed(event.reasonCode, event.retryable)
            is InstallationSessionEvent.ComponentProgressUpdated -> handleComponentProgressUpdated(event)
            is InstallationSessionEvent.InstallationStarted -> handleInstallationStarted(event.componentIds)
            is InstallationSessionEvent.InstallationBatchCompleted -> handleInstallationBatchCompleted(event)
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
                val initialInstall = !maintenanceReconnect && !installationReconnect
                val restoredState = if (installationReconnect) {
                    current.checkpoint?.state ?: InstallationSessionState.PAUSED
                } else {
                    InstallationSessionState.CONNECTED
                }
                val refreshedComponents = current.artifactManifests.map {
                    it.toComponentDescriptor(device.androidSdk)
                }
                val configuredComponents = current.maintenance.availableComponents
                    .ifEmpty { current.components }
                val retainedComponents = refreshedComponents
                    .takeIf { it.isNotEmpty() }
                    ?.let { mergeComponentDescriptors(configuredComponents, it) }
                    ?: configuredComponents
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
                    // A reconnect must enrich the retained full configuration;
                    // replacing it with the previously selected manifest subset
                    // is what made the installation page lose applications.
                    components = retainedComponents,
                    initialInventory = if (initialInstall) {
                        InitialApplicationInventory(state = InitialApplicationInventoryState.LOADING)
                    } else {
                        current.initialInventory
                    },
                    failure = null,
                    maintenanceReconnectPending = false,
                    installationReconnectPending = false,
                    maintenance = current.maintenance.copy(
                        availableComponents = current.maintenance.availableComponents
                            .ifEmpty { retainedComponents.filterNot { InstallerSelfIdentity.isSelfComponentId(it.id) } },
                    ),
                )
                publish(
                    if (maintenanceReconnect) next else withCheckpoint(next),
                    acceptedEventSequence,
                )
            }
        }
    }

    private fun handleInitialInstalledApplicationsResolved(
        event: InstallationSessionEvent.InitialInstalledApplicationsResolved,
    ) {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.CONNECTED ||
            current.initialInventory.state == InitialApplicationInventoryState.READY
        ) {
            return
        }
        val normalized = event.applications
            .filter { it.installed }
            .distinctBy { it.componentId }
        val knownIds = buildSet {
            addAll(current.components.map { it.id })
            addAll(com.ninepointnine.helper.domain.artifact.InstallerComponentTrustRegistry.ids())
        }
        val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        val invalid = normalized.any { application ->
            application.componentId !in knownIds ||
                !packagePattern.matches(application.packageName) ||
                !isKnownManagedPackage(current, application.componentId, application.packageName)
        }
        if (event.applications.size != normalized.size || invalid) {
            handleInitialInstalledApplicationsFailed(
                InstallationSessionEvent.InitialInstalledApplicationsFailed(
                    reasonCode = "initial_inventory_result_invalid",
                    retryable = false,
                ),
            )
            return
        }
        val inventory = InitialApplicationInventory(
            state = InitialApplicationInventoryState.READY,
            applications = normalized.sortedBy { it.componentId },
        )
        val installedIds = inventory.applications.mapTo(linkedSetOf()) { it.componentId }
        val nextComponents = markInitialInstalledComponents(current.components, installedIds)
        publish(
            current.copy(
                initialInventory = inventory,
                components = nextComponents,
                selectedOptionalComponentIds = current.selectedOptionalComponentIds - installedIds,
                failure = current.failure?.takeUnless(::isInitialInventoryFailure),
            ),
            acceptedEventSequence,
        )
    }

    private fun handleInitialInstalledApplicationsFailed(
        event: InstallationSessionEvent.InitialInstalledApplicationsFailed,
    ) {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.CONNECTED || event.reasonCode.isBlank()) return
        publish(
            current.copy(
                initialInventory = InitialApplicationInventory(
                    state = InitialApplicationInventoryState.FAILED,
                    failureReason = event.reasonCode,
                    failureRetryable = event.retryable,
                ),
                failure = SessionFailure(
                    category = FailureCategory.CONNECTION,
                    retryable = event.retryable,
                    reasonCode = event.reasonCode,
                ),
                checkpoint = null,
            ),
            acceptedEventSequence,
        )
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

    /**
     * Commits the complete preparation result for one immutable installation
     * batch. Preparation is intentionally atomic at the session boundary:
     * adapters may do local and remote work in any order, but the device phase
     * can only observe this one structured result.
     */
    private fun handleArtifactBatchPrepared(event: InstallationSessionEvent.ArtifactBatchPrepared) {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.PREPARING_ARTIFACTS) {
            fail(FailureCategory.UNKNOWN, reasonCode = "artifact_batch_event_out_of_order")
            return
        }
        val batch = current.installationBatch
        if (batch == null || event.batchId != batch.batchId) {
            fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = "artifact_batch_mismatch")
            return
        }

        val preparationIds = batch.preparationComponentIds
        val manifestsById = event.manifests.associateBy { it.componentId }
        val manifestIds = event.manifests.map { it.componentId }
        val failuresById = event.failures.mapNotNull { failure ->
            failure.componentId?.let { it to failure }
        }.toMap()
        val failureIds = failuresById.keys
        val duplicateManifest = manifestIds.size != manifestsById.size
        val duplicateFailure = event.failures.mapNotNull { it.componentId }.size != failureIds.size
        val unknownFailure = failureIds.any { it !in preparationIds }
        val unknownManifest = manifestIds.any { it !in preparationIds }
        val overlap = manifestIds.toSet() intersect failureIds
        if (
            duplicateManifest || duplicateFailure || unknownFailure || unknownManifest || overlap.isNotEmpty() ||
            manifestIds.toSet() + failureIds != preparationIds
        ) {
            fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = "artifact_batch_component_set_mismatch")
            return
        }
        if (event.failures.any { it.componentId == null || it.reasonCode.isBlank() }) {
            fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = "artifact_batch_failure_invalid")
            return
        }
        if (event.manifests.isNotEmpty()) {
            when (val validation = ArtifactManifestValidator.validateCatalog(event.manifests)) {
                is ManifestValidation.Invalid -> {
                    fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = validation.reasonCode)
                    return
                }

                ManifestValidation.Valid -> Unit
            }
        }
        if (event.manifests.any { manifest ->
                sourcePolicy.plan(manifest) is com.ninepointnine.helper.domain.artifact.SourcePlan.Rejected
            }
        ) {
            fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = "artifact_batch_source_policy_rejected")
            return
        }

        val selections = event.sourceSelections
        val selectionIds = selections.map { it.componentId }
        val expectedSelectionIds = manifestIds.toSet()
        if (
            selectionIds.size != selectionIds.toSet().size ||
            selectionIds.toSet() != expectedSelectionIds ||
            selections.any { selection ->
                manifestsById[selection.componentId]?.let { manifest ->
                    manifest.sources.any { it.kind == selection.sourceKind }
                } != true
            }
        ) {
            fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = "artifact_batch_source_evidence_invalid")
            return
        }

        val remoteManifestIds = event.manifests.filterNot { it.localOnly }.mapTo(linkedSetOf()) { it.componentId }
        fun <T> idsOf(items: List<T>, id: (T) -> String): Set<String> = items.mapTo(linkedSetOf(), id)
        val archiveIds = idsOf(event.archives) { it.componentId }
        val archiveVerificationIds = idsOf(event.archiveVerifications) { it.componentId }
        val extractionIds = idsOf(event.extractions) { it.componentId }
        val verificationIds = idsOf(event.verifications) { it.componentId }
        if (
            archiveIds != remoteManifestIds ||
            archiveVerificationIds != remoteManifestIds ||
            extractionIds != remoteManifestIds ||
            verificationIds != expectedSelectionIds
        ) {
            fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = "artifact_batch_evidence_set_mismatch")
            return
        }
        val archiveById = event.archives.associateBy { it.componentId }
        val archiveVerificationById = event.archiveVerifications.associateBy { it.componentId }
        val extractionById = event.extractions.associateBy { it.componentId }
        if (
            event.archives.size != archiveById.size ||
            event.archiveVerifications.size != archiveVerificationById.size ||
            event.extractions.size != extractionById.size ||
            event.verifications.size != verificationIds.size
        ) {
            fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = "artifact_batch_evidence_duplicate")
            return
        }
        val archiveEvidenceInvalid = event.manifests.any { manifest ->
            if (manifest.localOnly) {
                false
            } else {
                val archive = archiveById[manifest.componentId]
                val archiveVerification = archiveVerificationById[manifest.componentId]
                val extraction = extractionById[manifest.componentId]
                archive == null || archiveVerification == null || extraction == null ||
                    archive.sizeBytes != manifest.archiveSizeBytes ||
                    !archive.sha256.equals(manifest.archiveSha256, ignoreCase = true) ||
                    archiveVerification.sizeBytes != manifest.archiveSizeBytes ||
                    !archiveVerification.sha256.equals(manifest.archiveSha256, ignoreCase = true) ||
                    extraction.entryName != manifest.apkEntryName ||
                    extraction.sizeBytes != manifest.apkSizeBytes ||
                    !extraction.sha256.equals(manifest.apkSha256, ignoreCase = true)
            }
        }
        if (archiveEvidenceInvalid) {
            fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = "artifact_batch_archive_evidence_invalid")
            return
        }
        if (!event.verifications.all { verification ->
                val manifest = manifestsById[verification.componentId] ?: return@all false
                verification.sourceKind == selections.first { it.componentId == verification.componentId }.sourceKind &&
                    verification.archiveDeleted &&
                    verification.apkSizeBytes == manifest.apkSizeBytes &&
                    verification.apkSha256.equals(manifest.apkSha256, ignoreCase = true) &&
                    verification.packageName == manifest.packageName &&
                    verification.apkVersion == manifest.apkVersion &&
                    verification.certificateSha256.equals(manifest.certificateSha256, ignoreCase = true) &&
                    if (manifest.localOnly) {
                        verification.localDownload &&
                            verification.sourceKind == ArtifactSourceKind.LOCAL_DOWNLOAD &&
                            verification.archiveSizeBytes == 0L && verification.archiveSha256.isBlank()
                    } else {
                        verification.archiveSizeBytes == manifest.archiveSizeBytes &&
                            verification.archiveSha256.equals(manifest.archiveSha256, ignoreCase = true)
                    }
            }
        ) {
            fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = "artifact_batch_verification_invalid")
            return
        }

        val failedIds = failureIds
        val successfulIds = manifestIds.toSet()
        val sourceMap = selections.associate { it.componentId to it.sourceKind }
        val nextEvidence = current.evidence.copy(
            artifactsVerified = (current.evidence.artifactsVerified - preparationIds) + successfulIds,
            artifactVerifications = current.evidence.artifactVerifications
                .filterKeys { it !in preparationIds } + event.verifications.associateBy { it.componentId },
        )
        val nextComponents = current.components.map { component ->
            when {
                component.id in failedIds -> component.copy(
                    status = componentStatusForReasonCode(failuresById.getValue(component.id).reasonCode),
                    errorReason = failuresById.getValue(component.id).reasonCode,
                )
                component.id in successfulIds -> manifestsById.getValue(component.id)
                    .toComponentDescriptor(current.device?.androidSdk)
                else -> component
            }
        }
        val nextProgress = current.componentProgress.toMutableMap().apply {
            preparationIds.forEach { componentId ->
                val failure = failuresById[componentId]
                this[componentId] = ComponentProgress(
                    componentId = componentId,
                    phase = InstallPhase.CHECK,
                    status = if (failure == null) ComponentProgressStatus.COMPLETED else ComponentProgressStatus.FAILED,
                    fraction = if (failure == null) 1f else 0f,
                    indeterminate = false,
                )
            }
        }
        val next = current.copy(
            state = InstallationSessionState.ARTIFACTS_READY,
            artifactManifests = event.manifests,
            artifactCatalogStage = ArtifactCatalogStage.PREPARED,
            components = nextComponents,
            selectedSources = current.selectedSources - preparationIds + sourceMap,
            archiveDownloads = current.archiveDownloads - preparationIds + archiveById,
            archiveVerifications = current.archiveVerifications - preparationIds + archiveVerificationById,
            apkExtractions = current.apkExtractions - preparationIds + extractionById,
            failedComponentIds = (current.failedComponentIds - preparationIds) + failedIds,
            componentFailureRetryable = current.componentFailureRetryable.filterKeys { it !in preparationIds } +
                failuresById.mapValues { it.value.retryable },
            sourceFailures = (
                current.sourceFailures + failuresById.values.mapNotNull { failure ->
                    failure.sourceKind?.let { kind ->
                        SourceFailureRecord(
                            componentId = failure.componentId!!,
                            sourceKind = kind,
                            reasonCode = failure.reasonCode,
                            retryable = failure.retryable,
                        )
                    }
                }
            ).takeLast(MAX_SOURCE_FAILURE_RECORDS),
            evidence = nextEvidence,
            componentResults = buildComponentResults(nextEvidence, current.copy(
                components = nextComponents,
                failedComponentIds = failedIds,
                componentFailureRetryable = failuresById.mapValues { it.value.retryable },
                componentProgress = nextProgress,
                artifactManifests = event.manifests,
                artifactCatalogStage = ArtifactCatalogStage.PREPARED,
            )),
            componentProgress = nextProgress,
            progress = progress(
                fraction = 0.6f,
                indeterminate = false,
                completedCount = successfulIds.size,
            ),
            failure = null,
        )
        publish(withCheckpoint(next), acceptedEventSequence)
    }

    private fun catalogIdentity(snapshot: InstallationSessionSnapshot): InstallationCatalogIdentity? {
        if (snapshot.catalogRevision <= 0L) return null
        val version = snapshot.catalogVersion?.takeIf(String::isNotBlank) ?: return null
        val keyId = snapshot.catalogKeyId?.takeIf(String::isNotBlank) ?: return null
        val algorithm = snapshot.catalogSignatureAlgorithm?.takeIf(String::isNotBlank) ?: return null
        return InstallationCatalogIdentity(
            version = version,
            revision = snapshot.catalogRevision,
            keyId = keyId,
            signatureAlgorithm = algorithm,
        )
    }

    private fun handleCatalogFailed(reasonCode: String, retryable: Boolean) {
        val current = _snapshot.value
        if (current.state == InstallationSessionState.CONNECTED) {
            // A connected device is still usable; only the remote installation data is unavailable.
            // Clear the connected checkpoint so this cannot be presented as resumable installation work.
            publish(
                current.copy(
                    artifactManifests = emptyList(),
                    artifactCatalogStage = ArtifactCatalogStage.NOT_LOADED,
                    installationBatch = null,
                    failure = SessionFailure(
                        category = FailureCategory.VERIFICATION,
                        retryable = retryable,
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
            retryable = retryable,
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
        val initiallyInstalledIds = initialInstalledComponentIds(current)
        val displayedComponents = markInitialInstalledComponents(components, initiallyInstalledIds)
        val ids = displayedComponents.map { it.id }
        if (
            ids.size != ids.toSet().size ||
            displayedComponents.none { it.id == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID } ||
            displayedComponents.any { it.displayName.isBlank() }
        ) {
            fail(FailureCategory.VERIFICATION, retryable = false, reasonCode = "distribution_config_components_invalid")
            return
        }
        val selectableIds = displayedComponents.filterNot(::isMandatory).map { it.id }.toSet()
        val recommendedIds = displayedComponents
            .filter { it.required && !isMandatory(it) }
            .map { it.id }
            .toSet()
        // The first control-plane snapshot is the user's initial install
        // choice: every non-desktop component starts checked. Once a catalog
        // identity exists, retain explicit opt-outs across a refresh and only
        // re-apply the protocol's required-as-recommendation entries.
        // Metadata can survive a failed catalog attempt for rollback checks;
        // CONTROL_PLANE_READY is the explicit boundary that a prior catalog
        // was accepted for the current selection page. This keeps a retry's
        // first successful load at the documented all-optional-default state.
        val hasResolvedCatalog = current.components.isNotEmpty() &&
            current.artifactCatalogStage == ArtifactCatalogStage.CONTROL_PLANE_READY
        val selectedOptionalIds = if (hasResolvedCatalog) {
            ((current.selectedOptionalComponentIds intersect selectableIds) + recommendedIds) - initiallyInstalledIds
        } else {
            selectableIds - initiallyInstalledIds
        }
        publish(
            current.copy(
                components = displayedComponents,
                artifactManifests = emptyList(),
                artifactCatalogStage = ArtifactCatalogStage.CONTROL_PLANE_READY,
                installationBatch = null,
                catalogVersion = event.configVersion,
                catalogRevision = event.catalogRevision.coerceAtLeast(current.catalogRevision),
                catalogKeyId = event.keyId,
                catalogSignatureAlgorithm = event.signatureAlgorithm,
                selectedOptionalComponentIds = selectedOptionalIds,
                currentComponentName = null,
                progress = null,
                failedComponentIds = emptySet(),
                componentFailureRetryable = event.appFailureRetryable,
                evidence = SessionEvidence(),
                componentResults = emptyList(),
                checkpoint = null,
                selectedSources = emptyMap(),
                sourceFailures = emptyList(),
                archiveDownloads = emptyMap(),
                archiveVerifications = emptyMap(),
                apkExtractions = emptyMap(),
                failure = null,
                maintenance = current.maintenance.copy(
                    availableComponents = displayedComponents,
                    managedApplicationsState = current.maintenance.managedApplicationsState,
                ),
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

    private fun handleInstallationStarted(componentIds: List<String>) {
        if (!requireState(InstallationSessionState.ARTIFACTS_READY, "installation_start_out_of_order")) return
        val current = _snapshot.value
        val expected = successfulComponentIds(current)
        val preparationIds = preparationComponentIds(current)
        val requested = componentIds.toSet().ifEmpty { expected }
        if (requested != expected || !preparationIds.all { it in current.evidence.artifactsVerified }) {
            fail(FailureCategory.VERIFICATION, reasonCode = "artifact_evidence_incomplete")
            return
        }
        if (current.artifactCatalogStage != ArtifactCatalogStage.PREPARED ||
            current.evidence.artifactVerifications.keys != preparationIds
        ) {
            fail(FailureCategory.VERIFICATION, reasonCode = "artifact_identity_evidence_missing")
            return
        }
        transition(
            state = InstallationSessionState.INSTALLING,
            progress = progress(0.65f, indeterminate = true),
            componentProgress = markComponents(current, InstallPhase.SEND, ComponentProgressStatus.RUNNING),
        )
    }

    /** Commits the complete device receipt exactly once for the active batch. */
    private fun handleInstallationBatchCompleted(event: InstallationSessionEvent.InstallationBatchCompleted) {
        if (!requireState(InstallationSessionState.INSTALLING, "installation_batch_event_out_of_order")) return
        val current = _snapshot.value
        val batch = current.installationBatch
        if (batch == null) {
            fail(FailureCategory.INSTALLATION, reasonCode = "installation_batch_missing")
            return
        }
        event.receipt.validationFailure(
            plan = batch,
            manifests = trustedManifests(current),
            baseline = current.evidence,
        )?.let { reason ->
            fail(FailureCategory.INSTALLATION, retryable = false, reasonCode = reason)
            return
        }
        val receipt = event.receipt
        val evidence = receipt.toSessionEvidence(current)
        val results = receipt.toComponentResults(current)
        val hasFailure = results.any { it.status == ComponentResultStatus.NOT_INSTALLED }
        val hasPending = results.any { it.status == ComponentResultStatus.INSTALLATION_PENDING_CONFIRMATION }
        val hasPostFailure = results.any {
            it.status == ComponentResultStatus.AUTHORIZATION_INCOMPLETE ||
                it.status == ComponentResultStatus.AVAILABILITY_INCOMPLETE
        }
        val terminalState = when {
            !hasFailure && !hasPending && !hasPostFailure -> InstallationSessionState.SUCCEEDED
            else -> InstallationSessionState.COMPLETED_WITH_ERRORS
        }
        val installedIds = receipt.components
            .filter { it.installation.status == InstallationStageReceiptStatus.VERIFIED }
            .mapTo(linkedSetOf()) { it.componentId }
        val maintenance = current.maintenance.withVerifiedInstallations(
            trustedManifests(current).values.filter { it.componentId in installedIds },
        )
        val completedUpdateIds = results.asSequence()
            .filter { it.status == ComponentResultStatus.READY }
            .mapNotNull { it.componentId }
            .toSet()
        val updateManifestById = trustedManifests(current)
        val updateStatuses = maintenance.updateStatuses.map { status ->
            val canonicalId = if (InstallerSelfIdentity.isSelfComponentId(status.componentId)) {
                InstallerSelfIdentity.COMPONENT_ID
            } else {
                status.componentId
            }
            if (canonicalId in completedUpdateIds) {
                val manifest = updateManifestById[canonicalId]
                status.copy(
                    state = MaintenanceUpdateState.CURRENT,
                    installedVersionLabel = manifest?.version?.name ?: status.versionLabel,
                )
            } else {
                status
            }
        }
        val maintenanceWithUpdateResult = if (
            current.maintenance.routeAction == MaintenanceActionId.CHECK_UPDATES
        ) {
            maintenance.copy(
                updateStatuses = updateStatuses,
                activeAction = null,
                lastAction = MaintenanceActionRecord(
                    actionId = MaintenanceActionId.CHECK_UPDATES,
                    status = if (terminalState == InstallationSessionState.SUCCEEDED) {
                        MaintenanceActionStatus.SUCCEEDED
                    } else {
                        MaintenanceActionStatus.FAILED
                    },
                    resultCode = if (terminalState == InstallationSessionState.SUCCEEDED) {
                        "update_completed"
                    } else {
                        null
                    },
                    reasonCode = if (terminalState == InstallationSessionState.SUCCEEDED) {
                        null
                    } else {
                        results.firstOrNull { it.failureReason != null }?.failureReason
                            ?: "update_failed"
                    },
                    retryable = terminalState != InstallationSessionState.SUCCEEDED,
                ),
            )
        } else {
            maintenance
        }
        val resultById = results.associateBy { it.componentId }
        val terminalProgress = receipt.components.associate { component ->
            val result = checkNotNull(resultById[component.componentId])
            component.componentId to ComponentProgress(
                componentId = component.componentId,
                phase = when (result.status) {
                    ComponentResultStatus.NOT_INSTALLED,
                    ComponentResultStatus.INSTALLATION_PENDING_CONFIRMATION,
                    -> InstallPhase.SEND
                    ComponentResultStatus.AUTHORIZATION_INCOMPLETE -> InstallPhase.CONFIGURE
                    ComponentResultStatus.AVAILABILITY_INCOMPLETE,
                    ComponentResultStatus.READY,
                    -> InstallPhase.VERIFY
                },
                status = when (result.status) {
                    ComponentResultStatus.READY,
                    ComponentResultStatus.INSTALLATION_PENDING_CONFIRMATION,
                    -> ComponentProgressStatus.COMPLETED
                    ComponentResultStatus.NOT_INSTALLED,
                    ComponentResultStatus.AUTHORIZATION_INCOMPLETE,
                    ComponentResultStatus.AVAILABILITY_INCOMPLETE,
                    -> ComponentProgressStatus.FAILED
                },
                fraction = 1f,
                indeterminate = false,
            )
        }
        transition(
            state = terminalState,
            evidence = evidence,
            maintenance = maintenanceWithUpdateResult,
            componentResults = results,
            installationBatchReceipt = receipt,
            progress = progress(1f, indeterminate = false, completedCount = selectedComponents(current).size),
            checkpoint = null,
            componentProgress = terminalProgress,
        )
    }

    private fun validateAuthorizationEvidence(
        evidence: List<com.ninepointnine.helper.domain.device.AuthorizationActionEvidence>,
        snapshot: InstallationSessionSnapshot,
        expectedIds: Set<String> = snapshot.evidence.installed - snapshot.failedComponentIds,
    ): Boolean {
        if (expectedIds.isEmpty()) return evidence.isEmpty()
        val manifests = trustedManifests(snapshot).values.filter { it.componentId in expectedIds }
        if (manifests.size != expectedIds.size) return false
        val plan = when (val result = AuthorizationPlanFactory.createForManifests(
            manifests,
            requireDesktop = false,
        )) {
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
        val manifests = trustedManifests(snapshot)
        return evidence.all { item ->
            val manifest = manifests[item.componentId] ?: return@all false
            val isLaunchTarget = item.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID
            item.packageName == manifest.packageName &&
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

    private fun isSafeApkEntry(value: String): Boolean =
        value.isNotBlank() &&
            !value.startsWith('/') &&
            !value.startsWith('\\') &&
            !value.contains('/') &&
            !value.contains('\\') &&
            !value.contains("..") &&
            !value.contains('\u0000')

    private fun selectedComponentIds(snapshot: InstallationSessionSnapshot): Set<String> =
        snapshot.installationBatch?.selectedComponentIds
            ?: snapshot.components.filter { isMandatory(it) || it.id in snapshot.selectedOptionalComponentIds }
                .map { it.id }
                .toSet()

    /**
     * Result rows describe the user's current batch, not the retained catalog
     * or historical component statuses. A reusable maintenance prerequisite is
     * an internal dependency and must not appear as a newly installed app.
     */
    private fun resultComponentIds(snapshot: InstallationSessionSnapshot): Set<String> {
        snapshot.installationBatch?.let { return it.resultComponentIds }
        val selected = selectedComponentIds(snapshot)
        return if (snapshot.installationFlow == InstallationFlow.MAINTENANCE_INSTALL) {
            selected - reusableInstalledComponentIds(snapshot)
        } else {
            selected
        }
    }

    private fun successfulComponentIds(snapshot: InstallationSessionSnapshot): Set<String> =
        selectedComponentIds(snapshot) - snapshot.failedComponentIds

    private fun componentName(snapshot: InstallationSessionSnapshot, componentId: String): String? =
        snapshot.components.firstOrNull { it.id == componentId }?.displayName

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
                    routeAction = event.actionId,
                    lastAction = MaintenanceActionRecord(
                        actionId = event.actionId,
                        status = MaintenanceActionStatus.SUCCEEDED,
                        resultCode = event.resultCode,
                    ),
                    managedApplicationsFailureReason = current.maintenance.managedApplicationsFailureReason,
                    managedApplicationsFailureRetryable = current.maintenance.managedApplicationsFailureRetryable,
                    authorization = if (
                        event.actionId == MaintenanceActionId.REPAIR_CONFIGURATION &&
                        event.resultCode == "authorization_repaired"
                    ) {
                        current.maintenance.authorization.copy(
                            state = MaintenanceAuthorizationFlowState.COMPLETED,
                            currentComponentId = null,
                            applications = current.maintenance.authorization.applications.map { application ->
                                application.copy(
                                    authorized = true,
                                    state = MaintenanceAuthorizationState.AUTHORIZED,
                                    reasonCode = null,
                                )
                            },
                        )
                    } else {
                        current.maintenance.authorization
                    },
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
                maintenance = failedMaintenanceAction(
                    current.maintenance,
                    event.actionId,
                    event.reasonCode,
                    event.retryable,
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
                    managedApplications = event.refreshedApplications?.filter { it.installed }
                        ?: if (event.actionId == MaintenanceApplicationActionId.UNINSTALL) {
                            current.maintenance.managedApplications.filterNot { it.componentId == event.componentId }
                        } else {
                            current.maintenance.managedApplications
                        },
                    installedManifests = if (event.actionId == MaintenanceApplicationActionId.UNINSTALL) {
                        current.maintenance.installedManifests.filterNot { it.componentId == event.componentId }
                    } else {
                        current.maintenance.installedManifests
                    },
                    managedApplicationsState = if (event.refreshedApplications != null) {
                        MaintenanceInventoryState.READY
                    } else if (event.inventoryRefreshFailureReason != null) {
                        MaintenanceInventoryState.FAILED
                    } else {
                        current.maintenance.managedApplicationsState
                            .takeUnless { event.actionId == MaintenanceApplicationActionId.UNINSTALL }
                            ?: MaintenanceInventoryState.READY
                    },
                    managedApplicationsFailureReason = event.inventoryRefreshFailureReason,
                    managedApplicationsFailureRetryable = if (event.inventoryRefreshFailureReason != null) {
                        event.inventoryRefreshRetryable
                    } else {
                        false
                    },
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
                        applications = current.maintenance.managedApplications
                            .filter { it.componentId in event.componentIds }
                            .map { application ->
                                ManagedApplicationAuthorizationStatus(
                                    componentId = application.componentId,
                                    packageName = application.packageName,
                                    authorized = null,
                                    state = MaintenanceAuthorizationState.CHECKING,
                                )
                            },
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
        val activeAction = current.maintenance.activeAction
        val acceptedActions = setOf(
            MaintenanceActionId.MANAGE_APPS,
            MaintenanceActionId.REPAIR_CONFIGURATION,
            MaintenanceActionId.INSTALL_APPLICATIONS,
            MaintenanceActionId.INSTALL_FILE_MANAGER,
        )
        val applicationRefreshInFlight = current.maintenance.applicationAction?.let { action ->
            action.status == MaintenanceActionStatus.RUNNING &&
                action.actionId == MaintenanceApplicationActionId.UNINSTALL
        } == true
        val knownIds = buildSet {
            addAll(current.components.map { it.id })
            addAll(current.artifactManifests.map { it.componentId })
            addAll(current.maintenance.installedManifests.map { it.componentId })
            addAll(current.maintenance.availableManifests.map { it.componentId })
            addAll(current.maintenance.managedApplications.map { it.componentId })
            addAll(com.ninepointnine.helper.domain.artifact.InstallerComponentTrustRegistry.ids())
        }
        val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        val normalizedApplications = event.applications
            .filter { it.installed }
            .distinctBy { it.componentId }
        val invalid = normalizedApplications.any { application ->
            application.componentId !in knownIds ||
                !packagePattern.matches(application.packageName) ||
                !isKnownManagedPackage(current, application.componentId, application.packageName)
        }
        if (current.state != InstallationSessionState.MAINTENANCE ||
            (activeAction !in acceptedActions && !applicationRefreshInFlight) ||
            event.applications.size != normalizedApplications.size ||
            invalid
        ) {
            if (
                current.state == InstallationSessionState.MAINTENANCE &&
                (activeAction in acceptedActions || applicationRefreshInFlight)
            ) {
                failMaintenanceAction("maintenance_applications_invalid", retryable = false)
            }
            return
        }
        publish(
            current.copy(
                maintenance = current.maintenance.copy(
                    managedApplications = normalizedApplications,
                    installedManifests = current.maintenance.installedManifests.filter { baseline ->
                        normalizedApplications.any { it.componentId == baseline.componentId }
                    },
                    managedApplicationsState = MaintenanceInventoryState.READY,
                    managedApplicationsFailureReason = null,
                    managedApplicationsFailureRetryable = false,
                ),
            ),
            acceptedEventSequence,
        )
        if (activeAction?.isApplicationInstallation == true) {
            beginMaintenanceInstallationSelection(activeAction)
        }
    }

    private fun isKnownManagedPackage(
        snapshot: InstallationSessionSnapshot,
        componentId: String,
        packageName: String,
    ): Boolean {
        val batchManifests = if (snapshot.artifactCatalogStage == ArtifactCatalogStage.PREPARED) {
            snapshot.artifactManifests
        } else {
            emptyList()
        }
        val manifestPackage = (
            batchManifests +
                snapshot.maintenance.installedManifests +
                snapshot.maintenance.availableManifests
            )
            .firstOrNull { it.componentId == componentId }
            ?.packageName
        if (manifestPackage == packageName) return true
        if (snapshot.evidence.installation.any {
                it.key == componentId && it.value.packageName == packageName
            }
        ) return true
        if (snapshot.maintenance.managedApplications.any {
                it.componentId == componentId && it.packageName == packageName
            }
        ) return true
        return com.ninepointnine.helper.domain.artifact.InstallerComponentTrustRegistry
            .isAllowedPackageName(componentId, packageName)
    }

    private fun handleMaintenanceCatalogRefreshed(event: InstallationSessionEvent.MaintenanceCatalogRefreshed) {
        val current = _snapshot.value
        if (current.state != InstallationSessionState.MAINTENANCE ||
            current.maintenance.activeAction !in setOf(
                MaintenanceActionId.CHECK_UPDATES,
                MaintenanceActionId.INSTALL_APPLICATIONS,
                MaintenanceActionId.INSTALL_FILE_MANAGER,
            )
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
        if (!event.controlPlaneOnly) {
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
        }
        val installableManifests = event.manifests.filterNot {
            InstallerSelfIdentity.isSelfComponentId(it.componentId)
        }
        val previousCatalogManifests = (
            current.maintenance.installedManifests + current.maintenance.availableManifests
            ).distinctBy { it.componentId }
        val manifestDescriptors = installableManifests.map { manifest ->
            val descriptor = manifest.toComponentDescriptor(current.device?.androidSdk)
            when {
                descriptor.compatibilityState == ComponentCompatibility.UNSUPPORTED ->
                    descriptor.copy(status = ComponentStatus.CLIENT_CAPABILITY_INSUFFICIENT, errorReason = "component_incompatible")
                previousCatalogManifests.none { it.componentId == manifest.componentId } ->
                    descriptor.copy(status = ComponentStatus.NEW)
                previousCatalogManifests.firstOrNull { it.componentId == manifest.componentId }?.let { old ->
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
            !event.controlPlaneOnly && installableManifests.any { it.componentId !in componentIds }
        ) {
            failMaintenanceAction("maintenance_catalog_components_invalid", retryable = false)
            return
        }
        val selectableIds = components.filterNot(::isMandatory)
            .filter { isSelectable(it) }
            .map { it.id }.toSet()
        val availableIds = components.map { it.id }.toSet()
        val installedIds = current.maintenance.managedApplications
            .filter { it.installed }
            .map { it.componentId }
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
        // A maintenance refresh starts a new catalog decision. It must not
        // leave a previous installation batch looking prepared for the next
        // click. Full manifests live in the maintenance catalog; the batch is
        // populated only after the user confirms a selection.
        val availableManifests = if (event.controlPlaneOnly) emptyList() else installableManifests
        publish(
            current.copy(
                maintenance = current.maintenance.copy(
                    availableManifests = availableManifests,
                    availableComponents = components,
                    availableCatalogVersion = event.catalogVersion,
                    availableCatalogRevision = event.catalogRevision,
                    availableCatalogKeyId = event.keyId,
                    availableCatalogSignatureAlgorithm = event.signatureAlgorithm,
                    updateStatuses = event.updateStatuses,
                    catalogControlPlaneOnly = event.controlPlaneOnly,
                ),
                components = mergedComponents,
                artifactManifests = emptyList(),
                artifactCatalogStage = if (event.controlPlaneOnly) {
                    ArtifactCatalogStage.CONTROL_PLANE_READY
                } else {
                    ArtifactCatalogStage.NOT_LOADED
                },
                installationBatch = null,
                componentFailureRetryable = emptyMap(),
                catalogVersion = event.catalogVersion,
                catalogRevision = event.catalogRevision,
                catalogKeyId = event.keyId,
                catalogSignatureAlgorithm = event.signatureAlgorithm,
                selectedOptionalComponentIds = current.selectedOptionalComponentIds.filterTo(mutableSetOf()) {
                    it in selectableIds
                },
            ),
            acceptedEventSequence,
        )
        if (current.maintenance.activeAction?.isApplicationInstallation == true &&
            current.maintenance.managedApplicationsState == MaintenanceInventoryState.READY
        ) {
            beginMaintenanceInstallationSelection(checkNotNull(current.maintenance.activeAction))
        }
    }

    private fun failMaintenanceAction(reasonCode: String, retryable: Boolean) {
        val current = _snapshot.value
        val actionId = current.maintenance.activeAction ?: return
        publish(
            current.copy(
                maintenance = failedMaintenanceAction(
                    current.maintenance,
                    actionId,
                    reasonCode,
                    retryable,
                ),
            ),
            acceptedEventSequence,
        )
    }

    /**
     * Inventory is one phase of several maintenance actions. Once a live list
     * has been accepted, a later catalog or authorization failure must not
     * rewrite that successful read as an inventory failure.
     */
    private fun failedMaintenanceAction(
        maintenance: MaintenanceSnapshot,
        actionId: MaintenanceActionId,
        reasonCode: String,
        retryable: Boolean,
    ): MaintenanceSnapshot {
        val inventoryFailed = actionId in MAINTENANCE_INVENTORY_ACTIONS &&
            maintenance.managedApplicationsState != MaintenanceInventoryState.READY
        return maintenance.copy(
            activeAction = null,
            lastAction = MaintenanceActionRecord(
                actionId = actionId,
                status = MaintenanceActionStatus.FAILED,
                reasonCode = reasonCode,
                retryable = retryable,
            ),
            updateStatuses = if (actionId == MaintenanceActionId.CHECK_UPDATES) {
                emptyList()
            } else {
                maintenance.updateStatuses
            },
            managedApplicationsState = if (inventoryFailed) {
                MaintenanceInventoryState.FAILED
            } else {
                maintenance.managedApplicationsState
            },
            managedApplicationsFailureReason = if (inventoryFailed) {
                reasonCode
            } else {
                maintenance.managedApplicationsFailureReason
            },
            managedApplicationsFailureRetryable = if (inventoryFailed) {
                retryable
            } else {
                maintenance.managedApplicationsFailureRetryable
            },
            authorization = if (actionId == MaintenanceActionId.REPAIR_CONFIGURATION) {
                maintenance.authorization.copy(
                    state = MaintenanceAuthorizationFlowState.FAILED,
                    currentComponentId = null,
                )
            } else {
                maintenance.authorization
            },
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
        maintenance: MaintenanceSnapshot = _snapshot.value.maintenance,
        componentResults: List<ComponentResult> = _snapshot.value.componentResults,
        checkpoint: SessionCheckpoint? = _snapshot.value.checkpoint,
        selectedSources: Map<String, ArtifactSourceKind> = _snapshot.value.selectedSources,
        archiveDownloads: Map<String, ArchiveDownloadEvidence> = _snapshot.value.archiveDownloads,
        archiveVerifications: Map<String, ArchiveVerificationEvidence> = _snapshot.value.archiveVerifications,
        apkExtractions: Map<String, ApkExtractionEvidence> = _snapshot.value.apkExtractions,
        installationBatchReceipt: InstallationBatchReceipt? = _snapshot.value.installationBatchReceipt,
    ) {
        val current = _snapshot.value
        val next = current.copy(
            state = state,
            progress = progress,
            componentProgress = componentProgress,
            evidence = evidence,
            maintenance = maintenance,
            componentResults = componentResults,
            checkpoint = checkpoint,
            selectedSources = selectedSources,
            archiveDownloads = archiveDownloads,
            archiveVerifications = archiveVerifications,
            apkExtractions = apkExtractions,
            installationBatchReceipt = installationBatchReceipt,
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
        val componentResults = componentName?.let { failedComponent ->
            current.componentResults.map { result ->
                if (
                    result.failureReason == null &&
                    (result.componentId == failedComponent || result.componentName == failedComponent)
                ) {
                    result.copy(
                        failureReason = reasonCode,
                        failurePhase = installPhaseForReasonCode(reasonCode),
                        retryable = retryable,
                    )
                } else {
                    result
                }
            }
        } ?: current.componentResults
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
                componentResults = componentResults,
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
        val trusted = trustedManifests(snapshot)
        if (snapshot.artifactCatalogStage == ArtifactCatalogStage.PREPARED && trusted.isNotEmpty()) {
            when (val validation = ArtifactManifestValidator.validateCatalog(trusted.values.toList())) {
                is ManifestValidation.Invalid -> return validation.reasonCode
                ManifestValidation.Valid -> Unit
            }
            val manifestIds = trusted.keys
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
            val incompatible = trusted.values.any { manifest ->
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
        val initiallyInstalledIds = initialInstalledComponentIds(snapshot)
        if (!snapshot.selectedOptionalComponentIds.all { it in selectableIds || it in initiallyInstalledIds }) {
            return "unknown_component"
        }

        val selected = selectedComponents(snapshot)
        if (selected.isEmpty()) return "required_components_missing"
        if (snapshot.state == InstallationSessionState.SELECTION_CONFIRMED) {
            val reusableSelected = reusableInstalledComponentIds(snapshot) intersect selected.map { it.id }.toSet()
            if (reusableSelected.any { it !in trusted }) {
                return "installed_component_manifest_unavailable"
            }
            if (snapshot.artifactCatalogStage == ArtifactCatalogStage.PREPARED) {
                val unresolved = selected.map { it.id }
                    .filterNot { it in snapshot.failedComponentIds }
                    .filterNot { it in trusted }
                if (unresolved.isNotEmpty()) return "component_unavailable"
            }
        }
        if (snapshot.artifactCatalogStage != ArtifactCatalogStage.PREPARED) {
            if (selected.any { component ->
                    !isSelectable(component) &&
                        !isAlreadyInstalledMaintenanceComponent(snapshot, component.id) &&
                        component.id !in initiallyInstalledIds
                }) {
                return "component_unavailable"
            }
        }
        return null
    }

    private fun selectedComponents(snapshot: InstallationSessionSnapshot): List<ComponentDescriptor> {
        val selectedIds = selectedComponentIds(snapshot)
        return snapshot.components.filter { it.id in selectedIds }
    }

    /**
     * A missing-only maintenance batch can reuse only packages confirmed by the
     * same live inventory that built the selection page. The rule intentionally
     * does not depend on the transient session state: after selection is
     * confirmed the state becomes SELECTION_CONFIRMED while the inventory is
     * still the authoritative source for the batch.
     */
    /**
     * Returns only identities valid for this batch. Prepared manifests belong
     * to the current batch; maintenance manifests may be used only for a
     * live-inventory-confirmed component that is explicitly being skipped.
     */
    private fun trustedManifests(snapshot: InstallationSessionSnapshot): Map<String, ArtifactManifest> {
        val byId = linkedMapOf<String, ArtifactManifest>()
        if (snapshot.artifactCatalogStage == ArtifactCatalogStage.PREPARED) {
            snapshot.artifactManifests.forEach { manifest ->
                byId[manifest.componentId] = manifest
            }
        }
        val reusableIds = reusableInstalledComponentIds(snapshot)
        reusableIds.forEach { componentId ->
            installedIdentityManifest(snapshot, componentId)?.let { manifest ->
                byId[componentId] = manifest
            }
        }
        return byId
    }

    /**
     * Update batches use REINSTALL_SELECTED for the requested row, while a
     * trusted desktop package may still be reused as a prerequisite. Keep this
     * rule separate from missing-only installs so a target update can never be
     * accidentally classified as reusable.
     */
    private fun reusableMaintenancePrerequisiteIds(
        snapshot: InstallationSessionSnapshot,
        excludedIds: Set<String>,
    ): Set<String> {
        if (snapshot.maintenance.managedApplicationsState != MaintenanceInventoryState.READY) {
            return emptySet()
        }
        return snapshot.maintenance.managedApplications.asSequence()
            .filter { it.installed && it.componentId !in excludedIds }
            .filter { application ->
                val manifest = installedIdentityManifest(snapshot, application.componentId) ?: return@filter false
                application.packageName == manifest.packageName &&
                    application.versionCode == manifest.apkVersion.code
            }
            .map { it.componentId }
            .toSet()
    }

    /** Components whose APK/source evidence must be produced in this attempt. */
    private fun preparationComponentIds(snapshot: InstallationSessionSnapshot): Set<String> =
        (snapshot.installationBatch?.preparationComponentIds
            ?: (selectedComponentIds(snapshot) - reusableInstalledComponentIds(snapshot))) -
            snapshot.failedComponentIds

    /**
     * Merges a partial preparation/refresh result into the retained full
     * configuration without losing rows that were not selected in this run.
     */
    private fun mergeComponentDescriptors(
        base: List<ComponentDescriptor>,
        updates: List<ComponentDescriptor>,
    ): List<ComponentDescriptor> {
        if (base.isEmpty()) return updates.distinctBy { it.id }
        if (updates.isEmpty()) return base.distinctBy { it.id }
        val updatesById = updates.associateBy { it.id }
        val merged = base.map { existing ->
            val update = updatesById[existing.id] ?: return@map existing
            existing.copy(
                displayName = update.displayName.ifBlank { existing.displayName },
                // Never let a partial preparation response relax a required
                // row that was already trusted by the full configuration.
                required = existing.required || update.required,
                versionLabel = update.versionLabel ?: existing.versionLabel,
                sizeLabel = update.sizeLabel ?: existing.sizeLabel,
                compatibilityLabel = update.compatibilityLabel ?: existing.compatibilityLabel,
                compatibilityState = if (update.compatibilityState == ComponentCompatibility.UNKNOWN) {
                    existing.compatibilityState
                } else {
                    update.compatibilityState
                },
                iconKey = update.iconKey.ifBlank { existing.iconKey },
                iconAsset = update.iconAsset ?: existing.iconAsset,
                description = update.description.ifBlank { existing.description },
                status = update.status,
                errorReason = update.errorReason,
            )
        }.toMutableList()
        updates.filter { update -> base.none { it.id == update.id } }
            .forEach(merged::add)
        return merged.distinctBy { it.id }
    }

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

    private fun isAlreadyInstalledMaintenanceComponent(
        snapshot: InstallationSessionSnapshot,
        componentId: String,
    ): Boolean = snapshot.installationStrategy == InstallationStrategy.INSTALL_MISSING_ONLY &&
        snapshot.maintenance.managedApplicationsState == MaintenanceInventoryState.READY &&
        snapshot.maintenance.managedApplications.any {
            it.componentId == componentId && it.installed
        }

    private fun initialInstalledComponentIds(snapshot: InstallationSessionSnapshot): Set<String> =
        snapshot.initialInventory.applications.asSequence()
            .filter { it.installed }
            .mapTo(linkedSetOf()) { it.componentId }

    private fun markInitialInstalledComponents(
        components: List<ComponentDescriptor>,
        installedIds: Set<String>,
    ): List<ComponentDescriptor> = components.map { component ->
        if (component.id in installedIds) {
            component.copy(
                status = ComponentStatus.INSTALLED_LATEST,
                errorReason = null,
            )
        } else {
            component
        }
    }

    private fun isInitialInventoryFailure(failure: SessionFailure): Boolean =
        failure.reasonCode?.startsWith("initial_inventory") == true

    private fun allInitialComponentsInstalled(snapshot: InstallationSessionSnapshot): Boolean {
        if (snapshot.initialInventory.state != InitialApplicationInventoryState.READY) return false
        val targetIds = snapshot.components
            .filter { it.status != ComponentStatus.UNLISTED }
            .mapTo(linkedSetOf()) { it.id }
        return targetIds.isNotEmpty() && targetIds.all { it in initialInstalledComponentIds(snapshot) }
    }

    private fun catalogFailureStatus(reasonCode: String): ComponentStatus =
        componentStatusForReasonCode(reasonCode)

    private fun buildComponentResults(
        evidence: SessionEvidence,
        snapshot: InstallationSessionSnapshot,
    ): List<ComponentResult> {
        val pendingIds = snapshot.copy(evidence = evidence).confirmationPendingComponentIds()
        return snapshot.components.filter { it.id in resultComponentIds(snapshot) }.map { component ->
            // A descriptor error belongs to the catalog/selection surface. It may
            // describe one unavailable source even when a different source later
            // produced a verified APK. Only an explicit failure recorded for this
            // installation batch may affect the terminal result row.
            val failed = component.id in snapshot.failedComponentIds
            val sourceFailure = if (failed) {
                snapshot.sourceFailures.asSequence()
                    .filter { it.componentId == component.id }
                    .lastOrNull()
            } else {
                null
            }
            val failureReason = if (failed) {
                sourceFailure?.reasonCode ?: component.errorReason
            } else {
                null
            }
            val confirmationPending = !failed && component.id in pendingIds
            ComponentResult(
                componentName = component.displayName,
                installed = component.id in evidence.installed,
                configured = component.id in evidence.configured,
                available = component.id in evidence.available,
                componentId = component.id,
                writeConfirmed = component.id in evidence.writeConfirmed,
                confirmationPending = confirmationPending,
                failureReason = failureReason,
                failurePhase = snapshot.componentProgress[component.id]
                    ?.takeIf { it.status == ComponentProgressStatus.FAILED }
                    ?.phase
                    ?: sourceFailure?.let { failurePhaseFor(it.reasonCode) }
                    ?: failureReason?.let(::failurePhaseFor),
                retryable = sourceFailure?.retryable
                    ?: snapshot.componentFailureRetryable[component.id]
                    ?: false,
            )
        }
    }

    private fun failurePhaseFor(reasonCode: String): InstallPhase =
        installPhaseForReasonCode(reasonCode)

    private fun hasCompleteSuccessEvidence(snapshot: InstallationSessionSnapshot): Boolean =
        hasCompleteSuccessEvidence(snapshot.evidence, snapshot)

    private fun hasCompleteSuccessEvidence(
        evidence: SessionEvidence,
        snapshot: InstallationSessionSnapshot,
    ): Boolean {
        snapshot.installationBatchReceipt?.let { receipt ->
            return receipt.toComponentResults(snapshot).all { result ->
                result.status == ComponentResultStatus.READY
            }
        }
        val selectedIds = selectedComponentIds(snapshot)
        val pendingIds = snapshot.copy(evidence = evidence).confirmationPendingComponentIds()
        val expected = successfulComponentIds(snapshot) - pendingIds
        if (expected.isEmpty()) {
            // A terminal batch may consist entirely of explicit failures or
            // readback-pending components. That is a complete *classification*
            // even though it is not a success proof.
            return selectedIds.isNotEmpty() &&
                (snapshot.failedComponentIds.intersect(selectedIds).isNotEmpty() || pendingIds.isNotEmpty())
        }
        // A component can have a complete install proof and fail later during
        // authorization or availability. Keep that historical proof on the
        // result page, but exclude failed component ids from the aggregate
        // proof for the components that are still eligible to succeed.
        // Reusable maintenance components are already backed by the verified
        // inventory baseline. They still receive a live package-identity
        // readback during the device step, but they do not need a second
        // authorization / availability receipt in this batch.
        val reusableExpected = reusableInstalledComponentIds(snapshot) intersect expected
        val detailedExpected = expected - reusableExpected
        val installationForExpected = evidence.installation.filterKeys { it in detailedExpected }
        val authorizationForExpected = evidence.authorizationActions.filter { it.componentId in detailedExpected }
        val availabilityForExpected = evidence.availability.filterKeys { it in detailedExpected }
        val detailedEvidenceValid = trustedManifests(snapshot).isEmpty() || (
                installationForExpected.keys == detailedExpected &&
                availabilityForExpected.keys == detailedExpected &&
                validateAuthorizationEvidence(authorizationForExpected, snapshot, detailedExpected) &&
                validateAvailabilityEvidence(availabilityForExpected.values.toList(), snapshot, detailedExpected)
            )
        val artifactProofIds = evidence.artifactsVerified + (
            reusableInstalledComponentIds(snapshot) intersect evidence.installed
            )
        return expected.isNotEmpty() &&
            expected.all { it in artifactProofIds } &&
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
        installationStrategy = snapshot.installationStrategy,
        installationFlow = snapshot.installationFlow,
        installationBatch = snapshot.installationBatch,
        installationBatchReceipt = snapshot.installationBatchReceipt,
        artifactCatalogStage = snapshot.artifactCatalogStage,
        currentComponentName = snapshot.currentComponentName,
        progress = snapshot.progress,
        componentProgress = snapshot.componentProgress,
        failedComponentIds = snapshot.failedComponentIds,
        componentFailureRetryable = snapshot.componentFailureRetryable,
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
        val MAINTENANCE_INVENTORY_ACTIONS = setOf(
            MaintenanceActionId.MANAGE_APPS,
            MaintenanceActionId.REPAIR_CONFIGURATION,
            MaintenanceActionId.INSTALL_APPLICATIONS,
            MaintenanceActionId.INSTALL_FILE_MANAGER,
        )
        val SUPPORTED_CATALOG_SIGNATURE_ALGORITHMS = setOf("SHA256withECDSA", "Ed25519")
        val CATALOG_ACCEPTING_STATES = setOf(
            InstallationSessionState.IDLE,
            InstallationSessionState.DISCOVERING,
            InstallationSessionState.CONNECTED,
        )

        val DISCOVERY_ENTRY_STATES = setOf(
            InstallationSessionState.IDLE,
            InstallationSessionState.FAILED,
            InstallationSessionState.PAUSED,
            InstallationSessionState.MAINTENANCE,
        )

        val ACTIVE_INSTALL_STATES = setOf(
            InstallationSessionState.SELECTION_CONFIRMED,
            InstallationSessionState.PREPARING_ARTIFACTS,
            InstallationSessionState.ARTIFACTS_READY,
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
