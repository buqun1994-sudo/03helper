package com.ninepointnine.helper.ui.state

import com.ninepointnine.helper.domain.session.ComponentDescriptor
import com.ninepointnine.helper.domain.session.ComponentCompatibility
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.session.InstallPhase
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.domain.session.InstallationSessionState
import com.ninepointnine.helper.domain.session.InstallationFlow
import com.ninepointnine.helper.domain.session.MaintenanceApplicationActionId
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.domain.session.MaintenanceActionStatus
import com.ninepointnine.helper.domain.session.MaintenanceInventoryState
import com.ninepointnine.helper.domain.session.ArtifactCatalogStage
import com.ninepointnine.helper.domain.session.isApplicationInstallation
import com.ninepointnine.helper.domain.device.AuthorizationPlanFactory
import com.ninepointnine.helper.domain.session.resolveInstallationResult
import com.ninepointnine.helper.domain.session.compareInstallTimesDescending
import com.ninepointnine.helper.domain.session.thirdPartyRowId

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
                snapshot.copy(state = snapshot.checkpoint?.state ?: InstallationSessionState.PREPARING_ARTIFACTS),
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
        InstallationSessionState.PREPARING_ARTIFACTS,
        InstallationSessionState.ARTIFACTS_READY,
        InstallationSessionState.INSTALLING,
        InstallationSessionState.AUTHORIZING,
        InstallationSessionState.VERIFYING_DEVICE,
        -> installingState(snapshot)

        InstallationSessionState.SUCCEEDED,
        InstallationSessionState.COMPLETED_WITH_ERRORS,
        InstallationSessionState.PAUSED,
        -> resultState(snapshot)
        InstallationSessionState.FAILED -> if (
            snapshot.failure?.category == com.ninepointnine.helper.domain.session.FailureCategory.CONNECTION &&
                snapshot.checkpoint == null
        ) {
            connectionState(ConnectionVariant.FAILED, snapshot)
        } else {
            resultState(snapshot)
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
            // The route and its transient feedback are one domain-owned pair.
            // A payload such as installationSelection is not allowed to
            // resurrect a secondary page after returning to the home page.
            routeAction = snapshot.maintenance.routeAction,
            feedback = snapshot.maintenance.lastAction
                ?.takeIf { it.actionId == snapshot.maintenance.routeAction }
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
            applications = maintenanceApplicationRows(snapshot),
            applicationsState = snapshot.maintenance.managedApplicationsState,
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

    /**
     * Projects controlled and third-party packages into one flat list. A
     * package that is both controlled and third-party is represented once by
     * its controlled row, while every other third-party package receives the
     * same fixed maintenance actions through its synthetic row identity.
     */
    private fun maintenanceApplicationRows(
        snapshot: InstallationSessionSnapshot,
    ): List<MaintenanceApplicationRow> {
        val controlled = snapshot.maintenance.managedApplications.map { application ->
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
                isControlled = true,
            )
        }
        val thirdPartyByPackage = snapshot.maintenance.thirdPartyApplications.associateBy { it.packageName }
        val mergedControlled = controlled.map { row ->
            val observed = thirdPartyByPackage[row.packageName] ?: return@map row
            row.copy(
                versionLabel = row.versionLabel ?: observed.versionLabel,
                versionCode = row.versionCode ?: observed.versionCode,
                installTimeEpochMillis = row.installTimeEpochMillis ?: observed.installTimeEpochMillis,
                updateTimeEpochMillis = row.updateTimeEpochMillis ?: observed.updateTimeEpochMillis,
                filePath = row.filePath ?: observed.filePath,
                uid = row.uid ?: observed.uid,
            )
        }
        val controlledPackages = controlled.mapTo(mutableSetOf()) { it.packageName }
        val thirdParty = snapshot.maintenance.thirdPartyApplications
            .filterNot { it.packageName in controlledPackages }
            .map { application ->
                MaintenanceApplicationRow(
                    componentId = thirdPartyRowId(application.packageName),
                    displayName = application.packageName,
                    packageName = application.packageName,
                    installed = true,
                    versionLabel = application.versionLabel,
                    versionCode = application.versionCode,
                    installTimeEpochMillis = application.installTimeEpochMillis,
                    updateTimeEpochMillis = application.updateTimeEpochMillis,
                    filePath = application.filePath,
                    uid = application.uid,
                    iconKey = "third-party",
                    isControlled = false,
                )
            }
        return (mergedControlled + thirdParty).sortedWith { left, right ->
            val installTime = compareInstallTimesDescending(
                left.installTimeEpochMillis,
                right.installTimeEpochMillis,
            )
            if (installTime != 0) installTime
            else left.packageName.compareTo(right.packageName).takeUnless { it == 0 }
                ?: left.componentId.compareTo(right.componentId)
        }
    }

    private fun installingState(snapshot: InstallationSessionSnapshot): InstallUiState.Installing {
        val currentPhase = when (snapshot.state) {
            InstallationSessionState.PREPARING_ARTIFACTS -> snapshot.componentProgress.values
                .firstOrNull { it.status == com.ninepointnine.helper.domain.session.ComponentProgressStatus.RUNNING }
                ?.phase
                ?: InstallPhase.FETCH

            InstallationSessionState.ARTIFACTS_READY -> InstallPhase.CHECK

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

    private fun resultState(snapshot: InstallationSessionSnapshot): InstallUiState.Result {
        val result = snapshot.resolveInstallationResult()
        val hasVisibleReason = result.componentResults.any { it.failureReason != null }
        return InstallUiState.Result(
            kind = result.kind,
            componentResults = result.componentResults.map {
            ComponentResultRow(
                componentName = it.componentName,
                installed = it.installed,
                configured = it.configured,
                available = it.available,
                // The domain result is the only terminal adjudication. This
                // mapper translates it but never reinterprets raw evidence.
                status = it.status,
                // The result projection already scopes reasons to this batch.
                // Never reattach a catalog descriptor error here: that would
                // turn a successful fallback-source install into a failure.
                errorReason = (it.failureReason
                    ?: snapshot.failure?.reasonCode.takeIf { reason -> it.componentId == null })
                    ?.toUserMessage(),
            )
            },
            canContinue = result.canContinue,
            canEnterMaintenance = result.canEnterMaintenance,
            // A component row owns its concrete reason. Keeping the same
            // reason in the page header creates the duplicate warning that
            // previously looked like a second independent failure.
            failureReason = result.failureReasonCode
                ?.takeIf { !hasVisibleReason }
                ?.toUserMessage(),
            installationFlow = result.installationFlow,
            failureStage = result.failureStage,
        )
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

internal fun failureReasonToUserMessage(reasonCode: String?): String? = reasonCode?.toUserMessage()

private fun String.toUserMessage(): String = when (this) {
    "component_metadata_incomplete" -> "安装配置缺少必要信息，请重新读取安装配置"
    "selected_catalog_preparation_failed",
    "artifact_preparation_failed",
    "artifact_app_processing_failed",
    "distribution_app_processing_failed",
    -> "安装包准备失败，请重试"

    "catalog_android_profile_missing",
    "catalog_trust_evidence_missing",
    "distribution_config_metadata_invalid",
    "distribution_config_components_invalid",
    -> "暂时无法读取安装配置，请重试"

    "desktop_prerequisite_failed" -> "03桌面未完成，无法继续处理此应用"
    "public_download_publish_failed" -> "安装包已下载，但暂时无法保存到手机的下载目录"
    "local_download_candidate_missing" -> "手机的下载目录中没有可复用的安装包"
    "local_download_unavailable" -> "暂时无法读取手机的下载目录，请检查存储权限"
    "lanzou_folder_duplicate" -> "云端安装目录中存在重复文件，已停止使用"
    "lanzou_folder_missing" -> "云端安装目录中暂时没有这个应用"
    "lanzou_parse_timeout",
    "lanzou_folder_start_failed",
    "lanzou_webview_create_failed",
    "lanzou_webview_start_failed",
    -> "暂时无法读取云端安装目录，请重试"
    "lanzou_page_incompatible" -> "当前云端下载页面版本不兼容，请重试"
    "lanzou_verification_required" -> "云端要求完成安全验证，暂时无法自动下载"
    "lanzou_html_response" -> "云端返回了验证页面，请稍后重试"

    "install_apk_certificate_mismatch",
    "installation_installed_certificate_mismatch",
    "maintenance_installed_certificate_mismatch",
    -> "应用签名与官方发布者不匹配"

    "installation_installed_apk_hash_mismatch",
    "maintenance_installed_apk_hash_mismatch",
    "apk_hash_mismatch",
    -> "车机上的应用内容与官方发布包不一致，请重新安装"

    "archive_verification_failed",
    "distribution_archive_invalid",
    "archive_identity_invalid",
    "archive_identity_missing",
    "apk_extraction_evidence_invalid",
    "apk_identity_missing",
    -> "压缩包或安装包校验失败"

    "installation_package_path_missing",
    "installation_installed_apk_read_failed",
    "installation_installed_apk_verify_failed",
    -> "车机已接受安装操作，暂时无法确认应用身份，请重新检查"

    "success_evidence_incomplete",
    "installation_evidence_missing",
    "installation_detail_invalid",
    "availability_evidence_missing",
    "availability_detail_invalid",
    -> "安装结果未能确认，请重新检查车机状态"

    "authorization_confirmation_unavailable" -> "车机已接受授权操作，暂时无法确认授权状态，请重新检查"
    "authorization_confirmation_not_satisfied",
    "authorization_appop_not_allowed",
    "authorization_runtime_permission_not_granted",
    "authorization_secure_setting_disabled",
    "authorization_component_not_present",
    -> "车机授权未完成，请重新授权"

    "desktop_launch_failed",
    "desktop_launch_evidence_invalid",
    "desktop_process_not_running",
    "desktop_service_missing",
    "desktop_service_not_bound",
    "desktop_service_readback_failed",
    "desktop_verification_not_completed",
    -> "03桌面未通过可用性检查"

    "maintenance_route_invalid" -> "当前维护页面已失效，请返回维护首页后重试"

    "component_incompatible",
    "device_capability_missing",
    "device_android_sdk_missing",
    -> "当前客户端或车机能力不足"

    else -> "暂时无法完成，请重试"
}
}
