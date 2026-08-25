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
import com.ninepointnine.helper.domain.device.DeviceShortcut
import com.ninepointnine.helper.domain.device.DeviceShortcutFailureStage
import com.ninepointnine.helper.domain.device.DeviceShortcutResult
import com.ninepointnine.helper.domain.device.DeviceAvailabilityEvidence
import com.ninepointnine.helper.domain.device.DeviceInstallResult
import com.ninepointnine.helper.domain.device.InstallableArtifact
import com.ninepointnine.helper.domain.device.InstalledArtifactEvidence
import com.ninepointnine.helper.domain.device.ManagedApplicationProbe
import com.ninepointnine.helper.domain.device.ManagedApplicationsResult
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * DADB-backed device port. The install batch is separate from the single
 * post-install command: no package is launched during [install], and all
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

    override suspend fun install(artifacts: List<InstallableArtifact>): DeviceInstallResult =
        install(artifacts, InstallationStrategy.REINSTALL_SELECTED)

    override suspend fun install(
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

        // Validate every artifact that may be written. An already-installed
        // package does not need a local APK at all: its live APK is pulled and
        // verified below, so a stale or absent local cache cannot block a
        // missing-only batch.
        artifacts.forEach { artifact ->
            val packagePresent = artifact.manifest.packageName in installedInventory
            // The domain session only marks an application reusable when the
            // live inventory contains the exact catalog version. Keep that
            // invariant at the device boundary too: an older (or unknown)
            // version must receive the verified APK instead of being silently
            // treated as a no-op.
            val alreadyInstalled = packagePresent &&
                installedInventory[artifact.manifest.packageName] == artifact.manifest.apkVersion.code
            if (!alreadyInstalled || strategy == InstallationStrategy.REINSTALL_SELECTED) {
                validateInstallableArtifact(artifact)?.let { failure ->
                    return@withLease DeviceInstallResult.Failed(failure)
                }
            } else if (ArtifactManifestValidator.validate(artifact.manifest) !is ManifestValidation.Valid) {
                return@withLease DeviceInstallResult.Failed(
                    DeviceActionFailure("install_manifest_invalid", artifact.manifest.componentId, retryable = false),
                )
            }
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

        // Installation never launches an application. The only launch occurs
        // during the versioned authorization plan after every package has been installed.
        artifacts.forEach { artifact ->
            if (strategy == InstallationStrategy.INSTALL_MISSING_ONLY &&
                installedInventory[artifact.manifest.packageName] == artifact.manifest.apkVersion.code
            ) {
                // The package is already present. The identity readback below
                // remains mandatory; presence alone is never accepted as proof.
                return@forEach
            }
            val remotePath = remoteApkPath(artifact)
                ?: return@withLease DeviceInstallResult.Failed(
                    DeviceActionFailure("remote_staging_path_invalid", artifact.manifest.componentId, retryable = false),
                )
            val apkFile = artifact.apkFile
                ?: return@withLease DeviceInstallResult.Failed(
                    DeviceActionFailure("install_apk_file_invalid", artifact.manifest.componentId, retryable = false),
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
                if (!isSuccessful(installResponse) || !installResponse.output.trim().startsWith("Success")) {
                    failure = DeviceActionFailure("adb_pm_install_failed", artifact.manifest.componentId, retryable = true)
                }
            } catch (_: IOException) {
                failure = DeviceActionFailure("adb_install_transport_failed", artifact.manifest.componentId, retryable = true)
            } catch (_: Exception) {
                failure = DeviceActionFailure("adb_install_failed", artifact.manifest.componentId, retryable = true)
            } finally {
                if (!deleteRemoteStagingFile(remotePath)) {
                    failure = DeviceActionFailure(
                        "adb_remote_staging_cleanup_failed",
                        artifact.manifest.componentId,
                        retryable = true,
                    )
                }
            }
            if (failure != null) return@withLease DeviceInstallResult.Failed(checkNotNull(failure))
        }

        // Read back every installed APK only after the entire install batch is
        // complete. Nothing is authorized or launched before this succeeds.
        val installed = mutableListOf<InstalledArtifactEvidence>()
        artifacts.forEach { artifact ->
            when (val identity = verifyInstalledArtifactIdentity(artifact, metadataReader, verificationDirectory)) {
                is InstalledArtifactIdentityResult.Verified -> installed += identity.evidence
                is InstalledArtifactIdentityResult.Failed ->
                    return@withLease DeviceInstallResult.Failed(identity.failure)
            }
        }
        DeviceInstallResult.Installed(installed)
    }

    override suspend fun runShortcut(
        shortcut: DeviceShortcut,
        selectedComponentIds: Set<String>,
    ): DeviceShortcutResult {
        val components = AuthorizationPlanFactory.allManagedComponents()
            .filter { it.componentId in selectedComponentIds }
        val plan = when (val result = AuthorizationPlanFactory.createForComponents(components)) {
            is AuthorizationPlanBuildResult.Ready -> result.plan
            is AuthorizationPlanBuildResult.Rejected -> return DeviceShortcutResult.Failed(
                stage = DeviceShortcutFailureStage.AUTHORIZATION,
                failure = DeviceActionFailure(result.reasonCode, retryable = false),
            )
        }
        return runShortcutWithPlan(shortcut, selectedComponentIds, plan)
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
        if (shortcut != DeviceShortcut.CONFIGURE_ALL_INSTALLED_APPS_AND_START_DESKTOP) {
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
            adb.shell(CombinedAuthorizationCommand.build(authorizationPlan))
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
        val result = CombinedAuthorizationResponseParser.parse(response, authorizationPlan)
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
    ): MaintenanceDeviceResult = repairAuthorization(manifests, emptyMap())

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
        val plan = when (val result = AuthorizationPlanFactory.createForComponents(components)) {
            is AuthorizationPlanBuildResult.Ready -> result.plan
            is AuthorizationPlanBuildResult.Rejected -> return@withLease MaintenanceDeviceResult.Failed(
                DeviceActionFailure(result.reasonCode, retryable = false),
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
        AuthorizationDeclarationValidator.validateDeclarations(plan, declarations)?.let { failure ->
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
        CombinedAuthorizationResponseParser.parseRepair(response, plan)
    }

    override suspend fun inspectManagedApplications(): ManagedApplicationsResult =
        inspectManagedApplications(AuthorizationPlanFactory.allManagedComponents())

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

    override suspend fun inspectAuthorization(
        manifests: List<com.ninepointnine.helper.domain.artifact.ArtifactManifest>,
    ): MaintenanceAuthorizationResult = withLease(
        whenClosed = MaintenanceAuthorizationResult.Failed(
            DeviceActionFailure("adb_connection_closed", retryable = true),
        ),
    ) {
        if (manifests.isEmpty() || manifests.map { it.componentId }.toSet().size != manifests.size) {
            return@withLease MaintenanceAuthorizationResult.Failed(
                DeviceActionFailure("maintenance_manifest_selection_mismatch", retryable = false),
            )
        }
        inspectComponentAuthorizationLocked(manifests.map {
            ManagedComponent(
                componentId = it.componentId,
                packageName = it.packageName,
                setup = it.deviceSetup,
                order = it.sortOrder,
            )
        })
    }

    override suspend fun inspectComponentAuthorization(
        components: List<ManagedComponent>,
    ): MaintenanceAuthorizationResult = withLease(
        whenClosed = MaintenanceAuthorizationResult.Failed(
            DeviceActionFailure("adb_connection_closed", retryable = true),
        ),
    ) {
        inspectComponentAuthorizationLocked(components)
    }

    override suspend fun inspectComponentAuthorization(
        components: List<ManagedComponent>,
        installedApplications: List<ManagedApplicationProbe>,
    ): MaintenanceAuthorizationResult = withLease(
        whenClosed = MaintenanceAuthorizationResult.Failed(
            DeviceActionFailure("adb_connection_closed", retryable = true),
        ),
    ) {
        inspectComponentAuthorizationLocked(components, installedApplications)
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
            val plan = when (val result = AuthorizationPlanFactory.createForInspection(listOf(component))) {
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
            val failedProbe = probes.firstOrNull { it.value == null }
            val unmet = probes.firstOrNull { it.value == false }
            when {
                failedProbe != null -> ManagedApplicationAuthorizationStatus(
                    componentId = component.componentId,
                    packageName = installed.packageName,
                    authorized = null,
                    state = MaintenanceAuthorizationState.ERROR,
                    reasonCode = failedProbe.reasonCode,
                )

                unmet != null -> ManagedApplicationAuthorizationStatus(
                    componentId = component.componentId,
                    packageName = installed.packageName,
                    authorized = false,
                    state = MaintenanceAuthorizationState.NOT_AUTHORIZED,
                    reasonCode = unmet.reasonCode,
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

    private fun readAuthorizationAction(action: AuthorizationAction): AuthorizationProbe = when (action) {
        is AuthorizationAction.EnsureAppOpAllowed -> {
            val response = shell("appops get ${shellArgument(action.packageName)} ${shellArgument(action.operation.wireName)}")
            val state = response
                ?.takeIf { it.exitCode == 0 }
                ?.let { AppOpsResponseParser.parse(it.output, action.operation.wireName) }
            when (state) {
                AuthorizationValueState.ALLOWED -> AuthorizationProbe(true, null)
                null -> AuthorizationProbe(null, "authorization_appop_read_failed")
                else -> AuthorizationProbe(false, "authorization_appop_not_allowed")
            }
        }

        is AuthorizationAction.EnsureRuntimePermissionGranted -> {
            val response = shell("dumpsys package ${shellArgument(action.packageName)}")
            if (response == null || response.exitCode != 0) {
                AuthorizationProbe(null, "authorization_runtime_permission_read_failed")
            } else {
                val granted = Regex(
                    "(?m)^\\s*${Regex.escape(action.permission.wireName)}:\\s*granted=(true|false)",
                ).find(response.output)?.groupValues?.getOrNull(1)?.toBooleanStrictOrNull()
                when (granted) {
                    true -> AuthorizationProbe(true, null)
                    false -> AuthorizationProbe(false, "authorization_runtime_permission_not_granted")
                    null -> AuthorizationProbe(null, "authorization_runtime_permission_read_failed")
                }
            }
        }

        is AuthorizationAction.EnsureSecureSettingEnabled -> {
            val response = shell("settings get secure ${shellArgument(action.setting.wireName)}")
            when {
                response == null || response.exitCode != 0 ->
                    AuthorizationProbe(null, "authorization_secure_setting_read_failed")
                response.output.trim() == "1" -> AuthorizationProbe(true, null)
                response.output.trim() == "0" -> AuthorizationProbe(false, "authorization_secure_setting_disabled")
                else -> AuthorizationProbe(null, "authorization_secure_setting_read_failed")
            }
        }

        is AuthorizationAction.AppendSecureComponent -> {
            val response = shell("settings get secure ${shellArgument(action.setting.wireName)}")
            if (response == null || response.exitCode != 0) {
                AuthorizationProbe(null, "authorization_component_list_read_failed")
            } else {
                val present = response.output.trim()
                    .takeUnless { it.isBlank() || it == "null" }
                    ?.split(':')
                    ?.any { it == action.targetComponent }
                    ?: false
                if (present) {
                    AuthorizationProbe(true, null)
                } else {
                    AuthorizationProbe(false, "authorization_component_not_present")
                }
            }
        }
    }

    private data class AuthorizationProbe(
        val value: Boolean?,
        val reasonCode: String?,
    )

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
                if (launchComponent == null) {
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

    override suspend fun launchManagedComponent(componentId: String): MaintenanceDeviceResult = withLease(
        whenClosed = MaintenanceDeviceResult.Failed(
            DeviceActionFailure("adb_connection_closed", retryable = true),
        ),
    ) {
        val component = AuthorizationPlanFactory.allManagedComponents().firstOrNull {
            it.componentId == componentId
        } ?: return@withLease MaintenanceDeviceResult.Failed(
            DeviceActionFailure("maintenance_component_unapproved", componentId, retryable = false),
        )
        val launchComponent = AuthorizationPlanFactory.fixedLaunchComponent(component)
            ?: return@withLease MaintenanceDeviceResult.Failed(
                DeviceActionFailure("maintenance_launch_unavailable", componentId, retryable = false),
            )
        when (val installed = inspectInstalledPackage(component.componentId, component.packageName)) {
            is PackageInspection.Failed -> return@withLease MaintenanceDeviceResult.Failed(installed.failure)
            is PackageInspection.Completed -> if (!installed.installed) {
                return@withLease MaintenanceDeviceResult.Failed(
                    DeviceActionFailure("maintenance_component_not_installed", componentId, retryable = false),
                )
            }
        }
        val launch = shell("am start -n $launchComponent")
        waitForProcess(component.packageName)
        if (!isLaunchAccepted(launch)) {
            return@withLease MaintenanceDeviceResult.Failed(
                DeviceActionFailure("maintenance_launch_failed", componentId, retryable = true),
            )
        }
        MaintenanceDeviceResult.Completed("component_launched")
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
        return null
    }

    private fun verifyInstalledArtifactIdentity(
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
            if (
                metadata.packageName != manifest.packageName ||
                certificate == null
            ) {
                InstalledArtifactIdentityResult.Failed(
                    DeviceActionFailure(
                        "installation_installed_identity_mismatch",
                        manifest.componentId,
                        retryable = false,
                    ),
                )
            } else {
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
            if (
                metadata.packageName != manifest.packageName ||
                !certificateMatches
            ) {
                MaintenanceInstalledIdentity.Failed(
                    DeviceActionFailure(
                        "maintenance_installed_identity_mismatch",
                        manifest.componentId,
                        retryable = false,
                    ),
                )
            } else {
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
        val paths = response.output.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .toList()
        if (paths.isEmpty()) return PackageInspection.Completed(installed = false)
        if (paths.size != 1 || !INSTALLED_APK_PATH_PATTERN.matches(paths.single())) {
            return PackageInspection.Failed(
                DeviceActionFailure("maintenance_package_identity_invalid", componentId, retryable = false),
            )
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
        val firstInstall = Regex("firstInstallTime=([^\\s]+)").find(output)?.groupValues?.getOrNull(1)
            ?.let(::parseEpochMillis)
        val lastUpdate = Regex("lastUpdateTime=([^\\s]+)").find(output)?.groupValues?.getOrNull(1)
            ?.let(::parseEpochMillis)
        val filePath = paths.single()
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
        val hasPath = path.output.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .any { it.isNotBlank() }
        return if (hasPath) PackagePresence.PRESENT else if (path.output.isBlank()) {
            PackagePresence.ABSENT
        } else {
            PackagePresence.UNKNOWN
        }
    }

    private enum class PackagePresence {
        PRESENT,
        ABSENT,
        UNKNOWN,
    }

    private fun parseEpochMillis(value: String): Long? = runCatching {
        java.time.Instant.parse(value).toEpochMilli()
    }.getOrNull()

    private fun remoteApkPath(artifact: InstallableArtifact): String? {
        val componentId = artifact.manifest.componentId
        val digestPrefix = artifact.manifest.apkSha256.lowercase().take(16)
        if (!COMPONENT_ID_PATTERN.matches(componentId) || !DIGEST_PREFIX_PATTERN.matches(digestPrefix)) return null
        return "/data/local/tmp/03helper-$componentId-$digestPrefix.apk"
    }

    private fun readInstalledApkPath(packageName: String): String? {
        val response = shell("pm path $packageName") ?: return null
        if (!isSuccessful(response)) return null
        val paths = response.output.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .toList()
        if (paths.size != 1) return null
        return paths.single().takeIf { INSTALLED_APK_PATH_PATTERN.matches(it) }
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

    private fun isSuccessful(response: AdbShellResponse?): Boolean =
        response != null && response.exitCode == 0 && response.errorOutput.isBlank()

    private fun isLaunchAccepted(response: AdbShellResponse?): Boolean {
        if (!isSuccessful(response)) return false
        return response?.output.orEmpty().lines().none { line ->
            line.contains("Error", ignoreCase = true) ||
                line.contains("Exception", ignoreCase = true) ||
                line.contains("Unable", ignoreCase = true)
        }
    }

    private suspend fun waitForProcess(packageName: String): Boolean {
        repeat(10) {
            val process = shell("pidof ${shellArgument(packageName)}")
            if (isSuccessful(process) && !process?.output?.trim().isNullOrBlank()) return true
            delay(150L)
        }
        return false
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
        val INSTALLED_APK_PATH_PATTERN = Regex("^/data/app/[A-Za-z0-9_./=+\\-]+\\.apk$")
    }
}

internal object CombinedAuthorizationResponseParser {
    fun parse(
        response: AdbShellResponse?,
        plan: com.ninepointnine.helper.domain.device.AuthorizationPlan,
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
        // The Android shell may write harmless framework warnings to stderr while
        // still returning structured markers; exit code and markers are authoritative.
        if (failure != null || response.exitCode != 0) {
            val parts = failure?.split('|').orEmpty()
            val reasonCode = parts.getOrNull(2).takeUnless { it.isNullOrBlank() } ?: "adb_combined_command_failed"
            val componentId = parts.getOrNull(3).takeUnless { it.isNullOrBlank() }
            return DeviceShortcutResult.Failed(
                stage = if (reasonCode.startsWith("desktop_")) {
                    DeviceShortcutFailureStage.VERIFICATION
                } else {
                    DeviceShortcutFailureStage.AUTHORIZATION
                },
                failure = DeviceActionFailure(
                    reasonCode = reasonCode,
                    componentId = componentId,
                    retryable = reasonCode !in setOf("desktop_missing", "selected_component_missing"),
                ),
            )
        }
        if (lines.none { it == "${CombinedAuthorizationCommand.MARKER}|DONE|OK" }) {
            return DeviceShortcutResult.Failed(
                stage = DeviceShortcutFailureStage.AUTHORIZATION,
                failure = DeviceActionFailure("combined_command_result_missing", retryable = false),
            )
        }

        val authorizationEvidence = lines.mapNotNull(::parseAuthorizationEvidence)
        if (!AuthorizationPlanFactory.validateEvidence(plan, authorizationEvidence)) {
            return DeviceShortcutResult.Failed(
                stage = DeviceShortcutFailureStage.AUTHORIZATION,
                failure = DeviceActionFailure("authorization_evidence_invalid", retryable = false),
            )
        }
        val launch = lines.firstOrNull { it.startsWith("${CombinedAuthorizationCommand.MARKER}|LAUNCH|") }
            ?.split('|')
        val desktop = plan.components.firstOrNull { it.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID }
            ?: return DeviceShortcutResult.Failed(
                stage = DeviceShortcutFailureStage.VERIFICATION,
                failure = DeviceActionFailure("desktop_missing", retryable = false),
            )
        if (launch?.getOrNull(2) != desktop.componentId ||
            launch.getOrNull(3) != "OK"
        ) {
            return DeviceShortcutResult.Failed(
                stage = DeviceShortcutFailureStage.VERIFICATION,
                failure = DeviceActionFailure("desktop_launch_evidence_invalid", retryable = false),
            )
        }
        val selectedIds = plan.components.map { it.componentId }.toSet()
        val skippedIds = lines.mapNotNull { line ->
            line.split('|').takeIf { it.size >= 3 && it[1] == "SKIP" }?.get(2)
        }.toSet()
        return DeviceShortcutResult.Completed(
            configuredComponentIds = selectedIds - skippedIds,
            skippedComponentIds = skippedIds,
            authorizationEvidence = authorizationEvidence,
            availabilityEvidence = listOf(
                com.ninepointnine.helper.domain.device.ManagedApplicationAvailabilityEvidence(
                    componentId = AuthorizationPlanFactory.DESKTOP_COMPONENT_ID,
                    packageName = desktop.packageName,
                    launchAttempted = true,
                    launcherResolved = true,
                    processRunning = launch.getOrNull(4) == "RUNNING",
                    requiredServiceBound = launch.getOrNull(5) == "BOUND",
                ),
            ),
        )
    }

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
        if (failure != null || response.exitCode != 0) {
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
        if (lines.none { it == "${CombinedAuthorizationCommand.MARKER}|DONE|OK" }) {
            return MaintenanceDeviceResult.Failed(
                DeviceActionFailure("combined_command_result_missing", retryable = false),
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
    ): String {
        require(AuthorizationPlanFactory.validate(plan)) { "invalid_authorization_plan" }
        val dynamic = plan.components.any { component ->
            component.setup != null || component.componentId !in setOf(
                AuthorizationPlanFactory.DESKTOP_COMPONENT_ID,
                AuthorizationPlanFactory.LYRICS_COMPONENT_ID,
                AuthorizationPlanFactory.FILE_MANAGER_COMPONENT_ID,
            ) || (component.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID &&
                component.packageName != AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME)
        }
        val desktopPackage = plan.components
            .first { it.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID }
            .packageName
        val arguments = buildList {
            if (repairOnly) add("--repair")
            if (dynamic) add("--dynamic")
            if (dynamic) add("--desktop-package=$desktopPackage")
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

    private val SCRIPT = """
        set -u
        marker='03HELPER'
        repair_only=0
        if [ "${'$'}{1:-}" = --repair ]; then repair_only=1; shift; fi
        dynamic_mode=0
        if [ "${'$'}{1:-}" = --dynamic ]; then dynamic_mode=1; shift; fi
        desktop_package='${AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME}'
        case "${'$'}{1:-}" in
          --desktop-package=*) desktop_package="${'$'}{1#--desktop-package=}"; shift;;
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
        # APK declarations are validated locally before this command is sent.
        # The car only needs to report the runtime binding, and Android 9 builds
        # do not consistently implement the package-service query command.
        service_bound() {
          primary="${'$'}1"; alternate="${'$'}{2:-}"
          # Android 9's vendor text parser can segfault on this bounded state
          # scan. Keep the parser in POSIX shell so the probe cannot turn a
          # bound service into a false failure on the target device.
          dumpsys activity services 2>/dev/null | {
            in_target=0
            remaining=0
            while IFS= read -r line; do
              case "${'$'}line" in
                *"${'$'}primary"*) in_target=1; remaining=80; continue ;;
              esac
              if [ "${'$'}in_target" -eq 0 ] && [ -n "${'$'}alternate" ]; then
                case "${'$'}line" in
                  *"${'$'}alternate"*) in_target=1; remaining=80; continue ;;
                esac
              fi
              if [ "${'$'}in_target" -eq 1 ]; then
                case "${'$'}line" in
                  *"ServiceRecord{"*) in_target=0 ;;
                esac
                case "${'$'}line" in
                  *"requested=true"*"received=true"*"hasBound=true"*) exit 0 ;;
                esac
                remaining=${'$'}((remaining - 1))
                if [ "${'$'}remaining" -le 0 ]; then in_target=0; fi
              fi
            done
            exit 1
          }
        }
        wait_for_service_bound() {
          primary="${'$'}1"; alternate="${'$'}{2:-}"
          attempt=1
          while [ "${'$'}attempt" -le 10 ]; do
            service_bound "${'$'}primary" "${'$'}alternate" && return 0
            [ "${'$'}attempt" -lt 10 ] && sleep 1
            attempt=${'$'}((attempt + 1))
          done
          return 1
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
          old_ifs="${'$'}IFS"; IFS='|'; set -- ${'$'}spec; IFS="${'$'}old_ifs"
          type="${'$'}{1:-}"; component="${'$'}{2:-}"; action="${'$'}{3:-}"
          case "${'$'}type" in
            APP_OP)
              package="${'$'}{4:-}"; operation="${'$'}{5:-}"
              ensure_appop "${'$'}package" "${'$'}operation" "${'$'}component" "${'$'}action";;
            RUNTIME)
              package="${'$'}{4:-}"; permission="${'$'}{5:-}"
              ensure_runtime_permission "${'$'}package" "${'$'}permission" "${'$'}component" "${'$'}action";;
            SECURE_FLAG)
              setting="${'$'}{4:-}"
              ensure_secure_flag "${'$'}setting" "${'$'}component" "${'$'}action";;
            SECURE_COMPONENT)
              setting="${'$'}{4:-}"; target="${'$'}{5:-}"
              append_component "${'$'}setting" "${'$'}target" "${'$'}component" "${'$'}action";;
            *) fail authorization_action_invalid "${'$'}component";;
          esac
        }

        skipped=0
        if ! selected desktop "${'$'}@"; then fail desktop_missing desktop; fi
        if ! package_present "${'$'}desktop_package"; then fail desktop_missing desktop; fi
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
          for argument in "${'$'}@"; do
            case "${'$'}argument" in
              --action=*) apply_dynamic_action "${'$'}{argument#--action=}";;
            esac
          done
        fi

        [ "${'$'}skipped" -eq 0 ] || fail selected_component_missing
        if [ "${'$'}repair_only" -eq 1 ]; then emit "DONE|OK"; exit 0; fi
        if [ "${'$'}dynamic_mode" -eq 0 ]; then
          launch_component='${AuthorizationPlanFactory.DESKTOP_MAIN_ACTIVITY}'
          am start -n "${'$'}launch_component" >/dev/null 2>&1 || fail desktop_launch_failed desktop
          process_state=STOPPED; attempt=1
          while [ "${'$'}attempt" -le 13 ]; do if pidof com.tcrrry.desktop >/dev/null 2>&1; then process_state=RUNNING; break; fi; [ "${'$'}attempt" -lt 13 ] && sleep 0.25; attempt=${'$'}((attempt + 1)); done
          [ "${'$'}process_state" = RUNNING ] || fail desktop_process_not_running desktop
          wait_for_service_bound "com.tcrrry.desktop/.debug.NavigationDemoAccessibilityService" "com.tcrrry.desktop/com.tcrrry.desktop.debug.NavigationDemoAccessibilityService" && service_state=BOUND || service_state=UNBOUND
        else
          launch_component="${'$'}desktop_package/.MainActivity"
          am start -n "${'$'}launch_component" >/dev/null 2>&1 || fail desktop_launch_failed desktop
          process_state=STOPPED; attempt=1
          while [ "${'$'}attempt" -le 13 ]; do if pidof "${'$'}desktop_package" >/dev/null 2>&1; then process_state=RUNNING; break; fi; [ "${'$'}attempt" -lt 13 ] && sleep 0.25; attempt=${'$'}((attempt + 1)); done
          [ "${'$'}process_state" = RUNNING ] || fail desktop_process_not_running desktop
          wait_for_service_bound "${'$'}desktop_package/.debug.NavigationDemoAccessibilityService" "${'$'}desktop_package/${'$'}desktop_package.debug.NavigationDemoAccessibilityService" && service_state=BOUND || service_state=UNBOUND
        fi
        [ "${'$'}service_state" = BOUND ] || fail desktop_service_not_bound desktop
        emit "LAUNCH|desktop|OK|${'$'}process_state|${'$'}service_state"
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
