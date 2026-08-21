package com.tcrrry.helper.data.device

import com.tcrrry.helper.data.artifact.ApkMetadataReader
import com.tcrrry.helper.data.artifact.sha256
import com.tcrrry.helper.domain.artifact.ArtifactManifestValidator
import com.tcrrry.helper.domain.artifact.ManifestValidation
import com.tcrrry.helper.domain.device.AdbCommandGateway
import com.tcrrry.helper.domain.device.AuthorizationActionEvidence
import com.tcrrry.helper.domain.device.AuthorizationDeclarationValidator
import com.tcrrry.helper.domain.device.AuthorizationPlanBuildResult
import com.tcrrry.helper.domain.device.AuthorizationPlanFactory
import com.tcrrry.helper.domain.device.AuthorizationValueState
import com.tcrrry.helper.domain.device.DeviceActionFailure
import com.tcrrry.helper.domain.device.DeviceShortcut
import com.tcrrry.helper.domain.device.DeviceShortcutFailureStage
import com.tcrrry.helper.domain.device.DeviceShortcutResult
import com.tcrrry.helper.domain.device.DeviceAvailabilityEvidence
import com.tcrrry.helper.domain.device.DeviceInstallResult
import com.tcrrry.helper.domain.device.InstallableArtifact
import com.tcrrry.helper.domain.device.InstalledArtifactEvidence
import com.tcrrry.helper.domain.device.ManagedApplicationProbe
import com.tcrrry.helper.domain.device.ManagedApplicationsResult
import com.tcrrry.helper.domain.device.MaintenanceCommandGateway
import com.tcrrry.helper.domain.device.MaintenanceDeviceResult
import android.util.Log
import dadb.AdbShellResponse
import dadb.Dadb
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
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
    override suspend fun install(artifacts: List<InstallableArtifact>): DeviceInstallResult = withLease(
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

        // Validate the complete batch before touching the device. This keeps a
        // bad local artifact from starting a partially authorized session.
        artifacts.forEach { artifact ->
            validateInstallableArtifact(artifact)?.let { failure ->
                return@withLease DeviceInstallResult.Failed(failure)
            }
        }

        // Installation never launches an application. The only launch occurs
        // in the single fixed command after every package has been installed.
        artifacts.forEach { artifact ->
            val remotePath = remoteApkPath(artifact)
                ?: return@withLease DeviceInstallResult.Failed(
                    DeviceActionFailure("remote_staging_path_invalid", artifact.manifest.componentId, retryable = false),
                )
            var failure: DeviceActionFailure? = null
            try {
                adb.push(
                    artifact.apkFile,
                    remotePath,
                    REMOTE_FILE_MODE,
                    artifact.apkFile.lastModified().coerceAtLeast(1L),
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
        val components = AuthorizationPlanFactory.allManagedComponents()
            .filter { it.componentId in selectedComponentIds }
        if (components.map { it.componentId }.toSet() != selectedComponentIds) {
            return@withLease DeviceShortcutResult.Failed(
                stage = DeviceShortcutFailureStage.AUTHORIZATION,
                failure = DeviceActionFailure("shortcut_component_selection_invalid", retryable = false),
            )
        }
        val plan = when (val result = AuthorizationPlanFactory.createForComponents(components)) {
            is AuthorizationPlanBuildResult.Ready -> result.plan
            is AuthorizationPlanBuildResult.Rejected -> return@withLease DeviceShortcutResult.Failed(
                stage = DeviceShortcutFailureStage.AUTHORIZATION,
                failure = DeviceActionFailure(result.reasonCode, retryable = false),
            )
        }
        val response = try {
            adb.shell(CombinedAuthorizationCommand.build(selectedComponentIds))
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
        val result = CombinedAuthorizationResponseParser.parse(response, plan)
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

    override suspend fun repairAuthorization(
        manifests: List<com.tcrrry.helper.domain.artifact.ArtifactManifest>,
    ): MaintenanceDeviceResult = repairAuthorization(manifests, emptyMap())

    override suspend fun repairAuthorization(
        manifests: List<com.tcrrry.helper.domain.artifact.ArtifactManifest>,
        declarationsByComponent: Map<String, com.tcrrry.helper.domain.device.ApkDeclarationMetadata>,
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
            com.tcrrry.helper.domain.device.ManagedComponent(it.componentId, it.packageName)
        }
        val plan = when (val result = AuthorizationPlanFactory.createForComponents(components)) {
            is AuthorizationPlanBuildResult.Ready -> result.plan
            is AuthorizationPlanBuildResult.Rejected -> return@withLease MaintenanceDeviceResult.Failed(
                DeviceActionFailure(result.reasonCode, retryable = false),
            )
        }
        val declarations = linkedMapOf<String, com.tcrrry.helper.domain.device.ApkDeclarationMetadata?>()
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
        val response = try {
            adb.shell(CombinedAuthorizationCommand.build(manifests.map { it.componentId }.toSet(), repairOnly = true))
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
        CombinedAuthorizationResponseParser.parseRepair(response, plan)
    }

    override suspend fun inspectManagedApplications(): ManagedApplicationsResult = withLease(
        whenClosed = ManagedApplicationsResult.Failed(
            DeviceActionFailure("adb_connection_closed", retryable = true),
        ),
    ) {
        val applications = mutableListOf<ManagedApplicationProbe>()
        AuthorizationPlanFactory.allManagedComponents().forEach { component ->
            when (val result = inspectInstalledPackage(component.componentId, component.packageName)) {
                is PackageInspection.Completed -> applications += ManagedApplicationProbe(
                    componentId = component.componentId,
                    packageName = component.packageName,
                    installed = result.installed,
                )

                is PackageInspection.Failed -> return@withLease ManagedApplicationsResult.Failed(result.failure)
            }
        }
        ManagedApplicationsResult.Completed(applications)
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
        if (!isSuccessful(launch)) {
            return@withLease MaintenanceDeviceResult.Failed(
                DeviceActionFailure("maintenance_launch_failed", componentId, retryable = true),
            )
        }
        val process = shell("pidof ${component.packageName}")
        if (!isSuccessful(process) || process?.output?.trim().isNullOrBlank()) {
            return@withLease MaintenanceDeviceResult.Failed(
                DeviceActionFailure("maintenance_process_not_running", componentId, retryable = true),
            )
        }
        MaintenanceDeviceResult.Completed("component_launched")
    }

    private fun validateInstallableArtifact(artifact: InstallableArtifact): DeviceActionFailure? {
        if (ArtifactManifestValidator.validate(artifact.manifest) !is ManifestValidation.Valid) {
            return DeviceActionFailure("install_manifest_invalid", artifact.manifest.componentId, retryable = false)
        }
        if (!artifact.apkFile.isFile || artifact.apkFile.length() != artifact.manifest.apkSizeBytes) {
            return DeviceActionFailure("install_apk_file_invalid", artifact.manifest.componentId, retryable = false)
        }
        val digest = try {
            sha256(artifact.apkFile)
        } catch (_: Exception) {
            return DeviceActionFailure("install_apk_hash_failed", artifact.manifest.componentId, retryable = true)
        }
        if (!digest.equals(artifact.manifest.apkSha256, ignoreCase = true)) {
            return DeviceActionFailure("install_apk_hash_mismatch", artifact.manifest.componentId, retryable = false)
        }
        val metadataReader = installedApkMetadataReader
            ?: return DeviceActionFailure("install_apk_verifier_unavailable", artifact.manifest.componentId, retryable = false)
        val metadata = try {
            metadataReader.read(artifact.apkFile)
        } catch (_: Exception) {
            null
        } ?: return DeviceActionFailure("install_apk_metadata_unreadable", artifact.manifest.componentId, retryable = false)
        if (metadata.packageName != artifact.manifest.packageName) {
            return DeviceActionFailure("install_apk_package_mismatch", artifact.manifest.componentId, retryable = false)
        }
        if (metadata.version != artifact.manifest.apkVersion) {
            return DeviceActionFailure("install_apk_version_mismatch", artifact.manifest.componentId, retryable = false)
        }
        if (metadata.certificateSha256s.none { it.equals(artifact.manifest.certificateSha256, ignoreCase = true) }) {
            return DeviceActionFailure("install_apk_certificate_mismatch", artifact.manifest.componentId, retryable = false)
        }
        if (artifact.declarations != null && metadata.declarations != artifact.declarations) {
            return DeviceActionFailure("install_apk_declarations_mismatch", artifact.manifest.componentId, retryable = false)
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
            val certificate = metadata.certificateSha256s.singleOrNull { candidate ->
                candidate.equals(manifest.certificateSha256, ignoreCase = true)
            }
            if (
                pulledApk.length() != manifest.apkSizeBytes ||
                !digest.equals(manifest.apkSha256, ignoreCase = true) ||
                metadata.packageName != manifest.packageName ||
                metadata.version != manifest.apkVersion ||
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
        manifest: com.tcrrry.helper.domain.artifact.ArtifactManifest,
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
            val digest = sha256(pulledApk)
            val certificateMatches = metadata.certificateSha256s.any { candidate ->
                candidate.equals(manifest.certificateSha256, ignoreCase = true)
            }
            if (
                pulledApk.length() != manifest.apkSizeBytes ||
                !digest.equals(manifest.apkSha256, ignoreCase = true) ||
                metadata.packageName != manifest.packageName ||
                metadata.version != manifest.apkVersion ||
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
        manifest: com.tcrrry.helper.domain.artifact.ArtifactManifest,
    ): DeviceActionFailure? {
        val remoteApkPath = readInstalledApkPath(manifest.packageName)
            ?: return DeviceActionFailure("maintenance_package_path_missing", manifest.componentId, retryable = false)
        val stat = shell("stat -c %s $remoteApkPath")
            ?: return DeviceActionFailure("maintenance_identity_probe_failed", manifest.componentId, retryable = true)
        val size = stat.output.trim().lineSequence().firstOrNull()?.toLongOrNull()
            ?: return DeviceActionFailure("maintenance_identity_probe_invalid", manifest.componentId, retryable = true)
        if (size != manifest.apkSizeBytes) {
            return DeviceActionFailure("maintenance_installed_identity_mismatch", manifest.componentId, retryable = false)
        }
        val digestResponse = shell("sha256sum $remoteApkPath")
            ?: return DeviceActionFailure("maintenance_identity_probe_failed", manifest.componentId, retryable = true)
        val digest = digestResponse.output.trim().substringBefore(' ').takeIf { it.length == 64 }
            ?: return DeviceActionFailure("maintenance_identity_probe_invalid", manifest.componentId, retryable = true)
        if (!digest.equals(manifest.apkSha256, ignoreCase = true)) {
            return DeviceActionFailure("maintenance_installed_identity_mismatch", manifest.componentId, retryable = false)
        }
        val packageInfo = shell("dumpsys package ${manifest.packageName} | grep -m 2 -E 'versionCode=|versionName='")
            ?: return DeviceActionFailure("maintenance_identity_probe_failed", manifest.componentId, retryable = true)
        val lines = packageInfo.output.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val versionCode = lines.firstOrNull { it.startsWith("versionCode=") }
            ?.substringAfter('=')?.substringBefore(' ')?.toLongOrNull()
        val versionName = lines.firstOrNull { it.startsWith("versionName=") }?.substringAfter('=').orEmpty()
        if (versionCode != manifest.apkVersion.code || versionName != manifest.apkVersion.name) {
            return DeviceActionFailure("maintenance_installed_identity_mismatch", manifest.componentId, retryable = false)
        }
        return null
    }

    private fun inspectInstalledPackage(componentId: String, packageName: String): PackageInspection {
        val response = shell("pm path $packageName")
            ?: return PackageInspection.Failed(
                DeviceActionFailure("maintenance_package_check_failed", componentId, retryable = true),
            )
        if (response.exitCode != 0 || response.errorOutput.isNotBlank()) {
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
        return PackageInspection.Completed(installed = true)
    }

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

    private suspend fun <T> withLease(
        whenClosed: T,
        block: () -> T,
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
            val declarations: com.tcrrry.helper.domain.device.ApkDeclarationMetadata,
        ) : MaintenanceInstalledIdentity

        data class Failed(val failure: DeviceActionFailure) : MaintenanceInstalledIdentity
    }

    private sealed interface PackageInspection {
        data class Completed(val installed: Boolean) : PackageInspection

        data class Failed(val failure: DeviceActionFailure) : PackageInspection
    }

    private companion object {
        const val TAG = "03helper-device"
        const val REMOTE_FILE_MODE = 420
        val COMPONENT_ID_PATTERN = Regex("^[a-z][a-z0-9-]{1,63}$")
        val DIGEST_PREFIX_PATTERN = Regex("^[0-9a-f]{16}$")
        val INSTALLED_APK_PATH_PATTERN = Regex("^/data/app/[A-Za-z0-9_./=+\\-]+\\.apk$")
    }
}

internal object CombinedAuthorizationResponseParser {
    fun parse(
        response: AdbShellResponse?,
        plan: com.tcrrry.helper.domain.device.AuthorizationPlan,
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
        if (launch?.getOrNull(2) != AuthorizationPlanFactory.DESKTOP_COMPONENT_ID ||
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
                com.tcrrry.helper.domain.device.ManagedApplicationAvailabilityEvidence(
                    componentId = AuthorizationPlanFactory.DESKTOP_COMPONENT_ID,
                    packageName = AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME,
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
        plan: com.tcrrry.helper.domain.device.AuthorizationPlan,
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
 * The only post-install shell owned by the helper. It is fixed source code,
 * parameterized only by validated component ids, and invoked exactly once.
 */
internal object CombinedAuthorizationCommand {
    const val MARKER = "03HELPER"

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

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private val SCRIPT = """
        set -u
        marker='03HELPER'
        repair_only=0
        if [ "${'$'}{1:-}" = --repair ]; then repair_only=1; shift; fi
        selected() { wanted="${'$'}1"; shift; for value in "${'$'}@"; do [ "${'$'}value" = "${'$'}wanted" ] && return 0; done; return 1; }
        emit() { printf '%s|%s\n' "${'$'}marker" "${'$'}*"; }
        fail() { emit "FAIL|${'$'}1|${'$'}{2:-}"; exit 1; }
        list_contains() { case ":${'$'}1:" in *":${'$'}2:"*) return 0;; *) return 1;; esac; }
        list_preserved() { [ "${'$'}1" = "null" ] || [ -z "${'$'}1" ] || case "${'$'}2" in "${'$'}1"|"${'$'}1":*) return 0;; *) return 1;; esac; }
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
          before_state=COMPONENT_ABSENT; list_contains "${'$'}before_raw" "${'$'}target" && before_state=COMPONENT_PRESENT
          after_raw="${'$'}before_raw"; changed=0
          if [ "${'$'}before_state" = COMPONENT_ABSENT ]; then
            if [ "${'$'}setting" = enabled_notification_listeners ]; then cmd notification allow_listener "${'$'}target" 0 >/dev/null 2>&1 || fail authorization_component_list_write_failed "${'$'}component"; else
              if [ "${'$'}before_raw" = null ] || [ -z "${'$'}before_raw" ]; then next="${'$'}target"; else next="${'$'}before_raw:${'$'}target"; fi
              settings put secure "${'$'}setting" "${'$'}next" >/dev/null 2>&1 || fail authorization_component_list_write_failed "${'$'}component"
            fi
            changed=1; after_raw="${'$'}(secure_list "${'$'}setting")" || fail authorization_component_list_readback_failed "${'$'}component"
          fi
          list_contains "${'$'}after_raw" "${'$'}target" || fail authorization_component_list_not_present "${'$'}component"
          list_preserved "${'$'}before_raw" "${'$'}after_raw" || fail authorization_component_list_not_preserved "${'$'}component"
          emit_auth "${'$'}component" "${'$'}action" "${'$'}before_state" "${'$'}changed" COMPONENT_PRESENT "${'$'}(list_count "${'$'}before_raw")"
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

        skipped=0
        if ! selected desktop "${'$'}@"; then fail desktop_missing desktop; fi
        if ! package_present com.tcrrry.desktop; then fail desktop_missing desktop; fi
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

        [ "${'$'}skipped" -eq 0 ] || fail selected_component_missing
        if [ "${'$'}repair_only" -eq 1 ]; then emit "DONE|OK"; exit 0; fi
        launch_component='${AuthorizationPlanFactory.DESKTOP_MAIN_ACTIVITY}'
        am start -n "${'$'}launch_component" >/dev/null 2>&1 || fail desktop_launch_failed desktop
        process_state=STOPPED; attempt=1
        while [ "${'$'}attempt" -le 13 ]; do if pidof com.tcrrry.desktop >/dev/null 2>&1; then process_state=RUNNING; break; fi; [ "${'$'}attempt" -lt 13 ] && sleep 0.25; attempt=${'$'}((attempt + 1)); done
        [ "${'$'}process_state" = RUNNING ] || fail desktop_process_not_running desktop
        wait_for_service_bound "com.tcrrry.desktop/.debug.NavigationDemoAccessibilityService" "com.tcrrry.desktop/com.tcrrry.desktop.debug.NavigationDemoAccessibilityService" && service_state=BOUND || service_state=UNBOUND
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
