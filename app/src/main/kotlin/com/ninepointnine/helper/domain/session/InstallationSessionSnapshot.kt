package com.ninepointnine.helper.domain.session

import com.ninepointnine.helper.domain.artifact.ArchiveDownloadEvidence
import com.ninepointnine.helper.domain.artifact.ArchiveVerificationEvidence
import com.ninepointnine.helper.domain.artifact.ApkExtractionEvidence
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.AppIconAsset
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ArtifactVerification
import com.ninepointnine.helper.domain.artifact.SourceFailureRecord
import com.ninepointnine.helper.domain.device.DeviceCapability
import com.ninepointnine.helper.domain.device.AuthorizationActionEvidence
import com.ninepointnine.helper.domain.device.DeviceAvailabilityEvidence
import com.ninepointnine.helper.domain.device.InstalledArtifactEvidence
import com.ninepointnine.helper.domain.device.DeviceInstallWarning
import com.ninepointnine.helper.domain.device.MaintenanceAuthorizationState
import com.ninepointnine.helper.domain.device.ManagedApplicationAuthorizationStatus
import com.ninepointnine.helper.domain.device.AuthorizationPlanFactory

data class InstallationSessionSnapshot(
    val state: InstallationSessionState,
    val device: DeviceSummary? = null,
    val discoveredDevices: List<DeviceSummary> = emptyList(),
    val components: List<ComponentDescriptor> = emptyList(),
    val selectedOptionalComponentIds: Set<String> = emptySet(),
    val currentComponentName: String? = null,
    val progress: SessionProgress? = null,
    /** Per-application progress projected from the same installation event stream. */
    val componentProgress: Map<String, ComponentProgress> = emptyMap(),
    /** Selected applications that were attempted but failed in one phase. */
    val failedComponentIds: Set<String> = emptySet(),
    /** Retryability of the latest structured failure for each component. */
    val componentFailureRetryable: Map<String, Boolean> = emptyMap(),
    val failure: SessionFailure? = null,
    val componentResults: List<ComponentResult> = emptyList(),
    val sessionId: Long = 0L,
    val revision: Long = 0L,
    val lastEventSequence: Long = 0L,
    val checkpoint: SessionCheckpoint? = null,
    val evidence: SessionEvidence = SessionEvidence(),
    val artifactManifests: List<ArtifactManifest> = emptyList(),
    /** Stage of the current installation batch's artifact catalog. */
    val artifactCatalogStage: ArtifactCatalogStage = ArtifactCatalogStage.NOT_LOADED,
    val catalogVersion: String? = null,
    val catalogRevision: Long = 0L,
    val catalogKeyId: String? = null,
    val catalogSignatureAlgorithm: String? = null,
    val selectedSources: Map<String, ArtifactSourceKind> = emptyMap(),
    val sourceFailures: List<SourceFailureRecord> = emptyList(),
    val archiveDownloads: Map<String, ArchiveDownloadEvidence> = emptyMap(),
    val archiveVerifications: Map<String, ArchiveVerificationEvidence> = emptyMap(),
    val apkExtractions: Map<String, ApkExtractionEvidence> = emptyMap(),
    val maintenance: MaintenanceSnapshot = MaintenanceSnapshot(),
    /** True only while discovery was started to restore a disconnected maintenance lease. */
    val maintenanceReconnectPending: Boolean = false,
    /** True only while discovery is restoring an interrupted installation lease. */
    val installationReconnectPending: Boolean = false,
    /** Explicit install intent retained across a resumable batch. */
    val installationStrategy: InstallationStrategy = InstallationStrategy.INSTALL_MISSING_ONLY,
    /** Explicit business flow retained across a resumable batch. */
    val installationFlow: InstallationFlow = InstallationFlow.INITIAL_INSTALL,
    /** Immutable component decisions for the current installation attempt. */
    val installationBatch: InstallationBatchPlan? = null,
    /** Immutable device receipt committed once for the current batch. */
    val installationBatchReceipt: InstallationBatchReceipt? = null,
)

