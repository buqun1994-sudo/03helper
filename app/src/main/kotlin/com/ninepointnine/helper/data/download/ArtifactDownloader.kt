package com.ninepointnine.helper.data.download

import android.util.Log
import com.ninepointnine.helper.data.artifact.sha256
import com.ninepointnine.helper.domain.artifact.ArtifactFailure
import com.ninepointnine.helper.domain.artifact.ArtifactFailurePhase
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ResolvedDownloadRequest
import com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ArtifactTransportResponse(
    val statusCode: Int,
    val contentLength: Long?,
    val contentType: String?,
    val body: InputStream,
    private val closeAction: () -> Unit = {},
    /** Parsed Content-Range start for a 206 response, when the server sends it. */
    val contentRangeStartBytes: Long? = null,
    /** Parsed Content-Range total for a 206/416 response, when available. */
    val contentRangeTotalBytes: Long? = null,
) : Closeable {
    override fun close() {
        runCatching { body.close() }
        runCatching { closeAction() }
    }
}

fun interface ArtifactTransport {
    suspend fun open(request: ResolvedDownloadRequest, rangeStartBytes: Long): ArtifactTransportResponse
}

data class DownloadProgress(
    val bytesWritten: Long,
    val expectedBytes: Long,
    val resumed: Boolean,
)

sealed interface ArtifactDownloadResult {
    data class Completed(
        val archivePart: java.io.File,
        val sizeBytes: Long,
        val resumed: Boolean,
    ) : ArtifactDownloadResult

    data class Failed(
        val failure: ArtifactFailure,
        val partialBytes: Long,
    ) : ArtifactDownloadResult
}

