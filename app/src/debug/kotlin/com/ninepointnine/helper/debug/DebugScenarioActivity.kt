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
import com.ninepointnine.helper.domain.artifact.ApkExtractionEvidence
import com.ninepointnine.helper.domain.artifact.ArchiveDownloadEvidence
import com.ninepointnine.helper.domain.artifact.ArchiveVerificationEvidence
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactSource
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ArtifactVerification
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.CompatibilityRange
import com.ninepointnine.helper.domain.artifact.SourceSelectionEvidence
import com.ninepointnine.helper.domain.device.AuthorizationAction
import com.ninepointnine.helper.domain.device.AuthorizationActionEvidence
import com.ninepointnine.helper.domain.device.AuthorizationPlanBuildResult
import com.ninepointnine.helper.domain.device.AuthorizationPlanFactory
import com.ninepointnine.helper.domain.device.AuthorizationValueState
import com.ninepointnine.helper.domain.device.DeviceAvailabilityEvidence
import com.ninepointnine.helper.domain.device.InstalledArtifactEvidence
import com.ninepointnine.helper.domain.session.ArtifactCatalogStage
import com.ninepointnine.helper.domain.session.AuthorizationStageReceipt
import com.ninepointnine.helper.domain.session.AuthorizationStageReceiptStatus
import com.ninepointnine.helper.domain.session.AvailabilityStageReceipt
import com.ninepointnine.helper.domain.session.AvailabilityStageReceiptStatus
import com.ninepointnine.helper.domain.session.ComponentCheck
import com.ninepointnine.helper.domain.session.ComponentDescriptor
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.session.DeviceSummary
import com.ninepointnine.helper.domain.session.InstallationSession
import com.ninepointnine.helper.domain.session.InstallationBatchReceipt
import com.ninepointnine.helper.domain.session.InstallationComponentReceipt
import com.ninepointnine.helper.domain.session.InstallationStageReceipt
import com.ninepointnine.helper.domain.session.InstallationStageReceiptStatus
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
            InstallationSession(
                componentCatalog = DebugScenarioFixtures.components,
                initialSnapshot = InstallationSessionSnapshot(
                    state = InstallationSessionState.IDLE,
                    components = DebugScenarioFixtures.components,
                    artifactManifests = DebugScenarioFixtures.manifests,
                    artifactCatalogStage = ArtifactCatalogStage.PREPARED,
                ),
            )
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

    val manifests = components.mapIndexed { index, component ->
        val versionCode = (index + 1).toLong()
        ArtifactManifest(
            schemaVersion = 1,
            componentId = component.id,
            displayName = component.displayName,
            required = component.required,
            version = ArtifactVersion(component.versionLabel ?: "1.0", versionCode),
            compatibility = CompatibilityRange(minAndroidSdk = 26, maxAndroidSdk = 30),
            archiveFileName = "${component.id}.zip",
            archiveSizeBytes = (index + 1) * 1_024L,
            archiveSha256 = "1${index + 1}".repeat(32),
            apkEntryName = "${component.id}.apk",
            apkSizeBytes = (index + 1) * 512L,
            apkSha256 = "2${index + 1}".repeat(32),
            packageName = when (component.id) {
                AuthorizationPlanFactory.DESKTOP_COMPONENT_ID -> AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME
                AuthorizationPlanFactory.LYRICS_COMPONENT_ID -> AuthorizationPlanFactory.LYRICS_PACKAGE_NAME
                else -> AuthorizationPlanFactory.FILE_MANAGER_PACKAGE_NAME
            },
            apkVersion = ArtifactVersion(component.versionLabel ?: "1.0", versionCode),
            certificateSha256 = "33".repeat(32),
            sources = listOf(
                ArtifactSource(
                    kind = ArtifactSourceKind.LANZOU_SHARE,
                    url = "https://wwatl.lanzouw.com/i${component.id}",
                ),
            ),
        )
    }

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
        driver.sourceResolved(animated)
        driver.archivesDownloaded(animated)
        driver.archivesVerified(animated)
        driver.apksExtracted(animated)
        driver.artifactsVerified(animated)
        driver.event(InstallationSessionEvent.InstallationStarted(), animated)
        driver.event(
            InstallationSessionEvent.InstallationBatchCompleted(driver.successfulReceipt()),
            animated,
        )
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
        event(
            InstallationSessionEvent.DeviceConnectionConfirmed(DebugScenarioFixtures.connectedDevice),
            animated,
        )
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

    suspend fun sourceResolved(animated: Boolean) {
        event(
            InstallationSessionEvent.SourceResolved(
                sourceId = "debug-source",
                selections = preparationManifests().map {
                    SourceSelectionEvidence(it.componentId, ArtifactSourceKind.LANZOU_SHARE)
                },
            ),
            animated,
        )
    }

    suspend fun archivesDownloaded(animated: Boolean) {
        val manifests = preparationManifests()
        event(
            InstallationSessionEvent.ArchiveDownloaded(
                sizeBytes = manifests.sumOf { it.archiveSizeBytes },
                sha256 = manifests.first().archiveSha256,
                archives = manifests.map {
                    ArchiveDownloadEvidence(it.componentId, it.archiveSizeBytes, it.archiveSha256)
                },
            ),
            animated,
        )
    }

    suspend fun archivesVerified(animated: Boolean) {
        val manifests = preparationManifests()
        event(
            InstallationSessionEvent.ArchiveVerified(
                verified = true,
                verifications = manifests.map {
                    ArchiveVerificationEvidence(it.componentId, it.archiveSizeBytes, it.archiveSha256)
                },
            ),
            animated,
        )
    }

    suspend fun apksExtracted(animated: Boolean) {
        val manifests = preparationManifests()
        event(
            InstallationSessionEvent.ApkExtracted(
                entryName = manifests.first().apkEntryName,
                sizeBytes = manifests.sumOf { it.apkSizeBytes },
                sha256 = manifests.first().apkSha256,
                extractions = manifests.map {
                    ApkExtractionEvidence(it.componentId, it.apkEntryName, it.apkSizeBytes, it.apkSha256)
                },
            ),
            animated,
        )
    }

    suspend fun artifactsVerified(animated: Boolean) {
        val manifests = preparationManifests()
        event(
            InstallationSessionEvent.ArtifactsVerified(
                checks = manifests.map { ComponentCheck(it.componentId, passed = true) },
                verifications = manifests.map { manifest ->
                    ArtifactVerification(
                        componentId = manifest.componentId,
                        sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                        archiveSizeBytes = manifest.archiveSizeBytes,
                        archiveSha256 = manifest.archiveSha256,
                        apkSizeBytes = manifest.apkSizeBytes,
                        apkSha256 = manifest.apkSha256,
                        packageName = manifest.packageName,
                        apkVersion = manifest.apkVersion,
                        certificateSha256 = manifest.certificateSha256,
                        archiveDeleted = true,
                    )
                },
            ),
            animated,
        )
    }

    fun successfulReceipt(): InstallationBatchReceipt {
        val snapshot = session.currentSnapshot()
        val batch = checkNotNull(snapshot.installationBatch)
        val manifests = snapshot.artifactManifests
            .filter { it.componentId in batch.selectedComponentIds }
        val plan = when (val result = AuthorizationPlanFactory.createForManifests(
            manifests = manifests,
            requireDesktop = false,
        )) {
            is AuthorizationPlanBuildResult.Ready -> result.plan
            is AuthorizationPlanBuildResult.Rejected -> error(result.reasonCode)
        }
        val authorizationEvidence = plan.actions.map(::authorizationEvidence)
        val manifestsById = manifests.associateBy { it.componentId }
        return InstallationBatchReceipt(
            batchId = batch.batchId,
            components = snapshot.components
                .map { it.id }
                .filter { it in batch.selectedComponentIds }
                .map { componentId ->
                    val manifest = checkNotNull(manifestsById[componentId])
                    val componentAuthorization = authorizationEvidence.filter {
                        it.componentId == componentId
                    }
                    InstallationComponentReceipt(
                        componentId = componentId,
                        installation = InstallationStageReceipt(
                            status = InstallationStageReceiptStatus.VERIFIED,
                            evidence = InstalledArtifactEvidence(
                                componentId = componentId,
                                packageName = manifest.packageName,
                                version = manifest.apkVersion,
                                apkSizeBytes = manifest.apkSizeBytes,
                                apkSha256 = manifest.apkSha256,
                                certificateSha256 = manifest.certificateSha256,
                            ),
                            writeConfirmed = true,
                        ),
                        authorization = if (componentAuthorization.isEmpty()) {
                            AuthorizationStageReceipt(AuthorizationStageReceiptStatus.NOT_REQUIRED)
                        } else {
                            AuthorizationStageReceipt(
                                status = AuthorizationStageReceiptStatus.VERIFIED,
                                evidence = componentAuthorization,
                            )
                        },
                        availability = if (componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID) {
                            AvailabilityStageReceipt(
                                status = AvailabilityStageReceiptStatus.VERIFIED,
                                evidence = DeviceAvailabilityEvidence(
                                    componentId = componentId,
                                    packageName = manifest.packageName,
                                    version = manifest.apkVersion,
                                    installedArchiveVerified = true,
                                    launchAttempted = true,
                                    launcherResolved = true,
                                    processRunning = true,
                                    requiredServiceBound = true,
                                ),
                            )
                        } else {
                            AvailabilityStageReceipt(AvailabilityStageReceiptStatus.NOT_REQUIRED)
                        },
                    )
                },
        )
    }

    private fun preparationManifests(): List<ArtifactManifest> {
        val snapshot = session.currentSnapshot()
        val preparationIds = checkNotNull(snapshot.installationBatch).preparationComponentIds
        return snapshot.artifactManifests.filter { it.componentId in preparationIds }
    }

    private fun authorizationEvidence(action: AuthorizationAction): AuthorizationActionEvidence = when (action) {
        is AuthorizationAction.EnsureAppOpAllowed -> AuthorizationActionEvidence(
            componentId = action.componentId,
            actionId = action.id,
            before = AuthorizationValueState.DEFAULT,
            writeApplied = true,
            after = AuthorizationValueState.ALLOWED,
        )

        is AuthorizationAction.EnsureRuntimePermissionGranted -> AuthorizationActionEvidence(
            componentId = action.componentId,
            actionId = action.id,
            before = AuthorizationValueState.DENIED,
            writeApplied = true,
            after = AuthorizationValueState.GRANTED,
        )

        is AuthorizationAction.EnsureSecureSettingEnabled -> AuthorizationActionEvidence(
            componentId = action.componentId,
            actionId = action.id,
            before = AuthorizationValueState.DISABLED,
            writeApplied = true,
            after = AuthorizationValueState.ENABLED,
        )

        is AuthorizationAction.AppendSecureComponent -> AuthorizationActionEvidence(
            componentId = action.componentId,
            actionId = action.id,
            before = AuthorizationValueState.COMPONENT_ABSENT,
            writeApplied = true,
            after = AuthorizationValueState.COMPONENT_PRESENT,
            preservedEntryCount = 0,
        )
    }

    fun checks(): List<ComponentCheck> = session.currentSnapshot().components
        .filter {
            it.required || it.id in session.currentSnapshot().selectedOptionalComponentIds
        }
        .map { ComponentCheck(componentId = it.id, passed = true) }
}
