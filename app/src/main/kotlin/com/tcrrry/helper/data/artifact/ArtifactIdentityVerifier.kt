package com.tcrrry.helper.data.artifact

import com.tcrrry.helper.domain.artifact.ArtifactFailure
import com.tcrrry.helper.domain.artifact.ArtifactFailurePhase
import com.tcrrry.helper.domain.artifact.ArtifactManifest
import com.tcrrry.helper.domain.artifact.ArtifactSourceKind
import com.tcrrry.helper.domain.artifact.ArtifactVerification
import com.tcrrry.helper.domain.artifact.ArtifactVersion
import com.tcrrry.helper.domain.device.ApkDeclarationMetadata
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ApkMetadata(
    val packageName: String,
    val version: ArtifactVersion,
    val certificateSha256s: Set<String>,
    val declarations: ApkDeclarationMetadata = ApkDeclarationMetadata(),
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
            extractedApk.file.length() != manifest.apkSizeBytes -> "apk_size_mismatch"
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
        if (!actualSha256.equals(manifest.apkSha256, ignoreCase = true)) {
            return failed(manifest, verifiedArchive.file, extractedApk.file, finalApk, "apk_sha256_mismatch")
        }
        val metadata = try {
            metadataReader.read(extractedApk.file)
        } catch (_: Exception) {
            null
        } ?: return failed(manifest, verifiedArchive.file, extractedApk.file, finalApk, "apk_metadata_unreadable")
        if (metadata.packageName != manifest.packageName) {
            return failed(manifest, verifiedArchive.file, extractedApk.file, finalApk, "apk_package_mismatch")
        }
        if (metadata.version != manifest.apkVersion) {
            return failed(manifest, verifiedArchive.file, extractedApk.file, finalApk, "apk_version_mismatch")
        }
        val certificateMatches = metadata.certificateSha256s.any {
            it.equals(manifest.certificateSha256, ignoreCase = true)
        }
        if (!certificateMatches) {
            return failed(manifest, verifiedArchive.file, extractedApk.file, finalApk, "apk_certificate_mismatch")
        }

        finalApk.parentFile?.let { parent ->
            if (!parent.mkdirs() && !parent.isDirectory) {
                return failed(manifest, verifiedArchive.file, extractedApk.file, finalApk, "apk_output_unavailable")
            }
        }
        finalApk.delete()
        try {
            Files.move(
                extractedApk.file.toPath(),
                finalApk.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            return failed(manifest, verifiedArchive.file, extractedApk.file, finalApk, "apk_atomic_move_unsupported")
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
}
