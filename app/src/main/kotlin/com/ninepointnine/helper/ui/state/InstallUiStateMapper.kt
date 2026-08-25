package com.ninepointnine.helper.ui.state

import com.ninepointnine.helper.domain.session.ComponentDescriptor
import com.ninepointnine.helper.domain.session.ComponentCompatibility
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.session.FailureCategory
import com.ninepointnine.helper.domain.session.InstallPhase
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.domain.session.InstallationSessionState
import com.ninepointnine.helper.domain.session.ResultKind
import com.ninepointnine.helper.domain.session.MaintenanceApplicationActionId
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.domain.session.MaintenanceActionStatus
import com.ninepointnine.helper.domain.session.MaintenanceInventoryState
import com.ninepointnine.helper.domain.device.AuthorizationPlanFactory

object InstallUiStateMapper {
    fun map(snapshot: InstallationSessionSnapshot): InstallUiState {
        if (
            snapshot.maintenanceReconnectPending &&
            snapshot.state in setOf(InstallationSessionState.DISCOVERING, InstallationSessionState.CONNECTING)
        ) {
            return maintenanceState(snapshot, reconnecting = true)
        }
        if (
            snapshot.installationReconnectPending &&
            snapshot.state in setOf(InstallationSessionState.DISCOVERING, InstallationSessionState.CONNECTING)
        ) {
            return installingState(
                snapshot.copy(state = snapshot.checkpoint?.state ?: InstallationSessionState.RESOLVING_SOURCE),
            )
        }
        return when (snapshot.state) {
        InstallationSessionState.IDLE -> connectionState(ConnectionVariant.NOT_FOUND, snapshot)
        InstallationSessionState.DISCOVERING -> connectionState(
            if (snapshot.discoveredDevices.isEmpty()) ConnectionVariant.SEARCHING else ConnectionVariant.FOUND,
            snapshot,
        )
        InstallationSessionState.CONNECTING -> connectionState(ConnectionVariant.CONNECTING, snapshot)

        InstallationSessionState.CONNECTED -> selectionState(snapshot)
        InstallationSessionState.SELECTION_CONFIRMED,
        InstallationSessionState.RESOLVING_SOURCE,
        InstallationSessionState.DOWNLOADING_ARCHIVE,
        InstallationSessionState.VERIFYING_ARCHIVE,
        InstallationSessionState.EXTRACTING_APK,
        InstallationSessionState.VERIFYING_ARTIFACTS,
        InstallationSessionState.INSTALLING,
        InstallationSessionState.AUTHORIZING,
        InstallationSessionState.VERIFYING_DEVICE,
        -> installingState(snapshot)

        InstallationSessionState.SUCCEEDED -> resultState(ResultKind.SUCCESS, snapshot)
        InstallationSessionState.COMPLETED_WITH_ERRORS -> resultState(ResultKind.PARTIAL_FAILURE, snapshot)
        InstallationSessionState.PAUSED -> resultState(ResultKind.PAUSED, snapshot)
        InstallationSessionState.FAILED -> if (
            snapshot.failure?.category == FailureCategory.CONNECTION && snapshot.checkpoint == null
        ) {
            connectionState(ConnectionVariant.FAILED, snapshot)
        } else {
            resultState(snapshot.failure.toResultKind(), snapshot)
        }
        InstallationSessionState.MAINTENANCE -> maintenanceState(snapshot)
        }
    }

