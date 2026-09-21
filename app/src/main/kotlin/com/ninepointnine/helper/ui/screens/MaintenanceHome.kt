package com.ninepointnine.helper.ui.screens

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import kotlinx.coroutines.delay
import com.ninepointnine.helper.R
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.domain.session.MaintenanceGroupId
import com.ninepointnine.helper.domain.session.MaintenanceActionStatus
import com.ninepointnine.helper.domain.session.MaintenanceApplicationActionId
import com.ninepointnine.helper.domain.device.MaintenanceAuthorizationState
import com.ninepointnine.helper.domain.device.ApplicationAuthorizationRequirement
import com.ninepointnine.helper.domain.device.ApplicationAuthorizationRequirementKind
import com.ninepointnine.helper.domain.session.MaintenanceAuthorizationFlowState
import com.ninepointnine.helper.domain.session.MaintenanceInventoryState
import com.ninepointnine.helper.domain.session.ResultKind
import com.ninepointnine.helper.domain.session.requiresConnectedDevice
import com.ninepointnine.helper.domain.session.isApplicationInstallation
import com.ninepointnine.helper.domain.artifact.KnownApplicationPackages
import com.ninepointnine.helper.ui.state.ResultFailureStage
import com.ninepointnine.helper.ui.components.AnimatedEntry
import com.ninepointnine.helper.ui.components.ComponentLogo
import com.ninepointnine.helper.ui.components.DividerLine
import com.ninepointnine.helper.ui.components.IconTextActionButton
import com.ninepointnine.helper.ui.components.InstallationResultRow
import com.ninepointnine.helper.ui.components.InstallationPhaseList
import com.ninepointnine.helper.ui.components.PressableSurface
import com.ninepointnine.helper.ui.components.PrimaryActionButton
import com.ninepointnine.helper.ui.components.StatusIcon
import com.ninepointnine.helper.ui.components.TaskTopBar
import com.ninepointnine.helper.ui.components.TopLevelFloatingNotice
import com.ninepointnine.helper.ui.state.InstallUiIntent
import com.ninepointnine.helper.ui.state.InstallUiState
import com.ninepointnine.helper.ui.state.MaintenanceFeedback
import com.ninepointnine.helper.ui.state.MaintenanceApplicationDetailsRow
import com.ninepointnine.helper.ui.state.MaintenanceApplicationFeedback
import com.ninepointnine.helper.ui.state.MaintenanceApplicationRow
import com.ninepointnine.helper.domain.device.ApplicationAutostartState
import com.ninepointnine.helper.ui.state.MaintenanceAuthorizationRow
import com.ninepointnine.helper.ui.state.MaintenanceInstallationOptionRow
import com.ninepointnine.helper.ui.state.MaintenanceInstallationSelectionUi
import com.ninepointnine.helper.ui.state.MaintenanceUpdateRow
import com.ninepointnine.helper.ui.state.failureReasonToUserMessage
import com.ninepointnine.helper.ui.theme.InstallerColors
import com.ninepointnine.helper.ui.theme.InstallerDimensions
import com.ninepointnine.helper.ui.theme.InstallerMotion

