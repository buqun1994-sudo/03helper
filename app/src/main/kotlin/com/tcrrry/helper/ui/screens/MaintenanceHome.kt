package com.tcrrry.helper.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tcrrry.helper.R
import com.tcrrry.helper.domain.session.MaintenanceActionId
import com.tcrrry.helper.domain.session.MaintenanceGroupId
import com.tcrrry.helper.domain.session.MaintenanceActionStatus
import com.tcrrry.helper.domain.session.requiresConnectedDevice
import com.tcrrry.helper.ui.components.AnimatedEntry
import com.tcrrry.helper.ui.components.DividerLine
import com.tcrrry.helper.ui.components.PressableSurface
import com.tcrrry.helper.ui.components.StatusIcon
import com.tcrrry.helper.ui.components.TaskTopBar
import com.tcrrry.helper.ui.state.InstallUiIntent
import com.tcrrry.helper.ui.state.InstallUiState
import com.tcrrry.helper.ui.state.MaintenanceApplicationRow
import com.tcrrry.helper.ui.state.MaintenanceFeedback
import com.tcrrry.helper.ui.theme.InstallerColors
import com.tcrrry.helper.ui.theme.InstallerDimensions
import com.tcrrry.helper.ui.theme.InstallerMotion

@Composable
fun MaintenanceHome(
    state: InstallUiState.Maintenance,
    onIntent: (InstallUiIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val columns = maintenanceColumnCount(maxWidth.value)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = InstallerDimensions.PageHorizontalPadding),
        ) {
            TaskTopBar(title = stringResource(R.string.task_maintenance))
            DeviceStatusHeader(
                state = state,
                onReconnect = { onIntent(InstallUiIntent.Reconnect) },
                onDisconnect = { onIntent(InstallUiIntent.DisconnectDevice) },
            )
            AnimatedVisibility(
                visible = state.feedback != null,
                enter = fadeIn(InstallerMotion.stateChange()),
                exit = fadeOut(InstallerMotion.stateChange()),
            ) {
                state.feedback?.let { feedback ->
                    Column {
                        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
                        Crossfade(
                            targetState = feedback,
                            animationSpec = InstallerMotion.stateChange(),
                            label = "maintenanceFeedback",
                        ) { target ->
                            MaintenanceFeedbackBlock(target, state.applications)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(InstallerDimensions.SectionVerticalSpacing))
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .testTag("maintenance_groups"),
                verticalArrangement = Arrangement.spacedBy(InstallerDimensions.SectionVerticalSpacing),
            ) {
                items(state.groups, key = { it }) { group ->
                    MaintenanceGroup(
                        group = group,
                        columns = columns,
                        connected = state.connected,
                        busy = state.feedback?.status == MaintenanceActionStatus.RUNNING,
                        onAction = { action -> onIntent(InstallUiIntent.MaintenanceAction(action)) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
        }
    }
}

@Composable
private fun DeviceStatusHeader(
    state: InstallUiState.Maintenance,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val busy = state.feedback?.status == MaintenanceActionStatus.RUNNING
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusIcon(
            name = if (state.connected) "circle_check" else "circle_alert",
            contentDescription = if (state.connected) state.deviceName ?: stringResource(R.string.device_not_connected) else stringResource(R.string.maintenance_disconnected),
            tint = if (state.connected) InstallerColors.Success else InstallerColors.Warning,
            size = InstallerDimensions.SmallIconSize,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = state.deviceName ?: stringResource(R.string.maintenance_disconnected),
                style = MaterialTheme.typography.bodyLarge,
                color = InstallerColors.White,
            )
            if (!state.connected) {
                Text(text = stringResource(R.string.maintenance_disconnected), style = MaterialTheme.typography.bodySmall, color = InstallerColors.AuxiliaryWhite)
            }
        }
        if (state.reconnecting) {
            PressableSurface(
                onClick = {},
                enabled = false,
                modifier = Modifier.width(112.dp),
                minHeight = 48.dp,
                containerColor = InstallerColors.PageBlue,
                pressedColor = InstallerColors.PageBlue,
                borderColor = InstallerColors.White,
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Text(text = stringResource(R.string.maintenance_reconnecting), style = MaterialTheme.typography.bodySmall, color = InstallerColors.White)
                }
            }
        } else if (state.connected) {
            PressableSurface(
                onClick = onDisconnect,
                enabled = !busy,
                modifier = Modifier.width(112.dp),
                minHeight = 48.dp,
                containerColor = InstallerColors.PageBlue,
                pressedColor = InstallerColors.PressedBlue,
                borderColor = InstallerColors.White,
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Text(text = stringResource(R.string.maintenance_disconnect), style = MaterialTheme.typography.bodySmall, color = InstallerColors.White)
                }
            }
        } else {
            PressableSurface(
                onClick = onReconnect,
                enabled = !busy,
                modifier = Modifier.width(112.dp),
                minHeight = 48.dp,
                containerColor = InstallerColors.White,
                pressedColor = InstallerColors.AuxiliaryWhite,
                borderColor = InstallerColors.White,
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Text(text = stringResource(R.string.maintenance_reconnect), style = MaterialTheme.typography.bodySmall, color = InstallerColors.PressedBlue)
                }
            }
        }
    }
}

