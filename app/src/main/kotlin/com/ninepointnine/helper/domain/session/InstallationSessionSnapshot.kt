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
import com.ninepointnine.helper.domain.device.MaintenanceAuthorizationState
import com.ninepointnine.helper.domain.device.ManagedApplicationAuthorizationStatus

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
    val failure: SessionFailure? = null,
    val componentResults: List<ComponentResult> = emptyList(),
    val sessionId: Long = 0L,
    val revision: Long = 0L,
    val lastEventSequence: Long = 0L,
    val checkpoint: SessionCheckpoint? = null,
    val evidence: SessionEvidence = SessionEvidence(),
    val artifactManifests: List<ArtifactManifest> = emptyList(),
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
)

/** Structured proof collected by the session before it can report success. */
data class SessionEvidence(
    val artifactsVerified: Set<String> = emptySet(),
    val artifactVerifications: Map<String, ArtifactVerification> = emptyMap(),
    val installed: Set<String> = emptySet(),
    val installation: Map<String, InstalledArtifactEvidence> = emptyMap(),
    val configured: Set<String> = emptySet(),
    val available: Set<String> = emptySet(),
    val authorizationActions: List<AuthorizationActionEvidence> = emptyList(),
    val availability: Map<String, DeviceAvailabilityEvidence> = emptyMap(),
)

/** Structured, non-sensitive status for the one maintenance action in flight. */
data class MaintenanceSnapshot(
    val activeAction: MaintenanceActionId? = null,
    val lastAction: MaintenanceActionRecord? = null,
    /** Explicit inventory state; an empty list is a valid loaded result. */
    val managedApplicationsState: MaintenanceInventoryState = MaintenanceInventoryState.NOT_STARTED,
    val managedApplicationsFailureReason: String? = null,
    val managedApplicationsFailureRetryable: Boolean = false,
    val managedApplications: List<ManagedApplicationStatus> = emptyList(),
    /** Full signed/configured component set used by maintenance installation. */
    val availableComponents: List<ComponentDescriptor> = emptyList(),
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
    val evidence: SessionEvidence,
    val selectedSources: Map<String, ArtifactSourceKind> = emptyMap(),
    val archiveDownloads: Map<String, ArchiveDownloadEvidence> = emptyMap(),
    val archiveVerifications: Map<String, ArchiveVerificationEvidence> = emptyMap(),
    val apkExtractions: Map<String, ApkExtractionEvidence> = emptyMap(),
)
