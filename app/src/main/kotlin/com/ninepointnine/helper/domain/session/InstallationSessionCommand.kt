package com.ninepointnine.helper.domain.session

import com.ninepointnine.helper.domain.artifact.ArchiveDownloadEvidence
import com.ninepointnine.helper.domain.artifact.ArchiveVerificationEvidence
import com.ninepointnine.helper.domain.artifact.ApkExtractionEvidence
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ArtifactVerification
import com.ninepointnine.helper.domain.artifact.SourceSelectionEvidence
import com.ninepointnine.helper.domain.device.AuthorizationActionEvidence
import com.ninepointnine.helper.domain.device.DeviceAvailabilityEvidence
import com.ninepointnine.helper.domain.device.InstalledArtifactEvidence
import com.ninepointnine.helper.domain.device.ManagedApplicationAuthorizationStatus

/** Commands accepted by the single installation-session owner. */
sealed interface InstallationSessionCommand {
    data object StartDiscovery : InstallationSessionCommand
    data object StopDiscovery : InstallationSessionCommand
    data object CancelConnection : InstallationSessionCommand
    data class SelectDevice(val deviceId: String) : InstallationSessionCommand
    data class ToggleOptionalComponent(
        val componentId: String,
        val selected: Boolean,
    ) : InstallationSessionCommand

    data object StartInstallation : InstallationSessionCommand
    data object ConfirmSelection : InstallationSessionCommand
    data object BeginPipeline : InstallationSessionCommand
    data object CancelInstallation : InstallationSessionCommand
    data object ContinueInstallation : InstallationSessionCommand
    data object ResumeInstallation : InstallationSessionCommand
    data object RetryInstallation : InstallationSessionCommand
    /** Clears the failed attempt while retaining the connected device and choices. */
    data object ReturnToSelection : InstallationSessionCommand
    /** Returns a failed maintenance install to its own application-selection page. */
    data object ReturnToMaintenanceInstallationSelection : InstallationSessionCommand
    data object ReconfigureInstallation : InstallationSessionCommand
    /** Internal recovery command: restart from the saved selection after an uncertain write stage. */
    data object RestartFromCheckpoint : InstallationSessionCommand
    data object Reconnect : InstallationSessionCommand
    /** Internal fast path: retry the last verified device endpoint without a subnet scan. */
    data object ReconnectKnownDevice : InstallationSessionCommand
    data object DisconnectDevice : InstallationSessionCommand
    data object EnterMaintenance : InstallationSessionCommand
    /** Leaves a maintenance secondary page and clears any in-flight page action. */
    data object LeaveMaintenanceAction : InstallationSessionCommand
    data class MaintenanceAction(val actionId: MaintenanceActionId) : InstallationSessionCommand
    data class MaintenanceApplicationAction(
        val componentId: String,
        val actionId: MaintenanceApplicationActionId,
    ) : InstallationSessionCommand
    data class ToggleMaintenanceInstallationComponent(
        val componentId: String,
        val selected: Boolean,
    ) : InstallationSessionCommand
    data object StartMaintenanceInstallation : InstallationSessionCommand

    /** An event emitted by a discovery or installation adapter. */
    data class AdapterEvent(
        val event: InstallationSessionEvent,
        val sessionId: Long,
        val sequence: Long,
        val eventId: String = "$sessionId:$sequence",
    ) : InstallationSessionCommand
}

/** Structured adapter results. No event carries arbitrary shell text or UI state. */
sealed interface InstallationSessionEvent {
    data class DeviceDiscovered(val device: DeviceSummary) : InstallationSessionEvent

    data class DiscoverySnapshot(val devices: List<DeviceSummary>) : InstallationSessionEvent

    data class DiscoveryFinished(
        val scannedCount: Int,
        val confirmedCount: Int,
        val reasonCode: String? = null,
    ) : InstallationSessionEvent

    data class DeviceConnectionConfirmed(val device: DeviceSummary) : InstallationSessionEvent

    data class DeviceConnectionFailed(
        val deviceId: String,
        val reasonCode: String,
        val retryable: Boolean = true,
    ) : InstallationSessionEvent

    data class CatalogResolved(
        val catalogVersion: String,
        val keyId: String,
        val signatureAlgorithm: String,
        val manifests: List<ArtifactManifest>,
        val catalogRevision: Long = 0L,
        val apps: List<ComponentDescriptor> = emptyList(),
        val appFailures: Map<String, String> = emptyMap(),
    ) : InstallationSessionEvent

    /** Folder-based distribution exposes component choices before APK metadata is known. */
    data class DistributionConfigResolved(
        val configVersion: String,
        val keyId: String,
        val signatureAlgorithm: String,
        val components: List<ComponentDescriptor>,
        val appFailures: Map<String, String> = emptyMap(),
        val catalogRevision: Long = 0L,
    ) : InstallationSessionEvent

    /** The selected applications' manifests are ready after the user confirms the selection. */
    data class SelectedCatalogResolved(
        val catalogVersion: String,
        val keyId: String,
        val signatureAlgorithm: String,
        val manifests: List<ArtifactManifest>,
        val catalogRevision: Long = 0L,
        val apps: List<ComponentDescriptor> = emptyList(),
        val appFailures: Map<String, String> = emptyMap(),
    ) : InstallationSessionEvent

    data class CatalogFailed(val reasonCode: String) : InstallationSessionEvent

    data class SourceResolved(
        val sourceId: String,
        val componentId: String? = null,
        val sourceKind: ArtifactSourceKind? = null,
        val selections: List<SourceSelectionEvidence> = emptyList(),
    ) : InstallationSessionEvent