data class DeviceSummary(
    val id: String,
    val displayName: String,
    val connectionStatus: DeviceConnectionStatus,
    val lastConfirmedLabel: String? = null,
    val androidSdk: Int? = null,
    val capabilities: Set<DeviceCapability> = emptySet(),
)

data class ComponentDescriptor(
    val id: String,
    val displayName: String,
    val required: Boolean,
    val versionLabel: String? = null,
    val sizeLabel: String? = null,
    val compatibilityLabel: String? = null,
    /** Compatibility is evaluated internally; raw Android API numbers stay out of the UI. */
    val compatibilityState: ComponentCompatibility = ComponentCompatibility.SUPPORTED,
    /** Stable local asset key; remote data cannot select an arbitrary drawable. */
    val iconKey: String = id,
    /** Optional signed remote preview asset; APK-derived icons remain higher priority. */
    val iconAsset: AppIconAsset? = null,
    val description: String = "",
    val status: ComponentStatus = ComponentStatus.READING,
    val errorReason: String? = null,
)

enum class ComponentCompatibility {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

enum class ComponentStatus {
    READING,
    AVAILABLE,
    INSTALLED_LATEST,
    UPDATE_AVAILABLE,
    DIRECTORY_MISSING,
    ZIP_VALIDATION_FAILED,
    APK_SIGNATURE_MISMATCH,
    CLIENT_CAPABILITY_INSUFFICIENT,
    TEMPORARILY_UNAVAILABLE,
    UNLISTED,
    NEW,
}

data class SessionProgress(
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val fraction: Float? = null,
    val indeterminate: Boolean = false,
)

enum class ComponentProgressStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
}

data class ComponentProgress(
    val componentId: String,
    val phase: InstallPhase = InstallPhase.FETCH,
    val status: ComponentProgressStatus = ComponentProgressStatus.PENDING,
    val bytesWritten: Long = 0L,
    val totalBytes: Long = 0L,
    val fraction: Float? = null,
    val indeterminate: Boolean = true,
)

data class SessionFailure(
    val category: FailureCategory,
    val componentName: String? = null,
    val retryable: Boolean = true,
    val reasonCode: String? = null,
)

data class ComponentResult(
    val componentName: String,
    val installed: Boolean,
    val configured: Boolean,
    val available: Boolean,
    val componentId: String? = null,
    /** The first-class reason for this component's failed attempt, if any. */
    val failureReason: String? = null,
    val failurePhase: InstallPhase? = null,
    val retryable: Boolean = false,
    /** True when the device accepted the APK write but identity proof is absent. */
    val writeConfirmed: Boolean = false,
    /** True when the device operation completed but identity confirmation is unavailable. */
    val confirmationPending: Boolean = false,
    /** Stable stage semantics used by both initial and maintenance result views. */
    val status: ComponentResultStatus = resolveComponentResultStatus(
        installed = installed,
        configured = configured,
        available = available,
        failureReason = failureReason,
        failurePhase = failurePhase,
        writeConfirmed = writeConfirmed,
        confirmationPending = confirmationPending,
    ),
) {
    /**
     * Recomputes the status from the evidence fields. The constructor status
     * remains source-compatible for older callers, but it is never trusted by
     * the result projection because restored snapshots may carry stale data.
     */
    val derivedStatus: ComponentResultStatus
        get() = resolveComponentResultStatus(
            installed = installed,
            configured = configured,
            available = available,
            failureReason = failureReason,
            failurePhase = failurePhase,
            writeConfirmed = writeConfirmed,
            confirmationPending = confirmationPending,
        )
}

enum class ComponentResultStatus {
    NOT_INSTALLED,
    INSTALLATION_PENDING_CONFIRMATION,
    AUTHORIZATION_INCOMPLETE,
    AVAILABILITY_INCOMPLETE,
    READY,
}

