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
import com.tcrrry.helper.domain.session.FailureCategory
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
    data class Prepared(val artifacts: List<PreparedArtifact>) : ArtifactPreparationResult
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
                    failBatch(prepared, failure)
                    return ArtifactPreparationResult.Failed(failure)
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
                    prepared.forEach { cache.clearArtifact(it.manifest) }
                    return ArtifactPreparationResult.Failed(failure)
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
        )
    }

    private suspend fun prepareFromSource(
        manifest: ArtifactManifest,
        source: ArtifactSource,
    ): AttemptResult {
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

        val download = when (val result = downloader.download(manifest, request)) {
            is ArtifactDownloadResult.Completed -> result
            is ArtifactDownloadResult.Failed -> return AttemptResult.Failed(
                result.failure.copy(
                    componentId = manifest.componentId,
                    sourceKind = source.kind,
                ),
            )
        }
        val verifiedArchive = when (val result = archiveVerifier.verify(manifest, download.archivePart)) {
            is ArchiveIdentityResult.Verified -> result.archive
            is ArchiveIdentityResult.Failed -> {
                cache.clearArtifact(manifest)
                return AttemptResult.Failed(result.failure.copy(sourceKind = source.kind))
            }
        }
        val paths = cache.paths(manifest)
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
}
