package com.tcrrry.helper.data.catalog

import com.tcrrry.helper.data.artifact.ApkMetadataReader
import com.tcrrry.helper.data.download.DynamicArtifactDownloader
import com.tcrrry.helper.data.download.DynamicArchiveDownloadResult
import com.tcrrry.helper.data.download.ArtifactCache
import com.tcrrry.helper.data.web.LanzouFolderArtifact
import com.tcrrry.helper.data.web.LanzouFolderResolutionResult
import com.tcrrry.helper.data.web.LanzouFolderSourceAdapter
import com.tcrrry.helper.data.web.LanzouResolutionResult
import com.tcrrry.helper.data.web.LanzouWebSourceAdapter
import com.tcrrry.helper.domain.artifact.ArtifactManifest
import com.tcrrry.helper.domain.artifact.ArtifactManifestValidator
import com.tcrrry.helper.domain.artifact.ArtifactSourceKind
import com.tcrrry.helper.domain.artifact.ArtifactVersion
import com.tcrrry.helper.domain.artifact.CompatibilityRange
import com.tcrrry.helper.domain.artifact.ManifestValidation
import com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException

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
) {
    suspend fun load(): CatalogLoadResult {
        val config = when (val result = configAdapter.load()) {
            is DistributionConfigLoadResult.Failure -> {
                return CatalogLoadResult.Failure(result.reasonCode, result.retryable)
            }

            is DistributionConfigLoadResult.Success -> result.config
        }
        if (!workingDirectory.mkdirs() && !workingDirectory.isDirectory) {
            return CatalogLoadResult.Failure("distribution_catalog_cache_unavailable", retryable = true)
        }
        clearWorkingDirectory()
        return try {
            val folder = when (val result = folderSourceAdapter.resolve(config)) {
                is LanzouFolderResolutionResult.Failure -> {
                    return CatalogLoadResult.Failure(
                        result.failure.reasonCode,
                        result.failure.retryable,
                    )
                }

                is LanzouFolderResolutionResult.Success -> result.artifacts
            }
            val manifests = folder.map { artifact ->
                when (val result = buildManifest(config, artifact)) {
                    is ManifestBuildResult.Success -> result.manifest
                    is ManifestBuildResult.Failure -> {
                        return CatalogLoadResult.Failure(result.reasonCode, result.retryable)
                    }
                }
            }
            when (val validation = ArtifactManifestValidator.validateCatalog(manifests)) {
                is ManifestValidation.Invalid ->
                    CatalogLoadResult.Failure(validation.reasonCode, retryable = false)

                ManifestValidation.Valid -> CatalogLoadResult.Success(
                    TrustedArtifactCatalog(
                        catalogVersion = config.configVersion,
                        keyId = config.keyId,
                        signatureAlgorithm = config.signatureAlgorithm,
                        manifests = manifests,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CatalogLoadResult.Failure("distribution_catalog_build_failed", retryable = true)
        } finally {
            clearWorkingDirectory()
        }
    }

    private suspend fun buildManifest(
        config: InstallerDistributionConfig,
        artifact: LanzouFolderArtifact,
    ): ManifestBuildResult {
        val component = artifact.component
        val request = when (val result = lanzouSourceAdapter.resolve(artifact.source)) {
            is LanzouResolutionResult.Failure -> {
                return ManifestBuildResult.Failure(result.failure.reasonCode, result.failure.retryable)
            }

            is LanzouResolutionResult.Success -> result.request
        }
        val archiveFile = workingDirectory.resolve("${component.componentId}.zip")
        val apkFile = workingDirectory.resolve("${component.componentId}.apk")
        val archive = when (val result = downloader.download(request, archiveFile)) {
            is DynamicArchiveDownloadResult.Failed -> {
                return ManifestBuildResult.Failure(result.reasonCode, result.retryable)
            }

            is DynamicArchiveDownloadResult.Completed -> result.archive
        }
        val inspection = inspectArchive(archive.file, apkFile)
            ?: return ManifestBuildResult.Failure("distribution_archive_invalid", retryable = false)
        val metadata = try {
            metadataReader.read(inspection.apkFile)
        } catch (_: Exception) {
            null
        } ?: return ManifestBuildResult.Failure("distribution_apk_metadata_unreadable", retryable = false)

        val expectedPackage = component.packageName
            ?: return ManifestBuildResult.Failure("distribution_package_identity_missing", retryable = false)
        if (metadata.packageName != expectedPackage) {
            return ManifestBuildResult.Failure("distribution_apk_package_mismatch", retryable = false)
        }
        val expectedCertificate = component.certificateSha256
            ?: return ManifestBuildResult.Failure("distribution_certificate_identity_missing", retryable = false)
        if (metadata.certificateSha256s.none { it.equals(expectedCertificate, ignoreCase = true) }) {
            return ManifestBuildResult.Failure("distribution_apk_certificate_mismatch", retryable = false)
        }

        val manifest = ArtifactManifest(
            schemaVersion = ArtifactManifestValidator.SUPPORTED_SCHEMA_VERSION,
            componentId = component.componentId,
            displayName = component.displayName,
            required = component.required,
            version = metadata.version,
            compatibility = CompatibilityRange(minAndroidSdk = component.minAndroidSdk),
            archiveFileName = component.archiveFileName,
            archiveSizeBytes = archive.sizeBytes,
            archiveSha256 = archive.sha256,
            apkEntryName = inspection.entryName,
            apkSizeBytes = inspection.apkSizeBytes,
            apkSha256 = inspection.apkSha256,
            packageName = metadata.packageName,
            apkVersion = ArtifactVersion(metadata.version.name, metadata.version.code),
            certificateSha256 = expectedCertificate.lowercase(),
            sources = listOf(artifact.source),
            rollbackId = "${config.configVersion}-${component.componentId}",
        )
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
        return ManifestBuildResult.Success(manifest)
    }

    private fun inspectArchive(archive: File, apkFile: File): ArchiveInspection? {
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
                    if (entryCount > 1 || entry.isDirectory || !isSafeApkEntry(entry.name)) return null
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

    private sealed interface ManifestBuildResult {
        data class Success(val manifest: ArtifactManifest) : ManifestBuildResult

        data class Failure(val reasonCode: String, val retryable: Boolean) : ManifestBuildResult
    }
}
