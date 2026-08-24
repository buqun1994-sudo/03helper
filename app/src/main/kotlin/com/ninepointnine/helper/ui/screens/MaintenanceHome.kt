package com.ninepointnine.helper.ui.screens

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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ninepointnine.helper.R
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.domain.session.MaintenanceGroupId
import com.ninepointnine.helper.domain.session.MaintenanceActionStatus
import com.ninepointnine.helper.domain.session.MaintenanceApplicationActionId
import com.ninepointnine.helper.domain.device.MaintenanceAuthorizationState
import com.ninepointnine.helper.domain.session.MaintenanceAuthorizationFlowState
import com.ninepointnine.helper.domain.session.InstallPhase
import com.ninepointnine.helper.domain.session.ResultKind
import com.ninepointnine.helper.domain.session.requiresConnectedDevice
import com.ninepointnine.helper.ui.components.AnimatedEntry
import com.ninepointnine.helper.ui.components.ComponentLogo
import com.ninepointnine.helper.ui.components.DividerLine
import com.ninepointnine.helper.ui.components.IconTextActionButton
import com.ninepointnine.helper.ui.components.PressableSurface
import com.ninepointnine.helper.ui.components.PrimaryActionButton
import com.ninepointnine.helper.ui.components.StatusIcon
import com.ninepointnine.helper.ui.components.TaskTopBar
import com.ninepointnine.helper.ui.state.InstallUiIntent
import com.ninepointnine.helper.ui.state.InstallUiState
import com.ninepointnine.helper.ui.state.MaintenanceFeedback
import com.ninepointnine.helper.ui.state.MaintenanceApplicationDetailsRow
import com.ninepointnine.helper.ui.state.MaintenanceApplicationFeedback
import com.ninepointnine.helper.ui.state.MaintenanceApplicationRow
import com.ninepointnine.helper.ui.state.MaintenanceAuthorizationRow
import com.ninepointnine.helper.ui.state.MaintenanceInstallationSelectionUi
import com.ninepointnine.helper.ui.state.MaintenanceUpdateRow
import com.ninepointnine.helper.ui.theme.InstallerColors
import com.ninepointnine.helper.ui.theme.InstallerDimensions
import com.ninepointnine.helper.ui.theme.InstallerMotion
import kotlin.math.roundToInt

