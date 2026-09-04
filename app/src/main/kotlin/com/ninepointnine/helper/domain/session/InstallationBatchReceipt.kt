package com.ninepointnine.helper.domain.session

import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.device.AuthorizationActionEvidence
import com.ninepointnine.helper.domain.device.AuthorizationPlanBuildResult
import com.ninepointnine.helper.domain.device.AuthorizationPlanFactory
import com.ninepointnine.helper.domain.device.DeviceAvailabilityEvidence
import com.ninepointnine.helper.domain.device.DeviceInstallWarning
import com.ninepointnine.helper.domain.device.InstalledArtifactEvidence

/** Immutable device facts for exactly one [InstallationBatchPlan]. */
data class InstallationBatchReceipt(
    val batchId: Long,
    val components: List<InstallationComponentReceipt>,
    val warnings: List<DeviceInstallWarning> = emptyList(),
) {
    /** Validates the complete receipt before any terminal session fact is changed. */
    fun validationFailure(
        plan: InstallationBatchPlan,
        manifests: Map<String, ArtifactManifest>,
        baseline: SessionEvidence,
    ): String? {
        if (batchId != plan.batchId) return "installation_batch_receipt_batch_mismatch"
        val expectedIds = plan.selectedComponentIds
        val actualIds = components.map { it.componentId }
        if (actualIds.size != actualIds.toSet().size) {
            return "installation_batch_receipt_duplicate_component"
        }
        if (actualIds.toSet() != expectedIds) {
            return "installation_batch_receipt_component_set_mismatch"
        }
        if (warnings.any { it.reasonCode.isBlank() || (it.componentId != null && it.componentId !in expectedIds) }) {
            return "installation_batch_receipt_warning_invalid"
        }

        val missingManifestIds = expectedIds - manifests.keys
        if (missingManifestIds.any { it in plan.reusableComponentIds }) {
            return "installation_batch_receipt_manifest_missing"
        }
        // A component omitted by preparation is allowed to have no catalog
        // manifest, but it must be explicitly classified as not attempted and
        // cannot smuggle any post-install evidence across the boundary.
        components.filter { it.componentId in missingManifestIds }.forEach { component ->
            if (
                component.installation.status != InstallationStageReceiptStatus.NOT_ATTEMPTED ||
                component.authorization.status != AuthorizationStageReceiptStatus.NOT_ATTEMPTED ||
                component.availability.status != AvailabilityStageReceiptStatus.NOT_ATTEMPTED ||
                component.installation.evidence != null ||
                component.installation.writeConfirmed ||
                component.installation.operationConfirmed ||
                component.installation.reasonCode.isNullOrBlank() ||
                component.authorization.reasonCode.isNullOrBlank() ||
                component.availability.reasonCode.isNullOrBlank() ||
                component.authorization.evidence.isNotEmpty() ||
                component.availability.evidence != null
            ) {
                return "installation_batch_receipt_manifest_missing"
            }
        }

        // Authorization is a component-local contract. A malformed optional
        // setup must be recorded on that component without invalidating a
        // different component whose identity and authorization are complete.
        // Reusable components are stricter: their preserved baseline must
        // still resolve to a valid local plan.
        val authorizationPlansByComponent = linkedMapOf<String, com.ninepointnine.helper.domain.device.AuthorizationPlan?>()
        for (component in components) {
            val manifest = manifests[component.componentId] ?: continue
            when (val result = AuthorizationPlanFactory.createForManifests(
                manifests = listOf(manifest),
                requireDesktop = false,
            )) {
                is AuthorizationPlanBuildResult.Ready ->
                    authorizationPlansByComponent[component.componentId] = result.plan
                is AuthorizationPlanBuildResult.Rejected -> {
                    if (component.componentId in plan.reusableComponentIds) {
                        return "installation_batch_receipt_authorization_plan_invalid"
                    }
                    authorizationPlansByComponent[component.componentId] = null
                }
            }
        }
        val allAuthorizationEvidence = components.flatMap { it.authorization.evidence }
        if (allAuthorizationEvidence.map { it.actionId }.toSet().size != allAuthorizationEvidence.size) {
            return "installation_batch_receipt_authorization_evidence_invalid"
        }
        authorizationPlansByComponent.forEach { (componentId, authorizationPlan) ->
            val evidence = components.first { it.componentId == componentId }.authorization.evidence
            if (
                (authorizationPlan == null && evidence.isNotEmpty()) ||
                (authorizationPlan != null && !AuthorizationPlanFactory.validateEvidenceSubset(authorizationPlan, evidence))
            ) {
                return "installation_batch_receipt_authorization_evidence_invalid"
            }
        }

        for (component in components) {
            val manifest = manifests[component.componentId]
            if (manifest == null) {
                continue
            }
            val reusable = component.componentId in plan.reusableComponentIds
            component.installation.validationFailure(
                componentId = component.componentId,
                manifest = manifest,
                reusable = reusable,
            )?.let { return it }
            val installationVerified = component.installation.status == InstallationStageReceiptStatus.VERIFIED
            if (reusable && installationVerified) {
                if (component.authorization.status != AuthorizationStageReceiptStatus.PRESERVED ||
                    component.availability.status != AvailabilityStageReceiptStatus.PRESERVED
                ) {
                    return "installation_batch_receipt_reusable_stage_invalid"
                }
                if (component.componentId !in baseline.installed ||
                    component.componentId !in baseline.configured ||
                    component.componentId !in baseline.available
                ) {
                    return "installation_batch_receipt_preserved_baseline_missing"
                }
            } else if ((!reusable || !installationVerified) &&
                (component.authorization.status == AuthorizationStageReceiptStatus.PRESERVED ||
                component.availability.status == AvailabilityStageReceiptStatus.PRESERVED
                )
            ) {
                return "installation_batch_receipt_unexpected_preserved_stage"
            }

            if (!installationVerified &&
                (component.authorization.status != AuthorizationStageReceiptStatus.NOT_ATTEMPTED ||
                    component.authorization.evidence.isNotEmpty() ||
                    component.availability.status != AvailabilityStageReceiptStatus.NOT_ATTEMPTED ||
                    component.availability.evidence != null)
            ) {
                return "installation_batch_receipt_post_install_without_identity"
            }

            val authorizationPlan = authorizationPlansByComponent[component.componentId]
            if (authorizationPlan == null) {
                // A rejected optional setup is itself a component-scoped
                // authorization fact. It may not claim success or carry
                // evidence from a plan that was never accepted locally.
                when (component.authorization.status) {
                    AuthorizationStageReceiptStatus.FAILED,
                    AuthorizationStageReceiptStatus.UNKNOWN,
                    AuthorizationStageReceiptStatus.NOT_ATTEMPTED,
                    -> if (
                        component.authorization.evidence.isNotEmpty() ||
                        component.authorization.reasonCode.isNullOrBlank()
                    ) {
                        return "installation_batch_receipt_authorization_plan_invalid"
                    }

                    AuthorizationStageReceiptStatus.VERIFIED,
                    AuthorizationStageReceiptStatus.NOT_REQUIRED,
                    AuthorizationStageReceiptStatus.PRESERVED,
                    -> return "installation_batch_receipt_authorization_plan_invalid"
                }
            } else {
                val expectedActions = authorizationPlan.actions
                    .mapTo(linkedSetOf()) { it.id }
                component.authorization.validationFailure(
                    componentId = component.componentId,
                    expectedActionIds = expectedActions,
                    reusable = reusable,
                )?.let { return it }
            }

            val authorizationSatisfied = component.authorization.status in AUTHORIZATION_SATISFIED
            if (installationVerified && !authorizationSatisfied &&
                (component.availability.status != AvailabilityStageReceiptStatus.NOT_ATTEMPTED ||
                    component.availability.evidence != null)
            ) {
                return "installation_batch_receipt_availability_before_authorization"
            }
            component.availability.validationFailure(
                componentId = component.componentId,
                manifest = manifest,
                reusable = reusable,
            )?.let { return it }
        }
        return null
    }
}