/**
 * Converts all evidence for one component into its only user-facing result.
 * A structured failure phase wins over stale positive evidence from an older
 * checkpoint, while an explicit identity mismatch can never become pending.
 */
private fun resolveComponentResultStatus(
    installed: Boolean,
    configured: Boolean,
    available: Boolean,
    failureReason: String?,
    failurePhase: InstallPhase?,
    writeConfirmed: Boolean,
    confirmationPending: Boolean,
): ComponentResultStatus {
    if (failureReason != null && !installed) return ComponentResultStatus.NOT_INSTALLED
    if (confirmationPending || (writeConfirmed && !installed)) {
        return ComponentResultStatus.INSTALLATION_PENDING_CONFIRMATION
    }
    if (!installed) return ComponentResultStatus.NOT_INSTALLED

    val effectiveFailurePhase = failurePhase ?: failureReason?.let(::installPhaseForReasonCode)
    if (effectiveFailurePhase != null) {
        return when (effectiveFailurePhase) {
            InstallPhase.CONFIGURE -> ComponentResultStatus.AUTHORIZATION_INCOMPLETE
            InstallPhase.VERIFY -> ComponentResultStatus.AVAILABILITY_INCOMPLETE
            InstallPhase.FETCH,
            InstallPhase.CHECK,
            InstallPhase.SEND,
            -> ComponentResultStatus.NOT_INSTALLED
        }
    }
    return when {
        !configured -> ComponentResultStatus.AUTHORIZATION_INCOMPLETE
        !available -> ComponentResultStatus.AVAILABILITY_INCOMPLETE
        failureReason != null -> ComponentResultStatus.NOT_INSTALLED
        else -> ComponentResultStatus.READY
    }
}

/**
 * The one terminal result projection for both initial installation and
 * maintenance installation. UI may translate its reason code, but must not
 * recompute success, failure stage, or the visible batch from raw evidence.
 */
data class InstallationResultSummary(
    val kind: ResultKind,
    val componentResults: List<ComponentResult>,
    val failureReasonCode: String? = null,
    val installationFlow: InstallationFlow,
    val failureStage: InstallationResultFailureStage,
    val canContinue: Boolean,
    val canEnterMaintenance: Boolean,
)

/**
 * Returns the selected components whose device operation reached the
 * readback boundary but still lacks trusted identity evidence. The derived
 * set intentionally excludes a component once it is installed or explicitly
 * failed, so a late observation cannot resurrect an old pending row.
 */
internal fun InstallationSessionSnapshot.confirmationPendingComponentIds(): Set<String> {
    val selectedIds = installationBatch?.selectedComponentIds ?: components
        .filter { it.required || it.id in selectedOptionalComponentIds }
        .mapTo(linkedSetOf()) { it.id }
    return (
        (evidence.confirmationPending + (evidence.writeConfirmed - evidence.installed)) intersect selectedIds
        ) - failedComponentIds - evidence.installed
}

