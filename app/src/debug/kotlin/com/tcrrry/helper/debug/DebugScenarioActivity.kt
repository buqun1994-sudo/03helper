package com.tcrrry.helper.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import com.tcrrry.helper.domain.session.ComponentCheck
import com.tcrrry.helper.domain.session.ComponentDescriptor
import com.tcrrry.helper.domain.session.DeviceConnectionStatus
import com.tcrrry.helper.domain.session.DeviceSummary
import com.tcrrry.helper.domain.session.InstallationSession
import com.tcrrry.helper.domain.session.InstallationSessionCommand
import com.tcrrry.helper.domain.session.InstallationSessionEvent
import com.tcrrry.helper.ui.InstallApp
import com.tcrrry.helper.toInstallationSessionCommand
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
    val session = remember(scenario) {
        InstallationSession(componentCatalog = DebugScenarioFixtures.components)
    }
    val snapshot by session.snapshots.collectAsState()

    LaunchedEffect(session, scenario) {
        DebugScenarioFixtures.play(session, scenario)
    }
    DisposableEffect(session) {
        onDispose { session.close() }
    }

    InstallApp(
        snapshot = snapshot,
        onIntent = { intent -> session.dispatch(intent.toInstallationSessionCommand()) },
    )
}

internal object DebugScenarioFixtures {
    const val FLOW = "flow"
    const val AUTO_STEP_DELAY_MILLIS = 300L

    val components = listOf(
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

    internal val connectedDevice = DeviceSummary(
        id = "icar-03-demo",
        displayName = "iCAR 03",
        connectionStatus = DeviceConnectionStatus.CONFIRMED,
        lastConfirmedLabel = "刚刚确认",
    )

    suspend fun play(session: InstallationSession, scenario: String) {
        val driver = FakeSessionDriver(session)
        when (scenario) {
            FLOW -> install(driver, includeOptional = true, animated = true)
            "searching" -> driver.command(InstallationSessionCommand.StartDiscovery)
            "found" -> driver.discover()
            "selection" -> driver.connect(selectOptional = false)
            "progress" -> {
                driver.connect(selectOptional = false)
                driver.beginInstallation()
                driver.event(InstallationSessionEvent.SourceResolved("debug-source"))
            }

            "success" -> install(driver, includeOptional = true, animated = false)
            "paused" -> {
                driver.connect(selectOptional = false)
                driver.beginInstallation()
                driver.event(InstallationSessionEvent.SourceResolved("debug-source"))
                driver.command(InstallationSessionCommand.CancelInstallation)
            }

            "failed" -> {
                driver.connect(selectOptional = false)
                driver.beginInstallation()
                driver.event(InstallationSessionEvent.SourceResolved("debug-source"))
                driver.event(InstallationSessionEvent.ArchiveDownloaded(1024L, "debug-archive-sha"))
                driver.event(InstallationSessionEvent.ArchiveVerified(verified = false))
            }

            "maintenance" -> {
                install(driver, includeOptional = true, animated = false)
                driver.command(InstallationSessionCommand.EnterMaintenance)
            }

            "maintenance-disconnected" -> {
                install(driver, includeOptional = true, animated = false)
                driver.command(InstallationSessionCommand.EnterMaintenance)
                driver.event(InstallationSessionEvent.DeviceDisconnected(deviceId = connectedDevice.id))
            }
        }
    }

    private suspend fun install(
        driver: FakeSessionDriver,
        includeOptional: Boolean,
        animated: Boolean,
    ) {
        driver.connect(selectOptional = includeOptional, animated = animated)
        driver.beginInstallation(animated)
        driver.event(InstallationSessionEvent.SourceResolved("debug-source"), animated)
        driver.event(InstallationSessionEvent.ArchiveDownloaded(1024L, "debug-archive-sha"), animated)
        driver.event(InstallationSessionEvent.ArchiveVerified(verified = true), animated)
        driver.event(InstallationSessionEvent.ApkExtracted("component.apk", 512L, "debug-apk-sha"), animated)
        driver.event(InstallationSessionEvent.ArtifactsVerified(driver.checks()), animated)
        driver.event(InstallationSessionEvent.InstallationStarted(), animated)
        driver.event(InstallationSessionEvent.InstallationCompleted(driver.checks()), animated)
        driver.event(InstallationSessionEvent.AuthorizationCompleted(driver.checks()), animated)
        driver.event(InstallationSessionEvent.DeviceVerified(driver.checks()), animated)
    }
}

private class FakeSessionDriver(
    private val session: InstallationSession,
) {
    suspend fun command(command: InstallationSessionCommand, animated: Boolean = false) {
        session.dispatch(command)
        if (animated) delay(DebugScenarioFixtures.AUTO_STEP_DELAY_MILLIS)
    }

    suspend fun event(event: InstallationSessionEvent, animated: Boolean = false) {
        session.dispatchEvent(event)
        if (animated) delay(DebugScenarioFixtures.AUTO_STEP_DELAY_MILLIS)
    }

    suspend fun discover(animated: Boolean = false) {
        command(InstallationSessionCommand.StartDiscovery, animated)
        event(InstallationSessionEvent.DeviceDiscovered(DebugScenarioFixtures.connectedDevice), animated)
    }

    suspend fun connect(selectOptional: Boolean, animated: Boolean = false) {
        discover(animated)
        command(InstallationSessionCommand.SelectDevice(DebugScenarioFixtures.connectedDevice.id), animated)
        if (selectOptional) {
            command(
                InstallationSessionCommand.ToggleOptionalComponent("file-manager", selected = true),
                animated,
            )
        }
    }

    suspend fun beginInstallation(animated: Boolean = false) {
        command(InstallationSessionCommand.StartInstallation, animated)
        command(InstallationSessionCommand.BeginPipeline, animated)
    }

    fun checks(): List<ComponentCheck> = session.currentSnapshot().components
        .filter {
            it.required || it.id in session.currentSnapshot().selectedOptionalComponentIds
        }
        .map { ComponentCheck(componentId = it.id, passed = true) }
}
