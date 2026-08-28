package com.ninepointnine.helper.application

import android.util.Log
import com.ninepointnine.helper.application.device.DeviceDiscoverySessionAdapter
import com.ninepointnine.helper.application.device.DeviceConnectionSessionAdapter
import com.ninepointnine.helper.application.session.InstallationSessionEventDispatcher
import com.ninepointnine.helper.application.session.InstallationSessionEventPort
import com.ninepointnine.helper.data.catalog.CatalogLoadResult
import com.ninepointnine.helper.application.maintenance.MaintenanceController
import com.ninepointnine.helper.application.maintenance.MaintenanceBaselineProjector
import com.ninepointnine.helper.data.artifact.ApkIconRepository
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactFailure
import com.ninepointnine.helper.domain.artifact.ArtifactFailurePhase
import com.ninepointnine.helper.application.artifact.ArtifactPreparationResult
import com.ninepointnine.helper.application.artifact.PreparedArtifact
import com.ninepointnine.helper.domain.device.ConnectedDevice
import com.ninepointnine.helper.domain.device.DeviceActionFailure
import com.ninepointnine.helper.domain.device.DeviceConnectionLease
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.session.FailureCategory
import com.ninepointnine.helper.domain.session.InstallationSession
import com.ninepointnine.helper.domain.session.InstallationSessionCommand
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.domain.session.InstallationSessionState
import com.ninepointnine.helper.domain.session.InstallationBatchPlan
import com.ninepointnine.helper.domain.session.ArtifactCatalogStage
import com.ninepointnine.helper.domain.session.MaintenanceActionStatus
import com.ninepointnine.helper.domain.session.failureCategoryForReasonCode
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private sealed interface MaintenancePersistenceOperation {
    data class Save(val snapshot: InstallationSessionSnapshot) : MaintenancePersistenceOperation
    data object Clear : MaintenancePersistenceOperation
}

/**
 * Sole device-batch execution boundary. The four-argument SAM keeps existing
 * test adapters source-compatible; production overrides the extended method
 * so preparation failures travel with the same batch request.
 */
