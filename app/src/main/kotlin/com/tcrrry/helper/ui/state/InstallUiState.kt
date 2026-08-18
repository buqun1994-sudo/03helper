package com.tcrrry.helper.ui.state

import com.tcrrry.helper.domain.session.DeviceConnectionStatus
import com.tcrrry.helper.domain.session.InstallPhase
import com.tcrrry.helper.domain.session.MaintenanceActionId
import com.tcrrry.helper.domain.session.MaintenanceGroupId
import com.tcrrry.helper.domain.session.ResultKind

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
    ) : InstallUiState {
        override val screen: InstallScreen = InstallScreen.SELECTION
    }

    data class Installing(
        val deviceName: String,
        val currentComponentName: String?,
        val currentPhase: InstallPhase,
        val progress: UiProgress,
        val completedStages: Set<InstallPhase>,
        val canCancel: Boolean,
    ) : InstallUiState {
        override val screen: InstallScreen = InstallScreen.INSTALLING
    }

    data class Result(
        val kind: ResultKind,
        val componentResults: List<ComponentResultRow>,
        val canContinue: Boolean,
    ) : InstallUiState {
        override val screen: InstallScreen = InstallScreen.RESULT
    }

    data class Maintenance(
        val deviceName: String?,
        val connected: Boolean,
        val groups: List<MaintenanceGroupId> = listOf(
            MaintenanceGroupId.COMMON,
            MaintenanceGroupId.APPS,
            MaintenanceGroupId.STORAGE,
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
    FOUND,
    NOT_FOUND,
    FAILED,
}

enum class ConnectionAction {
    STOP,
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
    val compatibilityLabel: String?,
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
)

sealed interface InstallUiIntent {
    data object StopDiscovery : InstallUiIntent
    data object RetryDiscovery : InstallUiIntent
    data object Reconnect : InstallUiIntent
    data class SelectDevice(val deviceId: String) : InstallUiIntent
    data class ToggleOptionalComponent(val componentId: String, val selected: Boolean) : InstallUiIntent
    data object StartInstallation : InstallUiIntent
    data object CancelInstallation : InstallUiIntent
    data object ContinueInstallation : InstallUiIntent
    data object RetryInstallation : InstallUiIntent
    data object Reconfigure : InstallUiIntent
    data object EnterMaintenance : InstallUiIntent
    data class MaintenanceAction(val actionId: MaintenanceActionId) : InstallUiIntent
}
