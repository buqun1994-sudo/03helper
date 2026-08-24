package com.ninepointnine.helper.debug

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
import com.ninepointnine.helper.domain.session.ComponentCheck
import com.ninepointnine.helper.domain.session.ComponentDescriptor
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.session.DeviceSummary
import com.ninepointnine.helper.domain.session.InstallationSession
import com.ninepointnine.helper.domain.session.InstallationSessionCommand
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.domain.session.InstallationSessionState
import com.ninepointnine.helper.domain.session.MaintenanceSnapshot
import com.ninepointnine.helper.domain.session.ManagedApplicationStatus
import com.ninepointnine.helper.domain.session.ManagedApplicationDetails
import com.ninepointnine.helper.domain.session.MaintenanceApplicationActionId
import com.ninepointnine.helper.domain.session.MaintenanceUpdateState
import com.ninepointnine.helper.domain.session.MaintenanceUpdateStatus
import com.ninepointnine.helper.domain.device.ManagedApplicationAuthorizationStatus
import com.ninepointnine.helper.domain.device.MaintenanceAuthorizationState
import com.ninepointnine.helper.ui.InstallApp
import com.ninepointnine.helper.toInstallationSessionCommand
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
        if (scenario == "maintenance" || scenario == "maintenance-disconnected") {
            InstallationSession(
                initialSnapshot = DebugScenarioFixtures.maintenanceSnapshot(
                    disconnected = scenario == "maintenance-disconnected",
                ),
            )
        } else {
            InstallationSession(componentCatalog = DebugScenarioFixtures.components)
        }
    }
    val snapshot by session.snapshots.collectAsState()

    LaunchedEffect(session, scenario) {
        if (scenario != "maintenance" && scenario != "maintenance-disconnected") {
            DebugScenarioFixtures.play(session, scenario)
        }
    }
    DisposableEffect(session) {
        onDispose { session.close() }
    }

    InstallApp(
        snapshot = snapshot,
        onIntent = { intent ->
            val command = intent.toInstallationSessionCommand()
            session.dispatch(command)
            when (command) {
                is InstallationSessionCommand.MaintenanceAction -> {
                    if (session.currentSnapshot().maintenance.activeAction == command.actionId) {
                        if (command.actionId == com.ninepointnine.helper.domain.session.MaintenanceActionId.REPAIR_CONFIGURATION) {
                            val beforeCheck = session.currentSnapshot().maintenance.authorization.state !=
                                com.ninepointnine.helper.domain.session.MaintenanceAuthorizationFlowState.NOT_STARTED
                            val statuses = session.currentSnapshot().maintenance.managedApplications.map { application ->
                                ManagedApplicationAuthorizationStatus(
                                    componentId = application.componentId,
                                    packageName = application.packageName,
                                    authorized = true,
                                    state = MaintenanceAuthorizationState.AUTHORIZED,
                                )
                            }
                            session.dispatchEvent(
                                InstallationSessionEvent.MaintenanceAuthorizationCheckStarted(
                                    componentIds = statuses.map { it.componentId },
                                ),
                            )
                            statuses.forEach { status ->
                                session.dispatchEvent(
                                    InstallationSessionEvent.MaintenanceAuthorizationCheckProgress(
                                        componentId = status.componentId,
                                        status = status,
                                    ),
                                )
                            }
                            session.dispatchEvent(InstallationSessionEvent.MaintenanceAuthorizationChecked(statuses))
                            session.dispatchEvent(
                                InstallationSessionEvent.MaintenanceActionCompleted(
                                    actionId = command.actionId,
                                    resultCode = if (beforeCheck) "authorization_repaired" else "authorization_checked",
                                ),
                            )
                        } else {
                            session.dispatchEvent(
                                InstallationSessionEvent.MaintenanceActionCompleted(
                                    actionId = command.actionId,
                                    resultCode = "debug_completed",
                                ),
                            )
                        }
                    }
                }

                is InstallationSessionCommand.MaintenanceApplicationAction -> {
                    val application = session.currentSnapshot().maintenance.managedApplications
                        .firstOrNull { it.componentId == command.componentId }
                    if (application != null) {
                        if (command.actionId == MaintenanceApplicationActionId.DETAILS) {
                            session.dispatchEvent(
                                InstallationSessionEvent.MaintenanceApplicationDetailsResolved(
                                    ManagedApplicationDetails(
                                        componentId = application.componentId,
                                        displayName = session.currentSnapshot().components
                                            .firstOrNull { it.id == application.componentId }
                                            ?.displayName.orEmpty(),
                                        packageName = application.packageName,
                                        versionLabel = application.versionLabel,
                                        versionCode = application.versionCode,
                                        fileSizeBytes = application.fileSizeBytes,
                                        installTimeEpochMillis = application.installTimeEpochMillis,
                                        updateTimeEpochMillis = application.updateTimeEpochMillis,
                                        filePath = application.filePath,
                                        uid = application.uid,
                                    ),
                                ),
                            )
                        } else {
                            session.dispatchEvent(
                                InstallationSessionEvent.MaintenanceApplicationActionCompleted(
                                    componentId = command.componentId,
                                    actionId = command.actionId,
                                    resultCode = "debug_completed",
                                ),
                            )
                        }
                    }
                }

                else -> Unit
            }
        },
    )
}

