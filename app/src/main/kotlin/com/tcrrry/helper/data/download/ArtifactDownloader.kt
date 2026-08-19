package com.tcrrry.helper.data.download

import android.util.Log
import com.tcrrry.helper.domain.artifact.ArtifactFailure
import com.tcrrry.helper.domain.artifact.ArtifactFailurePhase
import com.tcrrry.helper.domain.artifact.ArtifactManifest
import com.tcrrry.helper.domain.artifact.ResolvedDownloadRequest
import com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy
import java.io.Closeable
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
) : Closeable {
    override fun close() {
        runCatching { body.close() }
        closeAction()
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
) {
    suspend fun download(
        manifest: ArtifactManifest,
        request: ResolvedDownloadRequest,
    ): ArtifactDownloadResult = withContext(Dispatchers.IO) {
        downloadInternal(manifest, request)
    }

    private suspend fun downloadInternal(
        manifest: ArtifactManifest,
        request: ResolvedDownloadRequest,
    ): ArtifactDownloadResult {
        val requestValidation = sourcePolicy.validateResolvedRequest(request)
        if (requestValidation is com.tcrrry.helper.domain.artifact.SourcePolicyValidation.Rejected) {
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
            if (response.statusCode == 416 && startBytes == manifest.archiveSizeBytes) {
                response.close()
                return ArtifactDownloadResult.Completed(paths.archivePart, startBytes, resumed = true)
            }
            if (response.statusCode !in 200..299) {
                return failed(manifest, request, "download_http_${response.statusCode}", startBytes)
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
                    written += count
                    if (written > manifest.archiveSizeBytes) {
                        return failed(manifest, request, "download_size_exceeds_manifest", written, clearPartial = true)
                    }
                    output.write(buffer, 0, count)
                    onProgress(DownloadProgress(written, manifest.archiveSizeBytes, resumed))
                }
                output.fd.sync()
                if (written != manifest.archiveSizeBytes) {
                    return failed(manifest, request, "download_incomplete", written)
                }
                return ArtifactDownloadResult.Completed(paths.archivePart, written, resumed)
            }
        } catch (cancelled: CancellationException) {
            // Keep the part and metadata for the same manifest/source resume boundary.
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "download_exception type=${error::class.java.simpleName} message=${error.message ?: "unknown"}")
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
                retryable = reasonCode == "download_incomplete" ||
                    reasonCode == "download_io_failed" ||
                    reasonCode.startsWith("download_http_5"),
            ),
            partialBytes = partialBytes,
        )
    }

    private companion object {
        const val TAG = "03helper.Download"
    }
}
