package com.ninepointnine.helper.ui.state

import com.ninepointnine.helper.domain.session.ComponentDescriptor
import com.ninepointnine.helper.domain.session.ComponentCompatibility
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.session.FailureCategory
import com.ninepointnine.helper.domain.session.InstallPhase
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.domain.session.InstallationSessionState
import com.ninepointnine.helper.domain.session.ResultKind
import com.ninepointnine.helper.domain.session.InstallationFlow
import com.ninepointnine.helper.domain.session.MaintenanceApplicationActionId
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.domain.session.MaintenanceActionStatus
import com.ninepointnine.helper.domain.session.MaintenanceInventoryState
import com.ninepointnine.helper.domain.session.MaintenanceBaselinePersistenceStatus
import com.ninepointnine.helper.domain.session.ArtifactCatalogStage
import com.ninepointnine.helper.domain.session.isApplicationInstallation
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
            // Keep the maintenance secondary route recoverable from the
            // active selection as well as the explicit route field. Older
            // snapshots and a recreated composition may briefly lack the
            // route field, but a maintenance selection still unambiguously
            // identifies the B-flow owner.
            routeAction = snapshot.maintenance.routeAction
                ?: snapshot.maintenance.installationSelection?.actionId
                    ?.takeIf { it.isApplicationInstallation },
            feedback = snapshot.maintenance.lastAction?.let { action ->
                MaintenanceFeedback(
                    actionId = action.actionId,
                    status = action.status,
                    resultCode = action.resultCode,
                    reasonCode = action.reasonCode,
                    retryable = action.retryable,
                    message = failureReasonToUserMessage(action.reasonCode)
                        .takeIf { action.status == MaintenanceActionStatus.FAILED && action.actionId.isApplicationInstallation },
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
                    MaintenanceActionId.INSTALL_APPLICATIONS,
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
                    feedback = snapshot.maintenance.lastAction
                        ?.takeIf { it.actionId == selection.actionId }
                        ?.let { action ->
                            MaintenanceFeedback(
                                actionId = action.actionId,
                                status = action.status,
                                resultCode = action.resultCode,
                                reasonCode = action.reasonCode,
                                retryable = action.retryable,
                                message = failureReasonToUserMessage(action.reasonCode)
                                    .takeIf { action.status == MaintenanceActionStatus.FAILED && action.actionId.isApplicationInstallation },
                            )
                        },
                )
            },
            persistenceWarning = snapshot.persistenceWarning(),
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
            canStart = snapshot.failure == null &&
                snapshot.device?.connectionStatus == DeviceConnectionStatus.CONFIRMED &&
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
                        (snapshot.artifactCatalogStage != ArtifactCatalogStage.PREPARED &&
                            it.compatibilityState != ComponentCompatibility.UNSUPPORTED ||
                            snapshot.artifactCatalogStage == ArtifactCatalogStage.PREPARED && (
                                !it.compatibilityLabel.isNullOrBlank() &&
                                it.compatibilityState == ComponentCompatibility.SUPPORTED
                            ))
                },
            preparing = snapshot.artifactCatalogStage == ArtifactCatalogStage.NOT_LOADED &&
                snapshot.components.isEmpty() && snapshot.failure == null,
            failureReason = snapshot.failure?.reasonCode?.toUserMessage(),
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
            installationFlow = snapshot.installationFlow,
        )
    }

    private fun resultState(
        kind: ResultKind,
        snapshot: InstallationSessionSnapshot,
    ): InstallUiState.Result {
        val batch = snapshot.installationBatch
        val resultIds = batch?.resultComponentIds
            ?: snapshot.componentResults.mapNotNull { it.componentId }.toSet()
        // A terminal snapshot can retain baseline evidence for a reusable
        // maintenance prerequisite. Only the current batch may influence the
        // result header or its rows.
        val resultRows = if (batch == null) {
            snapshot.componentResults
        } else {
            snapshot.componentResults.filter { it.componentId != null && it.componentId in resultIds }
        }
        val installedIds = if (batch == null) {
            snapshot.evidence.installed
        } else {
            snapshot.evidence.installed intersect resultIds
        }
        val configuredIds = if (batch == null) {
            snapshot.evidence.configured
        } else {
            snapshot.evidence.configured intersect resultIds
        }
        val availableIds = if (batch == null) {
            snapshot.evidence.available
        } else {
            snapshot.evidence.available intersect resultIds
        }
        val writeConfirmedIds = if (batch == null) {
            snapshot.evidence.writeConfirmed
        } else {
            snapshot.evidence.writeConfirmed intersect resultIds
        }
        val hasUnverifiedWrite = writeConfirmedIds.any { it !in installedIds } ||
            resultRows.any {
                it.status == com.ninepointnine.helper.domain.session.ComponentResultStatus.WRITE_CONFIRMED_IDENTITY_UNVERIFIED
            }
        val hasPostInstallFailure = hasUnverifiedWrite ||
            installedIds.any { it !in configuredIds || it !in availableIds } ||
            resultRows.any { it.installed && (!it.configured || !it.available) }
        // A write-confirmed component is not an ordinary install failure: the
        // device accepted the write, and only the identity readback remains
        // unresolved. Keep it out of the pre-install failure bucket so the
        // result page can explain the actual post-install boundary.
        val ordinaryInstallationFailureIds = resultIds - writeConfirmedIds
        val hasInstallationFailure = ordinaryInstallationFailureIds.any { it !in installedIds } ||
            resultRows.any {
                !it.installed &&
                    it.status != com.ninepointnine.helper.domain.session.ComponentResultStatus.WRITE_CONFIRMED_IDENTITY_UNVERIFIED
            }
        // A device write can succeed before authorization or availability
        // fails. Present that as a partial result so the page never labels a
        // package with current-batch install proof as wholly uninstalled.
        val effectiveKind = if (
            kind !in setOf(ResultKind.SUCCESS, ResultKind.PAUSED) &&
            hasPostInstallFailure
        ) {
            ResultKind.PARTIAL_FAILURE
        } else {
            kind
        }
        val visibleFailureReason = resultRows.asSequence()
            .mapNotNull { it.failureReason }
            .firstOrNull()
        val failureReason = (visibleFailureReason ?: snapshot.failure?.reasonCode)
            ?.toUserMessage()
        val failureStage = when {
            effectiveKind == ResultKind.SUCCESS || effectiveKind == ResultKind.PAUSED -> ResultFailureStage.NONE
            hasPostInstallFailure && hasInstallationFailure -> ResultFailureStage.MIXED
            hasPostInstallFailure -> ResultFailureStage.POST_INSTALL
            else -> ResultFailureStage.INSTALLATION
        }
        val desktopIsInCurrentBatch = batch?.let {
            AuthorizationPlanFactory.DESKTOP_COMPONENT_ID in it.resultComponentIds ||
                AuthorizationPlanFactory.DESKTOP_COMPONENT_ID in it.reusableComponentIds
        } ?: true
        val desktopReady = desktopIsInCurrentBatch &&
            AuthorizationPlanFactory.DESKTOP_COMPONENT_ID in snapshot.evidence.available
        return InstallUiState.Result(
            kind = effectiveKind,
            componentResults = resultRows.map {
            ComponentResultRow(
                componentName = it.componentName,
                installed = it.installed,
                configured = it.configured,
                available = it.available,
                status = it.status,
                errorReason = (
                    it.failureReason
                        ?: snapshot.components.firstOrNull { component -> component.id == it.componentId }?.errorReason
                        ?: snapshot.failure?.reasonCode.takeIf { reason -> it.componentId == null }
                )?.toUserMessage(),
            )
            },
            canContinue = effectiveKind != ResultKind.SUCCESS,
            canEnterMaintenance = effectiveKind in setOf(ResultKind.SUCCESS, ResultKind.PARTIAL_FAILURE) &&
                desktopReady,
            failureReason = failureReason,
            installationFlow = snapshot.installationFlow,
            failureStage = failureStage,
            persistenceWarning = snapshot.persistenceWarning(),
        )
    }

    private fun InstallationSessionSnapshot.persistenceWarning(): String? =
        maintenanceBaselinePersistence.reasonCode
            ?.takeIf { maintenanceBaselinePersistence.status == MaintenanceBaselinePersistenceStatus.FAILED }
            ?.let {
                "安装结果已保留在本次使用中，但未能保存到手机；下次打开时需要重新读取车机应用状态"
            }

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

