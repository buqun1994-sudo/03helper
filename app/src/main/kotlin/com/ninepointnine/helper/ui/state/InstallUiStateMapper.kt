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
                        .takeIf { action.status == MaintenanceActionStatus.FAILED },
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
                    iconKey = details.iconKey,
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
                                    .takeIf { action.status == MaintenanceActionStatus.FAILED },
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
        val rows = snapshot.components.map {
            it.toUiRow(
                selectedOptionalIds = snapshot.selectedOptionalComponentIds,
                installedIds = snapshot.initialInventory.applications
                    .asSequence()
                    .filter { application -> application.installed }
                    .map { application -> application.componentId }
                    .toSet(),
            )
        }
        val inventoryLoading = snapshot.initialInventory.state ==
            com.ninepointnine.helper.domain.session.InitialApplicationInventoryState.LOADING ||
            snapshot.initialInventory.state ==
                com.ninepointnine.helper.domain.session.InitialApplicationInventoryState.NOT_STARTED &&
                snapshot.components.isEmpty()
        val inventoryFailureReason = snapshot.initialInventory.failureReason
            ?.toUserMessage()
            ?.takeIf {
                snapshot.initialInventory.state ==
                    com.ninepointnine.helper.domain.session.InitialApplicationInventoryState.FAILED
            }
        val managedRows = rows.filter {
            it.status != com.ninepointnine.helper.domain.session.ComponentStatus.UNLISTED
        }
        val allInstalled = snapshot.initialInventory.state ==
            com.ninepointnine.helper.domain.session.InitialApplicationInventoryState.READY &&
            managedRows.isNotEmpty() &&
            managedRows.all { it.installed }
        val selectedRows = rows.filter { row ->
            !row.installed && (row.isMandatory() || row.selected)
        }
        val selected = selectedRows.size
        val sizeLabel = selectedRows.asSequence()
            .mapNotNull { it.sizeLabel }
            .joinToString(" + ")
            .ifBlank { "待准备" }
        return InstallUiState.Selection(
            deviceName = snapshot.device?.displayName ?: "车机未连接",
            components = rows,
            summaryCount = if (allInstalled) managedRows.count { it.installed } else selected,
            summarySizeLabel = if (allInstalled) "已全部安装" else sizeLabel,
            canStart = !allInstalled && !inventoryLoading && snapshot.failure == null &&
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
            canFinish = allInstalled &&
                snapshot.failure == null &&
                snapshot.device?.connectionStatus == DeviceConnectionStatus.CONFIRMED,
            inventoryLoading = inventoryLoading,
            inventoryFailureReason = inventoryFailureReason,
            preparing = snapshot.failure == null &&
                (inventoryLoading || (
                    snapshot.artifactCatalogStage == ArtifactCatalogStage.NOT_LOADED &&
                        snapshot.components.isEmpty()
                    )),
            failureReason = inventoryFailureReason
                ?: snapshot.failure?.reasonCode?.toUserMessage(),
        )
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
            selfUpdateReady = snapshot.installationFlow == InstallationFlow.SELF_UPDATE &&
                snapshot.state == InstallationSessionState.ARTIFACTS_READY,
            selfUpdateInstallInProgress = snapshot.installationFlow == InstallationFlow.SELF_UPDATE &&
                snapshot.state == InstallationSessionState.INSTALLING,
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
                    ?.toUserMessage(componentName = it.componentName),
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

    private fun ComponentDescriptor.toUiRow(
        selectedOptionalIds: Set<String>,
        installedIds: Set<String>,
    ): ComponentRow {
        val installed = id in installedIds || status ==
            com.ninepointnine.helper.domain.session.ComponentStatus.INSTALLED_LATEST
        return ComponentRow(
        id = id,
        displayName = displayName,
        required = required,
        selected = !installed && (
            id == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID || id in selectedOptionalIds
            ),
        installed = installed,
        versionLabel = versionLabel,
        sizeLabel = sizeLabel,
        compatibilityLabel = compatibilityLabel,
        compatibilityState = compatibilityState,
        iconKey = iconKey,
        description = description,
        status = status,
        errorReason = errorReason?.toUserMessage(componentName = displayName),
        )
    }
}

internal fun failureReasonToUserMessage(
    reasonCode: String?,
    componentName: String? = null,
): String? {
    val normalized = reasonCode?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return normalized.toUserMessage(componentName)
}

