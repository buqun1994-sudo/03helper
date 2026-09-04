package com.ninepointnine.helper.data.device

import com.ninepointnine.helper.data.artifact.ApkMetadataReader
import com.ninepointnine.helper.data.artifact.sha256
import com.ninepointnine.helper.domain.artifact.ArtifactManifestValidator
import com.ninepointnine.helper.domain.artifact.ManifestValidation
import com.ninepointnine.helper.domain.device.AdbCommandGateway
import com.ninepointnine.helper.domain.device.AuthorizationActionEvidence
import com.ninepointnine.helper.domain.device.AuthorizationAction
import com.ninepointnine.helper.domain.device.AuthorizationDeclarationValidator
import com.ninepointnine.helper.domain.device.AuthorizationPlanBuildResult
import com.ninepointnine.helper.domain.device.AuthorizationPlanFactory
import com.ninepointnine.helper.domain.device.AuthorizationCapacityPolicy
import com.ninepointnine.helper.domain.device.ManagedSecureComponentList
import com.ninepointnine.helper.domain.device.AuthorizationValueState
import com.ninepointnine.helper.domain.device.DeviceActionFailure
import com.ninepointnine.helper.domain.device.DeviceAuthorizationConfirmation
import com.ninepointnine.helper.domain.device.DeviceShortcut
import com.ninepointnine.helper.domain.device.DeviceShortcutFailureStage
import com.ninepointnine.helper.domain.device.DeviceShortcutResult
import com.ninepointnine.helper.domain.device.DeviceAvailabilityEvidence
import com.ninepointnine.helper.domain.device.DeviceInstallResult
import com.ninepointnine.helper.domain.device.DeviceInstallWarning
import com.ninepointnine.helper.domain.device.InstallableArtifact
import com.ninepointnine.helper.domain.device.InstalledArtifactEvidence
import com.ninepointnine.helper.domain.device.ManagedApplicationProbe
import com.ninepointnine.helper.domain.device.ManagedApplicationsResult
import com.ninepointnine.helper.domain.device.InstalledApplicationIconResult
import com.ninepointnine.helper.domain.device.ApplicationAuthorizationRequirement
import com.ninepointnine.helper.domain.device.ApplicationAuthorizationResult
import com.ninepointnine.helper.domain.device.ApplicationAuthorizationResultValue
import com.ninepointnine.helper.domain.device.DeclaredApplicationAuthorizationAction
import com.ninepointnine.helper.domain.device.DeclaredApplicationAuthorizationPlanFactory
import com.ninepointnine.helper.domain.device.DeclaredApplicationAuthorizationRequirement
import com.ninepointnine.helper.domain.device.ManagedComponent
import com.ninepointnine.helper.domain.device.ManagedApplicationDetailsProbe
import com.ninepointnine.helper.domain.device.ManagedApplicationDetailsProbeResult
import com.ninepointnine.helper.domain.device.ManagedApplicationAuthorizationStatus
import com.ninepointnine.helper.domain.device.MaintenanceAuthorizationResult
import com.ninepointnine.helper.domain.device.MaintenanceAuthorizationState
import com.ninepointnine.helper.domain.device.MaintenanceCommandGateway
import com.ninepointnine.helper.domain.device.MaintenanceDeviceResult
import com.ninepointnine.helper.domain.artifact.InstallerComponentTrustRegistry
import com.ninepointnine.helper.domain.session.MaintenanceApplicationActionId
import com.ninepointnine.helper.domain.session.InstallationStrategy
import android.util.Log
import dadb.AdbShellResponse
import dadb.Dadb
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Base64
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * DADB-backed device port. The install batch is separate from the single
 * post-install command: no package is launched during [installBatch], and all
 * authorization plus the desktop launch happen in one shell invocation.
 */
