package com.ninepointnine.helper.application.device

import com.ninepointnine.helper.application.artifact.PreparedArtifact
import com.ninepointnine.helper.application.session.InstallationSessionBoundary
import com.ninepointnine.helper.domain.device.AdbCommandGateway
import com.ninepointnine.helper.domain.device.AuthorizationActionEvidence
import com.ninepointnine.helper.domain.device.AuthorizationDeclarationValidator
import com.ninepointnine.helper.domain.device.AuthorizationPlan
import com.ninepointnine.helper.domain.device.AuthorizationPlanBuildResult
import com.ninepointnine.helper.domain.device.AuthorizationPlanFactory
import com.ninepointnine.helper.domain.device.DeviceActionConnectionLease
import com.ninepointnine.helper.domain.device.DeviceActionFailure
import com.ninepointnine.helper.domain.device.DeviceAuthorizationConfirmation
import com.ninepointnine.helper.domain.device.DeviceAvailabilityEvidence
import com.ninepointnine.helper.domain.device.DeviceConnectionLease
import com.ninepointnine.helper.domain.device.DeviceInstallResult
import com.ninepointnine.helper.domain.device.DeviceInstallWarning
import com.ninepointnine.helper.domain.device.DeviceShortcut
import com.ninepointnine.helper.domain.device.DeviceShortcutFailureStage
import com.ninepointnine.helper.domain.device.DeviceShortcutResult
import com.ninepointnine.helper.domain.device.InstallableArtifact
import com.ninepointnine.helper.domain.device.InstalledArtifactEvidence
import com.ninepointnine.helper.domain.device.ManagedApplicationAvailabilityEvidence
import com.ninepointnine.helper.domain.device.ManagedComponent
import com.ninepointnine.helper.domain.session.AuthorizationStageReceipt
import com.ninepointnine.helper.domain.session.AuthorizationStageReceiptStatus
import com.ninepointnine.helper.domain.session.AvailabilityStageReceipt
import com.ninepointnine.helper.domain.session.AvailabilityStageReceiptStatus
import com.ninepointnine.helper.domain.session.ComponentProgressStatus
import com.ninepointnine.helper.domain.session.FailureCategory
import com.ninepointnine.helper.domain.session.InstallPhase
import com.ninepointnine.helper.domain.session.InstallationBatchPlan
import com.ninepointnine.helper.domain.session.InstallationBatchReceipt
import com.ninepointnine.helper.domain.session.InstallationComponentReceipt
import com.ninepointnine.helper.domain.session.InstallationFlow
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import com.ninepointnine.helper.domain.session.InstallationStageReceipt
import com.ninepointnine.helper.domain.session.InstallationStageReceiptStatus
import kotlinx.coroutines.CancellationException

sealed interface DeviceInstallationExecutionResult {
    data object Completed : DeviceInstallationExecutionResult

    data object Failed : DeviceInstallationExecutionResult

    /** The adapter call outlived its session generation; no terminal event was emitted. */
    data object Stale : DeviceInstallationExecutionResult
}

/**
 * Executes one immutable installation batch against one retained device lease.
 * Progress may be emitted while work is running; exactly one terminal receipt
 * crosses back into the installation session.
 */
