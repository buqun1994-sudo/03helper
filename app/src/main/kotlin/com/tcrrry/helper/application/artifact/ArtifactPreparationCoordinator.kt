package com.tcrrry.helper.application.artifact

import com.tcrrry.helper.data.artifact.ArchiveExtractionResult
import com.tcrrry.helper.data.artifact.ArchiveIdentityResult
import com.tcrrry.helper.data.artifact.ArchiveIdentityVerifier
import com.tcrrry.helper.data.artifact.ArtifactArchiveExtractor
import com.tcrrry.helper.data.artifact.ArtifactIdentityResult
import com.tcrrry.helper.data.artifact.ArtifactIdentityVerifier
import com.tcrrry.helper.data.artifact.ExtractedApk
import com.tcrrry.helper.data.artifact.VerifiedApk
import com.tcrrry.helper.data.artifact.VerifiedArchive
import com.tcrrry.helper.data.download.ArtifactCache
import com.tcrrry.helper.data.download.ArtifactDownloadResult
import com.tcrrry.helper.data.download.ArtifactDownloader
import com.tcrrry.helper.data.web.LanzouResolutionResult
import com.tcrrry.helper.data.web.LanzouWebSourceAdapter
import com.tcrrry.helper.domain.artifact.ArchiveDownloadEvidence
import com.tcrrry.helper.domain.artifact.ArchiveVerificationEvidence
import com.tcrrry.helper.domain.artifact.ApkExtractionEvidence
import com.tcrrry.helper.domain.artifact.ArtifactFailure
import com.tcrrry.helper.domain.artifact.ArtifactFailurePhase
import com.tcrrry.helper.domain.artifact.ArtifactManifest
import com.tcrrry.helper.domain.artifact.ArtifactManifestValidator
import com.tcrrry.helper.domain.artifact.ArtifactSource
import com.tcrrry.helper.domain.artifact.ArtifactSourceKind
import com.tcrrry.helper.domain.artifact.ManifestValidation
import com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy
import com.tcrrry.helper.domain.artifact.ResolvedDownloadRequest
import com.tcrrry.helper.domain.artifact.SourcePlan
import com.tcrrry.helper.domain.artifact.SourceSelectionEvidence
import com.tcrrry.helper.domain.device.ApkDeclarationMetadata
import com.tcrrry.helper.domain.session.ComponentCheck
import com.tcrrry.helper.domain.session.ComponentProgressStatus
import com.tcrrry.helper.domain.session.FailureCategory
import com.tcrrry.helper.domain.session.InstallPhase
import com.tcrrry.helper.domain.session.InstallationSessionEvent
import java.io.File
import kotlinx.coroutines.CancellationException

data class PreparedArtifact(
    val manifest: ArtifactManifest,
    val sourceKind: ArtifactSourceKind,
    val finalApk: File,
    val declarations: ApkDeclarationMetadata? = null,
)

sealed interface ArtifactPreparationResult {
    data class Prepared(
        val artifacts: List<PreparedArtifact>,
        val failures: List<ArtifactFailure> = emptyList(),
    ) : ArtifactPreparationResult
    data class Failed(val failure: ArtifactFailure) : ArtifactPreparationResult
}

/**
 * Runs the fixed source attempts and publishes only structured evidence to the
 * single InstallationSession. File paths never cross the event port.
 */
