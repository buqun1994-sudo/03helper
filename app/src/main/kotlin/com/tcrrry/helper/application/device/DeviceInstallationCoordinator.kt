package com.tcrrry.helper.application.device

import com.tcrrry.helper.application.artifact.PreparedArtifact
import com.tcrrry.helper.application.session.InstallationSessionEventPort
import com.tcrrry.helper.domain.device.AuthorizationDeclarationValidator
import com.tcrrry.helper.domain.device.AuthorizationPlanBuildResult
import com.tcrrry.helper.domain.device.AuthorizationPlanFactory
import com.tcrrry.helper.domain.device.DeviceActionConnectionLease
import com.tcrrry.helper.domain.device.DeviceActionFailure
import com.tcrrry.helper.domain.device.DeviceConnectionLease
import com.tcrrry.helper.domain.device.DeviceInstallResult
import com.tcrrry.helper.domain.device.DeviceShortcut
import com.tcrrry.helper.domain.device.DeviceShortcutFailureStage
import com.tcrrry.helper.domain.device.DeviceShortcutResult
import com.tcrrry.helper.domain.device.DeviceAvailabilityEvidence
import com.tcrrry.helper.domain.device.InstallableArtifact
import com.tcrrry.helper.domain.session.ComponentCheck
import com.tcrrry.helper.domain.session.ComponentProgressStatus
import com.tcrrry.helper.domain.session.FailureCategory
import com.tcrrry.helper.domain.session.InstallPhase
import com.tcrrry.helper.domain.session.InstallationSessionEvent
import kotlinx.coroutines.CancellationException

sealed interface DeviceInstallationExecutionResult {
    data object Completed : DeviceInstallationExecutionResult

    data object Failed : DeviceInstallationExecutionResult
}

/**
 * Bridges verified private APK files to the retained device lease. It owns the
 * F4 event sequence but leaves all state transitions to InstallationSession.
 */