@Composable
fun MaintenanceHome(
    state: InstallUiState.Maintenance,
    onIntent: (InstallUiIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val overviewScrollState = rememberLazyListState()
    val leaveAction = {
        onIntent(InstallUiIntent.LeaveMaintenanceAction)
    }
    BackHandler(enabled = state.routeAction != null) {
        leaveAction()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val columns = maintenanceColumnCount(maxWidth.value)
        AnimatedContent(
            targetState = state.routeAction,
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
                    onAction = {
                        if (it == MaintenanceActionId.INSTALL_LOCAL_APPLICATION) {
                            // The local APK action is a source picker, not a
                            // maintenance adapter route. The selected URI is
                            // handed back to the domain only after the picker
                            // returns successfully.
                            onIntent(InstallUiIntent.PickLocalApk)
                        } else {
                            onIntent(InstallUiIntent.MaintenanceAction(it))
                        }
                    },
                    scrollState = overviewScrollState,
                )
            } else {
                when (action) {
                    MaintenanceActionId.CHECK_UPDATES -> MaintenanceUpdatesPage(
                        state = state,
                        onUpdate = { componentId ->
                            onIntent(InstallUiIntent.StartMaintenanceComponentUpdate(componentId))
                        },
                        onRetry = { onIntent(InstallUiIntent.MaintenanceAction(MaintenanceActionId.CHECK_UPDATES)) },
                        onBack = leaveAction,
                    )

                    MaintenanceActionId.REPAIR_CONFIGURATION -> MaintenanceAuthorizationPage(
                        state = state,
                        onIntent = onIntent,
                        onRetry = { onIntent(InstallUiIntent.MaintenanceAction(MaintenanceActionId.REPAIR_CONFIGURATION)) },
                        onBack = leaveAction,
                    )

                    MaintenanceActionId.MANAGE_APPS -> MaintenanceManageAppsPage(
                        state = state,
                        onIntent = onIntent,
                        onRetry = { onIntent(InstallUiIntent.MaintenanceAction(MaintenanceActionId.MANAGE_APPS)) },
                        onBack = leaveAction,
                    )

                    else -> if (action.isApplicationInstallation &&
                        state.installationSelection != null
                    ) {
                        MaintenanceInstallationSelectionPage(
                            selection = state.installationSelection,
                            onIntent = onIntent,
                            onBack = leaveAction,
                        )
                    } else {
                        MaintenanceActionPage(
                            action = action,
                            state = state,
                            onRetry = {
                                if (action == MaintenanceActionId.INSTALL_LOCAL_APPLICATION) {
                                    onIntent(InstallUiIntent.PickLocalApk)
                                } else {
                                    onIntent(InstallUiIntent.MaintenanceAction(action))
                                }
                            },
                            onBack = leaveAction,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MaintenanceUpdatesPage(
    state: InstallUiState.Maintenance,
    onUpdate: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val self = state.updateStatuses.filter { it.isSelf }
    val apps = state.updateStatuses.filterNot { it.isSelf }
    val visibleStatuses = if (state.connected) state.updateStatuses else self
    val checking = state.feedback?.actionId == MaintenanceActionId.CHECK_UPDATES &&
        state.feedback?.status == MaintenanceActionStatus.RUNNING
    val failed = state.feedback?.actionId == MaintenanceActionId.CHECK_UPDATES &&
        state.feedback?.status == MaintenanceActionStatus.FAILED
    val failureMessage = state.feedback?.let { feedback ->
        feedback.message ?: failureReasonToUserMessage(feedback.reasonCode)
    }
    // A running read-only check is still a navigable page. Consume the system
    // gesture and cancel the route through the shared maintenance owner.
    BackHandler(enabled = true, onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = InstallerDimensions.PageHorizontalPadding)
            .testTag("maintenance_updates_page"),
    ) {
        TaskTopBar(
            title = stringResource(R.string.maintenance_check_updates),
            iconName = actionIcon(MaintenanceActionId.CHECK_UPDATES),
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(InstallerDimensions.ContentSpacing),
        ) {
            item {
                Text(
                    text = stringResource(R.string.maintenance_update_self_section),
                    color = InstallerColors.AuxiliaryWhite,
                )
            }
            if (checking) {
                item { MaintenanceUpdateCheckingState(stringResource(R.string.maintenance_update_self_section)) }
            } else if (failed) {
                item {
                    MaintenanceEmptyState(
                        failureMessage ?: stringResource(R.string.maintenance_update_failed),
                    )
                }
            } else if (self.isEmpty()) {
                item { MaintenanceEmptyState(stringResource(R.string.maintenance_update_unavailable)) }
            } else {
                items(self, key = { "self:${it.componentId}" }) { row ->
                    MaintenanceUpdateRow(row) { onUpdate(row.componentId) }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.maintenance_update_apps_section),
                    color = InstallerColors.AuxiliaryWhite,
                )
            }
            if (checking) {
                item { MaintenanceUpdateCheckingState(stringResource(R.string.maintenance_update_apps_section)) }
            } else if (failed) {
                item {
                    MaintenanceEmptyState(
                        failureMessage ?: stringResource(R.string.maintenance_update_failed),
                    )
                }
            } else if (!state.connected) {
                item { MaintenanceEmptyState(stringResource(R.string.maintenance_update_car_disconnected)) }
            } else if (apps.isEmpty()) {
                item { MaintenanceEmptyState(stringResource(R.string.maintenance_update_unavailable)) }
            } else {
                items(apps, key = { it.componentId }) { row ->
                    MaintenanceUpdateRow(row) { onUpdate(row.componentId) }
                }
            }
        }
        if (checking) {
            Text(
                text = stringResource(R.string.maintenance_update_checking),
                color = InstallerColors.AuxiliaryWhite,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (failed && state.feedback?.retryable == true) {
            PrimaryActionButton(
                text = stringResource(R.string.maintenance_retry),
                onClick = onRetry,
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
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
    }
}

@Composable
private fun MaintenanceUpdateRow(row: MaintenanceUpdateRow, onUpdate: () -> Unit) {
    val actionable = row.state == com.ninepointnine.helper.domain.session.MaintenanceUpdateState.UPDATE_AVAILABLE
    PressableSurface(
        onClick = onUpdate,
        enabled = actionable,
        minHeight = 72.dp,
        containerColor = if (actionable) InstallerColors.White else InstallerColors.PageBlue,
        pressedColor = if (actionable) InstallerColors.AuxiliaryWhite else InstallerColors.PressedBlue,
        borderColor = if (actionable) InstallerColors.PageBlue else InstallerColors.WhiteBorder,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ComponentLogo(row.iconKey, row.displayName, size = 40.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    row.displayName,
                    color = if (actionable) InstallerColors.PageBlue else InstallerColors.White,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = updateVersionLabel(row),
                    color = if (actionable) InstallerColors.PressedBlue else updateVersionColor(row.state),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (actionable) {
                StatusIcon(
                    name = "download",
                    contentDescription = stringResource(R.string.maintenance_update_action),
                    tint = InstallerColors.PageBlue,
                    size = 24.dp,
                )
                Text(
                    text = stringResource(R.string.maintenance_update_action),
                    color = InstallerColors.PageBlue,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = updateStateLabel(row.state),
                    color = updateStateColor(row.state),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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
    Text(
        text = text,
        color = InstallerColors.AuxiliaryWhite,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = InstallerDimensions.ContentSpacing),
    )
}

@Composable
private fun MaintenanceUpdateCheckingState(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = InstallerColors.White,
            strokeWidth = 2.dp,
        )
        Text(
            text = stringResource(R.string.maintenance_update_node_checking, label),
            color = InstallerColors.White,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MaintenanceAuthorizationPage(
    state: InstallUiState.Maintenance,
    onIntent: (InstallUiIntent) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val checking = state.authorization.state == MaintenanceAuthorizationFlowState.CHECKING
    val actionRunning = state.feedback?.actionId == MaintenanceActionId.REPAIR_CONFIGURATION &&
        state.feedback?.status == MaintenanceActionStatus.RUNNING
    val authorizationRows = state.authorization.applications.ifEmpty {
        state.applications.map { app ->
            MaintenanceAuthorizationRow(
                componentId = app.componentId,
                packageName = app.packageName,
                versionLabel = app.versionLabel,
                state = if (checking) MaintenanceAuthorizationState.CHECKING else MaintenanceAuthorizationState.UNKNOWN,
                authorized = null,
            )
        }
    }
    val allAuthorized = authorizationRows.isNotEmpty() && authorizationRows.all { it.authorized == true }
    val completedRepair = state.authorization.state == MaintenanceAuthorizationFlowState.COMPLETED
    val inventoryLoading = state.applicationsState == MaintenanceInventoryState.LOADING
    val inventoryFailed = state.applicationsState == MaintenanceInventoryState.FAILED
    val hasApplications = authorizationRows.isNotEmpty() || state.applications.isNotEmpty()
    val retryableFailure = state.feedback?.actionId == MaintenanceActionId.REPAIR_CONFIGURATION &&
        state.feedback?.status == MaintenanceActionStatus.FAILED &&
        state.feedback?.retryable == true
    BackHandler(enabled = true, onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = InstallerDimensions.PageHorizontalPadding)
            .testTag("maintenance_authorization_page"),
    ) {
        TaskTopBar(
            title = stringResource(R.string.maintenance_repair),
            iconName = actionIcon(MaintenanceActionId.REPAIR_CONFIGURATION),
            onBack = onBack,
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
                if (inventoryLoading || checking || actionRunning) {
                    item { MaintenanceActionWaiting() }
                } else if (inventoryFailed) {
                    item {
                        MaintenanceEmptyState(
                            failureReasonToUserMessage(state.applicationsErrorReason)
                                ?: stringResource(R.string.maintenance_inventory_failed),
                        )
                    }
                } else if (!hasApplications) {
                    item { MaintenanceEmptyState(stringResource(R.string.maintenance_no_installed_apps)) }
                } else {
                    items(authorizationRows, key = { it.componentId }) { row ->
                        MaintenanceAuthorizationRowView(
                            row = row,
                            displayName = state.applications.firstOrNull { it.componentId == row.componentId }?.displayName
                                ?: row.componentId,
                            current = state.authorization.currentComponentId == row.componentId,
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(InstallerDimensions.SectionVerticalSpacing),
            ) {
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
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Keep the result copy away from the action area. An empty
                // inventory has no meaningful authorization write to repeat.
                if (hasApplications && !inventoryFailed) {
                    PrimaryActionButton(
                        text = stringResource(R.string.maintenance_reauthorize),
                        onClick = { onIntent(InstallUiIntent.MaintenanceAction(MaintenanceActionId.REPAIR_CONFIGURATION)) },
                        enabled = !checking && !actionRunning && !inventoryLoading,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (retryableFailure) {
                    PrimaryActionButton(
                        text = stringResource(R.string.maintenance_retry),
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
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
                if (!current && row.authorized != true) {
                    failureReasonToUserMessage(row.reasonCode)?.let { reason ->
                        Text(
                            text = reason,
                            color = InstallerColors.AuxiliaryWhite,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
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
    onIntent: (InstallUiIntent) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val applicationListState = rememberLazyListState()
    var expandedId by remember { mutableStateOf<String?>(null) }
    var pendingDestructive by remember { mutableStateOf<Pair<MaintenanceApplicationRow, MaintenanceApplicationActionId>?>(null) }
    var pendingAction by remember { mutableStateOf<Pair<MaintenanceApplicationRow, MaintenanceApplicationActionId>?>(null) }
    var pendingAutostart by remember { mutableStateOf<MaintenanceApplicationRow?>(null) }
    var pendingAutostartEnabled by remember { mutableStateOf(false) }
    val applicationActionsEnabled = applicationActionsEnabled(state.applicationAction)
    BackHandler(enabled = true, onBack = onBack)
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = InstallerDimensions.PageHorizontalPadding)
                .testTag("maintenance_manage_apps_page"),
        ) {
            TaskTopBar(
                title = stringResource(R.string.maintenance_manage_apps),
                iconName = actionIcon(MaintenanceActionId.MANAGE_APPS),
                onBack = onBack,
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = applicationListState,
                verticalArrangement = Arrangement.spacedBy(InstallerDimensions.ListSpacing),
            ) {
                if (state.applicationsState == MaintenanceInventoryState.LOADING ||
                    state.feedback?.status == MaintenanceActionStatus.RUNNING && state.applications.isEmpty()
                ) {
                    item { MaintenanceActionWaiting() }
                } else if (state.applicationsState == MaintenanceInventoryState.FAILED) {
                    item {
                        MaintenanceEmptyState(
                            failureReasonToUserMessage(state.applicationsErrorReason)
                                ?: stringResource(R.string.maintenance_inventory_failed),
                        )
                    }
                } else if (state.applications.isEmpty()) {
                    item { MaintenanceEmptyState(stringResource(R.string.maintenance_no_installed_apps)) }
                } else {
                    items(state.applications, key = { it.packageName }) { app ->
                        ManagedApplicationCard(
                            app = app,
                            expanded = expandedId == app.packageName,
                            enabled = applicationActionsEnabled,
                            onToggle = { expandedId = nextExpandedApplicationId(expandedId, app.packageName) },
                            onAction = { action ->
                                when (action) {
                                    MaintenanceApplicationActionId.CLEAR_DATA,
                                    MaintenanceApplicationActionId.UNINSTALL -> pendingDestructive = app to action
                                    MaintenanceApplicationActionId.AUTOSTART -> {
                                        pendingAutostart = app
                                        pendingAutostartEnabled = app.autostartState == ApplicationAutostartState.ENABLED
                                    }
                                    MaintenanceApplicationActionId.AUTHORIZE -> {
                                        pendingAction = app to action
                                        onIntent(
                                            InstallUiIntent.MaintenanceApplicationAction(
                                                app.packageName,
                                                MaintenanceApplicationActionId.INSPECT_AUTHORIZATION,
                                            ),
                                        )
                                    }
                                    MaintenanceApplicationActionId.DETAILS -> {
                                        pendingAction = app to action
                                        onIntent(InstallUiIntent.MaintenanceApplicationAction(app.packageName, action))
                                    }
                                    MaintenanceApplicationActionId.EXPORT_DIAGNOSTICS -> onIntent(
                                        InstallUiIntent.PickApplicationDiagnosticsDestination(app.packageName),
                                    )
                                    else -> onIntent(InstallUiIntent.MaintenanceApplicationAction(app.packageName, action))
                                }
                            },
                        )
                    }
                }
            }
            if (state.applicationsState == MaintenanceInventoryState.FAILED &&
                state.applicationsErrorRetryable
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.maintenance_retry),
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
        }
    }
    pendingDestructive?.let { (app, action) ->
        AlertDialog(
            onDismissRequest = { pendingDestructive = null },
            containerColor = InstallerColors.PageBlue,
            titleContentColor = InstallerColors.White,
            textContentColor = InstallerColors.AuxiliaryWhite,
            title = { Text(stringResource(if (action == MaintenanceApplicationActionId.CLEAR_DATA) R.string.maintenance_clear_data_title else R.string.maintenance_uninstall_title)) },
            text = { Text(stringResource(if (action == MaintenanceApplicationActionId.CLEAR_DATA) R.string.maintenance_clear_data_confirm else R.string.maintenance_uninstall_confirm, app.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDestructive = null
                    onIntent(InstallUiIntent.MaintenanceApplicationAction(app.packageName, action))
                }) { Text(stringResource(R.string.maintenance_confirm), color = InstallerColors.White) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDestructive = null }) {
                    Text(stringResource(R.string.maintenance_cancel), color = InstallerColors.White)
                }
            },
        )
    }
    pendingAutostart?.let { requestedApp ->
        val app = state.applications.singleOrNull { it.packageName == requestedApp.packageName } ?: requestedApp
        val supported = app.autostartState == ApplicationAutostartState.ENABLED ||
            app.autostartState == ApplicationAutostartState.DISABLED
        val actionFeedback = state.applicationAction?.takeIf {
            it.packageName == app.packageName && it.actionId == MaintenanceApplicationActionId.AUTOSTART
        }
        val actionRunning = actionFeedback?.status == MaintenanceActionStatus.RUNNING
        LaunchedEffect(app.autostartState, actionFeedback?.status) {
            if (!actionRunning) {
                pendingAutostartEnabled = app.autostartState == ApplicationAutostartState.ENABLED
            }
        }
        AlertDialog(
            onDismissRequest = { if (!actionRunning) pendingAutostart = null },
            modifier = Modifier.widthIn(max = 320.dp),
            containerColor = InstallerColors.PageBlue,
            titleContentColor = InstallerColors.White,
            textContentColor = InstallerColors.AuxiliaryWhite,
            title = { Text(stringResource(R.string.maintenance_autostart_dialog_title)) },
            text = {
                if (supported) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.maintenance_autostart_dialog_explanation),
                            color = InstallerColors.White,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            modifier = Modifier.testTag("maintenance_autostart_switch"),
                            checked = pendingAutostartEnabled,
                            enabled = !actionRunning,
                            onCheckedChange = { checked ->
                                pendingAutostartEnabled = checked
                                onIntent(
                                    InstallUiIntent.MaintenanceApplicationAction(
                                        app.packageName,
                                        MaintenanceApplicationActionId.AUTOSTART,
                                    ),
                                )
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = InstallerColors.White,
                                checkedTrackColor = InstallerColors.StatusGreen,
                                checkedBorderColor = InstallerColors.StatusGreen,
                                uncheckedThumbColor = InstallerColors.White,
                                uncheckedTrackColor = InstallerColors.StatusGray,
                                uncheckedBorderColor = InstallerColors.WhiteBorder,
                                disabledCheckedThumbColor = InstallerColors.White.copy(alpha = 0.65f),
                                disabledCheckedTrackColor = InstallerColors.StatusGreen.copy(alpha = 0.45f),
                                disabledUncheckedThumbColor = InstallerColors.White.copy(alpha = 0.65f),
                                disabledUncheckedTrackColor = InstallerColors.StatusGray.copy(alpha = 0.45f),
                            ),
                        )
                    }
                } else {
                    Text(
                        text = if (app.autostartState == ApplicationAutostartState.UNSUPPORTED) {
                            stringResource(R.string.maintenance_autostart_unsupported)
                        } else {
                            stringResource(R.string.maintenance_autostart_desktop_outdated)
                        },
                        color = InstallerColors.White,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("maintenance_autostart_finish"),
                    enabled = !actionRunning,
                    onClick = { pendingAutostart = null },
                ) {
                    Text(
                        stringResource(R.string.maintenance_finish),
                        color = if (actionRunning) InstallerColors.White.copy(alpha = 0.38f) else InstallerColors.White,
                    )
                }
            },
        )
    }
    pendingAction?.let { (app, action) ->
        val relevantActions = if (action == MaintenanceApplicationActionId.DETAILS) {
            setOf(MaintenanceApplicationActionId.DETAILS)
        } else {
            setOf(MaintenanceApplicationActionId.INSPECT_AUTHORIZATION, MaintenanceApplicationActionId.AUTHORIZE)
        }
        val dialogFeedback = state.applicationAction?.takeIf {
            it.packageName == app.packageName && it.actionId in relevantActions
        }
        val actionRunning = dialogFeedback?.status == MaintenanceActionStatus.RUNNING
        val actionFailed = dialogFeedback?.status == MaintenanceActionStatus.FAILED
        val authorizationPrimaryAction = authorizationDialogPrimaryAction(
            feedback = dialogFeedback,
            requirements = state.applicationAuthorizationRequirements,
        )
        AlertDialog(
            onDismissRequest = {
                if (!actionRunning && authorizationPrimaryAction in setOf(
                        AuthorizationDialogPrimaryAction.NONE,
                        AuthorizationDialogPrimaryAction.AUTHORIZE,
                    )
                ) {
                    pendingAction = null
                }
            },
            containerColor = InstallerColors.PageBlue,
            titleContentColor = InstallerColors.White,
            textContentColor = InstallerColors.AuxiliaryWhite,
            title = {
                Text(
                    if (action == MaintenanceApplicationActionId.DETAILS) {
                        stringResource(R.string.maintenance_details_title, app.displayName)
                    } else {
                        stringResource(R.string.maintenance_authorization_title, app.displayName)
                    },
                )
            },
            text = {
                if (action == MaintenanceApplicationActionId.DETAILS) {
                    val details = state.applicationDetails?.takeIf { applicationDetailsMatch(it, app) }
                    if (details == null) {
                        if (actionFailed) {
                            Text(
                                failureReasonToUserMessage(state.applicationAction?.reasonCode, app.displayName)
                                    ?: stringResource(R.string.maintenance_application_action_failed),
                                color = InstallerColors.Error,
                            )
                        } else MaintenanceActionWaiting()
                    } else {
                        Column(
                            modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ComponentLogo(
                                    app.iconKey,
                                    details.displayName,
                                    size = 48.dp,
                                    encodedPng = app.iconBase64,
                                )
                                Text(
                                    details.displayName,
                                    color = InstallerColors.White,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            InfoRow(stringResource(R.string.maintenance_detail_name), details.displayName)
                            InfoRow(stringResource(R.string.maintenance_detail_package), details.packageName)
                            InfoRow(stringResource(R.string.maintenance_detail_version), details.versionLabel ?: "-")
                            InfoRow(stringResource(R.string.maintenance_detail_version_code), details.versionCode?.toString() ?: "-")
                            InfoRow(stringResource(R.string.maintenance_detail_size), details.fileSizeBytes?.let(::formatBytes) ?: "-")
                            InfoRow(stringResource(R.string.maintenance_detail_install_time), formatEpoch(details.installTimeEpochMillis))
                            InfoRow(stringResource(R.string.maintenance_detail_update_time), formatEpoch(details.updateTimeEpochMillis))
                            InfoRow(stringResource(R.string.maintenance_detail_path), details.filePath ?: "-")
                            InfoRow(stringResource(R.string.maintenance_detail_uid), details.uid?.toString() ?: "-")
                        }
                    }
                } else {
                    val requirements = state.applicationAuthorizationRequirements
                    val inspecting = state.applicationAction?.packageName == app.packageName &&
                        state.applicationAction.actionId == MaintenanceApplicationActionId.INSPECT_AUTHORIZATION &&
                        state.applicationAction.status == MaintenanceActionStatus.RUNNING
                    val nextPendingIndex = requirements.indexOfFirst {
                        actionRunning && it.automaticallyActionable && !it.authorizationAttempted
                    }
                    Column(
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (inspecting || actionRunning && requirements.isEmpty()) {
                            MaintenanceActionWaiting()
                        } else if (actionFailed) {
                            Text(
                                failureReasonToUserMessage(state.applicationAction?.reasonCode, app.displayName)
                                    ?: stringResource(R.string.maintenance_authorization_failed),
                                color = InstallerColors.Error,
                            )
                        } else if (requirements.isEmpty()) {
                            Text(stringResource(R.string.maintenance_authorization_empty))
                        }
                        requirements.forEachIndexed { index, requirement ->
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(
                                        authorizationRequirementLabel(requirement),
                                        modifier = Modifier.weight(1f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    AnimatedContent(
                                        targetState = authorizationRequirementStatus(
                                            requirement = requirement,
                                            actionRunning = actionRunning,
                                            currentlyProcessing = index == nextPendingIndex,
                                        ),
                                        transitionSpec = { ContentTransform(fadeIn(InstallerMotion.stateChange()), fadeOut(InstallerMotion.stateChange())) },
                                        label = "authorizationRequirementStatus",
                                    ) { status ->
                                        Text(
                                            stringResource(status.labelRes),
                                            color = status.color,
                                        )
                                    }
                                }
                                requirement.reasonCode
                                    ?.takeIf { requirement.authorizationAttempted || !requirement.automaticallyActionable }
                                    ?.let { reasonCode ->
                                        Text(
                                            failureReasonToUserMessage(reasonCode, app.displayName)
                                                ?: stringResource(R.string.maintenance_authorization_unknown_reason),
                                            color = InstallerColors.AuxiliaryWhite,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                DividerLine()
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (action == MaintenanceApplicationActionId.DETAILS) {
                    TextButton(onClick = { pendingAction = null }) {
                        Text(stringResource(R.string.maintenance_confirm), color = InstallerColors.White)
                    }
                } else {
                    when (authorizationPrimaryAction) {
                        AuthorizationDialogPrimaryAction.AUTHORIZE -> TextButton(
                            onClick = {
                                onIntent(InstallUiIntent.MaintenanceApplicationAction(app.packageName, action))
                            },
                        ) {
                            Text(stringResource(R.string.maintenance_authorize), color = InstallerColors.White)
                        }

                        AuthorizationDialogPrimaryAction.ACKNOWLEDGE -> TextButton(
                            onClick = { pendingAction = null },
                        ) {
                            Text(stringResource(R.string.maintenance_install_done), color = InstallerColors.White)
                        }

                        AuthorizationDialogPrimaryAction.COMPLETE -> TextButton(
                            onClick = { pendingAction = null },
                        ) {
                            Text(stringResource(R.string.maintenance_finish), color = InstallerColors.White)
                        }

                        AuthorizationDialogPrimaryAction.NONE -> Unit
                    }
                }
            },
            dismissButton = {
                if (action != MaintenanceApplicationActionId.DETAILS &&
                    authorizationPrimaryAction == AuthorizationDialogPrimaryAction.AUTHORIZE
                ) {
                    TextButton(onClick = { pendingAction = null }) {
                        Text(stringResource(R.string.maintenance_cancel), color = InstallerColors.White)
                    }
                }
            },
        )
    }
    state.applicationAction
        ?.takeIf {
            it.actionId in setOf(
                MaintenanceApplicationActionId.START,
                MaintenanceApplicationActionId.FORCE_STOP,
                MaintenanceApplicationActionId.CLEAR_DATA,
                MaintenanceApplicationActionId.AUTHORIZE,
                MaintenanceApplicationActionId.AUTOSTART,
                MaintenanceApplicationActionId.UNINSTALL,
                MaintenanceApplicationActionId.EXPORT_DIAGNOSTICS,
            )
        }
        ?.let { feedback -> ApplicationActionToast(feedback, state.applications) }
}

@Composable
private fun ManagedApplicationCard(
    app: MaintenanceApplicationRow,
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onAction: (MaintenanceApplicationActionId) -> Unit,
) {
    PressableSurface(onClick = onToggle, enabled = enabled, minHeight = 76.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().animateContentSize(InstallerMotion.stateChange()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ComponentLogo(app.iconKey, app.displayName, size = 40.dp, encodedPng = app.iconBase64)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(app.displayName, color = InstallerColors.White, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = listOfNotNull(
                            app.versionLabel ?: app.versionCode?.let {
                                stringResource(R.string.maintenance_version_code_short, it)
                            },
                            app.packageName.ifBlank { null },
                        ).joinToString(" · ")
                            .ifBlank { stringResource(R.string.maintenance_version_unknown) },
                        color = InstallerColors.AuxiliaryWhite,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(InstallerMotion.stateChange()) + fadeIn(InstallerMotion.stateChange()),
                exit = shrinkVertically(InstallerMotion.stateChange()) + fadeOut(InstallerMotion.stateChange()),
            ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    managedApplicationActions(app.packageName).chunked(2).forEach { actions ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            actions.forEach { actionId ->
                                IconTextActionButton(
                                    text = applicationActionLabel(actionId),
                                    iconName = applicationActionIcon(actionId),
                                    onClick = { onAction(actionId) },
                                    modifier = if (actions.size == 1) {
                                        Modifier
                                            .fillMaxWidth()
                                            .testTag("maintenance_application_action_${actionId.name.lowercase()}")
                                    } else {
                                        Modifier
                                            .weight(1f)
                                            .testTag("maintenance_application_action_${actionId.name.lowercase()}")
                                    },
                                    enabled = enabled,
                                    iconTint = if (actionId == MaintenanceApplicationActionId.AUTOSTART) {
                                        autostartIconColor(app.autostartState)
                                    } else {
                                        InstallerColors.White
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal val MANAGED_APPLICATION_ACTIONS = listOf(
    MaintenanceApplicationActionId.START,
    MaintenanceApplicationActionId.FORCE_STOP,
    MaintenanceApplicationActionId.CLEAR_DATA,
    MaintenanceApplicationActionId.AUTHORIZE,
    MaintenanceApplicationActionId.UNINSTALL,
    MaintenanceApplicationActionId.DETAILS,
    MaintenanceApplicationActionId.AUTOSTART,
    MaintenanceApplicationActionId.EXPORT_DIAGNOSTICS,
)

internal fun managedApplicationActions(packageName: String): List<MaintenanceApplicationActionId> =
    if (KnownApplicationPackages.isApplicationOwnedAutostart(packageName)) {
        MANAGED_APPLICATION_ACTIONS.filterNot { it == MaintenanceApplicationActionId.AUTOSTART }
    } else {
        MANAGED_APPLICATION_ACTIONS
    }

internal fun nextExpandedApplicationId(currentId: String?, selectedId: String): String? =
    selectedId.takeUnless { it == currentId }

internal fun applicationActionsEnabled(feedback: MaintenanceApplicationFeedback?): Boolean =
    feedback?.status != MaintenanceActionStatus.RUNNING

internal enum class AuthorizationDialogPrimaryAction {
    NONE,
    AUTHORIZE,
    ACKNOWLEDGE,
    COMPLETE,
}

internal fun authorizationDialogPrimaryAction(
    feedback: MaintenanceApplicationFeedback?,
    requirements: List<ApplicationAuthorizationRequirement>,
): AuthorizationDialogPrimaryAction = when (feedback?.actionId) {
    MaintenanceApplicationActionId.INSPECT_AUTHORIZATION -> when (feedback.status) {
        MaintenanceActionStatus.RUNNING -> AuthorizationDialogPrimaryAction.NONE
        MaintenanceActionStatus.FAILED -> AuthorizationDialogPrimaryAction.ACKNOWLEDGE
        MaintenanceActionStatus.SUCCEEDED -> if (requirements.any { it.automaticallyActionable }) {
            AuthorizationDialogPrimaryAction.AUTHORIZE
        } else {
            AuthorizationDialogPrimaryAction.ACKNOWLEDGE
        }
    }

    MaintenanceApplicationActionId.AUTHORIZE -> when (feedback.status) {
        MaintenanceActionStatus.RUNNING -> AuthorizationDialogPrimaryAction.NONE
        MaintenanceActionStatus.SUCCEEDED,
        MaintenanceActionStatus.FAILED,
        -> AuthorizationDialogPrimaryAction.COMPLETE
    }

    else -> AuthorizationDialogPrimaryAction.NONE
}

@Composable
private fun applicationActionLabel(actionId: MaintenanceApplicationActionId): String = stringResource(
    when (actionId) {
        MaintenanceApplicationActionId.START -> R.string.maintenance_start
        MaintenanceApplicationActionId.AUTOSTART -> R.string.maintenance_autostart
        MaintenanceApplicationActionId.FORCE_STOP -> R.string.maintenance_force_stop
        MaintenanceApplicationActionId.CLEAR_DATA -> R.string.maintenance_clear_data
        MaintenanceApplicationActionId.AUTHORIZE -> R.string.maintenance_authorize
        MaintenanceApplicationActionId.UNINSTALL -> R.string.maintenance_uninstall
        MaintenanceApplicationActionId.DETAILS -> R.string.maintenance_details
        MaintenanceApplicationActionId.EXPORT_DIAGNOSTICS -> R.string.maintenance_export_logs
        MaintenanceApplicationActionId.INSPECT_AUTHORIZATION -> R.string.maintenance_authorize
    },
)

internal fun applicationActionIcon(actionId: MaintenanceApplicationActionId): String = when (actionId) {
    MaintenanceApplicationActionId.START -> "play"
    MaintenanceApplicationActionId.AUTOSTART -> "power"
    MaintenanceApplicationActionId.FORCE_STOP -> "square"
    MaintenanceApplicationActionId.CLEAR_DATA -> "eraser"
    MaintenanceApplicationActionId.AUTHORIZE,
    MaintenanceApplicationActionId.INSPECT_AUTHORIZATION -> "lock_keyhole"
    MaintenanceApplicationActionId.UNINSTALL -> "trash_2"
    MaintenanceApplicationActionId.DETAILS -> "info"
    MaintenanceApplicationActionId.EXPORT_DIAGNOSTICS -> "file_down"
}

internal fun autostartIconColor(state: ApplicationAutostartState): Color = when (state) {
    ApplicationAutostartState.ENABLED -> InstallerColors.StatusGreen
    ApplicationAutostartState.DISABLED -> InstallerColors.StatusRed
    ApplicationAutostartState.UNSUPPORTED,
    ApplicationAutostartState.UNAVAILABLE -> InstallerColors.StatusGray
}

@Composable
private fun authorizationRequirementLabel(
    requirement: ApplicationAuthorizationRequirement,
): String {
    return when (requirement.kind) {
        ApplicationAuthorizationRequirementKind.ACCESSIBILITY_SERVICE ->
            stringResource(R.string.maintenance_authorization_accessibility)
        ApplicationAuthorizationRequirementKind.NOTIFICATION_LISTENER_SERVICE ->
            stringResource(R.string.maintenance_authorization_notification)
        else -> authorizationPermissionLabel(requirement.permission)
    }
}

/** User-facing names for the complete local automatic-authorization whitelist. */
private fun authorizationPermissionLabel(permission: String): String = when (permission) {
    "android.permission.READ_CALENDAR" -> "读取日历"
    "android.permission.WRITE_CALENDAR" -> "修改日历"
    "android.permission.CAMERA" -> "使用相机"
    "android.permission.READ_CONTACTS" -> "读取联系人"
    "android.permission.WRITE_CONTACTS" -> "修改联系人"
    "android.permission.GET_ACCOUNTS" -> "读取设备账号"
    "android.permission.ACCESS_FINE_LOCATION" -> "精确位置信息"
    "android.permission.ACCESS_COARSE_LOCATION" -> "大致位置信息"
    "android.permission.RECORD_AUDIO" -> "使用麦克风"
    "android.permission.READ_PHONE_STATE" -> "读取手机状态"
    "android.permission.READ_PHONE_NUMBERS" -> "读取本机号码"
    "android.permission.CALL_PHONE" -> "拨打电话"
    "android.permission.ANSWER_PHONE_CALLS" -> "接听电话"
    "android.permission.ADD_VOICEMAIL" -> "添加语音信箱"
    "android.permission.USE_SIP" -> "使用网络电话"
    "android.permission.PROCESS_OUTGOING_CALLS" -> "读取拨出电话"
    "android.permission.BODY_SENSORS" -> "读取身体传感器"
    "android.permission.BODY_SENSORS_BACKGROUND" -> "后台读取身体传感器"
    "android.permission.SEND_SMS" -> "发送短信"
    "android.permission.RECEIVE_SMS" -> "接收短信"
    "android.permission.READ_SMS" -> "读取短信"
    "android.permission.RECEIVE_WAP_PUSH" -> "接收服务消息"
    "android.permission.RECEIVE_MMS" -> "接收彩信"
    "android.permission.READ_EXTERNAL_STORAGE" -> "读取文件"
    "android.permission.WRITE_EXTERNAL_STORAGE" -> "保存和修改文件"
    "android.permission.ACTIVITY_RECOGNITION" -> "识别身体活动"
    "android.permission.READ_MEDIA_IMAGES" -> "读取照片"
    "android.permission.READ_MEDIA_VIDEO" -> "读取视频"
    "android.permission.READ_MEDIA_AUDIO" -> "读取音频"
    "android.permission.SYSTEM_ALERT_WINDOW" -> "在其他应用上层显示"
    "android.permission.REQUEST_INSTALL_PACKAGES" -> "安装其他应用"
    "android.permission.PACKAGE_USAGE_STATS" -> "查看应用使用情况"
    "android.permission.WRITE_SETTINGS" -> "修改系统设置"
    else -> "应用所需权限"
}

private data class AuthorizationRequirementStatus(
    val labelRes: Int,
    val color: Color,
)

private fun authorizationRequirementStatus(
    requirement: ApplicationAuthorizationRequirement,
    actionRunning: Boolean,
    currentlyProcessing: Boolean,
): AuthorizationRequirementStatus = when {
    currentlyProcessing -> AuthorizationRequirementStatus(
        R.string.maintenance_authorization_processing,
        InstallerColors.White,
    )
    !requirement.automaticallyActionable -> AuthorizationRequirementStatus(
        R.string.maintenance_authorization_not_automatic,
        InstallerColors.AuxiliaryWhite,
    )
    actionRunning && !requirement.authorizationAttempted -> AuthorizationRequirementStatus(
        R.string.maintenance_authorization_waiting,
        InstallerColors.AuxiliaryWhite,
    )
    requirement.grantedAfter == true -> AuthorizationRequirementStatus(
        R.string.maintenance_authorization_active,
        InstallerColors.Success,
    )
    requirement.authorizationAttempted && requirement.grantedAfter == false -> AuthorizationRequirementStatus(
        R.string.maintenance_authorization_item_failed,
        InstallerColors.Error,
    )
    requirement.grantedAfter == false -> AuthorizationRequirementStatus(
        R.string.maintenance_authorization_not_granted,
        InstallerColors.Warning,
    )
    else -> AuthorizationRequirementStatus(
        R.string.maintenance_authorization_unknown,
        InstallerColors.Warning,
    )
}

@Composable
private fun ApplicationActionToast(
    feedback: MaintenanceApplicationFeedback,
    applications: List<MaintenanceApplicationRow>,
) {
    var visible by remember(feedback.packageName, feedback.actionId, feedback.status, feedback.resultCode, feedback.reasonCode) {
        mutableStateOf(true)
    }
    LaunchedEffect(feedback.packageName, feedback.actionId, feedback.status, feedback.resultCode, feedback.reasonCode) {
        visible = true
        if (feedback.status != MaintenanceActionStatus.RUNNING) {
            delay(2200L)
            visible = false
        }
    }
    val appName = applications.firstOrNull { it.packageName == feedback.packageName }?.displayName ?: feedback.packageName
    val successMessage = successfulApplicationActionMessage(feedback)
    val text = when {
        feedback.status == MaintenanceActionStatus.RUNNING &&
            feedback.actionId == MaintenanceApplicationActionId.AUTOSTART ->
            stringResource(R.string.maintenance_autostart_saving)
        feedback.status == MaintenanceActionStatus.RUNNING -> stringResource(R.string.maintenance_action_running, appName)
        feedback.actionId == MaintenanceApplicationActionId.AUTHORIZE &&
            feedback.status == MaintenanceActionStatus.SUCCEEDED &&
            feedback.resultCode == "authorization_partially_succeeded" ->
            failureReasonToUserMessage(feedback.reasonCode, appName)
                ?.let { reason -> stringResource(R.string.maintenance_authorization_partial_reason, reason) }
                ?: stringResource(R.string.maintenance_authorization_partial)
        successMessage != null -> stringResource(successMessage)
        feedback.actionId == MaintenanceApplicationActionId.AUTHORIZE &&
            feedback.status == MaintenanceActionStatus.FAILED ->
            failureReasonToUserMessage(feedback.reasonCode, appName)
                ?.let { reason -> stringResource(R.string.maintenance_authorization_failed_reason, reason) }
                ?: stringResource(R.string.maintenance_authorization_failed)
        feedback.status == MaintenanceActionStatus.FAILED ->
            failureReasonToUserMessage(feedback.reasonCode, appName)
                ?: stringResource(R.string.maintenance_application_action_failed)
        else -> stringResource(R.string.maintenance_application_action_failed)
    }
    TopLevelFloatingNotice {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(InstallerMotion.stateChange()) +
                slideInVertically(
                    animationSpec = InstallerMotion.stateChange(),
                    initialOffsetY = { height -> height / 2 },
                ),
            exit = fadeOut(InstallerMotion.stateChange()) +
                slideOutVertically(
                    animationSpec = InstallerMotion.stateChange(),
                    targetOffsetY = { height -> height / 2 },
                ),
        ) {
            Text(
                text = text,
                color = InstallerColors.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .testTag("application_action_notice")
                    .background(
                        color = Color(0xE61D232B),
                        shape = RoundedCornerShape(20.dp),
                    )
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

@StringRes
internal fun successfulApplicationActionMessage(feedback: MaintenanceApplicationFeedback): Int? {
    if (feedback.status != MaintenanceActionStatus.SUCCEEDED) return null
    return when (feedback.actionId) {
        MaintenanceApplicationActionId.START -> R.string.maintenance_start_success
        MaintenanceApplicationActionId.AUTOSTART -> R.string.maintenance_autostart_success
        MaintenanceApplicationActionId.FORCE_STOP -> R.string.maintenance_force_stop_success
        MaintenanceApplicationActionId.CLEAR_DATA -> R.string.maintenance_clear_data_success
        MaintenanceApplicationActionId.AUTHORIZE -> if (feedback.resultCode == "authorization_partially_succeeded") {
            R.string.maintenance_authorization_partial
        } else {
            R.string.maintenance_authorization_success
        }
        MaintenanceApplicationActionId.UNINSTALL -> R.string.maintenance_uninstall_success
        MaintenanceApplicationActionId.EXPORT_DIAGNOSTICS -> R.string.maintenance_export_logs_success
        MaintenanceApplicationActionId.INSPECT_AUTHORIZATION,
        MaintenanceApplicationActionId.DETAILS,
        -> null
    }
}

internal fun applicationDetailsMatch(
    details: MaintenanceApplicationDetailsRow,
    application: MaintenanceApplicationRow,
): Boolean = details.packageName == application.packageName

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = InstallerColors.AuxiliaryWhite, modifier = Modifier.weight(0.42f))
        Text(value, color = InstallerColors.White, modifier = Modifier.weight(0.58f))
    }
    DividerLine()
}

@Composable
private fun MaintenanceInstallationSelectionPage(
    selection: MaintenanceInstallationSelectionUi,
    onIntent: (InstallUiIntent) -> Unit,
    onBack: () -> Unit,
) {
    // An empty, successfully loaded catalog is terminal for this page too:
    // give the user a clear way out instead of presenting a disabled install
    // action that suggests there is work to do.
    val allInstalled = selection.options.isEmpty() || selection.options.all { it.installed }
    val installedOptions = selection.options.filter { it.installed }
    val notInstalledOptions = selection.options.filterNot { it.installed }
    val failed = selection.feedback?.status == MaintenanceActionStatus.FAILED
    BackHandler(enabled = true, onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = InstallerDimensions.PageHorizontalPadding)
            .testTag("maintenance_install_selection_page"),
    ) {
        TaskTopBar(
            title = stringResource(R.string.maintenance_install_file_manager),
            iconName = actionIcon(MaintenanceActionId.INSTALL_APPLICATIONS),
            onBack = onBack,
        )
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(InstallerDimensions.ListSpacing)) {
            if (failed) {
                item {
                    MaintenanceFeedbackBlock(
                        feedback = checkNotNull(selection.feedback),
                        applications = emptyList(),
                    )
                }
            }
            if (selection.options.isEmpty()) {
                item { MaintenanceEmptyState(stringResource(R.string.maintenance_no_installable_apps)) }
            }
            if (installedOptions.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.maintenance_installed_section),
                        color = InstallerColors.AuxiliaryWhite,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                items(installedOptions, key = { "installed:${it.componentId}" }) { option ->
                    MaintenanceInstallationOptionItem(option, selection, onIntent)
                }
            }
            if (notInstalledOptions.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.maintenance_not_installed_section),
                        color = InstallerColors.AuxiliaryWhite,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                items(notInstalledOptions, key = { "not-installed:${it.componentId}" }) { option ->
                    MaintenanceInstallationOptionItem(option, selection, onIntent)
                }
            }
        }
        PrimaryActionButton(
            text = stringResource(
                when {
                    allInstalled -> R.string.maintenance_install_done
                    failed -> R.string.maintenance_retry
                    else -> R.string.maintenance_install_start
                },
            ),
            onClick = {
                if (allInstalled) onBack()
                else onIntent(InstallUiIntent.StartMaintenanceInstallation)
            },
            enabled = allInstalled || selection.selectedComponentIds.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
    }
}

@Composable
private fun MaintenanceInstallationOptionItem(
    option: MaintenanceInstallationOptionRow,
    selection: MaintenanceInstallationSelectionUi,
    onIntent: (InstallUiIntent) -> Unit,
) {
    PressableSurface(
        onClick = {
            onIntent(
                InstallUiIntent.ToggleMaintenanceInstallationComponent(
                    option.componentId,
                    option.componentId !in selection.selectedComponentIds,
                ),
            )
        },
        enabled = !option.installed,
        minHeight = 76.dp,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ComponentLogo(option.iconKey, option.displayName, size = 40.dp)
            Column(
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
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
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (option.installed) {
                    StatusIcon(
                        name = "circle_check",
                        contentDescription = stringResource(R.string.maintenance_app_installed),
                        tint = InstallerColors.Success,
                        size = 22.dp,
                    )
                } else {
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
}

private fun updateStateLabel(state: com.ninepointnine.helper.domain.session.MaintenanceUpdateState): String = when (state) {
    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.CHECKING -> "检查中"
    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.CURRENT -> "已是最新"
    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.UPDATE_AVAILABLE -> "有更新"
    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.NOT_INSTALLED -> "未安装"
    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.DEVICE_DISCONNECTED -> "车机未连接"
    com.ninepointnine.helper.domain.session.MaintenanceUpdateState.UNAVAILABLE -> "版本号缺失"
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
    MaintenanceAuthorizationState.UNKNOWN -> "未读取"
    MaintenanceAuthorizationState.ERROR -> "授权状态读取失败"
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
    // Consume the gesture while an install is active. The install pipeline is
    // intentionally not cancellable from this page, but it must not minimize
    // the activity through the system back dispatcher.
    BackHandler(enabled = true, onBack = { if (canNavigateBack) onBack() })
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
            is InstallUiState.Installing -> MaintenanceInstallProgress(state = state)

            is InstallUiState.Result -> MaintenanceInstallResult(
                state = state,
                onIntent = onIntent,
                onBack = onBack,
                actionId = action,
            )

            else -> MaintenanceActionWaiting()
        }
    }
}

@Composable
private fun MaintenanceInstallProgress(
    state: InstallUiState.Installing,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
        Text(
            text = if (state.installationFlow == com.ninepointnine.helper.domain.session.InstallationFlow.SELF_UPDATE) {
                stringResource(R.string.self_update_preparing)
            } else {
                stringResource(R.string.installing_device, state.deviceName)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = InstallerColors.AuxiliaryWhite,
        )
        state.currentComponentName?.let { current ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = current, style = MaterialTheme.typography.headlineSmall, color = InstallerColors.White)
        }
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
        InstallationPhaseList(
            state = state,
            testTag = "maintenance_action_phases",
            modifier = Modifier
                .weight(1f),
        )
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
private fun MaintenanceInstallResult(
    state: InstallUiState.Result,
    onIntent: (InstallUiIntent) -> Unit,
    onBack: () -> Unit,
    actionId: MaintenanceActionId,
) {
    val retryIntent = when (state.installationFlow) {
        com.ninepointnine.helper.domain.session.InstallationFlow.MAINTENANCE_INSTALL ->
            InstallUiIntent.ReturnToMaintenanceInstallationSelection
        com.ninepointnine.helper.domain.session.InstallationFlow.LOCAL_APK_INSTALL ->
            InstallUiIntent.PickLocalApk
        com.ninepointnine.helper.domain.session.InstallationFlow.SELF_UPDATE ->
            InstallUiIntent.EnterMaintenance
        com.ninepointnine.helper.domain.session.InstallationFlow.INITIAL_INSTALL ->
            InstallUiIntent.ReturnToSelection
    }
    val (title, description, icon, tint, actionText, action) = when (state.kind) {
        ResultKind.SUCCESS -> ResultFlowCopy(
            R.string.result_success_title,
            R.string.result_success_description,
            "circle_check",
            InstallerColors.Success,
            R.string.result_enter_maintenance,
            InstallUiIntent.EnterMaintenance,
        )
        ResultKind.CONFIRMATION_PENDING -> ResultFlowCopy(
            R.string.result_confirmation_pending_title,
            R.string.result_confirmation_pending_description,
            "circle_alert",
            InstallerColors.Warning,
            if (state.canEnterMaintenance) R.string.result_enter_maintenance else R.string.result_retry,
            if (state.canEnterMaintenance) InstallUiIntent.EnterMaintenance else retryIntent,
        )
        ResultKind.PARTIAL_FAILURE -> if (state.failureStage == ResultFailureStage.POST_INSTALL) {
            ResultFlowCopy(
                R.string.result_post_install_failure_title,
                R.string.result_post_install_failure_description,
                "triangle_alert",
                InstallerColors.Warning,
                if (actionId.isApplicationInstallation || !state.canEnterMaintenance) {
                    R.string.result_retry
                } else {
                    R.string.result_enter_maintenance
                },
                if (actionId.isApplicationInstallation || !state.canEnterMaintenance) {
                    retryIntent
                } else {
                    InstallUiIntent.EnterMaintenance
                },
            )
        } else {
            ResultFlowCopy(
                R.string.result_partial_failure_title,
                R.string.result_partial_failure_description,
                "triangle_alert",
                InstallerColors.Warning,
                if (actionId.isApplicationInstallation || !state.canEnterMaintenance) {
                    R.string.result_retry
                } else {
                    R.string.result_enter_maintenance
                },
                if (actionId.isApplicationInstallation || !state.canEnterMaintenance) {
                    retryIntent
                } else {
                    InstallUiIntent.EnterMaintenance
                },
            )
        }
        ResultKind.PAUSED -> ResultFlowCopy(
            R.string.result_paused_title,
            R.string.result_paused_description,
            "circle_pause",
            InstallerColors.Warning,
            R.string.result_continue,
            InstallUiIntent.ContinueInstallation,
        )
        ResultKind.DOWNLOAD_FAILED -> ResultFlowCopy(
            R.string.result_failure_title,
            null,
            "cloud_off",
            InstallerColors.Error,
            R.string.result_retry,
            retryIntent,
        )
        ResultKind.INSTALLATION_FAILED -> ResultFlowCopy(
            R.string.result_failure_title,
            null,
            "triangle_alert",
            InstallerColors.Error,
            R.string.result_retry,
            retryIntent,
        )
        ResultKind.CONFIGURATION_FAILED -> ResultFlowCopy(
            R.string.result_failure_title,
            null,
            "settings_2",
            InstallerColors.Warning,
            R.string.result_retry,
            retryIntent,
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
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.headlineSmall,
            color = InstallerColors.White,
            textAlign = if (state.kind == ResultKind.SUCCESS || state.kind == ResultKind.PAUSED) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        description?.let { copy ->
            Text(
                text = stringResource(copy),
                style = MaterialTheme.typography.bodyLarge,
                color = InstallerColors.AuxiliaryWhite,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.failureReason
            ?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = InstallerColors.Warning,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(InstallerDimensions.SectionVerticalSpacing))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(InstallerDimensions.ListSpacing),
        ) {
            items(state.componentResults, key = { it.componentName }) { result ->
                InstallationResultRow(result = result)
            }
        }
        PrimaryActionButton(
            text = stringResource(actionText),
            onClick = {
                onIntent(action)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(InstallerDimensions.ContentSpacing))
    }
}

private data class ResultFlowCopy(
    val title: Int,
    val description: Int?,
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
                    onAction = onAction,
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
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val feedback = state.feedback?.takeIf { it.actionId == action }
    val canNavigateBack = true
    BackHandler(enabled = true, onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = InstallerDimensions.PageHorizontalPadding)
            .testTag("maintenance_action_page_${action.name.lowercase()}"),
    ) {
        TaskTopBar(
            title = stringResource(actionTitle(action)),
            onBack = onBack,
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
        if (feedback?.status == MaintenanceActionStatus.FAILED && feedback.retryable) {
            PrimaryActionButton(
                text = stringResource(R.string.maintenance_retry),
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            )
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
    val description = when (feedback.status) {
        MaintenanceActionStatus.FAILED ->
            feedback.message ?: failureReasonToUserMessage(feedback.reasonCode)
        else -> maintenanceFeedbackDescription(feedback)?.let { stringResource(it) }
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
                description?.let { message ->
                    Text(
                        text = message,
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
    val actions = maintenanceActions(group)
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

internal fun maintenanceActions(group: MaintenanceGroupId): List<MaintenanceActionId> =
    when (group) {
        MaintenanceGroupId.COMMON -> listOf(
            MaintenanceActionId.CHECK_UPDATES,
        )
        MaintenanceGroupId.APPS -> listOf(
            MaintenanceActionId.MANAGE_APPS,
            MaintenanceActionId.INSTALL_APPLICATIONS,
            MaintenanceActionId.INSTALL_LOCAL_APPLICATION,
        )
        MaintenanceGroupId.STORAGE -> listOf(
            MaintenanceActionId.EXPORT_DIAGNOSTICS,
        )
    }

private const val MAINTENANCE_TWO_COLUMN_MIN_WIDTH_DP = 600f

internal fun maintenanceColumnCount(availableWidthDp: Float): Int =
    if (availableWidthDp >= MAINTENANCE_TWO_COLUMN_MIN_WIDTH_DP) 2 else 1

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
    MaintenanceActionId.INSTALL_APPLICATIONS,
    MaintenanceActionId.INSTALL_FILE_MANAGER,
    -> R.string.maintenance_install_file_manager
    MaintenanceActionId.INSTALL_LOCAL_APPLICATION -> R.string.maintenance_install_local_application
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
    MaintenanceActionId.INSTALL_APPLICATIONS,
    MaintenanceActionId.INSTALL_FILE_MANAGER,
    -> R.string.maintenance_install_file_manager_description
    MaintenanceActionId.INSTALL_LOCAL_APPLICATION -> R.string.maintenance_install_local_application_description
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
    MaintenanceActionId.INSTALL_APPLICATIONS,
    MaintenanceActionId.INSTALL_FILE_MANAGER,
    -> "folder_plus"
    MaintenanceActionId.INSTALL_LOCAL_APPLICATION -> "file_plus"
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

    MaintenanceActionStatus.FAILED -> null
}
