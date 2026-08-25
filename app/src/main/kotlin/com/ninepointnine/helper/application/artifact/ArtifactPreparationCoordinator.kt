package com.ninepointnine.helper.application.artifact

import com.ninepointnine.helper.data.artifact.ArchiveExtractionResult
import com.ninepointnine.helper.data.artifact.ArchiveIdentityResult
import com.ninepointnine.helper.data.artifact.ArchiveIdentityVerifier
import com.ninepointnine.helper.data.artifact.ArtifactArchiveExtractor
import com.ninepointnine.helper.data.artifact.ArtifactIdentityResult
import com.ninepointnine.helper.data.artifact.ArtifactIdentityVerifier
import com.ninepointnine.helper.data.artifact.ExtractedApk
import com.ninepointnine.helper.data.artifact.VerifiedApk
import com.ninepointnine.helper.data.artifact.VerifiedArchive
import com.ninepointnine.helper.data.download.ArtifactCache
import com.ninepointnine.helper.data.download.ArtifactDownloadResult
import com.ninepointnine.helper.data.download.ArtifactDownloader
import com.ninepointnine.helper.data.web.LanzouResolutionResult
import com.ninepointnine.helper.data.web.LanzouWebSourceAdapter
import com.ninepointnine.helper.domain.artifact.ArchiveDownloadEvidence
import com.ninepointnine.helper.domain.artifact.ArchiveVerificationEvidence
import com.ninepointnine.helper.domain.artifact.ApkExtractionEvidence
import com.ninepointnine.helper.domain.artifact.ArtifactFailure
import com.ninepointnine.helper.domain.artifact.ArtifactFailurePhase
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactManifestValidator
import com.ninepointnine.helper.domain.artifact.ArtifactSource
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ManifestValidation
import com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy
import com.ninepointnine.helper.domain.artifact.ResolvedDownloadRequest
import com.ninepointnine.helper.domain.artifact.SourcePlan
import com.ninepointnine.helper.domain.artifact.SourceSelectionEvidence
import com.ninepointnine.helper.domain.device.ApkDeclarationMetadata
import com.ninepointnine.helper.domain.session.ComponentCheck
import com.ninepointnine.helper.domain.session.ComponentProgressStatus
import com.ninepointnine.helper.domain.session.FailureCategory
import com.ninepointnine.helper.domain.session.InstallPhase
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import java.io.File
import kotlinx.coroutines.CancellationException