class ArtifactDownloader(
    private val transport: ArtifactTransport,
    private val cache: ArtifactCache,
    private val sourcePolicy: ReleaseSourcePolicy = ReleaseSourcePolicy(),
    private val onProgress: (DownloadProgress) -> Unit = {},
    private val maxDynamicBytes: Long = 1L shl 30,
) {
    suspend fun download(
        manifest: ArtifactManifest,
        request: ResolvedDownloadRequest,
        progressListener: (DownloadProgress) -> Unit = onProgress,
    ): ArtifactDownloadResult = withContext(Dispatchers.IO) {
        downloadInternal(manifest, request, progressListener)
    }

    /**
     * Downloads an archive whose manifest identity is learned only after the
     * archive has been inspected. The transport and source-policy gates are
     * shared with [download], while the destination remains private working
     * storage owned by [ArtifactCache].
     */
    suspend fun downloadDynamic(
        request: ResolvedDownloadRequest,
        destination: File,
        progressListener: (DynamicDownloadProgress) -> Unit = {},
    ): DynamicArchiveDownloadResult = withContext(Dispatchers.IO) {
        val part = destination.resolveSibling("${destination.name}.part")
        // A dynamic URL is short-lived and its response is not resumable. Never
        // let a previous successful attempt become the result of a failed retry.
        if ((destination.exists() && !destination.delete()) || destination.exists()) {
            return@withContext DynamicArchiveDownloadResult.Failed(
                "dynamic_archive_cache_unavailable",
                retryable = true,
            )
        }
        if ((part.exists() && !part.delete()) || part.exists()) {
            return@withContext DynamicArchiveDownloadResult.Failed(
                "dynamic_archive_cache_unavailable",
                retryable = true,
            )
        }
        val validation = sourcePolicy.validateResolvedRequest(request)
        if (validation is com.ninepointnine.helper.domain.artifact.SourcePolicyValidation.Rejected) {
            return@withContext DynamicArchiveDownloadResult.Failed(validation.reasonCode, retryable = false)
        }
        destination.parentFile?.let { parent ->
            if (!parent.mkdirs() && !parent.isDirectory) {
                return@withContext DynamicArchiveDownloadResult.Failed(
                    "dynamic_archive_cache_unavailable",
                    retryable = true,
                )
            }
        }
        var response: ArtifactTransportResponse? = null
        var completed = false
        try {
            response = transport.open(request, 0L)
            if (response.statusCode !in 200..299) {
                return@withContext DynamicArchiveDownloadResult.Failed(
                    "dynamic_archive_http_${response.statusCode}",
                    retryable = isRetryableHttpStatus(response.statusCode),
                )
            }
            val contentType = response.contentType?.lowercase().orEmpty()
            if (contentType.startsWith("text/html") || contentType.startsWith("application/json")) {
                return@withContext DynamicArchiveDownloadResult.Failed("dynamic_archive_non_binary", retryable = true)
            }
            response.contentLength?.let { length ->
                if (length < 1L || length > maxDynamicBytes) {
                    return@withContext DynamicArchiveDownloadResult.Failed(
                        "dynamic_archive_size_invalid",
                        retryable = false,
                    )
                }
            }
            var written = 0L
            val expectedLength = response.contentLength
            FileOutputStream(part).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = response.body.read(buffer)
                    if (count < 0) break
                    if (count == 0) throw java.io.IOException("dynamic_archive_zero_read")
                    written += count
                    if (written > maxDynamicBytes) {
                        return@withContext DynamicArchiveDownloadResult.Failed(
                            "dynamic_archive_size_exceeds_limit",
                            retryable = false,
                        )
                    }
                    output.write(buffer, 0, count)
                    progressListener(DynamicDownloadProgress(written, response.contentLength))
                }
                output.fd.sync()
            }
            if (written <= 0L) {
                return@withContext DynamicArchiveDownloadResult.Failed("dynamic_archive_empty", retryable = false)
            }
            if (expectedLength != null && expectedLength >= 0L && written != expectedLength) {
                return@withContext DynamicArchiveDownloadResult.Failed(
                    "dynamic_archive_incomplete",
                    retryable = true,
                )
            }
            if (!hasZipSignature(part)) {
                part.delete()
                destination.delete()
                return@withContext DynamicArchiveDownloadResult.Failed(
                    "dynamic_archive_not_zip",
                    retryable = false,
                )
            }
            if (!part.renameTo(destination) || !destination.isFile || destination.length() != written) {
                return@withContext DynamicArchiveDownloadResult.Failed(
                    "dynamic_archive_finalize_failed",
                    retryable = true,
                )
            }
            val archive = DynamicArchiveDownload(destination, written, sha256(destination))
            completed = true
            DynamicArchiveDownloadResult.Completed(archive)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DynamicArchiveDownloadResult.Failed("dynamic_archive_io_failed", retryable = true)
        } finally {
            response?.close()
            part.delete()
            if (!completed) destination.delete()
        }
    }

    private suspend fun downloadInternal(
        manifest: ArtifactManifest,
        request: ResolvedDownloadRequest,
        progressListener: (DownloadProgress) -> Unit,
    ): ArtifactDownloadResult {
        val requestValidation = sourcePolicy.validateResolvedRequest(request)
        if (requestValidation is com.ninepointnine.helper.domain.artifact.SourcePolicyValidation.Rejected) {
            return failed(manifest, request, requestValidation.reasonCode)
        }
        val paths = cache.paths(manifest)
        val sourceKey = request.sourceKind.wireName
        val canResume = runCatching { cache.resumeMetadataMatches(manifest, sourceKey) }.getOrDefault(false) &&
            paths.archivePart.length() <= manifest.archiveSizeBytes
        try {
            if (!canResume) {
                cache.clearArchive(manifest)
            }
            cache.writeResumeMetadata(manifest, sourceKey)
        } catch (_: Exception) {
            return failed(manifest, request, "download_cache_unavailable", clearPartial = true)
        }
        var startBytes = if (canResume) paths.archivePart.length() else 0L
        var response: ArtifactTransportResponse? = null
        var resumed = startBytes > 0L
        try {
            response = transport.open(request, startBytes)
            if (startBytes > 0L && response.statusCode == 200) {
                response.close()
                response = transport.open(request, 0L)
                startBytes = 0L
                resumed = false
                paths.archivePart.delete()
            }
            if (startBytes > 0L && response.statusCode !in setOf(206, 416)) {
                return failed(
                    manifest,
                    request,
                    "download_range_response_invalid",
                    startBytes,
                    clearPartial = true,
                )
            }
            if (startBytes > 0L && response.statusCode == 206 &&
                ((response.contentRangeStartBytes != null && response.contentRangeStartBytes != startBytes) ||
                    (response.contentRangeTotalBytes != null &&
                        response.contentRangeTotalBytes != manifest.archiveSizeBytes))
            ) {
                return failed(
                    manifest,
                    request,
                    "download_range_response_invalid",
                    startBytes,
                    clearPartial = true,
                )
            }
            if (startBytes == 0L && response.statusCode == 206) {
                return failed(
                    manifest,
                    request,
                    "download_range_response_invalid",
                    clearPartial = true,
                )
            }
            if (response.statusCode == 416) {
                if (response.contentRangeTotalBytes != null &&
                    response.contentRangeTotalBytes != manifest.archiveSizeBytes
                ) {
                    return failed(
                        manifest,
                        request,
                        "download_range_response_invalid",
                        startBytes,
                        clearPartial = true,
                    )
                }
                if (startBytes == manifest.archiveSizeBytes) {
                    response.close()
                    if (!hasZipSignature(paths.archivePart)) {
                        return failed(manifest, request, "download_not_zip", startBytes, clearPartial = true)
                    }
                    return ArtifactDownloadResult.Completed(paths.archivePart, startBytes, resumed = true)
                }
                // An incomplete local part cannot be repaired with the same
                // unsatisfiable range. Drop it so the next attempt starts from
                // a clean request instead of repeating a permanent 416 loop.
                return failed(
                    manifest,
                    request,
                    "download_range_response_invalid",
                    startBytes,
                    clearPartial = true,
                )
            }
            if (response.statusCode !in 200..299) {
                return failed(
                    manifest,
                    request,
                    "download_http_${response.statusCode}",
                    startBytes,
                    clearPartial = !isRetryableHttpStatus(response.statusCode),
                )
            }
            val contentType = response.contentType?.lowercase().orEmpty()
            if (contentType.startsWith("text/html") || contentType.startsWith("application/json")) {
                return failed(manifest, request, "download_non_archive_response", 0L, clearPartial = true)
            }
            val responseLength = response.contentLength
            if (responseLength != null && responseLength >= 0L && startBytes + responseLength > manifest.archiveSizeBytes) {
                return failed(manifest, request, "download_size_exceeds_manifest", startBytes, clearPartial = true)
            }
            FileOutputStream(paths.archivePart, startBytes > 0L).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var written = startBytes
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = response.body.read(buffer)
                    if (count < 0) break
                    if (count == 0) throw java.io.IOException("download_zero_read")
                    written += count
                    if (written > manifest.archiveSizeBytes) {
                        return failed(manifest, request, "download_size_exceeds_manifest", written, clearPartial = true)
                    }
                    output.write(buffer, 0, count)
                    progressListener(DownloadProgress(written, manifest.archiveSizeBytes, resumed))
                }
                output.fd.sync()
                if (written != manifest.archiveSizeBytes) {
                    return failed(manifest, request, "download_incomplete", written)
                }
                if (!hasZipSignature(paths.archivePart)) {
                    return failed(manifest, request, "download_not_zip", written, clearPartial = true)
                }
                return ArtifactDownloadResult.Completed(paths.archivePart, written, resumed)
            }
        } catch (cancelled: CancellationException) {
            // Keep the part and metadata for the same manifest/source resume boundary.
            throw cancelled
        } catch (error: Exception) {
            // Exception messages can contain transient URLs supplied by the source.
            Log.w(TAG, "download_exception type=${error::class.java.simpleName}")
            return failed(manifest, request, "download_io_failed", paths.archivePart.length())
        } finally {
            response?.close()
        }
    }

    private fun failed(
        manifest: ArtifactManifest,
        request: ResolvedDownloadRequest,
        reasonCode: String,
        partialBytes: Long = 0L,
        clearPartial: Boolean = false,
    ): ArtifactDownloadResult.Failed {
        Log.w(TAG, "download_failed reason=$reasonCode partial=$partialBytes")
        if (clearPartial) {
            cache.clearArtifact(manifest)
        }
        return ArtifactDownloadResult.Failed(
            failure = ArtifactFailure(
                phase = ArtifactFailurePhase.DOWNLOAD,
                componentId = manifest.componentId,
                sourceKind = request.sourceKind,
                reasonCode = reasonCode,
                retryable = isRetryableFailure(reasonCode),
            ),
            partialBytes = partialBytes,
        )
    }

    private fun hasZipSignature(file: File): Boolean {
        if (!file.isFile || file.length() < 4L) return false
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                var offset = 0
                while (offset < header.size) {
                    val count = input.read(header, offset, header.size - offset)
                    if (count <= 0) return@runCatching false
                    offset += count
                }
                header[0] == 0x50.toByte() &&
                    header[1] == 0x4b.toByte() &&
                    ((header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) ||
                        (header[2] == 0x05.toByte() && header[3] == 0x06.toByte()) ||
                        (header[2] == 0x07.toByte() && header[3] == 0x08.toByte()))
            }
        }.getOrDefault(false)
    }

    private fun isRetryableFailure(reasonCode: String): Boolean =
        reasonCode == "download_incomplete" ||
            reasonCode == "download_io_failed" ||
            reasonCode == "download_range_response_invalid" ||
            reasonCode.startsWith("download_http_408") ||
            reasonCode.startsWith("download_http_425") ||
            reasonCode.startsWith("download_http_429") ||
            reasonCode.startsWith("download_http_5")

    private fun isRetryableHttpStatus(statusCode: Int): Boolean =
        statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500

    private companion object {
        const val TAG = "03helper.Download"
    }
}

data class DynamicArchiveDownload(
    val file: File,
    val sizeBytes: Long,
    val sha256: String,
)

data class DynamicDownloadProgress(
    val bytesWritten: Long,
    val expectedBytes: Long?,
)

sealed interface DynamicArchiveDownloadResult {
    data class Completed(val archive: DynamicArchiveDownload) : DynamicArchiveDownloadResult
    data class Failed(val reasonCode: String, val retryable: Boolean) : DynamicArchiveDownloadResult
}
