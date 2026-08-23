package com.tcrrry.helper.data.catalog

import com.tcrrry.helper.data.artifact.ApkMetadataReader
import com.tcrrry.helper.data.download.DynamicArtifactDownloader
import com.tcrrry.helper.data.download.DynamicArchiveDownloadResult
import com.tcrrry.helper.data.download.DynamicDownloadProgress
import com.tcrrry.helper.data.download.ArtifactCache
import com.tcrrry.helper.data.web.LanzouFolderArtifact
import com.tcrrry.helper.data.web.LanzouFolderResolutionResult
import com.tcrrry.helper.data.web.LanzouFolderSourceAdapter
import com.tcrrry.helper.data.web.LanzouFolderAppFailure
import com.tcrrry.helper.data.web.LanzouResolutionResult
import com.tcrrry.helper.data.web.LanzouWebSourceAdapter
import com.tcrrry.helper.domain.artifact.ArtifactManifest
import com.tcrrry.helper.domain.artifact.ArtifactManifestValidator
import com.tcrrry.helper.domain.artifact.ArtifactSourceKind
import com.tcrrry.helper.domain.artifact.ArtifactVersion
import com.tcrrry.helper.domain.artifact.CompatibilityRange
import com.tcrrry.helper.domain.artifact.ManifestValidation
import com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy
import com.tcrrry.helper.domain.artifact.InstallerComponentTrustRegistry
import com.tcrrry.helper.domain.artifact.InstallerPublisherTrustRegistry
import com.tcrrry.helper.domain.device.AuthorizationPlanFactory
import com.tcrrry.helper.domain.device.ManagedComponent
import com.tcrrry.helper.domain.session.ComponentProgressStatus
import com.tcrrry.helper.domain.session.InstallPhase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class CatalogPreparationProgress(
    val componentId: String,
    val phase: InstallPhase,
    val status: ComponentProgressStatus,
    val bytesWritten: Long = 0L,
    val totalBytes: Long = 0L,
    val indeterminate: Boolean = true,
)

/**
 * Builds the signed-catalog equivalent from the configured Lanzou folder.
 *
 * The control plane owns the folder and expected identities. The package
 * version, archive digest and APK digest are learned from the current folder
 * contents on every load, so replacing a ZIP is enough to publish an update.
 */
