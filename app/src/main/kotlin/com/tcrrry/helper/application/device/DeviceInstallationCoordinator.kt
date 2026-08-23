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
        if (installable.isEmpty() || componentIds.size != installable.size) {
            return failed(
                category = FailureCategory.INSTALLATION,
                failure = DeviceActionFailure("installable_artifacts_invalid", retryable = false),
            )
        }
        val authorizationPlan = when (val result = AuthorizationPlanFactory.create(installable)) {
            is AuthorizationPlanBuildResult.Ready -> result.plan
            is AuthorizationPlanBuildResult.Rejected -> return failed(
                category = FailureCategory.CONFIGURATION,
                failure = DeviceActionFailure(result.reasonCode, retryable = false),
            )
        }
        installable.forEach { artifact ->
            emitProgress(artifact.manifest.componentId, InstallPhase.SEND, ComponentProgressStatus.RUNNING)
        }
        val gateway = actionConnection.commandGateway
        eventPort.emit(InstallationSessionEvent.InstallationStarted(componentIds.toList()))

        val installationEvidence = when (val result = gateway.install(installable)) {
            is DeviceInstallResult.Failed -> return failed(FailureCategory.INSTALLATION, result.failure)
            is DeviceInstallResult.Installed -> {
                if (!validateInstallationEvidence(installable, result.evidence)) {
                    return failed(
                        category = FailureCategory.INSTALLATION,
                        failure = DeviceActionFailure("installation_evidence_invalid", retryable = false),
                    )
                }
                eventPort.emit(
                    InstallationSessionEvent.InstallationCompleted(
                        checks = componentIds.toChecks(),
                        evidence = result.evidence,
                    ),
                )
                installable.forEach { artifact ->
                    emitProgress(artifact.manifest.componentId, InstallPhase.SEND, ComponentProgressStatus.COMPLETED)
                }
                result.evidence
            }
        }

        AuthorizationDeclarationValidator.validate(authorizationPlan, installable)?.let { failure ->
            return failed(FailureCategory.CONFIGURATION, failure)
        }

        when (val result = gateway.runShortcut(
            shortcut = DeviceShortcut.CONFIGURE_ALL_INSTALLED_APPS_AND_START_DESKTOP,
            selectedComponentIds = componentIds,
            authorizationPlan = authorizationPlan,
        )) {
            is DeviceShortcutResult.Failed -> return failed(
                category = if (result.stage == DeviceShortcutFailureStage.AUTHORIZATION) {
                    FailureCategory.CONFIGURATION
                } else {
                    FailureCategory.VERIFICATION
                },
                failure = result.failure,
            )

            is DeviceShortcutResult.Completed -> {
                if (!componentIds.all { it in result.configuredComponentIds }) {
                    return failed(
                        category = FailureCategory.CONFIGURATION,
                        failure = DeviceActionFailure("shortcut_selected_component_missing", retryable = false),
                    )
                }
                val selectedActionIds = authorizationPlan.actions.map { it.id }.toSet()
                val selectedAuthorizationEvidence = result.authorizationEvidence.filter { it.actionId in selectedActionIds }
                if (!AuthorizationPlanFactory.validateEvidence(authorizationPlan, selectedAuthorizationEvidence)) {
                    return failed(
                        category = FailureCategory.CONFIGURATION,
                        failure = DeviceActionFailure("authorization_evidence_invalid", retryable = false),
                    )
                }
                eventPort.emit(
                    InstallationSessionEvent.AuthorizationCompleted(
                        checks = componentIds.toChecks(),
                        evidence = selectedAuthorizationEvidence,
                    ),
                )
                installable.forEach { artifact ->
                    emitProgress(artifact.manifest.componentId, InstallPhase.CONFIGURE, ComponentProgressStatus.COMPLETED)
                }
                val desktopRuntime = result.availabilityEvidence.firstOrNull {
                    it.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID
                } ?: return failed(
                    category = FailureCategory.VERIFICATION,
                    failure = DeviceActionFailure(
                        "availability_evidence_missing",
                        AuthorizationPlanFactory.DESKTOP_COMPONENT_ID,
                        retryable = false,
                    ),
                )
                val selectedAvailabilityEvidence = installable.map { artifact ->
                    val isDesktop = artifact.manifest.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID
                    val runtime = if (isDesktop) desktopRuntime else null
                    DeviceAvailabilityEvidence(
                        componentId = artifact.manifest.componentId,
                        packageName = artifact.manifest.packageName,
                        version = artifact.manifest.apkVersion,
                        installedArchiveVerified = true,
                        launchAttempted = runtime?.launchAttempted ?: false,
                        launcherResolved = runtime?.launcherResolved ?: false,
                        processRunning = runtime?.processRunning ?: false,
                        requiredServiceBound = runtime?.requiredServiceBound,
                    )
                }
                if (!validateAvailabilityEvidence(installable, authorizationPlan, selectedAvailabilityEvidence)) {
                    return failed(
                        category = FailureCategory.VERIFICATION,
                        failure = DeviceActionFailure("availability_evidence_invalid", retryable = false),
                    )
                }
                eventPort.emit(
                    InstallationSessionEvent.DeviceVerified(
                        checks = componentIds.toChecks(),
                        evidence = selectedAvailabilityEvidence,
                    ),
                )
                installable.forEach { artifact ->
                    emitProgress(artifact.manifest.componentId, InstallPhase.VERIFY, ComponentProgressStatus.COMPLETED)
                }
                artifacts.forEach { artifact ->
                    runCatching { artifact.finalApk.delete() }
                }
                return DeviceInstallationExecutionResult.Completed
            }
        }
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
