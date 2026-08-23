package com.tcrrry.helper.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import com.tcrrry.helper.domain.session.InstallPhase
import com.tcrrry.helper.domain.session.ResultKind
import com.tcrrry.helper.domain.session.requiresConnectedDevice
import com.tcrrry.helper.ui.components.AnimatedEntry
import com.tcrrry.helper.ui.components.DividerLine
import com.tcrrry.helper.ui.components.PressableSurface
import com.tcrrry.helper.ui.components.PrimaryActionButton
import com.tcrrry.helper.ui.components.StatusIcon
import com.tcrrry.helper.ui.components.TaskTopBar
import com.tcrrry.helper.ui.state.InstallUiIntent
import com.tcrrry.helper.ui.state.InstallUiState
import com.tcrrry.helper.ui.state.MaintenanceApplicationRow
import com.tcrrry.helper.ui.state.MaintenanceFeedback
import com.tcrrry.helper.ui.theme.InstallerColors
import com.tcrrry.helper.ui.theme.InstallerDimensions
import com.tcrrry.helper.ui.theme.InstallerMotion
import kotlin.math.roundToInt

@Composable
fun MaintenanceHome(
    state: InstallUiState.Maintenance,
    onIntent: (InstallUiIntent) -> Unit,
    modifier: Modifier = Modifier,
    selectedAction: MaintenanceActionId? = null,
    onSelectedActionChange: (MaintenanceActionId?) -> Unit = {},
) {
    val selectedActionRunning = selectedAction != null &&
        state.feedback?.actionId == selectedAction &&
        state.feedback?.status == MaintenanceActionStatus.RUNNING
    BackHandler(enabled = selectedAction != null && !selectedActionRunning) {
        onSelectedActionChange(null)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val columns = maintenanceColumnCount(maxWidth.value)
        AnimatedContent(
            targetState = selectedAction,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                if (targetState != null) {
                    ContentTransform(
                        targetContentEnter = fadeIn(InstallerMotion.pageEnter()) +
                            slideInHorizontally(
                                animationSpec = InstallerMotion.pageEnter(),
                                initialOffsetX = { width -> width },
                            ),
                        initialContentExit = fadeOut(InstallerMotion.pageExit()) +
                            slideOutHorizontally(
                                animationSpec = InstallerMotion.pageExit(),
                                targetOffsetX = { width -> -width },
                            ),
                        sizeTransform = null,
                    )
                } else {
                    ContentTransform(
                        targetContentEnter = fadeIn(InstallerMotion.pageEnter()) +
                            slideInHorizontally(
                                animationSpec = InstallerMotion.pageEnter(),
                                initialOffsetX = { width -> -width },
                            ),
                        initialContentExit = fadeOut(InstallerMotion.pageExit()) +
                            slideOutHorizontally(
                                animationSpec = InstallerMotion.pageExit(),
                                targetOffsetX = { width -> width },
                            ),
                        sizeTransform = null,
                    )
                }
            },
            contentKey = { it ?: "maintenance_home" },
            label = "maintenancePage",
        ) { action ->
            if (action == null) {
                MaintenanceOverview(
                    state = state,
                    columns = columns,
                    onReconnect = { onIntent(InstallUiIntent.Reconnect) },
                    onDisconnect = { onIntent(InstallUiIntent.DisconnectDevice) },
                    onAction = { onSelectedActionChange(it) },
                    onRunAction = { onIntent(InstallUiIntent.MaintenanceAction(it)) },
                )
            } else {
                MaintenanceActionPage(
                    action = action,
                    state = state,
                    onBack = { onSelectedActionChange(null) },
                )
            }
        }
    }
}

/** Keeps maintenance-originated installs inside the same secondary-page route. */
@Composable
fun MaintenanceActionFlowPage(
    action: MaintenanceActionId,
    state: InstallUiState,
    onIntent: (InstallUiIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canNavigateBack = state !is InstallUiState.Installing
    BackHandler(enabled = canNavigateBack, onBack = onBack)
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = InstallerDimensions.PageHorizontalPadding)
            .testTag("maintenance_action_flow_${action.name.lowercase()}"),
    ) {
        TaskTopBar(
            title = stringResource(actionTitle(action)),
            onBack = onBack.takeIf { canNavigateBack },
        )
        when (state) {
            is InstallUiState.Installing -> MaintenanceInstallProgress(
                state = state,
            )

            is InstallUiState.Result -> MaintenanceInstallResult(
                state = state,
                onIntent = onIntent,
                onBack = onBack,
            )

            else -> MaintenanceActionWaiting()
        }
    }
}