fun InstallationSessionSnapshot.resolveInstallationResult(): InstallationResultSummary {
    installationBatchReceipt?.let { receipt ->
        return receipt.toResultSummary(this)
    }
    val batch = installationBatch
    val resultIds = batch?.resultComponentIds
    val rows = if (resultIds == null) {
        componentResults
    } else {
        componentResults.filter { it.componentId in resultIds }
    }
    // A maintenance batch can contain reusable prerequisites that deliberately
    // have no visible result row. Aggregate classification must still inspect
    // the complete selected batch, while the UI projection remains scoped to
    // resultComponentIds.
    val selectedIds = batch?.selectedComponentIds ?: run {
        val rowIds = rows.mapNotNull { it.componentId }.toSet()
        if (rowIds.isNotEmpty()) {
            rowIds
        } else {
            components
                .filter { it.required || it.id in selectedOptionalComponentIds }
                .mapTo(linkedSetOf()) { it.id }
        }
    }
    val scopedIds = resultIds ?: rows.mapNotNull { it.componentId }.toSet().ifEmpty { selectedIds }
    val evidenceIds = selectedIds
    val installedIds = evidence.installed intersect evidenceIds
    val configuredIds = evidence.configured intersect evidenceIds
    val availableIds = evidence.available intersect evidenceIds
    val failedIds = failedComponentIds intersect evidenceIds
    val pendingIds = confirmationPendingComponentIds() intersect evidenceIds
    val ordinaryInstallationFailureIds = failedIds - installedIds - pendingIds
    val unaccountedIds = evidenceIds - installedIds - failedIds - pendingIds
    val hasInstallationFailure = ordinaryInstallationFailureIds.isNotEmpty() ||
        unaccountedIds.isNotEmpty() ||
        rows.any { !it.installed && it.derivedStatus == ComponentResultStatus.NOT_INSTALLED }
    val hasPostInstallFailure = pendingIds.isNotEmpty() ||
        installedIds.any { it !in configuredIds || it !in availableIds } ||
        (failedIds intersect installedIds).isNotEmpty() ||
        rows.any { it.installed && (!it.configured || !it.available) }
    val declaredKind = when (state) {
        InstallationSessionState.SUCCEEDED -> ResultKind.SUCCESS
        InstallationSessionState.COMPLETED_WITH_ERRORS -> ResultKind.PARTIAL_FAILURE
        InstallationSessionState.PAUSED -> ResultKind.PAUSED
        InstallationSessionState.FAILED -> when (failure?.category) {
            FailureCategory.DOWNLOAD,
            FailureCategory.ARCHIVE,
            -> ResultKind.DOWNLOAD_FAILED

            FailureCategory.CONFIGURATION -> ResultKind.CONFIGURATION_FAILED
            else -> ResultKind.INSTALLATION_FAILED
        }

        else -> ResultKind.INSTALLATION_FAILED
    }
    val successEvidence = declaredKind == ResultKind.SUCCESS &&
        hasConsistentSuccessEvidence(this, scopedIds)
    val kind = when {
        declaredKind == ResultKind.PAUSED -> ResultKind.PAUSED
        declaredKind == ResultKind.SUCCESS && successEvidence -> ResultKind.SUCCESS
        !hasInstallationFailure && pendingIds.isNotEmpty() &&
            declaredKind in setOf(ResultKind.SUCCESS, ResultKind.PARTIAL_FAILURE, ResultKind.INSTALLATION_FAILED) ->
            ResultKind.CONFIRMATION_PENDING
        declaredKind == ResultKind.SUCCESS && hasPostInstallFailure ->
            ResultKind.PARTIAL_FAILURE
        declaredKind == ResultKind.SUCCESS -> ResultKind.INSTALLATION_FAILED
        declaredKind !in setOf(ResultKind.SUCCESS, ResultKind.PAUSED) && hasPostInstallFailure ->
            if (!hasInstallationFailure && pendingIds.isNotEmpty()) {
                ResultKind.CONFIRMATION_PENDING
            } else {
                ResultKind.PARTIAL_FAILURE
            }
        else -> declaredKind
    }
    val visibleFailureReason = rows.asSequence().mapNotNull { it.failureReason }.firstOrNull()
    val batchFailureApplies = batch == null || failure?.componentName == null ||
        rows.any { it.componentId == failure.componentName || it.componentName == failure.componentName }
    val failureReasonCode = visibleFailureReason
        ?: failure?.reasonCode?.takeIf { batchFailureApplies }
        // Keep hidden reusable prerequisites out of the page header. A
        // structural fallback is useful only when there is no visible row
        // that can carry the concrete outcome.
        ?: "installation_evidence_missing".takeIf { unaccountedIds.isNotEmpty() && rows.isEmpty() }
        ?: "success_evidence_incomplete".takeIf {
            declaredKind == ResultKind.SUCCESS && !successEvidence && pendingIds.isEmpty()
        }
    val failureStage = when {
        kind in setOf(ResultKind.SUCCESS, ResultKind.PAUSED) -> InstallationResultFailureStage.NONE
        hasPostInstallFailure && hasInstallationFailure -> InstallationResultFailureStage.MIXED
        hasPostInstallFailure -> InstallationResultFailureStage.POST_INSTALL
        else -> InstallationResultFailureStage.INSTALLATION
    }
    val desktopParticipates = batch?.let {
        AuthorizationPlanFactory.DESKTOP_COMPONENT_ID in it.resultComponentIds ||
            AuthorizationPlanFactory.DESKTOP_COMPONENT_ID in it.reusableComponentIds
    } ?: true
    val desktopReady = desktopParticipates &&
        AuthorizationPlanFactory.DESKTOP_COMPONENT_ID in evidence.available &&
        AuthorizationPlanFactory.DESKTOP_COMPONENT_ID !in pendingIds &&
        AuthorizationPlanFactory.DESKTOP_COMPONENT_ID !in failedIds
    return InstallationResultSummary(
        kind = kind,
        componentResults = rows.map { row -> row.copy(status = row.derivedStatus) },
        failureReasonCode = failureReasonCode,
        installationFlow = installationFlow,
        failureStage = failureStage,
        canContinue = kind != ResultKind.SUCCESS,
        canEnterMaintenance = kind in setOf(
            ResultKind.SUCCESS,
            ResultKind.PARTIAL_FAILURE,
            ResultKind.CONFIRMATION_PENDING,
        ) && desktopReady,
    )
}

