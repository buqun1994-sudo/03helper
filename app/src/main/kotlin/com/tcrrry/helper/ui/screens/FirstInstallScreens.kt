package com.tcrrry.helper.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.tcrrry.helper.R
import com.tcrrry.helper.domain.session.DeviceConnectionStatus
import com.tcrrry.helper.domain.session.InstallPhase
import com.tcrrry.helper.domain.session.ResultKind
import com.tcrrry.helper.ui.components.AnimatedEntry
import com.tcrrry.helper.ui.components.InstallStepIndicator
import com.tcrrry.helper.ui.components.PressableSurface
import com.tcrrry.helper.ui.components.PrimaryActionButton
import com.tcrrry.helper.ui.components.StatusIcon
import com.tcrrry.helper.ui.components.TaskTopBar
import com.tcrrry.helper.ui.state.ComponentRow
import com.tcrrry.helper.ui.state.ConnectionVariant
import com.tcrrry.helper.ui.state.DeviceRow
import com.tcrrry.helper.ui.state.InstallUiIntent
import com.tcrrry.helper.ui.state.InstallUiState
import com.tcrrry.helper.ui.theme.InstallerColors
import com.tcrrry.helper.ui.theme.InstallerDimensions
import com.tcrrry.helper.ui.theme.InstallerMotion

@Composable
fun FirstInstallScreen(
    state: InstallUiState,
    onIntent: (InstallUiIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is InstallUiState.Connection -> ConnectionScreen(state, onIntent, modifier)
        is InstallUiState.Selection -> SelectionScreen(state, onIntent, modifier)
        is InstallUiState.Installing -> InstallingScreen(state, onIntent, modifier)
        is InstallUiState.Result -> ResultScreen(state, onIntent, modifier)
        is InstallUiState.Maintenance -> Unit
    }
}

