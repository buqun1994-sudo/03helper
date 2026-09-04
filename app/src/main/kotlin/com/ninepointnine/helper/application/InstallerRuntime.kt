package com.ninepointnine.helper.application

import android.util.Log
import com.ninepointnine.helper.application.device.DeviceDiscoverySessionAdapter
import com.ninepointnine.helper.application.device.DeviceConnectionSessionAdapter
import com.ninepointnine.helper.application.session.InstallationSessionEventDispatcher
import com.ninepointnine.helper.application.session.InstallationSessionBoundary
import com.ninepointnine.helper.application.session.InstallationSessionEventPort
import com.ninepointnine.helper.application.maintenance.MaintenanceController
import com.ninepointnine.helper.application.maintenance.MaintenanceBaselineProjector
import com.ninepointnine.helper.data.artifact.ApkIconRepository
import com.ninepointnine.helper.domain.artifact.ArtifactFailure
import com.ninepointnine.helper.domain.artifact.ArtifactFailurePhase
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.InstallerSelfIdentity
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
import com.ninepointnine.helper.domain.device.InstalledArtifactEvidence
import com.ninepointnine.helper.domain.session.InstallationBatchReceipt
import com.ninepointnine.helper.domain.session.InstallationComponentReceipt
import com.ninepointnine.helper.domain.session.InstallationStageReceipt
import com.ninepointnine.helper.domain.session.InstallationStageReceiptStatus
import com.ninepointnine.helper.domain.session.AuthorizationStageReceipt
import com.ninepointnine.helper.domain.session.AuthorizationStageReceiptStatus
import com.ninepointnine.helper.domain.session.AvailabilityStageReceipt
import com.ninepointnine.helper.domain.session.AvailabilityStageReceiptStatus
import com.ninepointnine.helper.domain.session.failureCategoryForReasonCode
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private sealed interface MaintenancePersistenceOperation {
    data class Save(val snapshot: InstallationSessionSnapshot) : MaintenancePersistenceOperation
    data object Clear : MaintenancePersistenceOperation
}

/** Serializes every operation that owns the retained vehicle connection. */
internal class SerializedDeviceWorkOwner(
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var currentJob: Job? = null

    @Synchronized
    fun replace(block: suspend () -> Unit): Job {
        val predecessor = currentJob
        predecessor?.cancel()
        return scope.launch {
            predecessor?.join()
            mutex.withLock { block() }
        }.also { replacement ->
            currentJob = replacement
        }
    }

    @Synchronized
    fun cancel() {
        currentJob?.cancel()
    }
}

/**
 * Defines what preparation files may survive an operation boundary.
 *
 * The public Download collection is the durable APK source. Only an
 * incomplete fixed-source ZIP may be retained for a retry; private APKs and
 * dynamic-download files are always disposable.
 */
enum class ArtifactWorkspaceCleanupMode {
    COMPLETE,
    PRESERVE_RESUMABLE_DOWNLOADS,
}

/**
 * Platform boundary for the helper APK update. Artifact preparation remains
 * shared with vehicle installs; only the final write endpoint is different.
 */
data class StagedSelfUpdate(
    val manifest: ArtifactManifest,
    val apkFile: File,
)

sealed interface SelfUpdateStageResult {
    data class Ready(val staged: StagedSelfUpdate) : SelfUpdateStageResult
    data class Failed(val reasonCode: String, val retryable: Boolean = true) : SelfUpdateStageResult
}

sealed interface SelfUpdateLaunchResult {
    data object Started : SelfUpdateLaunchResult
    data class Failed(val reasonCode: String, val retryable: Boolean = true) : SelfUpdateLaunchResult
}

sealed interface SelfUpdateVerificationResult {
    data class Confirmed(val evidence: InstalledArtifactEvidence) : SelfUpdateVerificationResult
    data object Pending : SelfUpdateVerificationResult
    data class Failed(val reasonCode: String, val retryable: Boolean = true) : SelfUpdateVerificationResult
}