/**
 * Defensive terminal proof used by the UI projection. The session normally
 * reaches SUCCEEDED only after this proof, but restored or externally-created
 * snapshots must not be allowed to display a false success.
 */
private fun hasConsistentSuccessEvidence(
    snapshot: InstallationSessionSnapshot,
    visibleIds: Set<String>,
): Boolean {
    val batch = snapshot.installationBatch
    val expectedIds = batch?.selectedComponentIds ?: visibleIds.ifEmpty {
        snapshot.components
            .filter { it.required || it.id in snapshot.selectedOptionalComponentIds }
            .mapTo(linkedSetOf()) { it.id }
    }
    if (expectedIds.isEmpty()) return false
    if (snapshot.failedComponentIds.any { it in expectedIds }) return false

    val evidence = snapshot.evidence
    val pendingIds = snapshot.confirmationPendingComponentIds() intersect expectedIds
    if (pendingIds.isNotEmpty()) return false
    if (!expectedIds.all { id ->
            id in evidence.installed && id in evidence.configured && id in evidence.available
        }
    ) {
        return false
    }
    if (evidence.writeConfirmed.any { it in expectedIds && it !in evidence.installed }) {
        return false
    }

    // Every non-reusable result component needs one corresponding row. A
    // reusable-only maintenance batch is valid with zero visible rows.
    val resultIds = batch?.resultComponentIds ?: visibleIds
    val rows = snapshot.componentResults.filter { it.componentId in resultIds }
    if (rows.mapNotNull { it.componentId }.toSet() != resultIds) return false
    if (rows.any { row ->
            !row.installed || !row.configured || !row.available ||
                row.failureReason != null ||
            row.derivedStatus != ComponentResultStatus.READY
        }
    ) {
        return false
    }

    // A trusted catalog, when present, must have current artifact proof for
    // every fresh component. A reusable maintenance prerequisite has no new
    // APK preparation event by design; its verified installed identity is the
    // equivalent proof and is already bound to this batch's live inventory.
    val reusableIds = (batch?.reusableComponentIds.orEmpty() intersect expectedIds)
    val artifactProofIds = evidence.artifactsVerified +
        (reusableIds intersect evidence.installed)
    if (snapshot.artifactManifests.isNotEmpty() &&
        !expectedIds.all { it in artifactProofIds }
    ) {
        return false
    }
    return true
}