@Composable
private fun ConnectionScreen(
    state: InstallUiState.Connection,
    onIntent: (InstallUiIntent) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = InstallerDimensions.PageHorizontalPadding),
    ) {
        TaskTopBar(title = stringResource(R.string.task_install))
        InstallStepIndicator(activeStep = 0, modifier = Modifier.testTag("install_steps"))
        Spacer(modifier = Modifier.height(InstallerDimensions.SectionVerticalSpacing))

        when (state.variant) {
            ConnectionVariant.SEARCHING -> {
                StatusIcon(
                    name = "search",
                    contentDescription = stringResource(R.string.connection_searching_title),
                    tint = InstallerColors.White,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
                ConnectionCopy(
                    title = stringResource(R.string.connection_searching_title),
                    description = stringResource(R.string.connection_searching_description),
                )
                Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
                CircularProgressIndicator(
                    color = InstallerColors.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.CenterHorizontally),
                )
            }

            ConnectionVariant.FOUND -> {
                ConnectionCopy(
                    title = stringResource(R.string.connection_found_title),
                    description = stringResource(R.string.connection_found_description),
                )
                Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("device_list"),
                    verticalArrangement = Arrangement.spacedBy(InstallerDimensions.ListSpacing),
                ) {
                    itemsIndexed(state.devices, key = { _, device -> device.id }) { index, device ->
                        AnimatedEntry(visible = true, index = index) {
                            DeviceChoiceRow(device = device, onClick = { onIntent(InstallUiIntent.SelectDevice(device.id)) })
                        }
                    }
                }
            }

            ConnectionVariant.NOT_FOUND -> {
                StatusIcon(
                    name = "wifi_off",
                    contentDescription = stringResource(R.string.connection_empty_title),
                    tint = InstallerColors.Warning,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
                ConnectionCopy(
                    title = stringResource(R.string.connection_empty_title),
                    description = stringResource(R.string.connection_empty_description),
                )
                Spacer(modifier = Modifier.weight(1f))
                PrimaryActionButton(
                    text = stringResource(R.string.connection_retry),
                    onClick = { onIntent(InstallUiIntent.RetryDiscovery) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("connection_primary_action"),
                )
                Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
            }

            ConnectionVariant.FAILED -> {
                StatusIcon(
                    name = "circle_alert",
                    contentDescription = stringResource(R.string.connection_failed_title),
                    tint = InstallerColors.Error,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
                ConnectionCopy(
                    title = stringResource(R.string.connection_failed_title),
                    description = stringResource(R.string.connection_failed_description),
                )
                Spacer(modifier = Modifier.weight(1f))
                PrimaryActionButton(
                    text = stringResource(R.string.connection_reconnect),
                    onClick = { onIntent(InstallUiIntent.Reconnect) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
            }
        }

        if (state.variant == ConnectionVariant.SEARCHING) {
            Spacer(modifier = Modifier.weight(1f))
            PrimaryActionButton(
                text = stringResource(R.string.connection_stop),
                onClick = { onIntent(InstallUiIntent.StopDiscovery) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
        }
    }
}

@Composable
private fun ConnectionCopy(title: String, description: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall, color = InstallerColors.White)
        Text(text = description, style = MaterialTheme.typography.bodyLarge, color = InstallerColors.AuxiliaryWhite)
    }
}

@Composable
private fun DeviceChoiceRow(device: DeviceRow, onClick: () -> Unit) {
    PressableSurface(
        onClick = onClick,
        modifier = Modifier.testTag("device_${device.id}"),
        minHeight = InstallerDimensions.ListItemMinHeight,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusIcon(
                name = if (device.status == DeviceConnectionStatus.CONFIRMED) "circle_check" else "car_front",
                contentDescription = device.displayName,
                tint = InstallerColors.Success,
                size = InstallerDimensions.SmallIconSize,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = InstallerColors.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                device.lastConfirmedLabel?.let { label ->
                    Text(text = label, style = MaterialTheme.typography.bodySmall, color = InstallerColors.AuxiliaryWhite)
                }
            }
            StatusIcon(
                name = "chevron_right",
                contentDescription = device.displayName,
                tint = InstallerColors.White,
                size = InstallerDimensions.SmallIconSize,
            )
        }
    }
}

@Composable
private fun SelectionScreen(
    state: InstallUiState.Selection,
    onIntent: (InstallUiIntent) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = InstallerDimensions.PageHorizontalPadding),
    ) {
        TaskTopBar(title = stringResource(R.string.task_install))
        InstallStepIndicator(activeStep = 1, modifier = Modifier.testTag("install_steps"))
        Spacer(modifier = Modifier.height(InstallerDimensions.SectionVerticalSpacing))
        Text(text = stringResource(R.string.selection_title), style = MaterialTheme.typography.headlineSmall, color = InstallerColors.White)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = stringResource(R.string.selection_description), style = MaterialTheme.typography.bodyLarge, color = InstallerColors.AuxiliaryWhite)
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))

        if (state.components.isEmpty()) {
            SelectionUnavailable(onIntent = onIntent, modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .testTag("component_list"),
                verticalArrangement = Arrangement.spacedBy(InstallerDimensions.ListSpacing),
            ) {
                itemsIndexed(state.components, key = { _, component -> component.id }) { index, component ->
                    AnimatedEntry(visible = true, index = index) {
                        ComponentChoiceRow(
                            component = component,
                            onToggle = { selected ->
                                onIntent(InstallUiIntent.ToggleOptionalComponent(component.id, selected))
                            },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
            Text(
                text = stringResource(R.string.selection_summary, state.summaryCount, state.summarySizeLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = InstallerColors.AuxiliaryWhite,
            )
            Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
            PrimaryActionButton(
                text = stringResource(R.string.selection_start),
                onClick = { onIntent(InstallUiIntent.StartInstallation) },
                enabled = state.canStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("start_installation"),
            )
            Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
        }
    }
}

@Composable
private fun SelectionUnavailable(
    onIntent: (InstallUiIntent) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(InstallerDimensions.ContentSpacing),
    ) {
        StatusIcon(
            name = "package_x",
            contentDescription = stringResource(R.string.selection_prepare_failed_title),
            tint = InstallerColors.Warning,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(
            text = stringResource(R.string.selection_prepare_failed_title),
            style = MaterialTheme.typography.headlineSmall,
            color = InstallerColors.White,
        )
        Spacer(modifier = Modifier.weight(1f))
        PrimaryActionButton(
            text = stringResource(R.string.selection_refresh),
            onClick = { onIntent(InstallUiIntent.RetryInstallation) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ComponentChoiceRow(component: ComponentRow, onToggle: (Boolean) -> Unit) {
    val selected = component.required || component.selected
    PressableSurface(
        onClick = { if (!component.required) onToggle(!selected) },
        enabled = !component.required,
        minHeight = InstallerDimensions.ComponentItemMinHeight,
        modifier = Modifier
            .testTag("component_${component.id}")
            .semantics { role = Role.Checkbox },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = component.displayName, style = MaterialTheme.typography.bodyLarge, color = InstallerColors.White)
                    if (component.required) {
                        StatusIcon(
                            name = "lock_keyhole",
                            contentDescription = stringResource(R.string.required_label),
                            tint = InstallerColors.AuxiliaryWhite,
                            size = 16.dp,
                        )
                        Text(text = stringResource(R.string.required_label), style = MaterialTheme.typography.bodySmall, color = InstallerColors.AuxiliaryWhite)
                    } else {
                        Text(text = stringResource(R.string.optional_label), style = MaterialTheme.typography.bodySmall, color = InstallerColors.AuxiliaryWhite)
                    }
                }
                Text(
                    text = listOfNotNull(component.versionLabel, component.sizeLabel, component.compatibilityLabel)
                        .joinToString(" · ")
                        .ifBlank { stringResource(R.string.unknown_value) },
                    style = MaterialTheme.typography.bodySmall,
                    color = InstallerColors.AuxiliaryWhite,
                )
            }
            Checkbox(
                checked = selected,
                onCheckedChange = if (component.required) null else onToggle,
                enabled = !component.required,
                colors = CheckboxDefaults.colors(
                    checkedColor = InstallerColors.White,
                    uncheckedColor = InstallerColors.WhiteBorder,
                    checkmarkColor = InstallerColors.PressedBlue,
                    disabledCheckedColor = InstallerColors.White,
                    disabledUncheckedColor = InstallerColors.WhiteBorder,
                ),
            )
        }
    }
}

@Composable
private fun InstallingScreen(
    state: InstallUiState.Installing,
    onIntent: (InstallUiIntent) -> Unit,
    modifier: Modifier,
) {
    val phases = listOf(
        InstallPhase.FETCH to R.string.phase_fetch,
        InstallPhase.CHECK to R.string.phase_check,
        InstallPhase.SEND to R.string.phase_send,
        InstallPhase.CONFIGURE to R.string.phase_configure,
        InstallPhase.VERIFY to R.string.phase_verify,
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = InstallerDimensions.PageHorizontalPadding),
    ) {
        TaskTopBar(title = stringResource(R.string.installing_title))
        InstallStepIndicator(activeStep = 2, modifier = Modifier.testTag("install_steps"))
        Spacer(modifier = Modifier.height(InstallerDimensions.SectionVerticalSpacing))
        Text(text = stringResource(R.string.installing_device, state.deviceName), style = MaterialTheme.typography.bodyLarge, color = InstallerColors.AuxiliaryWhite)
        state.currentComponentName?.let { current ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = current, style = MaterialTheme.typography.headlineSmall, color = InstallerColors.White)
        }
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .testTag("install_phases"),
            verticalArrangement = Arrangement.spacedBy(InstallerDimensions.ListSpacing),
        ) {
            itemsIndexed(phases, key = { _, item -> item.first }) { index, (phase, label) ->
                AnimatedEntry(visible = true, index = index) {
                    PhaseRow(
                        label = stringResource(label),
                        phase = phase,
                        state = state,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
        PressableSurface(
            onClick = { onIntent(InstallUiIntent.CancelInstallation) },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canCancel,
            minHeight = InstallerDimensions.PrimaryActionHeight,
            containerColor = Color.Transparent,
            pressedColor = InstallerColors.PressedBlue,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text(text = stringResource(R.string.install_cancel), style = MaterialTheme.typography.bodyLarge, color = InstallerColors.White)
            }
        }
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
    }
}

@Composable
private fun PhaseRow(
    label: String,
    phase: InstallPhase,
    state: InstallUiState.Installing,
) {
    val completed = phase in state.completedStages
    val current = phase == state.currentPhase
    val animatedProgress by animateFloatAsState(
        targetValue = state.progress.fraction ?: 0f,
        animationSpec = InstallerMotion.progress(),
        label = "phaseProgress",
    )
    PressableSurface(
        onClick = {},
        enabled = false,
        minHeight = InstallerDimensions.ListItemMinHeight,
        containerColor = if (current) InstallerColors.WhiteSurface else Color.Transparent,
        borderColor = if (current) InstallerColors.WhiteBorder else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedContent(
                targetState = when {
                    completed -> PhaseVisual.COMPLETED
                    current -> PhaseVisual.CURRENT
                    else -> PhaseVisual.PENDING
                },
                transitionSpec = {
                    (fadeIn(InstallerMotion.stateChange()) + scaleIn(InstallerMotion.stateChange(), initialScale = 0.92f))
                        .togetherWith(fadeOut(InstallerMotion.stateChange()) + scaleOut(InstallerMotion.stateChange(), targetScale = 0.92f))
                },
                label = "phaseStatus",
            ) { visual ->
                StatusIcon(
                    name = when (visual) {
                        PhaseVisual.COMPLETED -> "circle_check"
                        PhaseVisual.CURRENT -> "loader_circle"
                        PhaseVisual.PENDING -> "circle"
                    },
                    contentDescription = label,
                    tint = if (visual == PhaseVisual.COMPLETED) InstallerColors.Success else InstallerColors.White,
                    size = InstallerDimensions.SmallIconSize,
                )
            }
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
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            color = InstallerColors.White,
                            trackColor = InstallerColors.WhiteBorder,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultScreen(
    state: InstallUiState.Result,
    onIntent: (InstallUiIntent) -> Unit,
    modifier: Modifier,
) {
    val copy = when (state.kind) {
        ResultKind.SUCCESS -> ResultCopy(R.string.result_success_title, R.string.result_success_description, "circle_check", InstallerColors.Success, R.string.result_enter_maintenance) { onIntent(InstallUiIntent.EnterMaintenance) }
        ResultKind.PAUSED -> ResultCopy(R.string.result_paused_title, R.string.result_paused_description, "circle_pause", InstallerColors.Warning, R.string.result_continue) { onIntent(InstallUiIntent.ContinueInstallation) }
        ResultKind.DOWNLOAD_FAILED -> ResultCopy(R.string.result_download_failed_title, R.string.result_download_failed_description, "cloud_off", InstallerColors.Error, R.string.result_retry) { onIntent(InstallUiIntent.RetryInstallation) }
        ResultKind.INSTALLATION_FAILED -> ResultCopy(R.string.result_install_failed_title, R.string.result_install_failed_description, "triangle_alert", InstallerColors.Error, R.string.result_continue) { onIntent(InstallUiIntent.ContinueInstallation) }
        ResultKind.CONFIGURATION_FAILED -> ResultCopy(R.string.result_configuration_failed_title, R.string.result_configuration_failed_description, "settings_2", InstallerColors.Warning, R.string.result_reconfigure) { onIntent(InstallUiIntent.Reconfigure) }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = InstallerDimensions.PageHorizontalPadding)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(InstallerDimensions.ContentSpacing),
    ) {
        TaskTopBar(title = stringResource(R.string.task_install))
        Spacer(modifier = Modifier.height(InstallerDimensions.SectionVerticalSpacing))
        StatusIcon(name = copy.icon, contentDescription = stringResource(copy.title), tint = copy.tint, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(text = stringResource(copy.title), style = MaterialTheme.typography.headlineSmall, color = InstallerColors.White)
        Text(text = stringResource(copy.description), style = MaterialTheme.typography.bodyLarge, color = InstallerColors.AuxiliaryWhite)
        if (state.componentResults.isNotEmpty()) {
            state.componentResults.forEachIndexed { index, result ->
                AnimatedEntry(visible = true, index = index) {
                    ResultRow(result.componentName, result.installed, result.configured, result.available)
                }
            }
        }
        Spacer(modifier = Modifier.height(InstallerDimensions.SectionVerticalSpacing))
        PrimaryActionButton(text = stringResource(copy.actionText), onClick = copy.action, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
    }
}

private data class ResultCopy(
    val title: Int,
    val description: Int,
    val icon: String,
    val tint: Color,
    val actionText: Int,
    val action: () -> Unit,
)

@Composable
private fun ResultRow(componentName: String, installed: Boolean, configured: Boolean, available: Boolean) {
    PressableSurface(onClick = {}, enabled = false, containerColor = InstallerColors.WhiteSurface) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = componentName, style = MaterialTheme.typography.bodyLarge, color = InstallerColors.White)
                Text(
                    text = listOf(
                        stringResource(if (installed) R.string.result_status_installed else R.string.result_status_not_installed),
                        stringResource(if (configured) R.string.result_status_configured else R.string.result_status_not_configured),
                        stringResource(if (available) R.string.result_status_available else R.string.result_status_not_available),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = InstallerColors.AuxiliaryWhite,
                )
            }
            StatusIcon(
                name = if (installed && configured && available) "circle_check" else "circle_alert",
                contentDescription = componentName,
                tint = if (installed && configured && available) InstallerColors.Success else InstallerColors.Warning,
                size = InstallerDimensions.SmallIconSize,
            )
        }
    }
}

private enum class PhaseVisual {
    COMPLETED,
    CURRENT,
    PENDING,
}