/**
 * Converts an internal reason identifier into a short action-oriented message.
 * The fallback is deliberately concrete: a raw reason code must never leak to
 * users, and a vague "暂时无法" message makes the next action unknowable.
 */
private fun String.toUserMessage(componentName: String? = null): String {
    val reason = lowercase()
    val inferredComponent = componentIdFromReason(reason)
    val label = componentName?.trim()?.takeIf(String::isNotEmpty)
        ?.let(::friendlyComponentName)
        ?: inferredComponent?.let(::friendlyComponentName)
    val message = when {
        reason == "cancelled" || reason == "self_update_cancelled" ->
            "操作已取消：可重新开始"
        reason == "unknown_component" || reason == "selected_component_missing" ->
            "所选应用已不在安装清单：重新获取清单"
        reason == "no_devices_found" || reason == "device_not_discovered" ->
            "未找到车机：确认手机与车机在同一网络后重试"
        reason == "discovery_timeout" ->
            "查找车机超时：确认手机与车机在同一网络后重试"
        reason == "discovery_scope_too_large" ->
            "当前网络范围过大：切换到与车机相同的局域网"
        reason == "discovery_scope_unavailable" || reason == "discovery_adapter_unavailable" ->
            "无法读取手机网络范围：检查网络权限后重试"
        reason == "discovery_failed" ->
            "车机搜索失败：确认手机与车机在同一网络后重试"
        reason == "discovery_result_invalid" || reason == "discovery_event_out_of_order" ||
            reason == "discovery_finished_out_of_order" ->
            "车机搜索结果无效：重新查找车机"

        reason.startsWith("initial_inventory") -> when (reason) {
            "initial_inventory_connection_unavailable" -> "车机连接已断开：重新连接车机"
            "initial_inventory_result_invalid" -> "车机应用列表格式错误：重新连接车机"
            "initial_inventory_package_inventory_failed" -> "车机未返回应用列表：重新连接车机"
            "initial_inventory_read_failed" -> "读取车机应用列表时连接中断：重新连接车机"
            else -> "车机应用列表读取失败：重新连接车机"
        }

        reason == "component_metadata_incomplete" ->
            "安装配置缺少应用版本或文件大小：重新获取安装清单"
        reason == "selected_catalog_preparation_failed" ->
            "所选应用与安装清单不一致：重新获取安装清单"
        reason == "artifact_preparation_failed" ->
            "安装包处理器未返回结果：重新获取安装清单"
        reason == "artifact_preparation_unavailable" ->
            "安装包处理器未配置：检查应用版本"
        reason == "artifact_preparation_result_invalid" ||
            reason == "artifact_batch_event_missing" ->
            "安装包处理器未返回完整结果：重新获取安装清单"
        reason == "artifact_app_processing_failed" ||
            reason == "distribution_app_processing_failed" ->
            "应用安装包处理失败：重新下载该应用"

        reason == "catalog_empty" || reason == "distribution_config_apps_empty" ->
            "云端安装配置没有可安装应用"
        reason == "distribution_config_desktop_missing" ->
            "安装配置缺少 03桌面：更新云端配置"
        reason == "catalog_android_profile_missing" ||
            reason == "catalog_load_failed" ||
            reason == "catalog_transport_failed" ||
            reason == "catalog_zero_read" ||
            reason.startsWith("catalog_http_") ||
            reason == "distribution_config_unavailable" ||
            reason == "distribution_config_transport_failed" ->
            "安装配置读取失败：检查网络后重试"
        reason == "catalog_signature_invalid" ||
            reason == "catalog_envelope_invalid" ||
            reason == "catalog_payload_invalid" ||
            reason == "catalog_trust_evidence_missing" ||
            reason == "distribution_config_signature_invalid" ||
            reason == "distribution_config_envelope_invalid" ||
            reason == "distribution_config_payload_invalid" ||
            reason == "distribution_config_metadata_invalid" ||
            reason == "distribution_config_components_invalid" ->
            "安装配置内容无效：更新云端配置"
        reason == "distribution_config_expired" || reason == "distribution_config_time_window_invalid" ->
            "安装配置已过期：发布新的安装配置"
        reason == "distribution_config_rollback" || reason == "distribution_config_catalog_revision_mismatch" ->
            "安装配置修订号过低：发布更高修订号"
        reason.startsWith("distribution_config_app_") ||
            reason == "distribution_config_component_mapping_invalid" ->
            "安装配置中的应用条目无效：更新云端配置"
        reason.startsWith("distribution_config_") || reason.startsWith("catalog_") ->
            "安装配置字段无效：更新云端配置"

        reason == "desktop_prerequisite_failed" ->
            "03桌面未安装成功：先完成 03桌面安装"
        reason == "public_download_publish_failed" || reason == "public_download_output_unavailable" ->
            "无法写入手机 Download 目录：检查存储权限"
        reason == "local_download_candidate_missing" ->
            "手机 Download 中没有匹配当前版本的 APK"
        reason == "local_download_unavailable" ->
            "无法读取手机 Download 目录：检查存储权限"
        reason == "local_download_hash_failed" ->
            "手机中的 APK 摘要读取失败：删除旧文件后重试"

        reason == "lanzou_folder_duplicate" ||
            reason == "lanzou_folder_duplicate_file" ||
            reason == "lanzou_folder_duplicate_entry" ->
            "云端存在同名安装包：保留一个文件后重试"
        reason == "lanzou_folder_missing" || reason.startsWith("lanzou_folder_missing_") ||
            reason == "lanzou_folder_empty" || reason.startsWith("lanzou_folder_empty_") ->
            "云端没有对应安装包：检查发布目录"
        reason == "lanzou_parse_timeout" || reason == "lanzou_folder_parse_timeout" ||
            reason == "lanzou_page_load_failed" || reason == "lanzou_folder_start_failed" ||
            reason == "lanzou_webview_create_failed" || reason == "lanzou_webview_start_failed" ||
            reason == "lanzou_webview_host_unavailable" ->
            "云端下载页加载超时：重试下载"
        reason == "lanzou_page_incompatible" || reason == "lanzou_folder_client_schema_unsupported" ->
            "云端下载页版本不兼容：更新分享页"
        reason == "lanzou_verification_required" || reason == "lanzou_verification_url_not_download" ->
            "云端要求人工验证：完成验证后重试下载"
        reason == "lanzou_html_response" ->
            "云端返回网页而非下载文件：检查分享链接"
        reason.startsWith("lanzou_") ->
            "云端下载链接无效：检查发布配置"

        reason.startsWith("download_http_") ->
            "下载源返回 HTTP 错误：检查云端文件后重试"
        reason == "download_incomplete" || reason == "download_io_failed" ||
            reason == "download_range_response_invalid" || reason == "download_zero_read" ->
            "下载连接中断：重试下载"
        reason == "download_non_archive_response" || reason == "download_not_zip" ->
            "下载内容不是 ZIP 文件：检查云端文件"
        reason == "download_size_exceeds_manifest" ->
            "下载文件超过清单大小：检查云端文件"
        reason.startsWith("download_") ->
            "下载文件读取失败：重试下载"

        reason == "archive_missing" -> "ZIP 文件不存在：重新下载"
        reason == "archive_sha256_mismatch" || reason == "archive_hash_failed" ||
            reason == "archive_size_mismatch" || reason == "archive_size_invalid" ||
            reason == "archive_verification_failed" || reason == "archive_verification_evidence_invalid" ->
            "ZIP 与清单不一致：重新下载"
        reason == "archive_zip_invalid" || reason == "archive_format_unsupported" ->
            "ZIP 文件损坏或格式不支持：重新下载"
        reason == "archive_extra_entry" || reason == "archive_extra_file" ||
            reason == "archive_unexpected_apk" || reason == "archive_directory_forbidden" ||
            reason == "archive_path_traversal" || reason == "archive_symlink_forbidden" ->
            "ZIP 内容不是单 APK：重新发布安装包"
        reason == "insufficient_private_cache_space" || reason == "artifact_cache_unavailable" ->
            "手机存储空间不足：清理空间后重试"
        reason.startsWith("archive_") || reason.startsWith("dynamic_archive_") ||
            reason.startsWith("distribution_archive_") || reason.startsWith("apk_extraction_") ->
            "ZIP 解压失败：重新下载"

        reason == "install_apk_certificate_mismatch" ||
            reason == "distribution_apk_certificate_mismatch" ||
            reason == "installation_installed_certificate_mismatch" ||
            reason == "maintenance_installed_certificate_mismatch" ||
            reason == "apk_certificate_mismatch" ->
            "APK 签名与发布配置不一致：检查云端文件"
        reason == "apk_hash_mismatch" || reason == "installation_installed_apk_hash_mismatch" ||
            reason == "maintenance_installed_apk_hash_mismatch" ->
            "APK 内容与清单不一致：重新下载"
        reason == "distribution_apk_version_mismatch" || reason == "apk_version_mismatch" ||
            reason == "install_apk_version_mismatch" ->
            "APK 版本与安装配置不一致：更新云端文件"
        reason == "apk_package_mismatch" || reason == "distribution_apk_package_mismatch" ||
            reason == "install_apk_package_mismatch" || reason == "installation_installed_package_mismatch" ||
            reason == "maintenance_installed_package_mismatch" ->
            "APK 包身份与安装配置不一致：检查云端文件"
        reason == "apk_metadata_unreadable" || reason == "distribution_apk_metadata_unreadable" ||
            reason == "install_apk_metadata_unreadable" ->
            "APK 元数据读取失败：重新下载"
        reason == "apk_missing" || reason == "apk_entry_missing" || reason == "install_apk_file_invalid" ->
            "APK 文件缺失：重新下载"
        reason.startsWith("apk_") || reason.startsWith("install_apk_") ->
            "APK 文件校验失败：重新下载"

        reason == "installation_installed_identity_conflict" ->
            "车机已安装同组件的其他包名：先处理旧应用"
        reason == "installation_reused_package_missing" ->
            "车机上的已安装组件已消失：重新读取应用状态"
        reason == "installation_reused_version_mismatch" ->
            "车机已有版本与安装配置不一致：重新安装"
        reason == "adb_pm_install_failed" ->
            "车机拒绝安装 APK：检查车机存储和版本"
        reason == "adb_install_transport_failed" ->
            "发送 APK 时车机连接中断：重新连接车机"
        reason == "adb_install_failed" || reason == "installation_batch_execution_failed" ->
            "车机安装命令失败：重新连接后重试"
        reason == "remote_staging_path_invalid" ->
            "车机临时安装路径无效：重新连接车机"
        reason == "installation_package_path_missing" ||
            reason == "installation_installed_apk_read_failed" ||
            reason == "installation_installed_apk_verify_failed" ->
            "车机安装结果读取失败：重新连接车机"
        reason == "installation_result_invalid" || reason == "installation_evidence_invalid" ->
            "车机返回的安装结果格式错误：重新连接车机"
        reason == "installation_evidence_missing" || reason == "success_evidence_incomplete" ->
            "车机未返回完整安装结果：重新连接车机"
        reason == "installation_identity_unavailable" || reason == "installation_installed_apk_metadata_unreadable" ->
            "车机已安装 APK，但未读到包信息：重新连接车机"
        reason == "installation_batch_missing" || reason == "installation_batch_plan_invalid" ->
            "安装批次数据无效：返回上一步并重新开始"
        reason.startsWith("installation_batch_receipt_") ->
            "车机安装回执内容无效：重新连接车机"
        reason.startsWith("installation_") ->
            "车机安装未完成：重新连接车机后重试"

        reason == "authorization_confirmation_unavailable" ||
            reason == "authorization_appop_read_failed" ||
            reason == "authorization_runtime_permission_read_failed" ||
            reason == "authorization_secure_setting_read_failed" ->
            "车机未返回授权状态：重新连接车机"
        reason == "authorization_confirmation_not_satisfied" ||
            reason == "authorization_appop_not_allowed" ||
            reason == "authorization_runtime_permission_not_granted" ||
            reason == "authorization_secure_setting_disabled" ||
            reason == "authorization_component_not_present" ->
            "车机授权未完成：重新授权"
        reason == "authorization_capacity_entries_exceeded" ||
            reason == "authorization_capacity_bytes_exceeded" ->
            "车机授权列表已满：移除无用授权后重试"
        reason == "authorization_permission_not_declared" ||
            reason == "authorization_service_not_declared" ->
            "安装包未声明所需授权：检查发布包"
        reason == "authorization_artifacts_missing" || reason == "authorization_artifacts_mismatch" ->
            "授权对象与安装包不一致：重新获取安装配置"
        reason == "authorization_not_attempted_installation_unverified" ||
            reason == "authorization_not_attempted" ->
            "安装身份尚未确认：先完成安装结果读取"
        reason.startsWith("authorization_") ->
            "车机授权操作失败：重新授权"

        reason == "availability_evidence_missing" || reason == "availability_detail_invalid" ||
            reason == "availability_evidence_invalid" ->
            "应用可用性结果读取失败：重新启动应用"
        reason.startsWith("availability_") ->
            "应用未通过可用性检查：重新启动应用"
        reason == "desktop_launch_failed" || reason == "desktop_launch_evidence_invalid" ||
            reason == "desktop_process_not_running" || reason == "desktop_service_missing" ||
            reason == "desktop_service_not_bound" || reason == "desktop_service_readback_failed" ||
            reason == "desktop_verification_not_completed" ->
            "03桌面启动后未进入可用状态：重新安装 03桌面"
        reason.startsWith("desktop_") -> "03桌面状态异常：重新安装 03桌面"

        reason == "device_disconnected" || reason == "adb_connection_closed" ||
            reason == "adb_connection_lost" ->
            "车机连接已断开：重新连接车机"
        reason == "device_action_gateway_unavailable" || reason == "connection_adapter_unavailable" ->
            "车机操作通道未建立：重新连接车机"
        reason == "device_connection_target_missing" || reason == "device_connection_mismatch" ->
            "选中的车机已不可用：重新查找车机"
        reason == "known_device_endpoint_missing" || reason == "reconnect_device_mismatch" ->
            "上次连接的车机已不可达：重新查找车机"
        reason == "device_connection_failed" || reason == "adb_connect_failed" ->
            "车机连接失败：确认车机已开启 ADB 后重试"
        reason == "adb_identity_serial_missing" || reason == "adb_identity_model_missing" ||
            reason == "adb_identity_sdk_invalid" || reason == "adb_identity_invalid" ||
            reason == "adb_identity_read_failed" ->
            "未读取到车机身份信息：重新连接车机"
        reason == "adb_connection_check_failed" ->
            "车机连接检测失败：重新连接车机"
        reason == "device_connection_confirmation_invalid" || reason == "device_not_confirmed" ->
            "车机连接未确认：重新连接车机"
        reason == "device_identity_changed" || reason == "adb_identity_changed" ->
            "车机身份已变化：重新连接车机"
        reason == "device_android_sdk_missing" || reason == "device_capability_missing" ||
            reason == "component_incompatible" ->
            "当前车机不满足应用运行条件：选择兼容应用"
        reason.startsWith("device_") || reason.startsWith("adb_") ->
            "车机连接或操作失败：重新连接车机"

        reason == "maintenance_route_invalid" ->
            "维护页面状态已失效：返回维护首页"
        reason == "maintenance_component_not_installed" || reason == "component_unavailable" ->
            "车机未安装该应用：重新读取应用状态"
        reason == "maintenance_component_identity_invalid" || reason == "maintenance_package_identity_invalid" ||
            reason == "maintenance_package_path_missing" ->
            "车机应用身份与配置不一致：停止操作并重新检查"
        reason == "maintenance_launch_unavailable" -> "车机没有可用的启动入口：重新安装该应用"
        reason == "maintenance_launch_failed" -> "车机拒绝启动该应用：重新连接车机"
        reason == "maintenance_force_stop_failed" -> "车机未能停止该应用：重新连接车机"
        reason == "maintenance_uninstall_failed" -> "车机未能卸载该应用：重新连接车机"
        reason == "maintenance_application_details_unavailable" ||
            reason == "maintenance_package_details_failed" ->
            "车机应用详情读取失败：重新连接车机"
        reason == "maintenance_package_check_failed" ->
            "车机应用状态读取失败：重新连接车机"
        reason == "maintenance_inventory_refresh_failed" || reason == "maintenance_package_inventory_failed" ||
            reason == "maintenance_applications_invalid" ->
            "车机应用列表读取失败：重新连接车机"
        reason == "maintenance_catalog_metadata_invalid" ||
            reason == "maintenance_catalog_components_invalid" ||
            reason == "maintenance_catalog_source_invalid" ||
            reason == "maintenance_catalog_rollback" ->
            "维护配置无效：重新检查云端配置"
        reason == "maintenance_authorization_result_invalid" ->
            "车机授权结果格式错误：重新连接车机"
        reason == "maintenance_controller_unavailable" ->
            "维护操作模块未启动：重新打开应用"
        reason == "maintenance_application_action_failed" ->
            "车机未返回应用操作结果：重新读取应用状态"
        reason == "maintenance_action_failed" ->
            "维护操作未完成：重新连接车机后重试"
        reason == "maintenance_install_transition_invalid" || reason == "maintenance_manifest_selection_mismatch" ->
            "维护安装选择已失效：重新选择应用"
        reason == "maintenance_components_invalid" ->
            "维护应用清单无效：重新读取应用状态"
        reason == "maintenance_uninstall_postcondition_failed" ->
            "车机卸载后仍检测到应用：重新读取应用状态"
        reason == "maintenance_uninstall_postcondition_unknown" ->
            "车机卸载结果未读到：重新连接车机"
        reason == "maintenance_install_failed" || reason == "update_failed" ->
            "安装结果缺少车机回执：重新连接车机"
        reason.startsWith("maintenance_") ->
            "维护操作未完成：重新连接车机后重试"

        reason == "self_update_installer_unavailable" || reason == "self_update_unknown_sources_permission_required" ->
            "系统未允许安装未知来源应用：在设置中允许后继续"
        reason == "self_update_unknown_sources_settings_failed" ->
            "系统安装权限设置未保存：重新打开系统设置"
        reason == "self_update_uri_unavailable" ->
            "系统安装器无法打开更新文件：重新检查更新"
        reason == "self_update_installer_failed" ->
            "系统安装器启动失败：重新检查更新"
        reason == "self_update_package_missing" || reason == "self_update_artifact_unavailable" ||
            reason == "self_update_artifact_invalid" || reason == "self_update_stage_failed" ->
            "助手更新包未准备好：重新检查更新"
        reason == "self_update_version_not_newer" -> "当前助手已是目标版本：无需更新"
        reason == "self_update_readback_version_mismatch" ->
            "助手更新后版本与目标不一致：重新检查更新"
        reason == "self_update_readback_hash_mismatch" || reason == "self_update_apk_hash_mismatch" ->
            "助手更新包内容不一致：重新检查更新"
        reason == "self_update_readback_certificate_mismatch" ->
            "助手更新包签名不一致：重新检查更新"
        reason.startsWith("self_update_readback_") ->
            "助手更新结果读取失败：重新打开助手检查"
        reason.startsWith("self_update_") ->
            "助手更新未完成：重新检查更新"

        reason == "checkpoint_missing" -> "没有可恢复的安装记录：返回上一步并重新开始"
        else -> "安装流程数据无效：返回上一步并重新开始"
    }
    return if (label != null && shouldPrefixComponent(reason)) {
        "$label：$message"
    } else {
        message
    }
}