class FolderArtifactCatalogAdapter(
    private val configAdapter: CloudInstallerDistributionConfigAdapter,
    private val folderSourceAdapter: LanzouFolderSourceAdapter,
    private val lanzouSourceAdapter: LanzouWebSourceAdapter,
    private val downloader: DynamicArtifactDownloader,
    private val metadataReader: ApkMetadataReader,
    private val sourcePolicy: ReleaseSourcePolicy,
    private val artifactCache: ArtifactCache,
    private val workingDirectory: File,
    private val now: () -> Instant = Instant::now,
) {
    private var selectionContext: SelectionContext? = null
    private var lastConfigFailure: CatalogLoadResult.Failure =
        CatalogLoadResult.Failure("distribution_config_unavailable", retryable = true)

    private suspend fun loadConfig(): InstallerDistributionConfig? {
        return when (val result = configAdapter.load()) {
            is DistributionConfigLoadResult.Success -> {
                if (!result.config.expiresAt.isAfter(now())) {
                    lastConfigFailure = CatalogLoadResult.Failure("distribution_config_expired", retryable = true)
                    null
                } else {
                    result.config
                }
            }

            is DistributionConfigLoadResult.Failure -> {
                lastConfigFailure = CatalogLoadResult.Failure(result.reasonCode, result.retryable)
                null
            }
        }
    }

    /** Reads only the signed config and one Lanzou root listing. No ZIP is downloaded here. */
    suspend fun loadSelection(): CatalogLoadResult {
        val config = loadConfig() ?: return lastConfigFailure
        val folder = when (val result = folderSourceAdapter.resolve(config)) {
            is LanzouFolderResolutionResult.Failure -> {
                selectionContext = null
                return CatalogLoadResult.Failure(result.failure.reasonCode, result.failure.retryable)
            }

            is LanzouFolderResolutionResult.Success -> result
        }
        val context = SelectionContext(config, folder)
        selectionContext = context
        val listedAppsById = folder.artifacts.associateBy { it.component.componentId }
        return CatalogLoadResult.Success(
            TrustedArtifactCatalog(
                catalogVersion = config.effectiveCatalogVersion(),
                keyId = config.keyId,
                signatureAlgorithm = config.signatureAlgorithm,
                manifests = emptyList(),
                apps = config.declaredApps()
                    .filter { it.enabled }
                    .map { app ->
                        (listedAppsById[app.componentId]?.component ?: app).let { listed ->
                            // The signed catalog version is the only release
                            // label available before an APK is downloaded. It
                            // keeps the selection row informative; the later
                            // APK-derived version always replaces it.
                            listed.copy(versionLabel = listed.versionLabel ?: config.effectiveCatalogVersion())
                        }
                    },
                appFailures = folder.appFailures.map {
                    CatalogAppFailure(it.componentId, it.reasonCode, it.retryable)
                },
                catalogRevision = config.catalogRevision,
            ),
        )
    }

    /** Builds manifests only for the applications the user confirmed. */
    suspend fun prepareSelected(
        selectedIds: Set<String>,
        onProgress: (CatalogPreparationProgress) -> Unit = {},
    ): CatalogLoadResult {
        val context = selectionContext ?: when (val result = loadSelection()) {
            is CatalogLoadResult.Success -> selectionContext
            is CatalogLoadResult.Failure -> return result
        }
        ?: return CatalogLoadResult.Failure("distribution_selection_context_missing", retryable = true)
        if (!context.config.expiresAt.isAfter(now())) {
            selectionContext = null
            return CatalogLoadResult.Failure("distribution_config_expired", retryable = true)
        }
        if (!selectedIds.contains(DESKTOP_APP_ID)) {
            return CatalogLoadResult.Failure("distribution_desktop_unavailable", retryable = false)
        }
        if (!workingDirectory.mkdirs() && !workingDirectory.isDirectory) {
            return CatalogLoadResult.Failure("distribution_catalog_cache_unavailable", retryable = true)
        }
        clearWorkingDirectory()
        val stagedManifests = mutableListOf<ArtifactManifest>()
        var committed = false
        return try {
            val appFailures = context.folder.appFailures
                .map { CatalogAppFailure(it.componentId, it.reasonCode, it.retryable) }
                .toMutableList()
            val selectedArtifacts = context.folder.artifacts.filter {
                it.component.componentId in selectedIds
            }
            val results = coroutineScope {
                selectedArtifacts.chunked(MAX_PREPARATION_CONCURRENCY).flatMap { batch ->
                    batch.map { artifact ->
                        async(Dispatchers.IO) {
                            artifact to prepareSelectedArtifact(context.config, artifact, onProgress)
                        }
                    }.awaitAll()
                }
            }
            val manifests = results.mapNotNull { (artifact, result) ->
                val componentId = artifact.component.componentId
                when (result) {
                    is ManifestBuildResult.Success -> {
                        stagedManifests += result.manifest
                        result.manifest
                    }

                    is ManifestBuildResult.Failure -> {
                        if (componentId == DESKTOP_APP_ID) {
                            return CatalogLoadResult.Failure(result.reasonCode, result.retryable)
                        }
                        appFailures += CatalogAppFailure(componentId, result.reasonCode, result.retryable)
                        null
                    }
                }
            }
            if (manifests.none { it.componentId == DESKTOP_APP_ID }) {
                return CatalogLoadResult.Failure("distribution_desktop_unavailable", retryable = false)
            }
            when (val validation = ArtifactManifestValidator.validateCatalog(manifests)) {
                is ManifestValidation.Invalid -> CatalogLoadResult.Failure(validation.reasonCode, retryable = false)
                ManifestValidation.Valid -> {
                    committed = true
                    CatalogLoadResult.Success(
                        TrustedArtifactCatalog(
                            catalogVersion = context.config.effectiveCatalogVersion(),
                            keyId = context.config.keyId,
                            signatureAlgorithm = context.config.signatureAlgorithm,
                            manifests = manifests,
                            apps = context.config.declaredApps().filter { it.enabled },
                            appFailures = appFailures,
                            catalogRevision = context.config.catalogRevision,
                        ),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CatalogLoadResult.Failure("distribution_catalog_build_failed", retryable = true)
        } finally {
            if (!committed) stagedManifests.forEach(artifactCache::clearArtifact)
            clearWorkingDirectory()
        }
    }

    suspend fun load(): CatalogLoadResult {
        val config = loadConfig() ?: return lastConfigFailure
        if (!config.expiresAt.isAfter(now())) {
            return CatalogLoadResult.Failure("distribution_config_expired", retryable = true)
        }
        if (!workingDirectory.mkdirs() && !workingDirectory.isDirectory) {
            return CatalogLoadResult.Failure("distribution_catalog_cache_unavailable", retryable = true)
        }
        clearWorkingDirectory()
        val stagedManifests = mutableListOf<ArtifactManifest>()
        var committed = false
        return try {
            val folder = when (val result = folderSourceAdapter.resolve(config)) {
                is LanzouFolderResolutionResult.Failure -> {
                    return CatalogLoadResult.Failure(
                        result.failure.reasonCode,
                        result.failure.retryable,
                    )
                }

                is LanzouFolderResolutionResult.Success -> result
            }
            selectionContext = SelectionContext(config, folder)
            val appFailures = folder.appFailures
                .map { CatalogAppFailure(it.componentId, it.reasonCode, it.retryable) }
                .toMutableList()
            val manifests = folder.artifacts.mapNotNull { artifact ->
                when (val result = buildManifest(config, artifact)) {
                    is ManifestBuildResult.Success -> result.manifest.also { stagedManifests += it }
                    is ManifestBuildResult.Failure -> {
                        if (artifact.component.componentId == DESKTOP_APP_ID) {
                            return CatalogLoadResult.Failure(result.reasonCode, result.retryable)
                        }
                        appFailures += CatalogAppFailure(
                            componentId = artifact.component.componentId,
                            reasonCode = result.reasonCode,
                            retryable = result.retryable,
                        )
                        null
                    }
                }
            }
            if (manifests.none { it.componentId == DESKTOP_APP_ID }) {
                return CatalogLoadResult.Failure("distribution_desktop_unavailable", retryable = false)
            }
            if (!config.expiresAt.isAfter(now())) {
                return CatalogLoadResult.Failure("distribution_config_expired", retryable = true)
            }
            when (val validation = ArtifactManifestValidator.validateCatalog(manifests)) {
                is ManifestValidation.Invalid ->
                    CatalogLoadResult.Failure(validation.reasonCode, retryable = false)

                ManifestValidation.Valid -> {
                    committed = true
                    CatalogLoadResult.Success(
                        TrustedArtifactCatalog(
                            catalogVersion = config.effectiveCatalogVersion(),
                            keyId = config.keyId,
                            signatureAlgorithm = config.signatureAlgorithm,
                            manifests = manifests,
                            apps = config.declaredApps().filter { it.enabled },
                            appFailures = appFailures,
                            catalogRevision = config.catalogRevision,
                        ),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CatalogLoadResult.Failure("distribution_catalog_build_failed", retryable = true)
        } finally {
            if (!committed) stagedManifests.forEach(artifactCache::clearArtifact)
            clearWorkingDirectory()
        }
    }

    private suspend fun buildManifest(
        config: InstallerDistributionConfig,
        artifact: LanzouFolderArtifact,
        onDownloadProgress: (DynamicDownloadProgress) -> Unit = {},
    ): ManifestBuildResult {
        var manifestForCleanup: ArtifactManifest? = null
        return try {
            if (!config.expiresAt.isAfter(now())) {
                return ManifestBuildResult.Failure("distribution_config_expired", retryable = true)
            }
            val component = artifact.component
            val request = when (val result = lanzouSourceAdapter.resolve(artifact.source)) {
                is LanzouResolutionResult.Failure -> {
                    return ManifestBuildResult.Failure(result.failure.reasonCode, result.failure.retryable)
                }

                is LanzouResolutionResult.Success -> result.request
            }
            val archiveFile = workingDirectory.resolve("${component.componentId}.zip")
            val apkFile = workingDirectory.resolve("${component.componentId}.apk")
            val archive = when (val result = downloader.download(request, archiveFile, onDownloadProgress)) {
                is DynamicArchiveDownloadResult.Failed -> {
                    return ManifestBuildResult.Failure(result.reasonCode, result.retryable)
                }

                is DynamicArchiveDownloadResult.Completed -> result.archive
            }
            if (!config.expiresAt.isAfter(now())) {
                return ManifestBuildResult.Failure("distribution_config_expired", retryable = true)
            }
            val inspection = inspectArchive(archive.file, apkFile, component.apkEntryName.takeIf { it.isNotBlank() })
                ?: return ManifestBuildResult.Failure("distribution_archive_invalid", retryable = false)
            val metadata = try {
                metadataReader.read(inspection.apkFile)
            } catch (_: Exception) {
                null
            } ?: return ManifestBuildResult.Failure("distribution_apk_metadata_unreadable", retryable = false)

            if (component.packageName.isNotBlank() && metadata.packageName != component.packageName) {
                return ManifestBuildResult.Failure("distribution_apk_package_mismatch", retryable = false)
            }
            if (!InstallerPublisherTrustRegistry.isKnownProfile(component.trustProfileId)) {
                return ManifestBuildResult.Failure("distribution_trust_profile_invalid", retryable = false)
            }
            if (!InstallerPublisherTrustRegistry.isTrusted(
                    component.trustProfileId,
                    metadata.packageName,
                    metadata.certificateSha256s,
                )
            ) {
                return ManifestBuildResult.Failure("distribution_apk_certificate_mismatch", retryable = false)
            }
            val trustedComponent = InstallerComponentTrustRegistry.get(component.componentId)
            if (trustedComponent != null && metadata.packageName != trustedComponent.packageName) {
                return ManifestBuildResult.Failure("distribution_apk_package_mismatch", retryable = false)
            }
            if (!AuthorizationPlanFactory.validateComponent(
                    ManagedComponent(
                        componentId = component.componentId,
                        packageName = metadata.packageName,
                        setup = component.deviceSetup,
                        order = component.sortOrder,
                    ),
                )
            ) {
                return ManifestBuildResult.Failure("distribution_device_setup_invalid", retryable = false)
            }
            if (!config.expiresAt.isAfter(now())) {
                return ManifestBuildResult.Failure("distribution_config_expired", retryable = true)
            }

            val manifest = ArtifactManifest(
                schemaVersion = ArtifactManifestValidator.SUPPORTED_SCHEMA_VERSION,
                componentId = component.componentId,
                displayName = component.displayName,
                description = component.description,
                required = component.required,
                version = metadata.version,
                compatibility = CompatibilityRange(minAndroidSdk = metadata.minAndroidSdk ?: component.minAndroidSdk),
                archiveFileName = component.archiveFileName,
                archiveSizeBytes = archive.sizeBytes,
                archiveSha256 = archive.sha256,
                apkEntryName = inspection.entryName,
                apkSizeBytes = inspection.apkSizeBytes,
                apkSha256 = inspection.apkSha256,
                packageName = metadata.packageName,
                apkVersion = ArtifactVersion(metadata.version.name, metadata.version.code),
                certificateSha256 = checkNotNull(
                    InstallerPublisherTrustRegistry.trustedCertificateSha256(
                        component.trustProfileId,
                        metadata.certificateSha256s,
                    ),
                ).lowercase(),
                sources = listOf(artifact.source),
                rollbackId = "${config.effectiveCatalogVersion()}-${component.componentId}",
                deviceSetup = component.deviceSetup,
                sortOrder = component.sortOrder,
            )
            manifestForCleanup = manifest
            when (val validation = ArtifactManifestValidator.validate(manifest)) {
                is ManifestValidation.Invalid ->
                    return ManifestBuildResult.Failure(validation.reasonCode, retryable = false)

                ManifestValidation.Valid -> Unit
            }
            if (sourcePolicy.plan(manifest) !is com.tcrrry.helper.domain.artifact.SourcePlan.Accepted) {
                return ManifestBuildResult.Failure("distribution_source_policy_rejected", retryable = false)
            }
            val cachePaths = artifactCache.paths(manifest)
            cachePaths.archivePart.parentFile?.mkdirs()
            Files.copy(
                archive.file.toPath(),
                cachePaths.archivePart.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            artifactCache.writeResumeMetadata(manifest, ArtifactSourceKind.LANZOU_SHARE.wireName)
            ManifestBuildResult.Success(manifest)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            manifestForCleanup?.let { runCatching { artifactCache.clearArtifact(it) } }
            ManifestBuildResult.Failure("distribution_app_processing_failed", retryable = true)
        }
    }

    private suspend fun prepareSelectedArtifact(
        config: InstallerDistributionConfig,
        artifact: LanzouFolderArtifact,
        onProgress: (CatalogPreparationProgress) -> Unit,
    ): ManifestBuildResult {
        val componentId = artifact.component.componentId
        onProgress(CatalogPreparationProgress(componentId, InstallPhase.FETCH, ComponentProgressStatus.RUNNING))
        val result = buildManifest(config, artifact) { progress ->
            onProgress(
                CatalogPreparationProgress(
                    componentId = componentId,
                    phase = InstallPhase.FETCH,
                    status = ComponentProgressStatus.RUNNING,
                    bytesWritten = progress.bytesWritten,
                    totalBytes = progress.expectedBytes ?: 0L,
                    indeterminate = progress.expectedBytes == null,
                ),
            )
        }
        when (result) {
            is ManifestBuildResult.Success -> onProgress(
                CatalogPreparationProgress(
                    componentId,
                    InstallPhase.CHECK,
                    ComponentProgressStatus.COMPLETED,
                    bytesWritten = result.manifest.archiveSizeBytes,
                    totalBytes = result.manifest.archiveSizeBytes,
                    indeterminate = false,
                ),
            )

            is ManifestBuildResult.Failure -> onProgress(
                CatalogPreparationProgress(
                    componentId,
                    InstallPhase.CHECK,
                    ComponentProgressStatus.FAILED,
                    indeterminate = false,
                ),
            )
        }
        return result
    }

    private fun inspectArchive(archive: File, apkFile: File, expectedEntryName: String?): ArchiveInspection? {
        if (!archive.isFile || archive.length() <= 0L) return null
        apkFile.delete()
        var entryName: String? = null
        var entryCount = 0
        var written = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        return try {
            ZipInputStream(FileInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount += 1
                    if (entryCount > 1 || entry.isDirectory || !isSafeApkEntry(entry.name) ||
                        (expectedEntryName != null && entry.name != expectedEntryName)
                    ) {
                        return null
                    }
                    entryName = entry.name
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            written += count
                            if (written > ArtifactManifestValidator.MAX_APK_SIZE_BYTES) return null
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                }
            }
            val name = entryName ?: return null
            if (entryCount != 1 || written <= 0L || !apkFile.isFile || apkFile.length() != written) return null
            ArchiveInspection(
                entryName = name,
                apkFile = apkFile,
                apkSizeBytes = written,
                apkSha256 = digest.digest().toHexString(),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun isSafeApkEntry(name: String): Boolean =
        name.endsWith(".apk", ignoreCase = true) &&
            name.isNotBlank() &&
            !name.startsWith('/') &&
            !name.startsWith('\\') &&
            !name.contains('/') &&
            !name.contains('\\') &&
            !name.contains('\u0000') &&
            !name.contains(':')

    private fun clearWorkingDirectory() {
        workingDirectory.listFiles()?.forEach { file -> file.deleteRecursively() }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class ArchiveInspection(
        val entryName: String,
        val apkFile: File,
        val apkSizeBytes: Long,
        val apkSha256: String,
    )

    private data class SelectionContext(
        val config: InstallerDistributionConfig,
        val folder: LanzouFolderResolutionResult.Success,
    )

    private sealed interface ManifestBuildResult {
        data class Success(val manifest: ArtifactManifest) : ManifestBuildResult

        data class Failure(val reasonCode: String, val retryable: Boolean) : ManifestBuildResult
    }

    private companion object {
        const val DESKTOP_APP_ID = "desktop"
        const val MAX_PREPARATION_CONCURRENCY = 2
    }
}
