package com.tcrrry.helper.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tcrrry.helper.domain.session.ComponentDescriptor
import com.tcrrry.helper.domain.session.ComponentResult
import com.tcrrry.helper.domain.session.DeviceConnectionStatus
import com.tcrrry.helper.domain.session.DeviceSummary
import com.tcrrry.helper.domain.session.FailureCategory
import com.tcrrry.helper.domain.session.InstallationSessionSnapshot
import com.tcrrry.helper.domain.session.InstallationSessionState
import com.tcrrry.helper.domain.session.SessionFailure
import com.tcrrry.helper.domain.session.SessionProgress
import com.tcrrry.helper.ui.InstallApp
import com.tcrrry.helper.ui.state.InstallUiIntent
import kotlinx.coroutines.delay

class DebugScenarioActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val scenario = intent.getStringExtra(EXTRA_SCENARIO).orEmpty()
        setContent {
            DebugScenarioRoot(scenario)
        }
    }

    companion object {
        const val EXTRA_SCENARIO = "scenario"
    }
}

@Composable
private fun DebugScenarioRoot(scenario: String) {
    val autoAdvance = scenario == DebugScenarioFixtures.FLOW
    var snapshot by remember(scenario) {
        mutableStateOf(DebugScenarioFixtures.snapshot(scenario))
    }

    LaunchedEffect(autoAdvance, snapshot.state, snapshot.discoveredDevices, snapshot.progress) {
        if (autoAdvance) {
            DebugScenarioFixtures.nextAutomaticSnapshot(snapshot)?.let { next ->
                delay(DebugScenarioFixtures.AUTO_STEP_DELAY_MILLIS)
                snapshot = next
            }
        }
    }

    InstallApp(
        snapshot = snapshot,
        onIntent = { intent -> snapshot = DebugScenarioFixtures.reduce(snapshot, intent) },
    )
}

internal object DebugScenarioFixtures {
    const val FLOW = "flow"
    const val AUTO_STEP_DELAY_MILLIS = 900L

    private val connectedDevice = DeviceSummary(
        id = "icar-03-demo",
        displayName = "iCAR 03",
        connectionStatus = DeviceConnectionStatus.CONFIRMED,
        lastConfirmedLabel = "刚刚确认",
    )

    private val components = listOf(
        ComponentDescriptor(
            id = "lyrics",
            displayName = "03歌词",
            required = true,
            versionLabel = "1.14",
            sizeLabel = "18 MB",
            compatibilityLabel = "适用于当前车机",
        ),
        ComponentDescriptor(
            id = "desktop",
            displayName = "03桌面",
            required = true,
            versionLabel = "0.1",
            sizeLabel = "12 MB",
            compatibilityLabel = "适用于当前车机",
        ),
        ComponentDescriptor(
            id = "file-manager",
            displayName = "文件管理器",
            required = false,
            versionLabel = "1.6.1",
            sizeLabel = "9 MB",
            compatibilityLabel = "可选增强",
        ),
    )

    private val successResults = components.map { component ->
        ComponentResult(
            componentName = component.displayName,
            installed = true,
            configured = true,
            available = true,
        )
    }

    fun snapshot(scenario: String): InstallationSessionSnapshot = when (scenario) {
        FLOW,
        "searching",
        -> base(InstallationSessionState.DISCOVERING)

        "found" -> base(
            state = InstallationSessionState.DISCOVERING,
            discoveredDevices = listOf(connectedDevice),
        )

        "selection" -> base(InstallationSessionState.CONNECTED)
        "progress" -> base(
            state = InstallationSessionState.DOWNLOADING_ARCHIVE,
            currentComponentName = "03歌词",
            progress = SessionProgress(completedCount = 0, totalCount = 3, fraction = 0.42f),
        )

        "success" -> base(
            state = InstallationSessionState.SUCCEEDED,
            componentResults = successResults,
        )

        "paused" -> base(
            state = InstallationSessionState.PAUSED,
            currentComponentName = "03桌面",
        )

        "failed" -> base(
            state = InstallationSessionState.FAILED,
            currentComponentName = "03歌词",
            failure = SessionFailure(FailureCategory.DOWNLOAD, componentName = "03歌词"),
        )

        "maintenance" -> base(InstallationSessionState.MAINTENANCE)
        "maintenance-disconnected" -> base(
            state = InstallationSessionState.MAINTENANCE,
            device = connectedDevice.copy(connectionStatus = DeviceConnectionStatus.DISCONNECTED),
        )

        else -> InstallationSessionSnapshot(state = InstallationSessionState.IDLE)
    }