internal class DadbCommandGateway(
    private val adb: Dadb,
    private val closed: AtomicBoolean,
    private val ioMutex: Mutex,
    private val installedApkCacheDirectory: File?,
    private val installedApkMetadataReader: ApkMetadataReader?,
) : AdbCommandGateway, MaintenanceCommandGateway {
    /** Cached per lease so older Android package-manager builds are probed once. */
    private var versionedPackageInventorySupported: Boolean? = null

    override suspend fun installBatch(
        artifacts: List<InstallableArtifact>,
        strategy: InstallationStrategy,
    ): DeviceInstallResult = withLease(
        whenClosed = DeviceInstallResult.Failed(
            DeviceActionFailure("adb_connection_closed", retryable = true),
        ),
    ) {
        if (artifacts.isEmpty() || artifacts.map { it.manifest.componentId }.toSet().size != artifacts.size) {
            return@withLease DeviceInstallResult.Failed(
                DeviceActionFailure("installable_artifacts_invalid", retryable = false),
            )
        }
        val verificationDirectory = installedApkCacheDirectory
            ?: return@withLease DeviceInstallResult.Failed(
                DeviceActionFailure("install_apk_verifier_unavailable", retryable = false),
            )
        val metadataReader = installedApkMetadataReader
            ?: return@withLease DeviceInstallResult.Failed(
                DeviceActionFailure("install_apk_verifier_unavailable", retryable = false),
            )
        if (!verificationDirectory.mkdirs() && !verificationDirectory.isDirectory) {
            return@withLease DeviceInstallResult.Failed(
                DeviceActionFailure("installed_apk_verification_cache_unavailable", retryable = true),
            )
        }

        // A missing-only batch must establish a trustworthy package inventory
        // before the first device write. This prevents an unknown inventory
        // from silently falling through to a destructive reinstall.
        val installedInventory = if (strategy == InstallationStrategy.INSTALL_MISSING_ONLY) {
            readPackageInventory()?.associate { it.packageName to it.versionCode }
                ?: return@withLease DeviceInstallResult.Failed(
                    DeviceActionFailure("installation_package_inventory_failed", retryable = true),
                )
        } else {
            emptyMap()
        }

        // A recognized staging/production alias without the exact manifest
        // package is an identity conflict, not a missing package. Refuse to
        // write anything rather than install beside or over an unknown app.
        if (strategy == InstallationStrategy.INSTALL_MISSING_ONLY) {
            artifacts.firstOrNull { artifact ->
                val aliases = (InstallerComponentTrustRegistry.allowedPackageNames(artifact.manifest.componentId) +
                    artifact.manifest.packageName).toSet()
                artifact.manifest.packageName !in installedInventory &&
                    aliases.any { it != artifact.manifest.packageName && it in installedInventory }
            }?.let { conflict ->
                return@withLease DeviceInstallResult.Failed(
                    DeviceActionFailure(
                        "installation_installed_identity_conflict",
                        conflict.manifest.componentId,
                        retryable = false,
                    ),
                )
            }
        }

        val reusableMissingOnlyIds = mutableSetOf<String>()
        // Includes both fresh writes and packages intentionally skipped after
        // a trusted inventory read. It marks the set for which a later
        // identity-readback failure is a pending confirmation rather than a
        // pre-install failure.
        val operationConfirmedComponentIds = mutableSetOf<String>()
        val writeConfirmedComponentIds = mutableSetOf<String>()
        val installationWarnings = mutableListOf<DeviceInstallWarning>()
        // Validate every artifact that may be written. An already-installed
        // package does not need a local APK at all: its live APK is pulled and
        // verified below, so a stale or absent local cache cannot block a
        // missing-only batch.
        artifacts.forEach { artifact ->
            val packagePresent = artifact.manifest.packageName in installedInventory
            // The domain session passes a null APK only for a reusable
            // missing-only prerequisite. Presence is still required here;
            // when the legacy inventory cannot expose a versionCode, the live
            // APK identity readback below remains the final proof.
            if (strategy == InstallationStrategy.INSTALL_MISSING_ONLY && artifact.apkFile == null) {
                if (!packagePresent) {
                    return@withLease DeviceInstallResult.Failed(
                        failure = DeviceActionFailure(
                            "installation_reused_package_missing",
                            artifact.manifest.componentId,
                            retryable = true,
                        ),
                        operationConfirmedComponentIds = operationConfirmedComponentIds.toSet(),
                    )
                }
                val installedVersion = installedInventory[artifact.manifest.packageName]
                if (installedVersion != null && installedVersion != artifact.manifest.apkVersion.code) {
                    return@withLease DeviceInstallResult.Failed(
                        failure = DeviceActionFailure(
                            "installation_reused_version_mismatch",
                            artifact.manifest.componentId,
                            retryable = false,
                        ),
                        operationConfirmedComponentIds = operationConfirmedComponentIds.toSet(),
                    )
                }
                if (ArtifactManifestValidator.validate(artifact.manifest) !is ManifestValidation.Valid) {
                    return@withLease DeviceInstallResult.Failed(
                        failure = DeviceActionFailure("install_manifest_invalid", artifact.manifest.componentId, retryable = false),
                        operationConfirmedComponentIds = operationConfirmedComponentIds.toSet(),
                    )
                }
                reusableMissingOnlyIds += artifact.manifest.componentId
                // A trusted inventory hit is an operation-level confirmation,
                // even though no fresh PackageManager write is performed.
                operationConfirmedComponentIds += artifact.manifest.componentId
                return@forEach
            }
            val alreadyInstalled = packagePresent &&
                installedInventory[artifact.manifest.packageName] == artifact.manifest.apkVersion.code
            if (!alreadyInstalled || strategy == InstallationStrategy.REINSTALL_SELECTED) {
                validateInstallableArtifact(artifact)?.let { failure ->
                    return@withLease DeviceInstallResult.Failed(
                        failure = failure,
                        operationConfirmedComponentIds = operationConfirmedComponentIds.toSet(),
                    )
                }
            } else if (ArtifactManifestValidator.validate(artifact.manifest) !is ManifestValidation.Valid) {
                return@withLease DeviceInstallResult.Failed(
                    failure = DeviceActionFailure("install_manifest_invalid", artifact.manifest.componentId, retryable = false),
                    operationConfirmedComponentIds = operationConfirmedComponentIds.toSet(),
                )
            }
            if (alreadyInstalled) {
                operationConfirmedComponentIds += artifact.manifest.componentId
            }
        }

        // Installation never launches an application. The only launch occurs
        // during the versioned authorization plan after every package has been installed.
        artifacts.forEach { artifact ->
            if (artifact.manifest.componentId in reusableMissingOnlyIds ||
                (strategy == InstallationStrategy.INSTALL_MISSING_ONLY &&
                    installedInventory[artifact.manifest.packageName] == artifact.manifest.apkVersion.code)
            ) {
                // The package is already present. The identity readback below
                // remains mandatory; presence alone is never accepted as proof.
                operationConfirmedComponentIds += artifact.manifest.componentId
                return@forEach
            }
            val remotePath = remoteApkPath(artifact)
                ?: return@withLease DeviceInstallResult.Failed(
                    failure = DeviceActionFailure("remote_staging_path_invalid", artifact.manifest.componentId, retryable = false),
                    operationConfirmedComponentIds = operationConfirmedComponentIds.toSet(),
                )
            val apkFile = artifact.apkFile
                ?: return@withLease DeviceInstallResult.Failed(
                    failure = DeviceActionFailure("install_apk_file_invalid", artifact.manifest.componentId, retryable = false),
                    operationConfirmedComponentIds = operationConfirmedComponentIds.toSet(),
                )
            var failure: DeviceActionFailure? = null
            try {
                adb.push(
                    apkFile,
                    remotePath,
                    REMOTE_FILE_MODE,
                    apkFile.lastModified().coerceAtLeast(1L),
                )
                val installResponse = adb.shell("pm install -r $remotePath")
                if (!isPmInstallSuccessful(installResponse)) {
                    failure = DeviceActionFailure("adb_pm_install_failed", artifact.manifest.componentId, retryable = true)
                }
            } catch (_: IOException) {
                failure = DeviceActionFailure("adb_install_transport_failed", artifact.manifest.componentId, retryable = true)
            } catch (_: Exception) {
                failure = DeviceActionFailure("adb_install_failed", artifact.manifest.componentId, retryable = true)
            } finally {
                if (!deleteRemoteStagingFile(remotePath)) {
                    // Staging cleanup is housekeeping. It must never replace a
                    // successful PackageManager result with a user-visible
                    // installation failure; the next attempt safely reuses the
                    // deterministic path and overwrites it.
                    Log.w(TAG, "remote staging cleanup failed component=${artifact.manifest.componentId}")
                    installationWarnings += DeviceInstallWarning(
                        reasonCode = "remote_staging_cleanup_failed",
                        componentId = artifact.manifest.componentId,
                    )
                }
            }
            if (failure != null) {
                return@withLease DeviceInstallResult.Failed(
                    failure = checkNotNull(failure),
                    writeConfirmedComponentIds = writeConfirmedComponentIds.toSet(),
                    operationConfirmedComponentIds = operationConfirmedComponentIds.toSet(),
                    warnings = installationWarnings.toList(),
                )
            }
            writeConfirmedComponentIds += artifact.manifest.componentId
            operationConfirmedComponentIds += artifact.manifest.componentId
        }

        // Read back every installed APK only after the entire install batch is
        // complete. Nothing is authorized or launched before this succeeds.
        val installed = mutableListOf<InstalledArtifactEvidence>()
        artifacts.forEach { artifact ->
            when (val identity = verifyInstalledArtifactIdentity(artifact, metadataReader, verificationDirectory)) {
                is InstalledArtifactIdentityResult.Verified -> installed += identity.evidence
                is InstalledArtifactIdentityResult.Failed -> {
                    if (operationConfirmedComponentIds.isNotEmpty()) {
                        val identityMismatch = identity.failure.reasonCode in setOf(
                            "installation_installed_package_mismatch",
                            "installation_installed_certificate_mismatch",
                        )
                        return@withLease DeviceInstallResult.WrittenButUnverified(
                            writeConfirmedComponentIds = writeConfirmedComponentIds.toSet(),
                            failure = identity.failure,
                            operationConfirmedComponentIds = operationConfirmedComponentIds.toSet(),
                            verifiedEvidence = installed.toList(),
                            warnings = installationWarnings.toList(),
                            confirmationPendingComponentIds = operationConfirmedComponentIds
                                .filterNot { id -> installed.any { evidence -> evidence.componentId == id } }
                                .filterNot { id -> identityMismatch && id == identity.failure.componentId }
                                .toSet(),
                        )
                    }
                    return@withLease DeviceInstallResult.Failed(
                        failure = identity.failure,
                        verifiedEvidence = installed.toList(),
                        warnings = installationWarnings.toList(),
                    )
                }
            }
        }
        DeviceInstallResult.Installed(
            evidence = installed,
            warnings = installationWarnings,
            writeConfirmedComponentIds = writeConfirmedComponentIds.toSet(),
            operationConfirmedComponentIds = operationConfirmedComponentIds.toSet(),
        )
    }

    override suspend fun runShortcut(
        shortcut: DeviceShortcut,
        selectedComponentIds: Set<String>,
        authorizationPlan: com.ninepointnine.helper.domain.device.AuthorizationPlan,
    ): DeviceShortcutResult = runShortcutWithPlan(shortcut, selectedComponentIds, authorizationPlan)

    private suspend fun runShortcutWithPlan(
        shortcut: DeviceShortcut,
        selectedComponentIds: Set<String>,
        authorizationPlan: com.ninepointnine.helper.domain.device.AuthorizationPlan,
    ): DeviceShortcutResult = withLease(
        whenClosed = DeviceShortcutResult.Failed(
            stage = DeviceShortcutFailureStage.AUTHORIZATION,
            failure = DeviceActionFailure("adb_connection_closed", retryable = true),
        ),
    ) {
        val launchDesktop = shortcut == DeviceShortcut.CONFIGURE_ALL_INSTALLED_APPS_AND_START_DESKTOP
        if (shortcut !in setOf(
                DeviceShortcut.CONFIGURE_ALL_INSTALLED_APPS_AND_START_DESKTOP,
                DeviceShortcut.CONFIGURE_SELECTED_APPS,
            )
        ) {
            return@withLease DeviceShortcutResult.Failed(
                stage = DeviceShortcutFailureStage.AUTHORIZATION,
                failure = DeviceActionFailure("device_shortcut_unavailable", retryable = false),
            )
        }
        if (authorizationPlan.components.map { it.componentId }.toSet() != selectedComponentIds ||
            !AuthorizationPlanFactory.validate(authorizationPlan)
        ) {
            return@withLease DeviceShortcutResult.Failed(
                stage = DeviceShortcutFailureStage.AUTHORIZATION,
                failure = DeviceActionFailure("shortcut_component_selection_invalid", retryable = false),
            )
        }
        val capacityFailure = probeAuthorizationCapacity(authorizationPlan)
        if (capacityFailure != null) {
            return@withLease DeviceShortcutResult.Failed(
                stage = DeviceShortcutFailureStage.AUTHORIZATION,
                failure = capacityFailure,
            )
        }
        val response = try {
            adb.shell(
                CombinedAuthorizationCommand.build(
                    authorizationPlan,
                    launchDesktop = launchDesktop,
                ),
            )
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
        val parsed = CombinedAuthorizationResponseParser.parse(
            response,
            authorizationPlan,
            launchDesktop = launchDesktop,
        )
        val structuralFailure = (parsed as? DeviceShortcutResult.Failed)
            ?.takeIf { isNonMaskableAuthorizationFailure(it.failure.reasonCode) }
        val result = if (structuralFailure != null) {
            structuralFailure
        } else when (val postcondition = readAuthorizationPostcondition(authorizationPlan)) {
            is AuthorizationPostcondition.Satisfied -> {
                val completed = DeviceShortcutResult.Completed(
                    configuredComponentIds = postcondition.configuredComponentIds -
                        (parsed as? DeviceShortcutResult.Failed)?.skippedComponentIds.orEmpty(),
                    skippedComponentIds = (parsed as? DeviceShortcutResult.Failed)
                        ?.skippedComponentIds.orEmpty(),
                    authorizationEvidence = postcondition.evidence,
                    availabilityEvidence = parsed.availabilityEvidenceOrEmpty(),
                    authorizationConfirmation = DeviceAuthorizationConfirmation.CONFIRMED,
                )
                if (launchDesktop) verifyDesktopRuntime(completed, authorizationPlan) else completed
            }

            is AuthorizationPostcondition.NotSatisfied -> DeviceShortcutResult.Failed(
                stage = DeviceShortcutFailureStage.AUTHORIZATION,
                failure = DeviceActionFailure(
                    reasonCode = postcondition.probe.reasonCode
                        ?: "authorization_confirmation_not_satisfied",
                    componentId = postcondition.action.componentId,
                    retryable = true,
                ),
                configuredComponentIds = postcondition.configuredComponentIds,
                skippedComponentIds = parsed.skippedComponentIdsOrEmpty(),
                authorizationEvidence = postcondition.evidence,
                availabilityEvidence = parsed.availabilityEvidenceOrEmpty(),
                authorizationConfirmation = DeviceAuthorizationConfirmation.CONFIRMED,
            )

            is AuthorizationPostcondition.Unavailable -> when (parsed) {
                is DeviceShortcutResult.Completed -> {
                    // The combined command already emitted a complete, validated
                    // receipt. A separate readback outage lowers confidence but
                    // cannot rewrite that command into an authorization failure.
                    val completed = parsed.copy(
                        authorizationConfirmation = DeviceAuthorizationConfirmation.UNKNOWN,
                    )
                    if (launchDesktop) verifyDesktopRuntime(completed, authorizationPlan) else completed
                }

                is DeviceShortcutResult.Failed -> parsed
            }
        }
        Log.d(
            TAG,
            when (result) {
                is DeviceShortcutResult.Completed -> "shortcut_result completed"
                is DeviceShortcutResult.Failed ->
                    "shortcut_result failed stage=${result.stage} reason=${result.failure.reasonCode}"
            },
        )
        result
    }

    private suspend fun verifyDesktopRuntime(
        completed: DeviceShortcutResult.Completed,
        plan: com.ninepointnine.helper.domain.device.AuthorizationPlan,
    ): DeviceShortcutResult {
        val desktop = plan.components.firstOrNull {
            it.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID
        } ?: return completed.toVerificationFailure(
            reasonCode = "desktop_missing",
            retryable = false,
        )
        val service = AuthorizationPlanFactory.requiredRuntimeService(plan, desktop)
            ?: return completed.toVerificationFailure(
                reasonCode = "desktop_service_missing",
                retryable = false,
            )
        val processRunning = waitForProcess(desktop.packageName)
        if (!processRunning) {
            return completed.toVerificationFailure(
                reasonCode = "desktop_process_not_running",
                retryable = true,
                availability = desktopAvailability(desktop, processRunning = false, serviceBound = null),
            )
        }
        return when (waitForBoundService(desktop.packageName, service)) {
            BoundServiceProbe.BOUND -> completed.copy(
                availabilityEvidence = listOf(
                    desktopAvailability(desktop, processRunning = true, serviceBound = true),
                ),
            )

            BoundServiceProbe.UNBOUND -> completed.toVerificationFailure(
                reasonCode = "desktop_service_not_bound",
                retryable = true,
                availability = desktopAvailability(desktop, processRunning = true, serviceBound = false),
            )

            BoundServiceProbe.READ_FAILED -> completed.toVerificationFailure(
                reasonCode = "desktop_service_readback_failed",
                retryable = true,
                availability = desktopAvailability(desktop, processRunning = true, serviceBound = null),
            )
        }
    }

    private fun DeviceShortcutResult.Completed.toVerificationFailure(
        reasonCode: String,
        retryable: Boolean,
        availability: com.ninepointnine.helper.domain.device.ManagedApplicationAvailabilityEvidence? = null,
    ): DeviceShortcutResult.Failed = DeviceShortcutResult.Failed(
        stage = DeviceShortcutFailureStage.VERIFICATION,
        failure = DeviceActionFailure(
            reasonCode = reasonCode,
            componentId = AuthorizationPlanFactory.DESKTOP_COMPONENT_ID,
            retryable = retryable,
        ),
        configuredComponentIds = configuredComponentIds,
        skippedComponentIds = skippedComponentIds,
        authorizationEvidence = authorizationEvidence,
        availabilityEvidence = listOfNotNull(availability),
        authorizationConfirmation = authorizationConfirmation,
    )

    private fun desktopAvailability(
        desktop: ManagedComponent,
        processRunning: Boolean,
        serviceBound: Boolean?,
    ) = com.ninepointnine.helper.domain.device.ManagedApplicationAvailabilityEvidence(
        componentId = desktop.componentId,
        packageName = desktop.packageName,
        launchAttempted = true,
        launcherResolved = true,
        processRunning = processRunning,
        requiredServiceBound = serviceBound,
    )

    private suspend fun probeAuthorizationCapacity(
        plan: com.ninepointnine.helper.domain.device.AuthorizationPlan,
    ): DeviceActionFailure? {
        val settings = plan.actions.filterIsInstance<com.ninepointnine.helper.domain.device.AuthorizationAction.AppendSecureComponent>()
            .map { it.setting }
            .toSet()
        if (settings.isEmpty()) return null
        val existing = mutableMapOf<ManagedSecureComponentList, String?>()
        settings.forEach { setting ->
            val response = try {
                adb.shell("settings get secure ${setting.wireName}")
            } catch (_: Exception) {
                return DeviceActionFailure("authorization_capacity_probe_failed", retryable = true)
            }
            if (!isSuccessful(response)) {
                return DeviceActionFailure("authorization_capacity_probe_failed", retryable = true)
            }
            existing[setting] = response.output.trim().takeUnless { it.isBlank() }
        }
        val reason = AuthorizationCapacityPolicy.validate(plan, existing)
        return reason?.let { DeviceActionFailure(it, retryable = false) }
    }

    override suspend fun repairAuthorization(
        manifests: List<com.ninepointnine.helper.domain.artifact.ArtifactManifest>,
        declarationsByComponent: Map<String, com.ninepointnine.helper.domain.device.ApkDeclarationMetadata>,
    ): MaintenanceDeviceResult = withLease(
        whenClosed = MaintenanceDeviceResult.Failed(
            DeviceActionFailure("adb_connection_closed", retryable = true),
        ),
    ) {
        if (ArtifactManifestValidator.validateCatalog(manifests) !is ManifestValidation.Valid) {
            return@withLease MaintenanceDeviceResult.Failed(
                DeviceActionFailure("maintenance_manifest_invalid", retryable = false),
            )
        }
        val components = manifests.map {
            com.ninepointnine.helper.domain.device.ManagedComponent(
                componentId = it.componentId,
                packageName = it.packageName,
                setup = it.deviceSetup,
                order = it.sortOrder,
            )
        }
        val declarations = linkedMapOf<String, com.ninepointnine.helper.domain.device.ApkDeclarationMetadata?>()
        manifests.forEach { manifest ->
            verifyInstalledArtifactQuick(manifest)?.let { failure ->
                return@withLease MaintenanceDeviceResult.Failed(failure)
            }
        }
        val missingDeclarations = manifests.filter { manifest ->
            val declaration = declarationsByComponent[manifest.componentId]
            declarations[manifest.componentId] = declaration
            declaration == null
        }
        if (missingDeclarations.isNotEmpty()) {
            val verificationDirectory = installedApkCacheDirectory
                ?: return@withLease MaintenanceDeviceResult.Failed(
                    DeviceActionFailure("install_apk_verifier_unavailable", retryable = false),
                )
            val metadataReader = installedApkMetadataReader
                ?: return@withLease MaintenanceDeviceResult.Failed(
                    DeviceActionFailure("install_apk_verifier_unavailable", retryable = false),
                )
            if (!verificationDirectory.mkdirs() && !verificationDirectory.isDirectory) {
                return@withLease MaintenanceDeviceResult.Failed(
                    DeviceActionFailure("installed_apk_verification_cache_unavailable", retryable = true),
                )
            }
            missingDeclarations.forEach { manifest ->
                when (val result = verifyInstalledArtifactForMaintenance(manifest, metadataReader, verificationDirectory)) {
                    is MaintenanceInstalledIdentity.Completed -> declarations[manifest.componentId] = result.declarations
                    is MaintenanceInstalledIdentity.Failed -> return@withLease MaintenanceDeviceResult.Failed(result.failure)
                }
            }
        }
        val resolvedDeclarations = declarations.mapNotNull { (componentId, declaration) ->
            declaration?.let { componentId to it }
        }.toMap()
        if (resolvedDeclarations.size != manifests.size) {
            return@withLease MaintenanceDeviceResult.Failed(
                DeviceActionFailure("authorization_capability_metadata_missing", retryable = false),
            )
        }
        val plan = when (val result = AuthorizationPlanFactory.createForComponents(
            components,
            declaredServicesByComponent = resolvedDeclarations.mapValues { it.value.services },
        )) {
            is AuthorizationPlanBuildResult.Ready -> result.plan
            is AuthorizationPlanBuildResult.Rejected -> return@withLease MaintenanceDeviceResult.Failed(
                DeviceActionFailure(result.reasonCode, retryable = false),
            )
        }
        AuthorizationDeclarationValidator.validateDeclarations(plan, resolvedDeclarations)?.let { failure ->
            return@withLease MaintenanceDeviceResult.Failed(failure)
        }
        probeAuthorizationCapacity(plan)?.let { failure ->
            return@withLease MaintenanceDeviceResult.Failed(failure)
        }
        val response = try {
            adb.shell(CombinedAuthorizationCommand.build(plan, repairOnly = true))
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
        val parsed = CombinedAuthorizationResponseParser.parseRepair(response, plan)
        when (val postcondition = readAuthorizationPostcondition(plan)) {
            is AuthorizationPostcondition.Satisfied -> {
                (parsed as? MaintenanceDeviceResult.Failed)
                    ?.takeIf { isNonMaskableAuthorizationFailure(it.failure.reasonCode) }
                    ?: MaintenanceDeviceResult.Completed("authorization_repaired")
            }

            is AuthorizationPostcondition.NotSatisfied -> MaintenanceDeviceResult.Failed(
                DeviceActionFailure(
                    reasonCode = postcondition.probe.reasonCode
                        ?: "authorization_confirmation_not_satisfied",
                    componentId = postcondition.action.componentId,
                    retryable = true,
                ),
            )

            is AuthorizationPostcondition.Unavailable -> MaintenanceDeviceResult.Failed(
                DeviceActionFailure(
                    "authorization_confirmation_unavailable",
                    postcondition.action?.componentId,
                    retryable = true,
                ),
            )
        }
    }

    override suspend fun inspectManagedApplications(
        components: List<ManagedComponent>,
    ): ManagedApplicationsResult = withLease(
        whenClosed = ManagedApplicationsResult.Failed(
            DeviceActionFailure("adb_connection_closed", retryable = true),
        ),
    ) {
        val inventory = inspectInstalledApplicationInventoryLocked(components)
        if (inventory is ManagedApplicationsResult.Failed) return@withLease inventory
        val installed = (inventory as ManagedApplicationsResult.Completed).applications
        val detailed = installed.mapNotNull { application ->
            when (val result = inspectInstalledPackage(application.componentId, application.packageName)) {
                is PackageInspection.Completed -> if (result.installed) {
                    result.toProbe(
                        ManagedComponent(
                            componentId = application.componentId,
                            packageName = application.packageName,
                        ),
                    )
                } else null

                is PackageInspection.Failed -> return@withLease ManagedApplicationsResult.Failed(result.failure)
            }
        }
        ManagedApplicationsResult.Completed(detailed)
    }

    override suspend fun inspectInstalledApplicationInventory(
        components: List<ManagedComponent>,
    ): ManagedApplicationsResult = withLease(
        whenClosed = ManagedApplicationsResult.Failed(
            DeviceActionFailure("adb_connection_closed", retryable = true),
        ),
    ) {
        inspectInstalledApplicationInventoryLocked(components)
    }

    override suspend fun inspectAllInstalledApplications(): ManagedApplicationsResult = withLease(
        whenClosed = ManagedApplicationsResult.Failed(
            DeviceActionFailure("adb_connection_closed", retryable = true),
        ),
    ) {
        val inventory = readDesktopBridgeInventory()
        if (inventory == null) {
            return@withLease ManagedApplicationsResult.Failed(
                DeviceActionFailure("maintenance_app_catalog_bridge_unavailable", retryable = true),
            )
        }
        ManagedApplicationsResult.Completed(
            inventory.entries.map { entry ->
                ManagedApplicationProbe(
                    componentId = componentIdForPackage(entry.packageName),
                    packageName = entry.packageName,
                    installed = true,
                    displayName = entry.displayName,
                    versionLabel = entry.versionName,
                    versionCode = entry.versionCode,
                    installTimeEpochMillis = entry.firstInstallTime,
                    updateTimeEpochMillis = entry.lastUpdateTime,
                    uid = entry.uid,
                    iconBase64 = entry.iconBase64,
                    launchComponent = entry.launcherComponent,
                )
            },
        )
    }

    override suspend fun inspectInstalledApplicationIcon(
        packageName: String,
    ): InstalledApplicationIconResult = withLease(
        whenClosed = InstalledApplicationIconResult.Failed(
            DeviceActionFailure("adb_connection_closed", retryable = true),
        ),
    ) {
        if (!PACKAGE_NAME_PATTERN.matches(packageName)) {
            return@withLease InstalledApplicationIconResult.Failed(
                DeviceActionFailure("maintenance_component_identity_invalid", retryable = false),
            )
        }
        DESKTOP_BRIDGE_AUTHORITIES.forEach { authority ->
            val response = shell("content query --uri content://$authority/icons/$packageName")
                ?: return@forEach
            if (response.exitCode != 0) return@forEach
            when (val parsed = DesktopAppCatalogBridgeParser.parseIcon(response.output, packageName)) {
                is BridgeIconParseResult.Valid -> return@withLease InstalledApplicationIconResult.Completed(
                    packageName = packageName,
                    iconBase64 = parsed.iconBase64,
                )
                BridgeIconParseResult.Invalid -> Unit
            }
        }
        InstalledApplicationIconResult.Failed(
            DeviceActionFailure("maintenance_app_icon_unavailable", retryable = true),
        )
    }

    override suspend fun authorizeApplication(
        component: ManagedComponent,
    ): ApplicationAuthorizationResult = authorizeApplication(component) {}

    override suspend fun authorizeApplication(
        component: ManagedComponent,
        onProgress: (ApplicationAuthorizationResultValue) -> Unit,
    ): ApplicationAuthorizationResult = withLease(
        whenClosed = ApplicationAuthorizationResult.Failed(
            DeviceActionFailure("adb_connection_closed", retryable = true),
        ),
    ) {
        if (!PACKAGE_NAME_PATTERN.matches(component.packageName)) {
            return@withLease ApplicationAuthorizationResult.Failed(
                DeviceActionFailure("maintenance_component_identity_invalid", component.componentId, retryable = false),
            )
        }
        when (val installed = inspectInstalledPackage(component.componentId, component.packageName)) {
            is PackageInspection.Failed -> return@withLease ApplicationAuthorizationResult.Failed(installed.failure)
            is PackageInspection.Completed -> if (!installed.installed) {
                return@withLease ApplicationAuthorizationResult.Failed(
                    DeviceActionFailure("maintenance_component_not_installed", component.componentId, retryable = false),
                )
            }
        }
        authorizeApplicationLocked(component, write = true, onProgress = onProgress)
    }

    override suspend fun inspectApplicationAuthorization(
        component: ManagedComponent,
    ): ApplicationAuthorizationResult = withLease(
        whenClosed = ApplicationAuthorizationResult.Failed(
            DeviceActionFailure("adb_connection_closed", retryable = true),
        ),
    ) {
        authorizeApplicationLocked(component, write = false)
    }

    private fun authorizeApplicationLocked(
        component: ManagedComponent,
        write: Boolean,
        onProgress: (ApplicationAuthorizationResultValue) -> Unit = {},
    ): ApplicationAuthorizationResult {
        if (!PACKAGE_NAME_PATTERN.matches(component.packageName)) {
            return ApplicationAuthorizationResult.Failed(
                DeviceActionFailure("maintenance_component_identity_invalid", component.componentId, retryable = false),
            )
        }
        when (val installed = inspectInstalledPackage(component.componentId, component.packageName)) {
            is PackageInspection.Failed -> return ApplicationAuthorizationResult.Failed(installed.failure)
            is PackageInspection.Completed -> if (!installed.installed) {
                return ApplicationAuthorizationResult.Failed(
                    DeviceActionFailure("maintenance_component_not_installed", component.componentId, retryable = false),
                )
            }
        }
        val declarations = readInstalledApkDeclarations(component, component.packageName)
            ?: return ApplicationAuthorizationResult.Failed(
                DeviceActionFailure("authorization_capability_metadata_missing", component.componentId, retryable = true),
            )
        val declaredRequirements = DeclaredApplicationAuthorizationPlanFactory.create(
            component.packageName,
            declarations,
        )
        val initialPermissionDump = readPackageDetails(component.packageName)
        val initialRequirements = declaredRequirements.map { requirement ->
            inspectDeclaredAuthorizationRequirement(
                packageName = component.packageName,
                requirement = requirement,
                packageDetails = initialPermissionDump,
            )
        }
        if (!write) {
            return ApplicationAuthorizationResult.Completed(
                ApplicationAuthorizationResultValue(
                    component.componentId,
                    component.packageName,
                    initialRequirements,
                ),
            )
        }
        val requirements = initialRequirements.toMutableList()
        declaredRequirements.forEachIndexed { index, requirement ->
            requirements[index] = applyDeclaredAuthorizationRequirement(
                packageName = component.packageName,
                requirement = requirement,
                before = initialRequirements[index].grantedBefore,
            )
            onProgress(
                ApplicationAuthorizationResultValue(
                    component.componentId,
                    component.packageName,
                    requirements.toList(),
                ),
            )
        }
        return ApplicationAuthorizationResult.Completed(
            ApplicationAuthorizationResultValue(component.componentId, component.packageName, requirements),
        )
    }

    private fun inspectDeclaredAuthorizationRequirement(
        packageName: String,
        requirement: DeclaredApplicationAuthorizationRequirement,
        packageDetails: String?,
    ): ApplicationAuthorizationRequirement {
        val probes = requirement.actions.map { action ->
            readDeclaredAuthorizationAction(packageName, action, packageDetails)
        }
        val granted = aggregateAuthorizationProbes(probes)
        return ApplicationAuthorizationRequirement(
            permission = requirement.declaration,
            grantedBefore = granted,
            grantedAfter = granted,
            reasonCode = authorizationFailureReason(probes),
            kind = requirement.kind,
            automaticallyActionable = requirement.automaticallyActionable,
            authorizationAttempted = false,
        )
    }

    private fun applyDeclaredAuthorizationRequirement(
        packageName: String,
        requirement: DeclaredApplicationAuthorizationRequirement,
        before: Boolean?,
    ): ApplicationAuthorizationRequirement {
        val probes = requirement.actions.map { action ->
            applyDeclaredAuthorizationAction(packageName, action)
        }
        return ApplicationAuthorizationRequirement(
            permission = requirement.declaration,
            grantedBefore = before,
            grantedAfter = aggregateAuthorizationProbes(probes),
            reasonCode = authorizationFailureReason(probes),
            kind = requirement.kind,
            automaticallyActionable = requirement.automaticallyActionable,
            authorizationAttempted = true,
        )
    }

    private fun authorizationFailureReason(probes: List<AuthorizationProbe>): String? = probes
        .filter { it.value != true && it.reasonCode != null }
        .maxByOrNull { probe ->
            when {
                probe.reasonCode?.contains("write_failed") == true -> 4
                probe.reasonCode == "authorization_confirmation_not_satisfied" -> 3
                probe.value == false -> 2
                else -> 1
            }
        }
        ?.reasonCode

    private fun aggregateAuthorizationProbes(probes: List<AuthorizationProbe>): Boolean? = when {
        probes.all { it.value == true } -> true
        probes.any { it.value == false } -> false
        else -> null
    }

    private fun applyDeclaredAuthorizationAction(
        packageName: String,
        action: DeclaredApplicationAuthorizationAction,
    ): AuthorizationProbe {
        val before = readDeclaredAuthorizationAction(packageName, action)
        if (before.value == true) return before
        val writeFailure = when (action) {
            is DeclaredApplicationAuthorizationAction.InspectPermission -> return before

            is DeclaredApplicationAuthorizationAction.GrantRuntimePermission ->
                "authorization_runtime_permission_write_failed".takeUnless {
                    isSuccessful(
                        shell("pm grant ${shellArgument(packageName)} ${shellArgument(action.permission)}"),
                    )
                }

            is DeclaredApplicationAuthorizationAction.AllowAppOp ->
                "authorization_appop_write_failed".takeUnless {
                    isSuccessful(
                        shell(
                            "appops set ${shellArgument(packageName)} " +
                                "${shellArgument(action.operation.wireName)} allow",
                        ),
                    )
                }

            is DeclaredApplicationAuthorizationAction.EnableSecureFlag ->
                "authorization_secure_flag_write_failed".takeUnless {
                    isSuccessful(
                        shell("settings put secure ${shellArgument(action.setting.wireName)} 1"),
                    )
                }

            is DeclaredApplicationAuthorizationAction.AppendSecureComponent ->
                appendDeclaredSecureComponent(action)
        }
        if (writeFailure != null) return AuthorizationProbe(false, writeFailure)
        return readDeclaredAuthorizationAction(packageName, action).let { after ->
            if (after.value == false && after.reasonCode == null) {
                after.copy(reasonCode = "authorization_confirmation_not_satisfied")
            } else {
                after
            }
        }
    }

    private fun appendDeclaredSecureComponent(
        action: DeclaredApplicationAuthorizationAction.AppendSecureComponent,
    ): String? {
        val response = shell("settings get secure ${shellArgument(action.setting.wireName)}")
            ?: return "authorization_component_list_read_failed"
        if (response.exitCode != 0) return "authorization_component_list_read_failed"
        val existing = parseAuthorizationComponentList(response.output)
            ?: return "authorization_component_list_read_failed"
        if (action.componentName in existing) return null
        val next = (existing + action.componentName).distinct()
        if (next.size > MAX_DECLARED_SECURE_LIST_ENTRIES) {
            return "authorization_capacity_entries_exceeded"
        }
        val serialized = next.joinToString(":")
        if (serialized.toByteArray(Charsets.UTF_8).size > MAX_DECLARED_SECURE_LIST_BYTES) {
            return "authorization_capacity_bytes_exceeded"
        }
        val write = if (action.setting == ManagedSecureComponentList.ENABLED_NOTIFICATION_LISTENERS) {
            shell("cmd notification allow_listener ${shellArgument(action.componentName)} 0")
        } else {
            shell(
                "settings put secure ${shellArgument(action.setting.wireName)} " +
                    shellArgument(serialized),
            )
        }
        return "authorization_component_list_write_failed".takeUnless { isSuccessful(write) }
    }

    private fun readDeclaredAuthorizationAction(
        packageName: String,
        action: DeclaredApplicationAuthorizationAction,
        packageDetails: String? = null,
    ): AuthorizationProbe = when (action) {
        is DeclaredApplicationAuthorizationAction.InspectPermission -> {
            val output = packageDetails ?: readPackageDetails(packageName)
            val granted = output?.let { parseDeclaredPermission(it, action.permission) }
            when (granted) {
                true -> AuthorizationProbe(true, null, AuthorizationValueState.GRANTED)
                false -> AuthorizationProbe(
                    false,
                    "authorization_not_automatically_grantable",
                    AuthorizationValueState.DENIED,
                )
                null -> AuthorizationProbe(null, "authorization_permission_state_unknown")
            }
        }

        is DeclaredApplicationAuthorizationAction.GrantRuntimePermission -> {
            val output = packageDetails ?: readPackageDetails(packageName)
            val granted = output?.let { parseDeclaredPermission(it, action.permission) }
            when (granted) {
                true -> AuthorizationProbe(true, null, AuthorizationValueState.GRANTED)
                false -> AuthorizationProbe(
                    false,
                    "authorization_runtime_permission_not_granted",
                    AuthorizationValueState.DENIED,
                )
                null -> AuthorizationProbe(null, "authorization_runtime_permission_read_failed")
            }
        }

        is DeclaredApplicationAuthorizationAction.AllowAppOp -> {
            val response = shell(
                "appops get ${shellArgument(packageName)} ${shellArgument(action.operation.wireName)}",
            )
            when (
                val state = response?.takeIf { it.exitCode == 0 }
                    ?.let { AppOpsResponseParser.parse(it.output, action.operation.wireName) }
            ) {
                AuthorizationValueState.ALLOWED -> AuthorizationProbe(true, null, state)
                null -> AuthorizationProbe(null, "authorization_appop_read_failed")
                else -> AuthorizationProbe(false, "authorization_appop_not_allowed", state)
            }
        }

        is DeclaredApplicationAuthorizationAction.EnableSecureFlag -> {
            val response = shell("settings get secure ${shellArgument(action.setting.wireName)}")
            when {
                response == null || response.exitCode != 0 ->
                    AuthorizationProbe(null, "authorization_secure_setting_read_failed")
                response.output.trim() == "1" -> AuthorizationProbe(true, null, AuthorizationValueState.ENABLED)
                response.output.trim() == "0" -> AuthorizationProbe(
                    false,
                    "authorization_secure_setting_disabled",
                    AuthorizationValueState.DISABLED,
                )
                else -> AuthorizationProbe(null, "authorization_secure_setting_read_failed")
            }
        }

        is DeclaredApplicationAuthorizationAction.AppendSecureComponent -> {
            val response = shell("settings get secure ${shellArgument(action.setting.wireName)}")
            val entries = response?.takeIf { it.exitCode == 0 }
                ?.let { parseAuthorizationComponentList(it.output) }
            when {
                entries == null -> AuthorizationProbe(null, "authorization_component_list_read_failed")
                action.componentName in entries -> AuthorizationProbe(
                    true,
                    null,
                    AuthorizationValueState.COMPONENT_PRESENT,
                    entries.size,
                )
                else -> AuthorizationProbe(
                    false,
                    "authorization_component_not_present",
                    AuthorizationValueState.COMPONENT_ABSENT,
                    entries.size,
                )
            }
        }
    }

    override suspend fun inspectComponentAuthorization(
        components: List<ManagedComponent>,
        installedApplications: List<ManagedApplicationProbe>,
    ): MaintenanceAuthorizationResult = withLease(
        whenClosed = MaintenanceAuthorizationResult.Failed(
            DeviceActionFailure("adb_connection_closed", retryable = true),
        ),
    ) {
        inspectComponentAuthorizationLocked(components, installedApplications, emptyMap())
    }

    override suspend fun inspectComponentAuthorization(
        components: List<ManagedComponent>,
        installedApplications: List<ManagedApplicationProbe>,
        declarationsByComponent: Map<String, com.ninepointnine.helper.domain.device.ApkDeclarationMetadata>,
    ): MaintenanceAuthorizationResult = withLease(
        whenClosed = MaintenanceAuthorizationResult.Failed(
            DeviceActionFailure("adb_connection_closed", retryable = true),
        ),
    ) {
        inspectComponentAuthorizationLocked(components, installedApplications, declarationsByComponent)
    }

    private fun inspectInstalledApplicationInventoryLocked(
        components: List<ManagedComponent>,
    ): ManagedApplicationsResult {
        if (components.map { it.componentId }.toSet().size != components.size ||
            components.any {
                !COMPONENT_ID_PATTERN.matches(it.componentId) || !PACKAGE_NAME_PATTERN.matches(it.packageName)
            }
        ) {
            return ManagedApplicationsResult.Failed(
                DeviceActionFailure("maintenance_components_invalid", retryable = false),
            )
        }
        // Read the complete package inventory once. The versioned form also
        // carries versionCode, avoiding three extra ADB calls per component.
        val discoveredPackages = readPackageInventory()
            ?: return ManagedApplicationsResult.Failed(
                DeviceActionFailure("maintenance_package_inventory_failed", retryable = true),
            )
        if (discoveredPackages.isEmpty()) {
            return ManagedApplicationsResult.Completed(emptyList())
        }
        val componentByPackage = buildMap {
            components.forEach { component ->
                val aliases = (InstallerComponentTrustRegistry.allowedPackageNames(component.componentId) + component.packageName)
                    .filter { PACKAGE_NAME_PATTERN.matches(it) }
                aliases.forEach { packageName ->
                    putIfAbsent(packageName, component)
                }
            }
        }
        val applications = mutableListOf<ManagedApplicationProbe>()
        discoveredPackages.sortedBy { it.packageName }.forEach { entry ->
            val component = componentByPackage[entry.packageName] ?: return@forEach
            val resolvedComponent = component.copy(packageName = entry.packageName)
            if (entry.versionCode != null) {
                applications += ManagedApplicationProbe(
                    componentId = resolvedComponent.componentId,
                    packageName = entry.packageName,
                    installed = true,
                    versionCode = entry.versionCode,
                )
                return@forEach
            }
            // A legacy package-manager response without versionCode is still
            // usable for presence, but enrich it only on that compatibility
            // path so normal maintenance checks stay one round trip.
            when (val result = inspectInstalledPackage(resolvedComponent.componentId, entry.packageName)) {
                is PackageInspection.Completed -> if (result.installed) {
                    applications += result.toProbe(resolvedComponent)
                }

                is PackageInspection.Failed -> return ManagedApplicationsResult.Failed(result.failure)
            }
        }
        return ManagedApplicationsResult.Completed(
            applications.sortedWith(compareBy<ManagedApplicationProbe> { it.componentId }.thenBy { it.packageName }),
        )
    }

    private fun readPackageInventory(): List<PackageInventoryEntry>? {
        if (versionedPackageInventorySupported != false) {
            val versioned = shell("pm list packages --show-versioncode")
            if (versioned != null && versioned.exitCode == 0) {
                val entries = PackageInventoryParser.parseEntries(versioned.output)
                if (entries.isNotEmpty()) {
                    versionedPackageInventorySupported = true
                    return entries
                }
            }
            versionedPackageInventorySupported = false
        }
        val response = shell("pm list packages") ?: return null
        if (response.exitCode != 0) return null
        val entries = PackageInventoryParser.parseEntries(response.output)
        // Empty stdout is a valid empty inventory. Non-empty stdout with no
        // parseable package row is not a trustworthy device state; treating it
        // as "nothing installed" would recreate the stale UI bug.
        if (entries.isEmpty() && response.output.lineSequence().any { it.trim().isNotEmpty() }) {
            return null
        }
        return entries
    }

    private fun inspectComponentAuthorizationLocked(
        components: List<ManagedComponent>,
        installedApplications: List<ManagedApplicationProbe>? = null,
        declarationsByComponent: Map<String, com.ninepointnine.helper.domain.device.ApkDeclarationMetadata> = emptyMap(),
    ): MaintenanceAuthorizationResult {
        if (components.map { it.componentId }.toSet().size != components.size ||
            components.any {
                !COMPONENT_ID_PATTERN.matches(it.componentId) || !PACKAGE_NAME_PATTERN.matches(it.packageName)
            }
        ) {
            return MaintenanceAuthorizationResult.Failed(
                DeviceActionFailure("maintenance_components_invalid", retryable = false),
            )
        }
        val inventory = installedApplications?.let(ManagedApplicationsResult::Completed)
            ?: inspectInstalledApplicationInventoryLocked(components)
        if (inventory is ManagedApplicationsResult.Failed) {
            return MaintenanceAuthorizationResult.Failed(inventory.failure)
        }
        val installedById = (inventory as ManagedApplicationsResult.Completed).applications
            .associateBy { it.componentId }
        val statuses = components.mapNotNull { component ->
            val installed = installedById[component.componentId] ?: return@mapNotNull null
            val declarations = declarationsByComponent[component.componentId]
                ?: readInstalledApkDeclarations(component, installed.packageName)
            val plan = when (val result = AuthorizationPlanFactory.createForComponents(
                components = listOf(component),
                requireDesktop = false,
                declaredServicesByComponent = mapOf(
                    component.componentId to declarations?.services.orEmpty(),
                ).filterValues { it.isNotEmpty() },
            )) {
                is AuthorizationPlanBuildResult.Ready -> result.plan
                is AuthorizationPlanBuildResult.Rejected -> {
                    return@mapNotNull ManagedApplicationAuthorizationStatus(
                        componentId = component.componentId,
                        packageName = installed.packageName,
                        authorized = null,
                        state = MaintenanceAuthorizationState.ERROR,
                        reasonCode = result.reasonCode,
                    )
                }
            }
            if (plan.actions.isEmpty()) {
                return@mapNotNull ManagedApplicationAuthorizationStatus(
                    componentId = component.componentId,
                    packageName = installed.packageName,
                    authorized = true,
                    state = MaintenanceAuthorizationState.AUTHORIZED,
                    reasonCode = "authorization_not_required",
                )
            }
            val probes = plan.actions.map(::readAuthorizationAction)
            val unmet = probes.firstOrNull { it.value == false }
            val failedProbe = probes.firstOrNull { it.value == null }
            when {
                unmet != null -> ManagedApplicationAuthorizationStatus(
                    componentId = component.componentId,
                    packageName = installed.packageName,
                    authorized = false,
                    state = MaintenanceAuthorizationState.NOT_AUTHORIZED,
                    reasonCode = unmet.reasonCode,
                )

                failedProbe != null -> ManagedApplicationAuthorizationStatus(
                    componentId = component.componentId,
                    packageName = installed.packageName,
                    authorized = null,
                    state = MaintenanceAuthorizationState.ERROR,
                    reasonCode = failedProbe.reasonCode,
                )

                else -> ManagedApplicationAuthorizationStatus(
                    componentId = component.componentId,
                    packageName = installed.packageName,
                    authorized = true,
                    state = MaintenanceAuthorizationState.AUTHORIZED,
                )
            }
        }
        return MaintenanceAuthorizationResult.Completed(statuses)
    }

    private fun readAuthorizationAction(action: AuthorizationAction): AuthorizationProbe = try {
        when (action) {
        is AuthorizationAction.EnsureAppOpAllowed -> {
            val response = shell("appops get ${shellArgument(action.packageName)} ${shellArgument(action.operation.wireName)}")
            val state = response
                ?.takeIf { it.exitCode == 0 }
                ?.let { AppOpsResponseParser.parse(it.output, action.operation.wireName) }
            when (state) {
                AuthorizationValueState.ALLOWED -> AuthorizationProbe(
                    value = true,
                    reasonCode = null,
                    state = state,
                )
                null -> AuthorizationProbe(null, "authorization_appop_read_failed")
                else -> AuthorizationProbe(false, "authorization_appop_not_allowed", state)
            }
        }

        is AuthorizationAction.EnsureRuntimePermissionGranted -> {
            val response = shell("dumpsys package ${shellArgument(action.packageName)}")
            if (response == null || response.exitCode != 0) {
                AuthorizationProbe(null, "authorization_runtime_permission_read_failed")
            } else {
                val granted = Regex(
                    "(?im)^\\s*${Regex.escape(action.permission.wireName)}\\s*:\\s*granted\\s*=\\s*(true|false)\\b",
                ).find(response.output)?.groupValues?.getOrNull(1)
                    ?.equals("true", ignoreCase = true)
                when (granted) {
                    true -> AuthorizationProbe(true, null, AuthorizationValueState.GRANTED)
                    false -> AuthorizationProbe(false, "authorization_runtime_permission_not_granted", AuthorizationValueState.DENIED)
                    null -> AuthorizationProbe(null, "authorization_runtime_permission_read_failed")
                }
            }
        }

        is AuthorizationAction.EnsureSecureSettingEnabled -> {
            val response = shell("settings get secure ${shellArgument(action.setting.wireName)}")
            when {
                response == null || response.exitCode != 0 ->
                    AuthorizationProbe(null, "authorization_secure_setting_read_failed")
                response.output.trim() == "1" -> AuthorizationProbe(true, null, AuthorizationValueState.ENABLED)
                response.output.trim() == "0" -> AuthorizationProbe(false, "authorization_secure_setting_disabled", AuthorizationValueState.DISABLED)
                else -> AuthorizationProbe(null, "authorization_secure_setting_read_failed")
            }
        }

        is AuthorizationAction.AppendSecureComponent -> {
            val response = shell("settings get secure ${shellArgument(action.setting.wireName)}")
            if (response == null || response.exitCode != 0) {
                AuthorizationProbe(null, "authorization_component_list_read_failed")
            } else {
                when (val entries = parseAuthorizationComponentList(response.output)) {
                    null -> AuthorizationProbe(null, "authorization_component_list_read_failed")
                    else -> {
                        val present = action.targetComponent in entries
                        if (present) {
                            AuthorizationProbe(
                                value = true,
                                reasonCode = null,
                                state = AuthorizationValueState.COMPONENT_PRESENT,
                                preservedEntryCount = entries.size,
                            )
                        } else {
                            AuthorizationProbe(
                                value = false,
                                reasonCode = "authorization_component_not_present",
                                state = AuthorizationValueState.COMPONENT_ABSENT,
                                preservedEntryCount = entries.size,
                            )
                        }
                    }
                }
            }
        }
        }
    } catch (_: Exception) {
        AuthorizationProbe(
            value = null,
            reasonCode = when (action) {
                is AuthorizationAction.EnsureAppOpAllowed -> "authorization_appop_read_failed"
                is AuthorizationAction.EnsureRuntimePermissionGranted -> "authorization_runtime_permission_read_failed"
                is AuthorizationAction.EnsureSecureSettingEnabled -> "authorization_secure_setting_read_failed"
                is AuthorizationAction.AppendSecureComponent -> "authorization_component_list_read_failed"
            },
        )
    }

    private data class AuthorizationProbe(
        val value: Boolean?,
        val reasonCode: String?,
        val state: AuthorizationValueState? = null,
        val preservedEntryCount: Int? = null,
    )

    private sealed interface AuthorizationPostcondition {
        data class Satisfied(
            val evidence: List<AuthorizationActionEvidence>,
            val configuredComponentIds: Set<String>,
        ) : AuthorizationPostcondition

        data class NotSatisfied(
            val action: AuthorizationAction,
            val probe: AuthorizationProbe,
            val evidence: List<AuthorizationActionEvidence>,
            val configuredComponentIds: Set<String>,
        ) : AuthorizationPostcondition

        data class Unavailable(
            val action: AuthorizationAction?,
            val evidence: List<AuthorizationActionEvidence>,
            val configuredComponentIds: Set<String>,
        ) : AuthorizationPostcondition
    }

    /** Reads the same typed authorization postcondition used by maintenance inspection. */
    private suspend fun readAuthorizationPostcondition(
        plan: com.ninepointnine.helper.domain.device.AuthorizationPlan,
    ): AuthorizationPostcondition {
        if (plan.actions.isEmpty()) {
            return AuthorizationPostcondition.Satisfied(
                evidence = emptyList(),
                configuredComponentIds = plan.components.mapTo(linkedSetOf()) { it.componentId },
            )
        }
        var lastReadings = emptyList<Pair<AuthorizationAction, AuthorizationProbe>>()
        READBACK_RETRY_DELAYS_MILLIS.take(AUTHORIZATION_CONFIRMATION_ATTEMPTS).forEachIndexed { index, delayMillis ->
            val readings = plan.actions.map { action -> action to readAuthorizationAction(action) }
            lastReadings = readings
            val evidence = authorizationEvidenceFor(readings)
            val configured = configuredComponentIdsFor(readings, plan)
            val unmet = readings.firstOrNull { it.second.value == false }
            val unknown = readings.firstOrNull { it.second.value == null || it.second.state == null }
            if (unknown == null &&
                evidence.size == plan.actions.size &&
                AuthorizationPlanFactory.validateEvidence(plan, evidence)
            ) {
                return AuthorizationPostcondition.Satisfied(
                    evidence = evidence,
                    configuredComponentIds = configured,
                )
            }
            if (index < AUTHORIZATION_CONFIRMATION_ATTEMPTS - 1) delay(delayMillis)
        }
        val evidence = authorizationEvidenceFor(lastReadings)
        val unmet = lastReadings.firstOrNull { it.second.value == false }
        if (unmet != null) {
            return AuthorizationPostcondition.NotSatisfied(
                action = unmet.first,
                probe = unmet.second,
                evidence = evidence,
                configuredComponentIds = configuredComponentIdsFor(lastReadings, plan),
            )
        }
        return AuthorizationPostcondition.Unavailable(
            action = lastReadings.firstOrNull { it.second.value == null || it.second.state == null }?.first,
            evidence = evidence,
            configuredComponentIds = configuredComponentIdsFor(lastReadings, plan),
        )
    }

    private fun authorizationEvidenceFor(
        readings: List<Pair<AuthorizationAction, AuthorizationProbe>>,
    ): List<AuthorizationActionEvidence> = readings.mapNotNull { (action, probe) ->
        if (probe.value != true) return@mapNotNull null
        val after = probe.state ?: return@mapNotNull null
        val preserved = if (action is AuthorizationAction.AppendSecureComponent) {
            probe.preservedEntryCount ?: return@mapNotNull null
        } else {
            null
        }
        AuthorizationActionEvidence(
            componentId = action.componentId,
            actionId = action.id,
            // This is a read-only postcondition receipt. The desired value was
            // already present when observed, so no new write is claimed here.
            before = after,
            writeApplied = false,
            after = after,
            preservedEntryCount = preserved,
        )
    }

    private fun configuredComponentIdsFor(
        readings: List<Pair<AuthorizationAction, AuthorizationProbe>>,
        plan: com.ninepointnine.helper.domain.device.AuthorizationPlan,
    ): Set<String> {
        val evidence = authorizationEvidenceFor(readings)
        return if (AuthorizationPlanFactory.validateEvidenceSubset(plan, evidence)) {
            AuthorizationPlanFactory.configuredComponentIdsForEvidence(plan, evidence)
        } else {
            emptySet()
        }
    }

    private fun parseAuthorizationComponentList(output: String): List<String>? {
        val normalized = output.trim()
        if (normalized.isBlank() || normalized == "null") return emptyList()
        if (normalized.any { it == '\n' || it == '\r' || it.isWhitespace() }) return null
        val entries = normalized.split(':')
        if (entries.any { it.isBlank() }) return null
        return entries.distinct()
    }

    private fun DeviceShortcutResult.skippedComponentIdsOrEmpty(): Set<String> = when (this) {
        is DeviceShortcutResult.Completed -> skippedComponentIds
        is DeviceShortcutResult.Failed -> skippedComponentIds
    }

    private fun DeviceShortcutResult.availabilityEvidenceOrEmpty(): List<com.ninepointnine.helper.domain.device.ManagedApplicationAvailabilityEvidence> = when (this) {
        is DeviceShortcutResult.Completed -> availabilityEvidence
        is DeviceShortcutResult.Failed -> availabilityEvidence
    }

    override suspend fun performApplicationAction(
        component: com.ninepointnine.helper.domain.device.ManagedComponent,
        actionId: MaintenanceApplicationActionId,
    ): MaintenanceDeviceResult = withLease(
        whenClosed = MaintenanceDeviceResult.Failed(
            DeviceActionFailure("adb_connection_closed", retryable = true),
        ),
    ) {
        if (!COMPONENT_ID_PATTERN.matches(component.componentId) ||
            !PACKAGE_NAME_PATTERN.matches(component.packageName)
        ) {
            return@withLease MaintenanceDeviceResult.Failed(
                DeviceActionFailure("maintenance_component_identity_invalid", component.componentId, retryable = false),
            )
        }
        if (actionId == MaintenanceApplicationActionId.UNINSTALL) {
            when (probePackagePresence(component.packageName)) {
                PackagePresence.ABSENT ->
                    // Uninstall is idempotent. A stale management row can race
                    // with another remover; the desired postcondition is
                    // already true, so report success and refresh inventory.
                    return@withLease MaintenanceDeviceResult.Completed("component_already_uninstalled")
                PackagePresence.PRESENT -> Unit
                PackagePresence.UNKNOWN -> return@withLease MaintenanceDeviceResult.Failed(
                    DeviceActionFailure("maintenance_package_check_failed", component.componentId, retryable = true),
                )
            }
        } else {
            when (val installed = inspectInstalledPackage(component.componentId, component.packageName)) {
                is PackageInspection.Failed -> return@withLease MaintenanceDeviceResult.Failed(installed.failure)
                is PackageInspection.Completed -> if (!installed.installed) {
                    return@withLease MaintenanceDeviceResult.Failed(
                        DeviceActionFailure("maintenance_component_not_installed", component.componentId, retryable = false),
                    )
                }
            }
        }
        when (actionId) {
            MaintenanceApplicationActionId.START -> {
                val launchComponent = AuthorizationPlanFactory.fixedLaunchComponent(component)
                if (launchComponent == null ||
                    launchComponent.substringBefore('/', missingDelimiterValue = "") != component.packageName ||
                    !COMPONENT_NAME_PATTERN.matches(launchComponent)
                ) {
                    MaintenanceDeviceResult.Failed(
                        DeviceActionFailure("maintenance_launch_unavailable", component.componentId, retryable = false),
                    )
                } else {
                    val launch = shell("am start -n ${shellArgument(launchComponent)}")
                    // Process publication can lag behind am start on the car.
                    // Treat pidof as an observation, never as the launch write
                    // result shown to the user.
                    waitForProcess(component.packageName)
                    if (!isLaunchAccepted(launch)) {
                        MaintenanceDeviceResult.Failed(
                            DeviceActionFailure("maintenance_launch_failed", component.componentId, retryable = true),
                        )
                    } else {
                        MaintenanceDeviceResult.Completed("component_launched")
                    }
                }
            }
            MaintenanceApplicationActionId.FORCE_STOP -> {
                val response = shell("am force-stop ${shellArgument(component.packageName)}")
                if (!isSuccessful(response)) {
                    MaintenanceDeviceResult.Failed(
                        DeviceActionFailure("maintenance_force_stop_failed", component.componentId, retryable = true),
                    )
                } else {
                    MaintenanceDeviceResult.Completed("component_force_stopped")
                }
            }

            MaintenanceApplicationActionId.CLEAR_DATA -> {
                val response = shell("cmd package clear --user 0 ${shellArgument(component.packageName)}")
                if (!isClearDataAccepted(response)) {
                    MaintenanceDeviceResult.Failed(
                        DeviceActionFailure("maintenance_clear_data_failed", component.componentId, retryable = true),
                    )
                } else if (probePackagePresence(component.packageName) != PackagePresence.PRESENT) {
                    MaintenanceDeviceResult.Failed(
                        DeviceActionFailure("maintenance_clear_data_postcondition_failed", component.componentId, retryable = true),
                    )
                } else {
                    MaintenanceDeviceResult.Completed("component_data_cleared")
                }
            }

            MaintenanceApplicationActionId.INSPECT_AUTHORIZATION,
            MaintenanceApplicationActionId.AUTHORIZE -> MaintenanceDeviceResult.Failed(
                DeviceActionFailure("maintenance_authorization_requires_scan", component.componentId, retryable = false),
            )

            MaintenanceApplicationActionId.UNINSTALL -> {
                val response = shell("pm uninstall ${shellArgument(component.packageName)}")
                if (!isUninstallAccepted(response)) {
                    MaintenanceDeviceResult.Failed(
                        DeviceActionFailure("maintenance_uninstall_failed", component.componentId, retryable = true),
                    )
                } else {
                    var lastFailure: DeviceActionFailure? = null
                    var absent = false
                    // PackageManager may publish the uninstall a few seconds
                    // after returning its command response on Android 9 head
                    // units. The bounded probe is the source of truth shown
                    // to the user, not the first shell response.
                    repeat(8) { attempt ->
                        if (absent) return@repeat
                        when (probePackagePresence(component.packageName)) {
                            PackagePresence.ABSENT -> {
                                absent = true
                                return@repeat
                            }

                            PackagePresence.PRESENT -> lastFailure = DeviceActionFailure(
                                "maintenance_uninstall_postcondition_failed",
                                component.componentId,
                                retryable = true,
                            )

                            PackagePresence.UNKNOWN -> lastFailure = DeviceActionFailure(
                                "maintenance_uninstall_postcondition_unknown",
                                component.componentId,
                                retryable = true,
                            )
                        }
                        if (!absent && attempt < 7) {
                            delay(
                                when (attempt) {
                                    0 -> 150L
                                    1 -> 300L
                                    2 -> 500L
                                    else -> 750L
                                },
                            )
                        }
                    }
                    if (absent) {
                        MaintenanceDeviceResult.Completed("component_uninstalled")
                    } else {
                        MaintenanceDeviceResult.Failed(
                            lastFailure ?: DeviceActionFailure(
                                "maintenance_uninstall_postcondition_unknown",
                                component.componentId,
                                retryable = true,
                            ),
                        )
                    }
                }
            }

            MaintenanceApplicationActionId.DETAILS -> MaintenanceDeviceResult.Failed(
                DeviceActionFailure("maintenance_application_details_unavailable", component.componentId, retryable = false),
            )
        }
    }

    override suspend fun inspectManagedApplicationDetails(
        component: com.ninepointnine.helper.domain.device.ManagedComponent,
    ): ManagedApplicationDetailsProbeResult = withLease(
        whenClosed = ManagedApplicationDetailsProbeResult.Failed(
            DeviceActionFailure("adb_connection_closed", retryable = true),
        ),
    ) {
        if (!COMPONENT_ID_PATTERN.matches(component.componentId) ||
            !PACKAGE_NAME_PATTERN.matches(component.packageName)
        ) {
            return@withLease ManagedApplicationDetailsProbeResult.Failed(
                DeviceActionFailure("maintenance_component_identity_invalid", component.componentId, retryable = false),
            )
        }
        when (val result = inspectInstalledPackage(component.componentId, component.packageName)) {
            is PackageInspection.Failed -> ManagedApplicationDetailsProbeResult.Failed(result.failure)
            is PackageInspection.Completed -> ManagedApplicationDetailsProbeResult.Completed(
                ManagedApplicationDetailsProbe(
                    componentId = component.componentId,
                    packageName = component.packageName,
                    installed = result.installed,
                    versionLabel = result.versionLabel,
                    versionCode = result.versionCode,
                    fileSizeBytes = result.fileSizeBytes,
                    installTimeEpochMillis = result.installTimeEpochMillis,
                    updateTimeEpochMillis = result.updateTimeEpochMillis,
                    filePath = result.filePath,
                    uid = result.uid,
                ),
            )
        }
    }

    override suspend fun launchManagedComponent(
        component: com.ninepointnine.helper.domain.device.ManagedComponent,
    ): MaintenanceDeviceResult = withLease(
        whenClosed = MaintenanceDeviceResult.Failed(
            DeviceActionFailure("adb_connection_closed", retryable = true),
        ),
    ) {
        if (!COMPONENT_ID_PATTERN.matches(component.componentId) ||
            !PACKAGE_NAME_PATTERN.matches(component.packageName)
        ) {
            return@withLease MaintenanceDeviceResult.Failed(
                DeviceActionFailure("maintenance_component_identity_invalid", component.componentId, retryable = false),
            )
        }
        val launchComponent = AuthorizationPlanFactory.fixedLaunchComponent(component)
            ?: return@withLease MaintenanceDeviceResult.Failed(
                DeviceActionFailure("maintenance_launch_unavailable", component.componentId, retryable = false),
            )
        val launchPackage = launchComponent.substringBefore('/', missingDelimiterValue = "")
        if (launchPackage != component.packageName || !COMPONENT_NAME_PATTERN.matches(launchComponent)) {
            return@withLease MaintenanceDeviceResult.Failed(
                DeviceActionFailure("maintenance_launch_identity_invalid", component.componentId, retryable = false),
            )
        }
        when (val installed = inspectInstalledPackage(component.componentId, component.packageName)) {
            is PackageInspection.Failed -> return@withLease MaintenanceDeviceResult.Failed(installed.failure)
            is PackageInspection.Completed -> if (!installed.installed) {
                return@withLease MaintenanceDeviceResult.Failed(
                    DeviceActionFailure("maintenance_component_not_installed", component.componentId, retryable = false),
                )
            }
        }
        val launch = shell("am start -n ${shellArgument(launchComponent)}")
        waitForProcess(component.packageName)
        if (!isLaunchAccepted(launch)) {
            return@withLease MaintenanceDeviceResult.Failed(
                DeviceActionFailure("maintenance_launch_failed", component.componentId, retryable = true),
            )
        }
        MaintenanceDeviceResult.Completed("component_launched")
    }

    private fun validateInstallableArtifact(artifact: InstallableArtifact): DeviceActionFailure? {
        if (ArtifactManifestValidator.validate(artifact.manifest) !is ManifestValidation.Valid) {
            return DeviceActionFailure("install_manifest_invalid", artifact.manifest.componentId, retryable = false)
        }
        val apkFile = artifact.apkFile
            ?: return DeviceActionFailure("install_apk_file_invalid", artifact.manifest.componentId, retryable = false)
        if (!apkFile.isFile) {
            return DeviceActionFailure("install_apk_file_invalid", artifact.manifest.componentId, retryable = false)
        }
        val metadataReader = installedApkMetadataReader
            ?: return DeviceActionFailure("install_apk_verifier_unavailable", artifact.manifest.componentId, retryable = false)
        val metadata = try {
            metadataReader.read(apkFile)
        } catch (_: Exception) {
            null
        } ?: return DeviceActionFailure("install_apk_metadata_unreadable", artifact.manifest.componentId, retryable = false)
        if (metadata.packageName != artifact.manifest.packageName) {
            return DeviceActionFailure("install_apk_package_mismatch", artifact.manifest.componentId, retryable = false)
        }
        if (metadata.certificateSha256s.none { it.equals(artifact.manifest.certificateSha256, ignoreCase = true) }) {
            return DeviceActionFailure("install_apk_certificate_mismatch", artifact.manifest.componentId, retryable = false)
        }
        if (
            metadata.version.code != artifact.manifest.apkVersion.code ||
            (artifact.manifest.apkVersion.name.isNotBlank() &&
                metadata.version.name != artifact.manifest.apkVersion.name)
        ) {
            return DeviceActionFailure("install_apk_version_mismatch", artifact.manifest.componentId, retryable = false)
        }
        return null
    }

    private suspend fun verifyInstalledArtifactIdentity(
        artifact: InstallableArtifact,
        metadataReader: ApkMetadataReader,
        verificationDirectory: File,
    ): InstalledArtifactIdentityResult {
        var lastResult: InstalledArtifactIdentityResult =
            InstalledArtifactIdentityResult.Failed(
                DeviceActionFailure(
                    "installation_package_path_missing",
                    artifact.manifest.componentId,
                    retryable = true,
                ),
            )
        READBACK_RETRY_DELAYS_MILLIS.forEachIndexed { index, delayMillis ->
            val result = verifyInstalledArtifactIdentityOnce(artifact, metadataReader, verificationDirectory)
            if (result is InstalledArtifactIdentityResult.Verified) return result
            lastResult = result
            val failure = (result as InstalledArtifactIdentityResult.Failed).failure
            if (!failure.retryable || index == READBACK_RETRY_DELAYS_MILLIS.lastIndex) return result
            delay(delayMillis)
        }
        return lastResult
    }

    private fun verifyInstalledArtifactIdentityOnce(
        artifact: InstallableArtifact,
        metadataReader: ApkMetadataReader,
        verificationDirectory: File,
    ): InstalledArtifactIdentityResult {
        val manifest = artifact.manifest
        val remoteApkPath = readInstalledApkPath(manifest.packageName)
            ?: return InstalledArtifactIdentityResult.Failed(
                DeviceActionFailure("installation_package_path_missing", manifest.componentId, retryable = true),
            )
        val pulledApk = verificationDirectory.resolve(
            "${manifest.componentId}-${manifest.apkSha256.lowercase().take(16)}.installed.apk",
        )
        pulledApk.delete()
        return try {
            adb.pull(pulledApk, remoteApkPath)
            val metadata = metadataReader.read(pulledApk)
                ?: return InstalledArtifactIdentityResult.Failed(
                    DeviceActionFailure(
                        "installation_installed_apk_metadata_unreadable",
                        manifest.componentId,
                        retryable = true,
                    ),
                )
            val digest = sha256(pulledApk)
            val certificate = metadata.certificateSha256s.firstOrNull { candidate ->
                candidate.equals(manifest.certificateSha256, ignoreCase = true)
            }
            if (metadata.packageName != manifest.packageName) {
                InstalledArtifactIdentityResult.Failed(
                    DeviceActionFailure(
                        "installation_installed_package_mismatch",
                        manifest.componentId,
                        retryable = false,
                    ),
                )
            } else if (certificate == null) {
                InstalledArtifactIdentityResult.Failed(
                    DeviceActionFailure(
                        "installation_installed_certificate_mismatch",
                        manifest.componentId,
                        retryable = false,
                    ),
                )
            } else {
                // The package name and trusted signing certificate answer the
                // identity question. Version, size and digest are retained as
                // observations of the bytes actually installed; they are not
                // a second Cloud-field identity gate after PackageManager has
                // accepted the package.
                InstalledArtifactIdentityResult.Verified(
                    InstalledArtifactEvidence(
                        componentId = manifest.componentId,
                        packageName = metadata.packageName,
                        version = metadata.version,
                        apkSizeBytes = pulledApk.length(),
                        apkSha256 = digest,
                        certificateSha256 = certificate.lowercase(),
                        declarations = metadata.declarations,
                    ),
                )
            }
        } catch (_: IOException) {
            InstalledArtifactIdentityResult.Failed(
                DeviceActionFailure("installation_installed_apk_read_failed", manifest.componentId, retryable = true),
            )
        } catch (_: Exception) {
            InstalledArtifactIdentityResult.Failed(
                DeviceActionFailure("installation_installed_apk_verify_failed", manifest.componentId, retryable = true),
            )
        } finally {
            pulledApk.delete()
        }
    }

    private fun verifyInstalledArtifactForMaintenance(
        manifest: com.ninepointnine.helper.domain.artifact.ArtifactManifest,
        metadataReader: ApkMetadataReader,
        verificationDirectory: File,
    ): MaintenanceInstalledIdentity {
        val remoteApkPath = readInstalledApkPath(manifest.packageName)
            ?: return MaintenanceInstalledIdentity.Failed(
                DeviceActionFailure("maintenance_package_path_missing", manifest.componentId, retryable = false),
            )
        val pulledApk = verificationDirectory.resolve(
            "maintenance-${manifest.componentId}-${manifest.apkSha256.lowercase().take(16)}.apk",
        )
        pulledApk.delete()
        return try {
            adb.pull(pulledApk, remoteApkPath)
            val metadata = metadataReader.read(pulledApk)
                ?: return MaintenanceInstalledIdentity.Failed(
                    DeviceActionFailure(
                        "maintenance_installed_apk_metadata_unreadable",
                        manifest.componentId,
                        retryable = true,
                    ),
                )
            val certificateMatches = metadata.certificateSha256s.any { candidate ->
                candidate.equals(manifest.certificateSha256, ignoreCase = true)
            }
            if (metadata.packageName != manifest.packageName) {
                MaintenanceInstalledIdentity.Failed(
                    DeviceActionFailure(
                        "maintenance_installed_package_mismatch",
                        manifest.componentId,
                        retryable = false,
                    ),
                )
            } else if (!certificateMatches) {
                MaintenanceInstalledIdentity.Failed(
                    DeviceActionFailure(
                        "maintenance_installed_certificate_mismatch",
                        manifest.componentId,
                        retryable = false,
                    ),
                )
            } else {
                // Maintenance authorization needs the declarations from the
                // installed, trusted package. A version or digest difference
                // is an observation, not an identity rejection.
                MaintenanceInstalledIdentity.Completed(metadata.declarations)
            }
        } catch (_: IOException) {
            MaintenanceInstalledIdentity.Failed(
                DeviceActionFailure("maintenance_installed_apk_read_failed", manifest.componentId, retryable = true),
            )
        } catch (_: Exception) {
            MaintenanceInstalledIdentity.Failed(
                DeviceActionFailure("maintenance_installed_apk_verify_failed", manifest.componentId, retryable = true),
            )
        } finally {
            pulledApk.delete()
        }
    }

    /** Reads only small package-manager evidence before considering a full APK pull. */
    private fun verifyInstalledArtifactQuick(
        manifest: com.ninepointnine.helper.domain.artifact.ArtifactManifest,
    ): DeviceActionFailure? {
        if (readInstalledApkPath(manifest.packageName) == null) {
            return DeviceActionFailure("maintenance_package_path_missing", manifest.componentId, retryable = false)
        }
        return null
    }

    /**
     * Resolve declarations from the APK currently installed on the vehicle.
     * This is intentionally used only by an explicit authorization inspection;
     * inventory listing remains metadata-only and never pulls APK bytes.
     */
    private fun readInstalledApkDeclarations(
        component: ManagedComponent,
        packageName: String,
    ): com.ninepointnine.helper.domain.device.ApkDeclarationMetadata? {
        val verificationDirectory = installedApkCacheDirectory ?: return null
        val metadataReader = installedApkMetadataReader ?: return null
        if (!verificationDirectory.mkdirs() && !verificationDirectory.isDirectory) return null
        val remotePath = readInstalledApkPath(packageName) ?: return null
        val remoteSize = shell("stat -c %s ${shellArgument(remotePath)}")
            ?.takeIf { it.exitCode == 0 }
            ?.output
            ?.trim()
            ?.toLongOrNull()
            ?: return null
        if (remoteSize !in 1L..MAX_DECLARATION_READ_BYTES) return null
        val pulledApk = verificationDirectory.resolve(
            "authorization-${component.componentId}-${Integer.toHexString(packageName.hashCode())}.apk",
        )
        pulledApk.delete()
        return try {
            adb.pull(pulledApk, remotePath)
            val metadata = metadataReader.read(pulledApk) ?: return null
            metadata.declarations.takeIf { metadata.packageName == packageName }
        } catch (_: Exception) {
            null
        } finally {
            pulledApk.delete()
        }
    }

    private fun readDeclaredPermission(packageName: String, permission: String): Boolean? {
        return readPackageDetails(packageName)?.let { parseDeclaredPermission(it, permission) }
    }

    private fun readPackageDetails(packageName: String): String? {
        val response = shell("dumpsys package ${shellArgument(packageName)}") ?: return null
        return response.output.takeIf { response.exitCode == 0 }
    }

    private fun parseDeclaredPermission(packageDetails: String, permission: String): Boolean? =
        Regex(
            "(?im)^\\s*${Regex.escape(permission)}\\s*:\\s*granted\\s*=\\s*(true|false)\\b",
        ).find(packageDetails)?.groupValues?.getOrNull(1)?.equals("true", ignoreCase = true)

    private fun readDesktopBridgeInventory(): DesktopBridgeInventory? {
        DESKTOP_BRIDGE_AUTHORITIES.forEach { authority ->
            val response = shell("content query --uri content://$authority/applications") ?: return@forEach
            if (response.exitCode != 0) return@forEach
            when (val parsed = DesktopAppCatalogBridgeParser.parse(response.output)) {
                is BridgeCatalogParseResult.Valid -> return DesktopBridgeInventory(authority, parsed.entries)
                BridgeCatalogParseResult.Invalid -> Unit
            }
        }
        return null
    }

    private fun componentIdForPackage(packageName: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(packageName.toByteArray(Charsets.UTF_8))
        return "app-" + digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun inspectInstalledPackage(componentId: String, packageName: String): PackageInspection {
        val response = shell("pm path $packageName")
            ?: return PackageInspection.Failed(
                DeviceActionFailure("maintenance_package_check_failed", componentId, retryable = true),
            )
        if (response.exitCode != 0) {
            if (isPackageAbsentResponse(response)) return PackageInspection.Completed(installed = false)
            return PackageInspection.Failed(
                DeviceActionFailure("maintenance_package_check_failed", componentId, retryable = true),
            )
        }
        val paths = when (val parsed = InstalledPackagePathParser.parse(response.output)) {
            InstalledPackagePathParseResult.Missing -> return PackageInspection.Completed(installed = false)
            InstalledPackagePathParseResult.Invalid -> return PackageInspection.Failed(
                DeviceActionFailure("maintenance_package_identity_invalid", componentId, retryable = false),
            )

            is InstalledPackagePathParseResult.Valid -> parsed
        }
        val packageInfo = shell("dumpsys package ${shellArgument(packageName)}")
            ?: return PackageInspection.Failed(
                DeviceActionFailure("maintenance_package_details_failed", componentId, retryable = true),
            )
        if (packageInfo.exitCode != 0) {
            return PackageInspection.Failed(
                DeviceActionFailure("maintenance_package_details_failed", componentId, retryable = true),
            )
        }
        val output = packageInfo.output
        val versionCode = Regex("versionCode=(\\d+)").find(output)?.groupValues?.getOrNull(1)?.toLongOrNull()
        val versionLabel = Regex("versionName=([^\\s}]+)").find(output)?.groupValues?.getOrNull(1)
        val uid = Regex("userId=(\\d+)").find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val firstInstall = Regex("(?m)^\\s*firstInstallTime=(.+)$").find(output)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.let(::parseEpochMillis)
        val lastUpdate = Regex("(?m)^\\s*lastUpdateTime=(.+)$").find(output)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.let(::parseEpochMillis)
        val filePath = paths.baseApkPath
        val fileSize = shell("stat -c %s ${shellArgument(filePath)}")
            ?.takeIf { it.exitCode == 0 }
            ?.output
            ?.trim()
            ?.lineSequence()
            ?.firstOrNull()
            ?.toLongOrNull()
        return PackageInspection.Completed(
            installed = true,
            versionLabel = versionLabel,
            versionCode = versionCode,
            fileSizeBytes = fileSize,
            installTimeEpochMillis = firstInstall,
            updateTimeEpochMillis = lastUpdate,
            filePath = filePath,
            uid = uid,
        )
    }

    private fun isPackageAbsentResponse(response: AdbShellResponse): Boolean {
        val text = (response.output + "\n" + response.errorOutput).lowercase()
        return text.contains("unknown package") ||
            text.contains("package not found") ||
            text.contains("unable to find package") ||
            text.contains("does not exist") ||
            text.contains("not installed")
    }

    /**
     * Presence-only probe used by destructive actions. It deliberately avoids
     * dumpsys/stat because those details disappear before PackageManager has
     * finished publishing an uninstall.
     */
    private fun probePackagePresence(packageName: String): PackagePresence {
        val listed = shell("pm list packages --user 0 ${shellArgument(packageName)}")
        if (listed != null && listed.exitCode == 0) {
            val parsed = parsePackagePresence(listed.output, packageName)
            if (parsed != null) return if (parsed) PackagePresence.PRESENT else PackagePresence.ABSENT
            // A successful command with non-empty, non-package diagnostics is
            // not a trustworthy empty inventory.
            if (listed.output.lineSequence().any { it.trim().isNotEmpty() }) {
                return PackagePresence.UNKNOWN
            }
            return PackagePresence.ABSENT
        }
        val path = shell("pm path ${shellArgument(packageName)}") ?: return PackagePresence.UNKNOWN
        if (path.exitCode != 0) {
            return if (isPackageAbsentResponse(path)) PackagePresence.ABSENT else PackagePresence.UNKNOWN
        }
        return when (InstalledPackagePathParser.parse(path.output)) {
            is InstalledPackagePathParseResult.Valid -> PackagePresence.PRESENT
            InstalledPackagePathParseResult.Missing -> if (path.output.isBlank()) {
                PackagePresence.ABSENT
            } else {
                PackagePresence.UNKNOWN
            }

            InstalledPackagePathParseResult.Invalid -> PackagePresence.UNKNOWN
        }
    }

    private enum class PackagePresence {
        PRESENT,
        ABSENT,
        UNKNOWN,
    }

    private fun parseEpochMillis(value: String): Long? = parseAndroidTimestampEpochMillis(value)

    private fun remoteApkPath(artifact: InstallableArtifact): String? {
        val componentId = artifact.manifest.componentId
        val digestPrefix = artifact.manifest.apkSha256.lowercase().take(16)
        if (!COMPONENT_ID_PATTERN.matches(componentId) || !DIGEST_PREFIX_PATTERN.matches(digestPrefix)) return null
        return "/data/local/tmp/03helper-$componentId-$digestPrefix.apk"
    }

    private fun readInstalledApkPath(packageName: String): String? {
        val response = shell("pm path $packageName") ?: return null
        // A framework warning on stderr does not invalidate a structured path
        // response. Exit code and stdout are authoritative for this readback.
        if (response.exitCode != 0) return null
        return (InstalledPackagePathParser.parse(response.output) as? InstalledPackagePathParseResult.Valid)
            ?.baseApkPath
    }

    private fun deleteRemoteStagingFile(remotePath: String): Boolean = try {
        isSuccessful(adb.shell("rm -f $remotePath"))
    } catch (_: Exception) {
        false
    }

    private fun shell(command: String): AdbShellResponse? = try {
        adb.shell(command)
    } catch (_: IOException) {
        null
    }

    /** Shell stderr may contain framework diagnostics even when the typed command succeeded. */
    private fun isSuccessful(response: AdbShellResponse?): Boolean =
        response != null && response.exitCode == 0

    private fun isClearDataAccepted(response: AdbShellResponse?): Boolean =
        isSuccessful(response) && response?.output.orEmpty().lineSequence().any { it.trim() == "Success" }

    private fun isPmInstallSuccessful(response: AdbShellResponse?): Boolean = isInstallAccepted(response)

    private fun isLaunchAccepted(response: AdbShellResponse?): Boolean {
        if (!isSuccessful(response)) return false
        return response?.output.orEmpty().lines().none { line ->
            line.contains("Error", ignoreCase = true) ||
                line.contains("Exception", ignoreCase = true) ||
                line.contains("Unable", ignoreCase = true)
        }
    }

    private suspend fun waitForProcess(packageName: String): Boolean {
        repeat(PROCESS_READBACK_ATTEMPTS) {
            val process = shell("pidof ${shellArgument(packageName)}")
            if (isSuccessful(process) && !process?.output?.trim().isNullOrBlank()) return true
            delay(PROCESS_READBACK_DELAY_MILLIS)
        }
        return false
    }

    private suspend fun waitForBoundService(packageName: String, component: String): BoundServiceProbe {
        var readSucceeded = false
        repeat(SERVICE_READBACK_ATTEMPTS) { attempt ->
            val response = shell("dumpsys activity services ${shellArgument(packageName)}")
            if (isSuccessful(response)) {
                readSucceeded = true
                if (BoundServiceEvidenceParser.isBound(response?.output.orEmpty(), component)) {
                    return BoundServiceProbe.BOUND
                }
            }
            if (attempt < SERVICE_READBACK_ATTEMPTS - 1) delay(SERVICE_READBACK_DELAY_MILLIS)
        }
        return if (readSucceeded) BoundServiceProbe.UNBOUND else BoundServiceProbe.READ_FAILED
    }

    private fun shellArgument(value: String): String =
        "'" + value.replace("'", "'\\\"'\\\"'") + "'"

    private suspend fun <T> withLease(
        whenClosed: T,
        block: suspend () -> T,
    ): T = withContext(Dispatchers.IO) {
        if (closed.get()) return@withContext whenClosed
        ioMutex.withLock {
            if (closed.get()) whenClosed else block()
        }
    }

    private sealed interface InstalledArtifactIdentityResult {
        data class Verified(val evidence: InstalledArtifactEvidence) : InstalledArtifactIdentityResult

        data class Failed(val failure: DeviceActionFailure) : InstalledArtifactIdentityResult
    }

    private sealed interface MaintenanceInstalledIdentity {
        data class Completed(
            val declarations: com.ninepointnine.helper.domain.device.ApkDeclarationMetadata,
        ) : MaintenanceInstalledIdentity

        data class Failed(val failure: DeviceActionFailure) : MaintenanceInstalledIdentity
    }

    private sealed interface PackageInspection {
        data class Completed(
            val installed: Boolean,
            val versionLabel: String? = null,
            val versionCode: Long? = null,
            val fileSizeBytes: Long? = null,
            val installTimeEpochMillis: Long? = null,
            val updateTimeEpochMillis: Long? = null,
            val filePath: String? = null,
            val uid: Int? = null,
        ) : PackageInspection {
            fun toProbe(component: com.ninepointnine.helper.domain.device.ManagedComponent) =
                ManagedApplicationProbe(
                    componentId = component.componentId,
                    packageName = component.packageName,
                    installed = installed,
                    versionLabel = versionLabel,
                    versionCode = versionCode,
                    fileSizeBytes = fileSizeBytes,
                    installTimeEpochMillis = installTimeEpochMillis,
                    updateTimeEpochMillis = updateTimeEpochMillis,
                    filePath = filePath,
                    uid = uid,
                )
        }

        data class Failed(val failure: DeviceActionFailure) : PackageInspection
    }

    private companion object {
        const val TAG = "03helper-device"
        const val REMOTE_FILE_MODE = 420
        val COMPONENT_ID_PATTERN = Regex("^[a-z][a-z0-9-]{0,63}$")
        val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        val COMPONENT_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*/[A-Za-z0-9_.$]+$")
        val DIGEST_PREFIX_PATTERN = Regex("^[0-9a-f]{16}$")
        val READBACK_RETRY_DELAYS_MILLIS = longArrayOf(150L, 300L, 500L, 750L, 750L, 750L, 750L, 750L)
        const val PROCESS_READBACK_ATTEMPTS = 13
        const val PROCESS_READBACK_DELAY_MILLIS = 250L
        const val SERVICE_READBACK_ATTEMPTS = 10
        const val SERVICE_READBACK_DELAY_MILLIS = 1_000L
        const val AUTHORIZATION_CONFIRMATION_ATTEMPTS = 4
        const val MAX_DECLARED_SECURE_LIST_ENTRIES = 32
        const val MAX_DECLARED_SECURE_LIST_BYTES = 4 * 1024
        val DESKTOP_BRIDGE_AUTHORITIES = listOf(
            "com.ninepointnine.desktop.appcatalog",
            "com.ninepointnine.desktop.test.appcatalog",
        )
        const val MAX_DECLARATION_READ_BYTES = 128L * 1024L * 1024L
    }

    private enum class BoundServiceProbe {
        BOUND,
        UNBOUND,
        READ_FAILED,
    }
}

internal sealed interface InstalledPackagePathParseResult {
    data object Missing : InstalledPackagePathParseResult

    data object Invalid : InstalledPackagePathParseResult

    data class Valid(
        val baseApkPath: String,
        val allApkPaths: List<String>,
    ) : InstalledPackagePathParseResult
}

/** One strict parser shared by install identity readback and maintenance inventory. */
internal object InstalledPackagePathParser {
    private val segmentPattern = Regex("^[A-Za-z0-9._+=~@-]+$")

    fun parse(output: String): InstalledPackagePathParseResult {
        val packageLines = output.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("package:") }
            .toList()
        if (packageLines.isEmpty()) return InstalledPackagePathParseResult.Missing

        val paths = packageLines.map { it.removePrefix("package:") }
        if (paths.distinct().size != paths.size || paths.any { !isControlledApkPath(it) }) {
            return InstalledPackagePathParseResult.Invalid
        }
        val directories = paths.mapTo(linkedSetOf()) { it.substringBeforeLast('/') }
        val basePaths = paths.filter { it.substringAfterLast('/') == "base.apk" }
        if (directories.size != 1 || basePaths.size != 1) {
            return InstalledPackagePathParseResult.Invalid
        }
        return InstalledPackagePathParseResult.Valid(
            baseApkPath = basePaths.single(),
            allApkPaths = paths,
        )
    }

    private fun isControlledApkPath(path: String): Boolean {
        if (!path.startsWith("/data/app/") || !path.endsWith(".apk")) return false
        if (path.any { it == '\\' || it == '\u0000' || it.isWhitespace() }) return false
        val segments = path.removePrefix("/data/app/").split('/')
        return segments.size >= 2 && segments.all { segment ->
            segment.isNotBlank() && segment != "." && segment != ".." && segmentPattern.matches(segment)
        }
    }
}

/**
 * Failures in this set describe a violated target, safety, or runtime
 * contract. Authorization readback proves only the requested values; it
 * cannot prove that an unrelated component was not launched, an existing list
 * was preserved, the selected packages existed, or the desktop became usable.
 */
internal fun isNonMaskableAuthorizationFailure(reasonCode: String): Boolean = reasonCode in setOf(
    "authorization_component_list_not_preserved",
    "shortcut_component_selection_invalid",
    "unexpected_desktop_launch",
    "maintenance_unexpected_launch",
    "desktop_missing",
    "selected_component_missing",
    "desktop_launch_component_missing",
    "desktop_launch_failed",
    "desktop_launch_evidence_invalid",
    "desktop_process_not_running",
    "desktop_service_missing",
    "desktop_service_not_bound",
    "desktop_service_readback_failed",
    "desktop_verification_not_completed",
)

internal object CombinedAuthorizationResponseParser {
    fun parse(
        response: AdbShellResponse?,
        plan: com.ninepointnine.helper.domain.device.AuthorizationPlan,
        launchDesktop: Boolean = plan.components.any {
            it.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID
        },
    ): DeviceShortcutResult {
        if (response == null) {
            return DeviceShortcutResult.Failed(
                stage = DeviceShortcutFailureStage.AUTHORIZATION,
                failure = DeviceActionFailure("adb_combined_command_failed", retryable = true),
            )
        }
        val lines = response.output.lineSequence()
            .map(String::trim)
            .filter { it.startsWith(CombinedAuthorizationCommand.MARKER) }
            .toList()
        val failure = lines.firstOrNull { it.startsWith("${CombinedAuthorizationCommand.MARKER}|FAIL|") }
        val authorizationEvidence = lines.mapNotNull(::parseAuthorizationEvidence)
        val skippedIds = parseSkippedComponentIds(lines)
        val partialEvidenceValid = AuthorizationPlanFactory.validateEvidenceSubset(plan, authorizationEvidence)
        val configuredIds = if (partialEvidenceValid) {
            AuthorizationPlanFactory.configuredComponentIdsForEvidence(plan, authorizationEvidence)
        } else {
            emptySet()
        }
        val availabilityEvidence = emptyList<com.ninepointnine.helper.domain.device.ManagedApplicationAvailabilityEvidence>()
        // An explicit marker is the command's structured failure receipt. A
        // non-zero transport/shell exit without that marker is only a loss of
        // confidence when the complete, validated receipt is present (Android
        // 9 can append framework diagnostics after the script emits DONE).
        if (failure != null) {
            val parts = failure?.split('|').orEmpty()
            val reasonCode = parts.getOrNull(2).takeUnless { it.isNullOrBlank() } ?: "adb_combined_command_failed"
            val componentId = parts.getOrNull(3).takeUnless { it.isNullOrBlank() }
            return DeviceShortcutResult.Failed(
                stage = if (isVerificationFailureReason(reasonCode)) {
                    DeviceShortcutFailureStage.VERIFICATION
                } else {
                    DeviceShortcutFailureStage.AUTHORIZATION
                },
                failure = DeviceActionFailure(
                    reasonCode = reasonCode,
                    componentId = componentId,
                    retryable = reasonCode !in setOf("desktop_missing", "selected_component_missing"),
                ),
                configuredComponentIds = configuredIds,
                skippedComponentIds = skippedIds,
                authorizationEvidence = if (partialEvidenceValid) authorizationEvidence else emptyList(),
                availabilityEvidence = availabilityEvidence,
            )
        }
        val done = lines.any { it == "${CombinedAuthorizationCommand.MARKER}|DONE|OK" }
        if (!done) {
            return DeviceShortcutResult.Failed(
                stage = DeviceShortcutFailureStage.AUTHORIZATION,
                failure = DeviceActionFailure(
                    reasonCode = if (response.exitCode != 0) {
                        "adb_combined_command_failed"
                    } else {
                        "combined_command_result_missing"
                    },
                    retryable = response.exitCode != 0,
                ),
                configuredComponentIds = configuredIds,
                skippedComponentIds = skippedIds,
                authorizationEvidence = if (partialEvidenceValid) authorizationEvidence else emptyList(),
                availabilityEvidence = availabilityEvidence,
            )
        }

        if (!AuthorizationPlanFactory.validateEvidence(plan, authorizationEvidence)) {
            return DeviceShortcutResult.Failed(
                stage = DeviceShortcutFailureStage.AUTHORIZATION,
                failure = DeviceActionFailure("authorization_evidence_invalid", retryable = false),
                configuredComponentIds = configuredIds,
                skippedComponentIds = skippedIds,
                authorizationEvidence = if (partialEvidenceValid) authorizationEvidence else emptyList(),
                availabilityEvidence = availabilityEvidence,
            )
        }
        val launch = lines.firstOrNull { it.startsWith("${CombinedAuthorizationCommand.MARKER}|LAUNCH|") }
            ?.split('|')
        if (!launchDesktop) {
            if (launch != null) {
                return DeviceShortcutResult.Failed(
                    stage = DeviceShortcutFailureStage.VERIFICATION,
                    failure = DeviceActionFailure("unexpected_desktop_launch", retryable = false),
                    configuredComponentIds = configuredIds,
                    skippedComponentIds = skippedIds,
                    authorizationEvidence = if (partialEvidenceValid) authorizationEvidence else emptyList(),
                    availabilityEvidence = availabilityEvidence,
                )
            }
            return DeviceShortcutResult.Completed(
                configuredComponentIds = plan.components.map { it.componentId }.toSet() - skippedIds,
                skippedComponentIds = skippedIds,
                authorizationEvidence = authorizationEvidence,
                availabilityEvidence = availabilityEvidence,
                authorizationConfirmation = if (response.exitCode == 0) {
                    DeviceAuthorizationConfirmation.CONFIRMED
                } else {
                    DeviceAuthorizationConfirmation.UNKNOWN
                },
            )
        }
        val desktop = plan.components.firstOrNull { it.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID }
            ?: return DeviceShortcutResult.Failed(
                stage = DeviceShortcutFailureStage.VERIFICATION,
                failure = DeviceActionFailure("desktop_missing", retryable = false),
                configuredComponentIds = configuredIds,
                skippedComponentIds = skippedIds,
                authorizationEvidence = if (partialEvidenceValid) authorizationEvidence else emptyList(),
                availabilityEvidence = availabilityEvidence,
            )
        if (launch?.getOrNull(2) != desktop.componentId ||
            launch.getOrNull(3) != "OK"
        ) {
            return DeviceShortcutResult.Failed(
                stage = DeviceShortcutFailureStage.VERIFICATION,
                failure = DeviceActionFailure("desktop_launch_evidence_invalid", retryable = false),
                configuredComponentIds = configuredIds,
                skippedComponentIds = skippedIds,
                authorizationEvidence = if (partialEvidenceValid) authorizationEvidence else emptyList(),
                availabilityEvidence = availabilityEvidence,
            )
        }
        val selectedIds = plan.components.map { it.componentId }.toSet()
        return DeviceShortcutResult.Completed(
            configuredComponentIds = selectedIds - skippedIds,
            skippedComponentIds = skippedIds,
            authorizationEvidence = authorizationEvidence,
            availabilityEvidence = availabilityEvidence,
            authorizationConfirmation = if (response.exitCode == 0) {
                DeviceAuthorizationConfirmation.CONFIRMED
            } else {
                DeviceAuthorizationConfirmation.UNKNOWN
            },
        )
    }

    private fun parseSkippedComponentIds(lines: List<String>): Set<String> = lines.mapNotNull { line ->
        line.split('|').takeIf { it.size >= 3 && it[1] == "SKIP" }?.get(2)
    }.toSet()

    private fun isVerificationFailureReason(reasonCode: String): Boolean = reasonCode in setOf(
        "desktop_launch_failed",
        "desktop_process_not_running",
        "desktop_service_not_bound",
        "desktop_launch_evidence_invalid",
        "desktop_verification_not_completed",
    )

    fun parseRepair(
        response: AdbShellResponse?,
        plan: com.ninepointnine.helper.domain.device.AuthorizationPlan,
    ): MaintenanceDeviceResult {
        if (response == null) {
            return MaintenanceDeviceResult.Failed(
                DeviceActionFailure("adb_combined_command_failed", retryable = true),
            )
        }
        val lines = response.output.lineSequence()
            .map(String::trim)
            .filter { it.startsWith(CombinedAuthorizationCommand.MARKER) }
            .toList()
        val failure = lines.firstOrNull { it.startsWith("${CombinedAuthorizationCommand.MARKER}|FAIL|") }
        if (failure != null) {
            val parts = failure?.split('|').orEmpty()
            val reasonCode = parts.getOrNull(2).takeUnless { it.isNullOrBlank() }
                ?: "adb_combined_command_failed"
            val componentId = parts.getOrNull(3).takeUnless { it.isNullOrBlank() }
            return MaintenanceDeviceResult.Failed(
                DeviceActionFailure(
                    reasonCode = reasonCode,
                    componentId = componentId,
                    retryable = reasonCode !in setOf("desktop_missing", "selected_component_missing"),
                ),
            )
        }
        val done = lines.any { it == "${CombinedAuthorizationCommand.MARKER}|DONE|OK" }
        if (!done) {
            return MaintenanceDeviceResult.Failed(
                DeviceActionFailure(
                    reasonCode = if (response.exitCode != 0) {
                        "adb_combined_command_failed"
                    } else {
                        "combined_command_result_missing"
                    },
                    retryable = response.exitCode != 0,
                ),
            )
        }
        val evidence = lines.mapNotNull(::parseAuthorizationEvidence)
        if (!AuthorizationPlanFactory.validateEvidence(plan, evidence)) {
            return MaintenanceDeviceResult.Failed(
                DeviceActionFailure("authorization_evidence_invalid", retryable = false),
            )
        }
        if (lines.any { it.startsWith("${CombinedAuthorizationCommand.MARKER}|LAUNCH|") }) {
            return MaintenanceDeviceResult.Failed(
                DeviceActionFailure("maintenance_unexpected_launch", retryable = false),
            )
        }
        return MaintenanceDeviceResult.Completed("authorization_repaired")
    }

    private fun parseAuthorizationEvidence(line: String): AuthorizationActionEvidence? {
        val parts = line.split('|')
        if (parts.size < 8 || parts[1] != "AUTH") return null
        val before = runCatching { AuthorizationValueState.valueOf(parts[4]) }.getOrNull() ?: return null
        val after = runCatching { AuthorizationValueState.valueOf(parts[6]) }.getOrNull() ?: return null
        val changed = when (parts[5]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val preserved = parts[7].toIntOrNull()
        return AuthorizationActionEvidence(
            componentId = parts[2],
            actionId = parts[3],
            before = before,
            writeApplied = changed,
            after = after,
            preservedEntryCount = preserved,
        )
    }
}

/**
 * The only post-install authorization executor owned by the helper. Its
 * versioned source code is parameterized only by validated typed actions and
 * invoked exactly once.
 */
internal object CombinedAuthorizationCommand {
    const val MARKER = "03HELPER"

    fun build(
        plan: com.ninepointnine.helper.domain.device.AuthorizationPlan,
        repairOnly: Boolean = false,
        launchDesktop: Boolean = true,
    ): String {
        require(AuthorizationPlanFactory.validate(plan)) { "invalid_authorization_plan" }
        val desktop = plan.components.firstOrNull {
            it.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID
        }
        val dynamic = desktop == null || plan.components.any { component ->
            component.setup != null || component.componentId !in setOf(
                AuthorizationPlanFactory.DESKTOP_COMPONENT_ID,
                AuthorizationPlanFactory.LYRICS_COMPONENT_ID,
                AuthorizationPlanFactory.FILE_MANAGER_COMPONENT_ID,
            ) || component.packageName != staticPackageName(component.componentId)
        }
        val desktopPackage = desktop?.packageName
        val desktopLaunchComponent = desktop?.let(AuthorizationPlanFactory::fixedLaunchComponent)
        require(
            !launchDesktop || !dynamic || desktopLaunchComponent != null,
        ) { "desktop_runtime_contract_missing" }
        val arguments = buildList {
            if (repairOnly) add("--repair")
            if (!launchDesktop) add("--no-desktop")
            if (dynamic) add("--dynamic")
            if (dynamic && desktopPackage != null) add("--desktop-package=$desktopPackage")
            if (dynamic && desktopLaunchComponent != null) add("--desktop-launch=$desktopLaunchComponent")
            addAll(plan.components.map { it.componentId })
            if (dynamic) plan.actions.forEach { action -> add("--action=${action.toWireToken()}") }
        }.joinToString(" ") { shellQuote(it) }
        return "sh -c ${shellQuote(SCRIPT)} 03helper $arguments"
    }

    fun build(
        selectedComponentIds: Set<String>,
        repairOnly: Boolean = false,
    ): String {
        val ordered = AuthorizationPlanFactory.allManagedComponents()
            .map { it.componentId }
            .filter { it in selectedComponentIds }
        require(ordered.toSet() == selectedComponentIds) { "unapproved_component" }
        val arguments = buildList {
            if (repairOnly) add("--repair")
            addAll(ordered)
        }.joinToString(" ")
        return "sh -c ${shellQuote(SCRIPT)} 03helper $arguments"
    }

    private fun com.ninepointnine.helper.domain.device.AuthorizationAction.toWireToken(): String = when (this) {
        is com.ninepointnine.helper.domain.device.AuthorizationAction.EnsureAppOpAllowed ->
            listOf("APP_OP", componentId, id, packageName, operation.wireName).joinToString("|")

        is com.ninepointnine.helper.domain.device.AuthorizationAction.EnsureRuntimePermissionGranted ->
            listOf("RUNTIME", componentId, id, packageName, permission.wireName).joinToString("|")

        is com.ninepointnine.helper.domain.device.AuthorizationAction.EnsureSecureSettingEnabled ->
            listOf("SECURE_FLAG", componentId, id, setting.wireName).joinToString("|")

        is com.ninepointnine.helper.domain.device.AuthorizationAction.AppendSecureComponent ->
            listOf("SECURE_COMPONENT", componentId, id, setting.wireName, targetComponent).joinToString("|")
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    /** Package identities understood by the legacy static script branch. */
    private fun staticPackageName(componentId: String): String? = when (componentId) {
        AuthorizationPlanFactory.DESKTOP_COMPONENT_ID -> AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME
        AuthorizationPlanFactory.LYRICS_COMPONENT_ID -> AuthorizationPlanFactory.LYRICS_PACKAGE_NAME
        AuthorizationPlanFactory.FILE_MANAGER_COMPONENT_ID -> AuthorizationPlanFactory.FILE_MANAGER_PACKAGE_NAME
        else -> null
    }

    private val SCRIPT = """
        set -u
        marker='03HELPER'
        repair_only=0
        if [ "${'$'}{1:-}" = --repair ]; then repair_only=1; shift; fi
        launch_desktop=1
        if [ "${'$'}{1:-}" = --no-desktop ]; then launch_desktop=0; shift; fi
        dynamic_mode=0
        if [ "${'$'}{1:-}" = --dynamic ]; then dynamic_mode=1; shift; fi
        desktop_package='${AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME}'
        case "${'$'}{1:-}" in
          --desktop-package=*) desktop_package="${'$'}{1#--desktop-package=}"; shift;;
        esac
        desktop_launch_component=''
        case "${'$'}{1:-}" in
          --desktop-launch=*) desktop_launch_component="${'$'}{1#--desktop-launch=}"; shift;;
        esac
        selected() { wanted="${'$'}1"; shift; for value in "${'$'}@"; do [ "${'$'}value" = "${'$'}wanted" ] && return 0; done; return 1; }
        emit() { printf '%s|%s\n' "${'$'}marker" "${'$'}*"; }
        fail() { emit "FAIL|${'$'}1|${'$'}{2:-}"; exit 1; }
        list_contains() { case ":${'$'}1:" in *":${'$'}2:"*) return 0;; *) return 1;; esac; }
        dedupe_list() {
          raw="${'$'}1"; result=""; old_ifs="${'$'}IFS"; IFS=':'
          for item in ${'$'}raw; do
            [ -n "${'$'}item" ] || continue
            case ":${'$'}result:" in *":${'$'}item:"*) continue;; esac
            if [ -n "${'$'}result" ]; then result="${'$'}result:${'$'}item"; else result="${'$'}item"; fi
          done
          IFS="${'$'}old_ifs"; printf '%s' "${'$'}result"
        }
        list_preserved() {
          [ "${'$'}1" = "null" ] || [ -z "${'$'}1" ] && return 0
          old_ifs="${'$'}IFS"; IFS=':'
          for item in ${'$'}1; do
            [ -n "${'$'}item" ] || continue
            list_contains "${'$'}2" "${'$'}item" || { IFS="${'$'}old_ifs"; return 1; }
          done
          IFS="${'$'}old_ifs"; return 0
        }
        list_count() {
          if [ "${'$'}1" = "null" ] || [ -z "${'$'}1" ]; then
            echo 0
          else
            value="${'$'}1"; count=1
            while [ "${'$'}{value#*:}" != "${'$'}value" ]; do
              value="${'$'}{value#*:}"
              count=${'$'}((count + 1))
            done
            echo "${'$'}count"
          fi
        }
        package_present() { pm path "${'$'}1" >/dev/null 2>&1; }
        appop_state() {
          value="${'$'}(appops get "${'$'}1" "${'$'}2" 2>/dev/null)" || return 1
          case "${'$'}value" in
            *"${'$'}2: allow"*) echo ALLOWED;;
            *"${'$'}2: deny"*) echo DENIED;;
            *"${'$'}2: ignore"*) echo IGNORED;;
            *"${'$'}2: errored"*) echo ERRORED;;
            *"Default mode: allow"*) echo ALLOWED;;
            *"Default mode: default"*) echo DEFAULT;;
            *"Default mode: ignore"*) echo IGNORED;;
            *"No operations."*) echo DEFAULT;;
            *) return 1;;
          esac
        }
        runtime_state() { dumpsys package "${'$'}1" 2>/dev/null | grep -Fq "${'$'}2: granted=true" && echo GRANTED || echo DENIED; }
        secure_flag_state() {
          value="${'$'}(settings get secure "${'$'}1" 2>/dev/null)" || return 1
          if [ "${'$'}value" = 1 ]; then
            echo ENABLED
          elif [ "${'$'}value" = 0 ]; then
            echo DISABLED
          else
            return 1
          fi
        }
        secure_list() { settings get secure "${'$'}1" 2>/dev/null || return 1; }
        emit_auth() { emit "AUTH|${'$'}1|${'$'}2|${'$'}3|${'$'}4|${'$'}5|${'$'}6"; }
        ensure_appop() {
          package="${'$'}1"; operation="${'$'}2"; component="${'$'}3"; action="${'$'}4"
          before="${'$'}(appop_state "${'$'}package" "${'$'}operation")" || fail authorization_appop_read_failed "${'$'}component"
          after="${'$'}before"; changed=0
          if [ "${'$'}before" != ALLOWED ]; then appops set "${'$'}package" "${'$'}operation" allow >/dev/null 2>&1 || fail authorization_appop_write_failed "${'$'}component"; changed=1; after="${'$'}(appop_state "${'$'}package" "${'$'}operation")" || fail authorization_appop_readback_failed "${'$'}component"; fi
          [ "${'$'}after" = ALLOWED ] || fail authorization_appop_not_allowed "${'$'}component"
          emit_auth "${'$'}component" "${'$'}action" "${'$'}before" "${'$'}changed" "${'$'}after" "-"
        }
        ensure_runtime_permission() {
          package="${'$'}1"; permission="${'$'}2"; component="${'$'}3"; action="${'$'}4"
          before="${'$'}(runtime_state "${'$'}package" "${'$'}permission")" || fail authorization_runtime_permission_read_failed "${'$'}component"
          after="${'$'}before"; changed=0
          if [ "${'$'}before" != GRANTED ]; then pm grant "${'$'}package" "${'$'}permission" >/dev/null 2>&1 || fail authorization_runtime_permission_write_failed "${'$'}component"; changed=1; after="${'$'}(runtime_state "${'$'}package" "${'$'}permission")" || fail authorization_runtime_permission_readback_failed "${'$'}component"; fi
          [ "${'$'}after" = GRANTED ] || fail authorization_runtime_permission_not_granted "${'$'}component"
          emit_auth "${'$'}component" "${'$'}action" "${'$'}before" "${'$'}changed" "${'$'}after" "-"
        }
        ensure_secure_flag() {
          setting="${'$'}1"; component="${'$'}2"; action="${'$'}3"
          before="${'$'}(secure_flag_state "${'$'}setting")" || fail authorization_secure_flag_read_failed "${'$'}component"
          after="${'$'}before"; changed=0
          if [ "${'$'}before" != ENABLED ]; then settings put secure "${'$'}setting" 1 >/dev/null 2>&1 || fail authorization_secure_flag_write_failed "${'$'}component"; changed=1; after="${'$'}(secure_flag_state "${'$'}setting")" || fail authorization_secure_flag_readback_failed "${'$'}component"; fi
          [ "${'$'}after" = ENABLED ] || fail authorization_secure_flag_not_enabled "${'$'}component"
          emit_auth "${'$'}component" "${'$'}action" "${'$'}before" "${'$'}changed" "${'$'}after" "-"
        }
        append_component() {
          setting="${'$'}1"; target="${'$'}2"; component="${'$'}3"; action="${'$'}4"
          before_raw="${'$'}(secure_list "${'$'}setting")" || fail authorization_component_list_read_failed "${'$'}component"
          before_normalized="${'$'}(dedupe_list "${'$'}before_raw")"
          before_state=COMPONENT_ABSENT; list_contains "${'$'}before_normalized" "${'$'}target" && before_state=COMPONENT_PRESENT
          after_raw="${'$'}before_normalized"; changed=0
          if [ "${'$'}before_state" = COMPONENT_ABSENT ]; then
            if [ "${'$'}setting" = enabled_notification_listeners ]; then cmd notification allow_listener "${'$'}target" 0 >/dev/null 2>&1 || fail authorization_component_list_write_failed "${'$'}component"; else
              if [ "${'$'}before_normalized" = null ] || [ -z "${'$'}before_normalized" ]; then next="${'$'}target"; else next="${'$'}before_normalized:${'$'}target"; fi
              settings put secure "${'$'}setting" "${'$'}next" >/dev/null 2>&1 || fail authorization_component_list_write_failed "${'$'}component"
            fi
            changed=1; after_raw="${'$'}(secure_list "${'$'}setting")" || fail authorization_component_list_readback_failed "${'$'}component"
          fi
          list_contains "${'$'}after_raw" "${'$'}target" || fail authorization_component_list_not_present "${'$'}component"
          list_preserved "${'$'}before_raw" "${'$'}after_raw" || fail authorization_component_list_not_preserved "${'$'}component"
          emit_auth "${'$'}component" "${'$'}action" "${'$'}before_state" "${'$'}changed" COMPONENT_PRESENT "${'$'}(list_count "${'$'}before_normalized")"
        }
        ensure_notification_listener() {
          component='lyrics'; target='com.tcrrry.desktoplyrics/com.tcrrry.desktoplyrics.MediaListenerService'
          append_component enabled_notification_listeners "${'$'}target" "${'$'}component" lyrics-notification-listener-v1
        }
        ensure_accessibility_service() {
          component="${'$'}1"; target="${'$'}2"; class_name="${'$'}3"; record="${'$'}4"; label="${'$'}5"
          enabled="${'$'}(settings get secure accessibility_enabled 2>/dev/null)" || fail authorization_secure_flag_read_failed "${'$'}component"
          if [ "${'$'}enabled" != 1 ]; then settings put secure accessibility_enabled 1 >/dev/null 2>&1 || fail authorization_secure_flag_write_failed "${'$'}component"; fi
          append_component enabled_accessibility_services "${'$'}target" "${'$'}component" "${'$'}label"
        }

        apply_dynamic_action() {
          spec="${'$'}1"
          # Android 9's mksh treats an unescaped `|` in parameter-removal
          # patterns as alternation. Parse the bounded wire tuple through IFS
          # with pathname expansion disabled so values can never become files.
          old_ifs="${'$'}IFS"; IFS='|'; set -f
          set -- ${'$'}spec
          set +f; IFS="${'$'}old_ifs"
          [ "${'$'}#" -eq 4 ] || [ "${'$'}#" -eq 5 ] || fail authorization_action_invalid
          type="${'$'}{1:-}"; component="${'$'}{2:-}"; action="${'$'}{3:-}"
          value4="${'$'}{4:-}"; value5="${'$'}{5:-}"
          [ -n "${'$'}type" ] && [ -n "${'$'}component" ] && [ -n "${'$'}action" ] && [ -n "${'$'}value4" ] || fail authorization_action_invalid
          case "${'$'}type" in
            APP_OP)
              [ -n "${'$'}value5" ] || fail authorization_action_invalid
              ensure_appop "${'$'}value4" "${'$'}value5" "${'$'}component" "${'$'}action";;
            RUNTIME)
              [ -n "${'$'}value5" ] || fail authorization_action_invalid
              ensure_runtime_permission "${'$'}value4" "${'$'}value5" "${'$'}component" "${'$'}action";;
            SECURE_FLAG)
              [ -z "${'$'}value5" ] || fail authorization_action_invalid
              ensure_secure_flag "${'$'}value4" "${'$'}component" "${'$'}action";;
            SECURE_COMPONENT)
              [ -n "${'$'}value5" ] || fail authorization_action_invalid
              append_component "${'$'}value4" "${'$'}value5" "${'$'}component" "${'$'}action";;
            *) fail authorization_action_invalid "${'$'}component";;
          esac
        }

        skipped=0
        if [ "${'$'}launch_desktop" -eq 1 ]; then
          if ! selected desktop "${'$'}@"; then fail desktop_missing desktop; fi
          if ! package_present "${'$'}desktop_package"; then fail desktop_missing desktop; fi
        fi
        if [ "${'$'}dynamic_mode" -eq 0 ]; then
          ensure_appop com.tcrrry.desktop SYSTEM_ALERT_WINDOW desktop desktop-overlay-v1
          ensure_appop com.tcrrry.desktop REQUEST_INSTALL_PACKAGES desktop desktop-install-packages-v1
          ensure_secure_flag accessibility_enabled desktop desktop-accessibility-master-v1
          ensure_accessibility_service desktop com.tcrrry.desktop/com.tcrrry.desktop.debug.NavigationDemoAccessibilityService com.tcrrry.desktop.debug.NavigationDemoAccessibilityService com.tcrrry.desktop/.debug.NavigationDemoAccessibilityService desktop-accessibility-service-v1

          if selected lyrics "${'$'}@"; then
            if package_present com.tcrrry.desktoplyrics; then
              ensure_appop com.tcrrry.desktoplyrics SYSTEM_ALERT_WINDOW lyrics lyrics-overlay-v1
              ensure_notification_listener
              ensure_accessibility_service lyrics com.tcrrry.desktoplyrics/com.tcrrry.desktoplyrics.IcarDockAccessibilityService com.tcrrry.desktoplyrics.IcarDockAccessibilityService com.tcrrry.desktoplyrics/.IcarDockAccessibilityService lyrics-accessibility-service-v1
            else
              emit SKIP lyrics; skipped=1
            fi
          fi

          if selected file-manager "${'$'}@"; then
            if package_present org.fossify.filemanager.debug; then
              ensure_runtime_permission org.fossify.filemanager.debug android.permission.READ_EXTERNAL_STORAGE file-manager file-manager-read-permission-v1
              ensure_appop org.fossify.filemanager.debug READ_EXTERNAL_STORAGE file-manager file-manager-read-appop-v1
              ensure_runtime_permission org.fossify.filemanager.debug android.permission.WRITE_EXTERNAL_STORAGE file-manager file-manager-write-permission-v1
              ensure_appop org.fossify.filemanager.debug WRITE_EXTERNAL_STORAGE file-manager file-manager-write-appop-v1
              ensure_appop org.fossify.filemanager.debug REQUEST_INSTALL_PACKAGES file-manager file-manager-install-packages-v1
            else
              emit SKIP file-manager; skipped=1
            fi
          fi
        else
          if [ "${'$'}launch_desktop" -eq 1 ]; then
            [ -n "${'$'}desktop_launch_component" ] || fail desktop_launch_component_missing desktop
          fi
          for argument in "${'$'}@"; do
            case "${'$'}argument" in
              --action=*) apply_dynamic_action "${'$'}{argument#--action=}";;
            esac
          done
        fi

        [ "${'$'}skipped" -eq 0 ] || fail selected_component_missing
        if [ "${'$'}repair_only" -eq 1 ]; then emit "DONE|OK"; exit 0; fi
        if [ "${'$'}launch_desktop" -eq 0 ]; then emit "DONE|OK"; exit 0; fi
        if [ "${'$'}dynamic_mode" -eq 0 ]; then
          launch_component='${AuthorizationPlanFactory.DESKTOP_MAIN_ACTIVITY}'
          am start -n "${'$'}launch_component" >/dev/null 2>&1 || fail desktop_launch_failed desktop
        else
          launch_component="${'$'}desktop_launch_component"
          am start -n "${'$'}launch_component" >/dev/null 2>&1 || fail desktop_launch_failed desktop
        fi
        emit "LAUNCH|desktop|OK"
        emit "DONE|OK"
    """.trimIndent()
}

/** Matches the two Android component spellings emitted by dumpsys. */
internal object BoundServiceEvidenceParser {
    fun isBound(output: String, component: String): Boolean = componentSpellings(component).any { spelling ->
        var index = output.indexOf(spelling)
        while (index >= 0) {
            val following = output.substring(index, (index + SERVICE_RECORD_WINDOW_CHARS).coerceAtMost(output.length))
            if (following.contains("hasBound=true")) return true
            index = output.indexOf(spelling, index + spelling.length)
        }
        false
    }

    private fun componentSpellings(component: String): Set<String> {
        val packageName = component.substringBefore('/', missingDelimiterValue = "")
        val className = component.substringAfter('/', missingDelimiterValue = "")
        if (packageName.isBlank() || className.isBlank()) return emptySet()
        val shortClassName = className.removePrefix("$packageName.").let { suffix ->
            if (suffix == className) null else ".${suffix}"
        }
        return buildSet {
            add(component)
            shortClassName?.let { add("$packageName/$it") }
        }
    }

    private const val SERVICE_RECORD_WINDOW_CHARS = 4_096
}

/** Parses explicit and untouched/default AppOps output across Android versions. */
internal object AppOpsResponseParser {
    fun parse(output: String, operation: String): AuthorizationValueState? {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val line = lines.firstOrNull { it.startsWith("$operation:", ignoreCase = true) }
            ?: lines.firstOrNull { it.startsWith("Default mode:", ignoreCase = true) }
        if (line == null) {
            return AuthorizationValueState.DEFAULT.takeIf {
                lines.any { it.equals("No operations.", ignoreCase = true) }
            }
        }
        val value = line.substringAfter(':').trim().substringBefore(';').trim().lowercase()
        return when (value) {
            "allow", "allowed" -> AuthorizationValueState.ALLOWED
            "deny", "denied" -> AuthorizationValueState.DENIED
            "default" -> AuthorizationValueState.DEFAULT
            "ignore", "ignored" -> AuthorizationValueState.IGNORED
            "errored", "error" -> AuthorizationValueState.ERRORED
            else -> null
        }
    }
}

internal data class PackageInventoryEntry(
    val packageName: String,
    val versionCode: Long?,
)

internal data class BridgeApplicationEntry(
    val packageName: String,
    val displayName: String,
    val versionName: String?,
    val versionCode: Long?,
    val firstInstallTime: Long?,
    val lastUpdateTime: Long?,
    val uid: Int?,
    val iconBase64: String?,
    val launcherComponent: String? = null,
)

private data class DesktopBridgeInventory(
    val authority: String,
    val entries: List<BridgeApplicationEntry>,
)

internal sealed interface BridgeCatalogParseResult {
    data class Valid(val entries: List<BridgeApplicationEntry>) : BridgeCatalogParseResult
    data object Invalid : BridgeCatalogParseResult
}

internal sealed interface BridgeIconParseResult {
    data class Valid(val iconBase64: String?) : BridgeIconParseResult
    data object Invalid : BridgeIconParseResult
}

internal object DesktopAppCatalogBridgeParser {
    private val rowPattern = Regex("^Row:\\s*(\\d+)\\s+(.*)$")
    private val fieldPattern = Regex("([A-Za-z][A-Za-z0-9]*?)=([^,]*)(?:, |$)")
    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val componentPattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*/[A-Za-z0-9_.$]+$")
    private val digestPattern = Regex("^[a-f0-9]{64}$")
    private val iconPattern = Regex("^[A-Za-z0-9_-]*$")

    fun parse(output: String): BridgeCatalogParseResult {
        val rows = parseRows(output, MAX_METADATA_OUTPUT_LENGTH) ?: return BridgeCatalogParseResult.Invalid
        val summary = rows.singleOrNull { it.fields["rowType"] == ROW_TYPE_SUMMARY }
            ?: return BridgeCatalogParseResult.Invalid
        if (summary.index != 0 || summary.fields["protocolVersion"]?.toIntOrNull() != SUPPORTED_PROTOCOL_VERSION) {
            return BridgeCatalogParseResult.Invalid
        }
        val expectedCount = summary.fields["batchCount"]?.toIntOrNull()
            ?.takeIf { it in 0..MAX_APPLICATION_COUNT }
            ?: return BridgeCatalogParseResult.Invalid
        val expectedDigest = summary.fields["batchDigest"]?.takeIf(digestPattern::matches)
            ?: return BridgeCatalogParseResult.Invalid
        val applicationRows = rows.filter { it.fields["rowType"] == ROW_TYPE_APPLICATION }
        if (rows.size != expectedCount + 1 || applicationRows.size != expectedCount) {
            return BridgeCatalogParseResult.Invalid
        }
        if (rows.map { it.index } != rows.indices.toList()) return BridgeCatalogParseResult.Invalid
        val entries = applicationRows.map { row ->
            parseApplication(row.fields, expectedCount, expectedDigest)
                ?: return BridgeCatalogParseResult.Invalid
        }
        if (entries.distinctBy { it.packageName }.size != entries.size || batchDigest(entries) != expectedDigest) {
            return BridgeCatalogParseResult.Invalid
        }
        return BridgeCatalogParseResult.Valid(
            entries.sortedWith(
                compareByDescending<BridgeApplicationEntry> { it.firstInstallTime ?: Long.MIN_VALUE }
                    .thenByDescending { it.lastUpdateTime ?: Long.MIN_VALUE }
                    .thenBy { it.packageName },
            ),
        )
    }

    fun parseIcon(output: String, expectedPackageName: String): BridgeIconParseResult {
        if (!packagePattern.matches(expectedPackageName)) return BridgeIconParseResult.Invalid
        val row = parseRows(output, MAX_ICON_OUTPUT_LENGTH)?.singleOrNull()
            ?: return BridgeIconParseResult.Invalid
        val fields = row.fields
        if (row.index != 0 ||
            fields["protocolVersion"]?.toIntOrNull() != SUPPORTED_PROTOCOL_VERSION ||
            fields["rowType"] != ROW_TYPE_ICON ||
            fields["packageName"] != expectedPackageName
        ) return BridgeIconParseResult.Invalid
        val icon = fields["iconBase64"] ?: return BridgeIconParseResult.Invalid
        if (icon.length > MAX_ICON_BASE64_LENGTH || !iconPattern.matches(icon)) {
            return BridgeIconParseResult.Invalid
        }
        return BridgeIconParseResult.Valid(icon.takeIf(String::isNotEmpty))
    }

    private fun parseApplication(
        fields: Map<String, String>,
        expectedCount: Int,
        expectedDigest: String,
    ): BridgeApplicationEntry? {
        if (fields["protocolVersion"]?.toIntOrNull() != SUPPORTED_PROTOCOL_VERSION ||
            fields["batchCount"]?.toIntOrNull() != expectedCount ||
            fields["batchDigest"] != expectedDigest
        ) return null
        val packageName = fields["packageName"]?.takeIf(packagePattern::matches) ?: return null
        val displayName = decode(fields["displayName"])?.takeIf(String::isNotBlank) ?: return null
        val versionName = decode(fields["versionName"]) ?: return null
        val versionCode = fields["versionCode"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val firstInstallTime = fields["firstInstallTime"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val lastUpdateTime = fields["lastUpdateTime"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val uid = fields["uid"]?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val launcher = fields["launcherComponent"] ?: return null
        if (launcher.isNotEmpty() &&
            (!componentPattern.matches(launcher) || launcher.substringBefore('/') != packageName)
        ) return null
        return BridgeApplicationEntry(
            packageName = packageName,
            displayName = displayName,
            versionName = versionName.ifBlank { null },
            versionCode = versionCode,
            firstInstallTime = firstInstallTime,
            lastUpdateTime = lastUpdateTime,
            uid = uid,
            iconBase64 = null,
            launcherComponent = launcher.ifBlank { null },
        )
    }

    private fun parseRows(output: String, maxLength: Int): List<BridgeRow>? {
        if (output.isBlank() || output.length > maxLength) return null
        val rows = mutableListOf<BridgeRow>()
        try {
            output.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
                val match = rowPattern.matchEntire(line) ?: throw InvalidBridgeRow
                val fieldsText = match.groupValues[2]
                val fields = fieldPattern.findAll(fieldsText).associate { it.groupValues[1] to it.groupValues[2] }
                if (fields.isEmpty() || fieldPattern.findAll(fieldsText).joinToString(", ") { it.value.removeSuffix(", ") } != fieldsText) {
                    throw InvalidBridgeRow
                }
                rows += BridgeRow(match.groupValues[1].toIntOrNull() ?: throw InvalidBridgeRow, fields)
            }
        } catch (_: InvalidBridgeRow) {
            return null
        }
        return rows
    }

    private object InvalidBridgeRow : RuntimeException()

    private fun batchDigest(entries: List<BridgeApplicationEntry>): String {
        val canonical = entries.sortedBy { it.packageName }.joinToString(RECORD_SEPARATOR) { entry ->
            listOf(
                entry.packageName,
                encode(entry.displayName),
                encode(entry.versionName.orEmpty()),
                entry.versionCode.toString(),
                entry.firstInstallTime.toString(),
                entry.lastUpdateTime.toString(),
                entry.uid.toString(),
                entry.launcherComponent.orEmpty(),
            ).joinToString(FIELD_SEPARATOR)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun decode(value: String?): String? {
        val encoded = value?.takeIf { it.length <= MAX_TEXT_BASE64_LENGTH } ?: return null
        return runCatching {
            String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.length <= MAX_DECODED_TEXT_LENGTH }
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    private const val FIELD_SEPARATOR = "\u001f"
    private const val RECORD_SEPARATOR = "\u001e"
    private const val ROW_TYPE_SUMMARY = "summary"
    private const val ROW_TYPE_APPLICATION = "application"
    private const val ROW_TYPE_ICON = "icon"
    private const val MAX_APPLICATION_COUNT = 1_000
    private const val MAX_TEXT_BASE64_LENGTH = 1024
    private const val MAX_DECODED_TEXT_LENGTH = 256
    private const val MAX_ICON_BASE64_LENGTH = 512 * 1024
    private const val MAX_METADATA_OUTPUT_LENGTH = 2 * 1024 * 1024
    private const val MAX_ICON_OUTPUT_LENGTH = MAX_ICON_BASE64_LENGTH + 1024
    private const val SUPPORTED_PROTOCOL_VERSION = 2

    private data class BridgeRow(
        val index: Int,
        val fields: Map<String, String>,
    )
}

/** Parses stable package-manager lines without depending on shell locale text. */
internal object PackageInventoryParser {
    private val packageNamePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val versionedLinePattern = Regex(
        "^package:($PACKAGE_NAME_PATTERN_TEXT)(?:\\s+versionCode:(\\d+)(?:\\s.*)?)?",
    )
    private const val PACKAGE_NAME_PATTERN_TEXT = "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+"

    fun parseEntries(output: String): List<PackageInventoryEntry> = output.lineSequence()
        .map(String::trim)
        .mapNotNull { line ->
            val match = versionedLinePattern.matchEntire(line) ?: return@mapNotNull null
            val packageName = match.groupValues[1].takeIf(packageNamePattern::matches) ?: return@mapNotNull null
            PackageInventoryEntry(packageName, match.groupValues[2].toLongOrNull())
        }
        .toList()
        .distinctBy { it.packageName }

    fun parse(output: String): Set<String> = parseEntries(output).mapTo(linkedSetOf()) { it.packageName }
}

/** Returns null when output is not a package inventory response. */
internal fun parsePackagePresence(output: String, packageName: String): Boolean? {
    val packageNamePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    val lines = output.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toList()
    if (lines.isEmpty()) return false
    val packages = lines.mapNotNull { line ->
        line.removePrefix("package:")
            .takeIf { line.startsWith("package:") && it.matches(packageNamePattern) }
    }
    if (packages.isEmpty()) return null
    return packageName in packages
}

/** `pm uninstall` can return only harmless framework diagnostics with exitCode=0. */
internal fun isUninstallAccepted(response: AdbShellResponse?): Boolean {
    if (response == null || response.exitCode != 0) return false
    val lines = (response.output + "\n" + response.errorOutput)
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toList()
    val hasFailure = lines.any { line ->
        line.startsWith("Failure", ignoreCase = true) ||
            line.contains("INSTALL_FAILED", ignoreCase = true) ||
            line.startsWith("Error", ignoreCase = true)
    }
    // The package-presence postcondition below is authoritative. Some Android
    // 9 PackageManager builds return exitCode=0 with only a warning (or no
    // stdout) after completing an uninstall, so requiring literal "Success"
    // would turn a real uninstall into a false failure.
    return !hasFailure
}

/** `pm install` may complete with only framework diagnostics on Android 9. */
internal fun isInstallAccepted(response: AdbShellResponse?): Boolean {
    if (response == null) return false
    val lines = (response.output + "\n" + response.errorOutput)
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toList()
    val hasFailure = lines.any { line ->
        line.startsWith("Failure", ignoreCase = true) ||
            line.contains("INSTALL_FAILED", ignoreCase = true) ||
            line.startsWith("Error:", ignoreCase = true) ||
            line.startsWith("Error ", ignoreCase = true)
    }
    if (hasFailure) return false
    val hasExplicitSuccess = lines.any { it.equals("Success", ignoreCase = true) }
    // Installed package identity is read back immediately after the batch, so
    // literal stdout "Success" is not required as a second success signal
    // when the wrapper reports exitCode=0. A few Android 9/vendor wrappers
    // emit that same unambiguous marker and still return a non-zero status;
    // accept only that narrow exception, never an arbitrary non-zero warning.
    return response.exitCode == 0 || hasExplicitSuccess
}

/** Android 9 emits local timestamps while newer builds may emit ISO instants. */
internal fun parseAndroidTimestampEpochMillis(value: String): Long? {
    val normalized = value.trim()
    if (normalized.isEmpty()) return null
    runCatching { Instant.parse(normalized).toEpochMilli() }.getOrNull()?.let { return it }
    return runCatching {
        LocalDateTime.parse(normalized, ANDROID_PACKAGE_TIMESTAMP_FORMATTER)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()
}

private val ANDROID_PACKAGE_TIMESTAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
