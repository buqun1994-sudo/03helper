package com.ninepointnine.helper.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.ninepointnine.helper.R
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.device.AuthorizationPlanFactory
import com.ninepointnine.helper.domain.session.InstallPhase
import com.ninepointnine.helper.domain.session.ResultKind
import com.ninepointnine.helper.ui.state.ResultFailureStage
import com.ninepointnine.helper.ui.components.AnimatedEntry
import com.ninepointnine.helper.ui.components.ComponentLogo
import com.ninepointnine.helper.ui.components.InstallStepIndicator
import com.ninepointnine.helper.ui.components.InstallationResultRow
import com.ninepointnine.helper.ui.components.PressableSurface
import com.ninepointnine.helper.ui.components.PrimaryActionButton
import com.ninepointnine.helper.ui.components.StatusIcon
import com.ninepointnine.helper.ui.components.TaskTopBar
import com.ninepointnine.helper.ui.state.ComponentRow
import com.ninepointnine.helper.ui.state.ConnectionVariant
import com.ninepointnine.helper.ui.state.DeviceRow
import com.ninepointnine.helper.ui.state.InstallUiIntent
import com.ninepointnine.helper.ui.state.InstallUiState
import com.ninepointnine.helper.ui.state.isMandatory
import com.ninepointnine.helper.ui.theme.InstallerColors
import com.ninepointnine.helper.ui.theme.InstallerDimensions
import com.ninepointnine.helper.ui.theme.InstallerMotion
import kotlin.math.roundToInt