internal object DebugScenarioFixtures {
    const val FLOW = "flow"
    const val AUTO_STEP_DELAY_MILLIS = 300L

    val components = listOf(
        ComponentDescriptor(
            id = "desktop",
            displayName = "03桌面",
            required = true,
            versionLabel = "0.1",
            sizeLabel = "12 MB",
            compatibilityLabel = null,
        ),
        ComponentDescriptor(
            id = "lyrics",
            displayName = "03歌词",
            required = false,
            versionLabel = "1.14",
            sizeLabel = "18 MB",
            compatibilityLabel = null,
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

    fun maintenanceSnapshot(disconnected: Boolean): InstallationSessionSnapshot {
        val applications = components.mapIndexed { index, component ->
            ManagedApplicationStatus(
                componentId = component.id,
                packageName = when (component.id) {
                    "desktop" -> "com.tcrrry.desktop"
                    "lyrics" -> "com.tcrrry.desktoplyrics"
                    else -> "org.fossify.filemanager.debug"
                },
                installed = component.id != "file-manager",
                versionLabel = component.versionLabel,
                versionCode = (index + 1).toLong(),
                fileSizeBytes = (index + 1) * 1024L * 1024L,
                installTimeEpochMillis = 1_706_700_000_000L + index * 3_600_000L,
                updateTimeEpochMillis = 1_724_000_000_000L + index * 3_600_000L,
                filePath = "/data/app/${component.id}/base.apk",
                uid = 10_000 + index,
            )
        }
        return InstallationSessionSnapshot(
            state = InstallationSessionState.MAINTENANCE,
            device = connectedDevice.copy(
                connectionStatus = if (disconnected) {
                    DeviceConnectionStatus.DISCONNECTED
                } else {
                    DeviceConnectionStatus.CONFIRMED
                },
            ),
            components = components,
            maintenance = MaintenanceSnapshot(
                managedApplications = applications,
                updateStatuses = listOf(
                    MaintenanceUpdateStatus(
                        componentId = "03helper",
                        displayName = "03车机助手",
                        versionLabel = "0.1",
                        installedVersionLabel = "0.1",
                        state = MaintenanceUpdateState.CURRENT,
                        isSelf = true,
                        iconKey = "03helper",
                    ),
                    MaintenanceUpdateStatus(
                        componentId = "desktop",
                        displayName = "03桌面",
                        versionLabel = "1.2",
                        installedVersionLabel = "0.1",
                        state = MaintenanceUpdateState.UPDATE_AVAILABLE,
                        iconKey = "desktop",
                    ),
                    MaintenanceUpdateStatus(
                        componentId = "lyrics",
                        displayName = "03歌词",
                        versionLabel = "1.14",
                        installedVersionLabel = "1.14",
                        state = MaintenanceUpdateState.CURRENT,
                        iconKey = "lyrics",
                    ),
                    MaintenanceUpdateStatus(
                        componentId = "file-manager",
                        displayName = "文件管理器",
                        versionLabel = "1.6.1",
                        installedVersionLabel = null,
                        state = MaintenanceUpdateState.NOT_INSTALLED,
                        iconKey = "file-manager",
                    ),
                ),
            ),
        )
    }

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
                InstallationSessionCommand.ToggleOptionalComponent("lyrics", selected = true),
                animated,
            )
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
