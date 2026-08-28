package com.ninepointnine.helper.ui.state

import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.session.ComponentCompatibility
import com.ninepointnine.helper.domain.session.InstallPhase
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.domain.session.MaintenanceActionStatus
import com.ninepointnine.helper.domain.session.MaintenanceApplicationActionId
import com.ninepointnine.helper.domain.session.MaintenanceGroupId
import com.ninepointnine.helper.domain.session.ResultKind
import com.ninepointnine.helper.domain.session.MaintenanceUpdateState
import com.ninepointnine.helper.domain.session.MaintenanceAuthorizationFlowState
import com.ninepointnine.helper.domain.session.MaintenanceInventoryState
import com.ninepointnine.helper.domain.device.MaintenanceAuthorizationState
import com.ninepointnine.helper.domain.session.InstallationFlow
import com.ninepointnine.helper.domain.session.InstallationResultFailureStage

sealed interface InstallUiState {
    val screen: InstallScreen

    data class Connection(
        val variant: ConnectionVariant,
        val devices: List<DeviceRow>,
        val primaryAction: ConnectionAction,
    ) : InstallUiState {
        override val screen: InstallScreen = InstallScreen.CONNECTION
    }

    data class Selection(
        val deviceName: String,
        val components: List<ComponentRow>,
        val summaryCount: Int,
        val summarySizeLabel: String,
        val canStart: Boolean,
        /** True while the connected session is still building the remote catalog. */
        val preparing: Boolean = false,
        /** User-facing reason when the connected catalog or preparation failed. */
        val failureReason: String? = null,
    ) : InstallUiState {
        override val screen: InstallScreen = InstallScreen.SELECTION
    }

    data class Installing(
        val deviceName: String,
        val currentComponentName: String?,
        val currentPhase: InstallPhase,
        val progress: UiProgress,
        val completedStages: Set<InstallPhase>,
        /** Business flow owning this installation page; never inferred by UI route state. */
        val installationFlow: InstallationFlow = InstallationFlow.INITIAL_INSTALL,
    ) : InstallUiState {
        override val screen: InstallScreen = InstallScreen.INSTALLING
    }

    data class Result(
        val kind: ResultKind,
        val componentResults: List<ComponentResultRow>,
        val canContinue: Boolean,
        val canEnterMaintenance: Boolean = false,
        /** Batch-level concrete reason, including failures of hidden prerequisites. */
        val failureReason: String? = null,
        /** Business flow owning this result page; never inferred by UI route state. */
        val installationFlow: InstallationFlow = InstallationFlow.INITIAL_INSTALL,
        /** Whether a failed result stopped before or after the APK write. */
        val failureStage: InstallationResultFailureStage = InstallationResultFailureStage.NONE,
    ) : InstallUiState {
        override val screen: InstallScreen = InstallScreen.RESULT
    }

    data class Maintenance(
        val deviceName: String?,
        val connected: Boolean,
        val reconnecting: Boolean = false,
        /** Secondary maintenance page selected by the domain session. */
        val routeAction: MaintenanceActionId? = null,
        val feedback: MaintenanceFeedback? = null,
        val applications: List<MaintenanceApplicationRow> = emptyList(),
        val applicationsState: MaintenanceInventoryState = MaintenanceInventoryState.NOT_STARTED,
        val applicationsErrorReason: String? = null,
        val applicationsErrorRetryable: Boolean = false,
        val updateStatuses: List<MaintenanceUpdateRow> = emptyList(),
        val authorization: MaintenanceAuthorizationUi = MaintenanceAuthorizationUi(),
        val applicationAction: MaintenanceApplicationFeedback? = null,
        val applicationDetails: MaintenanceApplicationDetailsRow? = null,
        val installationSelection: MaintenanceInstallationSelectionUi? = null,
        val groups: List<MaintenanceGroupId> = listOf(
            MaintenanceGroupId.COMMON,
            MaintenanceGroupId.APPS,
        ),
    ) : InstallUiState {
        override val screen: InstallScreen = InstallScreen.MAINTENANCE
    }
}

enum class InstallScreen {
    CONNECTION,
    SELECTION,
    INSTALLING,
    RESULT,
    MAINTENANCE,
}

enum class ConnectionVariant {
    SEARCHING,
    CONNECTING,
    FOUND,
    NOT_FOUND,
    FAILED,
}

enum class ConnectionAction {
    STOP,
    CANCEL_CONNECTION,
    RETRY,
    RECONNECT,
}

data class DeviceRow(
    val id: String,
    val displayName: String,
    val status: DeviceConnectionStatus,
    val lastConfirmedLabel: String?,
)

data class ComponentRow(
    val id: String,
    val displayName: String,
    val required: Boolean,
    val selected: Boolean,
    val versionLabel: String?,
    val sizeLabel: String?,
    /** Retained for state/test compatibility; never rendered as raw Android API text. */
    val compatibilityLabel: String?,
    val compatibilityState: ComponentCompatibility,
    val iconKey: String,
    val description: String = "",
    val status: com.ninepointnine.helper.domain.session.ComponentStatus =
        com.ninepointnine.helper.domain.session.ComponentStatus.READING,
    val errorReason: String? = null,
)