/** Android-specific installer adapter; no UI or session state lives here. */
interface SelfUpdateInstaller {
    fun stage(artifact: PreparedArtifact): SelfUpdateStageResult
    fun launch(staged: StagedSelfUpdate): SelfUpdateLaunchResult
    fun verifyInstalled(manifest: ArtifactManifest): SelfUpdateVerificationResult
    fun clear(staged: StagedSelfUpdate)
}

/** Sole device-batch execution boundary for one immutable installation batch. */
fun interface InstallationBatchExecutor {
    suspend fun execute(
        connection: DeviceConnectionLease,
        artifacts: List<PreparedArtifact>,
        batchPlan: InstallationBatchPlan,
        preparationFailures: Map<String, DeviceActionFailure>,
        eventPort: InstallationSessionBoundary,
    )
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
    /** First-install inventory probe; production uses the fixed maintenance gateway. */
    private val loadInitialInventory: (suspend (
        InstallationSessionSnapshot,
        DeviceConnectionLease,
        InstallationSessionBoundary,
    ) -> Unit)? = null,
    /** Sole preparation hook for one immutable installation batch. */
    private val prepareInstallationBatch: (suspend (InstallationBatchPlan, InstallationSessionBoundary) -> ArtifactPreparationResult)? = null,
    /** Owns private preparation-file cleanup; public Download files are never removed here. */
    private val cleanupArtifactWorkspace: (suspend (ArtifactWorkspaceCleanupMode) -> Unit)? = null,
    private val maintenanceController: MaintenanceController? = null,
    private val persistMaintenanceSnapshot: (suspend (InstallationSessionSnapshot) -> Unit)? = null,
    private val clearMaintenanceSnapshot: (suspend () -> Unit)? = null,
    coroutineContext: CoroutineContext,
    /** Optional UI adapter; production injects the APK-backed icon reader. */
    val apkIconRepository: ApkIconRepository? = null,
    /** Sole device-installation hook; the immutable domain batch is the complete request. */
    private val executeDeviceInstallationWithBatch: InstallationBatchExecutor? = null,
    /** Optional Android package-installer endpoint for [InstallationFlow.SELF_UPDATE]. */
    private val selfUpdateInstaller: SelfUpdateInstaller? = null,
) : AutoCloseable {
    private val runtimeJob = SupervisorJob(coroutineContext[Job])
    private val scope = CoroutineScope(coroutineContext + runtimeJob)
    private val deviceWorkOwner = SerializedDeviceWorkOwner(scope)
    private var discoveryAdapter: DeviceDiscoverySessionAdapter? = null
    private var discoveryJob: Job? = null
    private var connectionAdapter: DeviceConnectionSessionAdapter? = null
    private var connectionJob: Job? = null
    private var activeConnection: DeviceConnectionLease? = null
    private var lastConfirmedDevice: ConnectedDevice? = null
    private var connectionHealthJob: Job? = null
    private var catalogJob: Job? = null
    private var initialInventoryJob: Job? = null
    private var artifactJob: Job? = null
    private var maintenanceJob: Job? = null
    private var eventDispatcherSessionId: Long? = null
    private var eventDispatcher: InstallationSessionBoundary? = null
    private var automaticReconnectSessionId: Long? = null
    /** Session generation currently attempting the last confirmed endpoint. */
    private var knownReconnectAttemptSessionId: Long? = null
    private var foregroundGeneration = 0L
    private var maintenanceReconnectGeneration: Long? = null
    private var manualMaintenanceDisconnect = false
    private var lastMaintenancePersistenceOperation: MaintenancePersistenceOperation? = null
    private var closed = false
    private var pendingSelfUpdate: PendingSelfUpdate? = null

    private data class PendingSelfUpdate(
        val sessionId: Long,
        val batchId: Long,
        val staged: StagedSelfUpdate,
        var launchIssued: Boolean = false,
    )

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
                    try {
                        when (operation) {
                            is MaintenancePersistenceOperation.Save -> checkNotNull(persist).invoke(operation.snapshot)
                            MaintenancePersistenceOperation.Clear -> checkNotNull(clear).invoke()
                        }
                        // Only a completed write establishes the deduplication
                        // baseline. A failed attempt must remain retryable when
                        // the same business state is emitted again.
                        lastMaintenancePersistenceOperation = operation
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
                beginArtifactPreparation(after)
            }

            is InstallationSessionCommand.StartMaintenanceComponentUpdate -> {
                if (before.state == InstallationSessionState.MAINTENANCE &&
                    after.state == InstallationSessionState.SELECTION_CONFIRMED
                ) {
                    if (after.sessionId != before.sessionId) cancelTransferWork()
                    beginArtifactPreparation(after)
                }
            }

            InstallationSessionCommand.InstallPreparedSelfUpdate -> {
                if (
                    before.state == InstallationSessionState.ARTIFACTS_READY &&
                    after.state == InstallationSessionState.INSTALLING &&
                    after.installationFlow == com.ninepointnine.helper.domain.session.InstallationFlow.SELF_UPDATE
                ) {
                    launchPreparedSelfUpdate(after)
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
                // Leaving a secondary route is a hard task boundary. Cancel
                // every page-owned adapter job so the next page cannot wait
                // behind a stale operation holding the shared device lease.
                cancelTransferWork()
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
                    beginArtifactPreparation(after)
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
                    beginArtifactPreparation(after)
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
        pollSelfUpdate()
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

    private fun launchPreparedSelfUpdate(snapshot: InstallationSessionSnapshot) {
        val pending = pendingSelfUpdate
        val installer = selfUpdateInstaller
        if (pending == null || installer == null ||
            pending.sessionId != snapshot.sessionId ||
            pending.batchId != snapshot.installationBatch?.batchId
        ) {
            eventPortFor(snapshot).emit(
                InstallationSessionEvent.FatalError(
                    category = FailureCategory.INSTALLATION,
                    reasonCode = "self_update_package_missing",
                ),
            )
            return
        }
        when (val result = runCatching { installer.launch(pending.staged) }.getOrElse {
            SelfUpdateLaunchResult.Failed("self_update_installer_failed", retryable = true)
        }) {
            SelfUpdateLaunchResult.Started -> {
                pending.launchIssued = true
            }

            is SelfUpdateLaunchResult.Failed -> {
                pendingSelfUpdate = null
                runCatching { installer.clear(pending.staged) }
                eventPortFor(snapshot).emit(
                    InstallationSessionEvent.FatalError(
                        category = FailureCategory.INSTALLATION,
                        reasonCode = result.reasonCode,
                    ),
                )
            }
        }
    }

    /** Reads the package manager only after the system installer has returned. */
    private fun pollSelfUpdate() {
        val pending = pendingSelfUpdate ?: return
        if (!pending.launchIssued) return
        val snapshot = session.currentSnapshot()
        if (
            snapshot.sessionId != pending.sessionId ||
            snapshot.installationBatch?.batchId != pending.batchId ||
            snapshot.state != InstallationSessionState.INSTALLING
        ) {
            return
        }
        val installer = selfUpdateInstaller ?: return
        when (val result = runCatching {
            installer.verifyInstalled(pending.staged.manifest)
        }.getOrElse {
            SelfUpdateVerificationResult.Failed("self_update_readback_failed", retryable = true)
        }) {
            SelfUpdateVerificationResult.Pending -> Unit
            is SelfUpdateVerificationResult.Failed -> {
                pendingSelfUpdate = null
                runCatching { installer.clear(pending.staged) }
                eventPortFor(snapshot).emit(
                    InstallationSessionEvent.FatalError(
                        category = FailureCategory.INSTALLATION,
                        reasonCode = result.reasonCode,
                    ),
                )
            }

            is SelfUpdateVerificationResult.Confirmed -> {
                val evidence = result.evidence
                val manifest = pending.staged.manifest
                val mismatch = when {
                    evidence.componentId != InstallerSelfIdentity.COMPONENT_ID -> "self_update_readback_component_mismatch"
                    evidence.packageName != InstallerSelfIdentity.PACKAGE_NAME -> "self_update_readback_package_mismatch"
                    evidence.version != manifest.apkVersion -> "self_update_readback_version_mismatch"
                    evidence.apkSizeBytes != manifest.apkSizeBytes -> "self_update_readback_size_mismatch"
                    !evidence.apkSha256.equals(manifest.apkSha256, ignoreCase = true) -> "self_update_readback_hash_mismatch"
                    !evidence.certificateSha256.equals(manifest.certificateSha256, ignoreCase = true) ->
                        "self_update_readback_certificate_mismatch"
                    else -> null
                }
                if (mismatch != null) {
                    pendingSelfUpdate = null
                    runCatching { installer.clear(pending.staged) }
                    eventPortFor(snapshot).emit(
                        InstallationSessionEvent.FatalError(
                            category = FailureCategory.VERIFICATION,
                            reasonCode = mismatch,
                        ),
                    )
                    return
                }
                pendingSelfUpdate = null
                runCatching { installer.clear(pending.staged) }
                eventPortFor(snapshot).emit(
                    InstallationSessionEvent.InstallationBatchCompleted(
                        selfUpdateReceipt(pending.batchId, evidence),
                    ),
                )
            }
        }
    }

    private fun selfUpdateReceipt(
        batchId: Long,
        evidence: InstalledArtifactEvidence,
    ): InstallationBatchReceipt = InstallationBatchReceipt(
        batchId = batchId,
        components = listOf(
            InstallationComponentReceipt(
                componentId = InstallerSelfIdentity.COMPONENT_ID,
                installation = InstallationStageReceipt(
                    status = InstallationStageReceiptStatus.VERIFIED,
                    evidence = evidence,
                    writeConfirmed = true,
                    operationConfirmed = true,
                ),
                authorization = AuthorizationStageReceipt(
                    status = AuthorizationStageReceiptStatus.NOT_REQUIRED,
                ),
                availability = AvailabilityStageReceipt(
                    status = AvailabilityStageReceiptStatus.NOT_REQUIRED,
                ),
            ),
        ),
    )

    private fun clearPendingSelfUpdate() {
        val pending = pendingSelfUpdate ?: return
        pendingSelfUpdate = null
        selfUpdateInstaller?.let { installer -> runCatching { installer.clear(pending.staged) } }
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
                    loadInitialInventory != null &&
                    snapshot.initialInventory.state == com.ninepointnine.helper.domain.session.InitialApplicationInventoryState.NOT_STARTED &&
                    initialInventoryJob?.isActive != true
                ) {
                    launchInitialInventory(snapshot)
                } else if (
                    snapshot.artifactCatalogStage == ArtifactCatalogStage.NOT_LOADED ||
                    snapshot.artifactCatalogStage == ArtifactCatalogStage.CONTROL_PLANE_READY &&
                    snapshot.failure != null
                ) {
                    launchCatalog(snapshot)
                }
            }

            InstallationSessionState.SELECTION_CONFIRMED -> beginArtifactPreparation(snapshot)
            InstallationSessionState.PREPARING_ARTIFACTS -> launchArtifactPreparation(snapshot)

            InstallationSessionState.ARTIFACTS_READY,
            InstallationSessionState.INSTALLING,
            InstallationSessionState.AUTHORIZING,
            InstallationSessionState.VERIFYING_DEVICE,
            -> if (snapshot.installationFlow == com.ninepointnine.helper.domain.session.InstallationFlow.SELF_UPDATE) {
                if (snapshot.state == InstallationSessionState.INSTALLING) {
                    pollSelfUpdate()
                } else if (pendingSelfUpdate == null) {
                    beginArtifactPreparation(snapshot)
                }
            } else {
                restartInstallationFromCheckpoint(snapshot)
            }
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
            beginArtifactPreparation(restored)
        }
    }

    private fun beginAutomaticInstallReconnect(snapshot: InstallationSessionSnapshot) {
        if (snapshot.checkpoint == null) return
        dispatch(InstallationSessionCommand.Reconnect)
    }

    private fun beginArtifactPreparation(snapshot: InstallationSessionSnapshot) {
        val pipeline = session.dispatch(InstallationSessionCommand.BeginPipeline)
        if (pipeline.state == InstallationSessionState.PREPARING_ARTIFACTS) {
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
                    if (loadInitialInventory == null) {
                        launchCatalog(confirmedSnapshot)
                    } else {
                        launchInitialInventory(confirmedSnapshot)
                    }
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

    private fun launchInitialInventory(snapshot: InstallationSessionSnapshot) {
        val loader = loadInitialInventory ?: run {
            launchCatalog(snapshot)
            return
        }
        if (initialInventoryJob?.isActive == true) return
        val port = eventPortFor(snapshot)
        val connection = activeConnection ?: return
        initialInventoryJob = deviceWorkOwner.replace {
            try {
                loader(snapshot, connection, port)
                val latest = session.currentSnapshot()
                if (
                    latest.sessionId == snapshot.sessionId &&
                    latest.state == InstallationSessionState.CONNECTED &&
                    latest.initialInventory.state == com.ninepointnine.helper.domain.session.InitialApplicationInventoryState.READY
                ) {
                    launchCatalog(latest)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                port.emit(
                    InstallationSessionEvent.InitialInstalledApplicationsFailed(
                        reasonCode = "initial_inventory_read_failed",
                        retryable = true,
                    ),
                )
            }
        }
    }

    private fun launchArtifactPreparation(snapshot: InstallationSessionSnapshot) {
        artifactJob?.cancel()
        initialInventoryJob?.cancel()
        val port = eventPortFor(snapshot)
        val batchPlan = snapshot.installationBatch
        val preparer = prepareInstallationBatch
        if (batchPlan == null) {
            port.emit(
                InstallationSessionEvent.FatalError(
                    category = FailureCategory.VERIFICATION,
                    reasonCode = "installation_batch_missing",
                ),
            )
            return
        }
        if (preparer == null) {
            port.emit(
                InstallationSessionEvent.FatalError(
                    category = FailureCategory.VERIFICATION,
                    reasonCode = "artifact_preparation_unavailable",
                ),
            )
            return
        }
        launchUnifiedArtifactPreparation(snapshot, batchPlan, port)
    }


    private fun launchUnifiedArtifactPreparation(
        snapshot: InstallationSessionSnapshot,
        batchPlan: InstallationBatchPlan,
        port: InstallationSessionBoundary,
    ) {
        artifactJob = deviceWorkOwner.replace {
            runUnifiedArtifactPreparation(snapshot, batchPlan, port)
        }
    }

    private suspend fun runUnifiedArtifactPreparation(
        snapshot: InstallationSessionSnapshot,
        batchPlan: InstallationBatchPlan,
        port: InstallationSessionBoundary,
    ) {
        var preparationReturned = false
        var executionStarted = false
        var cleanupRequested = false
        var cleanupMode = ArtifactWorkspaceCleanupMode.PRESERVE_RESUMABLE_DOWNLOADS
        try {
            val result = checkNotNull(prepareInstallationBatch).invoke(batchPlan, port)
            preparationReturned = true
            cleanupRequested = true
            cleanupMode = when (result) {
                is ArtifactPreparationResult.Failed -> if (result.failure.retryable) {
                    ArtifactWorkspaceCleanupMode.PRESERVE_RESUMABLE_DOWNLOADS
                } else {
                    ArtifactWorkspaceCleanupMode.COMPLETE
                }

                is ArtifactPreparationResult.Prepared -> if (result.failures.any { it.retryable }) {
                    ArtifactWorkspaceCleanupMode.PRESERVE_RESUMABLE_DOWNLOADS
                } else {
                    ArtifactWorkspaceCleanupMode.COMPLETE
                }
            }
            val latest = session.currentSnapshot()
            if (
                latest.sessionId != snapshot.sessionId ||
                latest.installationBatch?.batchId != batchPlan.batchId
            ) return
            when (result) {
                is ArtifactPreparationResult.Failed -> {
                    if (latest.state == InstallationSessionState.PREPARING_ARTIFACTS) {
                        emitPreparationFailure(
                            port = port,
                            snapshot = latest,
                            fallbackCategory = failureCategory(result.failure),
                            fallbackReason = result.failure.reasonCode,
                            fallbackComponentName = result.failure.componentId,
                        )
                    }
                }

                is ArtifactPreparationResult.Prepared -> {
                    if (latest.state != InstallationSessionState.ARTIFACTS_READY) {
                        if (latest.state == InstallationSessionState.PREPARING_ARTIFACTS) {
                            port.emit(
                                InstallationSessionEvent.FatalError(
                                    category = FailureCategory.VERIFICATION,
                                    reasonCode = "artifact_batch_event_missing",
                                ),
                            )
                        }
                        return
                    }
                    val preparedIds = result.artifacts.map { it.manifest.componentId }
                    val failureIds = result.failures.mapNotNull { it.componentId }
                    val expectedIds = batchPlan.preparationComponentIds
                    val ids = preparedIds.toSet() + failureIds.toSet()
                    if (
                        preparedIds.size != preparedIds.toSet().size ||
                        failureIds.size != failureIds.toSet().size ||
                        ids != expectedIds ||
                        preparedIds.toSet() intersect failureIds.toSet() != emptySet<String>()
                    ) {
                        port.emit(
                            InstallationSessionEvent.FatalError(
                                category = FailureCategory.VERIFICATION,
                                reasonCode = "artifact_preparation_result_invalid",
                            ),
                        )
                        return
                    }
                    if (batchPlan.flow == com.ninepointnine.helper.domain.session.InstallationFlow.SELF_UPDATE) {
                        if (result.failures.isNotEmpty()) {
                            emitPreparationFailure(
                                port = port,
                                snapshot = latest,
                                fallbackCategory = FailureCategory.DOWNLOAD,
                                fallbackReason = result.failures.first().reasonCode,
                                fallbackComponentName = result.failures.first().componentId,
                            )
                            return
                        }
                        val artifact = result.artifacts.singleOrNull {
                            InstallerSelfIdentity.isSelfComponentId(it.manifest.componentId) &&
                                it.manifest.packageName == InstallerSelfIdentity.PACKAGE_NAME
                        }
                        val installer = selfUpdateInstaller
                        if (artifact == null || installer == null) {
                            port.emit(
                                InstallationSessionEvent.FatalError(
                                    category = FailureCategory.INSTALLATION,
                                    reasonCode = if (installer == null) {
                                        "self_update_installer_unavailable"
                                    } else {
                                        "self_update_artifact_invalid"
                                    },
                                ),
                            )
                            return
                        }
                        when (val staged = runCatching { installer.stage(artifact) }.getOrElse {
                            SelfUpdateStageResult.Failed("self_update_stage_failed", retryable = true)
                        }) {
                            is SelfUpdateStageResult.Ready -> {
                                pendingSelfUpdate = PendingSelfUpdate(
                                    sessionId = latest.sessionId,
                                    batchId = batchPlan.batchId,
                                    staged = staged.staged,
                                )
                            }

                            is SelfUpdateStageResult.Failed -> {
                                port.emit(
                                    InstallationSessionEvent.FatalError(
                                        category = FailureCategory.INSTALLATION,
                                        reasonCode = staged.reasonCode,
                                    ),
                                )
                            }
                        }
                        return
                    }
                    val preparationFailures = result.failures.associate { failure ->
                        val componentId = checkNotNull(failure.componentId)
                        componentId to DeviceActionFailure(
                            reasonCode = failure.reasonCode,
                            componentId = componentId,
                            retryable = failure.retryable,
                        )
                    }
                    val reusableArtifacts = batchPlan.reusableComponentIds.mapNotNull { componentId ->
                        session.installedIdentityManifest(latest, componentId)?.let { manifest ->
                            PreparedArtifact(
                                manifest = manifest,
                                sourceKind = null,
                                finalApk = null,
                            )
                        }
                    }
                    val connection = activeConnection
                    if (connection == null) {
                        port.emit(
                            InstallationSessionEvent.FatalError(
                                category = FailureCategory.INSTALLATION,
                                reasonCode = "device_action_gateway_unavailable",
                            ),
                        )
                        return
                    }
                    val executeWithBatch = executeDeviceInstallationWithBatch
                    if (executeWithBatch == null) {
                        port.emit(
                            InstallationSessionEvent.FatalError(
                                category = FailureCategory.INSTALLATION,
                                reasonCode = "device_action_gateway_unavailable",
                            ),
                        )
                    } else {
                        executionStarted = true
                        executeWithBatch.execute(
                            connection = connection,
                            artifacts = result.artifacts + reusableArtifacts,
                            batchPlan = batchPlan,
                            preparationFailures = preparationFailures,
                            eventPort = port,
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            cleanupRequested = true
            if (!preparationReturned || !executionStarted) {
                // A cancellation before device execution may have left a
                // fixed-source ZIP part that can be resumed. Dynamic
                // downloads and private APKs are filtered by the cleaner.
                cleanupMode = ArtifactWorkspaceCleanupMode.PRESERVE_RESUMABLE_DOWNLOADS
            }
            throw cancelled
        } catch (_: Exception) {
            cleanupRequested = true
            if (!preparationReturned) {
                cleanupMode = ArtifactWorkspaceCleanupMode.PRESERVE_RESUMABLE_DOWNLOADS
            }
            emitPreparationFailure(
                port = port,
                snapshot = session.currentSnapshot(),
                fallbackCategory = FailureCategory.DOWNLOAD,
                fallbackReason = "artifact_preparation_failed",
            )
        } finally {
            if (cleanupRequested) cleanupArtifactWorkspaceSafely(cleanupMode)
        }
    }

    private suspend fun cleanupArtifactWorkspaceSafely(mode: ArtifactWorkspaceCleanupMode) {
        val cleanup = cleanupArtifactWorkspace ?: return
        withContext(NonCancellable) {
            try {
                cleanup(mode)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                Log.w("03helper-runtime", "artifact_workspace_cleanup_failed", exception)
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
        maintenanceJob = deviceWorkOwner.replace {
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
        maintenanceJob = deviceWorkOwner.replace {
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

    private fun eventPortFor(snapshot: InstallationSessionSnapshot): InstallationSessionBoundary {
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
        connectionHealthJob?.cancel()
        initialInventoryJob?.cancel()
        artifactJob?.cancel()
        maintenanceJob?.cancel()
        deviceWorkOwner.cancel()
        catalogJob = null
        connectionHealthJob = null
        artifactJob = null
        initialInventoryJob = null
        maintenanceJob = null
        clearPendingSelfUpdate()
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
            InstallationSessionState.PREPARING_ARTIFACTS,
            InstallationSessionState.ARTIFACTS_READY,
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
            InstallationSessionState.PREPARING_ARTIFACTS,
            InstallationSessionState.ARTIFACTS_READY,
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
