package com.tcrrry.helper.domain.session

import com.tcrrry.helper.domain.artifact.ArchiveDownloadEvidence
import com.tcrrry.helper.domain.artifact.ArchiveVerificationEvidence
import com.tcrrry.helper.domain.artifact.ApkExtractionEvidence
import com.tcrrry.helper.domain.artifact.ArtifactManifest
import com.tcrrry.helper.domain.artifact.ArtifactSourceKind
import com.tcrrry.helper.domain.artifact.ArtifactVerification
import com.tcrrry.helper.domain.artifact.SourceSelectionEvidence

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
    data object ReconfigureInstallation : InstallationSessionCommand
    data object Reconnect : InstallationSessionCommand
    data object DisconnectDevice : InstallationSessionCommand
    data object EnterMaintenance : InstallationSessionCommand
    data class MaintenanceAction(val actionId: MaintenanceActionId) : InstallationSessionCommand

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

    data class InstallationStarted(val componentIds: List<String> = emptyList()) : InstallationSessionEvent

    data class InstallationCompleted(val checks: List<ComponentCheck>) : InstallationSessionEvent

    data class AuthorizationCompleted(val checks: List<ComponentCheck>) : InstallationSessionEvent

    data class DeviceVerified(val checks: List<ComponentCheck>) : InstallationSessionEvent

    data class DeviceDisconnected(
        val deviceId: String? = null,
        val reasonCode: String = "device_disconnected",
    ) : InstallationSessionEvent

    data class DeviceReconnected(val device: DeviceSummary) : InstallationSessionEvent

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
