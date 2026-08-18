package com.tcrrry.helper.ui.state

import com.tcrrry.helper.domain.session.ComponentDescriptor
import com.tcrrry.helper.domain.session.DeviceConnectionStatus
import com.tcrrry.helper.domain.session.FailureCategory
import com.tcrrry.helper.domain.session.InstallPhase
import com.tcrrry.helper.domain.session.InstallationSessionSnapshot
import com.tcrrry.helper.domain.session.InstallationSessionState
import com.tcrrry.helper.domain.session.ResultKind

object InstallUiStateMapper {
    fun map(snapshot: InstallationSessionSnapshot): InstallUiState = when (snapshot.state) {
        InstallationSessionState.IDLE -> connectionState(ConnectionVariant.NOT_FOUND, snapshot)
        InstallationSessionState.DISCOVERING -> connectionState(
            if (snapshot.discoveredDevices.isEmpty()) ConnectionVariant.SEARCHING else ConnectionVariant.FOUND,
            snapshot,
        )

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
        InstallationSessionState.PAUSED -> resultState(ResultKind.PAUSED, snapshot)
        InstallationSessionState.FAILED -> resultState(snapshot.failure.toResultKind(), snapshot)
        InstallationSessionState.MAINTENANCE -> InstallUiState.Maintenance(
            deviceName = snapshot.device?.displayName,
            connected = snapshot.device?.connectionStatus == DeviceConnectionStatus.CONFIRMED,
        )
    }

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
            ConnectionVariant.FAILED -> ConnectionAction.RECONNECT
            ConnectionVariant.FOUND,
            ConnectionVariant.NOT_FOUND,
            -> ConnectionAction.RETRY
        },
    )

    private fun selectionState(snapshot: InstallationSessionSnapshot): InstallUiState.Selection {
        val rows = snapshot.components.map { it.toUiRow(snapshot.selectedOptionalComponentIds) }
        val selected = rows.count { it.required || it.selected }
        val sizeLabel = rows.asSequence()
            .filter { it.required || it.selected }
            .mapNotNull { it.sizeLabel }
            .joinToString(" + ")
            .ifBlank { "待准备" }
        val selectedRows = rows.filter { it.required || it.selected }
        return InstallUiState.Selection(
            deviceName = snapshot.device?.displayName ?: "车机未连接",
            components = rows,
            summaryCount = selected,
            summarySizeLabel = sizeLabel,
            canStart = snapshot.device?.connectionStatus == DeviceConnectionStatus.CONFIRMED &&
                selectedRows.isNotEmpty() &&
                selectedRows.all {
                    !it.versionLabel.isNullOrBlank() &&
                        !it.sizeLabel.isNullOrBlank() &&
                        !it.compatibilityLabel.isNullOrBlank()
                },
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
            canCancel = true,
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
            )
        },
        canContinue = kind != ResultKind.SUCCESS,
    )

    private fun ComponentDescriptor.toUiRow(selectedOptionalIds: Set<String>): ComponentRow = ComponentRow(
        id = id,
        displayName = displayName,
        required = required,
        selected = required || id in selectedOptionalIds,
        versionLabel = versionLabel,
        sizeLabel = sizeLabel,
        compatibilityLabel = compatibilityLabel,
    )

    private fun com.tcrrry.helper.domain.session.SessionFailure?.toResultKind(): ResultKind = when (this?.category) {
        FailureCategory.DOWNLOAD,
        FailureCategory.ARCHIVE,
        -> ResultKind.DOWNLOAD_FAILED

        FailureCategory.CONFIGURATION -> ResultKind.CONFIGURATION_FAILED
        else -> ResultKind.INSTALLATION_FAILED
    }
}
