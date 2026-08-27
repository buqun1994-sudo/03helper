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
    /** Phone-local durability of the projected maintenance baseline; never an install result. */
    val maintenanceBaselinePersistence: MaintenanceBaselinePersistence = MaintenanceBaselinePersistence(),
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
    /** Stable stage semantics used by both initial and maintenance result views. */
    val status: ComponentResultStatus = when {
        writeConfirmed && !installed -> ComponentResultStatus.WRITE_CONFIRMED_IDENTITY_UNVERIFIED
        !installed -> ComponentResultStatus.NOT_INSTALLED
        !configured -> ComponentResultStatus.AUTHORIZATION_INCOMPLETE
        !available -> ComponentResultStatus.AVAILABILITY_INCOMPLETE
        else -> ComponentResultStatus.READY
    },
)

enum class ComponentResultStatus {
    NOT_INSTALLED,
    WRITE_CONFIRMED_IDENTITY_UNVERIFIED,
    AUTHORIZATION_INCOMPLETE,
    AVAILABILITY_INCOMPLETE,
    READY,
}

/** Structured result of saving the durable maintenance baseline on this phone. */
data class MaintenanceBaselinePersistence(
    val status: MaintenanceBaselinePersistenceStatus = MaintenanceBaselinePersistenceStatus.NOT_ATTEMPTED,
    /** Runtime-local correlation id used to reject a late result from an older baseline. */
    val attemptId: Long = 0L,
    val reasonCode: String? = null,
)

enum class MaintenanceBaselinePersistenceStatus {
    NOT_ATTEMPTED,
    SAVING,
    SAVED,
    FAILED,
}

/** Structured proof collected by the session before it can report success. */
data class SessionEvidence(
    val artifactsVerified: Set<String> = emptySet(),
    val artifactVerifications: Map<String, ArtifactVerification> = emptyMap(),
    val installed: Set<String> = emptySet(),
    /** Device-side PackageManager writes confirmed before identity proof. */
    val writeConfirmed: Set<String> = emptySet(),
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
    val inventoryState = when {
        normalized.managedApplicationsState == MaintenanceInventoryState.READY -> MaintenanceInventoryState.READY
        normalized.managedApplications.any { it.installed } -> MaintenanceInventoryState.READY
        else -> MaintenanceInventoryState.NOT_STARTED
    }
    return MaintenanceSnapshot(
        managedApplicationsState = inventoryState,
        managedApplications = normalized.managedApplications,
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
)