    private fun maintenanceState(
        snapshot: InstallationSessionSnapshot,
        reconnecting: Boolean = false,
    ): InstallUiState.Maintenance = InstallUiState.Maintenance(
            deviceName = snapshot.device?.displayName,
            connected = snapshot.device?.connectionStatus == DeviceConnectionStatus.CONFIRMED,
            reconnecting = reconnecting,
            feedback = snapshot.maintenance.lastAction?.let { action ->
                MaintenanceFeedback(
                    actionId = action.actionId,
                    status = action.status,
                    resultCode = action.resultCode,
                    reasonCode = action.reasonCode,
                    retryable = action.retryable,
                )
            },
            applications = snapshot.maintenance.managedApplications.map { application ->
                MaintenanceApplicationRow(
                    componentId = application.componentId,
                    displayName = snapshot.components.firstOrNull { it.id == application.componentId }
                        ?.displayName ?: application.componentId,
                    packageName = application.packageName,
                    installed = application.installed,
                    versionLabel = application.versionLabel,
                    versionCode = application.versionCode,
                    fileSizeBytes = application.fileSizeBytes,
                    installTimeEpochMillis = application.installTimeEpochMillis,
                    updateTimeEpochMillis = application.updateTimeEpochMillis,
                    filePath = application.filePath,
                    uid = application.uid,
                    iconKey = application.componentId,
                )
            },
            applicationsState = when {
                snapshot.maintenance.managedApplicationsState != MaintenanceInventoryState.NOT_STARTED ->
                    snapshot.maintenance.managedApplicationsState
                snapshot.maintenance.managedApplications.isNotEmpty() -> MaintenanceInventoryState.READY
                snapshot.maintenance.lastAction?.actionId in setOf(
                    MaintenanceActionId.MANAGE_APPS,
                    MaintenanceActionId.REPAIR_CONFIGURATION,
                    MaintenanceActionId.INSTALL_FILE_MANAGER,
                ) && snapshot.maintenance.lastAction?.status == MaintenanceActionStatus.SUCCEEDED ->
                    MaintenanceInventoryState.READY
                else -> MaintenanceInventoryState.NOT_STARTED
            },
            applicationsErrorReason = snapshot.maintenance.managedApplicationsFailureReason,
            applicationsErrorRetryable = snapshot.maintenance.managedApplicationsFailureRetryable,
            updateStatuses = snapshot.maintenance.updateStatuses.map { status ->
                MaintenanceUpdateRow(
                    componentId = status.componentId,
                    displayName = status.displayName,
                    versionLabel = status.versionLabel,
                    installedVersionLabel = status.installedVersionLabel,
                    state = status.state,
                    isSelf = status.isSelf,
                    iconKey = status.iconKey,
                )
            },
            authorization = MaintenanceAuthorizationUi(
                state = snapshot.maintenance.authorization.state,
                currentComponentId = snapshot.maintenance.authorization.currentComponentId,
                applications = snapshot.maintenance.authorization.applications.map { item ->
                    val managed = snapshot.maintenance.managedApplications
                        .firstOrNull { application -> application.componentId == item.componentId }
                    MaintenanceAuthorizationRow(
                        componentId = item.componentId,
                        packageName = item.packageName,
                        versionLabel = managed?.versionLabel
                            ?: snapshot.components.firstOrNull { component -> component.id == item.componentId }?.versionLabel,
                        state = item.state,
                        authorized = item.authorized,
                        reasonCode = item.reasonCode,
                    )
                },
            ),
            applicationAction = snapshot.maintenance.applicationAction?.let { action ->
                MaintenanceApplicationFeedback(
                    componentId = action.componentId,
                    actionId = action.actionId,
                    status = action.status,
                    resultCode = action.resultCode,
                    reasonCode = action.reasonCode,
                    retryable = action.retryable,
                )
            },
            applicationDetails = snapshot.maintenance.applicationDetails?.let { details ->
                MaintenanceApplicationDetailsRow(
                    componentId = details.componentId,
                    displayName = details.displayName,
                    packageName = details.packageName,
                    versionLabel = details.versionLabel,
                    versionCode = details.versionCode,
                    fileSizeBytes = details.fileSizeBytes,
                    installTimeEpochMillis = details.installTimeEpochMillis,
                    updateTimeEpochMillis = details.updateTimeEpochMillis,
                    filePath = details.filePath,
                    uid = details.uid,
                )
            },
            installationSelection = snapshot.maintenance.installationSelection?.let { selection ->
                MaintenanceInstallationSelectionUi(
                    actionId = selection.actionId,
                    options = selection.options.map { option ->
                        MaintenanceInstallationOptionRow(
                            componentId = option.componentId,
                            displayName = option.displayName,
                            versionLabel = option.versionLabel,
                            sizeLabel = option.sizeLabel,
                            installed = option.installed,
                            required = option.required,
                            iconKey = option.iconKey,
                        )
                    },
                    selectedComponentIds = selection.selectedComponentIds,
                )
            },
        )