fun interface InstallationBatchExecutor {
    suspend fun execute(
        connection: DeviceConnectionLease,
        artifacts: List<PreparedArtifact>,
        batchPlan: InstallationBatchPlan,
        eventPort: InstallationSessionEventPort,
    )

    suspend fun executeWithPreparationFailures(
        connection: DeviceConnectionLease,
        artifacts: List<PreparedArtifact>,
        batchPlan: InstallationBatchPlan,
        preparationFailures: Map<String, DeviceActionFailure>,
        eventPort: InstallationSessionEventPort,
    ) {
        check(preparationFailures.isEmpty()) {
            "batch_executor_does_not_accept_preparation_failures"
        }
        execute(connection, artifacts, batchPlan, eventPort)
    }
}

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
    private val maintenanceController: MaintenanceController? = null,
    private val persistMaintenanceSnapshot: (suspend (InstallationSessionSnapshot) -> Unit)? = null,
    private val clearMaintenanceSnapshot: (suspend () -> Unit)? = null,
    coroutineContext: CoroutineContext,
    private val prepareSelectedCatalog: (suspend (Set<String>, InstallationSessionEventPort) -> CatalogLoadResult)? = null,
    /** Strategy-aware selected-catalog hook; skipped ids never enter remote preparation. */
    private val prepareSelectedCatalogWithSkipped: (suspend (Set<String>, Set<String>, InstallationSessionEventPort) -> CatalogLoadResult)? = null,
    /** Production selected-catalog hook; the immutable domain batch is the complete request. */
    private val prepareSelectedCatalogWithBatch: (suspend (InstallationBatchPlan, InstallationSessionEventPort) -> CatalogLoadResult)? = null,
    /** Optional UI adapter; production injects the APK-backed icon reader. */
    val apkIconRepository: ApkIconRepository? = null,
    /** Sole device-installation hook; the immutable domain batch is the complete request. */
    private val executeDeviceInstallationWithBatch: InstallationBatchExecutor? = null,
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
    private var lastMaintenancePersistenceOperation: MaintenancePersistenceOperation? = null
    private var closed = false

    init {
        scope.launch {
            session.snapshots.collect { snapshot ->
                reconcileDisconnectedInstallation(snapshot)
            }
        }
        val persist = persistMaintenanceSnapshot
        val clear = clearMaintenanceSnapshot
        if (persist != null || clear != null) {
            scope.launch {
                session.snapshots.collect { snapshot ->
                    val baseline = MaintenanceBaselineProjector.project(snapshot)
                    val operation = when {
                        baseline != null && persist != null -> MaintenancePersistenceOperation.Save(baseline)
                        MaintenanceBaselineProjector.shouldClear(snapshot) && clear != null ->
                            MaintenancePersistenceOperation.Clear
                        else -> return@collect
                    }
                    if (operation == lastMaintenancePersistenceOperation) return@collect
                    // Phone-local durability is deliberately outside the session.
                    // It cannot change a verified car result or trigger a UI state.
                    lastMaintenancePersistenceOperation = operation
                    try {
                        when (operation) {
                            is MaintenancePersistenceOperation.Save -> checkNotNull(persist).invoke(operation.snapshot)
                            MaintenancePersistenceOperation.Clear -> checkNotNull(clear).invoke()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (exception: Exception) {
                        Log.w("03helper-runtime", "maintenance_baseline_persistence_failed", exception)
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
                if (needsSelectedCatalogPreparation(after) && hasSelectedCatalogPreparer()) {
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

            InstallationSessionCommand.ReturnToSelection -> {
                if (after.sessionId != before.sessionId) cancelTransferWork()
                // A catalog failure may leave no component rows. Re-enter the
                // connected selection route and let the normal reconcile path
                // request a fresh catalog instead of leaving the page inert.
                if (after.state == InstallationSessionState.CONNECTED && after.components.isEmpty()) {
                    reconcile(after)
                }
            }

            InstallationSessionCommand.ReturnToMaintenanceInstallationSelection -> {
                if (after.sessionId != before.sessionId) cancelTransferWork()
            }

            InstallationSessionCommand.DisconnectDevice -> {
                knownReconnectAttemptSessionId = null
                maintenanceJob?.cancel()
                closeDeviceConnection()
            }

            InstallationSessionCommand.LeaveMaintenanceAction -> {
                // The command only clears the maintenance route state; keep
                // the confirmed device lease alive for the home page.
                maintenanceJob?.cancel()
                maintenanceJob = null
            }

            is InstallationSessionCommand.ToggleOptionalComponent,
            InstallationSessionCommand.BeginPipeline,
            InstallationSessionCommand.EnterMaintenance,
            InstallationSessionCommand.RestartFromCheckpoint,
            -> Unit

            is InstallationSessionCommand.MaintenanceAction -> when {
                before.state == InstallationSessionState.MAINTENANCE &&
                    after.state == InstallationSessionState.SELECTION_CONFIRMED -> {
                    if (after.sessionId != before.sessionId) cancelTransferWork()
                    if (needsSelectedCatalogPreparation(after) && hasSelectedCatalogPreparer()) {
                        beginSelectedCatalogPreparation(after)
                    } else {
                        beginArtifactPreparation(after)
                    }
                }

                before.state == InstallationSessionState.MAINTENANCE &&
                    after.maintenance.activeAction == effectiveCommand.actionId &&
                    before.maintenance.activeAction != effectiveCommand.actionId -> {
                    if (after.sessionId != before.sessionId) {
                        // Every maintenance action starts a fresh generation.
                        // Stop the previous adapter before launching the new
                        // one; its already-emitted callbacks are rejected by
                        // sessionId.
                        cancelTransferWork()
                    }
                    launchMaintenanceAction(effectiveCommand.actionId, after)
                }
            }

            is InstallationSessionCommand.MaintenanceApplicationAction -> {
                if (
                    before.state == InstallationSessionState.MAINTENANCE &&
                    after.maintenance.applicationAction?.componentId == effectiveCommand.componentId &&
                    after.maintenance.applicationAction?.actionId == effectiveCommand.actionId &&
                    after.maintenance.applicationAction?.status == MaintenanceActionStatus.RUNNING
                ) {
                    if (after.sessionId != before.sessionId) cancelTransferWork()
                    launchMaintenanceApplicationAction(effectiveCommand, after)
                }
            }

            InstallationSessionCommand.StartMaintenanceInstallation -> {
                if (
                    before.state == InstallationSessionState.MAINTENANCE &&
                    after.state == InstallationSessionState.SELECTION_CONFIRMED
                ) {
                    if (after.sessionId != before.sessionId) cancelTransferWork()
                    if (needsSelectedCatalogPreparation(after) && hasSelectedCatalogPreparer()) {
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
                } else if (
                    snapshot.artifactCatalogStage == ArtifactCatalogStage.NOT_LOADED ||
                    snapshot.artifactCatalogStage == ArtifactCatalogStage.CONTROL_PLANE_READY &&
                    snapshot.failure != null
                ) {
                    launchCatalog(snapshot)
                }
            }

            InstallationSessionState.SELECTION_CONFIRMED -> if (
                needsSelectedCatalogPreparation(snapshot) && hasSelectedCatalogPreparer()
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
            if (needsSelectedCatalogPreparation(restored) && hasSelectedCatalogPreparer()) {
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
        val loader = prepareSelectedCatalog
        val batchLoader = prepareSelectedCatalogWithBatch
        if (loader == null && prepareSelectedCatalogWithSkipped == null && batchLoader == null) {
            return beginArtifactPreparation(snapshot)
        }
        val batch = snapshot.installationBatch
        // The batch identifies the user's installation attempt; sessionId
        // identifies the current adapter-event generation. Reconnect and
        // checkpoint recovery advance only the latter.
        if (batchLoader != null && batch == null) {
            port.emit(
                InstallationSessionEvent.FatalError(
                    category = FailureCategory.VERIFICATION,
                    reasonCode = "selected_catalog_batch_mismatch",
                ),
            )
            return
        }
        if (batchLoader != null && batch?.catalogIdentity == null) {
            port.emit(
                InstallationSessionEvent.FatalError(
                    category = FailureCategory.VERIFICATION,
                    reasonCode = "selected_catalog_identity_mismatch",
                ),
            )
            return
        }
        val selectedIds = snapshot.installationBatch?.selectedComponentIds
            ?: snapshot.components
                .filter {
                    it.id == com.ninepointnine.helper.domain.device.AuthorizationPlanFactory.DESKTOP_COMPONENT_ID ||
                        it.id in snapshot.selectedOptionalComponentIds
                }
                .map { it.id }
                .toSet()
        if (selectedIds.isEmpty()) {
            port.emit(
                InstallationSessionEvent.FatalError(
                    category = FailureCategory.VERIFICATION,
                    reasonCode = "selected_catalog_component_set_empty",
                ),
            )
            return
        }
        // Only selected components participate in this preparation request.
        // Older or unlisted installed components remain maintenance state, but
        // must not make a selected-catalog adapter reject the request.
        val skippedIds = (snapshot.installationBatch?.reusableComponentIds
            ?: session.reusableInstalledComponentIds(snapshot)) intersect selectedIds
        artifactJob = scope.launch {
            try {
                val result = when {
                    batchLoader != null -> batchLoader.invoke(checkNotNull(batch), port)

                    prepareSelectedCatalogWithSkipped != null ->
                        prepareSelectedCatalogWithSkipped.invoke(selectedIds, skippedIds, port)

                    skippedIds.isEmpty() && loader != null -> loader(selectedIds, port)

                    else -> {
                        port.emit(
                            InstallationSessionEvent.FatalError(
                                category = FailureCategory.VERIFICATION,
                                reasonCode = "selected_catalog_preparer_strategy_unavailable",
                            ),
                        )
                        return@launch
                    }
                }
                when (result) {
                    is CatalogLoadResult.Success -> {
                        val current = session.currentSnapshot()
                        if (current.state == InstallationSessionState.SELECTION_CONFIRMED) {
                            beginArtifactPreparation(current)
                        }
                    }

                    is CatalogLoadResult.Failure -> emitCatalogFailureIfWaiting(port, result)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emitPreparationFailure(
                    port = port,
                    snapshot = session.currentSnapshot(),
                    fallbackCategory = FailureCategory.DOWNLOAD,
                    fallbackReason = "selected_catalog_preparation_failed",
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
                port.emit(InstallationSessionEvent.CatalogFailed("catalog_load_failed", retryable = true))
            }
        }
    }

    /**
     * Some test or future catalog adapters return a typed failure without
     * emitting the session event themselves. Close that boundary exactly once
     * while the selected-catalog state is still waiting.
     */
    private fun emitCatalogFailureIfWaiting(
        port: InstallationSessionEventPort,
        result: CatalogLoadResult.Failure,
    ) {
        if (session.currentSnapshot().state == InstallationSessionState.SELECTION_CONFIRMED) {
            port.emit(
                InstallationSessionEvent.CatalogFailed(
                    reasonCode = result.reasonCode,
                    retryable = result.retryable,
                ),
            )
        }
    }

    private fun launchArtifactPreparation(snapshot: InstallationSessionSnapshot) {
        artifactJob?.cancel()
        val port = eventPortFor(snapshot)
        val batchPlan = snapshot.installationBatch
        val selectedIds = snapshot.installationBatch?.selectedComponentIds
            ?: snapshot.components
                .filter { it.id == com.ninepointnine.helper.domain.device.AuthorizationPlanFactory.DESKTOP_COMPONENT_ID ||
                    it.id in snapshot.selectedOptionalComponentIds }
                .map { it.id }
                .toSet()
        if (snapshot.artifactCatalogStage != ArtifactCatalogStage.PREPARED) {
            port.emit(
                InstallationSessionEvent.FatalError(
                    category = FailureCategory.VERIFICATION,
                    reasonCode = "artifact_catalog_not_prepared",
                ),
            )
            return
        }
        val reusableIds = (snapshot.installationBatch?.reusableComponentIds
            ?: session.reusableInstalledComponentIds(snapshot)) intersect selectedIds
        val reusableManifests = reusableIds.mapNotNull { componentId ->
            session.installedIdentityManifest(snapshot, componentId)
        }
        val missingReusableIds =
            ((snapshot.installationBatch?.reusableComponentIds
                ?: session.reusableInstalledComponentIds(snapshot)) intersect selectedIds) -
                reusableManifests.map { it.componentId }.toSet()
        if (missingReusableIds.isNotEmpty()) {
            port.emit(
                InstallationSessionEvent.FatalError(
                    category = FailureCategory.VERIFICATION,
                    reasonCode = "installed_component_manifest_unavailable",
                ),
            )
            return
        }
        val preparationIds = selectedIds - reusableIds
        val manifests = snapshot.artifactManifests.filter { it.componentId in preparationIds }
        val unresolvedIds = preparationIds - manifests.map { it.componentId }.toSet() - snapshot.failedComponentIds
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
                } else {
                    // A cancelled adapter may still complete a non-cooperative
                    // I/O call. Never let its result start device work for a
                    // newer batch or overwrite a terminal state.
                    val currentGeneration = session.currentSnapshot()
                    if (currentGeneration.sessionId != snapshot.sessionId) {
                        return@launch
                    }
                    when (result) {
                        is ArtifactPreparationResult.Failed -> {
                            val failure = result.failure
                            failure.componentId?.let { componentId ->
                                port.emit(
                                    InstallationSessionEvent.ArtifactUnavailable(
                                        componentId = componentId,
                                        reasonCode = failure.reasonCode,
                                        sourceKind = failure.sourceKind,
                                        retryable = failure.retryable,
                                    ),
                                )
                            }
                            emitPreparationFailure(
                                port = port,
                                snapshot = session.currentSnapshot(),
                                fallbackCategory = failureCategory(failure),
                                fallbackReason = failure.reasonCode,
                                fallbackComponentName = failure.componentId,
                            )
                        }

                        is ArtifactPreparationResult.Prepared -> {
                            val invalidFailure = result.failures.firstOrNull { failure ->
                                failure.componentId !in preparationIds
                            }
                            val duplicateFailureId = result.failures
                                .mapNotNull { it.componentId }
                                .groupingBy { it }
                                .eachCount()
                                .entries
                                .firstOrNull { it.value > 1 }
                                ?.key
                            val preparedIdsInOrder = result.artifacts.map { it.manifest.componentId }
                            val duplicatePreparedId = preparedIdsInOrder
                                .groupingBy { it }
                                .eachCount()
                                .entries
                                .firstOrNull { it.value > 1 }
                                ?.key
                            val unexpectedPreparedId = preparedIdsInOrder.firstOrNull { it !in preparationIds }
                            if (
                                invalidFailure != null ||
                                duplicateFailureId != null ||
                                duplicatePreparedId != null ||
                                unexpectedPreparedId != null
                            ) {
                                port.emit(
                                    InstallationSessionEvent.FatalError(
                                        category = FailureCategory.VERIFICATION,
                                        componentName = invalidFailure?.componentId
                                            ?: duplicateFailureId
                                            ?: duplicatePreparedId
                                            ?: unexpectedPreparedId,
                                        reasonCode = "artifact_preparation_result_invalid",
                                    ),
                                )
                                return@launch
                            }

                            // A typed result may carry component failures without
                            // emitting the corresponding session events. Normalize
                            // those failures at this boundary before deciding if
                            // the device batch is complete.
                            result.failures.forEach { failure ->
                                val componentId = failure.componentId ?: return@forEach
                                if (componentId !in session.currentSnapshot().failedComponentIds) {
                                    port.emit(
                                        InstallationSessionEvent.ArtifactUnavailable(
                                            componentId = componentId,
                                            reasonCode = failure.reasonCode,
                                            sourceKind = failure.sourceKind,
                                            retryable = failure.retryable,
                                        ),
                                    )
                                }
                            }
                            val afterFailures = session.currentSnapshot()
                            val preparedIds = preparedIdsInOrder.toSet()
                            val failedPreparationIds =
                                afterFailures.failedComponentIds intersect preparationIds
                            val failedPreparedId = preparedIds.firstOrNull { it in failedPreparationIds }
                            if (failedPreparedId != null) {
                                port.emit(
                                    InstallationSessionEvent.FatalError(
                                        category = FailureCategory.VERIFICATION,
                                        componentName = failedPreparedId,
                                        reasonCode = "artifact_preparation_result_invalid",
                                    ),
                                )
                                return@launch
                            }
                            val missingFreshIds = (preparationIds - preparedIds) - failedPreparationIds
                            if (missingFreshIds.isNotEmpty()) {
                                missingFreshIds.forEach { componentId ->
                                    port.emit(
                                        InstallationSessionEvent.ArtifactUnavailable(
                                            componentId = componentId,
                                            reasonCode = "artifact_preparation_incomplete",
                                            retryable = false,
                                        ),
                                    )
                                }
                            }

                            val normalizedSnapshot = session.currentSnapshot()
                            val normalizedFailedIds =
                                normalizedSnapshot.failedComponentIds intersect preparationIds
                            val stillMissingIds = (preparationIds - preparedIds) - normalizedFailedIds
                            if (stillMissingIds.isNotEmpty()) {
                                port.emit(
                                    InstallationSessionEvent.FatalError(
                                        category = FailureCategory.VERIFICATION,
                                        reasonCode = "artifact_preparation_result_invalid",
                                    ),
                                )
                                return@launch
                            }
                            val typedFailuresById = result.failures
                                .mapNotNull { failure ->
                                    failure.componentId?.let { componentId ->
                                        componentId to DeviceActionFailure(
                                            reasonCode = failure.reasonCode,
                                            componentId = componentId,
                                            retryable = failure.retryable,
                                        )
                                    }
                                }
                                .toMap()
                            val preparationFailures = normalizedFailedIds.associateWith { componentId ->
                                typedFailuresById[componentId]
                                    ?: normalizedSnapshot.sourceFailures
                                        .asReversed()
                                        .firstOrNull { it.componentId == componentId }
                                        ?.let { failure ->
                                            DeviceActionFailure(
                                                reasonCode = failure.reasonCode,
                                                componentId = componentId,
                                                retryable = failure.retryable,
                                            )
                                        }
                                    ?: normalizedSnapshot.components
                                        .firstOrNull { it.id == componentId }
                                        ?.errorReason
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { reasonCode ->
                                            DeviceActionFailure(
                                                reasonCode = reasonCode,
                                                componentId = componentId,
                                                retryable = normalizedSnapshot.componentFailureRetryable[componentId]
                                                    ?: false,
                                            )
                                        }
                                    ?: DeviceActionFailure(
                                        reasonCode = "artifact_preparation_incomplete",
                                        componentId = componentId,
                                        retryable = false,
                                    )
                            }

                            val reusableArtifacts = reusableManifests.map { manifest ->
                                PreparedArtifact(
                                    manifest = manifest,
                                    sourceKind = null,
                                    finalApk = null,
                                )
                            }
                            val preparedArtifacts = result.artifacts + reusableArtifacts
                            val connection = activeConnection
                            val latest = session.currentSnapshot()
                            if (
                                latest.sessionId != snapshot.sessionId ||
                                latest.state != InstallationSessionState.VERIFYING_ARTIFACTS
                            ) {
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
                            val executeWithBatch = executeDeviceInstallationWithBatch
                            if (executeWithBatch == null || batchPlan == null) {
                                port.emit(
                                    InstallationSessionEvent.FatalError(
                                        category = FailureCategory.INSTALLATION,
                                        reasonCode = "device_action_gateway_unavailable",
                                    ),
                                )
                            } else {
                                executeWithBatch.executeWithPreparationFailures(
                                    connection,
                                    preparedArtifacts,
                                    batchPlan,
                                    preparationFailures,
                                    port,
                                )
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emitPreparationFailure(
                    port = port,
                    snapshot = session.currentSnapshot(),
                    fallbackCategory = FailureCategory.VERIFICATION,
                    fallbackReason = "artifact_preparation_failed",
                )
            }
        }
    }

    private fun emitPreparationFailure(
        port: InstallationSessionEventPort,
        snapshot: InstallationSessionSnapshot,
        fallbackCategory: FailureCategory,
        fallbackReason: String,
        fallbackComponentName: String? = null,
    ) {
        if (snapshot.state == InstallationSessionState.FAILED ||
            snapshot.state == InstallationSessionState.COMPLETED_WITH_ERRORS
        ) {
            return
        }
        val concrete = concreteFailure(snapshot)
        port.emit(
            InstallationSessionEvent.FatalError(
                category = concrete?.second?.let(::failureCategoryForReason) ?: fallbackCategory,
                componentName = concrete?.first ?: fallbackComponentName,
                reasonCode = concrete?.second ?: fallbackReason,
            ),
        )
    }

    private fun concreteFailure(snapshot: InstallationSessionSnapshot): Pair<String, String>? =
        snapshot.installationBatch?.resultComponentIds
            ?.asSequence()
            ?.mapNotNull { componentId ->
                // Component descriptors carry catalog/selection diagnostics,
                // which are not proof that this batch failed. Use only the
                // result projection built from explicit batch failure facts.
                val reason = snapshot.componentResults.firstOrNull { it.componentId == componentId }?.failureReason
                reason?.let { componentId to it }
            }
            ?.firstOrNull()

    private fun failureCategoryForReason(reasonCode: String): FailureCategory =
        failureCategoryForReasonCode(reasonCode)

    private fun failureCategory(failure: ArtifactFailure): FailureCategory = when (failure.phase) {
        ArtifactFailurePhase.ARCHIVE_VERIFICATION,
        ArtifactFailurePhase.EXTRACTION,
        -> FailureCategory.ARCHIVE

        ArtifactFailurePhase.DOWNLOAD,
        ArtifactFailurePhase.SOURCE_RESOLUTION,
        ArtifactFailurePhase.CACHE,
        -> FailureCategory.DOWNLOAD

        ArtifactFailurePhase.CATALOG,
        ArtifactFailurePhase.APK_VERIFICATION,
        -> FailureCategory.VERIFICATION
    }

    private fun hasSelectedCatalogPreparer(): Boolean =
        prepareSelectedCatalogWithBatch != null ||
            prepareSelectedCatalog != null ||
            prepareSelectedCatalogWithSkipped != null

    /**
     * A selected-catalog pass is required whenever the control plane is ready
     * but this batch has not yet produced identities for every non-skipped
     * selected application. This is deliberately independent of list emptiness.
     */
    private fun needsSelectedCatalogPreparation(snapshot: InstallationSessionSnapshot): Boolean {
        if (snapshot.artifactCatalogStage != ArtifactCatalogStage.PREPARED) return true
        val selectedIds = snapshot.installationBatch?.selectedComponentIds
            ?: snapshot.components
                .filter {
                    it.id == com.ninepointnine.helper.domain.device.AuthorizationPlanFactory.DESKTOP_COMPONENT_ID ||
                        it.id in snapshot.selectedOptionalComponentIds
                }
                .map { it.id }
                .toSet()
        val reusableIds = (snapshot.installationBatch?.reusableComponentIds
            ?: session.reusableInstalledComponentIds(snapshot)) intersect selectedIds
        val preparedIds = snapshot.artifactManifests.map { it.componentId }.toSet()
        return (selectedIds - reusableIds - preparedIds - snapshot.failedComponentIds).isNotEmpty()
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