internal fun failureReasonToUserMessage(reasonCode: String?): String? = reasonCode?.toUserMessage()

private fun String.toUserMessage(): String = when {
    contains("component_metadata_incomplete") ->
        "安装配置缺少必要信息，请重新读取安装配置"
    contains("selected_catalog_preparation_failed") || contains("artifact_preparation_failed") ->
        "安装包准备失败，请重试"
    contains("catalog_android_profile") || contains("catalog_load") || contains("distribution_config") ->
        "暂时无法读取安装配置，请重试"
    contains("desktop_prerequisite") -> "03桌面未完成，无法继续授权此应用"
    contains("public_download_publish_failed") ->
        "安装包已下载，但暂时无法保存到手机的下载目录"
    contains("local_download") || contains("distribution_app_missing") ->
        "手机的下载目录中没有可复用的安装包"
    contains("lanzou_folder_duplicate") ->
        "云端安装目录中存在重复文件，已停止使用"
    contains("lanzou_folder_missing") ->
        "云端安装目录中暂时没有这个应用"
    contains("lanzou_folder") ->
        "暂时无法读取云端安装目录，请重试"
    contains("certificate") -> "应用签名与官方发布者不匹配"
    contains("archive") || contains("zip") -> "压缩包校验失败"
    contains("desktop_launch") || contains("desktop_process") || contains("desktop_service") ->
        "03桌面未通过可用性检查"
    contains("install") || contains("shortcut_selected") -> "车机未接受此应用的安装"
    contains("authorization") -> "车机授权未完成"
    contains("availability") || contains("device_verif") -> "应用未通过可用性检查"
    contains("missing") -> "安装所需资料不完整，请重试"
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