    private fun connectionState(
        variant: ConnectionVariant,
        snapshot: InstallationSessionSnapshot,
    ): InstallUiState.Connection = InstallUiState.Connection(
        variant = variant,
        devices = snapshot.discoveredDevices.map { device ->
            DeviceRow(
                id = device.id,
                displayName = device.displayName,
                status = device.connectionStatus,
                lastConfirmedLabel = device.lastConfirmedLabel,
            )
        },
        primaryAction = when (variant) {
            ConnectionVariant.SEARCHING -> ConnectionAction.STOP
            ConnectionVariant.CONNECTING -> ConnectionAction.CANCEL_CONNECTION
            ConnectionVariant.FAILED -> ConnectionAction.RECONNECT
            ConnectionVariant.FOUND,
            ConnectionVariant.NOT_FOUND,
            -> ConnectionAction.RETRY
        },
    )

    private fun selectionState(snapshot: InstallationSessionSnapshot): InstallUiState.Selection {
        val rows = snapshot.components.map { it.toUiRow(snapshot.selectedOptionalComponentIds) }
        val selected = rows.count { it.isMandatory() || it.selected }
        val sizeLabel = rows.asSequence()
            .filter { it.isMandatory() || it.selected }
            .mapNotNull { it.sizeLabel }
            .joinToString(" + ")
            .ifBlank { "待准备" }
        val selectedRows = rows.filter { it.isMandatory() || it.selected }
        return InstallUiState.Selection(
            deviceName = snapshot.device?.displayName ?: "车机未连接",
            components = rows,
            summaryCount = selected,
            summarySizeLabel = sizeLabel,
            canStart = snapshot.device?.connectionStatus == DeviceConnectionStatus.CONFIRMED &&
                selectedRows.isNotEmpty() &&
                selectedRows.all {
                    it.status in setOf(
                        com.ninepointnine.helper.domain.session.ComponentStatus.READING,
                        com.ninepointnine.helper.domain.session.ComponentStatus.AVAILABLE,
                        com.ninepointnine.helper.domain.session.ComponentStatus.NEW,
                        com.ninepointnine.helper.domain.session.ComponentStatus.UPDATE_AVAILABLE,
                        // A missing remote ZIP is recoverable from public
                        // Download, provided Cloud metadata is present.
                        com.ninepointnine.helper.domain.session.ComponentStatus.DIRECTORY_MISSING,
                    ) &&
                        (it.status != com.ninepointnine.helper.domain.session.ComponentStatus.DIRECTORY_MISSING ||
                            (!it.versionLabel.isNullOrBlank() && !it.sizeLabel.isNullOrBlank())) &&
                        (snapshot.artifactManifests.isEmpty() &&
                            it.compatibilityState != ComponentCompatibility.UNSUPPORTED ||
                            snapshot.artifactManifests.isNotEmpty() && (
                                !it.compatibilityLabel.isNullOrBlank() &&
                                it.compatibilityState == ComponentCompatibility.SUPPORTED
                            ))
                },
            preparing = snapshot.components.isEmpty() && snapshot.failure == null,
        )
    }