data class UiProgress(
    val completedCount: Int,
    val totalCount: Int,
    val fraction: Float?,
    val indeterminate: Boolean,
)

data class ComponentResultRow(
    val componentName: String,
    val installed: Boolean,
    val configured: Boolean,
    val available: Boolean,
    val errorReason: String? = null,
    val status: com.ninepointnine.helper.domain.session.ComponentResultStatus =
        com.ninepointnine.helper.domain.session.ComponentResultStatus.NOT_INSTALLED,
)

/** Compatibility name for UI call sites; semantics are owned by the domain. */
typealias ResultFailureStage = InstallationResultFailureStage

data class MaintenanceFeedback(
    val actionId: MaintenanceActionId,
    val status: MaintenanceActionStatus,
    val resultCode: String? = null,
    val reasonCode: String? = null,
    val retryable: Boolean = false,
    /** Concrete explanation retained for application-install failures. */
    val message: String? = null,
)

data class MaintenanceApplicationRow(
    val componentId: String,
    val displayName: String,
    val packageName: String = "",
    val installed: Boolean,
    val versionLabel: String? = null,
    val versionCode: Long? = null,
    val fileSizeBytes: Long? = null,
    val installTimeEpochMillis: Long? = null,
    val updateTimeEpochMillis: Long? = null,
    val filePath: String? = null,
    val uid: Int? = null,
    val iconKey: String = componentId,
)

data class MaintenanceUpdateRow(
    val componentId: String,
    val displayName: String,
    val versionLabel: String?,
    val installedVersionLabel: String?,
    val state: MaintenanceUpdateState,
    val isSelf: Boolean,
    val iconKey: String,
)

data class MaintenanceAuthorizationUi(
    val state: MaintenanceAuthorizationFlowState = MaintenanceAuthorizationFlowState.NOT_STARTED,
    val currentComponentId: String? = null,
    val applications: List<MaintenanceAuthorizationRow> = emptyList(),
)

data class MaintenanceAuthorizationRow(
    val componentId: String,
    val packageName: String,
    val versionLabel: String? = null,
    val state: MaintenanceAuthorizationState,
    val authorized: Boolean?,
    val reasonCode: String? = null,
)

data class MaintenanceApplicationFeedback(
    val componentId: String,
    val actionId: MaintenanceApplicationActionId,
    val status: MaintenanceActionStatus,
    val resultCode: String? = null,
    val reasonCode: String? = null,
    val retryable: Boolean = false,
)

data class MaintenanceApplicationDetailsRow(
    val componentId: String,
    val displayName: String,
    val packageName: String,
    val versionLabel: String?,
    val versionCode: Long?,
    val fileSizeBytes: Long?,
    val installTimeEpochMillis: Long?,
    val updateTimeEpochMillis: Long?,
    val filePath: String?,
    val uid: Int?,
)

data class MaintenanceInstallationSelectionUi(
    val actionId: MaintenanceActionId,
    val options: List<MaintenanceInstallationOptionRow>,
    val selectedComponentIds: Set<String>,
    val feedback: MaintenanceFeedback? = null,
)

data class MaintenanceInstallationOptionRow(
    val componentId: String,
    val displayName: String,
    val versionLabel: String?,
    val sizeLabel: String?,
    val installed: Boolean,
    val required: Boolean,
    val iconKey: String,
)

sealed interface InstallUiIntent {
    data object StopDiscovery : InstallUiIntent
    data object CancelConnection : InstallUiIntent
    data object RetryDiscovery : InstallUiIntent
    data object Reconnect : InstallUiIntent
    data object DisconnectDevice : InstallUiIntent
    data class SelectDevice(val deviceId: String) : InstallUiIntent
    data class ToggleOptionalComponent(val componentId: String, val selected: Boolean) : InstallUiIntent
    data object StartInstallation : InstallUiIntent
    data object CancelInstallation : InstallUiIntent
    data object ContinueInstallation : InstallUiIntent
    data object RetryInstallation : InstallUiIntent
    /** Leaves a terminal install result and returns to the existing selection. */
    data object ReturnToSelection : InstallUiIntent
    /** Leaves a failed maintenance install and returns to its application-selection page. */
    data object ReturnToMaintenanceInstallationSelection : InstallUiIntent
    data object Reconfigure : InstallUiIntent
    data object EnterMaintenance : InstallUiIntent
    data object LeaveMaintenanceAction : InstallUiIntent
    data class MaintenanceAction(val actionId: MaintenanceActionId) : InstallUiIntent
    data class MaintenanceApplicationAction(
        val componentId: String,
        val actionId: MaintenanceApplicationActionId,
    ) : InstallUiIntent
    data class ToggleMaintenanceInstallationComponent(
        val componentId: String,
        val selected: Boolean,
    ) : InstallUiIntent
    data object StartMaintenanceInstallation : InstallUiIntent
}