data class InstallationComponentReceipt(
    val componentId: String,
    val installation: InstallationStageReceipt,
    val authorization: AuthorizationStageReceipt,
    val availability: AvailabilityStageReceipt,
) {
    init {
        require(componentId.isNotBlank())
    }
}

enum class InstallationStageReceiptStatus {
    VERIFIED,
    WRITE_CONFIRMED_PENDING_IDENTITY,
    FAILED,
    NOT_ATTEMPTED,
}

data class InstallationStageReceipt(
    val status: InstallationStageReceiptStatus,
    val evidence: InstalledArtifactEvidence? = null,
    val reasonCode: String? = null,
    val retryable: Boolean = false,
    /** True only when PackageManager accepted a fresh write in this batch. */
    val writeConfirmed: Boolean = false,
    /**
     * True when the device accepted the operation or a trusted inventory
     * confirmed an existing package, even if identity readback is pending.
     * It is deliberately separate from [writeConfirmed] so a reusable
     * prerequisite cannot be mistaken for a fresh APK write.
     */
    val operationConfirmed: Boolean = writeConfirmed,
) {
    internal fun validationFailure(
        componentId: String,
        manifest: ArtifactManifest,
        reusable: Boolean,
    ): String? {
        if (reusable && writeConfirmed) {
            return "installation_batch_receipt_reusable_write_confirmed"
        }
        return when (status) {
        InstallationStageReceiptStatus.VERIFIED -> when {
            evidence == null -> "installation_batch_receipt_installation_evidence_missing"
            evidence.componentId != componentId || evidence.packageName != manifest.packageName ->
                "installation_batch_receipt_installation_package_mismatch"
            !evidence.certificateSha256.equals(manifest.certificateSha256, ignoreCase = true) ->
                "installation_batch_receipt_installation_certificate_mismatch"
            !reasonCode.isNullOrBlank() -> "installation_batch_receipt_verified_has_reason"
            writeConfirmed && !operationConfirmed ->
                "installation_batch_receipt_write_without_operation"
            else -> null
        }

        InstallationStageReceiptStatus.WRITE_CONFIRMED_PENDING_IDENTITY -> when {
            evidence != null -> "installation_batch_receipt_pending_has_identity"
            writeConfirmed && !operationConfirmed ->
                "installation_batch_receipt_write_without_operation"
            !reusable && !writeConfirmed -> "installation_batch_receipt_pending_without_write"
            !operationConfirmed -> "installation_batch_receipt_pending_without_operation"
            reasonCode.isNullOrBlank() -> "installation_batch_receipt_pending_reason_missing"
            else -> null
        }

        InstallationStageReceiptStatus.FAILED -> when {
            evidence != null -> "installation_batch_receipt_failed_has_identity"
            writeConfirmed && !operationConfirmed ->
                "installation_batch_receipt_write_without_operation"
            reasonCode.isNullOrBlank() -> "installation_batch_receipt_installation_reason_missing"
            else -> null
        }

        InstallationStageReceiptStatus.NOT_ATTEMPTED -> when {
            evidence != null || writeConfirmed || operationConfirmed ->
                "installation_batch_receipt_not_attempted_has_installation_fact"
            reasonCode.isNullOrBlank() -> "installation_batch_receipt_not_attempted_reason_missing"
            else -> null
        }
        }
    }
}