@Composable
private fun MaintenanceInstallProgress(
    state: InstallUiState.Installing,
) {
    val phases = listOf(
        InstallPhase.FETCH to R.string.phase_fetch,
        InstallPhase.CHECK to R.string.phase_check,
        InstallPhase.SEND to R.string.phase_send,
        InstallPhase.CONFIGURE to R.string.phase_configure,
        InstallPhase.VERIFY to R.string.phase_verify,
    )
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
        Text(
            text = stringResource(R.string.installing_device, state.deviceName),
            style = MaterialTheme.typography.bodyLarge,
            color = InstallerColors.AuxiliaryWhite,
        )
        state.currentComponentName?.let { current ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = current, style = MaterialTheme.typography.headlineSmall, color = InstallerColors.White)
        }
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
        MaintenanceNextStep(stringResource(R.string.maintenance_next_step_running))
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .testTag("maintenance_action_phases"),
            verticalArrangement = Arrangement.spacedBy(InstallerDimensions.ListSpacing),
        ) {
            items(phases, key = { it.first }) { (phase, label) ->
                MaintenancePhaseRow(
                    phase = phase,
                    label = stringResource(label),
                    state = state,
                )
            }
        }
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
        Text(
            text = stringResource(R.string.install_keep_screen_on),
            style = MaterialTheme.typography.bodySmall,
            color = InstallerColors.AuxiliaryWhite,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
    }
}