/** Structured proof collected by the session before it can report success. */
data class SessionEvidence(
    val artifactsVerified: Set<String> = emptySet(),
    val artifactVerifications: Map<String, ArtifactVerification> = emptyMap(),
    val installed: Set<String> = emptySet(),
    /** Device-side PackageManager writes confirmed before identity proof. */
    val writeConfirmed: Set<String> = emptySet(),
    /** Device operation reached the readback boundary but identity is unknown. */
    val confirmationPending: Set<String> = emptySet(),
    val installation: Map<String, InstalledArtifactEvidence> = emptyMap(),
    val configured: Set<String> = emptySet(),
    val available: Set<String> = emptySet(),
    val authorizationActions: List<AuthorizationActionEvidence> = emptyList(),
    val availability: Map<String, DeviceAvailabilityEvidence> = emptyMap(),
    /** Non-fatal transfer housekeeping observations. */
    val installationWarnings: List<DeviceInstallWarning> = emptyList(),
)

/** Structured, non-sensitive status for the one maintenance action in flight. */
data class MaintenanceSnapshot(
    val activeAction: MaintenanceActionId? = null,
    /** Domain-owned secondary-page route; null means the maintenance home. */
    val routeAction: MaintenanceActionId? = null,
    val lastAction: MaintenanceActionRecord? = null,
    /** Explicit inventory state; an empty list is a valid loaded result. */
    val managedApplicationsState: MaintenanceInventoryState = MaintenanceInventoryState.NOT_STARTED,
    val managedApplicationsFailureReason: String? = null,
    val managedApplicationsFailureRetryable: Boolean = false,
    val managedApplications: List<ManagedApplicationStatus> = emptyList(),
    /** Full signed/configured component set used by maintenance installation. */
    val availableComponents: List<ComponentDescriptor> = emptyList(),
    /** Last verified APK identities for applications currently installed on the car. */
    val installedManifests: List<ArtifactManifest> = emptyList(),
    val availableManifests: List<ArtifactManifest> = emptyList(),
    val availableCatalogVersion: String? = null,
    val availableCatalogRevision: Long = 0L,
    val availableCatalogKeyId: String? = null,
    val availableCatalogSignatureAlgorithm: String? = null,
    /** True when the last catalog check loaded only signed control metadata. */
    val catalogControlPlaneOnly: Boolean = false,
    val applicationAction: MaintenanceApplicationActionRecord? = null,
    val applicationDetails: ManagedApplicationDetails? = null,
    val updateStatuses: List<MaintenanceUpdateStatus> = emptyList(),
    val diagnostic: MaintenanceDiagnosticSnapshot? = null,
    val authorization: MaintenanceAuthorizationSnapshot = MaintenanceAuthorizationSnapshot(),
    val installationSelection: MaintenanceInstallationSelection? = null,
)

enum class MaintenanceInventoryState {
    NOT_STARTED,
    LOADING,
    READY,
    FAILED,
}

data class MaintenanceActionRecord(
    val actionId: MaintenanceActionId,
    val status: MaintenanceActionStatus,
    val resultCode: String? = null,
    val reasonCode: String? = null,
    val retryable: Boolean = false,
)

data class ManagedApplicationStatus(
    val componentId: String,
    val packageName: String,
    val installed: Boolean,
    val versionLabel: String? = null,
    val versionCode: Long? = null,
    val fileSizeBytes: Long? = null,
    val installTimeEpochMillis: Long? = null,
    val updateTimeEpochMillis: Long? = null,
    val filePath: String? = null,
    val uid: Int? = null,
    val authorizationState: MaintenanceAuthorizationState? = null,
)

