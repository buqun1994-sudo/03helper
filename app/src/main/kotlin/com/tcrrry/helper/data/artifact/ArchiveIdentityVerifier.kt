package com.tcrrry.helper.data.artifact

import com.tcrrry.helper.domain.artifact.ArtifactFailure
import com.tcrrry.helper.domain.artifact.ArtifactFailurePhase
import com.tcrrry.helper.domain.artifact.ArtifactManifest
import java.io.File
import java.nio.file.Files

data class VerifiedArchive(
    val componentId: String,
    val file: File,
    val sizeBytes: Long,
    val sha256: String,
)

sealed interface ArchiveIdentityResult {
    data class Verified(val archive: VerifiedArchive) : ArchiveIdentityResult
    data class Failed(val failure: ArtifactFailure) : ArchiveIdentityResult
}

class ArchiveIdentityVerifier {
    fun verify(manifest: ArtifactManifest, archivePart: File): ArchiveIdentityResult {
        val reasonCode = when {
            !archivePart.isFile -> "archive_missing"
            Files.isSymbolicLink(archivePart.toPath()) -> "archive_symlink_forbidden"
            archivePart.length() != manifest.archiveSizeBytes -> "archive_size_mismatch"
            else -> null
        }
        if (reasonCode != null) return failed(manifest, archivePart, reasonCode)

        val actualSha256 = try {
            sha256(archivePart)
        } catch (_: Exception) {
            return failed(manifest, archivePart, "archive_hash_failed")
        }
        if (!actualSha256.equals(manifest.archiveSha256, ignoreCase = true)) {
            return failed(manifest, archivePart, "archive_sha256_mismatch")
        }
        return ArchiveIdentityResult.Verified(
            VerifiedArchive(
                componentId = manifest.componentId,
                file = archivePart,
                sizeBytes = archivePart.length(),
                sha256 = actualSha256,
            ),
        )
    }

    private fun failed(
        manifest: ArtifactManifest,
        archivePart: File,
        reasonCode: String,
    ): ArchiveIdentityResult.Failed {
        archivePart.delete()
        return ArchiveIdentityResult.Failed(
            ArtifactFailure(
                phase = ArtifactFailurePhase.ARCHIVE_VERIFICATION,
                componentId = manifest.componentId,
                reasonCode = reasonCode,
                retryable = false,
            ),
        )
    }
}