class DeviceInstallationCoordinator(
    private val eventPort: InstallationSessionBoundary,
) {
    suspend fun executeBatch(
        connection: DeviceConnectionLease,
        artifacts: List<PreparedArtifact>,
        batchPlan: InstallationBatchPlan,
        preparationFailures: Map<String, DeviceActionFailure> = emptyMap(),
    ): DeviceInstallationExecutionResult {
        val gateway = (connection as? DeviceActionConnectionLease)?.commandGateway
            ?: return failed(
                category = FailureCategory.INSTALLATION,
                failure = DeviceActionFailure("device_action_gateway_unavailable", retryable = false),
            )
        val installable = artifacts
            .sortedWith(compareBy<PreparedArtifact> { it.manifest.sortOrder }.thenBy { it.manifest.componentId })
            .map { artifact ->
                InstallableArtifact(
                    manifest = artifact.manifest,
                    apkFile = artifact.finalApk,
                    declarations = artifact.declarations,
                )
            }
        validateBatchPlan(batchPlan, installable, preparationFailures)?.let { failure ->
            return failed(FailureCategory.INSTALLATION, failure)
        }

        eventPort.emit(
            InstallationSessionEvent.InstallationStarted(
                installable.map { it.manifest.componentId }.sorted(),
            ),
        )
        // Accept the batch only while its generation is still current. This
        // checkpoint must precede the first device write.
        if (!eventPort.isBatchActive(batchPlan.batchId)) {
            return DeviceInstallationExecutionResult.Stale
        }
        installable.filter { it.apkFile != null }.forEach { artifact ->
            emitProgress(artifact.manifest.componentId, InstallPhase.SEND, ComponentProgressStatus.RUNNING)
        }

        val deviceInstallResult = executeInstallBatch(gateway, installable, batchPlan)
        // A cancellation or a newer session generation may have raced the
        // non-cooperative ADB call. Its facts must not be authorized or
        // committed to the newer session.
        if (!eventPort.isBatchActive(batchPlan.batchId)) {
            return DeviceInstallationExecutionResult.Stale
        }
        // Once InstallationStarted crossed the session boundary, the batch
        // must always end in one receipt. Keep each classification boundary
        // fail-closed while preserving the already-confirmed installation
        // facts when a later stage throws unexpectedly.
        val installation = try {
            classifyInstallationResult(
                result = deviceInstallResult,
                artifacts = installable,
                selectedIds = batchPlan.selectedComponentIds,
                preparationFailures = preparationFailures,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            fallbackInstallationFacts(
                batchPlan = batchPlan,
                preparationFailures = preparationFailures,
                reasonCode = INSTALLATION_BATCH_EXECUTION_FAILED,
                result = deviceInstallResult,
                artifacts = installable,
            )
        }
        emitInstallationProgress(installation.stages)

        val postInstallation = try {
            executePostInstallation(
                gateway = gateway,
                artifacts = installable,
                batchPlan = batchPlan,
                installation = installation,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            fallbackPostInstallationFacts(
                batchPlan = batchPlan,
                installation = installation,
                reasonCode = AUTHORIZATION_BATCH_EXECUTION_FAILED,
            )
        }
        if (!eventPort.isBatchActive(batchPlan.batchId)) {
            return DeviceInstallationExecutionResult.Stale
        }
        val receipt = try {
            buildReceipt(batchPlan, installation, postInstallation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Complete only missing map entries. Facts already confirmed by
            // the adapter remain authoritative and must never be replaced by
            // an all-component failure receipt.
            val safeInstallation = completeInstallationFacts(
                batchPlan = batchPlan,
                facts = installation,
                preparationFailures = preparationFailures,
                reasonCode = INSTALLATION_BATCH_EXECUTION_FAILED,
            )
            val safePostInstallation = completePostInstallationFacts(
                batchPlan = batchPlan,
                installation = safeInstallation,
                facts = postInstallation,
                reasonCode = AUTHORIZATION_BATCH_EXECUTION_FAILED,
            )
            buildReceipt(
                batchPlan = batchPlan,
                installation = safeInstallation,
                postInstallation = safePostInstallation,
            )
        }
        if (!eventPort.isBatchActive(batchPlan.batchId)) {
            return DeviceInstallationExecutionResult.Stale
        }
        eventPort.emit(InstallationSessionEvent.InstallationBatchCompleted(receipt))
        return DeviceInstallationExecutionResult.Completed
    }

    private suspend fun executeInstallBatch(
        gateway: AdbCommandGateway,
        artifacts: List<InstallableArtifact>,
        batchPlan: InstallationBatchPlan,
    ): DeviceInstallResult = if (artifacts.isEmpty()) {
        DeviceInstallResult.Installed(emptyList())
    } else {
        try {
            gateway.installBatch(artifacts, batchPlan.strategy)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DeviceInstallResult.Failed(
                DeviceActionFailure("install_exception", retryable = false),
            )
        }
    }

    private fun classifyInstallationResult(
        result: DeviceInstallResult,
        artifacts: List<InstallableArtifact>,
        selectedIds: Set<String>,
        preparationFailures: Map<String, DeviceActionFailure>,
    ): InstallationFacts {
        val artifactsById = artifacts.associateBy { it.manifest.componentId }
        val deviceIds = artifactsById.keys
        val writeIds = result.writeConfirmedComponentIds()
        val evidence = result.verifiedEvidence()
        // Identity evidence itself proves that the device accepted the
        // operation. Union it with the explicit adapter receipt so mixed
        // reusable/fresh batches retain every confirmed operation fact.
        val operationIds = result.operationConfirmedComponentIds() + evidence.map { it.componentId } + writeIds
        val failure = result.failureOrNull()
        val confirmationPendingIds = (result as? DeviceInstallResult.WrittenButUnverified)
            ?.confirmationPendingComponentIds.orEmpty()
        val normalizedOperationIds = (operationIds + confirmationPendingIds).intersect(deviceIds)
        val normalizedWriteIds = writeIds.intersect(deviceIds)
        val duplicateEvidenceIds = evidence.groupingBy { it.componentId }
            .eachCount()
            .filterValues { it != 1 }
            .keys
        val evidenceById = evidence
            .filter { it.componentId in deviceIds }
            .groupBy { it.componentId }
            .filterKeys { it !in duplicateEvidenceIds }
            .mapNotNull { (componentId, values) ->
                val artifact = artifactsById[componentId] ?: return@mapNotNull null
                values.singleOrNull()
                    ?.takeIf { isInstallationEvidenceValid(artifact, it) }
                    ?.let { componentId to it }
            }
            .toMap()
        val invalidEvidenceIds = evidence
            .filter { it.componentId in deviceIds && it.componentId !in duplicateEvidenceIds }
            .filterNot { item ->
                artifactsById[item.componentId]?.let { artifact ->
                    isInstallationEvidenceValid(artifact, item)
                } == true
            }
            .mapTo(linkedSetOf()) { it.componentId }
        val protocolInvalid = writeIds.any { it !in deviceIds } ||
            operationIds.any { it !in deviceIds } ||
            confirmationPendingIds.any { it !in deviceIds } ||
            failure?.componentId?.let { it !in deviceIds } == true ||
            evidence.any { it.componentId !in deviceIds } ||
            duplicateEvidenceIds.isNotEmpty() || invalidEvidenceIds.isNotEmpty()
        val identityMismatch = failure?.let(::isInstalledIdentityMismatch) == true
        val stages = selectedIds.associateWith { componentId ->
            preparationFailures[componentId]?.let { preparationFailure ->
                return@associateWith InstallationStageReceipt(
                    status = InstallationStageReceiptStatus.NOT_ATTEMPTED,
                    reasonCode = preparationFailure.reasonCode,
                    retryable = preparationFailure.retryable,
                )
            }
            val verified = evidenceById[componentId]
            val componentProtocolInvalid = componentId in duplicateEvidenceIds || componentId in invalidEvidenceIds
            when (result) {
                is DeviceInstallResult.Installed -> when {
                    componentProtocolInvalid -> failedInstallation(
                        reasonCode = "installation_result_invalid",
                        retryable = false,
                        writeConfirmed = componentId in normalizedWriteIds,
                        operationConfirmed = componentId in normalizedOperationIds,
                    )
                    verified != null -> verifiedInstallation(
                        evidence = verified,
                        writeConfirmed = componentId in normalizedWriteIds,
                        operationConfirmed = componentId in normalizedOperationIds,
                    )
                    componentId in normalizedOperationIds -> pendingInstallation(
                        reasonCode = "installation_identity_unavailable",
                        retryable = true,
                        writeConfirmed = componentId in normalizedWriteIds,
                    )
                    else -> failedInstallation(
                        reasonCode = if (protocolInvalid) "installation_result_invalid" else "installation_evidence_missing",
                        retryable = !protocolInvalid,
                    )
                }

                is DeviceInstallResult.WrittenButUnverified -> when {
                    identityMismatch && componentId == failure?.componentId -> failedInstallation(
                        reasonCode = checkNotNull(failure).reasonCode,
                        retryable = failure.retryable,
                        writeConfirmed = componentId in normalizedWriteIds,
                        operationConfirmed = componentId in normalizedOperationIds,
                    )
                    componentId == result.failure.componentId && verified != null -> failedInstallation(
                        reasonCode = result.failure.reasonCode,
                        retryable = result.failure.retryable,
                        writeConfirmed = componentId in normalizedWriteIds,
                        operationConfirmed = componentId in normalizedOperationIds,
                    )
                    componentProtocolInvalid -> failedInstallation(
                        reasonCode = "installation_result_invalid",
                        retryable = false,
                        writeConfirmed = componentId in normalizedWriteIds,
                        operationConfirmed = componentId in normalizedOperationIds,
                    )
                    verified != null -> verifiedInstallation(
                        evidence = verified,
                        writeConfirmed = componentId in normalizedWriteIds,
                        operationConfirmed = componentId in normalizedOperationIds,
                    )
                    componentId in confirmationPendingIds || componentId in normalizedOperationIds ->
                        pendingInstallation(
                            reasonCode = result.failure.reasonCode,
                            retryable = result.failure.retryable,
                            writeConfirmed = componentId in normalizedWriteIds,
                        )
                    componentId == result.failure.componentId || result.failure.componentId == null ->
                        failedInstallation(result.failure.reasonCode, result.failure.retryable)
                    else -> failedInstallation("installation_evidence_missing", result.failure.retryable)
                }

                is DeviceInstallResult.Failed -> when {
                    identityMismatch && componentId == result.failure.componentId -> failedInstallation(
                        reasonCode = result.failure.reasonCode,
                        retryable = result.failure.retryable,
                        writeConfirmed = componentId in normalizedWriteIds,
                        operationConfirmed = componentId in normalizedOperationIds,
                    )
                    componentProtocolInvalid -> failedInstallation(
                        reasonCode = "installation_result_invalid",
                        retryable = false,
                        writeConfirmed = componentId in normalizedWriteIds,
                        operationConfirmed = componentId in normalizedOperationIds,
                    )
                    componentId == result.failure.componentId &&
                        isConfirmedInstallationWriteFailure(result.failure) -> failedInstallation(
                        reasonCode = result.failure.reasonCode,
                        retryable = result.failure.retryable,
                        writeConfirmed = componentId in normalizedWriteIds,
                        operationConfirmed = componentId in normalizedOperationIds,
                    )
                    componentId == result.failure.componentId && verified != null -> verifiedInstallation(
                        evidence = verified,
                        writeConfirmed = componentId in normalizedWriteIds,
                        operationConfirmed = componentId in normalizedOperationIds,
                    )
                    componentId == result.failure.componentId &&
                        componentId in normalizedOperationIds &&
                        isInstallationReadbackUncertainty(result.failure) -> pendingInstallation(
                        reasonCode = result.failure.reasonCode,
                        retryable = result.failure.retryable,
                        writeConfirmed = componentId in normalizedWriteIds,
                    )
                    componentId == result.failure.componentId -> failedInstallation(
                        reasonCode = result.failure.reasonCode,
                        retryable = result.failure.retryable,
                        writeConfirmed = componentId in normalizedWriteIds,
                        operationConfirmed = componentId in normalizedOperationIds,
                    )
                    verified != null -> verifiedInstallation(
                        evidence = verified,
                        writeConfirmed = componentId in normalizedWriteIds,
                        operationConfirmed = componentId in normalizedOperationIds,
                    )
                    componentId in normalizedOperationIds -> pendingInstallation(
                        reasonCode = result.failure.reasonCode,
                        retryable = result.failure.retryable,
                        writeConfirmed = componentId in normalizedWriteIds,
                    )
                    result.failure.componentId == null ->
                        failedInstallation(result.failure.reasonCode, result.failure.retryable)
                    else -> InstallationStageReceipt(
                        status = InstallationStageReceiptStatus.NOT_ATTEMPTED,
                        reasonCode = "installation_not_attempted_after_failure",
                        retryable = result.failure.retryable,
                    )
                }
            }
        }
        return InstallationFacts(
            stages = stages,
            evidenceById = evidenceById.filterKeys {
                stages[it]?.status == InstallationStageReceiptStatus.VERIFIED
            },
            warnings = result.validWarnings(selectedIds),
        )
    }

    private suspend fun executePostInstallation(
        gateway: AdbCommandGateway,
        artifacts: List<InstallableArtifact>,
        batchPlan: InstallationBatchPlan,
        installation: InstallationFacts,
    ): PostInstallationFacts {
        val authorization = linkedMapOf<String, AuthorizationStageReceipt>()
        val availability = linkedMapOf<String, AvailabilityStageReceipt>()
        val installedIds = installation.stages.filterValues {
            it.status == InstallationStageReceiptStatus.VERIFIED
        }.keys

        (batchPlan.selectedComponentIds - installedIds).forEach { componentId ->
            authorization[componentId] = authorizationNotAttempted(
                "authorization_not_attempted_installation_unverified",
            )
            availability[componentId] = availabilityNotAttempted(
                "availability_not_attempted_installation_unverified",
            )
        }
        (batchPlan.reusableComponentIds intersect installedIds).forEach { componentId ->
            authorization[componentId] = AuthorizationStageReceipt(AuthorizationStageReceiptStatus.PRESERVED)
            availability[componentId] = AvailabilityStageReceipt(AvailabilityStageReceiptStatus.PRESERVED)
        }

        val freshInstalledIds = installedIds - batchPlan.reusableComponentIds
        if (AuthorizationPlanFactory.DESKTOP_COMPONENT_ID !in installedIds) {
            freshInstalledIds.forEach { componentId ->
                authorization[componentId] = AuthorizationStageReceipt(
                    status = AuthorizationStageReceiptStatus.FAILED,
                    reasonCode = "desktop_prerequisite_failed",
                    retryable = false,
                )
                availability[componentId] = availabilityNotAttempted(
                    "availability_not_attempted_authorization_incomplete",
                )
            }
        } else if (freshInstalledIds.isNotEmpty()) {
            val freshArtifacts = artifacts.filter { it.manifest.componentId in freshInstalledIds }
            val requireDesktop = AuthorizationPlanFactory.DESKTOP_COMPONENT_ID !in batchPlan.reusableComponentIds
            val preparation = prepareAuthorizationBatch(freshArtifacts, requireDesktop)
            authorization.putAll(preparation.rejected)
            preparation.rejected.keys.forEach { componentId ->
                availability[componentId] = availabilityNotAttempted(
                    "availability_not_attempted_authorization_incomplete",
                )
            }
            if (preparation.plan != null && preparation.candidates.isNotEmpty()) {
                preparation.candidates.forEach { artifact ->
                    emitProgress(
                        artifact.manifest.componentId,
                        InstallPhase.CONFIGURE,
                        ComponentProgressStatus.RUNNING,
                    )
                }
                val shortcutResult = executeAuthorizationBatch(
                    gateway = gateway,
                    candidates = preparation.candidates,
                    plan = preparation.plan,
                    launchDesktop = requireDesktop,
                )
                val classified = classifyAuthorizationResult(
                    result = shortcutResult,
                    candidates = preparation.candidates,
                    plan = preparation.plan,
                    installationEvidence = installation.evidenceById,
                )
                authorization.putAll(classified.authorization)
                availability.putAll(classified.availability)
            }
        }

        batchPlan.selectedComponentIds.forEach { componentId ->
            authorization.putIfAbsent(
                componentId,
                authorizationNotAttempted("authorization_not_attempted"),
            )
            availability.putIfAbsent(
                componentId,
                availabilityNotAttempted("availability_not_attempted"),
            )
        }
        val facts = PostInstallationFacts(authorization, availability)
        emitPostInstallationProgress(facts, freshInstalledIds)
        return facts
    }

    private fun prepareAuthorizationBatch(
        artifacts: List<InstallableArtifact>,
        requireDesktop: Boolean,
    ): AuthorizationPreparation {
        var candidates = artifacts
        val rejected = linkedMapOf<String, AuthorizationStageReceipt>()
        while (candidates.isNotEmpty()) {
            val plan = when (val build = AuthorizationPlanFactory.createForComponents(
                candidates.map(::toManagedComponent),
                requireDesktop = requireDesktop,
            )) {
                is AuthorizationPlanBuildResult.Ready -> build.plan
                is AuthorizationPlanBuildResult.Rejected -> {
                    candidates.forEach { artifact ->
                        rejected[artifact.manifest.componentId] = AuthorizationStageReceipt(
                            status = AuthorizationStageReceiptStatus.FAILED,
                            reasonCode = build.reasonCode,
                            retryable = false,
                        )
                    }
                    return AuthorizationPreparation(emptyList(), null, rejected)
                }
            }
            val declarationFailure = AuthorizationDeclarationValidator.validate(plan, candidates)
                ?: return AuthorizationPreparation(candidates, plan, rejected)
            val failedId = declarationFailure.componentId
            val optionalFailure = failedId != null &&
                failedId != AuthorizationPlanFactory.DESKTOP_COMPONENT_ID &&
                candidates.any { it.manifest.componentId == failedId }
            if (optionalFailure) {
                rejected[checkNotNull(failedId)] = AuthorizationStageReceipt(
                    status = AuthorizationStageReceiptStatus.FAILED,
                    reasonCode = declarationFailure.reasonCode,
                    retryable = declarationFailure.retryable,
                )
                candidates = candidates.filterNot { it.manifest.componentId == failedId }
                continue
            }

            candidates.forEach { artifact ->
                val componentId = artifact.manifest.componentId
                rejected[componentId] = AuthorizationStageReceipt(
                    status = AuthorizationStageReceiptStatus.FAILED,
                    reasonCode = if (failedId == null || componentId == failedId) {
                        declarationFailure.reasonCode
                    } else {
                        "desktop_prerequisite_failed"
                    },
                    retryable = declarationFailure.retryable,
                )
            }
            return AuthorizationPreparation(emptyList(), null, rejected)
        }
        return AuthorizationPreparation(emptyList(), null, rejected)
    }

    private suspend fun executeAuthorizationBatch(
        gateway: AdbCommandGateway,
        candidates: List<InstallableArtifact>,
        plan: AuthorizationPlan,
        launchDesktop: Boolean,
    ): DeviceShortcutResult = try {
        gateway.runShortcut(
            shortcut = if (launchDesktop) {
                DeviceShortcut.CONFIGURE_ALL_INSTALLED_APPS_AND_START_DESKTOP
            } else {
                DeviceShortcut.CONFIGURE_SELECTED_APPS
            },
            selectedComponentIds = candidates.mapTo(linkedSetOf()) { it.manifest.componentId },
            authorizationPlan = plan,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DeviceShortcutResult.Failed(
            stage = DeviceShortcutFailureStage.AUTHORIZATION,
            failure = DeviceActionFailure("authorization_exception", retryable = true),
        )
    }

    private fun classifyAuthorizationResult(
        result: DeviceShortcutResult,
        candidates: List<InstallableArtifact>,
        plan: AuthorizationPlan,
        installationEvidence: Map<String, InstalledArtifactEvidence>,
    ): PostInstallationFacts {
        val candidateIds = candidates.mapTo(linkedSetOf()) { it.manifest.componentId }
        val evidence = result.authorizationEvidence()
        val evidenceValid = AuthorizationPlanFactory.validateEvidenceSubset(plan, evidence)
        val configuredIds = if (evidenceValid) {
            AuthorizationPlanFactory.configuredComponentIdsForEvidence(plan, evidence)
        } else {
            emptySet()
        }
        val skippedIds = result.skippedComponentIds()
        if (skippedIds.any { it !in candidateIds }) {
            return PostInstallationFacts(
                authorization = candidateIds.associateWith {
                    AuthorizationStageReceipt(
                        status = AuthorizationStageReceiptStatus.FAILED,
                        reasonCode = "authorization_result_invalid",
                        retryable = false,
                    )
                },
                availability = candidateIds.associateWith {
                    availabilityNotAttempted("availability_not_attempted_authorization_incomplete")
                },
            )
        }
        val authorization = candidates.associate { artifact ->
            val componentId = artifact.manifest.componentId
            val expectedActionIds = plan.actions.filter { it.componentId == componentId }
                .mapTo(linkedSetOf()) { it.id }
            val componentEvidence = evidence.filter { it.componentId == componentId }
            val completeEvidence = evidenceValid &&
                componentEvidence.mapTo(linkedSetOf()) { it.actionId } == expectedActionIds
            componentId to when {
                componentId in skippedIds -> AuthorizationStageReceipt(
                    status = AuthorizationStageReceiptStatus.FAILED,
                    evidence = componentEvidence,
                    reasonCode = "shortcut_selected_component_missing",
                    retryable = false,
                )
                expectedActionIds.isEmpty() ->
                    AuthorizationStageReceipt(AuthorizationStageReceiptStatus.NOT_REQUIRED)
                result is DeviceShortcutResult.Completed && !completeEvidence -> AuthorizationStageReceipt(
                    status = AuthorizationStageReceiptStatus.FAILED,
                    evidence = componentEvidence,
                    reasonCode = "authorization_evidence_invalid",
                    retryable = false,
                )
                result is DeviceShortcutResult.Completed &&
                    result.authorizationConfirmation == DeviceAuthorizationConfirmation.UNKNOWN ->
                    AuthorizationStageReceipt(
                        status = AuthorizationStageReceiptStatus.UNKNOWN,
                        evidence = componentEvidence,
                        reasonCode = "authorization_confirmation_unavailable",
                        retryable = true,
                    )
                result is DeviceShortcutResult.Completed && componentId in configuredIds ->
                    AuthorizationStageReceipt(
                        status = AuthorizationStageReceiptStatus.VERIFIED,
                        evidence = componentEvidence,
                    )
                result is DeviceShortcutResult.Failed &&
                    result.stage == DeviceShortcutFailureStage.VERIFICATION &&
                    result.authorizationConfirmation == DeviceAuthorizationConfirmation.UNKNOWN &&
                    completeEvidence && componentId in configuredIds ->
                    AuthorizationStageReceipt(
                        status = AuthorizationStageReceiptStatus.UNKNOWN,
                        evidence = componentEvidence,
                        reasonCode = "authorization_confirmation_unavailable",
                        retryable = true,
                    )
                result is DeviceShortcutResult.Failed && completeEvidence && componentId in configuredIds &&
                    result.authorizationConfirmation != DeviceAuthorizationConfirmation.UNKNOWN &&
                    (result.stage == DeviceShortcutFailureStage.VERIFICATION ||
                        result.failure.componentId != componentId) -> AuthorizationStageReceipt(
                    status = AuthorizationStageReceiptStatus.VERIFIED,
                    evidence = componentEvidence,
                )
                result is DeviceShortcutResult.Failed -> AuthorizationStageReceipt(
                    status = AuthorizationStageReceiptStatus.FAILED,
                    evidence = componentEvidence,
                    reasonCode = result.failure.reasonCode,
                    retryable = result.failure.retryable,
                )
                else -> AuthorizationStageReceipt(
                    status = AuthorizationStageReceiptStatus.FAILED,
                    evidence = componentEvidence,
                    reasonCode = "authorization_evidence_invalid",
                    retryable = false,
                )
            }
        }
        val runtimeEvidence = result.availabilityEvidence()
        val availability = candidates.associate { artifact ->
            val componentId = artifact.manifest.componentId
            val authorizationStatus = checkNotNull(authorization[componentId]).status
            componentId to when {
                authorizationStatus !in AUTHORIZATION_SATISFIED -> availabilityNotAttempted(
                    "availability_not_attempted_authorization_incomplete",
                )
                componentId != AuthorizationPlanFactory.DESKTOP_COMPONENT_ID ->
                    AvailabilityStageReceipt(AvailabilityStageReceiptStatus.NOT_REQUIRED)
                else -> classifyDesktopAvailability(
                    result = result,
                    artifact = artifact,
                    runtimeRows = runtimeEvidence.filter { it.componentId == componentId },
                    installedEvidence = installationEvidence[componentId],
                )
            }
        }
        return PostInstallationFacts(authorization, availability)
    }

    private fun classifyDesktopAvailability(
        result: DeviceShortcutResult,
        artifact: InstallableArtifact,
        runtimeRows: List<ManagedApplicationAvailabilityEvidence>,
        installedEvidence: InstalledArtifactEvidence?,
    ): AvailabilityStageReceipt {
        val runtime = runtimeRows.singleOrNull()
        if (runtime != null && isDesktopAvailabilityValid(artifact, runtime)) {
            return AvailabilityStageReceipt(
                status = AvailabilityStageReceiptStatus.VERIFIED,
                evidence = DeviceAvailabilityEvidence(
                    componentId = artifact.manifest.componentId,
                    packageName = runtime.packageName,
                    version = installedEvidence?.version ?: artifact.manifest.apkVersion,
                    installedArchiveVerified = true,
                    launchAttempted = runtime.launchAttempted,
                    launcherResolved = runtime.launcherResolved,
                    processRunning = runtime.processRunning,
                    requiredServiceBound = runtime.requiredServiceBound,
                ),
            )
        }
        if (runtimeRows.isNotEmpty()) {
            return AvailabilityStageReceipt(
                status = AvailabilityStageReceiptStatus.FAILED,
                reasonCode = "availability_evidence_invalid",
                retryable = false,
            )
        }
        return when (result) {
            is DeviceShortcutResult.Failed -> AvailabilityStageReceipt(
                status = AvailabilityStageReceiptStatus.FAILED,
                reasonCode = if (result.stage == DeviceShortcutFailureStage.VERIFICATION) {
                    result.failure.reasonCode
                } else {
                    "desktop_verification_not_completed"
                },
                retryable = result.failure.retryable,
            )
            is DeviceShortcutResult.Completed -> AvailabilityStageReceipt(
                status = AvailabilityStageReceiptStatus.UNKNOWN,
                reasonCode = "desktop_verification_not_completed",
                retryable = true,
            )
        }
    }

    private fun buildReceipt(
        batchPlan: InstallationBatchPlan,
        installation: InstallationFacts,
        postInstallation: PostInstallationFacts,
    ): InstallationBatchReceipt = InstallationBatchReceipt(
        batchId = batchPlan.batchId,
        components = batchPlan.selectedComponentIds.sorted().map { componentId ->
            InstallationComponentReceipt(
                componentId = componentId,
                installation = checkNotNull(installation.stages[componentId]),
                authorization = checkNotNull(postInstallation.authorization[componentId]),
                availability = checkNotNull(postInstallation.availability[componentId]),
            )
        },
        warnings = installation.warnings,
    )

    private fun fallbackInstallationFacts(
        batchPlan: InstallationBatchPlan,
        preparationFailures: Map<String, DeviceActionFailure>,
        reasonCode: String,
        result: DeviceInstallResult? = null,
        artifacts: List<InstallableArtifact> = emptyList(),
    ): InstallationFacts {
        val artifactsById = artifacts.associateBy { it.manifest.componentId }
        val writeIds = result?.writeConfirmedComponentIds().orEmpty()
        val operationIds = (
            result?.operationConfirmedComponentIds().orEmpty() +
                result?.verifiedEvidence().orEmpty().map { it.componentId } +
                writeIds
            )
        val evidenceById = result?.verifiedEvidence().orEmpty()
            .distinctBy { it.componentId }
            .mapNotNull { evidence ->
                val componentId = evidence.componentId.takeIf { it in batchPlan.selectedComponentIds }
                    ?: return@mapNotNull null
                val artifact = artifactsById[componentId] ?: return@mapNotNull null
                evidence.takeIf { isInstallationEvidenceValid(artifact, it) }
                    ?.let { componentId to it }
            }
            .toMap()
        val failure = result?.failureOrNull()
        val pendingIds = (result as? DeviceInstallResult.WrittenButUnverified)
            ?.confirmationPendingComponentIds.orEmpty()
        val stages = batchPlan.selectedComponentIds.associateWith { componentId ->
            preparationFailures[componentId]?.let { failure ->
                InstallationStageReceipt(
                    status = InstallationStageReceiptStatus.NOT_ATTEMPTED,
                    reasonCode = failure.reasonCode,
                    retryable = failure.retryable,
                )
            } ?: run {
                val evidence = evidenceById[componentId]
                val identityMismatch = failure != null &&
                    failure.componentId == componentId && isInstalledIdentityMismatch(failure)
                when {
                    identityMismatch -> failedInstallation(
                        reasonCode = checkNotNull(failure).reasonCode,
                        retryable = checkNotNull(failure).retryable,
                        writeConfirmed = componentId in writeIds,
                        operationConfirmed = componentId in operationIds,
                    )
                    evidence != null -> verifiedInstallation(
                        evidence = evidence,
                        writeConfirmed = componentId in writeIds,
                        operationConfirmed = componentId in operationIds,
                    )
                    componentId in pendingIds || componentId in operationIds -> pendingInstallation(
                        reasonCode = failure?.reasonCode ?: reasonCode,
                        retryable = failure?.retryable ?: true,
                        writeConfirmed = componentId in writeIds,
                    )
                    failure?.componentId == componentId -> failedInstallation(
                        reasonCode = checkNotNull(failure).reasonCode,
                        retryable = checkNotNull(failure).retryable,
                    )
                    else -> failedInstallation(
                        reasonCode = reasonCode,
                        retryable = true,
                    )
                }
            }
        }
        return InstallationFacts(
            stages = stages,
            evidenceById = evidenceById.filterKeys {
                stages[it]?.status == InstallationStageReceiptStatus.VERIFIED
            },
            warnings = result?.validWarnings(batchPlan.selectedComponentIds).orEmpty(),
        )
    }

    private fun completeInstallationFacts(
        batchPlan: InstallationBatchPlan,
        facts: InstallationFacts,
        preparationFailures: Map<String, DeviceActionFailure>,
        reasonCode: String,
    ): InstallationFacts {
        val fallback = fallbackInstallationFacts(
            batchPlan = batchPlan,
            preparationFailures = preparationFailures,
            reasonCode = reasonCode,
        )
        val stages = batchPlan.selectedComponentIds.associateWith { componentId ->
            facts.stages[componentId] ?: checkNotNull(fallback.stages[componentId])
        }
        return facts.copy(
            stages = stages,
            evidenceById = facts.evidenceById.filterKeys {
                stages[it]?.status == InstallationStageReceiptStatus.VERIFIED
            },
        )
    }

    private fun fallbackPostInstallationFacts(
        batchPlan: InstallationBatchPlan,
        installation: InstallationFacts,
        reasonCode: String,
    ): PostInstallationFacts {
        val authorization = linkedMapOf<String, AuthorizationStageReceipt>()
        val availability = linkedMapOf<String, AvailabilityStageReceipt>()
        batchPlan.selectedComponentIds.forEach { componentId ->
            val installed = installation.stages[componentId]?.status == InstallationStageReceiptStatus.VERIFIED
            val reusable = componentId in batchPlan.reusableComponentIds
            when {
                !installed -> {
                    authorization[componentId] = authorizationNotAttempted(
                        "authorization_not_attempted_installation_unverified",
                    )
                    availability[componentId] = availabilityNotAttempted(
                        "availability_not_attempted_installation_unverified",
                    )
                }

                reusable -> {
                    authorization[componentId] = AuthorizationStageReceipt(
                        AuthorizationStageReceiptStatus.PRESERVED,
                    )
                    availability[componentId] = AvailabilityStageReceipt(
                        AvailabilityStageReceiptStatus.PRESERVED,
                    )
                }

                else -> {
                    // This is a known execution exception, rather than an
                    // unknown device observation. Preserve installation proof
                    // and classify only the post-install stage as failed.
                    authorization[componentId] = AuthorizationStageReceipt(
                        status = AuthorizationStageReceiptStatus.FAILED,
                        reasonCode = reasonCode,
                        retryable = true,
                    )
                    availability[componentId] = availabilityNotAttempted(
                        "availability_not_attempted_authorization_incomplete",
                    )
                }
            }
        }
        return PostInstallationFacts(authorization, availability)
    }

    private fun completePostInstallationFacts(
        batchPlan: InstallationBatchPlan,
        installation: InstallationFacts,
        facts: PostInstallationFacts,
        reasonCode: String,
    ): PostInstallationFacts {
        val fallback = fallbackPostInstallationFacts(batchPlan, installation, reasonCode)
        return PostInstallationFacts(
            authorization = batchPlan.selectedComponentIds.associateWith { componentId ->
                facts.authorization[componentId] ?: checkNotNull(fallback.authorization[componentId])
            },
            availability = batchPlan.selectedComponentIds.associateWith { componentId ->
                facts.availability[componentId] ?: checkNotNull(fallback.availability[componentId])
            },
        )
    }

    private fun validateBatchPlan(
        plan: InstallationBatchPlan,
        artifacts: List<InstallableArtifact>,
        preparationFailures: Map<String, DeviceActionFailure>,
    ): DeviceActionFailure? {
        val byId = artifacts.associateBy { it.manifest.componentId }
        if (byId.size != artifacts.size || byId.keys.any { it !in plan.selectedComponentIds }) {
            return DeviceActionFailure("installation_batch_plan_invalid", retryable = false)
        }
        if (
            preparationFailures.keys.any { it !in plan.preparationComponentIds } ||
            preparationFailures.keys.any { it in byId } ||
            preparationFailures.values.any { it.reasonCode.isBlank() }
        ) {
            return DeviceActionFailure("installation_batch_plan_invalid", retryable = false)
        }
        if (byId.keys + preparationFailures.keys != plan.selectedComponentIds) {
            return DeviceActionFailure("installation_batch_plan_invalid", retryable = false)
        }
        val invalidReusable = plan.reusableComponentIds.firstOrNull { byId[it]?.apkFile != null }
        if (invalidReusable != null) {
            return DeviceActionFailure("installation_batch_plan_invalid", invalidReusable, retryable = false)
        }
        val invalidFresh = plan.preparationComponentIds.firstOrNull {
            it !in preparationFailures && byId[it]?.apkFile == null
        }
        if (invalidFresh != null) {
            return DeviceActionFailure("installation_batch_plan_invalid", invalidFresh, retryable = false)
        }
        if (plan.flow == InstallationFlow.INITIAL_INSTALL && plan.reusableComponentIds.isNotEmpty()) {
            return DeviceActionFailure("installation_batch_plan_invalid", retryable = false)
        }
        return null
    }

    private fun emitInstallationProgress(stages: Map<String, InstallationStageReceipt>) {
        stages.forEach { (componentId, receipt) ->
            emitProgress(
                componentId = componentId,
                phase = InstallPhase.SEND,
                status = when (receipt.status) {
                    InstallationStageReceiptStatus.VERIFIED,
                    InstallationStageReceiptStatus.WRITE_CONFIRMED_PENDING_IDENTITY,
                    -> ComponentProgressStatus.COMPLETED
                    InstallationStageReceiptStatus.FAILED,
                    InstallationStageReceiptStatus.NOT_ATTEMPTED,
                    -> ComponentProgressStatus.FAILED
                },
                indeterminate = false,
            )
        }
    }

    private fun emitPostInstallationProgress(
        facts: PostInstallationFacts,
        componentIds: Set<String>,
    ) {
        componentIds.forEach { componentId ->
            val authorization = checkNotNull(facts.authorization[componentId]).status
            val availability = checkNotNull(facts.availability[componentId]).status
            val authorizationSatisfied = authorization in AUTHORIZATION_SATISFIED
            val availabilitySatisfied = availability in AVAILABILITY_SATISFIED
            emitProgress(
                componentId = componentId,
                phase = if (authorizationSatisfied) InstallPhase.VERIFY else InstallPhase.CONFIGURE,
                status = if (authorizationSatisfied && availabilitySatisfied) {
                    ComponentProgressStatus.COMPLETED
                } else {
                    ComponentProgressStatus.FAILED
                },
                indeterminate = false,
            )
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
        eventPort.emit(
            if (failure.retryable) {
                InstallationSessionEvent.RecoverableError(
                    category = category,
                    componentName = failure.componentId,
                    reasonCode = failure.reasonCode,
                )
            } else {
                InstallationSessionEvent.FatalError(
                    category = category,
                    componentName = failure.componentId,
                    reasonCode = failure.reasonCode,
                )
            },
        )
        return DeviceInstallationExecutionResult.Failed
    }

    private fun emitProgress(
        componentId: String,
        phase: InstallPhase,
        status: ComponentProgressStatus,
        indeterminate: Boolean = status == ComponentProgressStatus.RUNNING,
    ) {
        try {
            eventPort.emit(
                InstallationSessionEvent.ComponentProgressUpdated(
                    componentId = componentId,
                    phase = phase,
                    status = status,
                    fraction = if (status == ComponentProgressStatus.COMPLETED) 1f else null,
                    indeterminate = indeterminate,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Progress is telemetry. A failing observer must not prevent the
            // device batch from producing its single terminal receipt.
        }
    }

    private data class InstallationFacts(
        val stages: Map<String, InstallationStageReceipt>,
        val evidenceById: Map<String, InstalledArtifactEvidence>,
        val warnings: List<DeviceInstallWarning>,
    )

    private data class PostInstallationFacts(
        val authorization: Map<String, AuthorizationStageReceipt>,
        val availability: Map<String, AvailabilityStageReceipt>,
    )

    private data class AuthorizationPreparation(
        val candidates: List<InstallableArtifact>,
        val plan: AuthorizationPlan?,
        val rejected: Map<String, AuthorizationStageReceipt>,
    )

    private companion object {
        const val INSTALLATION_BATCH_EXECUTION_FAILED = "installation_batch_execution_failed"
        const val AUTHORIZATION_BATCH_EXECUTION_FAILED = "authorization_batch_execution_failed"
        val AUTHORIZATION_SATISFIED = setOf(
            AuthorizationStageReceiptStatus.VERIFIED,
            AuthorizationStageReceiptStatus.NOT_REQUIRED,
        )
        val AVAILABILITY_SATISFIED = setOf(
            AvailabilityStageReceiptStatus.VERIFIED,
            AvailabilityStageReceiptStatus.NOT_REQUIRED,
        )
    }
}

private fun DeviceInstallResult.writeConfirmedComponentIds(): Set<String> = when (this) {
    is DeviceInstallResult.Installed -> writeConfirmedComponentIds
    is DeviceInstallResult.WrittenButUnverified -> writeConfirmedComponentIds
    is DeviceInstallResult.Failed -> writeConfirmedComponentIds
}

private fun DeviceInstallResult.operationConfirmedComponentIds(): Set<String> = when (this) {
    is DeviceInstallResult.Installed -> operationConfirmedComponentIds
    is DeviceInstallResult.WrittenButUnverified -> operationConfirmedComponentIds
    is DeviceInstallResult.Failed -> operationConfirmedComponentIds
}

private fun DeviceInstallResult.verifiedEvidence(): List<InstalledArtifactEvidence> = when (this) {
    is DeviceInstallResult.Installed -> evidence
    is DeviceInstallResult.WrittenButUnverified -> verifiedEvidence
    is DeviceInstallResult.Failed -> verifiedEvidence
}

private fun DeviceInstallResult.failureOrNull(): DeviceActionFailure? = when (this) {
    is DeviceInstallResult.Installed -> null
    is DeviceInstallResult.WrittenButUnverified -> failure
    is DeviceInstallResult.Failed -> failure
}

private fun DeviceInstallResult.validWarnings(selectedIds: Set<String>): List<DeviceInstallWarning> = when (this) {
    is DeviceInstallResult.Installed -> warnings
    is DeviceInstallResult.WrittenButUnverified -> warnings
    is DeviceInstallResult.Failed -> warnings
}.filter { warning ->
    warning.reasonCode.isNotBlank() && (warning.componentId == null || warning.componentId in selectedIds)
}.distinct()

private fun DeviceShortcutResult.authorizationEvidence(): List<AuthorizationActionEvidence> = when (this) {
    is DeviceShortcutResult.Completed -> authorizationEvidence
    is DeviceShortcutResult.Failed -> authorizationEvidence
}

private fun DeviceShortcutResult.availabilityEvidence(): List<ManagedApplicationAvailabilityEvidence> = when (this) {
    is DeviceShortcutResult.Completed -> availabilityEvidence
    is DeviceShortcutResult.Failed -> availabilityEvidence
}

private fun DeviceShortcutResult.skippedComponentIds(): Set<String> = when (this) {
    is DeviceShortcutResult.Completed -> skippedComponentIds
    is DeviceShortcutResult.Failed -> skippedComponentIds
}

private fun toManagedComponent(artifact: InstallableArtifact): ManagedComponent = ManagedComponent(
    componentId = artifact.manifest.componentId,
    packageName = artifact.manifest.packageName,
    setup = artifact.manifest.deviceSetup,
    order = artifact.manifest.sortOrder,
)

private fun isInstallationEvidenceValid(
    artifact: InstallableArtifact,
    evidence: InstalledArtifactEvidence,
): Boolean = evidence.componentId == artifact.manifest.componentId &&
    evidence.packageName == artifact.manifest.packageName &&
    evidence.certificateSha256.equals(artifact.manifest.certificateSha256, ignoreCase = true)

private fun isDesktopAvailabilityValid(
    artifact: InstallableArtifact,
    evidence: ManagedApplicationAvailabilityEvidence,
): Boolean = evidence.componentId == artifact.manifest.componentId &&
    evidence.packageName == artifact.manifest.packageName &&
    evidence.launchAttempted &&
    evidence.launcherResolved &&
    evidence.processRunning &&
    evidence.requiredServiceBound == true

    private fun verifiedInstallation(
        evidence: InstalledArtifactEvidence,
        writeConfirmed: Boolean,
        operationConfirmed: Boolean,
    ): InstallationStageReceipt = InstallationStageReceipt(
        status = InstallationStageReceiptStatus.VERIFIED,
        evidence = evidence,
        writeConfirmed = writeConfirmed,
        operationConfirmed = operationConfirmed,
    )

    private fun pendingInstallation(
        reasonCode: String,
        retryable: Boolean,
        writeConfirmed: Boolean,
    ): InstallationStageReceipt = InstallationStageReceipt(
        status = InstallationStageReceiptStatus.WRITE_CONFIRMED_PENDING_IDENTITY,
        reasonCode = reasonCode,
        retryable = retryable,
        writeConfirmed = writeConfirmed,
        operationConfirmed = true,
    )

    private fun failedInstallation(
        reasonCode: String,
        retryable: Boolean,
        writeConfirmed: Boolean = false,
        operationConfirmed: Boolean = writeConfirmed,
    ): InstallationStageReceipt = InstallationStageReceipt(
        status = InstallationStageReceiptStatus.FAILED,
        reasonCode = reasonCode,
        retryable = retryable,
        writeConfirmed = writeConfirmed,
        operationConfirmed = operationConfirmed,
    )

private fun authorizationNotAttempted(reasonCode: String): AuthorizationStageReceipt =
    AuthorizationStageReceipt(
        status = AuthorizationStageReceiptStatus.NOT_ATTEMPTED,
        reasonCode = reasonCode,
    )

private fun availabilityNotAttempted(reasonCode: String): AvailabilityStageReceipt =
    AvailabilityStageReceipt(
        status = AvailabilityStageReceiptStatus.NOT_ATTEMPTED,
        reasonCode = reasonCode,
    )

private fun isInstalledIdentityMismatch(failure: DeviceActionFailure): Boolean =
    failure.reasonCode in setOf(
        "installation_installed_package_mismatch",
        "installation_installed_certificate_mismatch",
    )

/**
 * Only these adapter failures mean that a device write was accepted but the
 * subsequent identity observation was temporarily unavailable. Every other
 * component-scoped failure is treated as a confirmed write/precondition
 * failure, so positive-looking evidence cannot hide a real install error.
 */
private fun isInstallationReadbackUncertainty(failure: DeviceActionFailure): Boolean =
    failure.reasonCode in setOf(
        "installation_package_path_missing",
        "installation_installed_apk_metadata_unreadable",
        "installation_installed_apk_read_failed",
        "installation_installed_apk_verify_failed",
    )

private fun isConfirmedInstallationWriteFailure(failure: DeviceActionFailure): Boolean =
    !isInstallationReadbackUncertainty(failure)
