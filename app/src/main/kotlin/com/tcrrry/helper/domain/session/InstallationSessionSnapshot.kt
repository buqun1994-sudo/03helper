package com.tcrrry.helper.domain.session

import com.tcrrry.helper.domain.artifact.ArchiveDownloadEvidence
import com.tcrrry.helper.domain.artifact.ArchiveVerificationEvidence
import com.tcrrry.helper.domain.artifact.ApkExtractionEvidence
import com.tcrrry.helper.domain.artifact.ArtifactManifest
import com.tcrrry.helper.domain.artifact.ArtifactSourceKind
import com.tcrrry.helper.domain.artifact.ArtifactVerification
import com.tcrrry.helper.domain.artifact.SourceFailureRecord
import com.tcrrry.helper.domain.device.DeviceCapability
import com.tcrrry.helper.domain.device.AuthorizationActionEvidence
import com.tcrrry.helper.domain.device.DeviceAvailabilityEvidence
import com.tcrrry.helper.domain.device.InstalledArtifactEvidence

data class InstallationSessionSnapshot(
    val state: InstallationSessionState,
    val device: DeviceSummary? = null,
    val discoveredDevices: List<DeviceSummary> = emptyList(),
    val components: List<ComponentDescriptor> = emptyList(),
    val selectedOptionalComponentIds: Set<String> = emptySet(),
    val currentComponentName: String? = null,
    val progress: SessionProgress? = null,
    val failure: SessionFailure? = null,
    val componentResults: List<ComponentResult> = emptyList(),
    val sessionId: Long = 0L,
    val revision: Long = 0L,
    val lastEventSequence: Long = 0L,
    val checkpoint: SessionCheckpoint? = null,
    val evidence: SessionEvidence = SessionEvidence(),
    val artifactManifests: List<ArtifactManifest> = emptyList(),
    val catalogVersion: String? = null,
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
)

enum class ComponentCompatibility {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

data class SessionProgress(
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val fraction: Float? = null,
    val indeterminate: Boolean = false,
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
    val managedApplications: List<ManagedApplicationStatus> = emptyList(),
    val availableManifests: List<ArtifactManifest> = emptyList(),
    val availableCatalogVersion: String? = null,
    val availableCatalogKeyId: String? = null,
    val availableCatalogSignatureAlgorithm: String? = null,
)

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
)

/** The last reliable point from which a paused installation can resume. */
data class SessionCheckpoint(
    val sessionId: Long,
    val state: InstallationSessionState,
    val deviceId: String?,
    val selectedOptionalComponentIds: Set<String>,
    val currentComponentName: String?,
    val progress: SessionProgress?,
    val evidence: SessionEvidence,
    val selectedSources: Map<String, ArtifactSourceKind> = emptyMap(),
    val archiveDownloads: Map<String, ArchiveDownloadEvidence> = emptyMap(),
    val archiveVerifications: Map<String, ArchiveVerificationEvidence> = emptyMap(),
    val apkExtractions: Map<String, ApkExtractionEvidence> = emptyMap(),
)