/** Atomically replaces verified identities without disturbing other installed baselines. */
internal fun mergeVerifiedInstalledManifests(
    existing: List<ArtifactManifest>,
    verified: List<ArtifactManifest>,
): List<ArtifactManifest> {
    val byComponent = linkedMapOf<String, ArtifactManifest>()
    existing.forEach { manifest -> byComponent[manifest.componentId] = manifest }
    verified.forEach { manifest -> byComponent[manifest.componentId] = manifest }
    return byComponent.values.toList()
}

/** Derives inventory rows from the same verified identities used by installation. */
internal fun mergeVerifiedManagedApplications(
    existing: List<ManagedApplicationStatus>,
    verified: List<ArtifactManifest>,
): List<ManagedApplicationStatus> {
    val byComponent = linkedMapOf<String, ManagedApplicationStatus>()
    existing.forEach { application -> byComponent[application.componentId] = application }
    verified.forEach { manifest ->
        val retained = byComponent[manifest.componentId]
        byComponent[manifest.componentId] = retained?.copy(
            packageName = manifest.packageName,
            installed = true,
            versionLabel = manifest.apkVersion.name.takeIf(String::isNotBlank),
            versionCode = manifest.apkVersion.code,
            fileSizeBytes = manifest.apkSizeBytes,
        ) ?: ManagedApplicationStatus(
            componentId = manifest.componentId,
            packageName = manifest.packageName,
            installed = true,
            versionLabel = manifest.apkVersion.name.takeIf(String::isNotBlank),
            versionCode = manifest.apkVersion.code,
            fileSizeBytes = manifest.apkSizeBytes,
        )
    }
    return byComponent.values.toList()
}

/** The one domain rule for promoting verified installation identities into maintenance facts. */
internal fun MaintenanceSnapshot.withVerifiedInstallations(
    verified: List<ArtifactManifest>,
): MaintenanceSnapshot {
    val mergedManifests = mergeVerifiedInstalledManifests(installedManifests, verified)
    return copy(
        installedManifests = mergedManifests,
        managedApplications = mergeVerifiedManagedApplications(managedApplications, verified),
        managedApplicationsState = if (
            managedApplicationsState == MaintenanceInventoryState.READY || mergedManifests.isNotEmpty()
        ) {
            MaintenanceInventoryState.READY
        } else {
            managedApplicationsState
        },
        managedApplicationsFailureReason = null,
        managedApplicationsFailureRetryable = false,
    )
}

/** Removes every page/action field that cannot survive process death independently. */
internal fun MaintenanceSnapshot.toDurableMaintenanceBaseline(): MaintenanceSnapshot {
    val normalized = withVerifiedInstallations(installedManifests)
    val verifiedIds = normalized.installedManifests.map { it.componentId }.toSet()
    val verifiedApplications = normalized.managedApplications.filter { application ->
        application.installed && application.componentId in verifiedIds
    }
    return MaintenanceSnapshot(
        managedApplicationsState = if (verifiedIds.isEmpty()) {
            MaintenanceInventoryState.NOT_STARTED
        } else {
            MaintenanceInventoryState.READY
        },
        managedApplications = verifiedApplications,
        availableComponents = normalized.availableComponents,
        installedManifests = normalized.installedManifests,
        availableManifests = normalized.availableManifests,
        availableCatalogVersion = normalized.availableCatalogVersion,
        availableCatalogRevision = normalized.availableCatalogRevision,
        availableCatalogKeyId = normalized.availableCatalogKeyId,
        availableCatalogSignatureAlgorithm = normalized.availableCatalogSignatureAlgorithm,
        catalogControlPlaneOnly = normalized.catalogControlPlaneOnly,
    )
}

data class MaintenanceAuthorizationSnapshot(
    val state: MaintenanceAuthorizationFlowState = MaintenanceAuthorizationFlowState.NOT_STARTED,
    val currentComponentId: String? = null,
    val applications: List<ManagedApplicationAuthorizationStatus> = emptyList(),
)