enum class AuthorizationStageReceiptStatus {
    VERIFIED,
    NOT_REQUIRED,
    PRESERVED,
    UNKNOWN,
    FAILED,
    NOT_ATTEMPTED,
}

data class AuthorizationStageReceipt(
    val status: AuthorizationStageReceiptStatus,
    val evidence: List<AuthorizationActionEvidence> = emptyList(),
    val reasonCode: String? = null,
    val retryable: Boolean = false,
) {
    internal fun validationFailure(
        componentId: String,
        expectedActionIds: Set<String>,
        reusable: Boolean,
    ): String? {
        if (evidence.any { it.componentId != componentId }) {
            return "installation_batch_receipt_authorization_component_mismatch"
        }
        val evidenceIds = evidence.mapTo(linkedSetOf()) { it.actionId }
        if (!expectedActionIds.containsAll(evidenceIds)) {
            return "installation_batch_receipt_authorization_action_mismatch"
        }
        return when (status) {
            AuthorizationStageReceiptStatus.VERIFIED -> when {
                reusable -> "installation_batch_receipt_reusable_authorization_reverified"
                expectedActionIds.isEmpty() -> "installation_batch_receipt_authorization_not_required"
                evidenceIds != expectedActionIds -> "installation_batch_receipt_authorization_evidence_missing"
                !reasonCode.isNullOrBlank() -> "installation_batch_receipt_verified_has_reason"
                else -> null
            }

            AuthorizationStageReceiptStatus.NOT_REQUIRED -> when {
                reusable -> "installation_batch_receipt_reusable_authorization_not_preserved"
                expectedActionIds.isNotEmpty() -> "installation_batch_receipt_authorization_actions_ignored"
                evidence.isNotEmpty() -> "installation_batch_receipt_not_required_has_evidence"
                !reasonCode.isNullOrBlank() -> "installation_batch_receipt_not_required_has_reason"
                else -> null
            }

            AuthorizationStageReceiptStatus.PRESERVED -> when {
                !reusable -> "installation_batch_receipt_unexpected_preserved_stage"
                evidence.isNotEmpty() || !reasonCode.isNullOrBlank() ->
                    "installation_batch_receipt_preserved_authorization_invalid"
                else -> null
            }

            AuthorizationStageReceiptStatus.UNKNOWN,
            AuthorizationStageReceiptStatus.FAILED,
            -> when {
                reasonCode.isNullOrBlank() -> "installation_batch_receipt_authorization_reason_missing"
                status == AuthorizationStageReceiptStatus.UNKNOWN && expectedActionIds.isEmpty() ->
                    "installation_batch_receipt_unknown_authorization_not_required"
                status == AuthorizationStageReceiptStatus.UNKNOWN && evidenceIds != expectedActionIds ->
                    "installation_batch_receipt_unknown_authorization_evidence_incomplete"
                else -> null
            }

            AuthorizationStageReceiptStatus.NOT_ATTEMPTED -> when {
                evidence.isNotEmpty() -> "installation_batch_receipt_not_attempted_has_authorization_evidence"
                reasonCode.isNullOrBlank() -> "installation_batch_receipt_authorization_reason_missing"
                else -> null
            }
        }
    }
}

