package com.tcrrry.helper.domain.session

import com.tcrrry.helper.domain.artifact.ArchiveDownloadEvidence
import com.tcrrry.helper.domain.artifact.ArchiveVerificationEvidence
import com.tcrrry.helper.domain.artifact.ApkExtractionEvidence
import com.tcrrry.helper.domain.artifact.ArtifactManifest
import com.tcrrry.helper.domain.artifact.ArtifactSourceKind
import com.tcrrry.helper.domain.artifact.ArtifactVerification
import com.tcrrry.helper.domain.artifact.SourceFailureRecord
import com.tcrrry.helper.domain.device.DeviceCapability

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
)

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
    val configured: Set<String> = emptySet(),
    val available: Set<String> = emptySet(),
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
