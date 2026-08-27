package com.ninepointnine.helper.application.device

import com.ninepointnine.helper.application.artifact.PreparedArtifact
import com.ninepointnine.helper.application.session.InstallationSessionEventPort
import com.ninepointnine.helper.domain.device.AuthorizationDeclarationValidator
import com.ninepointnine.helper.domain.device.AuthorizationPlanBuildResult
import com.ninepointnine.helper.domain.device.AuthorizationPlanFactory
import com.ninepointnine.helper.domain.device.DeviceActionConnectionLease
import com.ninepointnine.helper.domain.device.DeviceActionFailure
import com.ninepointnine.helper.domain.device.DeviceConnectionLease
import com.ninepointnine.helper.domain.device.DeviceInstallResult
import com.ninepointnine.helper.domain.device.DeviceShortcut
import com.ninepointnine.helper.domain.device.DeviceShortcutFailureStage
import com.ninepointnine.helper.domain.device.DeviceShortcutResult
import com.ninepointnine.helper.domain.device.DeviceAvailabilityEvidence
import com.ninepointnine.helper.domain.device.InstallableArtifact
import com.ninepointnine.helper.domain.session.ComponentCheck
import com.ninepointnine.helper.domain.session.ComponentProgressStatus
import com.ninepointnine.helper.domain.session.FailureCategory
import com.ninepointnine.helper.domain.session.InstallPhase
import com.ninepointnine.helper.domain.session.InstallationBatchPlan
import com.ninepointnine.helper.domain.session.InstallationFlow
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import com.ninepointnine.helper.domain.session.InstallationStrategy
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
    /** Plan-aware entry point used by the production composition root. */
    suspend fun execute(
        connection: DeviceConnectionLease,
        artifacts: List<PreparedArtifact>,
        batchPlan: InstallationBatchPlan,
    ): DeviceInstallationExecutionResult = execute(
        connection = connection,
        artifacts = artifacts,
        strategy = batchPlan.strategy,
        flow = batchPlan.flow,
        batchPlan = batchPlan,
    )

    suspend fun execute(
        connection: DeviceConnectionLease,
        artifacts: List<PreparedArtifact>,
        strategy: InstallationStrategy = InstallationStrategy.INSTALL_MISSING_ONLY,
        flow: InstallationFlow = InstallationFlow.INITIAL_INSTALL,
        batchPlan: InstallationBatchPlan? = null,
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
        batchPlan?.let { plan ->
            validateBatchPlan(plan, installable, strategy, flow)?.let { failure ->
                return failed(category = FailureCategory.INSTALLATION, failure = failure)
            }
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
        val installationEvidence = mutableListOf<com.ninepointnine.helper.domain.device.InstalledArtifactEvidence>()
        val writeConfirmedComponentIds = mutableSetOf<String>()
        val installationWarnings = mutableListOf<com.ninepointnine.helper.domain.device.DeviceInstallWarning>()

        fun consumeVerifiedEvidence(evidence: List<com.ninepointnine.helper.domain.device.InstalledArtifactEvidence>) {
            evidence.forEach { liveEvidence ->
                val artifact = installable.firstOrNull { it.manifest.componentId == liveEvidence.componentId }
                    ?: return@forEach
                if (!validateInstallationEvidence(listOf(artifact), listOf(liveEvidence))) return@forEach
                if (installedArtifacts.none { it.manifest.componentId == artifact.manifest.componentId }) {
                    installedArtifacts += artifact.copy(
                        declarations = artifact.declarations ?: liveEvidence.declarations,
                    )
                    installationEvidence += liveEvidence
                }
            }
        }

        installable.forEach { artifact ->
            if (artifact.apkFile != null) {
                emitProgress(artifact.manifest.componentId, InstallPhase.SEND, ComponentProgressStatus.RUNNING)
            }
            val result = try {
                gateway.install(listOf(artifact), strategy)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DeviceInstallResult.Failed(
                    DeviceActionFailure("install_exception", artifact.manifest.componentId, retryable = false),
                )
            }
            when (result) {
                is DeviceInstallResult.Failed -> {
                    writeConfirmedComponentIds += result.writeConfirmedComponentIds
                    installationWarnings += result.warnings
                    consumeVerifiedEvidence(result.verifiedEvidence)
                    recordFailure(artifact, installFailurePhase(result.failure), result.failure)
                }
                is DeviceInstallResult.Installed -> {
                    writeConfirmedComponentIds += result.writeConfirmedComponentIds
                    if (result.writeConfirmedComponentIds.isEmpty() && artifact.apkFile != null) {
                        // Legacy gateways do not expose the new receipt yet;
                        // a verified fresh artifact still proves the write.
                        writeConfirmedComponentIds += artifact.manifest.componentId
                    }
                    installationWarnings += result.warnings
                    if (validateInstallationEvidence(listOf(artifact), result.evidence)) {
                        // A reused package has no local APK. Its declaration
                        // receipt comes from the live APK readback and is
                        // required by the same authorization gate as a fresh
                        // package.
                        val liveEvidence = result.evidence.single()
                        installedArtifacts += artifact.copy(
                            declarations = artifact.declarations ?: liveEvidence.declarations,
                        )
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
                is DeviceInstallResult.WrittenButUnverified -> {
                    writeConfirmedComponentIds += result.writeConfirmedComponentIds
                    installationWarnings += result.warnings
                    consumeVerifiedEvidence(result.verifiedEvidence)
                    // PackageManager accepted the write, but this component is
                    // deliberately excluded from installedArtifacts and all
                    // authorization candidates until identity proof succeeds.
                    emitProgress(artifact.manifest.componentId, InstallPhase.SEND, ComponentProgressStatus.COMPLETED)
                    recordFailure(artifact, InstallPhase.VERIFY, result.failure)
                }
            }
        }
        eventPort.emit(
            InstallationSessionEvent.InstallationCompleted(
                checks = installedArtifacts.map { ComponentCheck(it.manifest.componentId, true) },
                evidence = installationEvidence,
                warnings = installationWarnings.toList(),
                writeConfirmedComponentIds = writeConfirmedComponentIds.toSet(),
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

        // A maintenance batch may carry a null APK for a package proven present
        // by the same inventory snapshot. Those components are a retained
        // baseline, not fresh work: they must be represented explicitly in the
        // events and must never cause a second desktop launch.
        val installedComponentIds = installedArtifacts.map { it.manifest.componentId }.toSet()
        val reusableComponentIds = if (batchPlan != null) {
            // The domain plan is the sole owner of reuse semantics. Intersect
            // with successful install evidence so a failed live readback can
            // never be treated as a preserved prerequisite.
            batchPlan.reusableComponentIds intersect installedComponentIds
        } else if (flow == InstallationFlow.MAINTENANCE_INSTALL) {
            // Compatibility path for older test adapters that do not yet pass
            // a plan. Production always takes the branch above.
            installedArtifacts.filter { it.apkFile == null }
                .map { it.manifest.componentId }
                .toSet()
        } else {
            emptySet()
        }
        val incrementalMaintenance = flow == InstallationFlow.MAINTENANCE_INSTALL &&
            AuthorizationPlanFactory.DESKTOP_COMPONENT_ID in reusableComponentIds
        val preservedComponentIds = if (incrementalMaintenance) reusableComponentIds else emptySet()
        val requireDesktop = !incrementalMaintenance
        val launchDesktop = !incrementalMaintenance
        val configuredArtifacts = mutableListOf<InstallableArtifact>()
        val authorizationEvidence = linkedMapOf<String, com.ninepointnine.helper.domain.device.AuthorizationActionEvidence>()
        var desktopRuntime: com.ninepointnine.helper.domain.device.ManagedApplicationAvailabilityEvidence? = null
        var authorizationCandidates = installedArtifacts
            .filterNot { it.manifest.componentId in preservedComponentIds }
            .toMutableList()
        val verificationFailures = mutableListOf<Pair<InstallableArtifact, DeviceActionFailure>>()

        // Declaration failures are deterministic and component-scoped. Remove
        // the offending optional component before the one device invocation so
        // a malformed optional APK does not block the desktop or its siblings.
        var preflightFailure = authorizationPreflightFailure(authorizationCandidates, requireDesktop)
        if (preflightFailure != null &&
            preflightFailure.componentId != AuthorizationPlanFactory.DESKTOP_COMPONENT_ID
        ) {
            val failedId = preflightFailure.componentId
            authorizationCandidates.firstOrNull { it.manifest.componentId == failedId }?.let { failedArtifact ->
                recordFailure(failedArtifact, InstallPhase.CONFIGURE, preflightFailure)
                authorizationCandidates = authorizationCandidates
                    .filterNot { it.manifest.componentId == failedId }
                    .toMutableList()
                preflightFailure = authorizationPreflightFailure(authorizationCandidates, requireDesktop)
            }
        }

        if (requireDesktop && (authorizationCandidates.isEmpty() || authorizationCandidates.none {
                it.manifest.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID
            })) {
            preflightFailure = preflightFailure ?: DeviceActionFailure(
                "desktop_prerequisite_failed",
                AuthorizationPlanFactory.DESKTOP_COMPONENT_ID,
                retryable = false,
            )
        }

        if (preflightFailure != null) {
            // No device write has happened in this branch. Keep the concrete
            // component reason, and mark other installed components as blocked
            // by the desktop prerequisite only when the desktop itself failed.
            val failedId = preflightFailure.componentId
            val desktopFailed = requireDesktop && (
                failedId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID || failedId == null
                )
            if (desktopFailed) {
                installedArtifacts.forEach { artifact ->
                    val failure = if (artifact.manifest.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID) {
                        preflightFailure.copy(componentId = artifact.manifest.componentId)
                    } else {
                        DeviceActionFailure(
                            "desktop_prerequisite_failed",
                            artifact.manifest.componentId,
                            retryable = preflightFailure.retryable,
                        )
                    }
                    recordFailure(artifact, InstallPhase.CONFIGURE, failure)
                }
            } else {
                // A residual global validation error is attributed to every
                // candidate because no subset was sent to the device.
                authorizationCandidates.forEach { artifact ->
                    recordFailure(
                        artifact,
                        InstallPhase.CONFIGURE,
                        preflightFailure.copy(componentId = artifact.manifest.componentId),
                    )
                }
            }
        } else if (authorizationCandidates.isNotEmpty()) {
            val authorizationAttempt = authorizeBatch(
                gateway = gateway,
                artifacts = authorizationCandidates,
                launchDesktop = launchDesktop,
                requireDesktop = requireDesktop,
            )
            when (authorizationAttempt) {
                is AuthorizationAttempt.Success -> {
                    val configuredIds = authorizationAttempt.configuredComponentIds
                    authorizationCandidates.forEach { artifact ->
                        if (artifact.manifest.componentId in configuredIds) {
                            configuredArtifacts += artifact
                            emitProgress(
                                artifact.manifest.componentId,
                                InstallPhase.CONFIGURE,
                                ComponentProgressStatus.COMPLETED,
                            )
                        } else {
                            recordFailure(
                                artifact,
                                InstallPhase.CONFIGURE,
                                DeviceActionFailure(
                                    "shortcut_selected_component_missing",
                                    artifact.manifest.componentId,
                                    retryable = false,
                                ),
                            )
                        }
                    }
                    authorizationAttempt.evidence.forEach { evidence ->
                        authorizationEvidence.putIfAbsent(evidence.actionId, evidence)
                    }
                    desktopRuntime = authorizationAttempt.desktopRuntime
                }

                is AuthorizationAttempt.Failure -> {
                    val failure = authorizationAttempt.failure
                    val configuredIds = authorizationAttempt.configuredComponentIds intersect
                        authorizationCandidates.map { it.manifest.componentId }.toSet()
                    configuredArtifacts += authorizationCandidates.filter {
                        it.manifest.componentId in configuredIds
                    }
                    authorizationAttempt.authorizationEvidence.forEach { evidence ->
                        authorizationEvidence.putIfAbsent(evidence.actionId, evidence)
                    }
                    desktopRuntime = authorizationAttempt.availabilityEvidence.firstOrNull {
                        it.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID
                    }?.let { evidence ->
                        com.ninepointnine.helper.domain.device.ManagedApplicationAvailabilityEvidence(
                            componentId = evidence.componentId,
                            packageName = evidence.packageName,
                            launchAttempted = evidence.launchAttempted,
                            launcherResolved = evidence.launcherResolved,
                            processRunning = evidence.processRunning,
                            requiredServiceBound = evidence.requiredServiceBound,
                        )
                    }
                    val desktopConfigured = AuthorizationPlanFactory.DESKTOP_COMPONENT_ID in configuredIds
                    val failedId = failure.componentId
                    // A receipt proves that a component's authorization actions
                    // completed. Components without that complete receipt were
                    // not reached (or are the concrete failing component).
                    authorizationCandidates.forEach { artifact ->
                        val id = artifact.manifest.componentId
                        if (id !in configuredIds) {
                            val componentFailure = when {
                                id == failedId -> failure.copy(componentId = id)
                                id in authorizationAttempt.skippedComponentIds -> DeviceActionFailure(
                                    "shortcut_selected_component_missing",
                                    id,
                                    retryable = false,
                                )
                                id == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID && !desktopConfigured && requireDesktop -> null
                                requireDesktop && !desktopConfigured && id != AuthorizationPlanFactory.DESKTOP_COMPONENT_ID ->
                                    DeviceActionFailure(
                                        "desktop_prerequisite_failed",
                                        id,
                                        retryable = failure.retryable,
                                    )
                                else -> DeviceActionFailure(
                                    "authorization_not_attempted",
                                    id,
                                    retryable = failure.retryable,
                                )
                            }
                            componentFailure?.let { recordFailure(artifact, InstallPhase.CONFIGURE, it) }
                        }
                    }
                    if (requireDesktop && !desktopConfigured) {
                        authorizationCandidates.firstOrNull {
                            it.manifest.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID
                        }?.let { artifact ->
                            val desktopFailure = if (failedId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID || failedId == null) {
                                failure.copy(componentId = artifact.manifest.componentId)
                            } else {
                                DeviceActionFailure(
                                    "authorization_not_attempted",
                                    artifact.manifest.componentId,
                                    retryable = failure.retryable,
                                )
                            }
                            recordFailure(artifact, InstallPhase.CONFIGURE, desktopFailure)
                        }
                    } else if (launchDesktop && desktopRuntime == null) {
                        // The device reached the post-authorization boundary but
                        // did not return a desktop launch/service receipt. Keep
                        // all installation and authorization proof, and report
                        // only the missing availability verification.
                        authorizationCandidates.firstOrNull {
                            it.manifest.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID
                        }?.let { artifact ->
                            verificationFailures += artifact to failure.copy(
                                reasonCode = if (authorizationAttempt.stage == DeviceShortcutFailureStage.VERIFICATION) {
                                    failure.reasonCode
                                } else {
                                    "desktop_verification_not_completed"
                                },
                                componentId = artifact.manifest.componentId,
                            )
                        }
                    }
                }
            }
        }
        eventPort.emit(
            InstallationSessionEvent.AuthorizationCompleted(
                checks = configuredArtifacts.map { ComponentCheck(it.manifest.componentId, true) },
                evidence = authorizationEvidence.values.toList(),
                preservedComponentIds = preservedComponentIds,
            ),
        )

        // Verification failures are emitted only after the authorization
        // checkpoint. This preserves the truthful "installed + authorized"
        // state for a package whose launch/service probe failed.
        verificationFailures.distinctBy { it.first.manifest.componentId }.forEach { (artifact, failure) ->
            recordFailure(artifact, InstallPhase.VERIFY, failure)
        }

        val availableEvidence = mutableListOf<DeviceAvailabilityEvidence>()
        val installationEvidenceByComponent = installationEvidence.associateBy { it.componentId }
        val failedVerificationIds = verificationFailures.map { it.first.manifest.componentId }.toSet()
        val configuredIds = configuredArtifacts.map { it.manifest.componentId }
            .filterNot { it in failedVerificationIds }
            .toSet()
        if (desktopRuntime != null && AuthorizationPlanFactory.DESKTOP_COMPONENT_ID in configuredIds) {
            val desktopManifest = desktop.manifest
            val runtime = desktopRuntime
            availableEvidence += DeviceAvailabilityEvidence(
                componentId = desktopManifest.componentId,
                packageName = desktopManifest.packageName,
                version = installationEvidenceByComponent[desktopManifest.componentId]?.version
                    ?: desktopManifest.apkVersion,
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
                    version = installationEvidenceByComponent[artifact.manifest.componentId]?.version
                        ?: artifact.manifest.apkVersion,
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
                preservedComponentIds = preservedComponentIds,
            ),
        )
        return DeviceInstallationExecutionResult.Completed
    }

    /**
     * Verifies the immutable batch contract before the first device write.
     * Preparation failures may legitimately omit fresh artifacts; reusable
     * artifacts may not be omitted because their live identity is part of the
     * batch baseline.
     */
    private fun validateBatchPlan(
        plan: InstallationBatchPlan,
        artifacts: List<InstallableArtifact>,
        strategy: InstallationStrategy,
        flow: InstallationFlow,
    ): DeviceActionFailure? {
        if (plan.flow != flow || plan.strategy != strategy) {
            return DeviceActionFailure("installation_batch_plan_invalid", retryable = false)
        }
        val byId = artifacts.associateBy { it.manifest.componentId }
        if (byId.size != artifacts.size) {
            return DeviceActionFailure("installation_batch_plan_invalid", retryable = false)
        }
        val selectedIds = plan.selectedComponentIds
        if (artifacts.any { it.manifest.componentId !in selectedIds }) {
            return DeviceActionFailure(
                "installation_batch_plan_invalid",
                artifacts.firstOrNull { it.manifest.componentId !in selectedIds }?.manifest?.componentId,
                retryable = false,
            )
        }
        val missingReusable = plan.reusableComponentIds.firstOrNull { it !in byId }
        if (missingReusable != null) {
            return DeviceActionFailure("installation_batch_plan_invalid", missingReusable, retryable = false)
        }
        if (plan.reusableComponentIds.any { byId[it]?.apkFile != null }) {
            return DeviceActionFailure(
                "installation_batch_plan_invalid",
                plan.reusableComponentIds.first { byId[it]?.apkFile != null },
                retryable = false,
            )
        }
        val invalidFresh = artifacts.firstOrNull { artifact ->
            artifact.manifest.componentId !in plan.reusableComponentIds && artifact.apkFile == null
        }
        if (invalidFresh != null) {
            return DeviceActionFailure(
                "installation_batch_plan_invalid",
                invalidFresh.manifest.componentId,
                retryable = false,
            )
        }
        // Initial installation never has a reusable baseline. Keeping this
        // invariant explicit prevents a maintenance-only null APK from
        // leaking through an old caller that labels itself as initial.
        if (flow == InstallationFlow.INITIAL_INSTALL && plan.reusableComponentIds.isNotEmpty()) {
            return DeviceActionFailure("installation_batch_plan_invalid", retryable = false)
        }
        return null
    }

    private fun authorizationPreflightFailure(
        artifacts: List<InstallableArtifact>,
        requireDesktop: Boolean = true,
    ): DeviceActionFailure? {
        if (artifacts.isEmpty()) return null
        val plan = when (val result = AuthorizationPlanFactory.createForComponents(
            artifacts.map { artifact ->
                com.ninepointnine.helper.domain.device.ManagedComponent(
                    componentId = artifact.manifest.componentId,
                    packageName = artifact.manifest.packageName,
                    setup = artifact.manifest.deviceSetup,
                    order = artifact.manifest.sortOrder,
                )
            },
            requireDesktop = requireDesktop,
        )) {
            is AuthorizationPlanBuildResult.Ready -> result.plan
            is AuthorizationPlanBuildResult.Rejected ->
                return DeviceActionFailure(result.reasonCode, artifacts.lastOrNull()?.manifest?.componentId, false)
        }
        return AuthorizationDeclarationValidator.validate(plan, artifacts)
    }

    private suspend fun authorizeBatch(
        gateway: com.ninepointnine.helper.domain.device.AdbCommandGateway,
        artifacts: List<InstallableArtifact>,
        launchDesktop: Boolean,
        requireDesktop: Boolean,
    ): AuthorizationAttempt {
        val plan = when (val result = AuthorizationPlanFactory.createForComponents(
            artifacts.map { artifact ->
                com.ninepointnine.helper.domain.device.ManagedComponent(
                    componentId = artifact.manifest.componentId,
                    packageName = artifact.manifest.packageName,
                    setup = artifact.manifest.deviceSetup,
                    order = artifact.manifest.sortOrder,
                )
            },
            requireDesktop = requireDesktop,
        )) {
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
                shortcut = if (launchDesktop) {
                    DeviceShortcut.CONFIGURE_ALL_INSTALLED_APPS_AND_START_DESKTOP
                } else {
                    DeviceShortcut.CONFIGURE_SELECTED_APPS
                },
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
            is DeviceShortcutResult.Failed -> AuthorizationAttempt.Failure(
                failure = result.failure,
                stage = result.stage,
                configuredComponentIds = result.configuredComponentIds,
                skippedComponentIds = result.skippedComponentIds,
                authorizationEvidence = result.authorizationEvidence,
                availabilityEvidence = result.availabilityEvidence,
            )
            is DeviceShortcutResult.Completed -> {
                val selectedActionIds = plan.actions.map { it.id }.toSet()
                val evidence = result.authorizationEvidence.filter { it.actionId in selectedActionIds }
                if (!AuthorizationPlanFactory.validateEvidence(plan, evidence)) {
                    AuthorizationAttempt.Failure(
                        DeviceActionFailure("authorization_evidence_invalid", artifacts.lastOrNull()?.manifest?.componentId, false),
                    )
                } else {
                    AuthorizationAttempt.Success(
                        evidence = evidence,
                        configuredComponentIds = result.configuredComponentIds intersect artifacts.map {
                            it.manifest.componentId
                        }.toSet(),
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
            val evidence: List<com.ninepointnine.helper.domain.device.AuthorizationActionEvidence>,
            val configuredComponentIds: Set<String>,
            val desktopRuntime: com.ninepointnine.helper.domain.device.ManagedApplicationAvailabilityEvidence?,
        ) : AuthorizationAttempt

        data class Failure(
            val failure: DeviceActionFailure,
            val stage: DeviceShortcutFailureStage = DeviceShortcutFailureStage.AUTHORIZATION,
            val configuredComponentIds: Set<String> = emptySet(),
            val skippedComponentIds: Set<String> = emptySet(),
            val authorizationEvidence: List<com.ninepointnine.helper.domain.device.AuthorizationActionEvidence> = emptyList(),
            val availabilityEvidence: List<com.ninepointnine.helper.domain.device.ManagedApplicationAvailabilityEvidence> = emptyList(),
        ) : AuthorizationAttempt
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

    private fun installFailurePhase(failure: DeviceActionFailure): InstallPhase = when {
        failure.reasonCode.contains("installed_") ||
            failure.reasonCode.contains("package_path") ||
            failure.reasonCode.contains("apk_read") ||
            failure.reasonCode.contains("apk_verify") -> InstallPhase.VERIFY

        else -> InstallPhase.SEND
    }

    private fun validateInstallationEvidence(
        artifacts: List<InstallableArtifact>,
        evidence: List<com.ninepointnine.helper.domain.device.InstalledArtifactEvidence>,
    ): Boolean {
        if (evidence.size != artifacts.size || evidence.map { it.componentId }.toSet().size != evidence.size) {
            return false
        }
        val expected = artifacts.associateBy { it.manifest.componentId }
        if (evidence.map { it.componentId }.toSet() != expected.keys) return false
        return evidence.all { installed ->
            val manifest = expected[installed.componentId]?.manifest ?: return@all false
            installed.packageName == manifest.packageName &&
                installed.certificateSha256.equals(manifest.certificateSha256, ignoreCase = true) &&
                installed.version.code == manifest.apkVersion.code &&
                (manifest.apkVersion.name.isBlank() || installed.version.name == manifest.apkVersion.name)
        }
    }

    private fun validateAvailabilityEvidence(
        artifacts: List<InstallableArtifact>,
        plan: com.ninepointnine.helper.domain.device.AuthorizationPlan,
        evidence: List<com.ninepointnine.helper.domain.device.DeviceAvailabilityEvidence>,
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
