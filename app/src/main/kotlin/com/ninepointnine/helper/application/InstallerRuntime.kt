package com.ninepointnine.helper.application

import com.ninepointnine.helper.application.device.DeviceDiscoverySessionAdapter
import com.ninepointnine.helper.application.device.DeviceConnectionSessionAdapter
import com.ninepointnine.helper.application.session.InstallationSessionEventDispatcher
import com.ninepointnine.helper.application.session.InstallationSessionEventPort
import com.ninepointnine.helper.data.catalog.CatalogLoadResult
import com.ninepointnine.helper.application.maintenance.MaintenanceController
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.application.artifact.ArtifactPreparationResult
import com.ninepointnine.helper.application.artifact.PreparedArtifact
import com.ninepointnine.helper.domain.device.ConnectedDevice
import com.ninepointnine.helper.domain.device.DeviceConnectionLease
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.session.FailureCategory
import com.ninepointnine.helper.domain.session.InstallationSession
import com.ninepointnine.helper.domain.session.InstallationSessionCommand
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.domain.session.InstallationSessionState
import com.ninepointnine.helper.domain.session.MaintenanceActionStatus
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Application owner that starts, retains and cancels adapters around the single domain session.
 * It contains no UI state and hands verified artifacts to the retained device connection.
 */
class InstallerRuntime(
    val session: InstallationSession,
    private val createDiscoveryAdapter: (InstallationSessionEventPort) -> DeviceDiscoverySessionAdapter,
    private val createConnectionAdapter: (InstallationSessionEventPort) -> DeviceConnectionSessionAdapter,
    private val loadCatalog: suspend (InstallationSessionEventPort) -> Unit,
    private val prepareArtifacts: suspend (List<ArtifactManifest>, InstallationSessionEventPort) -> Unit = { _, _ -> },
    private val prepareArtifactsWithResult: (suspend (List<ArtifactManifest>, InstallationSessionEventPort) -> ArtifactPreparationResult)? = null,
    private val executeDeviceInstallation: (suspend (DeviceConnectionLease, List<PreparedArtifact>, InstallationSessionEventPort) -> Unit)? = null,
    private val maintenanceController: MaintenanceController? = null,
    private val persistMaintenanceSnapshot: (suspend (InstallationSessionSnapshot) -> Unit)? = null,
    coroutineContext: CoroutineContext,
    private val prepareSelectedCatalog: (suspend (Set<String>, InstallationSessionEventPort) -> CatalogLoadResult)? = null,
) : AutoCloseable {
    private val runtimeJob = SupervisorJob(coroutineContext[Job])
    private val scope = CoroutineScope(coroutineContext + runtimeJob)
    private var discoveryAdapter: DeviceDiscoverySessionAdapter? = null
    private var discoveryJob: Job? = null
    private var connectionAdapter: DeviceConnectionSessionAdapter? = null
    private var connectionJob: Job? = null
    private var activeConnection: DeviceConnectionLease? = null
    private var lastConfirmedDevice: ConnectedDevice? = null
    private var connectionHealthJob: Job? = null
    private var catalogJob: Job? = null
    private var artifactJob: Job? = null
    private var maintenanceJob: Job? = null
    private var eventDispatcherSessionId: Long? = null
    private var eventDispatcher: InstallationSessionEventPort? = null
    private var automaticReconnectSessionId: Long? = null
    /** Session generation currently attempting the last confirmed endpoint. */
    private var knownReconnectAttemptSessionId: Long? = null
    private var foregroundGeneration = 0L
    private var maintenanceReconnectGeneration: Long? = null
    private var manualMaintenanceDisconnect = false
    private var closed = false

    init {
        scope.launch {
            session.snapshots.collect { snapshot ->
                reconcileDisconnectedInstallation(snapshot)
            }
        }
        persistMaintenanceSnapshot?.let { persist ->
            scope.launch {
                session.snapshots.collect { snapshot ->
                    if (
                        snapshot.state == InstallationSessionState.MAINTENANCE ||
                        snapshot.state == InstallationSessionState.SUCCEEDED
                    ) {
                        try {
                            persist(snapshot)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Persistence is best effort; the in-memory session remains authoritative.
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    fun dispatch(command: InstallationSessionCommand): InstallationSessionSnapshot {
        if (closed) return session.currentSnapshot()
        // Mark a manual maintenance disconnect before publishing the snapshot.
        // StateFlow collectors may reconcile synchronously on the same dispatcher;
        // setting this after dispatch lets the collector mistake the user action
        // for an unexpected drop and start a reconnect generation.
        if (command == InstallationSessionCommand.DisconnectDevice &&
            session.currentSnapshot().state == InstallationSessionState.MAINTENANCE
        ) {
            manualMaintenanceDisconnect = true
            maintenanceReconnectGeneration = foregroundGeneration
            knownReconnectAttemptSessionId = null
            maintenanceJob?.cancel()
        }
        val before = session.currentSnapshot()
        val effectiveCommand = if (
            command == InstallationSessionCommand.Reconnect &&
            canFastReconnect(before)
        ) {
            InstallationSessionCommand.ReconnectKnownDevice
        } else {
            command
        }
        val after = session.dispatch(effectiveCommand)

        when (effectiveCommand) {
            InstallationSessionCommand.StartDiscovery,
            InstallationSessionCommand.Reconnect,
            -> {
                knownReconnectAttemptSessionId = null
                if (before.state == InstallationSessionState.MAINTENANCE) {
                    manualMaintenanceDisconnect = false
                    maintenanceReconnectGeneration = foregroundGeneration
                }
                if (enteredNewGeneration(before, after, InstallationSessionState.DISCOVERING)) {
                    cancelAllWork(closeConnection = true)
                    launchDiscovery(after)
                }
            }

            InstallationSessionCommand.ReconnectKnownDevice -> {
                knownReconnectAttemptSessionId = after.sessionId
                if (before.state == InstallationSessionState.MAINTENANCE) {
                    manualMaintenanceDisconnect = false
                    maintenanceReconnectGeneration = foregroundGeneration
                }
                if (enteredNewGeneration(before, after, InstallationSessionState.CONNECTING)) {
                    cancelAllWork(closeConnection = true)
                    val knownDevice = lastConfirmedDevice
                    if (knownDevice == null || after.device?.id != knownDevice.identity.stableId) {
                        eventPortFor(after).emit(
                            InstallationSessionEvent.DeviceConnectionFailed(
                                deviceId = after.device?.id.orEmpty(),
                                reasonCode = "known_device_endpoint_missing",
                                retryable = true,
                            ),
                        )
                    } else {
                        launchConnection(after, knownDevice)
                    }
                }
            }

            InstallationSessionCommand.StopDiscovery -> {
                if (before.state == InstallationSessionState.DISCOVERING) cancelDiscovery()
            }

            InstallationSessionCommand.CancelConnection -> {
                if (after.sessionId != before.sessionId) cancelConnectionAttempt()
            }

            is InstallationSessionCommand.SelectDevice -> {
                if (enteredNewGeneration(before, after, InstallationSessionState.CONNECTING)) {
                    val selectedDevice = discoveryAdapter?.confirmedDevice(effectiveCommand.deviceId)
                    cancelDiscovery()
                    if (selectedDevice == null) {
                        eventPortFor(after).emit(
                            InstallationSessionEvent.DeviceConnectionFailed(
                                deviceId = effectiveCommand.deviceId,
                                reasonCode = "device_connection_target_missing",
                                retryable = false,
                            ),
                        )
                    } else {
                        launchConnection(after, selectedDevice)
                    }
                }
            }

            InstallationSessionCommand.StartInstallation,
            InstallationSessionCommand.ConfirmSelection,
            -> if (
                before.state == InstallationSessionState.CONNECTED &&
                after.state == InstallationSessionState.SELECTION_CONFIRMED
            ) {
                if (after.artifactManifests.isEmpty() && prepareSelectedCatalog != null) {
                    beginSelectedCatalogPreparation(after)
                } else {
                    beginArtifactPreparation(after)
                }
            }

            InstallationSessionCommand.CancelInstallation -> {
                if (after.sessionId != before.sessionId) cancelTransferWork()
            }

            InstallationSessionCommand.ContinueInstallation,
            InstallationSessionCommand.ResumeInstallation,
            InstallationSessionCommand.RetryInstallation,
            InstallationSessionCommand.ReconfigureInstallation,
            -> {
                if (after.sessionId != before.sessionId) cancelTransferWork()
                // Resume is a user intent. If the lease was lost, reconcile starts
                // one bounded discovery pass and restores the same checkpoint.
                reconcile(after)
            }

            InstallationSessionCommand.DisconnectDevice -> {
                knownReconnectAttemptSessionId = null
                maintenanceJob?.cancel()
                closeDeviceConnection()
            }

            is InstallationSessionCommand.ToggleOptionalComponent,
            InstallationSessionCommand.BeginPipeline,
            InstallationSessionCommand.EnterMaintenance,
            InstallationSessionCommand.RestartFromCheckpoint,
            -> Unit

            is InstallationSessionCommand.MaintenanceAction -> when {
                before.state == InstallationSessionState.MAINTENANCE &&
                    after.state == InstallationSessionState.SELECTION_CONFIRMED -> beginArtifactPreparation(after)

                before.state == InstallationSessionState.MAINTENANCE &&
                    after.maintenance.activeAction == effectiveCommand.actionId &&
                    before.maintenance.activeAction != effectiveCommand.actionId -> launchMaintenanceAction(effectiveCommand.actionId, after)
            }

            is InstallationSessionCommand.MaintenanceApplicationAction -> {
                if (
                    before.state == InstallationSessionState.MAINTENANCE &&
                    after.maintenance.applicationAction?.componentId == effectiveCommand.componentId &&
                    after.maintenance.applicationAction?.actionId == effectiveCommand.actionId &&
                    after.maintenance.applicationAction?.status == MaintenanceActionStatus.RUNNING
                ) {
                    launchMaintenanceApplicationAction(effectiveCommand, after)
                }
            }

            InstallationSessionCommand.StartMaintenanceInstallation -> {
                if (
                    before.state == InstallationSessionState.MAINTENANCE &&
                    after.state == InstallationSessionState.SELECTION_CONFIRMED
                ) {
                    if (after.artifactManifests.isEmpty() && prepareSelectedCatalog != null) {
                        beginSelectedCatalogPreparation(after)
                    } else {
                        beginArtifactPreparation(after)
                    }
                }
            }

            is InstallationSessionCommand.ToggleMaintenanceInstallationComponent -> Unit

            is InstallationSessionCommand.AdapterEvent -> {
                if (effectiveCommand.event is InstallationSessionEvent.DeviceDisconnected) {
                    maintenanceJob?.cancel()
                    maintenanceJob = null
                    closeDeviceConnection()
                }
            }
        }
        return session.currentSnapshot()
    }

    /** Starts a bounded discovery pass when the app returns to the foreground on the connection page. */
    @Synchronized
    fun onForeground(): InstallationSessionSnapshot {
        foregroundGeneration += 1L
        val current = session.currentSnapshot()
        return when {
            current.state == InstallationSessionState.IDLE -> dispatch(InstallationSessionCommand.StartDiscovery)
            current.state == InstallationSessionState.PAUSED &&
                current.checkpoint != null &&
                current.failure?.reasonCode != "cancelled" -> {
                dispatch(InstallationSessionCommand.ContinueInstallation)
            }
            current.state in INSTALL_RECONCILE_STATES && activeConnection == null -> {
                reconcile(current)
                session.currentSnapshot()
            }
            current.state == InstallationSessionState.MAINTENANCE &&
                current.device?.connectionStatus == DeviceConnectionStatus.DISCONNECTED &&
                !manualMaintenanceDisconnect &&
                maintenanceReconnectGeneration != foregroundGeneration -> {
                maintenanceReconnectGeneration = foregroundGeneration
                dispatch(InstallationSessionCommand.Reconnect)
            }
            current.state in CONNECTION_HELD_STATES &&
                current.device?.connectionStatus == DeviceConnectionStatus.CONFIRMED &&
                activeConnection != null -> {
                scheduleConnectionHealthCheck(current)
                current
            }

            else -> current
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        cancelAllWork(closeConnection = true)
        scope.cancel()
        session.close()
    }

    @Synchronized
    private fun reconcile(snapshot: InstallationSessionSnapshot) {
        when (snapshot.state) {
            InstallationSessionState.DISCOVERING -> launchDiscovery(snapshot)
            InstallationSessionState.CONNECTING -> {
                val device = snapshot.device
                if (device != null && device.connectionStatus == DeviceConnectionStatus.CONNECTING) {
                    val candidate = discoveryAdapter?.confirmedDevice(device.id)
                    if (candidate != null) launchConnection(snapshot, candidate)
                }
            }
            InstallationSessionState.CONNECTED -> {
                if (activeConnection == null && snapshot.checkpoint != null) {
                    beginAutomaticInstallReconnect(snapshot)
                } else if (snapshot.artifactManifests.isEmpty()) {
                    launchCatalog(snapshot)
                }
            }

            InstallationSessionState.SELECTION_CONFIRMED -> if (
                snapshot.artifactManifests.isEmpty() && prepareSelectedCatalog != null
            ) {
                beginSelectedCatalogPreparation(snapshot)
            } else {
                beginArtifactPreparation(snapshot)
            }
            InstallationSessionState.RESOLVING_SOURCE,
            InstallationSessionState.DOWNLOADING_ARCHIVE,
            InstallationSessionState.VERIFYING_ARCHIVE,
            InstallationSessionState.EXTRACTING_APK,
            InstallationSessionState.VERIFYING_ARTIFACTS,
            -> launchArtifactPreparation(snapshot)

            InstallationSessionState.INSTALLING,
            InstallationSessionState.AUTHORIZING,
            InstallationSessionState.VERIFYING_DEVICE,
            -> restartInstallationFromCheckpoint(snapshot)
            else -> Unit
        }
    }

    private fun restartInstallationFromCheckpoint(snapshot: InstallationSessionSnapshot) {
        if (activeConnection == null) {
            beginAutomaticInstallReconnect(snapshot)
            return
        }
        // A write may have completed immediately before the lease disappeared.
        // Re-running the verified pipeline is idempotent and restores every
        // selected application's structured proofs before success is reported.
        val restored = session.dispatch(InstallationSessionCommand.RestartFromCheckpoint)
        if (restored.state == InstallationSessionState.SELECTION_CONFIRMED) {
            if (restored.artifactManifests.isEmpty() && prepareSelectedCatalog != null) {
                beginSelectedCatalogPreparation(restored)
            } else {
                beginArtifactPreparation(restored)
            }
        }
    }

    private fun beginAutomaticInstallReconnect(snapshot: InstallationSessionSnapshot) {
        if (snapshot.checkpoint == null) return
        dispatch(InstallationSessionCommand.Reconnect)
    }

    private fun beginArtifactPreparation(snapshot: InstallationSessionSnapshot) {
        val pipeline = session.dispatch(InstallationSessionCommand.BeginPipeline)
        if (pipeline.state == InstallationSessionState.RESOLVING_SOURCE) {
            launchArtifactPreparation(pipeline)
        }
    }

    private fun beginSelectedCatalogPreparation(snapshot: InstallationSessionSnapshot) {
        artifactJob?.cancel()
        val port = eventPortFor(snapshot)
        val loader = prepareSelectedCatalog ?: return beginArtifactPreparation(snapshot)
        val selectedIds = snapshot.components
            .filter {
                it.id == com.ninepointnine.helper.domain.device.AuthorizationPlanFactory.DESKTOP_COMPONENT_ID ||
                    it.id in snapshot.selectedOptionalComponentIds
            }
            .map { it.id }
            .toSet()
        artifactJob = scope.launch {
            try {
                val result = loader(selectedIds, port)
                if (result is CatalogLoadResult.Success) {
                    val current = session.currentSnapshot()
                    if (current.state == InstallationSessionState.SELECTION_CONFIRMED) {
                        beginArtifactPreparation(current)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                port.emit(
                    InstallationSessionEvent.FatalError(
                        category = FailureCategory.DOWNLOAD,
                        reasonCode = "selected_catalog_preparation_failed",
                    ),
                )
            }
        }
    }

    private fun launchDiscovery(snapshot: InstallationSessionSnapshot) {
        cancelDiscovery()
        val port = eventPortFor(snapshot)
        val adapter = try {
            createDiscoveryAdapter(port)
        } catch (_: Exception) {
            port.emit(InstallationSessionEvent.DiscoveryFinished(0, 0, "discovery_adapter_unavailable"))
            return
        }
        discoveryAdapter = adapter
        discoveryJob = scope.launch {
            try {
                adapter.discover()
                autoSelectReconnectTarget(snapshot.sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                port.emit(InstallationSessionEvent.DiscoveryFinished(0, 0, "discovery_failed"))
            }
        }
    }

    /**
     * Adapter ports update the session directly, so this observer owns the
     * one-shot recovery trigger for a connection loss. It never polls ADB and
     * deliberately ignores non-connection failures to avoid retry loops.
     */
    @Synchronized
    private fun reconcileDisconnectedInstallation(snapshot: InstallationSessionSnapshot) {
        if (
            !closed &&
            snapshot.state == InstallationSessionState.MAINTENANCE &&
            snapshot.device?.connectionStatus == DeviceConnectionStatus.DISCONNECTED &&
            snapshot.failure?.category == FailureCategory.CONNECTION &&
            knownReconnectAttemptSessionId != null &&
            knownReconnectAttemptSessionId != snapshot.sessionId
        ) {
            // The remembered endpoint failed. Fall back once to the bounded LAN
            // discovery path; the generation guard below prevents a retry loop.
            knownReconnectAttemptSessionId = null
            dispatch(InstallationSessionCommand.StartDiscovery)
            return
        }
        if (
            !closed &&
            snapshot.state == InstallationSessionState.MAINTENANCE &&
            snapshot.device?.connectionStatus == DeviceConnectionStatus.DISCONNECTED &&
            !manualMaintenanceDisconnect &&
            maintenanceReconnectGeneration != foregroundGeneration
        ) {
            maintenanceReconnectGeneration = foregroundGeneration
            dispatch(InstallationSessionCommand.Reconnect)
            return
        }
        if (
            closed ||
            snapshot.state != InstallationSessionState.PAUSED ||
            snapshot.checkpoint == null ||
            snapshot.failure?.category != FailureCategory.CONNECTION ||
            snapshot.failure?.reasonCode !in AUTO_RECONNECT_FAILURES ||
            automaticReconnectSessionId == snapshot.sessionId
        ) {
            return
        }
        automaticReconnectSessionId = snapshot.sessionId
        dispatch(InstallationSessionCommand.Reconnect)
    }

    /**
     * Discovery adapters publish directly to the session event port. Once the
     * bounded scan returns, the runtime performs the one automatic selection
     * needed by a reconnect; ordinary discovery still waits for user choice.
     */
    @Synchronized
    private fun autoSelectReconnectTarget(expectedSessionId: Long) {
        val current = session.currentSnapshot()
        if (
            current.sessionId != expectedSessionId ||
            current.state != InstallationSessionState.DISCOVERING ||
            (!current.installationReconnectPending && !current.maintenanceReconnectPending)
        ) {
            return
        }
        val targetId = current.device?.id ?: return
        if (current.discoveredDevices.any {
                it.id == targetId && it.connectionStatus == DeviceConnectionStatus.CONFIRMED
            }
        ) {
            dispatch(InstallationSessionCommand.SelectDevice(targetId))
        }
    }

    private fun launchConnection(snapshot: InstallationSessionSnapshot, device: ConnectedDevice) {
        connectionJob?.cancel()
        val port = eventPortFor(snapshot)
        val adapter = try {
            createConnectionAdapter(port)
        } catch (_: Exception) {
            port.emit(
                InstallationSessionEvent.DeviceConnectionFailed(
                    deviceId = device.identity.stableId,
                    reasonCode = "connection_adapter_unavailable",
                    retryable = false,
                ),
            )
            return
        }
        connectionAdapter = adapter
        connectionJob = scope.launch {
            try {
                val connection = adapter.connect(device) ?: return@launch
                val confirmedSnapshot = session.currentSnapshot()
                if (
                    confirmedSnapshot.sessionId != snapshot.sessionId ||
                    (
                    confirmedSnapshot.state !in CONNECTION_HELD_STATES
                        ) ||
                    confirmedSnapshot.device?.id != device.identity.stableId ||
                    confirmedSnapshot.device?.connectionStatus != DeviceConnectionStatus.CONFIRMED ||
                    (manualMaintenanceDisconnect && confirmedSnapshot.state == InstallationSessionState.MAINTENANCE)
                ) {
                    connection.close()
                    return@launch
                }
                activeConnection?.close()
                activeConnection = connection
                lastConfirmedDevice = connection.device
                knownReconnectAttemptSessionId = null
                manualMaintenanceDisconnect = false
                maintenanceReconnectGeneration = null
                if (confirmedSnapshot.state == InstallationSessionState.CONNECTED) {
                    launchCatalog(confirmedSnapshot)
                } else {
                    scheduleConnectionHealthCheck(confirmedSnapshot)
                    reconcile(confirmedSnapshot)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                port.emit(
                    InstallationSessionEvent.DeviceConnectionFailed(
                        deviceId = device.identity.stableId,
                        reasonCode = "device_connection_failed",
                    ),
                )
            }
        }
    }

    private fun launchCatalog(snapshot: InstallationSessionSnapshot) {
        catalogJob?.cancel()
        val port = eventPortFor(snapshot)
        catalogJob = scope.launch {
            try {
                loadCatalog(port)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                port.emit(InstallationSessionEvent.CatalogFailed("catalog_load_failed"))
            }
        }
    }

    private fun launchArtifactPreparation(snapshot: InstallationSessionSnapshot) {
        artifactJob?.cancel()
        val port = eventPortFor(snapshot)
        val selectedIds = snapshot.components
            .filter { it.id == com.ninepointnine.helper.domain.device.AuthorizationPlanFactory.DESKTOP_COMPONENT_ID ||
                it.id in snapshot.selectedOptionalComponentIds }
            .map { it.id }
            .toSet()
        val manifests = snapshot.artifactManifests.filter { it.componentId in selectedIds }
        val unresolvedIds = selectedIds - manifests.map { it.componentId }.toSet() - snapshot.failedComponentIds
        if (unresolvedIds.isNotEmpty()) {
            port.emit(
                InstallationSessionEvent.FatalError(
                    category = FailureCategory.VERIFICATION,
                    reasonCode = "artifact_manifest_selection_mismatch",
                ),
            )
            return
        }
        artifactJob = scope.launch {
            try {
                val result = prepareArtifactsWithResult?.invoke(manifests, port)
                if (result == null) {
                    prepareArtifacts(manifests, port)
                } else if (result is ArtifactPreparationResult.Prepared) {
                    val connection = activeConnection
                    if (session.currentSnapshot().state != InstallationSessionState.VERIFYING_ARTIFACTS) {
                        return@launch
                    }
                    if (connection == null) {
                        port.emit(
                            InstallationSessionEvent.FatalError(
                                category = FailureCategory.INSTALLATION,
                                reasonCode = "device_action_gateway_unavailable",
                            ),
                        )
                        return@launch
                    }
                    val execute = executeDeviceInstallation
                    if (execute == null) {
                        port.emit(
                            InstallationSessionEvent.FatalError(
                                category = FailureCategory.INSTALLATION,
                                reasonCode = "device_action_gateway_unavailable",
                            ),
                        )
                    } else {
                        execute(connection, result.artifacts, port)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                port.emit(
                    InstallationSessionEvent.FatalError(
                        category = FailureCategory.VERIFICATION,
                        reasonCode = "artifact_preparation_failed",
                    ),
                )
            }
        }
    }

    private fun launchMaintenanceAction(
        actionId: com.ninepointnine.helper.domain.session.MaintenanceActionId,
        snapshot: InstallationSessionSnapshot,
    ) {
        maintenanceJob?.cancel()
        val port = eventPortFor(snapshot)
        val controller = maintenanceController
        if (controller == null) {
            port.emit(
                InstallationSessionEvent.MaintenanceActionFailed(
                    actionId = actionId,
                    reasonCode = "maintenance_controller_unavailable",
                    retryable = false,
                ),
            )
            return
        }
        val connection = activeConnection
        maintenanceJob = scope.launch {
            try {
                controller.execute(actionId, snapshot, connection, port)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                port.emit(
                    InstallationSessionEvent.MaintenanceActionFailed(
                        actionId = actionId,
                        reasonCode = "maintenance_action_failed",
                        retryable = true,
                    ),
                )
            }
        }
    }

    private fun launchMaintenanceApplicationAction(
        command: InstallationSessionCommand.MaintenanceApplicationAction,
        snapshot: InstallationSessionSnapshot,
    ) {
        maintenanceJob?.cancel()
        val port = eventPortFor(snapshot)
        val controller = maintenanceController
        if (controller == null) {
            port.emit(
                InstallationSessionEvent.MaintenanceApplicationActionFailed(
                    componentId = command.componentId,
                    actionId = command.actionId,
                    reasonCode = "maintenance_controller_unavailable",
                    retryable = false,
                ),
            )
            return
        }
        val connection = activeConnection
        maintenanceJob = scope.launch {
            try {
                controller.executeApplicationAction(
                    componentId = command.componentId,
                    actionId = command.actionId,
                    snapshot = snapshot,
                    connection = connection,
                    eventPort = port,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                port.emit(
                    InstallationSessionEvent.MaintenanceApplicationActionFailed(
                        componentId = command.componentId,
                        actionId = command.actionId,
                        reasonCode = "maintenance_application_action_failed",
                        retryable = true,
                    ),
                )
            }
        }
    }

    private fun eventPortFor(snapshot: InstallationSessionSnapshot): InstallationSessionEventPort {
        if (eventDispatcherSessionId != snapshot.sessionId || eventDispatcher == null) {
            eventDispatcherSessionId = snapshot.sessionId
            eventDispatcher = InstallationSessionEventDispatcher(session, snapshot.sessionId)
        }
        return checkNotNull(eventDispatcher)
    }

    private fun cancelDiscovery() {
        discoveryAdapter?.cancel()
        discoveryJob?.cancel()
        discoveryAdapter = null
        discoveryJob = null
    }

    private fun cancelConnectionAttempt() {
        connectionJob?.cancel()
        connectionJob = null
        connectionAdapter = null
    }

    private fun cancelTransferWork() {
        cancelDiscovery()
        cancelConnectionAttempt()
        catalogJob?.cancel()
        artifactJob?.cancel()
        maintenanceJob?.cancel()
        catalogJob = null
        artifactJob = null
        maintenanceJob = null
    }

    private fun cancelAllWork(closeConnection: Boolean) {
        cancelTransferWork()
        if (closeConnection) closeDeviceConnection()
    }

    private fun closeDeviceConnection() {
        connectionHealthJob?.cancel()
        connectionHealthJob = null
        cancelConnectionAttempt()
        activeConnection?.close()
        activeConnection = null
    }

    private fun scheduleConnectionHealthCheck(snapshot: InstallationSessionSnapshot) {
        val connection = activeConnection ?: return
        connectionHealthJob?.cancel()
        connectionHealthJob = scope.launch {
            val check = try {
                connection.check()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                com.ninepointnine.helper.domain.device.DeviceConnectionCheck(false, "adb_connection_check_failed")
            }
            if (!check.healthy && activeConnection === connection) {
                closeDeviceConnection()
                eventPortFor(snapshot).emit(
                    InstallationSessionEvent.DeviceDisconnected(
                        deviceId = snapshot.device?.id,
                        reasonCode = check.reasonCode ?: "adb_connection_lost",
                    ),
                )
            }
        }
    }

    private fun enteredNewGeneration(
        before: InstallationSessionSnapshot,
        after: InstallationSessionSnapshot,
        expectedState: InstallationSessionState,
    ): Boolean = after.state == expectedState && after.sessionId != before.sessionId

    private fun canFastReconnect(snapshot: InstallationSessionSnapshot): Boolean {
        val known = lastConfirmedDevice ?: return false
        if (snapshot.device?.id != known.identity.stableId) return false
        return snapshot.state == InstallationSessionState.MAINTENANCE ||
            snapshot.state in INSTALL_RECONCILE_STATES && snapshot.checkpoint != null ||
            snapshot.state in setOf(InstallationSessionState.PAUSED, InstallationSessionState.FAILED) &&
            snapshot.checkpoint != null
    }

    private companion object {
        val CONNECTION_HELD_STATES = setOf(
            InstallationSessionState.CONNECTED,
            InstallationSessionState.SELECTION_CONFIRMED,
            InstallationSessionState.RESOLVING_SOURCE,
            InstallationSessionState.DOWNLOADING_ARCHIVE,
            InstallationSessionState.VERIFYING_ARCHIVE,
            InstallationSessionState.EXTRACTING_APK,
            InstallationSessionState.VERIFYING_ARTIFACTS,
            InstallationSessionState.INSTALLING,
            InstallationSessionState.AUTHORIZING,
            InstallationSessionState.VERIFYING_DEVICE,
            InstallationSessionState.SUCCEEDED,
            InstallationSessionState.COMPLETED_WITH_ERRORS,
            InstallationSessionState.PAUSED,
            InstallationSessionState.MAINTENANCE,
        )
        val INSTALL_RECONCILE_STATES = setOf(
            // CONNECTED also carries a checkpoint; losing the lease here must
            // restore the selection/catalog boundary instead of falling back
            // to the initial connection page.
            InstallationSessionState.CONNECTED,
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
        val AUTO_RECONNECT_FAILURES = setOf(
            "device_disconnected",
            "adb_connection_lost",
            "adb_connection_check_failed",
        )
    }
}