enum class AvailabilityStageReceiptStatus {
    VERIFIED,
    NOT_REQUIRED,
    PRESERVED,
    UNKNOWN,
    FAILED,
    NOT_ATTEMPTED,
}

data class AvailabilityStageReceipt(
    val status: AvailabilityStageReceiptStatus,
    val evidence: DeviceAvailabilityEvidence? = null,
    val reasonCode: String? = null,
    val retryable: Boolean = false,
) {
    internal fun validationFailure(
        componentId: String,
        manifest: ArtifactManifest,
        reusable: Boolean,
    ): String? {
        if (evidence != null && (evidence.componentId != componentId || evidence.packageName != manifest.packageName)) {
            return "installation_batch_receipt_availability_component_mismatch"
        }
        return when (status) {
            AvailabilityStageReceiptStatus.VERIFIED -> when {
                reusable -> "installation_batch_receipt_reusable_availability_reverified"
                componentId != AuthorizationPlanFactory.DESKTOP_COMPONENT_ID ->
                    "installation_batch_receipt_unexpected_availability_verification"
                evidence == null -> "installation_batch_receipt_availability_evidence_missing"
                !evidence.installedArchiveVerified || !evidence.launchAttempted || !evidence.launcherResolved ||
                    !evidence.processRunning || evidence.requiredServiceBound != true ->
                    "installation_batch_receipt_availability_evidence_invalid"
                !reasonCode.isNullOrBlank() -> "installation_batch_receipt_verified_has_reason"
                else -> null
            }

            AvailabilityStageReceiptStatus.NOT_REQUIRED -> when {
                reusable -> "installation_batch_receipt_reusable_availability_not_preserved"
                componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID ->
                    "installation_batch_receipt_desktop_availability_required"
                evidence != null || !reasonCode.isNullOrBlank() ->
                    "installation_batch_receipt_not_required_availability_invalid"
                else -> null
            }

            AvailabilityStageReceiptStatus.PRESERVED -> when {
                !reusable -> "installation_batch_receipt_unexpected_preserved_stage"
                evidence != null || !reasonCode.isNullOrBlank() ->
                    "installation_batch_receipt_preserved_availability_invalid"
                else -> null
            }

            AvailabilityStageReceiptStatus.UNKNOWN,
            AvailabilityStageReceiptStatus.FAILED,
            -> if (reasonCode.isNullOrBlank()) {
                "installation_batch_receipt_availability_reason_missing"
            } else {
                null
            }

            AvailabilityStageReceiptStatus.NOT_ATTEMPTED -> when {
                evidence != null -> "installation_batch_receipt_not_attempted_has_availability_evidence"
                reasonCode.isNullOrBlank() -> "installation_batch_receipt_availability_reason_missing"
                else -> null
            }
        }
    }
}