data class MaintenanceInstallationSelection(
    val actionId: MaintenanceActionId,
    val options: List<MaintenanceInstallationOption> = emptyList(),
    val selectedComponentIds: Set<String> = emptySet(),
)

data class MaintenanceInstallationOption(
    val componentId: String,
    val displayName: String,
    val versionLabel: String? = null,
    val sizeLabel: String? = null,
    val installed: Boolean = false,
    val required: Boolean = false,
    val iconKey: String = componentId,
)

enum class MaintenanceAuthorizationFlowState {
    NOT_STARTED,
    CHECKING,
    READY,
    REPAIRING,
    COMPLETED,
    FAILED,
}

/**
 * Static package metadata shown by the maintenance application-details page.
 * The values are read from the confirmed car connection and never contain
 * arbitrary command output.
 */
data class ManagedApplicationDetails(
    val componentId: String,
    val displayName: String,
    val packageName: String,
    val versionLabel: String? = null,
    val versionCode: Long? = null,
    val fileSizeBytes: Long? = null,
    val installTimeEpochMillis: Long? = null,
    val updateTimeEpochMillis: Long? = null,
    val filePath: String? = null,
    val uid: Int? = null,
)

data class MaintenanceApplicationActionRecord(
    val componentId: String,
    val actionId: MaintenanceApplicationActionId,
    val status: MaintenanceActionStatus,
    val resultCode: String? = null,
    val reasonCode: String? = null,
    val retryable: Boolean = false,
)

enum class MaintenanceUpdateState {
    CHECKING,
    CURRENT,
    UPDATE_AVAILABLE,
    NOT_INSTALLED,
    DEVICE_DISCONNECTED,
    UNAVAILABLE,
}

data class MaintenanceUpdateStatus(
    val componentId: String,
    val displayName: String,
    val versionLabel: String? = null,
    val installedVersionLabel: String? = null,
    val state: MaintenanceUpdateState = MaintenanceUpdateState.CHECKING,
    val isSelf: Boolean = false,
    val iconKey: String = componentId,
)

data class MaintenanceDiagnosticSnapshot(
    val state: DiagnosticLoadState = DiagnosticLoadState.NOT_STARTED,
    val fileName: String? = null,
    val sizeBytes: Long? = null,
    val modifiedEpochMillis: Long? = null,
)

enum class DiagnosticLoadState {
    NOT_STARTED,
    LOADING,
    READY,
    FAILED,
}

/** The last reliable point from which a paused installation can resume. */
data class SessionCheckpoint(
    val sessionId: Long,
    val state: InstallationSessionState,
    val deviceId: String?,
    val selectedOptionalComponentIds: Set<String>,
    val currentComponentName: String?,
    val progress: SessionProgress?,
    val componentProgress: Map<String, ComponentProgress> = emptyMap(),
    val failedComponentIds: Set<String> = emptySet(),
    val componentFailureRetryable: Map<String, Boolean> = emptyMap(),
    val evidence: SessionEvidence,
    val selectedSources: Map<String, ArtifactSourceKind> = emptyMap(),
    val archiveDownloads: Map<String, ArchiveDownloadEvidence> = emptyMap(),
    val archiveVerifications: Map<String, ArchiveVerificationEvidence> = emptyMap(),
    val apkExtractions: Map<String, ApkExtractionEvidence> = emptyMap(),
    val installationStrategy: InstallationStrategy = InstallationStrategy.INSTALL_MISSING_ONLY,
    val artifactCatalogStage: ArtifactCatalogStage = ArtifactCatalogStage.NOT_LOADED,
    val installationFlow: InstallationFlow = InstallationFlow.INITIAL_INSTALL,
    val installationBatch: InstallationBatchPlan? = null,
    val installationBatchReceipt: InstallationBatchReceipt? = null,
)