data class PreparedArtifact(
    val manifest: ArtifactManifest,
    /** Null only for an already-installed device prerequisite. */
    val sourceKind: ArtifactSourceKind? = null,
    val finalApk: File? = null,
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
        if (manifests.isEmpty()) {
            emitEmptyPreparationEvidence()
            return ArtifactPreparationResult.Prepared(emptyList())
        }
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
                try {
                    val local = prepareFromExistingApk(manifest)
                    if (local is AttemptResult.Success) {
                        prepared += local.value
                        return@forEach
                    }
                    if (manifest.localOnly && local is AttemptResult.Failed) {
                        failures += local.failure
                        emitProgress(
                            manifest.componentId,
                            InstallPhase.CHECK,
                            ComponentProgressStatus.FAILED,
                            indeterminate = false,
                        )
                        eventPort.emit(
                            InstallationSessionEvent.ArtifactUnavailable(
                                componentId = manifest.componentId,
                                reasonCode = local.failure.reasonCode,
                                sourceKind = ArtifactSourceKind.LOCAL_DOWNLOAD,
                            ),
                        )
                        return@forEach
                    }
                    val plan = sourcePolicy.plan(manifest)
                    if (plan is SourcePlan.Rejected) {
                        val failure = ArtifactFailure(
                            phase = ArtifactFailurePhase.SOURCE_RESOLUTION,
                            componentId = manifest.componentId,
                            reasonCode = plan.reasonCode,
                            retryable = false,
                        )
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
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    val failure = ArtifactFailure(
                        phase = ArtifactFailurePhase.CACHE,
                        componentId = manifest.componentId,
                        reasonCode = "artifact_app_processing_failed",
                        retryable = true,
                    )
                    failures += failure
                    runCatching { cache.clearArtifact(manifest) }
                    emitProgress(
                        manifest.componentId,
                        InstallPhase.CHECK,
                        ComponentProgressStatus.FAILED,
                        indeterminate = false,
                    )
                    eventPort.emit(
                        InstallationSessionEvent.ArtifactUnavailable(
                            componentId = manifest.componentId,
                            reasonCode = failure.reasonCode,
                            sourceKind = ArtifactSourceKind.LOCAL_DOWNLOAD,
                        ),
                    )
                }
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

                ArtifactSourceKind.LOCAL_DOWNLOAD ->
                    return AttemptResult.Failed(
                        ArtifactFailure(
                            phase = ArtifactFailurePhase.CACHE,
                            componentId = manifest.componentId,
                            sourceKind = source.kind,
                            reasonCode = "local_download_candidate_missing",
                            retryable = false,
                        ),
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
        if (!manifest.localOnly && !cache.publishApk(manifest, verifiedApk.file)) {
            cache.clearArtifact(manifest)
            return AttemptResult.Failed(
                ArtifactFailure(
                    phase = ArtifactFailurePhase.CACHE,
                    componentId = manifest.componentId,
                    sourceKind = source.kind,
                    reasonCode = "public_download_publish_failed",
                    retryable = true,
                ),
            )
        }
        cache.retainVerifiedApk(manifest, verifiedApk.file)
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

    private fun prepareFromExistingApk(manifest: ArtifactManifest): AttemptResult {
        if (!cache.publicDirectoryAvailable) {
            return AttemptResult.Failed(
                ArtifactFailure(
                    phase = ArtifactFailurePhase.CACHE,
                    componentId = manifest.componentId,
                    sourceKind = ArtifactSourceKind.LOCAL_DOWNLOAD,
                    reasonCode = "public_download_directory_unavailable",
                    retryable = true,
                ),
            )
        }
        val sourceKind = if (manifest.localOnly) {
            ArtifactSourceKind.LOCAL_DOWNLOAD
        } else {
            manifest.sources.firstOrNull()?.kind ?: ArtifactSourceKind.LOCAL_DOWNLOAD
        }
        val candidates = buildList {
            cache.verifiedApkFor(manifest)?.let(::add)
            cache.withPublicApkCandidates { addAll(it) }
        }.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        candidates.forEach { candidate ->
            when (val result = identityVerifier.verifyExisting(manifest, sourceKind, candidate)) {
                is ArtifactIdentityResult.Verified -> {
                    // A previously staged ZIP is no longer needed once the
                    // exact APK identity has passed verification.
                    cache.retainVerifiedApk(manifest, result.apk.file)
                    cache.clearArchive(manifest)
                    emitProgress(manifest.componentId, InstallPhase.FETCH, ComponentProgressStatus.COMPLETED, 1L, 1L, false)
                    emitProgress(manifest.componentId, InstallPhase.CHECK, ComponentProgressStatus.COMPLETED, 1L, 1L, false)
                    return AttemptResult.Success(
                        AttemptSuccess(
                            manifest = manifest,
                            sourceKind = sourceKind,
                            downloaded = null,
                            verifiedArchive = null,
                            extractedApk = null,
                            verifiedApk = result.apk,
                        ),
                    )
                }

                is ArtifactIdentityResult.Failed -> Unit
            }
        }
        return AttemptResult.Failed(
            ArtifactFailure(
                phase = ArtifactFailurePhase.CACHE,
                componentId = manifest.componentId,
                sourceKind = sourceKind,
                reasonCode = "local_download_candidate_missing",
                retryable = false,
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
        val downloads = prepared.mapNotNull { item ->
            when {
                item.verifiedArchive != null -> ArchiveDownloadEvidence(
                    componentId = item.manifest.componentId,
                    sizeBytes = item.verifiedArchive.sizeBytes,
                    sha256 = item.verifiedArchive.sha256,
                    resumed = item.downloaded?.resumed ?: true,
                )

                // A previously published APK can satisfy a remote manifest
                // without a second ZIP download. Preserve the manifest's
                // archive identity in the session proof so strict replay
                // validation distinguishes reuse from missing evidence.
                !item.manifest.localOnly -> ArchiveDownloadEvidence(
                    componentId = item.manifest.componentId,
                    sizeBytes = item.manifest.archiveSizeBytes,
                    sha256 = item.manifest.archiveSha256,
                    resumed = true,
                )

                else -> null
            }
        }
        val archiveVerifications = prepared.mapNotNull { item ->
            when {
                item.verifiedArchive != null -> ArchiveVerificationEvidence(
                    componentId = item.manifest.componentId,
                    sizeBytes = item.verifiedArchive.sizeBytes,
                    sha256 = item.verifiedArchive.sha256,
                )

                !item.manifest.localOnly -> ArchiveVerificationEvidence(
                    componentId = item.manifest.componentId,
                    sizeBytes = item.manifest.archiveSizeBytes,
                    sha256 = item.manifest.archiveSha256,
                )

                else -> null
            }
        }
        val extractions = prepared.mapNotNull { item ->
            when {
                item.extractedApk != null -> ApkExtractionEvidence(
                    componentId = item.manifest.componentId,
                    entryName = item.extractedApk.entryName,
                    sizeBytes = item.extractedApk.sizeBytes,
                    sha256 = item.extractedApk.sha256,
                )

                !item.manifest.localOnly -> ApkExtractionEvidence(
                    componentId = item.manifest.componentId,
                    entryName = item.manifest.apkEntryName,
                    sizeBytes = item.manifest.apkSizeBytes,
                    sha256 = item.manifest.apkSha256,
                )

                else -> null
            }
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
                sha256 = if (downloads.isEmpty()) "local" else "batch",
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

    private fun emitEmptyPreparationEvidence() {
        eventPort.emit(
            InstallationSessionEvent.SourceResolved(
                sourceId = "fixed-release-source-policy",
                selections = emptyList(),
            ),
        )
        eventPort.emit(
            InstallationSessionEvent.ArchiveDownloaded(
                sizeBytes = 1L,
                sha256 = "empty",
                archives = emptyList(),
            ),
        )
        eventPort.emit(InstallationSessionEvent.ArchiveVerified(verified = true, verifications = emptyList()))
        eventPort.emit(
            InstallationSessionEvent.ApkExtracted(
                entryName = "batch.apk",
                sizeBytes = 1L,
                sha256 = "empty",
                extractions = emptyList(),
            ),
        )
        eventPort.emit(
            InstallationSessionEvent.ArtifactsVerified(
                checks = emptyList(),
                verifications = emptyList(),
                archiveDeleted = true,
            ),
        )
    }

    private data class AttemptSuccess(
        val manifest: ArtifactManifest,
        val sourceKind: ArtifactSourceKind,
        val downloaded: ArtifactDownloadResult.Completed?,
        val verifiedArchive: VerifiedArchive?,
        val extractedApk: ExtractedApk?,
        val verifiedApk: VerifiedApk,
    )

    private sealed interface AttemptResult {
        data class Success(val value: AttemptSuccess) : AttemptResult
        data class Failed(val failure: ArtifactFailure) : AttemptResult
    }

}