@Composable
private fun MaintenanceFeedbackBlock(
    feedback: MaintenanceFeedback,
    applications: List<MaintenanceApplicationRow>,
) {
    val icon = when (feedback.status) {
        MaintenanceActionStatus.RUNNING -> "loader_circle"
        MaintenanceActionStatus.SUCCEEDED -> "circle_check"
        MaintenanceActionStatus.FAILED -> "triangle_alert"
    }
    val tint = when (feedback.status) {
        MaintenanceActionStatus.RUNNING -> InstallerColors.White
        MaintenanceActionStatus.SUCCEEDED -> InstallerColors.Success
        MaintenanceActionStatus.FAILED -> InstallerColors.Error
    }
    val title = when (feedback.status) {
        MaintenanceActionStatus.RUNNING -> stringResource(
            R.string.maintenance_action_running,
            stringResource(actionTitle(feedback.actionId)),
        )

        MaintenanceActionStatus.SUCCEEDED -> stringResource(
            R.string.maintenance_action_completed,
            stringResource(actionTitle(feedback.actionId)),
        )

        MaintenanceActionStatus.FAILED -> stringResource(
            R.string.maintenance_action_failed,
            stringResource(actionTitle(feedback.actionId)),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusIcon(name = icon, contentDescription = title, tint = tint)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, color = InstallerColors.White)
                maintenanceFeedbackDescription(feedback)?.let { description ->
                    Text(
                        text = stringResource(description),
                        style = MaterialTheme.typography.bodySmall,
                        color = InstallerColors.AuxiliaryWhite,
                    )
                }
            }
        }
        if (
            feedback.actionId == MaintenanceActionId.MANAGE_APPS &&
            feedback.status == MaintenanceActionStatus.SUCCEEDED &&
            applications.isNotEmpty()
        ) {
            Text(
                text = stringResource(R.string.maintenance_installed_apps),
                style = MaterialTheme.typography.bodyMedium,
                color = InstallerColors.AuxiliaryWhite,
            )
            applications.forEach { application ->
                DividerLine()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = application.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = InstallerColors.White,
                    )
                    Text(
                        text = stringResource(
                            if (application.installed) {
                                R.string.maintenance_app_installed
                            } else {
                                R.string.maintenance_app_not_installed
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (application.installed) InstallerColors.Success else InstallerColors.Warning,
                    )
                }
            }
        }
    }
}

@Composable
private fun MaintenanceGroup(
    group: MaintenanceGroupId,
    columns: Int,
    connected: Boolean,
    busy: Boolean,
    onAction: (MaintenanceActionId) -> Unit,
) {
    val actions = when (group) {
        MaintenanceGroupId.COMMON -> listOf(
            MaintenanceActionId.CHECK_UPDATES,
            MaintenanceActionId.REINSTALL,
            MaintenanceActionId.REPAIR_CONFIGURATION,
        )
        MaintenanceGroupId.APPS -> listOf(
            MaintenanceActionId.MANAGE_APPS,
            MaintenanceActionId.INSTALL_FILE_MANAGER,
            MaintenanceActionId.LAUNCH_LYRICS,
            MaintenanceActionId.LAUNCH_DESKTOP,
        )
        MaintenanceGroupId.STORAGE -> listOf(
            MaintenanceActionId.CLEANUP,
            MaintenanceActionId.EXPORT_DIAGNOSTICS,
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(groupTitle(group)), style = MaterialTheme.typography.bodyMedium, color = InstallerColors.AuxiliaryWhite)
        val rows = actions.chunked(columns)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEachIndexed { columnIndex, action ->
                        MaintenanceActionCard(
                            action = action,
                            onClick = { onAction(action) },
                            enabled = maintenanceActionEnabled(action, connected, busy),
                            modifier = Modifier.weight(1f),
                            index = rowIndex * columns + columnIndex,
                        )
                    }
                    repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

internal fun maintenanceColumnCount(screenWidthDp: Float): Int = if (screenWidthDp >= 360f) 2 else 1

internal fun maintenanceActionEnabled(
    action: MaintenanceActionId,
    connected: Boolean,
    busy: Boolean,
): Boolean = !busy && (connected || !action.requiresConnectedDevice)

@Composable
private fun MaintenanceActionCard(
    action: MaintenanceActionId,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier,
    index: Int,
) {
    val contentAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.55f,
        animationSpec = InstallerMotion.stateChange(),
        label = "maintenanceActionEnabled",
    )
    AnimatedEntry(visible = true, index = index, modifier = modifier.alpha(contentAlpha)) {
        PressableSurface(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.testTag("maintenance_${action.name.lowercase()}"),
            minHeight = InstallerDimensions.MaintenanceActionMinHeight,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusIcon(
                    name = actionIcon(action),
                    contentDescription = stringResource(actionTitle(action)),
                    tint = InstallerColors.White,
                )
                Text(text = stringResource(actionTitle(action)), style = MaterialTheme.typography.headlineSmall, color = InstallerColors.White)
                Text(text = stringResource(actionDescription(action)), style = MaterialTheme.typography.bodySmall, color = InstallerColors.AuxiliaryWhite)
            }
        }
    }
}