    fun reduce(
        snapshot: InstallationSessionSnapshot,
        intent: InstallUiIntent,
    ): InstallationSessionSnapshot = when (intent) {
        InstallUiIntent.StopDiscovery -> InstallationSessionSnapshot(state = InstallationSessionState.IDLE)
        InstallUiIntent.RetryDiscovery -> base(InstallationSessionState.DISCOVERING)
        InstallUiIntent.Reconnect -> base(
            state = InstallationSessionState.DISCOVERING,
            discoveredDevices = listOf(connectedDevice),
        )

        is InstallUiIntent.SelectDevice -> base(InstallationSessionState.CONNECTED)
        is InstallUiIntent.ToggleOptionalComponent -> snapshot.copy(
            selectedOptionalComponentIds = if (intent.selected) {
                snapshot.selectedOptionalComponentIds + intent.componentId
            } else {
                snapshot.selectedOptionalComponentIds - intent.componentId
            },
        )

        InstallUiIntent.StartInstallation -> snapshot.copy(
            state = InstallationSessionState.SELECTION_CONFIRMED,
            currentComponentName = "03歌词",
            progress = SessionProgress(completedCount = 0, totalCount = 3, indeterminate = true),
        )

        InstallUiIntent.CancelInstallation -> snapshot.copy(state = InstallationSessionState.PAUSED)
        InstallUiIntent.ContinueInstallation,
        InstallUiIntent.RetryInstallation,
        InstallUiIntent.Reconfigure,
        -> base(
            state = InstallationSessionState.RESOLVING_SOURCE,
            currentComponentName = snapshot.currentComponentName ?: "03歌词",
            progress = SessionProgress(completedCount = 0, totalCount = 3, indeterminate = true),
        )

        InstallUiIntent.EnterMaintenance -> base(InstallationSessionState.MAINTENANCE)
        is InstallUiIntent.MaintenanceAction -> snapshot
    }

    fun nextAutomaticSnapshot(snapshot: InstallationSessionSnapshot): InstallationSessionSnapshot? = when (snapshot.state) {
        InstallationSessionState.DISCOVERING -> if (snapshot.discoveredDevices.isEmpty()) {
            snapshot.copy(discoveredDevices = listOf(connectedDevice))
        } else {
            snapshot.copy(state = InstallationSessionState.CONNECTED)
        }

        InstallationSessionState.CONNECTED -> snapshot.copy(
            state = InstallationSessionState.SELECTION_CONFIRMED,
            currentComponentName = "03歌词",
            progress = SessionProgress(completedCount = 0, totalCount = 3, indeterminate = true),
        )

        InstallationSessionState.SELECTION_CONFIRMED -> snapshot.copy(
            state = InstallationSessionState.RESOLVING_SOURCE,
            progress = SessionProgress(completedCount = 0, totalCount = 3, indeterminate = true),
        )

        InstallationSessionState.RESOLVING_SOURCE -> snapshot.copy(
            state = InstallationSessionState.DOWNLOADING_ARCHIVE,
            progress = SessionProgress(completedCount = 0, totalCount = 3, fraction = 0.28f),
        )

        InstallationSessionState.DOWNLOADING_ARCHIVE -> if ((snapshot.progress?.fraction ?: 0f) < 0.75f) {
            snapshot.copy(progress = snapshot.progress?.copy(fraction = 0.78f))
        } else {
            snapshot.copy(
                state = InstallationSessionState.VERIFYING_ARCHIVE,
                progress = SessionProgress(completedCount = 1, totalCount = 3, indeterminate = true),
            )
        }

        InstallationSessionState.VERIFYING_ARCHIVE -> snapshot.copy(state = InstallationSessionState.INSTALLING)
        InstallationSessionState.INSTALLING -> snapshot.copy(state = InstallationSessionState.AUTHORIZING)
        InstallationSessionState.AUTHORIZING -> snapshot.copy(state = InstallationSessionState.VERIFYING_DEVICE)
        InstallationSessionState.VERIFYING_DEVICE -> snapshot.copy(
            state = InstallationSessionState.SUCCEEDED,
            progress = SessionProgress(completedCount = 3, totalCount = 3, fraction = 1f),
            componentResults = successResults,
        )

        else -> null
    }

    private fun base(
        state: InstallationSessionState,
        device: DeviceSummary? = connectedDevice,
        discoveredDevices: List<DeviceSummary> = emptyList(),
        currentComponentName: String? = null,
        progress: SessionProgress? = null,
        failure: SessionFailure? = null,
        componentResults: List<ComponentResult> = emptyList(),
    ): InstallationSessionSnapshot = InstallationSessionSnapshot(
        state = state,
        device = device,
        discoveredDevices = discoveredDevices,
        components = components,
        selectedOptionalComponentIds = setOf("file-manager"),
        currentComponentName = currentComponentName,
        progress = progress,
        failure = failure,
        componentResults = componentResults,
    )
}
