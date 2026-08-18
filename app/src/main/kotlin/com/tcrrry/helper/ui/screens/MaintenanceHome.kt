package com.tcrrry.helper.ui.screens

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tcrrry.helper.R
import com.tcrrry.helper.domain.session.MaintenanceActionId
import com.tcrrry.helper.domain.session.MaintenanceGroupId
import com.tcrrry.helper.ui.components.AnimatedEntry
import com.tcrrry.helper.ui.components.PressableSurface
import com.tcrrry.helper.ui.components.StatusIcon
import com.tcrrry.helper.ui.components.TaskTopBar
import com.tcrrry.helper.ui.state.InstallUiIntent
import com.tcrrry.helper.ui.state.InstallUiState
import com.tcrrry.helper.ui.theme.InstallerColors
import com.tcrrry.helper.ui.theme.InstallerDimensions

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
        if (state.connected) {
            PressableSurface(
                onClick = onDisconnect,
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
private fun MaintenanceGroup(
    group: MaintenanceGroupId,
    columns: Int,
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

@Composable
private fun MaintenanceActionCard(
    action: MaintenanceActionId,
    onClick: () -> Unit,
    modifier: Modifier,
    index: Int,
) {
    AnimatedEntry(visible = true, index = index, modifier = modifier) {
        PressableSurface(
            onClick = onClick,
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