class DeviceInstallationCoordinator(
    private val eventPort: InstallationSessionEventPort,
) {
    suspend fun execute(
        connection: DeviceConnectionLease,
        artifacts: List<PreparedArtifact>,
    ): DeviceInstallationExecutionResult {
        val actionConnection = connection as? DeviceActionConnectionLease
            ?: return failed(
                category = FailureCategory.INSTALLATION,
                failure = DeviceActionFailure("device_action_gateway_unavailable", retryable = false),
            )
        val orderedArtifacts = artifacts.sortedWith(compareBy<PreparedArtifact> { it.manifest.sortOrder }.thenBy { it.manifest.componentId })
        val installable = orderedArtifacts.map { artifact ->
            InstallableArtifact(
                manifest = artifact.manifest,
                apkFile = artifact.finalApk,
                declarations = artifact.declarations,
            )
        }
        val componentIds = installable.map { it.manifest.componentId }.toSet()
        if (componentIds.size != installable.size) {
            return failed(
                category = FailureCategory.INSTALLATION,
                failure = DeviceActionFailure("installable_artifacts_invalid", retryable = false),
            )
        }
        val gateway = actionConnection.commandGateway
        eventPort.emit(InstallationSessionEvent.InstallationStarted(componentIds.toList()))
        if (installable.isEmpty()) {
            eventPort.emit(InstallationSessionEvent.InstallationCompleted(emptyList(), emptyList()))
            eventPort.emit(InstallationSessionEvent.AuthorizationCompleted(emptyList(), emptyList()))
            eventPort.emit(InstallationSessionEvent.DeviceVerified(emptyList(), emptyList()))
            return DeviceInstallationExecutionResult.Completed
        }

        val installedArtifacts = mutableListOf<InstallableArtifact>()
        val installationEvidence = mutableListOf<com.tcrrry.helper.domain.device.InstalledArtifactEvidence>()
        installable.forEach { artifact ->
            emitProgress(artifact.manifest.componentId, InstallPhase.SEND, ComponentProgressStatus.RUNNING)
            val result = try {
                gateway.install(listOf(artifact))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DeviceInstallResult.Failed(
                    DeviceActionFailure("install_exception", artifact.manifest.componentId, retryable = false),
                )
            }
            when (result) {
                is DeviceInstallResult.Failed -> recordFailure(artifact, InstallPhase.SEND, result.failure)
                is DeviceInstallResult.Installed -> {
                    if (validateInstallationEvidence(listOf(artifact), result.evidence)) {
                        installedArtifacts += artifact
                        installationEvidence += result.evidence
                        emitProgress(artifact.manifest.componentId, InstallPhase.SEND, ComponentProgressStatus.COMPLETED)
                    } else {
                        recordFailure(
                            artifact,
                            InstallPhase.SEND,
                            DeviceActionFailure("installation_evidence_invalid", artifact.manifest.componentId, false),
                        )
                    }
                }
            }
        }
        eventPort.emit(
            InstallationSessionEvent.InstallationCompleted(
                checks = installedArtifacts.map { ComponentCheck(it.manifest.componentId, true) },
                evidence = installationEvidence,
            ),
        )

        val desktop = installedArtifacts.firstOrNull {
            it.manifest.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID
        }
        if (desktop == null) {
            installedArtifacts.forEach { artifact ->
                recordFailure(
                    artifact,
                    InstallPhase.CONFIGURE,
                    DeviceActionFailure("desktop_prerequisite_failed", artifact.manifest.componentId, false),
                )
            }
            eventPort.emit(InstallationSessionEvent.AuthorizationCompleted(emptyList(), emptyList()))
            eventPort.emit(InstallationSessionEvent.DeviceVerified(emptyList(), emptyList()))
            return DeviceInstallationExecutionResult.Completed
        }

        val configuredArtifacts = mutableListOf<InstallableArtifact>()
        // Each optional authorization group includes the desktop prerequisite.
        // Keep one evidence record per typed action so the final session proof
        // remains a plan-sized set instead of duplicating desktop actions.
        val authorizationEvidence = linkedMapOf<String, com.tcrrry.helper.domain.device.AuthorizationActionEvidence>()
        var desktopRuntime: com.tcrrry.helper.domain.device.ManagedApplicationAvailabilityEvidence? = null
        val desktopAttempt = authorizeGroup(gateway, listOf(desktop))
        if (desktopAttempt is AuthorizationAttempt.Success) {
            configuredArtifacts += desktop
            desktopAttempt.evidence.forEach { evidence ->
                authorizationEvidence.putIfAbsent(evidence.actionId, evidence)
            }
            desktopRuntime = desktopAttempt.desktopRuntime
            emitProgress(desktop.manifest.componentId, InstallPhase.CONFIGURE, ComponentProgressStatus.COMPLETED)
        } else {
            recordFailure(desktop, InstallPhase.CONFIGURE, (desktopAttempt as AuthorizationAttempt.Failure).failure)
        }

        if (desktopRuntime != null) {
            installedArtifacts.filter { it.manifest.componentId != AuthorizationPlanFactory.DESKTOP_COMPONENT_ID }
                .forEach { artifact ->
                    when (val attempt = authorizeGroup(gateway, listOf(desktop, artifact))) {
                        is AuthorizationAttempt.Success -> {
                            configuredArtifacts += artifact
                            attempt.evidence.forEach { evidence ->
                                authorizationEvidence.putIfAbsent(evidence.actionId, evidence)
                            }
                            emitProgress(artifact.manifest.componentId, InstallPhase.CONFIGURE, ComponentProgressStatus.COMPLETED)
                        }

                        is AuthorizationAttempt.Failure -> recordFailure(artifact, InstallPhase.CONFIGURE, attempt.failure)
                    }
                }
        } else {
            installedArtifacts.filter { it.manifest.componentId != AuthorizationPlanFactory.DESKTOP_COMPONENT_ID }
                .forEach { artifact ->
                    recordFailure(
                        artifact,
                        InstallPhase.CONFIGURE,
                        DeviceActionFailure("desktop_prerequisite_failed", artifact.manifest.componentId, false),
                    )
                }
        }
        eventPort.emit(
            InstallationSessionEvent.AuthorizationCompleted(
                checks = configuredArtifacts.map { ComponentCheck(it.manifest.componentId, true) },
                evidence = authorizationEvidence.values.toList(),
            ),
        )

        val availableEvidence = mutableListOf<DeviceAvailabilityEvidence>()
        val configuredIds = configuredArtifacts.map { it.manifest.componentId }.toSet()
        if (desktopRuntime != null && AuthorizationPlanFactory.DESKTOP_COMPONENT_ID in configuredIds) {
            val desktopManifest = desktop.manifest
            val runtime = desktopRuntime
            availableEvidence += DeviceAvailabilityEvidence(
                componentId = desktopManifest.componentId,
                packageName = desktopManifest.packageName,
                version = desktopManifest.apkVersion,
                installedArchiveVerified = true,
                launchAttempted = runtime.launchAttempted,
                launcherResolved = runtime.launcherResolved,
                processRunning = runtime.processRunning,
                requiredServiceBound = runtime.requiredServiceBound,
            )
        }
        configuredArtifacts.filter { it.manifest.componentId != AuthorizationPlanFactory.DESKTOP_COMPONENT_ID }
            .forEach { artifact ->
                availableEvidence += DeviceAvailabilityEvidence(
                    componentId = artifact.manifest.componentId,
                    packageName = artifact.manifest.packageName,
                    version = artifact.manifest.apkVersion,
                    installedArchiveVerified = true,
                    launchAttempted = false,
                    launcherResolved = false,
                    processRunning = false,
                    requiredServiceBound = null,
                )
                emitProgress(artifact.manifest.componentId, InstallPhase.VERIFY, ComponentProgressStatus.COMPLETED)
            }
        if (desktopRuntime != null && AuthorizationPlanFactory.DESKTOP_COMPONENT_ID in configuredIds) {
            emitProgress(AuthorizationPlanFactory.DESKTOP_COMPONENT_ID, InstallPhase.VERIFY, ComponentProgressStatus.COMPLETED)
        }
        eventPort.emit(
            InstallationSessionEvent.DeviceVerified(
                checks = availableEvidence.map { ComponentCheck(it.componentId, true) },
                evidence = availableEvidence,
            ),
        )
        return DeviceInstallationExecutionResult.Completed
    }

    private suspend fun authorizeGroup(
        gateway: com.tcrrry.helper.domain.device.AdbCommandGateway,
        artifacts: List<InstallableArtifact>,
    ): AuthorizationAttempt {
        val plan = when (val result = AuthorizationPlanFactory.create(artifacts)) {
            is AuthorizationPlanBuildResult.Ready -> result.plan
            is AuthorizationPlanBuildResult.Rejected -> return AuthorizationAttempt.Failure(
                DeviceActionFailure(result.reasonCode, artifacts.lastOrNull()?.manifest?.componentId, false),
            )
        }
        AuthorizationDeclarationValidator.validate(plan, artifacts)?.let {
            return AuthorizationAttempt.Failure(it)
        }
        val result = try {
            gateway.runShortcut(
                shortcut = DeviceShortcut.CONFIGURE_ALL_INSTALLED_APPS_AND_START_DESKTOP,
                selectedComponentIds = artifacts.map { it.manifest.componentId }.toSet(),
                authorizationPlan = plan,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return AuthorizationAttempt.Failure(
                DeviceActionFailure("authorization_exception", artifacts.lastOrNull()?.manifest?.componentId, false),
            )
        }
        return when (result) {
            is DeviceShortcutResult.Failed -> AuthorizationAttempt.Failure(result.failure)
            is DeviceShortcutResult.Completed -> {
                val selectedActionIds = plan.actions.map { it.id }.toSet()
                val evidence = result.authorizationEvidence.filter { it.actionId in selectedActionIds }
                if (!AuthorizationPlanFactory.validateEvidence(plan, evidence)) {
                    AuthorizationAttempt.Failure(
                        DeviceActionFailure("authorization_evidence_invalid", artifacts.lastOrNull()?.manifest?.componentId, false),
                    )
                } else if (!artifacts.all { it.manifest.componentId in result.configuredComponentIds }) {
                    AuthorizationAttempt.Failure(
                        DeviceActionFailure("shortcut_selected_component_missing", artifacts.lastOrNull()?.manifest?.componentId, false),
                    )
                } else {
                    AuthorizationAttempt.Success(
                        evidence = evidence,
                        desktopRuntime = result.availabilityEvidence.firstOrNull {
                            it.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID
                        },
                    )
                }
            }
        }
    }

    private sealed interface AuthorizationAttempt {
        data class Success(
            val evidence: List<com.tcrrry.helper.domain.device.AuthorizationActionEvidence>,
            val desktopRuntime: com.tcrrry.helper.domain.device.ManagedApplicationAvailabilityEvidence?,
        ) : AuthorizationAttempt

        data class Failure(val failure: DeviceActionFailure) : AuthorizationAttempt
    }

    private fun recordFailure(
        artifact: InstallableArtifact,
        phase: InstallPhase,
        failure: DeviceActionFailure,
    ) {
        emitProgress(artifact.manifest.componentId, phase, ComponentProgressStatus.FAILED, indeterminate = false)
        eventPort.emit(
            InstallationSessionEvent.ComponentFailed(
                componentId = artifact.manifest.componentId,
                phase = phase,
                reasonCode = failure.reasonCode,
                retryable = failure.retryable,
            ),
        )
    }

    private fun validateInstallationEvidence(
        artifacts: List<InstallableArtifact>,
        evidence: List<com.tcrrry.helper.domain.device.InstalledArtifactEvidence>,
    ): Boolean {
        if (evidence.size != artifacts.size || evidence.map { it.componentId }.toSet().size != evidence.size) {
            return false
        }
        val expected = artifacts.associateBy { it.manifest.componentId }
        if (evidence.map { it.componentId }.toSet() != expected.keys) return false
        return evidence.all { installed ->
            val manifest = expected[installed.componentId]?.manifest ?: return@all false
            installed.packageName == manifest.packageName &&
                installed.version == manifest.apkVersion &&
                installed.apkSizeBytes == manifest.apkSizeBytes &&
                installed.apkSha256.equals(manifest.apkSha256, ignoreCase = true) &&
                installed.certificateSha256.equals(manifest.certificateSha256, ignoreCase = true)
        }
    }

    private fun validateAvailabilityEvidence(
        artifacts: List<InstallableArtifact>,
        plan: com.tcrrry.helper.domain.device.AuthorizationPlan,
        evidence: List<com.tcrrry.helper.domain.device.DeviceAvailabilityEvidence>,
    ): Boolean {
        if (evidence.size != artifacts.size || evidence.map { it.componentId }.toSet().size != evidence.size) {
            return false
        }
        val expected = artifacts.associateBy { it.manifest.componentId }
        if (evidence.map { it.componentId }.toSet() != expected.keys) return false
        return evidence.all { item ->
            val artifact = expected[item.componentId] ?: return@all false
            val isLaunchTarget = item.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID
            item.packageName == artifact.manifest.packageName &&
                item.version == artifact.manifest.apkVersion &&
                item.installedArchiveVerified &&
                item.launchAttempted == isLaunchTarget &&
                if (isLaunchTarget) {
                    item.launcherResolved &&
                        item.processRunning &&
                        item.requiredServiceBound == true
                } else {
                    !item.launcherResolved &&
                        !item.processRunning &&
                        item.requiredServiceBound == null
                }
        }
    }

    private fun failed(
        category: FailureCategory,
        failure: DeviceActionFailure,
    ): DeviceInstallationExecutionResult {
        failure.componentId?.let { componentId ->
            emitProgress(
                componentId = componentId,
                phase = when (category) {
                    FailureCategory.INSTALLATION -> InstallPhase.SEND
                    FailureCategory.CONFIGURATION -> InstallPhase.CONFIGURE
                    else -> InstallPhase.VERIFY
                },
                status = ComponentProgressStatus.FAILED,
                indeterminate = false,
            )
        }
        if (failure.retryable) {
            eventPort.emit(
                InstallationSessionEvent.RecoverableError(
                    category = category,
                    componentName = failure.componentId,
                    reasonCode = failure.reasonCode,
                ),
            )
        } else {
            eventPort.emit(
                InstallationSessionEvent.FatalError(
                    category = category,
                    componentName = failure.componentId,
                    reasonCode = failure.reasonCode,
                ),
            )
        }
        return DeviceInstallationExecutionResult.Failed
    }

    private fun emitProgress(
        componentId: String,
        phase: InstallPhase,
        status: ComponentProgressStatus,
        indeterminate: Boolean = status == ComponentProgressStatus.RUNNING,
    ) {
        eventPort.emit(
            InstallationSessionEvent.ComponentProgressUpdated(
                componentId = componentId,
                phase = phase,
                status = status,
                fraction = if (status == ComponentProgressStatus.COMPLETED) 1f else null,
                indeterminate = indeterminate,
            ),
        )
    }

    private fun Set<String>.toChecks(): List<ComponentCheck> =
        sorted().map { componentId -> ComponentCheck(componentId, passed = true) }
}