    private fun installingState(snapshot: InstallationSessionSnapshot): InstallUiState.Installing {
        val currentPhase = when (snapshot.state) {
            InstallationSessionState.RESOLVING_SOURCE,
            InstallationSessionState.DOWNLOADING_ARCHIVE,
            -> InstallPhase.FETCH

            InstallationSessionState.VERIFYING_ARCHIVE,
            InstallationSessionState.EXTRACTING_APK,
            InstallationSessionState.VERIFYING_ARTIFACTS,
            -> InstallPhase.CHECK

            InstallationSessionState.INSTALLING -> InstallPhase.SEND
            InstallationSessionState.AUTHORIZING -> InstallPhase.CONFIGURE
            InstallationSessionState.VERIFYING_DEVICE -> InstallPhase.VERIFY
            InstallationSessionState.SELECTION_CONFIRMED -> InstallPhase.FETCH
            else -> InstallPhase.FETCH
        }
        val completedStages = InstallPhase.entries
            .filter { it.ordinal < currentPhase.ordinal }
            .toSet()
        val progress = snapshot.progress
        return InstallUiState.Installing(
            deviceName = snapshot.device?.displayName ?: "车机未连接",
            currentComponentName = snapshot.currentComponentName,
            currentPhase = currentPhase,
            progress = UiProgress(
                completedCount = progress?.completedCount ?: 0,
                totalCount = progress?.totalCount ?: 0,
                fraction = progress?.fraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
                indeterminate = progress?.indeterminate ?: true,
            ),
            completedStages = completedStages,
        )
    }

    private fun resultState(
        kind: ResultKind,
        snapshot: InstallationSessionSnapshot,
    ): InstallUiState.Result = InstallUiState.Result(
        kind = kind,
        componentResults = snapshot.componentResults.map {
            ComponentResultRow(
                componentName = it.componentName,
                installed = it.installed,
                configured = it.configured,
                available = it.available,
                errorReason = (
                    it.failureReason
                        ?: snapshot.components.firstOrNull { component -> component.id == it.componentId }?.errorReason
                        ?: snapshot.failure?.reasonCode.takeIf { reason -> it.componentId == null }
                    )?.toUserMessage(),
            )
        },
        canContinue = kind != ResultKind.SUCCESS,
        canEnterMaintenance = kind == ResultKind.SUCCESS ||
            kind == ResultKind.PARTIAL_FAILURE &&
            AuthorizationPlanFactory.DESKTOP_COMPONENT_ID in snapshot.evidence.available,
    )

    private fun ComponentDescriptor.toUiRow(selectedOptionalIds: Set<String>): ComponentRow = ComponentRow(
        id = id,
        displayName = displayName,
        required = required,
        selected = id == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID || id in selectedOptionalIds,
        versionLabel = versionLabel,
        sizeLabel = sizeLabel,
        compatibilityLabel = compatibilityLabel,
        compatibilityState = compatibilityState,
        iconKey = iconKey,
        description = description,
        status = status,
        errorReason = errorReason?.toUserMessage(),
    )

    private fun ComponentRow.isMandatory(): Boolean = id == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID

private fun String.toUserMessage(): String = when {
    contains("desktop_prerequisite") -> "03桌面未完成，无法继续授权此应用"
    contains("local_download") || contains("public_download") || contains("distribution_app_missing") ->
        "下载目录中没有可用的安装包"
    contains("certificate") -> "应用签名与官方发布者不匹配"
    contains("archive") || contains("zip") -> "压缩包校验失败"
    contains("install") || contains("shortcut_selected") -> "车机未接受此应用的安装"
    contains("authorization") -> "车机授权未完成"
    contains("availability") || contains("device_verif") -> "应用未通过可用性检查"
    contains("missing") -> "下载目录中暂时没有这个应用"
    contains("schema") || contains("capability") || contains("incompatible") -> "当前客户端或车机能力不足"
    else -> "暂时无法使用，请稍后重试"
}

    private fun com.ninepointnine.helper.domain.session.SessionFailure?.toResultKind(): ResultKind = when (this?.category) {
        FailureCategory.DOWNLOAD,
        FailureCategory.ARCHIVE,
        -> ResultKind.DOWNLOAD_FAILED

        FailureCategory.CONFIGURATION -> ResultKind.CONFIGURATION_FAILED
        else -> ResultKind.INSTALLATION_FAILED
    }
}
