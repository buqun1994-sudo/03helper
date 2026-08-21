package com.tcrrry.helper.application

import com.tcrrry.helper.application.device.DeviceDiscoverySessionAdapter
import com.tcrrry.helper.application.device.DeviceConnectionSessionAdapter
import com.tcrrry.helper.application.session.InstallationSessionEventDispatcher
import com.tcrrry.helper.application.session.InstallationSessionEventPort
import com.tcrrry.helper.application.maintenance.MaintenanceController
import com.tcrrry.helper.domain.artifact.ArtifactManifest
import com.tcrrry.helper.application.artifact.ArtifactPreparationResult
import com.tcrrry.helper.application.artifact.PreparedArtifact
import com.tcrrry.helper.domain.device.ConnectedDevice
import com.tcrrry.helper.domain.device.DeviceConnectionLease
import com.tcrrry.helper.domain.session.DeviceConnectionStatus
import com.tcrrry.helper.domain.session.FailureCategory
import com.tcrrry.helper.domain.session.InstallationSession
import com.tcrrry.helper.domain.session.InstallationSessionCommand
import com.tcrrry.helper.domain.session.InstallationSessionEvent
import com.tcrrry.helper.domain.session.InstallationSessionSnapshot
import com.tcrrry.helper.domain.session.InstallationSessionState
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
) : AutoCloseable {
    private val runtimeJob = SupervisorJob(coroutineContext[Job])
    private val scope = CoroutineScope(coroutineContext + runtimeJob)
    private var discoveryAdapter: DeviceDiscoverySessionAdapter? = null
    private var discoveryJob: Job? = null
    private var connectionAdapter: DeviceConnectionSessionAdapter? = null
    private var connectionJob: Job? = null
    private var activeConnection: DeviceConnectionLease? = null
    private var connectionHealthJob: Job? = null
    private var catalogJob: Job? = null
    private var artifactJob: Job? = null
    private var maintenanceJob: Job? = null
    private var eventDispatcherSessionId: Long? = null
    private var eventDispatcher: InstallationSessionEventPort? = null
    private var closed = false

    init {
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
        val before = session.currentSnapshot()
        val after = session.dispatch(command)

        when (command) {
            InstallationSessionCommand.StartDiscovery,
            InstallationSessionCommand.Reconnect,
            -> if (enteredNewGeneration(before, after, InstallationSessionState.DISCOVERING)) {
                cancelAllWork(closeConnection = true)
                launchDiscovery(after)
            }

            InstallationSessionCommand.StopDiscovery -> {
                if (before.state == InstallationSessionState.DISCOVERING) cancelDiscovery()
            }

            InstallationSessionCommand.CancelConnection -> {
                if (after.sessionId != before.sessionId) cancelConnectionAttempt()
            }

            is InstallationSessionCommand.SelectDevice -> {
                if (enteredNewGeneration(before, after, InstallationSessionState.CONNECTING)) {
                    val selectedDevice = discoveryAdapter?.confirmedDevice(command.deviceId)
                    cancelDiscovery()
                    if (selectedDevice == null) {
                        eventPortFor(after).emit(
                            InstallationSessionEvent.DeviceConnectionFailed(
                                deviceId = command.deviceId,
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
                beginArtifactPreparation(after)
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
                reconcile(after)
            }

            InstallationSessionCommand.DisconnectDevice -> {
                maintenanceJob?.cancel()
                closeDeviceConnection()
            }

            is InstallationSessionCommand.ToggleOptionalComponent,
            InstallationSessionCommand.BeginPipeline,
            InstallationSessionCommand.EnterMaintenance,
            -> Unit

            is InstallationSessionCommand.MaintenanceAction -> when {
                before.state == InstallationSessionState.MAINTENANCE &&
                    after.state == InstallationSessionState.SELECTION_CONFIRMED -> beginArtifactPreparation(after)

                before.state == InstallationSessionState.MAINTENANCE &&
                    after.maintenance.activeAction == command.actionId &&
                    before.maintenance.activeAction != command.actionId -> launchMaintenanceAction(command.actionId, after)
            }

            is InstallationSessionCommand.AdapterEvent -> {
                if (command.event is InstallationSessionEvent.DeviceDisconnected) {
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
        val current = session.currentSnapshot()
        return when {
            current.state == InstallationSessionState.IDLE -> dispatch(InstallationSessionCommand.StartDiscovery)
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

    private fun reconcile(snapshot: InstallationSessionSnapshot) {
        when (snapshot.state) {
            InstallationSessionState.DISCOVERING -> launchDiscovery(snapshot)
            InstallationSessionState.CONNECTING -> Unit
            InstallationSessionState.CONNECTED -> {
                if (snapshot.artifactManifests.isEmpty()) launchCatalog(snapshot)
            }

            InstallationSessionState.SELECTION_CONFIRMED -> beginArtifactPreparation(snapshot)
            InstallationSessionState.RESOLVING_SOURCE -> launchArtifactPreparation(snapshot)
            else -> Unit
        }
    }

    private fun beginArtifactPreparation(snapshot: InstallationSessionSnapshot) {
        val pipeline = session.dispatch(InstallationSessionCommand.BeginPipeline)
        if (pipeline.state == InstallationSessionState.RESOLVING_SOURCE) {
            launchArtifactPreparation(pipeline)
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                port.emit(InstallationSessionEvent.DiscoveryFinished(0, 0, "discovery_failed"))
            }
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
                        confirmedSnapshot.state != InstallationSessionState.CONNECTED &&
                            confirmedSnapshot.state != InstallationSessionState.MAINTENANCE
                        ) ||
                    confirmedSnapshot.device?.id != device.identity.stableId
                ) {
                    connection.close()
                    return@launch
                }
                activeConnection?.close()
                activeConnection = connection
                if (confirmedSnapshot.state == InstallationSessionState.CONNECTED) {
                    launchCatalog(confirmedSnapshot)
                } else {
                    scheduleConnectionHealthCheck(confirmedSnapshot)
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
            .filter { it.required || it.id in snapshot.selectedOptionalComponentIds }
            .map { it.id }
            .toSet()
        val manifests = snapshot.artifactManifests.filter { it.componentId in selectedIds }
        if (manifests.map { it.componentId }.toSet() != selectedIds) {
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
        actionId: com.tcrrry.helper.domain.session.MaintenanceActionId,
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
                com.tcrrry.helper.domain.device.DeviceConnectionCheck(false, "adb_connection_check_failed")
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
            InstallationSessionState.PAUSED,
            InstallationSessionState.MAINTENANCE,
        )
    }
}
