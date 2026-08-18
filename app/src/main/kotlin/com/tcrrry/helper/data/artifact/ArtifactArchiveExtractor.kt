package com.tcrrry.helper.data.artifact

import com.tcrrry.helper.domain.artifact.ArtifactFailure
import com.tcrrry.helper.domain.artifact.ArtifactFailurePhase
import com.tcrrry.helper.domain.artifact.ArtifactManifest
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

fun interface UsableSpaceProvider {
    fun usableBytes(directory: File): Long
}

data class ExtractedApk(
    val componentId: String,
    val file: File,
    val entryName: String,
    val sizeBytes: Long,
    val sha256: String,
)

sealed interface ArchiveExtractionResult {
    data class Extracted(val apk: ExtractedApk) : ArchiveExtractionResult
    data class Failed(val failure: ArtifactFailure) : ArchiveExtractionResult
}

@Suppress("UsableSpace")
class ArtifactArchiveExtractor(
    private val usableSpaceProvider: UsableSpaceProvider = UsableSpaceProvider { it.usableSpace },
) {
    fun extract(
        manifest: ArtifactManifest,
        verifiedArchive: VerifiedArchive,
        apkPart: File,
    ): ArchiveExtractionResult {
        if (
            verifiedArchive.componentId != manifest.componentId ||
            verifiedArchive.sizeBytes != manifest.archiveSizeBytes ||
            !verifiedArchive.sha256.equals(manifest.archiveSha256, ignoreCase = true)
        ) {
            return failed(manifest, verifiedArchive.file, apkPart, "archive_receipt_mismatch")
        }
        val outputDirectory = apkPart.parentFile
            ?: return failed(manifest, verifiedArchive.file, apkPart, "apk_output_directory_missing")
        if (!outputDirectory.mkdirs() && !outputDirectory.isDirectory) {
            return failed(manifest, verifiedArchive.file, apkPart, "apk_output_directory_unavailable")
        }
        val requiredBytes = try {
            Math.addExact(
                Math.addExact(manifest.archiveSizeBytes, manifest.apkSizeBytes),
                MIN_FREE_SPACE_BYTES,
            )
        } catch (_: ArithmeticException) {
            return failed(manifest, verifiedArchive.file, apkPart, "artifact_size_overflow")
        }
        if (usableSpaceProvider.usableBytes(outputDirectory) < requiredBytes) {
            return failed(manifest, verifiedArchive.file, apkPart, "insufficient_private_cache_space")
        }

        apkPart.delete()
        return try {
            if (!hasZipSignature(verifiedArchive.file)) {
                throw ExtractionRejected("archive_zip_invalid")
            }
            val extracted = extractSingleEntry(manifest, verifiedArchive.file, apkPart)
            ArchiveExtractionResult.Extracted(extracted)
        } catch (rejected: ExtractionRejected) {
            failed(manifest, verifiedArchive.file, apkPart, rejected.reasonCode)
        } catch (_: ZipException) {
            failed(manifest, verifiedArchive.file, apkPart, "archive_zip_invalid")
        } catch (_: Exception) {
            failed(manifest, verifiedArchive.file, apkPart, "archive_extraction_failed")
        }
    }

    private fun extractSingleEntry(
        manifest: ArtifactManifest,
        archive: File,
        apkPart: File,
    ): ExtractedApk {
        val digest = MessageDigest.getInstance("SHA-256")
        var entryCount = 0
        var bytesWritten = 0L
        ZipInputStream(FileInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                if (entryCount > 1) throw ExtractionRejected("archive_extra_entry")
                val entryName = entry.name
                if (!isSafeRootEntry(entryName)) throw ExtractionRejected("archive_path_traversal")
                if (entry.isDirectory) throw ExtractionRejected("archive_directory_forbidden")
                if (entryName != manifest.apkEntryName) {
                    throw ExtractionRejected(
                        if (entryName.endsWith(".apk", ignoreCase = true)) {
                            "archive_unexpected_apk"
                        } else {
                            "archive_extra_file"
                        },
                    )
                }
                if (entry.size > manifest.apkSizeBytes) throw ExtractionRejected("apk_size_exceeds_manifest")
                BufferedOutputStream(FileOutputStream(apkPart)).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        bytesWritten += count
                        if (bytesWritten > manifest.apkSizeBytes) {
                            throw ExtractionRejected("apk_size_exceeds_manifest")
                        }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    output.flush()
                }
                zip.closeEntry()
            }
        }
        if (entryCount == 0) throw ExtractionRejected("apk_entry_missing")
        if (bytesWritten != manifest.apkSizeBytes) throw ExtractionRejected("apk_size_mismatch")
        return ExtractedApk(
            componentId = manifest.componentId,
            file = apkPart,
            entryName = manifest.apkEntryName,
            sizeBytes = bytesWritten,
            sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) },
        )
    }

    private fun failed(
        manifest: ArtifactManifest,
        archive: File,
        apkPart: File,
        reasonCode: String,
    ): ArchiveExtractionResult.Failed {
        archive.delete()
        apkPart.delete()
        return ArchiveExtractionResult.Failed(
            ArtifactFailure(
                phase = ArtifactFailurePhase.EXTRACTION,
                componentId = manifest.componentId,
                reasonCode = reasonCode,
                retryable = false,
            ),
        )
    }

    private fun isSafeRootEntry(name: String): Boolean =
        name.isNotBlank() &&
            name != "." &&
            name != ".." &&
            !name.startsWith('/') &&
            !name.startsWith('\\') &&
            !name.contains('/') &&
            !name.contains('\\') &&
            !name.contains('\u0000') &&
            !name.contains(":")

    private fun hasZipSignature(file: File): Boolean {
        FileInputStream(file).use { input ->
            val header = ByteArray(4)
            if (input.read(header) != header.size) return false
            return header.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04)) ||
                header.contentEquals(byteArrayOf(0x50, 0x4b, 0x05, 0x06)) ||
                header.contentEquals(byteArrayOf(0x50, 0x4b, 0x07, 0x08))
        }
    }

    private class ExtractionRejected(val reasonCode: String) : RuntimeException(reasonCode)

    companion object {
        const val MIN_FREE_SPACE_BYTES = 64L * 1024L * 1024L
    }
}