    data class SourceFailed(
        val componentId: String,
        val sourceKind: ArtifactSourceKind,
        val reasonCode: String,
        val retryable: Boolean = true,
        val terminal: Boolean = false,
    ) : InstallationSessionEvent

    data class ArchiveDownloaded(
        val sizeBytes: Long,
        val sha256: String,
        val componentId: String? = null,
        val resumed: Boolean = false,
        val archives: List<ArchiveDownloadEvidence> = emptyList(),
    ) : InstallationSessionEvent

    data class ArchiveVerified(
        val verified: Boolean,
        val componentId: String? = null,
        val verification: ArchiveVerificationEvidence? = null,
        val verifications: List<ArchiveVerificationEvidence> = emptyList(),
    ) : InstallationSessionEvent

    data class ApkExtracted(
        val entryName: String,
        val sizeBytes: Long,
        val sha256: String,
        val componentId: String? = null,
        val extractions: List<ApkExtractionEvidence> = emptyList(),
    ) : InstallationSessionEvent

    data class ArtifactsVerified(
        val checks: List<ComponentCheck>,
        val verifications: List<ArtifactVerification> = emptyList(),
        val archiveDeleted: Boolean = false,
    ) : InstallationSessionEvent

    data class ArtifactUnavailable(
        val componentId: String,
        val reasonCode: String,
        val sourceKind: ArtifactSourceKind? = null,
    ) : InstallationSessionEvent

    /** A real per-application transfer or install state update. */
    data class ComponentProgressUpdated(
        val componentId: String,
        val phase: InstallPhase,
        val status: ComponentProgressStatus,
        val bytesWritten: Long = 0L,
        val totalBytes: Long = 0L,
        val fraction: Float? = null,
        val indeterminate: Boolean = true,
    ) : InstallationSessionEvent

    /** One application failed; the batch must continue with the remaining apps. */
    data class ComponentFailed(
        val componentId: String,
        val phase: InstallPhase,
        val reasonCode: String,
        val retryable: Boolean = false,
    ) : InstallationSessionEvent

    data class InstallationStarted(val componentIds: List<String> = emptyList()) : InstallationSessionEvent

    data class InstallationCompleted(
        val checks: List<ComponentCheck>,
        val evidence: List<InstalledArtifactEvidence> = emptyList(),
    ) : InstallationSessionEvent

    data class AuthorizationCompleted(
        val checks: List<ComponentCheck>,
        val evidence: List<AuthorizationActionEvidence> = emptyList(),
    ) : InstallationSessionEvent

    data class DeviceVerified(
        val checks: List<ComponentCheck>,
        val evidence: List<DeviceAvailabilityEvidence> = emptyList(),
    ) : InstallationSessionEvent

    data class DeviceDisconnected(
        val deviceId: String? = null,
        val reasonCode: String = "device_disconnected",
    ) : InstallationSessionEvent

    data class DeviceReconnected(val device: DeviceSummary) : InstallationSessionEvent

    data class MaintenanceActionCompleted(
        val actionId: MaintenanceActionId,
        val resultCode: String = "completed",
    ) : InstallationSessionEvent

    data class MaintenanceActionFailed(
        val actionId: MaintenanceActionId,
        val reasonCode: String,
        val retryable: Boolean = true,
    ) : InstallationSessionEvent

    data class MaintenanceApplicationsResolved(
        val applications: List<ManagedApplicationStatus>,
    ) : InstallationSessionEvent

    data class MaintenanceApplicationActionCompleted(
        val componentId: String,
        val actionId: MaintenanceApplicationActionId,
        val resultCode: String = "completed",
        /** Fresh car inventory read after a destructive application action. */
        val refreshedApplications: List<ManagedApplicationStatus>? = null,
        /** Set when the action succeeded but the follow-up inventory read did not. */
        val inventoryRefreshFailureReason: String? = null,
        val inventoryRefreshRetryable: Boolean = true,
    ) : InstallationSessionEvent

    data class MaintenanceApplicationActionFailed(
        val componentId: String,
        val actionId: MaintenanceApplicationActionId,
        val reasonCode: String,
        val retryable: Boolean = true,
    ) : InstallationSessionEvent

    data class MaintenanceApplicationDetailsResolved(
        val details: ManagedApplicationDetails,
    ) : InstallationSessionEvent

    data class MaintenanceAuthorizationCheckStarted(
        val componentIds: List<String>,
    ) : InstallationSessionEvent

    data class MaintenanceAuthorizationCheckProgress(
        val componentId: String,
        val status: ManagedApplicationAuthorizationStatus,
    ) : InstallationSessionEvent

    data class MaintenanceAuthorizationChecked(
        val applications: List<ManagedApplicationAuthorizationStatus>,
    ) : InstallationSessionEvent

    data class MaintenanceCatalogRefreshed(
        val catalogVersion: String,
        val keyId: String,
        val signatureAlgorithm: String,
        val manifests: List<ArtifactManifest>,
        val apps: List<ComponentDescriptor> = emptyList(),
        val appFailures: Map<String, String> = emptyMap(),
        val catalogRevision: Long = 0L,
        val updateStatuses: List<MaintenanceUpdateStatus> = emptyList(),
        val controlPlaneOnly: Boolean = false,
    ) : InstallationSessionEvent

    data class RecoverableError(
        val category: FailureCategory,
        val componentName: String? = null,
        val reasonCode: String = "recoverable_error",
    ) : InstallationSessionEvent

    data class FatalError(
        val category: FailureCategory,
        val componentName: String? = null,
        val reasonCode: String = "fatal_error",
    ) : InstallationSessionEvent

    data object Unknown : InstallationSessionEvent
}

/** A passed/failed proof for one selected component. */
data class ComponentCheck(
    val componentId: String,
    val passed: Boolean,
)
