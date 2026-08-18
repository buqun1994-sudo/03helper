package com.tcrrry.helper.domain.session

/** Commands accepted by the single installation-session owner. */
sealed interface InstallationSessionCommand {
    data object StartDiscovery : InstallationSessionCommand
    data object StopDiscovery : InstallationSessionCommand
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

    data class SourceResolved(val sourceId: String) : InstallationSessionEvent

    data class ArchiveDownloaded(
        val sizeBytes: Long,
        val sha256: String,
    ) : InstallationSessionEvent

    data class ArchiveVerified(val verified: Boolean) : InstallationSessionEvent

    data class ApkExtracted(
        val entryName: String,
        val sizeBytes: Long,
        val sha256: String,
    ) : InstallationSessionEvent

    data class ArtifactsVerified(val checks: List<ComponentCheck>) : InstallationSessionEvent

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