/** Merges this batch's facts and the read-only preinstalled baseline. */
internal fun InstallationBatchReceipt.toSessionEvidence(
    snapshot: InstallationSessionSnapshot,
): SessionEvidence {
    val current = snapshot.evidence
    val batch = snapshot.installationBatch
    val selectedIds = batch?.selectedComponentIds
        ?: components.mapTo(linkedSetOf()) { it.componentId }
    // Initial inventory is an explicit presence fact. It is not a fresh write,
    // but it is sufficient to satisfy the existing desktop prerequisite and to
    // keep an already-installed component out of the post-install failure path.
    val preinstalledIds = batch?.preinstalledComponentIds.orEmpty()
    val installed = components.filter { it.installation.status == InstallationStageReceiptStatus.VERIFIED }
        .mapTo(linkedSetOf()) { it.componentId }
    val writeConfirmed = components.filter { it.installation.writeConfirmed }
        .mapTo(linkedSetOf()) { it.componentId }
    val pending = components.filter {
        it.installation.status == InstallationStageReceiptStatus.WRITE_CONFIRMED_PENDING_IDENTITY
    }.mapTo(linkedSetOf()) { it.componentId }
    val configured = components.filter { it.authorization.status in AUTHORIZATION_SATISFIED }
        .mapTo(linkedSetOf()) { it.componentId }
    val available = components.filter { it.availability.status in AVAILABILITY_SATISFIED }
        .mapTo(linkedSetOf()) { it.componentId }
    val preservedAuthorizationIds = components.filter {
        it.authorization.status == AuthorizationStageReceiptStatus.PRESERVED
    }.mapTo(linkedSetOf()) { it.componentId }
    val preservedAvailabilityIds = components.filter {
        it.availability.status == AvailabilityStageReceiptStatus.PRESERVED
    }.mapTo(linkedSetOf()) { it.componentId }
    val retainedWarnings = current.installationWarnings.filter { warning ->
        warning.componentId == null || warning.componentId !in selectedIds
    }
    return current.copy(
        installed = (current.installed - selectedIds) + preinstalledIds + installed,
        writeConfirmed = (current.writeConfirmed - selectedIds) + writeConfirmed,
        confirmationPending = (current.confirmationPending - selectedIds) + pending,
        installation = (current.installation - selectedIds) + components.mapNotNull { item ->
            item.installation.evidence?.let { item.componentId to it }
        }.toMap(),
        configured = (current.configured - selectedIds) + preinstalledIds + configured,
        available = (current.available - selectedIds) + preinstalledIds + available,
        authorizationActions = current.authorizationActions.filter { evidence ->
            evidence.componentId !in selectedIds || evidence.componentId in preservedAuthorizationIds
        } + components.flatMap { it.authorization.evidence },
        availability = current.availability.filterKeys { componentId ->
            componentId !in selectedIds || componentId in preservedAvailabilityIds
        } + components.mapNotNull { item ->
            item.availability.evidence?.let { item.componentId to it }
        }.toMap(),
        installationWarnings = (retainedWarnings + warnings).distinct(),
    )
}

/** Builds the only component-result list allowed after a receipt is committed. */
internal fun InstallationBatchReceipt.toComponentResults(
    snapshot: InstallationSessionSnapshot,
): List<ComponentResult> = components
    .sortedBy { item ->
        snapshot.components.indexOfFirst { it.id == item.componentId }
            .let { index -> if (index < 0) Int.MAX_VALUE else index }
    }
    .map { item ->
        val descriptor = snapshot.components.firstOrNull { it.id == item.componentId }
        val installationVerified = item.installation.status == InstallationStageReceiptStatus.VERIFIED
        val authorizationVerified = item.authorization.status in AUTHORIZATION_SATISFIED
        val availabilityVerified = item.availability.status in AVAILABILITY_SATISFIED
        val status = when {
            item.installation.status == InstallationStageReceiptStatus.WRITE_CONFIRMED_PENDING_IDENTITY ->
                ComponentResultStatus.INSTALLATION_PENDING_CONFIRMATION
            !installationVerified -> ComponentResultStatus.NOT_INSTALLED
            !authorizationVerified -> ComponentResultStatus.AUTHORIZATION_INCOMPLETE
            !availabilityVerified -> ComponentResultStatus.AVAILABILITY_INCOMPLETE
            else -> ComponentResultStatus.READY
        }
        val reason = when {
            item.installation.status != InstallationStageReceiptStatus.VERIFIED ->
                item.installation.reasonCode ?: "installation_failed"
            !authorizationVerified -> item.authorization.reasonCode ?: "authorization_confirmation_unavailable"
            !availabilityVerified -> item.availability.reasonCode ?: "availability_evidence_missing"
            else -> null
        }
        val phase = when (status) {
            ComponentResultStatus.NOT_INSTALLED,
            ComponentResultStatus.INSTALLATION_PENDING_CONFIRMATION,
            -> InstallPhase.SEND
            ComponentResultStatus.AUTHORIZATION_INCOMPLETE -> InstallPhase.CONFIGURE
            ComponentResultStatus.AVAILABILITY_INCOMPLETE -> InstallPhase.VERIFY
            ComponentResultStatus.READY -> null
        }
        ComponentResult(
            componentName = descriptor?.displayName ?: item.componentId,
            installed = installationVerified,
            configured = authorizationVerified,
            available = availabilityVerified,
            componentId = item.componentId,
            failureReason = reason,
            failurePhase = phase,
            retryable = when (status) {
                ComponentResultStatus.NOT_INSTALLED,
                ComponentResultStatus.INSTALLATION_PENDING_CONFIRMATION,
                -> item.installation.retryable
                ComponentResultStatus.AUTHORIZATION_INCOMPLETE -> item.authorization.retryable
                ComponentResultStatus.AVAILABILITY_INCOMPLETE -> item.availability.retryable
                ComponentResultStatus.READY -> false
            },
            writeConfirmed = item.installation.writeConfirmed,
            confirmationPending = item.installation.status ==
                InstallationStageReceiptStatus.WRITE_CONFIRMED_PENDING_IDENTITY,
            status = status,
        )
    }

