package com.tcrrry.helper.data.web

import android.util.Log
import com.tcrrry.helper.domain.artifact.ArtifactFailure
import com.tcrrry.helper.domain.artifact.ArtifactFailurePhase
import com.tcrrry.helper.domain.artifact.ArtifactSource
import com.tcrrry.helper.domain.artifact.ArtifactSourceKind
import com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy
import com.tcrrry.helper.domain.artifact.ResolvedDownloadRequest
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

fun interface LanzouWebViewHostFactory {
    fun create(): LanzouWebViewHost
}

interface LanzouWebViewHost {
    fun start(
        shareUrl: String,
        onDownload: (ResolvedDownloadRequest) -> Unit,
        onFailure: (ArtifactFailure) -> Unit,
    )

    fun stopAndDestroy()
}

sealed interface LanzouResolutionResult {
    data class Success(val request: ResolvedDownloadRequest) : LanzouResolutionResult
    data class Failure(val failure: ArtifactFailure) : LanzouResolutionResult
}

/** Converts one signed-configured Lanzou share page into an in-memory download request. */
class LanzouWebSourceAdapter(
    private val hostFactory: LanzouWebViewHostFactory,
    private val sourcePolicy: ReleaseSourcePolicy = ReleaseSourcePolicy(),
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    suspend fun resolve(source: ArtifactSource): LanzouResolutionResult {
        Log.d(TAG, "resolve_start kind=${source.kind}")
        if (source.kind != ArtifactSourceKind.LANZOU_SHARE) {
            return LanzouResolutionResult.Failure(
                ArtifactFailure(
                    phase = ArtifactFailurePhase.SOURCE_RESOLUTION,
                    sourceKind = source.kind,
                    reasonCode = "lanzou_source_kind_invalid",
                    retryable = false,
                ),
            )
        }
        val sourceValidation = sourcePolicy.validateManifestSource(source)
        if (sourceValidation is com.tcrrry.helper.domain.artifact.SourcePolicyValidation.Rejected) {
            return LanzouResolutionResult.Failure(
                ArtifactFailure(
                    phase = ArtifactFailurePhase.SOURCE_RESOLUTION,
                    sourceKind = source.kind,
                    reasonCode = sourceValidation.reasonCode,
                    retryable = false,
                ),
            )
        }

        val host = try {
            hostFactory.create()
        } catch (_: Exception) {
            return LanzouResolutionResult.Failure(
                ArtifactFailure(
                    phase = ArtifactFailurePhase.SOURCE_RESOLUTION,
                    sourceKind = source.kind,
                    reasonCode = "lanzou_webview_create_failed",
                    retryable = true,
                ),
            )
        }
        return try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    val completeFailure: (ArtifactFailure) -> Unit = { failure ->
                        if (continuation.isActive) continuation.resume(LanzouResolutionResult.Failure(failure))
                    }
                    val completeDownload: (ResolvedDownloadRequest) -> Unit = { request ->
                        if (continuation.isActive) {
                            continuation.resume(validateDownloadRequest(request))
                        }
                    }
                    try {
                        host.start(source.url, completeDownload, completeFailure)
                    } catch (_: Exception) {
                        completeFailure(
                            ArtifactFailure(
                                phase = ArtifactFailurePhase.SOURCE_RESOLUTION,
                                sourceKind = source.kind,
                                reasonCode = "lanzou_webview_start_failed",
                                retryable = true,
                            ),
                        )
                    }
                    continuation.invokeOnCancellation { host.stopAndDestroy() }
                }
            }
        } catch (_: TimeoutCancellationException) {
            LanzouResolutionResult.Failure(
                ArtifactFailure(
                    phase = ArtifactFailurePhase.SOURCE_RESOLUTION,
                    sourceKind = source.kind,
                    reasonCode = "lanzou_parse_timeout",
                    retryable = true,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            host.stopAndDestroy()
        }
    }

    private fun validateDownloadRequest(request: ResolvedDownloadRequest): LanzouResolutionResult {
        if (request.sourceKind != ArtifactSourceKind.LANZOU_SHARE) {
            return LanzouResolutionResult.Failure(
                ArtifactFailure(
                    phase = ArtifactFailurePhase.SOURCE_RESOLUTION,
                    sourceKind = request.sourceKind,
                    reasonCode = "lanzou_callback_source_invalid",
                    retryable = false,
                ),
            )
        }
        if (request.mimeType?.lowercase()?.startsWith("text/html") == true) {
            return LanzouResolutionResult.Failure(
                ArtifactFailure(
                    phase = ArtifactFailurePhase.SOURCE_RESOLUTION,
                    sourceKind = request.sourceKind,
                    reasonCode = "lanzou_html_response",
                    retryable = true,
                ),
            )
        }
        val validation = sourcePolicy.validateResolvedRequest(request)
        return when (validation) {
            com.tcrrry.helper.domain.artifact.SourcePolicyValidation.Accepted ->
                if (request.userAgent.isNullOrBlank()) {
                    LanzouResolutionResult.Failure(
                        ArtifactFailure(
                            phase = ArtifactFailurePhase.SOURCE_RESOLUTION,
                            sourceKind = request.sourceKind,
                            reasonCode = "lanzou_user_agent_missing",
                            retryable = true,
                        ),
                    )
                } else {
                    LanzouResolutionResult.Success(request)
                }

            is com.tcrrry.helper.domain.artifact.SourcePolicyValidation.Rejected ->
                LanzouResolutionResult.Failure(
                    ArtifactFailure(
                        phase = ArtifactFailurePhase.SOURCE_RESOLUTION,
                        sourceKind = request.sourceKind,
                        reasonCode = validation.reasonCode,
                        retryable = true,
                    ),
                )
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
        private const val TAG = "03helper.Lanzou"
    }
}