@Composable
private fun MaintenancePhaseRow(
    phase: InstallPhase,
    label: String,
    state: InstallUiState.Installing,
) {
    val completed = phase in state.completedStages
    val current = phase == state.currentPhase
    val animatedProgress by animateFloatAsState(
        targetValue = state.progress.fraction ?: 0f,
        animationSpec = InstallerMotion.progress(),
        label = "maintenancePhaseProgress",
    )
    PressableSurface(
        onClick = {},
        enabled = false,
        minHeight = InstallerDimensions.ListItemMinHeight,
        containerColor = if (current) InstallerColors.WhiteSurface else InstallerColors.PageBlue,
        borderColor = if (current) InstallerColors.WhiteBorder else InstallerColors.WhiteBorder,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusIcon(
                name = when {
                    completed -> "circle_check"
                    current -> "loader_circle"
                    else -> "circle"
                },
                contentDescription = label,
                tint = if (completed) InstallerColors.Success else InstallerColors.White,
                size = InstallerDimensions.SmallIconSize,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (current || completed) InstallerColors.White else InstallerColors.AuxiliaryWhite,
                )
                if (current) {
                    if (state.progress.indeterminate || state.progress.fraction == null) {
                        LinearProgressIndicator(
                            color = InstallerColors.White,
                            trackColor = InstallerColors.WhiteBorder,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                color = InstallerColors.White,
                                trackColor = InstallerColors.WhiteBorder,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = stringResource(
                                    R.string.install_progress_percent,
                                    (animatedProgress * 100f).roundToInt(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = InstallerColors.White,
                                modifier = Modifier.width(44.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MaintenanceInstallResult(
    state: InstallUiState.Result,
    onIntent: (InstallUiIntent) -> Unit,
    onBack: () -> Unit,
) {
    val (title, description, icon, tint, actionText, action) = when (state.kind) {
        ResultKind.SUCCESS -> ResultFlowCopy(
            R.string.result_success_title,
            R.string.result_success_description,
            "circle_check",
            InstallerColors.Success,
            R.string.result_enter_maintenance,
            InstallUiIntent.EnterMaintenance,
        )
        ResultKind.PARTIAL_FAILURE -> ResultFlowCopy(
            R.string.result_partial_failure_title,
            R.string.result_partial_failure_description,
            "triangle_alert",
            InstallerColors.Warning,
            if (state.canEnterMaintenance) R.string.result_enter_maintenance else R.string.result_retry,
            if (state.canEnterMaintenance) InstallUiIntent.EnterMaintenance else InstallUiIntent.RetryInstallation,
        )
        ResultKind.PAUSED -> ResultFlowCopy(
            R.string.result_paused_title,
            R.string.result_paused_description,
            "circle_pause",
            InstallerColors.Warning,
            R.string.result_continue,
            InstallUiIntent.ContinueInstallation,
        )
        ResultKind.DOWNLOAD_FAILED -> ResultFlowCopy(
            R.string.result_download_failed_title,
            R.string.result_download_failed_description,
            "cloud_off",
            InstallerColors.Error,
            R.string.result_retry,
            InstallUiIntent.RetryInstallation,
        )
        ResultKind.INSTALLATION_FAILED -> ResultFlowCopy(
            R.string.result_install_failed_title,
            R.string.result_install_failed_description,
            "triangle_alert",
            InstallerColors.Error,
            R.string.result_continue,
            InstallUiIntent.ContinueInstallation,
        )
        ResultKind.CONFIGURATION_FAILED -> ResultFlowCopy(
            R.string.result_configuration_failed_title,
            R.string.result_configuration_failed_description,
            "settings_2",
            InstallerColors.Warning,
            R.string.result_reconfigure,
            InstallUiIntent.Reconfigure,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("maintenance_action_result"),
        verticalArrangement = Arrangement.spacedBy(InstallerDimensions.ContentSpacing),
    ) {
        Spacer(modifier = Modifier.height(InstallerDimensions.SectionVerticalSpacing))
        StatusIcon(
            name = icon,
            contentDescription = stringResource(title),
            tint = tint,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(text = stringResource(title), style = MaterialTheme.typography.headlineSmall, color = InstallerColors.White)
        Text(text = stringResource(description), style = MaterialTheme.typography.bodyLarge, color = InstallerColors.AuxiliaryWhite)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(InstallerDimensions.ListSpacing),
        ) {
            items(state.componentResults, key = { it.componentName }) { result ->
                PressableSurface(onClick = {}, enabled = false, containerColor = InstallerColors.WhiteSurface) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(text = result.componentName, style = MaterialTheme.typography.bodyLarge, color = InstallerColors.White)
                            Text(
                                text = listOf(
                                    stringResource(if (result.installed) R.string.result_status_installed else R.string.result_status_not_installed),
                                    stringResource(if (result.configured) R.string.result_status_configured else R.string.result_status_not_configured),
                                    stringResource(if (result.available) R.string.result_status_available else R.string.result_status_not_available),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = InstallerColors.AuxiliaryWhite,
                            )
                        }
                        StatusIcon(
                            name = if (result.installed && result.configured && result.available) "circle_check" else "circle_alert",
                            contentDescription = result.componentName,
                            tint = if (result.installed && result.configured && result.available) InstallerColors.Success else InstallerColors.Warning,
                            size = InstallerDimensions.SmallIconSize,
                        )
                    }
                }
            }
        }
        MaintenanceNextStep(stringResource(maintenanceResultNextStep(state.kind)))
        PrimaryActionButton(
            text = stringResource(actionText),
            onClick = {
                onIntent(action)
                if (action == InstallUiIntent.EnterMaintenance) {
                    onBack()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
    }
}

private data class ResultFlowCopy(
    val title: Int,
    val description: Int,
    val icon: String,
    val tint: androidx.compose.ui.graphics.Color,
    val actionText: Int,
    val action: InstallUiIntent,
)

@Composable
private fun MaintenanceOverview(
    state: InstallUiState.Maintenance,
    columns: Int,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onAction: (MaintenanceActionId) -> Unit,
    onRunAction: (MaintenanceActionId) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = InstallerDimensions.PageHorizontalPadding)
            .testTag("maintenance_home"),
    ) {
        TaskTopBar(title = stringResource(R.string.task_maintenance))
        DeviceStatusHeader(
            state = state,
            onReconnect = onReconnect,
            onDisconnect = onDisconnect,
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
                    connected = state.connected,
                    busy = state.feedback?.status == MaintenanceActionStatus.RUNNING,
                    onAction = { action ->
                        onAction(action)
                        onRunAction(action)
                    },
                )
            }
        }
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
    }
}

@Composable
private fun MaintenanceActionPage(
    action: MaintenanceActionId,
    state: InstallUiState.Maintenance,
    onBack: () -> Unit,
) {
    val feedback = state.feedback?.takeIf { it.actionId == action }
    val canNavigateBack = feedback?.status != MaintenanceActionStatus.RUNNING
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = InstallerDimensions.PageHorizontalPadding)
            .testTag("maintenance_action_page_${action.name.lowercase()}"),
    ) {
        TaskTopBar(
            title = stringResource(actionTitle(action)),
            onBack = onBack.takeIf { canNavigateBack },
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .testTag("maintenance_action_content"),
            verticalArrangement = Arrangement.spacedBy(InstallerDimensions.ContentSpacing),
        ) {
            item {
                MaintenanceActionHeader(action = action, state = state)
            }
            item {
                AnimatedContent(
                    targetState = feedback,
                    transitionSpec = {
                        ContentTransform(
                            targetContentEnter = fadeIn(InstallerMotion.stateChange()),
                            initialContentExit = fadeOut(InstallerMotion.stateChange()),
                            sizeTransform = null,
                        )
                    },
                    contentKey = { target ->
                        target?.let {
                            "${it.actionId}:${it.status}:${it.resultCode ?: ""}:${it.reasonCode ?: ""}"
                        } ?: "waiting"
                    },
                    label = "maintenanceActionState",
                ) { target ->
                    if (target == null) {
                        MaintenanceActionWaiting()
                    } else {
                        MaintenanceFeedbackBlock(target, state.applications)
                    }
                }
            }
            item {
                MaintenanceNextStep(
                    text = stringResource(
                        if (feedback == null) {
                            R.string.maintenance_next_step_preparing
                        } else {
                            maintenanceNextStep(feedback.status)
                        },
                    ),
                )
            }
        }
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
    }
}

@Composable
private fun MaintenanceActionHeader(
    action: MaintenanceActionId,
    state: InstallUiState.Maintenance,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusIcon(
            name = actionIcon(action),
            contentDescription = stringResource(actionTitle(action)),
            tint = InstallerColors.White,
            size = InstallerDimensions.IconSize,
        )
        Text(
            text = stringResource(actionDescription(action)),
            style = MaterialTheme.typography.bodyLarge,
            color = InstallerColors.AuxiliaryWhite,
        )
        DividerLine()
        Text(
            text = stringResource(
                R.string.maintenance_action_target,
                state.deviceName ?: stringResource(R.string.maintenance_disconnected),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = InstallerColors.White,
        )
        Text(
            text = stringResource(R.string.maintenance_action_scope, stringResource(actionTitle(action))),
            style = MaterialTheme.typography.bodySmall,
            color = InstallerColors.AuxiliaryWhite,
        )
    }
}

@Composable
private fun MaintenanceActionWaiting() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(28.dp)
                .testTag("maintenance_action_progress"),
            color = InstallerColors.White,
            strokeWidth = 2.dp,
        )
        Text(
            text = stringResource(R.string.maintenance_action_preparing),
            style = MaterialTheme.typography.bodyLarge,
            color = InstallerColors.White,
        )
    }
}

@Composable
private fun MaintenanceNextStep(text: String) {
    Text(
        text = stringResource(R.string.maintenance_next_step_label, text),
        style = MaterialTheme.typography.bodySmall,
        color = InstallerColors.AuxiliaryWhite,
    )
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(16.dp)
                            .testTag("maintenance_reconnect_progress"),
                        color = InstallerColors.White,
                        strokeWidth = 2.dp,
                    )
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
            if (feedback.status == MaintenanceActionStatus.RUNNING) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("maintenance_action_progress"),
                    color = InstallerColors.White,
                    strokeWidth = 2.dp,
                )
            } else {
                StatusIcon(name = icon, contentDescription = title, tint = tint)
            }
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

private fun maintenanceNextStep(status: MaintenanceActionStatus): Int = when (status) {
    MaintenanceActionStatus.RUNNING -> R.string.maintenance_next_step_running
    MaintenanceActionStatus.SUCCEEDED -> R.string.maintenance_next_step_succeeded
    MaintenanceActionStatus.FAILED -> R.string.maintenance_next_step_failed
}

private fun maintenanceResultNextStep(kind: ResultKind): Int = when (kind) {
    ResultKind.SUCCESS -> R.string.maintenance_next_step_succeeded
    ResultKind.PARTIAL_FAILURE -> R.string.maintenance_next_step_succeeded
    ResultKind.PAUSED,
    ResultKind.INSTALLATION_FAILED,
    -> R.string.maintenance_next_step_continue

    ResultKind.DOWNLOAD_FAILED -> R.string.maintenance_next_step_retry
    ResultKind.CONFIGURATION_FAILED -> R.string.maintenance_next_step_reconfigure
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