class ArtifactPreparationCoordinator(
    private val sourcePolicy: ReleaseSourcePolicy,
    private val lanzouSourceAdapter: LanzouWebSourceAdapter,
    private val downloader: ArtifactDownloader,
    private val archiveVerifier: ArchiveIdentityVerifier,
    private val archiveExtractor: ArtifactArchiveExtractor,
    private val identityVerifier: ArtifactIdentityVerifier,
    private val cache: ArtifactCache,
    private val eventPort: ArtifactSessionEventPort,
) {
    suspend fun prepare(manifests: List<ArtifactManifest>): ArtifactPreparationResult {
        when (val validation = ArtifactManifestValidator.validateCatalog(manifests)) {
            is ManifestValidation.Invalid -> {
                val failure = ArtifactFailure(
                    phase = ArtifactFailurePhase.CATALOG,
                    reasonCode = validation.reasonCode,
                    retryable = false,
                )
                eventPort.emit(
                    InstallationSessionEvent.FatalError(
                        category = FailureCategory.VERIFICATION,
                        reasonCode = failure.reasonCode,
                    ),
                )
                return ArtifactPreparationResult.Failed(failure)
            }

            ManifestValidation.Valid -> Unit
        }

        val prepared = mutableListOf<AttemptSuccess>()
        val failures = mutableListOf<ArtifactFailure>()
        try {
            manifests.forEach { manifest ->
                val plan = sourcePolicy.plan(manifest)
                if (plan is SourcePlan.Rejected) {
                    val failure = ArtifactFailure(
                        phase = ArtifactFailurePhase.SOURCE_RESOLUTION,
                        componentId = manifest.componentId,
                        reasonCode = plan.reasonCode,
                        retryable = false,
                    )
                    if (manifest.componentId == DESKTOP_COMPONENT_ID) {
                        failBatch(prepared, failure)
                        return ArtifactPreparationResult.Failed(failure)
                    }
                    failures += failure
                    eventPort.emit(
                        InstallationSessionEvent.ArtifactUnavailable(
                            componentId = manifest.componentId,
                            reasonCode = failure.reasonCode,
                            sourceKind = failure.sourceKind,
                        ),
                    )
                    return@forEach
                }
                val sources = (plan as SourcePlan.Accepted).sources
                var success: AttemptSuccess? = null
                var lastFailure: ArtifactFailure? = null
                sources.forEachIndexed { index, source ->
                    if (success != null) return@forEachIndexed
                    when (val attempt = prepareFromSource(manifest, source)) {
                        is AttemptResult.Success -> success = attempt.value
                        is AttemptResult.Failed -> {
                            lastFailure = attempt.failure
                            emitProgress(
                                manifest.componentId,
                                when (attempt.failure.phase) {
                                    ArtifactFailurePhase.DOWNLOAD,
                                    ArtifactFailurePhase.SOURCE_RESOLUTION,
                                    -> InstallPhase.FETCH
                                    else -> InstallPhase.CHECK
                                },
                                ComponentProgressStatus.FAILED,
                                indeterminate = false,
                            )
                            val terminal = index == sources.lastIndex
                            eventPort.emit(
                                InstallationSessionEvent.SourceFailed(
                                    componentId = manifest.componentId,
                                    sourceKind = source.kind,
                                    reasonCode = attempt.failure.reasonCode,
                                    retryable = attempt.failure.retryable,
                                    terminal = terminal,
                                ),
                            )
                            if (!terminal) cache.clearArtifact(manifest)
                        }
                    }
                }
                if (success == null) {
                    val failure = lastFailure ?: ArtifactFailure(
                        phase = ArtifactFailurePhase.SOURCE_RESOLUTION,
                        componentId = manifest.componentId,
                        reasonCode = "all_sources_failed",
                        retryable = true,
                    )
                    if (manifest.componentId == DESKTOP_COMPONENT_ID) {
                        prepared.forEach { cache.clearArtifact(it.manifest) }
                        return ArtifactPreparationResult.Failed(failure)
                    }
                    failures += failure
                    eventPort.emit(
                        InstallationSessionEvent.ArtifactUnavailable(
                            componentId = manifest.componentId,
                            reasonCode = failure.reasonCode,
                            sourceKind = failure.sourceKind,
                        ),
                    )
                    return@forEach
                }
                prepared += success!!
            }
        } catch (cancelled: CancellationException) {
            eventPort.emit(
                InstallationSessionEvent.RecoverableError(
                    category = FailureCategory.DOWNLOAD,
                    reasonCode = "artifact_preparation_cancelled",
                ),
            )
            throw cancelled
        }

        emitBatchEvidence(prepared)
        return ArtifactPreparationResult.Prepared(
            prepared.map { success ->
                PreparedArtifact(
                    manifest = success.manifest,
                    sourceKind = success.sourceKind,
                    finalApk = success.verifiedApk.file,
                    declarations = success.verifiedApk.metadata?.declarations,
                )
            },
            failures = failures,
        )
    }

    private suspend fun prepareFromSource(
        manifest: ArtifactManifest,
        source: ArtifactSource,
    ): AttemptResult {
        emitProgress(manifest.componentId, InstallPhase.FETCH, ComponentProgressStatus.RUNNING)
        val paths = cache.paths(manifest)
        var cachedArchive: VerifiedArchive? = null
        var download: ArtifactDownloadResult.Completed? = null

        // The selected-catalog pass may already have downloaded this exact ZIP
        // to the same cache key. Verify that complete part before resolving a
        // second short-lived URL; this keeps one user action to one download
        // while retaining the normal archive and APK identity gates below.
        val hasCompleteCachedArchive = runCatching {
            cache.resumeMetadataMatches(manifest, source.kind.wireName) &&
                paths.archivePart.length() == manifest.archiveSizeBytes
        }.getOrDefault(false)
        if (hasCompleteCachedArchive) {
            cachedArchive = when (val result = archiveVerifier.verify(manifest, paths.archivePart)) {
                is ArchiveIdentityResult.Verified -> result.archive
                is ArchiveIdentityResult.Failed -> {
                    cache.clearArtifact(manifest)
                    null
                }
            }
            if (cachedArchive != null) {
                download = ArtifactDownloadResult.Completed(
                    archivePart = paths.archivePart,
                    sizeBytes = manifest.archiveSizeBytes,
                    resumed = true,
                )
                emitProgress(
                    componentId = manifest.componentId,
                    phase = InstallPhase.FETCH,
                    status = ComponentProgressStatus.RUNNING,
                    bytesWritten = manifest.archiveSizeBytes,
                    totalBytes = manifest.archiveSizeBytes,
                    indeterminate = false,
                )
            }
        }

        if (download == null) {
            val request = when (source.kind) {
                ArtifactSourceKind.LANZOU_SHARE -> when (val resolution = lanzouSourceAdapter.resolve(source)) {
                    is LanzouResolutionResult.Success -> resolution.request
                    is LanzouResolutionResult.Failure -> return AttemptResult.Failed(
                        resolution.failure.copy(componentId = manifest.componentId),
                    )
                }

                ArtifactSourceKind.R2,
                ArtifactSourceKind.GITHUB_RELEASES,
                -> ResolvedDownloadRequest(
                    sourceKind = source.kind,
                    url = source.url,
                )
            }

            download = when (val result = downloader.download(
                manifest,
                request,
                progressListener = { progress ->
                    emitProgress(
                        componentId = manifest.componentId,
                        phase = InstallPhase.FETCH,
                        status = ComponentProgressStatus.RUNNING,
                        bytesWritten = progress.bytesWritten,
                        totalBytes = progress.expectedBytes,
                        indeterminate = false,
                    )
                },
            )) {
                is ArtifactDownloadResult.Completed -> result
                is ArtifactDownloadResult.Failed -> return AttemptResult.Failed(
                    result.failure.copy(
                        componentId = manifest.componentId,
                        sourceKind = source.kind,
                    ),
                )
            }
        }
        val completedDownload = checkNotNull(download)
        emitProgress(
            manifest.componentId,
            InstallPhase.FETCH,
            ComponentProgressStatus.COMPLETED,
            bytesWritten = completedDownload.sizeBytes,
            totalBytes = manifest.archiveSizeBytes,
            indeterminate = false,
        )
        emitProgress(manifest.componentId, InstallPhase.CHECK, ComponentProgressStatus.RUNNING)
        val verifiedArchive = cachedArchive ?: when (val result = archiveVerifier.verify(manifest, completedDownload.archivePart)) {
            is ArchiveIdentityResult.Verified -> result.archive
            is ArchiveIdentityResult.Failed -> {
                cache.clearArtifact(manifest)
                return AttemptResult.Failed(result.failure.copy(sourceKind = source.kind))
            }
        }
        val extractedApk = when (val result = archiveExtractor.extract(manifest, verifiedArchive, paths.apkPart)) {
            is ArchiveExtractionResult.Extracted -> result.apk
            is ArchiveExtractionResult.Failed -> {
                cache.clearArtifact(manifest)
                return AttemptResult.Failed(result.failure.copy(sourceKind = source.kind))
            }
        }
        val verifiedApk = when (
            val result = identityVerifier.verify(
                manifest = manifest,
                sourceKind = source.kind,
                verifiedArchive = verifiedArchive,
                extractedApk = extractedApk,
                finalApk = paths.apk,
            )
        ) {
            is ArtifactIdentityResult.Verified -> result.apk
            is ArtifactIdentityResult.Failed -> {
                cache.clearArtifact(manifest)
                return AttemptResult.Failed(result.failure.copy(sourceKind = source.kind))
            }
        }
        cache.clearArchive(manifest)
        emitProgress(
            manifest.componentId,
            InstallPhase.CHECK,
            ComponentProgressStatus.COMPLETED,
            bytesWritten = manifest.archiveSizeBytes,
            totalBytes = manifest.archiveSizeBytes,
            indeterminate = false,
        )
        return AttemptResult.Success(
            AttemptSuccess(
                manifest = manifest,
                sourceKind = source.kind,
                downloaded = download,
                verifiedArchive = verifiedArchive,
                extractedApk = extractedApk,
                verifiedApk = verifiedApk,
            ),
        )
    }

    private fun emitProgress(
        componentId: String,
        phase: InstallPhase,
        status: ComponentProgressStatus,
        bytesWritten: Long = 0L,
        totalBytes: Long = 0L,
        indeterminate: Boolean = true,
    ) {
        eventPort.emit(
            InstallationSessionEvent.ComponentProgressUpdated(
                componentId = componentId,
                phase = phase,
                status = status,
                bytesWritten = bytesWritten,
                totalBytes = totalBytes,
                indeterminate = indeterminate,
            ),
        )
    }

    private fun emitBatchEvidence(prepared: List<AttemptSuccess>) {
        val selections = prepared.map { SourceSelectionEvidence(it.manifest.componentId, it.sourceKind) }
        val downloads = prepared.map {
            ArchiveDownloadEvidence(
                componentId = it.manifest.componentId,
                sizeBytes = it.verifiedArchive.sizeBytes,
                sha256 = it.verifiedArchive.sha256,
                resumed = it.downloaded.resumed,
            )
        }
        val archiveVerifications = prepared.map {
            ArchiveVerificationEvidence(
                componentId = it.manifest.componentId,
                sizeBytes = it.verifiedArchive.sizeBytes,
                sha256 = it.verifiedArchive.sha256,
            )
        }
        val extractions = prepared.map {
            ApkExtractionEvidence(
                componentId = it.manifest.componentId,
                entryName = it.extractedApk.entryName,
                sizeBytes = it.extractedApk.sizeBytes,
                sha256 = it.extractedApk.sha256,
            )
        }
        eventPort.emit(
            InstallationSessionEvent.SourceResolved(
                sourceId = "fixed-release-source-policy",
                selections = selections,
            ),
        )
        eventPort.emit(
            InstallationSessionEvent.ArchiveDownloaded(
                sizeBytes = downloads.sumOf { it.sizeBytes },
                sha256 = "batch",
                archives = downloads,
            ),
        )
        eventPort.emit(
            InstallationSessionEvent.ArchiveVerified(
                verified = true,
                verifications = archiveVerifications,
            ),
        )
        eventPort.emit(
            InstallationSessionEvent.ApkExtracted(
                entryName = "batch.apk",
                sizeBytes = extractions.sumOf { it.sizeBytes },
                sha256 = "batch",
                extractions = extractions,
            ),
        )
        eventPort.emit(
            InstallationSessionEvent.ArtifactsVerified(
                checks = prepared.map { ComponentCheck(it.manifest.componentId, passed = true) },
                verifications = prepared.map { it.verifiedApk.verification },
                archiveDeleted = true,
            ),
        )
    }

    private fun failBatch(prepared: List<AttemptSuccess>, failure: ArtifactFailure) {
        prepared.forEach { cache.clearArtifact(it.manifest) }
        eventPort.emit(
            InstallationSessionEvent.FatalError(
                category = FailureCategory.VERIFICATION,
                componentName = failure.componentId,
                reasonCode = failure.reasonCode,
            ),
        )
    }

    private data class AttemptSuccess(
        val manifest: ArtifactManifest,
        val sourceKind: ArtifactSourceKind,
        val downloaded: ArtifactDownloadResult.Completed,
        val verifiedArchive: VerifiedArchive,
        val extractedApk: ExtractedApk,
        val verifiedApk: VerifiedApk,
    )

    private sealed interface AttemptResult {
        data class Success(val value: AttemptSuccess) : AttemptResult
        data class Failed(val failure: ArtifactFailure) : AttemptResult
    }

    private companion object {
        const val DESKTOP_COMPONENT_ID = "desktop"
    }
}