/** Aggregates component statuses without consulting legacy mutable failure sets. */
internal fun InstallationBatchReceipt.toResultSummary(
    snapshot: InstallationSessionSnapshot,
): InstallationResultSummary {
    val all = toComponentResults(snapshot)
    val resultIds = snapshot.installationBatch?.resultComponentIds
        ?: all.mapNotNull { it.componentId }.toSet()
    val visible = all.filter { it.componentId in resultIds }
    val installationFailures = all.count { it.status == ComponentResultStatus.NOT_INSTALLED }
    val pending = all.count { it.status == ComponentResultStatus.INSTALLATION_PENDING_CONFIRMATION }
    val postInstallFailures = all.count {
        it.status == ComponentResultStatus.AUTHORIZATION_INCOMPLETE ||
            it.status == ComponentResultStatus.AVAILABILITY_INCOMPLETE
    }
    val kind = when {
        installationFailures > 0 && (pending > 0 || postInstallFailures > 0) -> ResultKind.PARTIAL_FAILURE
        installationFailures > 0 -> ResultKind.INSTALLATION_FAILED
        pending > 0 -> ResultKind.CONFIRMATION_PENDING
        postInstallFailures > 0 -> ResultKind.PARTIAL_FAILURE
        else -> ResultKind.SUCCESS
    }
    val failureStage = when {
        kind == ResultKind.SUCCESS -> InstallationResultFailureStage.NONE
        (installationFailures > 0 || pending > 0) && postInstallFailures > 0 ->
            InstallationResultFailureStage.MIXED
        postInstallFailures > 0 -> InstallationResultFailureStage.POST_INSTALL
        else -> InstallationResultFailureStage.INSTALLATION
    }
    val desktop = all.firstOrNull {
        it.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID
    }
    val maintenanceEntryReady = if (snapshot.installationFlow == InstallationFlow.SELF_UPDATE) {
        true
    } else {
        desktop?.status == ComponentResultStatus.READY ||
            AuthorizationPlanFactory.DESKTOP_COMPONENT_ID in snapshot.installationBatch
                ?.preinstalledComponentIds.orEmpty()
    }
    return InstallationResultSummary(
        kind = kind,
        componentResults = visible,
        failureReasonCode = visible.firstOrNull { it.failureReason != null }?.failureReason
            ?: all.firstOrNull { it.failureReason != null }?.failureReason,
        installationFlow = snapshot.installationFlow,
        failureStage = failureStage,
        canContinue = kind != ResultKind.SUCCESS,
        canEnterMaintenance = kind in setOf(
            ResultKind.SUCCESS,
            ResultKind.PARTIAL_FAILURE,
            ResultKind.CONFIRMATION_PENDING,
        ) && maintenanceEntryReady,
    )
}

private val AUTHORIZATION_SATISFIED = setOf(
    AuthorizationStageReceiptStatus.VERIFIED,
    AuthorizationStageReceiptStatus.NOT_REQUIRED,
    AuthorizationStageReceiptStatus.PRESERVED,
)

private val AVAILABILITY_SATISFIED = setOf(
    AvailabilityStageReceiptStatus.VERIFIED,
    AvailabilityStageReceiptStatus.NOT_REQUIRED,
    AvailabilityStageReceiptStatus.PRESERVED,
)