@Composable
fun FirstInstallScreen(
    state: InstallUiState,
    onIntent: (InstallUiIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is InstallUiState.Connection -> ConnectionScreen(state, onIntent, modifier)
        is InstallUiState.Selection -> SelectionScreen(state, onIntent, modifier)
        is InstallUiState.Installing -> InstallingScreen(state, modifier)
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

            ConnectionVariant.CONNECTING -> {
                StatusIcon(
                    name = "loader_circle",
                    contentDescription = stringResource(R.string.connection_connecting_title),
                    tint = InstallerColors.White,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
                ConnectionCopy(
                    title = stringResource(R.string.connection_connecting_title),
                    description = stringResource(R.string.connection_connecting_description),
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
        } else if (state.variant == ConnectionVariant.CONNECTING) {
            Spacer(modifier = Modifier.weight(1f))
            PrimaryActionButton(
                text = stringResource(R.string.connection_cancel),
                onClick = { onIntent(InstallUiIntent.CancelConnection) },
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
                name = when (device.status) {
                    DeviceConnectionStatus.CONFIRMED -> "circle_check"
                    DeviceConnectionStatus.CONNECTING -> "loader_circle"
                    DeviceConnectionStatus.DISCONNECTED -> "car_front"
                },
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

        if (state.failureReason != null) {
            SelectionFailure(
                reason = state.failureReason,
                onRetry = { onIntent(InstallUiIntent.RetryInstallation) },
                modifier = Modifier.weight(1f),
            )
        } else if (state.preparing) {
            SelectionPreparing(modifier = Modifier.weight(1f))
        } else if (state.components.isEmpty()) {
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
private fun SelectionPreparing(modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            color = InstallerColors.White,
            strokeWidth = 2.dp,
            modifier = Modifier.size(28.dp),
        )
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
        Text(
            text = stringResource(R.string.selection_prepare_loading_title),
            style = MaterialTheme.typography.headlineSmall,
            color = InstallerColors.White,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.selection_prepare_loading_description),
            style = MaterialTheme.typography.bodyLarge,
            color = InstallerColors.AuxiliaryWhite,
        )
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
private fun SelectionFailure(
    reason: String,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(InstallerDimensions.ContentSpacing),
    ) {
        StatusIcon(
            name = "circle_alert",
            contentDescription = reason,
            tint = InstallerColors.Error,
        )
        Text(
            text = stringResource(R.string.selection_prepare_failed_title),
            style = MaterialTheme.typography.headlineSmall,
            color = InstallerColors.White,
            textAlign = TextAlign.Center,
        )
        Text(
            text = reason,
            style = MaterialTheme.typography.bodyLarge,
            color = InstallerColors.AuxiliaryWhite,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.weight(1f))
        PrimaryActionButton(
            text = stringResource(R.string.selection_refresh),
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ComponentChoiceRow(component: ComponentRow, onToggle: (Boolean) -> Unit) {
    // Cloud's `required` field is an install recommendation for non-desktop
    // entries. Only the desktop component is locked in first-install UI.
    val mandatory = component.isMandatory()
    val selected = mandatory || component.selected
    val supported = component.compatibilityState != com.ninepointnine.helper.domain.session.ComponentCompatibility.UNSUPPORTED &&
        component.status in setOf(
            com.ninepointnine.helper.domain.session.ComponentStatus.AVAILABLE,
            com.ninepointnine.helper.domain.session.ComponentStatus.NEW,
            com.ninepointnine.helper.domain.session.ComponentStatus.UPDATE_AVAILABLE,
            com.ninepointnine.helper.domain.session.ComponentStatus.READING,
            com.ninepointnine.helper.domain.session.ComponentStatus.DIRECTORY_MISSING,
        )
    PressableSurface(
        onClick = { if (!mandatory && supported) onToggle(!selected) },
        enabled = !mandatory && supported,
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
            ComponentLogo(
                iconKey = component.iconKey,
                contentDescription = component.displayName,
                size = 40.dp,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = component.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = InstallerColors.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        modifier = Modifier.width(68.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (mandatory) {
                            StatusIcon(
                                name = "lock_keyhole",
                                contentDescription = stringResource(R.string.required_label),
                                tint = InstallerColors.AuxiliaryWhite,
                                size = 16.dp,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = stringResource(if (mandatory) R.string.required_label else R.string.optional_label),
                            style = MaterialTheme.typography.bodySmall,
                            color = InstallerColors.AuxiliaryWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = listOfNotNull(component.versionLabel, component.sizeLabel)
                        .joinToString(" · ")
                        .ifBlank { stringResource(R.string.unknown_value) },
                    style = MaterialTheme.typography.bodySmall,
                    color = InstallerColors.AuxiliaryWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = if (mandatory || !supported) null else onToggle,
                    enabled = !mandatory && supported,
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
}

@Composable
private fun InstallingScreen(
    state: InstallUiState.Installing,
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
        Text(
            text = stringResource(R.string.install_keep_screen_on),
            style = MaterialTheme.typography.bodySmall,
            color = InstallerColors.AuxiliaryWhite,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
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
private fun ResultScreen(
    state: InstallUiState.Result,
    onIntent: (InstallUiIntent) -> Unit,
    modifier: Modifier,
) {
    val copy = when (state.kind) {
        ResultKind.SUCCESS -> ResultCopy(R.string.result_success_title, R.string.result_success_description, "circle_check", InstallerColors.Success, R.string.result_enter_maintenance) { onIntent(InstallUiIntent.EnterMaintenance) }
        ResultKind.CONFIRMATION_PENDING -> ResultCopy(
            R.string.result_confirmation_pending_title,
            R.string.result_confirmation_pending_description,
            "circle_alert",
            InstallerColors.Warning,
            if (state.canEnterMaintenance) R.string.result_enter_maintenance else R.string.result_retry,
        ) {
            onIntent(if (state.canEnterMaintenance) InstallUiIntent.EnterMaintenance else InstallUiIntent.ReturnToSelection)
        }
        ResultKind.PARTIAL_FAILURE -> if (state.failureStage == ResultFailureStage.POST_INSTALL) {
            ResultCopy(
                R.string.result_post_install_failure_title,
                R.string.result_post_install_failure_description,
                "triangle_alert",
                InstallerColors.Warning,
                if (state.canEnterMaintenance) R.string.result_enter_maintenance else R.string.result_retry,
            ) {
                onIntent(if (state.canEnterMaintenance) InstallUiIntent.EnterMaintenance else InstallUiIntent.ReturnToSelection)
            }
        } else if (state.canEnterMaintenance) {
            ResultCopy(
                R.string.result_partial_failure_title,
                R.string.result_partial_failure_description,
                "triangle_alert",
                InstallerColors.Warning,
                R.string.result_enter_maintenance,
            ) { onIntent(InstallUiIntent.EnterMaintenance) }
        } else {
            ResultCopy(
                R.string.result_partial_failure_title,
                R.string.result_partial_failure_description,
                "triangle_alert",
                InstallerColors.Warning,
                R.string.result_retry,
            ) { onIntent(InstallUiIntent.ReturnToSelection) }
        }
        ResultKind.PAUSED -> ResultCopy(R.string.result_paused_title, R.string.result_paused_description, "circle_pause", InstallerColors.Warning, R.string.result_continue) { onIntent(InstallUiIntent.ContinueInstallation) }
        ResultKind.DOWNLOAD_FAILED -> ResultCopy(R.string.result_failure_title, null, "cloud_off", InstallerColors.Error, R.string.result_retry) { onIntent(InstallUiIntent.ReturnToSelection) }
        ResultKind.INSTALLATION_FAILED -> ResultCopy(R.string.result_failure_title, null, "triangle_alert", InstallerColors.Error, R.string.result_retry) { onIntent(InstallUiIntent.ReturnToSelection) }
        ResultKind.CONFIGURATION_FAILED -> ResultCopy(R.string.result_failure_title, null, "settings_2", InstallerColors.Warning, R.string.result_retry) { onIntent(InstallUiIntent.ReturnToSelection) }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = InstallerDimensions.PageHorizontalPadding),
    ) {
        TaskTopBar(title = stringResource(R.string.task_install))
        Spacer(modifier = Modifier.height(InstallerDimensions.SectionVerticalSpacing))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusIcon(
                name = copy.icon,
                contentDescription = stringResource(copy.title),
                tint = copy.tint,
            )
            Text(
                text = stringResource(copy.title),
                style = MaterialTheme.typography.headlineSmall,
                color = InstallerColors.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            copy.description?.let { description ->
                Text(
                    text = stringResource(description),
                    style = MaterialTheme.typography.bodyLarge,
                    color = InstallerColors.AuxiliaryWhite,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.failureReason
                ?.takeIf { state.componentResults.none { result -> result.errorReason != null } }
                ?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = InstallerColors.Warning,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // Keep the result heading visually separate from the first app card;
        // the card list owns its own scrolling area and must not touch the
        // status copy above it.
        Spacer(modifier = Modifier.height(InstallerDimensions.SectionVerticalSpacing))
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(InstallerDimensions.ContentSpacing),
        ) {
            if (state.componentResults.isNotEmpty()) {
                state.componentResults.forEachIndexed { index, result ->
                    AnimatedEntry(visible = true, index = index) {
                        InstallationResultRow(result = result)
                    }
                }
            }
        }
        PrimaryActionButton(text = stringResource(copy.actionText), onClick = copy.action, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
    }
}

private data class ResultCopy(
    val title: Int,
    val description: Int?,
    val icon: String,
    val tint: Color,
    val actionText: Int,
    val action: () -> Unit,
)

private enum class PhaseVisual {
    COMPLETED,
    CURRENT,
    PENDING,
}