@Composable
fun MaintenanceHome(
    state: InstallUiState.Maintenance,
    onIntent: (InstallUiIntent) -> Unit,
    modifier: Modifier = Modifier,
    selectedAction: MaintenanceActionId? = null,
    onSelectedActionChange: (MaintenanceActionId?) -> Unit = {},
) {
    val overviewScrollState = rememberLazyListState()
    var applicationPage by remember { mutableStateOf(ApplicationMaintenancePage.LIST) }
    LaunchedEffect(selectedAction) { applicationPage = ApplicationMaintenancePage.LIST }
    val selectedActionRunning = selectedAction != null &&
        state.installationSelection == null &&
        ((state.feedback?.actionId == selectedAction &&
            state.feedback?.status == MaintenanceActionStatus.RUNNING) ||
            state.authorization.state == MaintenanceAuthorizationFlowState.CHECKING ||
            state.applicationAction?.status == MaintenanceActionStatus.RUNNING)
    BackHandler(enabled = selectedAction != null && !selectedActionRunning && applicationPage == ApplicationMaintenancePage.LIST) {
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
                    scrollState = overviewScrollState,
                )
            } else {
                when (action) {
                    MaintenanceActionId.CHECK_UPDATES -> MaintenanceUpdatesPage(
                        state = state,
                        onBeginUpdates = {
                            onSelectedActionChange(MaintenanceActionId.REINSTALL)
                            onIntent(InstallUiIntent.MaintenanceAction(MaintenanceActionId.REINSTALL))
                        },
                        onBack = { onSelectedActionChange(null) },
                    )

                    MaintenanceActionId.REPAIR_CONFIGURATION -> MaintenanceAuthorizationPage(
                        state = state,
                        onIntent = onIntent,
                        onBack = { onSelectedActionChange(null) },
                    )

                    MaintenanceActionId.MANAGE_APPS -> MaintenanceManageAppsPage(
                        state = state,
                        page = applicationPage,
                        onPageChange = { applicationPage = it },
                        onIntent = onIntent,
                        onBack = { onSelectedActionChange(null) },
                    )

                    else -> if (action == MaintenanceActionId.INSTALL_FILE_MANAGER &&
                        state.installationSelection != null
                    ) {
                        MaintenanceInstallationSelectionPage(
                            selection = state.installationSelection,
                            onIntent = onIntent,
                            onBack = { onSelectedActionChange(null) },
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
    }
}

private enum class ApplicationMaintenancePage {
    LIST,
    DETAILS,
    UNINSTALL,
}

@Composable
private fun MaintenanceUpdatesPage(
    state: InstallUiState.Maintenance,
    onBeginUpdates: () -> Unit,
    onBack: () -> Unit,
) {
    val self = state.updateStatuses.filter { it.isSelf }
    val apps = state.updateStatuses.filterNot { it.isSelf }
    val visibleStatuses = if (state.connected) state.updateStatuses else self
    val hasUpdate = visibleStatuses.any {
        it.state == com.ninepointnine.helper.domain.session.MaintenanceUpdateState.UPDATE_AVAILABLE
    }
    BackHandler(enabled = state.feedback?.status != MaintenanceActionStatus.RUNNING, onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = InstallerDimensions.PageHorizontalPadding)
            .testTag("maintenance_updates_page"),
    ) {
        TaskTopBar(
            title = stringResource(R.string.maintenance_check_updates),
            iconName = actionIcon(MaintenanceActionId.CHECK_UPDATES),
            onBack = onBack.takeIf { state.feedback?.status != MaintenanceActionStatus.RUNNING },
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(InstallerDimensions.ContentSpacing),
        ) {
            item { Text(stringResource(R.string.maintenance_update_self_section), color = InstallerColors.AuxiliaryWhite) }
            if (self.isEmpty()) {
                item { MaintenanceEmptyState(stringResource(R.string.maintenance_update_unavailable)) }
            } else {
                items(self, key = { "self:${it.componentId}" }) { row -> MaintenanceUpdateRow(row) }
            }
            item { Text(stringResource(R.string.maintenance_update_apps_section), color = InstallerColors.AuxiliaryWhite) }
            if (!state.connected) {
                item { MaintenanceEmptyState(stringResource(R.string.maintenance_update_car_disconnected)) }
            } else if (apps.isEmpty()) {
                item { MaintenanceEmptyState(stringResource(R.string.maintenance_update_unavailable)) }
            } else {
                items(apps, key = { it.componentId }) { row -> MaintenanceUpdateRow(row) }
            }
        }
        if (hasUpdate) {
            PrimaryActionButton(
                text = stringResource(R.string.maintenance_update_all),
                onClick = onBeginUpdates,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (
            state.feedback?.status == MaintenanceActionStatus.SUCCEEDED &&
            visibleStatuses.isNotEmpty() &&
            visibleStatuses.all {
                it.state == com.ninepointnine.helper.domain.session.MaintenanceUpdateState.CURRENT
            }
        ) {
            Text(
                text = stringResource(R.string.maintenance_update_current),
                color = InstallerColors.Success,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
    }
}

@Composable
private fun MaintenanceUpdateRow(row: MaintenanceUpdateRow) {
    PressableSurface(onClick = {}, enabled = false, minHeight = 72.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ComponentLogo(row.iconKey, row.displayName, size = 40.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(row.displayName, color = InstallerColors.White, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = updateVersionLabel(row),
                    color = updateVersionColor(row.state),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = updateStateLabel(row.state),
                color = updateStateColor(row.state),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun updateVersionLabel(row: MaintenanceUpdateRow): String = when (row.state) {
    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.UPDATE_AVAILABLE ->
        row.versionLabel?.let(::formatVersionLabel)
            ?: row.installedVersionLabel?.let { installed ->
                stringResource(R.string.maintenance_installed_version, formatVersionLabel(installed))
            }
            ?: stringResource(R.string.maintenance_version_unknown)

    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.CURRENT ->
        row.installedVersionLabel?.let { installed ->
            stringResource(R.string.maintenance_installed_version, formatVersionLabel(installed))
        }
            ?: row.versionLabel?.let(::formatVersionLabel)
            ?: stringResource(R.string.maintenance_version_unknown)

    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.NOT_INSTALLED ->
        row.versionLabel?.let(::formatVersionLabel) ?: stringResource(R.string.maintenance_app_not_installed)

    else -> row.versionLabel?.let(::formatVersionLabel)
        ?: row.installedVersionLabel?.let { installed ->
            stringResource(R.string.maintenance_installed_version, formatVersionLabel(installed))
        }
        ?: stringResource(R.string.maintenance_version_unknown)
}

@Composable
private fun MaintenanceEmptyState(text: String) {
    Text(text = text, color = InstallerColors.AuxiliaryWhite, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun MaintenanceAuthorizationPage(
    state: InstallUiState.Maintenance,
    onIntent: (InstallUiIntent) -> Unit,
    onBack: () -> Unit,
) {
    val checking = state.authorization.state == MaintenanceAuthorizationFlowState.CHECKING
    val actionRunning = state.feedback?.actionId == MaintenanceActionId.REPAIR_CONFIGURATION &&
        state.feedback?.status == MaintenanceActionStatus.RUNNING
    val allAuthorized = state.authorization.applications.isNotEmpty() &&
        state.authorization.applications.all { it.authorized == true }
    val completedRepair = state.feedback?.resultCode == "authorization_repaired"
    BackHandler(enabled = !checking && !actionRunning, onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = InstallerDimensions.PageHorizontalPadding)
            .testTag("maintenance_authorization_page"),
    ) {
        TaskTopBar(
            title = stringResource(R.string.maintenance_repair),
            iconName = actionIcon(MaintenanceActionId.REPAIR_CONFIGURATION),
            onBack = onBack.takeIf { !checking && !actionRunning },
        )
        if (!state.connected) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MaintenanceEmptyState(stringResource(R.string.maintenance_authorization_disconnected))
            }
            PrimaryActionButton(
                text = stringResource(R.string.maintenance_confirm),
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(InstallerDimensions.ListSpacing),
            ) {
                items(state.authorization.applications, key = { it.componentId }) { row ->
                    MaintenanceAuthorizationRowView(
                        row = row,
                        displayName = state.applications.firstOrNull { it.componentId == row.componentId }?.displayName
                            ?: row.componentId,
                        current = state.authorization.currentComponentId == row.componentId,
                    )
                }
                if (checking && state.authorization.applications.isEmpty()) {
                    item { MaintenanceActionWaiting() }
                }
            }
            if (allAuthorized && state.feedback?.status == MaintenanceActionStatus.SUCCEEDED) {
                Text(
                    text = stringResource(
                        if (completedRepair) {
                            R.string.maintenance_authorization_complete
                        } else {
                            R.string.maintenance_authorization_check_complete
                        },
                    ),
                    color = InstallerColors.Success,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PrimaryActionButton(
                text = stringResource(R.string.maintenance_reauthorize),
                onClick = { onIntent(InstallUiIntent.MaintenanceAction(MaintenanceActionId.REPAIR_CONFIGURATION)) },
                enabled = !checking && !actionRunning,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
    }
}

@Composable
private fun MaintenanceAuthorizationRowView(
    row: MaintenanceAuthorizationRow,
    displayName: String,
    current: Boolean,
) {
    PressableSurface(onClick = {}, enabled = false, minHeight = 76.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ComponentLogo(row.componentId, displayName, size = 40.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(displayName, color = InstallerColors.White, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = listOfNotNull(row.versionLabel, row.packageName.ifBlank { null }).joinToString(" · ")
                        .ifBlank { stringResource(R.string.maintenance_version_unknown) },
                    color = InstallerColors.AuxiliaryWhite,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (current) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = InstallerColors.White, strokeWidth = 2.dp)
            } else {
                Text(
                    text = authorizationStateLabel(row.state),
                    color = if (row.authorized == true) InstallerColors.Success else InstallerColors.Warning,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun MaintenanceManageAppsPage(
    state: InstallUiState.Maintenance,
    page: ApplicationMaintenancePage,
    onPageChange: (ApplicationMaintenancePage) -> Unit,
    onIntent: (InstallUiIntent) -> Unit,
    onBack: () -> Unit,
) {
    val selectedId = state.applicationAction?.componentId
    val selectedApplication = state.applications.firstOrNull { it.componentId == selectedId }
    val applicationListState = rememberLazyListState()
    var pendingUninstall by remember { mutableStateOf<MaintenanceApplicationRow?>(null) }
    val appActionRunning = state.applicationAction?.status == MaintenanceActionStatus.RUNNING
    BackHandler(enabled = !appActionRunning, onBack = {
        if (page == ApplicationMaintenancePage.LIST) onBack() else onPageChange(ApplicationMaintenancePage.LIST)
    })
    when (page) {
        ApplicationMaintenancePage.LIST -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = InstallerDimensions.PageHorizontalPadding)
                    .testTag("maintenance_manage_apps_page"),
            ) {
                TaskTopBar(
                    title = stringResource(R.string.maintenance_manage_apps),
                    iconName = actionIcon(MaintenanceActionId.MANAGE_APPS),
                    onBack = onBack.takeIf { !appActionRunning },
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = applicationListState,
                    verticalArrangement = Arrangement.spacedBy(InstallerDimensions.ListSpacing),
                ) {
                    if (state.feedback?.status == MaintenanceActionStatus.RUNNING && state.applications.isEmpty()) {
                        item { MaintenanceActionWaiting() }
                    } else {
                        items(state.applications, key = { it.componentId }) { app ->
                            ManagedApplicationCard(
                                app = app,
                                action = state.applicationAction,
                                onStart = { onIntent(InstallUiIntent.MaintenanceApplicationAction(app.componentId, MaintenanceApplicationActionId.START)) },
                                onForceStop = { onIntent(InstallUiIntent.MaintenanceApplicationAction(app.componentId, MaintenanceApplicationActionId.FORCE_STOP)) },
                                onUninstall = { pendingUninstall = app },
                                onDetails = {
                                    onPageChange(ApplicationMaintenancePage.DETAILS)
                                    onIntent(InstallUiIntent.MaintenanceApplicationAction(app.componentId, MaintenanceApplicationActionId.DETAILS))
                                },
                            )
                        }
                    }
                    state.applicationAction?.takeIf { it.actionId == MaintenanceApplicationActionId.START || it.actionId == MaintenanceApplicationActionId.FORCE_STOP }
                        ?.let { feedback -> item { ApplicationActionToast(feedback, state.applications) } }
                }
                Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
            }
        }

        ApplicationMaintenancePage.DETAILS -> {
            MaintenanceApplicationDetailsPage(
                details = state.applicationDetails?.takeIf { it.componentId == selectedId },
                fallbackName = selectedApplication?.displayName ?: selectedId.orEmpty(),
                loading = appActionRunning,
                onBack = { onPageChange(ApplicationMaintenancePage.LIST) },
            )
        }

        ApplicationMaintenancePage.UNINSTALL -> {
            MaintenanceUninstallPage(
                application = selectedApplication,
                feedback = state.applicationAction,
                onComplete = {
                    onPageChange(ApplicationMaintenancePage.LIST)
                    onIntent(InstallUiIntent.MaintenanceAction(MaintenanceActionId.MANAGE_APPS))
                },
                onBack = { onPageChange(ApplicationMaintenancePage.LIST) },
            )
        }
    }
    pendingUninstall?.let { app ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            containerColor = InstallerColors.PageBlue,
            titleContentColor = InstallerColors.White,
            textContentColor = InstallerColors.AuxiliaryWhite,
            title = { Text(stringResource(R.string.maintenance_uninstall_title)) },
            text = { Text(app.displayName) },
            confirmButton = {
                TextButton(onClick = {
                    pendingUninstall = null
                    onPageChange(ApplicationMaintenancePage.UNINSTALL)
                    onIntent(InstallUiIntent.MaintenanceApplicationAction(app.componentId, MaintenanceApplicationActionId.UNINSTALL))
                }) { Text(stringResource(R.string.maintenance_confirm), color = InstallerColors.White) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) {
                    Text(stringResource(R.string.maintenance_cancel), color = InstallerColors.White)
                }
            },
        )
    }
}

@Composable
private fun ManagedApplicationCard(
    app: MaintenanceApplicationRow,
    action: MaintenanceApplicationFeedback?,
    onStart: () -> Unit,
    onForceStop: () -> Unit,
    onUninstall: () -> Unit,
    onDetails: () -> Unit,
) {
    PressableSurface(onClick = {}, enabled = false, minHeight = 132.dp) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ComponentLogo(app.iconKey, app.displayName, size = 40.dp)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(app.displayName, color = InstallerColors.White, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = listOfNotNull(app.versionLabel, app.packageName.ifBlank { null }).joinToString(" · ")
                            .ifBlank { stringResource(R.string.maintenance_version_unknown) },
                        color = InstallerColors.AuxiliaryWhite,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(if (app.installed) R.string.maintenance_app_installed else R.string.maintenance_app_not_installed),
                    color = if (app.installed) InstallerColors.Success else InstallerColors.Warning,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                IconTextActionButton(stringResource(R.string.maintenance_start), "play", onStart, Modifier.weight(1f), app.installed)
                IconTextActionButton(stringResource(R.string.maintenance_force_stop), "square", onForceStop, Modifier.weight(1f), app.installed)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                IconTextActionButton(stringResource(R.string.maintenance_uninstall), "trash_2", onUninstall, Modifier.weight(1f), app.installed)
                IconTextActionButton(stringResource(R.string.maintenance_details), "info", onDetails, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ApplicationActionToast(
    feedback: MaintenanceApplicationFeedback,
    applications: List<MaintenanceApplicationRow>,
) {
    val appName = applications.firstOrNull { it.componentId == feedback.componentId }?.displayName ?: feedback.componentId
    val text = when {
        feedback.status == MaintenanceActionStatus.RUNNING -> stringResource(R.string.maintenance_action_running, appName)
        feedback.actionId == MaintenanceApplicationActionId.START && feedback.status == MaintenanceActionStatus.SUCCEEDED -> stringResource(R.string.maintenance_start_success)
        feedback.actionId == MaintenanceApplicationActionId.FORCE_STOP && feedback.status == MaintenanceActionStatus.SUCCEEDED -> stringResource(R.string.maintenance_force_stop_success)
        else -> stringResource(R.string.maintenance_application_action_failed)
    }
    Text(text = text, color = if (feedback.status == MaintenanceActionStatus.SUCCEEDED) InstallerColors.Success else InstallerColors.Warning)
}

@Composable
private fun MaintenanceApplicationDetailsPage(
    details: com.ninepointnine.helper.ui.state.MaintenanceApplicationDetailsRow?,
    fallbackName: String,
    loading: Boolean,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = InstallerDimensions.PageHorizontalPadding)
            .testTag("maintenance_application_details_page"),
    ) {
        TaskTopBar(
            title = stringResource(R.string.maintenance_details),
            iconName = "info",
            onBack = onBack.takeIf { !loading },
        )
        if (details == null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loading) MaintenanceActionWaiting()
                else MaintenanceEmptyState(fallbackName)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { InfoRow(stringResource(R.string.maintenance_detail_name), details.displayName) }
                item { InfoRow(stringResource(R.string.maintenance_detail_package), details.packageName) }
                item { InfoRow(stringResource(R.string.maintenance_detail_version), details.versionLabel ?: "-") }
                item { InfoRow(stringResource(R.string.maintenance_detail_version_code), details.versionCode?.toString() ?: "-") }
                item { InfoRow(stringResource(R.string.maintenance_detail_size), details.fileSizeBytes?.let(::formatBytes) ?: "-") }
                item { InfoRow(stringResource(R.string.maintenance_detail_install_time), formatEpoch(details.installTimeEpochMillis)) }
                item { InfoRow(stringResource(R.string.maintenance_detail_update_time), formatEpoch(details.updateTimeEpochMillis)) }
                item { InfoRow(stringResource(R.string.maintenance_detail_path), details.filePath ?: "-") }
                item { InfoRow(stringResource(R.string.maintenance_detail_uid), details.uid?.toString() ?: "-") }
            }
        }
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = InstallerColors.AuxiliaryWhite, modifier = Modifier.weight(0.42f))
        Text(value, color = InstallerColors.White, modifier = Modifier.weight(0.58f))
    }
    DividerLine()
}

@Composable
private fun MaintenanceUninstallPage(
    application: MaintenanceApplicationRow?,
    feedback: MaintenanceApplicationFeedback?,
    onComplete: () -> Unit,
    onBack: () -> Unit,
) {
    val success = feedback?.actionId == MaintenanceApplicationActionId.UNINSTALL &&
        feedback.status == MaintenanceActionStatus.SUCCEEDED
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = InstallerDimensions.PageHorizontalPadding),
    ) {
        TaskTopBar(
            title = application?.displayName ?: stringResource(R.string.maintenance_uninstall_title),
            iconName = "trash_2",
            onBack = onBack.takeIf { !success && feedback?.status != MaintenanceActionStatus.RUNNING },
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (feedback?.status == MaintenanceActionStatus.RUNNING) {
                MaintenanceActionWaiting()
            } else if (success) {
                Text(stringResource(R.string.maintenance_uninstall_success), color = InstallerColors.Success)
            } else if (feedback?.status == MaintenanceActionStatus.FAILED) {
                Text(stringResource(R.string.maintenance_application_action_failed), color = InstallerColors.Error)
            }
        }
        if (success) {
            PrimaryActionButton(
                text = stringResource(R.string.maintenance_uninstall_done),
                onClick = onComplete,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
    }
}

@Composable
private fun MaintenanceInstallationSelectionPage(
    selection: MaintenanceInstallationSelectionUi,
    onIntent: (InstallUiIntent) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = InstallerDimensions.PageHorizontalPadding)
            .testTag("maintenance_install_selection_page"),
    ) {
        TaskTopBar(
            title = stringResource(R.string.maintenance_install_file_manager),
            iconName = actionIcon(MaintenanceActionId.INSTALL_FILE_MANAGER),
            onBack = onBack,
        )
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(InstallerDimensions.ListSpacing)) {
            items(selection.options, key = { it.componentId }) { option ->
                PressableSurface(
                    onClick = {
                        onIntent(
                            InstallUiIntent.ToggleMaintenanceInstallationComponent(
                                option.componentId,
                                option.componentId !in selection.selectedComponentIds,
                            ),
                        )
                    },
                    minHeight = 76.dp,
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ComponentLogo(option.iconKey, option.displayName, size = 40.dp)
                        Column(modifier = Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(option.displayName, color = InstallerColors.White)
                            Text(
                                listOfNotNull(option.versionLabel, option.sizeLabel).joinToString(" · "),
                                color = InstallerColors.AuxiliaryWhite,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                stringResource(if (option.installed) R.string.maintenance_app_installed else R.string.maintenance_app_not_installed),
                                color = if (option.installed) InstallerColors.Success else InstallerColors.Warning,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Checkbox(
                            checked = option.componentId in selection.selectedComponentIds,
                            onCheckedChange = { checked ->
                                onIntent(InstallUiIntent.ToggleMaintenanceInstallationComponent(option.componentId, checked))
                            },
                            enabled = !option.required,
                        )
                    }
                }
            }
        }
        PrimaryActionButton(
            text = stringResource(R.string.maintenance_install_start),
            onClick = { onIntent(InstallUiIntent.StartMaintenanceInstallation) },
            enabled = selection.selectedComponentIds.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
    }
}

private fun updateStateLabel(state: com.ninepointnine.helper.domain.session.MaintenanceUpdateState): String = when (state) {
    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.CHECKING -> "检查中"
    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.CURRENT -> "已是最新"
    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.UPDATE_AVAILABLE -> "有更新"
    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.NOT_INSTALLED -> "未安装"
    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.DEVICE_DISCONNECTED -> "车机未连接"
    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.UNAVAILABLE -> "暂不可用"
}

private fun updateStateColor(state: com.ninepointnine.helper.domain.session.MaintenanceUpdateState) = when (state) {
    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.UPDATE_AVAILABLE -> InstallerColors.Warning
    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.CURRENT -> InstallerColors.Success

    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.NOT_INSTALLED,
    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.DEVICE_DISCONNECTED,
    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.UNAVAILABLE,
    -> InstallerColors.Warning

    else -> InstallerColors.AuxiliaryWhite
}

private fun updateVersionColor(state: com.ninepointnine.helper.domain.session.MaintenanceUpdateState) = when (state) {
    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.UPDATE_AVAILABLE -> InstallerColors.Success
    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.NOT_INSTALLED,
    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.DEVICE_DISCONNECTED,
    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.UNAVAILABLE,
    -> InstallerColors.Warning

    else -> InstallerColors.AuxiliaryWhite
}

private fun formatVersionLabel(value: String): String {
    val normalized = value.trim().removePrefix("v").removePrefix("V")
    return if (normalized.isEmpty()) "v-" else "v$normalized"
}

private fun authorizationStateLabel(state: MaintenanceAuthorizationState): String = when (state) {
    MaintenanceAuthorizationState.CHECKING -> "检查中"
    MaintenanceAuthorizationState.AUTHORIZED -> "授权正常"
    MaintenanceAuthorizationState.NOT_AUTHORIZED -> "未授权"
    MaintenanceAuthorizationState.UNKNOWN -> "待检查"
    MaintenanceAuthorizationState.ERROR -> "检查失败"
}

private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024L -> "%.1f MB".format(java.util.Locale.ROOT, value / 1024f / 1024f)
    value >= 1024L -> "%.1f KB".format(java.util.Locale.ROOT, value / 1024f)
    else -> "$value B"
}

private fun formatEpoch(value: Long?): String = value?.let {
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(it))
} ?: "-"

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
            iconName = actionIcon(action),
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
    scrollState: LazyListState,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = InstallerDimensions.PageHorizontalPadding)
            .testTag("maintenance_home"),
    ) {
        TaskTopBar(title = stringResource(R.string.task_maintenance), iconName = "car_front")
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
            state = scrollState,
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
            iconName = actionIcon(action),
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .testTag("maintenance_action_content"),
            verticalArrangement = Arrangement.spacedBy(InstallerDimensions.ContentSpacing),
        ) {
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
        }
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
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
            MaintenanceActionId.REPAIR_CONFIGURATION,
        )
        MaintenanceGroupId.APPS -> listOf(
            MaintenanceActionId.MANAGE_APPS,
            MaintenanceActionId.INSTALL_FILE_MANAGER,
        )
        MaintenanceGroupId.STORAGE -> listOf(
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
                            enabled = maintenanceActionEnabled(action, connected, busy) ||
                                (!connected && !busy && action == MaintenanceActionId.REPAIR_CONFIGURATION),
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
