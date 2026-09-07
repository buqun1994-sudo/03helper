package com.ninepointnine.helper.data.artifact

import com.ninepointnine.helper.domain.artifact.ArtifactFailure
import com.ninepointnine.helper.domain.artifact.ArtifactFailurePhase
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ArtifactVerification
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.device.ApkDeclarationMetadata
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ApkMetadata(
    val packageName: String,
    val version: ArtifactVersion,
    val certificateSha256s: Set<String>,
    val declarations: ApkDeclarationMetadata = ApkDeclarationMetadata(),
    val minAndroidSdk: Int? = null,
)

fun interface ApkMetadataReader {
    fun read(apk: File): ApkMetadata?
}

data class VerifiedApk(
    val file: File,
    val verification: ArtifactVerification,
    val metadata: ApkMetadata? = null,
)

sealed interface ArtifactIdentityResult {
    data class Verified(val apk: VerifiedApk) : ArtifactIdentityResult
    data class Failed(val failure: ArtifactFailure) : ArtifactIdentityResult
}

class ArtifactIdentityVerifier(
    private val metadataReader: ApkMetadataReader,
) {
    /**
     * Re-validates an APK already present in the public Download directory.
     * This path never deletes the candidate: files placed by the user belong
     * to the user, even when they do not match the selected release.
     */
    fun verifyExisting(
        manifest: ArtifactManifest,
        sourceKind: ArtifactSourceKind,
        apk: File,
    ): ArtifactIdentityResult {
        if (!apk.isFile) return existingFailure(manifest, sourceKind, "apk_missing")
        val actualSha256 = try {
            sha256(apk)
        } catch (_: Exception) {
            return existingFailure(manifest, sourceKind, "apk_hash_failed")
        }
        if (manifest.apkSizeBytes > 0L && apk.length() != manifest.apkSizeBytes) {
            return existingFailure(manifest, sourceKind, "apk_size_mismatch")
        }
        if (manifest.apkSha256.isNotBlank() &&
            !actualSha256.equals(manifest.apkSha256, ignoreCase = true)
        ) {
            return existingFailure(manifest, sourceKind, "apk_hash_mismatch")
        }
        val metadata = runCatching { metadataReader.read(apk) }.getOrNull()
            ?: return existingFailure(manifest, sourceKind, "apk_metadata_unreadable")
        if (metadata.packageName != manifest.packageName) {
            return existingFailure(manifest, sourceKind, "apk_package_mismatch")
        }
        if (!manifest.matchesCertificates(metadata.certificateSha256s)) {
            return existingFailure(manifest, sourceKind, "apk_certificate_mismatch")
        }
        if (metadata.version.code != manifest.apkVersion.code ||
            (manifest.apkVersion.name.isNotBlank() && metadata.version.name != manifest.apkVersion.name)
        ) {
            return existingFailure(manifest, sourceKind, "apk_version_mismatch")
        }
        return ArtifactIdentityResult.Verified(
            VerifiedApk(
                file = apk,
                verification = ArtifactVerification(
                    componentId = manifest.componentId,
                    sourceKind = sourceKind,
                    archiveSizeBytes = if (manifest.localOnly) 0L else manifest.archiveSizeBytes,
                    archiveSha256 = if (manifest.localOnly) "" else manifest.archiveSha256,
                    apkSizeBytes = apk.length(),
                    apkSha256 = actualSha256,
                    packageName = metadata.packageName,
                    apkVersion = metadata.version,
                    certificateSha256 = manifest.certificateSha256.lowercase(),
                    archiveDeleted = true,
                    localDownload = true,
                ),
                metadata = metadata,
            ),
        )
    }

    fun verify(
        manifest: ArtifactManifest,
        sourceKind: ArtifactSourceKind,
        verifiedArchive: VerifiedArchive,
        extractedApk: ExtractedApk,
        finalApk: File,
    ): ArtifactIdentityResult {
        val reasonCode = when {
            extractedApk.componentId != manifest.componentId -> "apk_component_mismatch"
            extractedApk.entryName != manifest.apkEntryName -> "apk_entry_mismatch"
            !extractedApk.file.isFile -> "apk_missing"
            else -> null
        }
        if (reasonCode != null) {
            return failed(manifest, verifiedArchive.file, extractedApk.file, finalApk, reasonCode)
        }
        val actualSha256 = try {
            sha256(extractedApk.file)
        } catch (_: Exception) {
            return failed(manifest, verifiedArchive.file, extractedApk.file, finalApk, "apk_hash_failed")
        }
        val metadata = try {
            metadataReader.read(extractedApk.file)
        } catch (_: Exception) {
            null
        } ?: return failed(manifest, verifiedArchive.file, extractedApk.file, finalApk, "apk_metadata_unreadable")
        if (metadata.packageName != manifest.packageName) {
            return failed(manifest, verifiedArchive.file, extractedApk.file, finalApk, "apk_package_mismatch")
        }
        val certificateMatches = manifest.matchesCertificates(metadata.certificateSha256s)
        if (!certificateMatches) {
            return failed(manifest, verifiedArchive.file, extractedApk.file, finalApk, "apk_certificate_mismatch")
        }
        if (extractedApk.file.length() != manifest.apkSizeBytes || !actualSha256.equals(manifest.apkSha256, ignoreCase = true)) {
            return failed(manifest, verifiedArchive.file, extractedApk.file, finalApk, "apk_hash_mismatch")
        }
        if (
            metadata.version.code != manifest.apkVersion.code ||
            (manifest.apkVersion.name.isNotBlank() && metadata.version.name != manifest.apkVersion.name)
        ) {
            return failed(manifest, verifiedArchive.file, extractedApk.file, finalApk, "apk_version_mismatch")
        }

        finalApk.parentFile?.let { parent ->
            if (!parent.mkdirs() && !parent.isDirectory) {
                return failed(manifest, verifiedArchive.file, extractedApk.file, finalApk, "apk_output_unavailable")
            }
        }
        try {
            try {
                Files.move(
                    extractedApk.file.toPath(),
                    finalApk.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // Some Android-backed filesystems do not expose an atomic
                // rename. The source has already passed identity checks, so a
                // regular replace is the correct portable finalization path.
                Files.move(
                    extractedApk.file.toPath(),
                    finalApk.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (_: Exception) {
            return failed(manifest, verifiedArchive.file, extractedApk.file, finalApk, "apk_finalize_failed")
        }
        val archiveDeleted = verifiedArchive.file.delete() || !verifiedArchive.file.exists()
        if (!archiveDeleted) {
            return failed(manifest, verifiedArchive.file, extractedApk.file, finalApk, "archive_cleanup_failed")
        }
        return ArtifactIdentityResult.Verified(
            VerifiedApk(
                file = finalApk,
                verification = ArtifactVerification(
                    componentId = manifest.componentId,
                    sourceKind = sourceKind,
                    archiveSizeBytes = verifiedArchive.sizeBytes,
                    archiveSha256 = verifiedArchive.sha256,
                    apkSizeBytes = finalApk.length(),
                    apkSha256 = actualSha256,
                    packageName = metadata.packageName,
                    apkVersion = metadata.version,
                    certificateSha256 = manifest.certificateSha256.lowercase(),
                    archiveDeleted = true,
                ),
                metadata = metadata,
            ),
        )
    }

    private fun failed(
        manifest: ArtifactManifest,
        archive: File,
        apkPart: File,
        finalApk: File,
        reasonCode: String,
    ): ArtifactIdentityResult.Failed {
        archive.delete()
        apkPart.delete()
        finalApk.delete()
        return ArtifactIdentityResult.Failed(
            ArtifactFailure(
                phase = ArtifactFailurePhase.APK_VERIFICATION,
                componentId = manifest.componentId,
                reasonCode = reasonCode,
                retryable = false,
            ),
        )
    }

    private fun existingFailure(
        manifest: ArtifactManifest,
        sourceKind: ArtifactSourceKind,
        reasonCode: String,
    ): ArtifactIdentityResult.Failed = ArtifactIdentityResult.Failed(
        ArtifactFailure(
            phase = ArtifactFailurePhase.APK_VERIFICATION,
            componentId = manifest.componentId,
            sourceKind = sourceKind,
            reasonCode = reasonCode,
            retryable = false,
        ),
    )
}