private fun groupTitle(group: MaintenanceGroupId): Int = when (group) {
    MaintenanceGroupId.COMMON -> R.string.maintenance_common
    MaintenanceGroupId.APPS -> R.string.maintenance_apps
    MaintenanceGroupId.STORAGE -> R.string.maintenance_storage
}

private fun actionTitle(action: MaintenanceActionId): Int = when (action) {
    MaintenanceActionId.CHECK_UPDATES -> R.string.maintenance_check_updates
    MaintenanceActionId.REINSTALL -> R.string.maintenance_reinstall
    MaintenanceActionId.REPAIR_CONFIGURATION -> R.string.maintenance_repair
    MaintenanceActionId.MANAGE_APPS -> R.string.maintenance_manage_apps
    MaintenanceActionId.INSTALL_FILE_MANAGER -> R.string.maintenance_install_file_manager
    MaintenanceActionId.LAUNCH_LYRICS -> R.string.maintenance_launch_lyrics
    MaintenanceActionId.LAUNCH_DESKTOP -> R.string.maintenance_launch_desktop
    MaintenanceActionId.CLEANUP -> R.string.maintenance_cleanup
    MaintenanceActionId.EXPORT_DIAGNOSTICS -> R.string.maintenance_diagnostics
}

private fun actionDescription(action: MaintenanceActionId): Int = when (action) {
    MaintenanceActionId.CHECK_UPDATES -> R.string.maintenance_check_updates_description
    MaintenanceActionId.REINSTALL -> R.string.maintenance_reinstall_description
    MaintenanceActionId.REPAIR_CONFIGURATION -> R.string.maintenance_repair_description
    MaintenanceActionId.MANAGE_APPS -> R.string.maintenance_manage_apps_description
    MaintenanceActionId.INSTALL_FILE_MANAGER -> R.string.maintenance_install_file_manager_description
    MaintenanceActionId.LAUNCH_LYRICS -> R.string.maintenance_launch_lyrics_description
    MaintenanceActionId.LAUNCH_DESKTOP -> R.string.maintenance_launch_desktop_description
    MaintenanceActionId.CLEANUP -> R.string.maintenance_cleanup_description
    MaintenanceActionId.EXPORT_DIAGNOSTICS -> R.string.maintenance_diagnostics_description
}

private fun actionIcon(action: MaintenanceActionId): String = when (action) {
    MaintenanceActionId.CHECK_UPDATES -> "refresh_cw"
    MaintenanceActionId.REINSTALL -> "download"
    MaintenanceActionId.REPAIR_CONFIGURATION -> "wrench"
    MaintenanceActionId.MANAGE_APPS -> "layout_grid"
    MaintenanceActionId.INSTALL_FILE_MANAGER -> "folder_plus"
    MaintenanceActionId.LAUNCH_LYRICS -> "music_2"
    MaintenanceActionId.LAUNCH_DESKTOP -> "panels_top_left"
    MaintenanceActionId.CLEANUP -> "trash_2"
    MaintenanceActionId.EXPORT_DIAGNOSTICS -> "file_down"
}

private fun maintenanceFeedbackDescription(feedback: MaintenanceFeedback): Int? = when (feedback.status) {
    MaintenanceActionStatus.RUNNING -> null
    MaintenanceActionStatus.SUCCEEDED -> when (feedback.resultCode) {
        "updates_available" -> R.string.maintenance_result_updates_available
        "up_to_date" -> R.string.maintenance_result_up_to_date
        "authorization_repaired" -> R.string.maintenance_result_authorization_repaired
        "applications_checked" -> R.string.maintenance_result_applications_checked
        "component_launched" -> R.string.maintenance_result_component_launched
        "cache_cleared" -> R.string.maintenance_result_cache_cleared
        "diagnostics_ready" -> R.string.maintenance_result_diagnostics_ready
        else -> null
    }

    MaintenanceActionStatus.FAILED -> when {
        feedback.reasonCode in setOf(
            "adb_connection_closed",
            "device_action_gateway_unavailable",
            "device_disconnected",
        ) -> R.string.maintenance_failure_disconnected

        feedback.reasonCode?.startsWith("catalog_") == true -> R.string.maintenance_failure_catalog
        feedback.reasonCode?.contains("identity") == true -> R.string.maintenance_failure_identity
        else -> R.string.maintenance_failure_generic
    }
}