private fun shouldPrefixComponent(reason: String): Boolean =
    reason.startsWith("lanzou_folder_missing_") ||
        reason.startsWith("lanzou_folder_empty_") ||
        reason.startsWith("component_") ||
        reason.startsWith("artifact_app_") ||
        reason.startsWith("distribution_app_") ||
        reason.startsWith("apk_") ||
        reason.startsWith("install_apk_") ||
        reason.startsWith("installation_") ||
        reason.startsWith("authorization_") ||
        reason.startsWith("availability_") ||
        reason == "adb_pm_install_failed" ||
        reason == "adb_install_failed" ||
        reason == "adb_install_transport_failed" ||
        reason.startsWith("maintenance_component_") ||
        reason.startsWith("maintenance_package_")

private fun componentIdFromReason(reason: String): String? = when {
    reason.startsWith("lanzou_folder_missing_") -> reason.removePrefix("lanzou_folder_missing_")
    reason.startsWith("lanzou_folder_empty_") -> reason.removePrefix("lanzou_folder_empty_")
    else -> null
}

private fun friendlyComponentName(value: String): String = when (value) {
    "desktop" -> "03桌面"
    "lyrics" -> "03歌词"
    "cast" -> "03投屏"
    "file-manager" -> "文件管理器"
    "03helper", "helper" -> "03车机助手"
    else -> value
}
