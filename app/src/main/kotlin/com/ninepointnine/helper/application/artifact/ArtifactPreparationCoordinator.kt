package com.ninepointnine.helper.application.artifact

import com.ninepointnine.helper.data.artifact.ArchiveExtractionResult
import com.ninepointnine.helper.data.artifact.ArchiveIdentityResult
import com.ninepointnine.helper.data.artifact.ArchiveIdentityVerifier
import com.ninepointnine.helper.data.artifact.ArtifactArchiveExtractor
import com.ninepointnine.helper.data.artifact.ArtifactIdentityResult
import com.ninepointnine.helper.data.artifact.ArtifactIdentityVerifier
import com.ninepointnine.helper.data.artifact.ApkMetadata
import com.ninepointnine.helper.data.artifact.ApkMetadataReader
import com.ninepointnine.helper.data.artifact.ExtractedApk
import com.ninepointnine.helper.data.artifact.VerifiedApk
import com.ninepointnine.helper.data.artifact.VerifiedArchive
import com.ninepointnine.helper.data.artifact.sha256
import com.ninepointnine.helper.data.catalog.ArtifactPreparationPlan
import com.ninepointnine.helper.data.catalog.InstallerComponentSource
import com.ninepointnine.helper.data.download.ArtifactCache
import com.ninepointnine.helper.data.download.ArtifactDownloadResult
import com.ninepointnine.helper.data.download.ArtifactDownloader
import com.ninepointnine.helper.data.download.DynamicArchiveDownloadResult
import com.ninepointnine.helper.data.web.LanzouFolderResolutionResult
import com.ninepointnine.helper.data.web.LanzouFolderSourceAdapter
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
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.CompatibilityRange
import com.ninepointnine.helper.domain.artifact.InstallerComponentTrustRegistry
import com.ninepointnine.helper.domain.artifact.InstallerPublisherTrustRegistry
import com.ninepointnine.helper.domain.artifact.InstallerSelfIdentity
import com.ninepointnine.helper.domain.artifact.ManifestValidation
import com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy
import com.ninepointnine.helper.domain.artifact.SourcePlan
import com.ninepointnine.helper.domain.artifact.SourceSelectionEvidence
import com.ninepointnine.helper.domain.device.ApkDeclarationMetadata
import com.ninepointnine.helper.domain.device.AuthorizationPlanFactory
import com.ninepointnine.helper.domain.device.ManagedComponent
import com.ninepointnine.helper.domain.session.ComponentProgressStatus
import com.ninepointnine.helper.domain.session.InstallPhase
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import com.ninepointnine.helper.application.session.InstallationSessionBoundary
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream
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
    private val eventPort: InstallationSessionBoundary,
    private val folderSourceAdapter: LanzouFolderSourceAdapter,
    private val metadataReader: ApkMetadataReader,
) {
    /**
     * Production entry point. A plan is prepared exactly once: public Download
     * is checked first, and only unresolved components open the configured
     * Lanzou folder. The result crosses the session boundary as one atomic
     * [InstallationSessionEvent.ArtifactBatchPrepared] event.
     */
    suspend fun prepare(plan: ArtifactPreparationPlan): ArtifactPreparationResult =
        prepareInternal(plan)

    private suspend fun prepareInternal(plan: ArtifactPreparationPlan): ArtifactPreparationResult {
        val expectedIds = plan.batch.preparationComponentIds
        if (plan.components.map { it.componentId }.toSet() != expectedIds) {
            val failure = ArtifactFailure(
                phase = ArtifactFailurePhase.CATALOG,
                reasonCode = "artifact_preparation_plan_component_set_invalid",
                retryable = false,
            )
            return ArtifactPreparationResult.Failed(failure)
        }
        if (!eventPort.isArtifactPreparationActive(plan.batch.batchId)) {
            return ArtifactPreparationResult.Prepared(emptyList())
        }

        // Remove private APK material left by a previous process while keeping
        // valid fixed-download resume pairs owned by the cache.
        cache.clearPrivateApkCopies()
        val localCandidates = if (cache.publicDirectoryAvailable) {
            cache.refreshPublicApkCandidates()
        } else {
            emptyList()
        }
        val prepared = mutableListOf<PlanAttemptSuccess>()
        val failures = mutableListOf<ArtifactFailure>()
        val unresolved = mutableListOf<InstallerComponentSource>()

        // One immutable candidate snapshot is shared by every component. A
        // non-matching APK is a miss and never blocks the remote source.
        plan.components.forEach { component ->
            val candidate = localCandidates.asSequence()
                .mapNotNull { file -> reusableLocalCandidate(plan.config, component, file, metadataReader) }
                .sortedWith(
                    compareByDescending<LocalCandidate> {
                        it.identity.track == InstallerPublisherTrustRegistry.trackFor(
                            plan.config.environment,
                            plan.config.channel,
                        )
                    }
                        .thenByDescending { it.metadata.version.code }
                        .thenByDescending { it.file.lastModified() }
                        .thenBy { it.file.name },
                )
                .firstOrNull()
            if (candidate == null) {
                unresolved += component
                return@forEach
            }
            when (val result = prepareFromLocalCandidate(plan.config, component, candidate)) {
                is PlanAttemptResult.Success -> prepared += result.value
                is PlanAttemptResult.Failed -> {
                    // A candidate can become unreadable between the inventory
                    // scan and the identity read. Treat that as a remote miss
                    // so the signed source can be resolved again.
                    unresolved += component
                }
            }
        }

        val folderArtifacts: Map<String, com.ninepointnine.helper.data.web.LanzouFolderArtifact>
        val folderFailures: Map<String, ArtifactFailure>
        if (unresolved.isEmpty()) {
            folderArtifacts = emptyMap()
            folderFailures = emptyMap()
        } else {
            when (val resolved = folderSourceAdapter.resolve(
                plan.config,
                unresolved.mapTo(linkedSetOf()) { it.componentId },
            )) {
                is LanzouFolderResolutionResult.Success -> {
                    folderArtifacts = resolved.artifacts.associateBy { it.component.componentId }
                    folderFailures = resolved.appFailures.associate {
                        it.componentId to ArtifactFailure(
                            phase = ArtifactFailurePhase.SOURCE_RESOLUTION,
                            componentId = it.componentId,
                            sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                            reasonCode = it.reasonCode,
                            retryable = it.retryable,
                        )
                    }
                }

                is LanzouFolderResolutionResult.Failure -> {
                    folderArtifacts = emptyMap()
                    folderFailures = unresolved.associate { component ->
                        component.componentId to resolved.failure.copy(componentId = component.componentId)
                    }
                }

            }
        }

        unresolved.forEach { component ->
            if (!eventPort.isArtifactPreparationActive(plan.batch.batchId)) return@forEach
            val artifact = folderArtifacts[component.componentId]
            val attempt = if (artifact == null) {
                PlanAttemptResult.Failed(
                    folderFailures[component.componentId] ?: ArtifactFailure(
                        phase = ArtifactFailurePhase.SOURCE_RESOLUTION,
                        componentId = component.componentId,
                        sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                        reasonCode = "lanzou_folder_missing_${component.componentId}",
                        retryable = false,
                    ),
                )
            } else {
                prepareFromDynamicFolderArtifact(plan.config, artifact)
            }
            when (attempt) {
                is PlanAttemptResult.Success -> prepared += attempt.value
                is PlanAttemptResult.Failed -> failures += attempt.failure
            }
        }

        val sourceSelections = prepared.map { item ->
            SourceSelectionEvidence(item.manifest.componentId, item.sourceKind)
        }
        val archives = prepared.mapNotNull { item ->
            item.verifiedArchive?.let { archive ->
                ArchiveDownloadEvidence(
                    componentId = item.manifest.componentId,
                    sizeBytes = archive.sizeBytes,
                    sha256 = archive.sha256,
                    resumed = item.downloaded?.resumed ?: false,
                )
            }
        }
        val archiveVerifications = prepared.mapNotNull { item ->
            item.verifiedArchive?.let { archive ->
                ArchiveVerificationEvidence(
                    componentId = item.manifest.componentId,
                    sizeBytes = archive.sizeBytes,
                    sha256 = archive.sha256,
                )
            }
        }
        val extractions = prepared.mapNotNull { item ->
            item.extractedApk?.let { apk ->
                ApkExtractionEvidence(
                    componentId = item.manifest.componentId,
                    entryName = apk.entryName,
                    sizeBytes = apk.sizeBytes,
                    sha256 = apk.sha256,
                )
            }
        }
        val verifications = prepared.map { it.verifiedApk.verification }
        if (eventPort.isArtifactPreparationActive(plan.batch.batchId)) {
            eventPort.emit(
                InstallationSessionEvent.ArtifactBatchPrepared(
                    batchId = plan.batch.batchId,
                    manifests = prepared.map { it.manifest },
                    sourceSelections = sourceSelections,
                    archives = archives,
                    archiveVerifications = archiveVerifications,
                    extractions = extractions,
                    verifications = verifications,
                    failures = failures,
                ),
            )
        }
        return ArtifactPreparationResult.Prepared(
            artifacts = prepared.map { item ->
                PreparedArtifact(
                    manifest = item.manifest,
                    sourceKind = item.sourceKind,
                    finalApk = item.verifiedApk.file,
                    declarations = item.verifiedApk.metadata?.declarations,
                )
            },
            failures = failures,
        )
    }

    private fun reusableLocalCandidate(
        config: com.ninepointnine.helper.data.catalog.InstallerDistributionConfig,
        component: InstallerComponentSource,
        file: File,
        reader: ApkMetadataReader,
    ): LocalCandidate? {
        val metadata = runCatching { reader.read(file) }.getOrNull() ?: return null
        if (component.packageName.isNotBlank() && metadata.packageName != component.packageName) return null
        if (InstallerSelfIdentity.isSelfComponentId(component.componentId) &&
            metadata.packageName != InstallerSelfIdentity.PACKAGE_NAME
        ) return null
        if (InstallerComponentTrustRegistry.get(component.componentId) != null &&
            !InstallerComponentTrustRegistry.isAllowedPackageName(component.componentId, metadata.packageName)
        ) return null
        if (!InstallerPublisherTrustRegistry.isKnownProfile(component.trustProfileId)) return null
        if (component.certificateSha256.isNotBlank() && metadata.certificateSha256s.none {
                it.equals(component.certificateSha256, ignoreCase = true)
            }
        ) return null
        val identity = InstallerPublisherTrustRegistry.matchComponentIdentity(
            componentId = component.componentId,
            profileId = component.trustProfileId,
            environment = config.environment,
            channel = config.channel,
            packageName = metadata.packageName,
            certificateDigests = metadata.certificateSha256s,
        ) ?: return null
        return LocalCandidate(file, metadata, identity)
    }

    private fun prepareFromLocalCandidate(
        config: com.ninepointnine.helper.data.catalog.InstallerDistributionConfig,
        component: InstallerComponentSource,
        candidate: LocalCandidate,
    ): PlanAttemptResult {
        val manifest = ArtifactManifest(
            schemaVersion = ArtifactManifestValidator.SUPPORTED_SCHEMA_VERSION,
            componentId = component.componentId,
            displayName = component.displayName,
            description = component.description,
            required = component.required,
            version = candidate.metadata.version,
            compatibility = CompatibilityRange(
                minAndroidSdk = candidate.metadata.minAndroidSdk ?: component.minAndroidSdk,
            ),
            archiveFileName = component.archiveFileName,
            archiveSizeBytes = 0L,
            archiveSha256 = "",
            apkEntryName = "local.apk",
            apkSizeBytes = candidate.file.length(),
            apkSha256 = runCatching { sha256(candidate.file) }.getOrElse {
                return PlanAttemptResult.Failed(
                    ArtifactFailure(
                        phase = ArtifactFailurePhase.CACHE,
                        componentId = component.componentId,
                        sourceKind = ArtifactSourceKind.LOCAL_DOWNLOAD,
                        reasonCode = "local_download_hash_failed",
                        retryable = false,
                    ),
                )
            },
            packageName = candidate.metadata.packageName,
            apkVersion = candidate.metadata.version,
            certificateSha256 = candidate.identity.certificateSha256.lowercase(),
            sources = listOf(
                ArtifactSource(
                    kind = ArtifactSourceKind.LOCAL_DOWNLOAD,
                    url = ArtifactManifestValidator.LOCAL_DOWNLOAD_URL,
                ),
            ),
            rollbackId = "${config.effectiveCatalogVersion()}-${component.componentId}",
            deviceSetup = component.deviceSetup,
            sortOrder = component.sortOrder,
            localOnly = true,
        )
        when (val validation = ArtifactManifestValidator.validate(manifest)) {
            is ManifestValidation.Invalid -> return PlanAttemptResult.Failed(
                ArtifactFailure(
                    phase = ArtifactFailurePhase.CATALOG,
                    componentId = component.componentId,
                    sourceKind = ArtifactSourceKind.LOCAL_DOWNLOAD,
                    reasonCode = validation.reasonCode,
                    retryable = false,
                ),
            )

            ManifestValidation.Valid -> Unit
        }
        return when (val result = identityVerifier.verifyExisting(
            manifest,
            ArtifactSourceKind.LOCAL_DOWNLOAD,
            candidate.file,
        )) {
            is ArtifactIdentityResult.Verified -> PlanAttemptResult.Success(
                PlanAttemptSuccess(
                    manifest = manifest,
                    sourceKind = ArtifactSourceKind.LOCAL_DOWNLOAD,
                    downloaded = null,
                    verifiedArchive = null,
                    extractedApk = null,
                    verifiedApk = result.apk,
                ),
            )

            is ArtifactIdentityResult.Failed -> PlanAttemptResult.Failed(
                result.failure.copy(sourceKind = ArtifactSourceKind.LOCAL_DOWNLOAD),
            )
        }
    }

    private suspend fun prepareFromDynamicFolderArtifact(
        config: com.ninepointnine.helper.data.catalog.InstallerDistributionConfig,
        artifact: com.ninepointnine.helper.data.web.LanzouFolderArtifact,
    ): PlanAttemptResult {
        val component = artifact.component
        val request = when (val result = lanzouSourceAdapter.resolve(artifact.source)) {
            is LanzouResolutionResult.Success -> result.request
            is LanzouResolutionResult.Failure -> return PlanAttemptResult.Failed(
                result.failure.copy(componentId = component.componentId),
            )
        }
        val working = cache.privateWorkingDirectory()
        val archiveFile = working.resolve("prepare-${component.componentId}.zip")
        val apkFile = working.resolve("prepare-${component.componentId}.apk")
        var generatedManifest: ArtifactManifest? = null
        var preparedApkRetained = false
        archiveFile.delete()
        apkFile.delete()
        try {
            emitProgress(component.componentId, InstallPhase.FETCH, ComponentProgressStatus.RUNNING)
            val archive = when (val result = downloader.downloadDynamic(
                request = request,
                destination = archiveFile,
                progressListener = { progress ->
                    emitProgress(
                        component.componentId,
                        InstallPhase.FETCH,
                        ComponentProgressStatus.RUNNING,
                        bytesWritten = progress.bytesWritten,
                        totalBytes = progress.expectedBytes ?: 0L,
                        indeterminate = progress.expectedBytes == null,
                    )
                },
            )) {
                is DynamicArchiveDownloadResult.Completed -> result.archive
                is DynamicArchiveDownloadResult.Failed -> return PlanAttemptResult.Failed(
                    ArtifactFailure(
                        phase = ArtifactFailurePhase.DOWNLOAD,
                        componentId = component.componentId,
                        sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                        reasonCode = result.reasonCode,
                        retryable = result.retryable,
                    ),
                )
            }
            val inspection = inspectDynamicArchive(
                archive.file,
                apkFile,
                component.apkEntryName.takeIf { it.isNotBlank() },
            ) ?: return PlanAttemptResult.Failed(
                ArtifactFailure(
                    phase = ArtifactFailurePhase.EXTRACTION,
                    componentId = component.componentId,
                    sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                    reasonCode = "distribution_archive_invalid",
                    retryable = false,
                ),
            )
            val metadata = runCatching { metadataReader.read(inspection.apkFile) }.getOrNull()
                ?: return PlanAttemptResult.Failed(
                    ArtifactFailure(
                        phase = ArtifactFailurePhase.APK_VERIFICATION,
                        componentId = component.componentId,
                        sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                        reasonCode = "distribution_apk_metadata_unreadable",
                        retryable = false,
                    ),
                )
            val identity = validateDynamicIdentity(config, component, metadata)
                ?: return PlanAttemptResult.Failed(
                    ArtifactFailure(
                        phase = ArtifactFailurePhase.APK_VERIFICATION,
                        componentId = component.componentId,
                        sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                        reasonCode = dynamicIdentityFailureReason(config, component, metadata),
                        retryable = false,
                    ),
                )
            val manifest = ArtifactManifest(
                schemaVersion = ArtifactManifestValidator.SUPPORTED_SCHEMA_VERSION,
                componentId = component.componentId,
                displayName = component.displayName,
                description = component.description,
                required = component.required,
                version = metadata.version,
                compatibility = CompatibilityRange(
                    minAndroidSdk = metadata.minAndroidSdk ?: component.minAndroidSdk,
                ),
                archiveFileName = component.archiveFileName,
                archiveSizeBytes = archive.sizeBytes,
                archiveSha256 = archive.sha256,
                apkEntryName = inspection.entryName,
                apkSizeBytes = inspection.apkSizeBytes,
                apkSha256 = inspection.apkSha256,
                packageName = metadata.packageName,
                apkVersion = metadata.version,
                certificateSha256 = identity.certificateSha256.lowercase(),
                sources = listOf(artifact.source),
                rollbackId = "${config.effectiveCatalogVersion()}-${component.componentId}",
                deviceSetup = component.deviceSetup,
                sortOrder = component.sortOrder,
            )
            generatedManifest = manifest
            when (val validation = ArtifactManifestValidator.validate(manifest)) {
                is ManifestValidation.Invalid -> return PlanAttemptResult.Failed(
                    ArtifactFailure(
                        phase = ArtifactFailurePhase.CATALOG,
                        componentId = component.componentId,
                        sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                        reasonCode = validation.reasonCode,
                        retryable = false,
                    ),
                )

                ManifestValidation.Valid -> Unit
            }
            if (sourcePolicy.plan(manifest) is SourcePlan.Rejected) {
                return PlanAttemptResult.Failed(
                    ArtifactFailure(
                        phase = ArtifactFailurePhase.SOURCE_RESOLUTION,
                        componentId = component.componentId,
                        sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                        reasonCode = "distribution_source_policy_rejected",
                        retryable = false,
                    ),
                )
            }
            val paths = cache.paths(manifest)
            paths.archivePart.parentFile?.mkdirs()
            Files.move(
                archive.file.toPath(),
                paths.archivePart.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            val verifiedArchive = when (val result = archiveVerifier.verify(manifest, paths.archivePart)) {
                is ArchiveIdentityResult.Verified -> result.archive
                is ArchiveIdentityResult.Failed -> return PlanAttemptResult.Failed(
                    result.failure.copy(sourceKind = ArtifactSourceKind.LANZOU_SHARE),
                )
            }
            val extractedApk = when (val result = archiveExtractor.extract(manifest, verifiedArchive, paths.apkPart)) {
                is ArchiveExtractionResult.Extracted -> result.apk
                is ArchiveExtractionResult.Failed -> return PlanAttemptResult.Failed(
                    result.failure.copy(sourceKind = ArtifactSourceKind.LANZOU_SHARE),
                )
            }
            val verifiedApk = when (val result = identityVerifier.verify(
                manifest = manifest,
                sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                verifiedArchive = verifiedArchive,
                extractedApk = extractedApk,
                finalApk = paths.apk,
            )) {
                is ArtifactIdentityResult.Verified -> result.apk
                is ArtifactIdentityResult.Failed -> return PlanAttemptResult.Failed(
                    result.failure.copy(sourceKind = ArtifactSourceKind.LANZOU_SHARE),
                )
            }
            if (!cache.publishApk(manifest, verifiedApk.file)) {
                return PlanAttemptResult.Failed(
                    ArtifactFailure(
                        phase = ArtifactFailurePhase.CACHE,
                        componentId = component.componentId,
                        sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                        reasonCode = "public_download_publish_failed",
                        retryable = true,
                    ),
                )
            }
            cache.clearArchive(manifest)
            emitProgress(
                component.componentId,
                InstallPhase.CHECK,
                ComponentProgressStatus.COMPLETED,
                bytesWritten = manifest.apkSizeBytes,
                totalBytes = manifest.apkSizeBytes,
                indeterminate = false,
            )
            // On MediaStore-backed devices [paths.apk] is the private file
            // that the ADB executor consumes. Keep it until the device batch
            // finishes; the production executor owns the final cleanup.
            preparedApkRetained = true
            return PlanAttemptResult.Success(
                PlanAttemptSuccess(
                    manifest = manifest,
                    sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                    downloaded = ArtifactDownloadResult.Completed(
                        archivePart = paths.archivePart,
                        sizeBytes = verifiedArchive.sizeBytes,
                        resumed = false,
                    ),
                    verifiedArchive = verifiedArchive,
                    extractedApk = extractedApk,
                    verifiedApk = verifiedApk,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return PlanAttemptResult.Failed(
                ArtifactFailure(
                    phase = ArtifactFailurePhase.CACHE,
                    componentId = component.componentId,
                    sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                    reasonCode = "artifact_app_processing_failed",
                    retryable = true,
                ),
            )
        } finally {
            archiveFile.delete()
            apkFile.delete()
            generatedManifest?.let { manifest ->
                // Dynamic downloads are never resumable. Their manifest-keyed
                // private files must not survive a failed or cancelled try.
                cache.clearArchive(manifest)
                if (!preparedApkRetained) cache.clearApk(manifest)
            }
        }
    }

    private fun validateDynamicIdentity(
        config: com.ninepointnine.helper.data.catalog.InstallerDistributionConfig,
        component: InstallerComponentSource,
        metadata: ApkMetadata,
    ): com.ninepointnine.helper.domain.artifact.TrustedArtifactIdentity? {
        if (InstallerSelfIdentity.isSelfComponentId(component.componentId) &&
            metadata.packageName != InstallerSelfIdentity.PACKAGE_NAME
        ) return null
        if (!InstallerPublisherTrustRegistry.isKnownProfile(component.trustProfileId)) return null
        if (InstallerComponentTrustRegistry.get(component.componentId) != null &&
            !InstallerComponentTrustRegistry.isAllowedPackageName(component.componentId, metadata.packageName)
        ) return null
        if (!AuthorizationPlanFactory.validateComponent(
                ManagedComponent(
                    componentId = component.componentId,
                    packageName = metadata.packageName,
                    setup = component.deviceSetup,
                    order = component.sortOrder,
                ),
            )
        ) return null
        return InstallerPublisherTrustRegistry.matchComponentIdentity(
            componentId = component.componentId,
            profileId = component.trustProfileId,
            environment = config.environment,
            channel = config.channel,
            packageName = metadata.packageName,
            certificateDigests = metadata.certificateSha256s,
        )
    }

    private fun dynamicIdentityFailureReason(
        config: com.ninepointnine.helper.data.catalog.InstallerDistributionConfig,
        component: InstallerComponentSource,
        metadata: ApkMetadata,
    ): String = when {
        InstallerSelfIdentity.isSelfComponentId(component.componentId) &&
            metadata.packageName != InstallerSelfIdentity.PACKAGE_NAME -> "distribution_self_apk_package_mismatch"
        !InstallerPublisherTrustRegistry.isKnownProfile(component.trustProfileId) -> "distribution_trust_profile_invalid"
        InstallerComponentTrustRegistry.get(component.componentId) != null &&
            !InstallerComponentTrustRegistry.isAllowedPackageName(component.componentId, metadata.packageName) ->
            "distribution_apk_package_mismatch"
        !AuthorizationPlanFactory.validateComponent(
            ManagedComponent(
                componentId = component.componentId,
                packageName = metadata.packageName,
                setup = component.deviceSetup,
                order = component.sortOrder,
            ),
        ) -> "distribution_device_setup_invalid"
        else -> "distribution_apk_certificate_mismatch"
    }

    private fun inspectDynamicArchive(
        archive: File,
        apkFile: File,
        expectedEntryName: String?,
    ): DynamicArchiveInspection? {
        if (!archive.isFile || archive.length() <= 0L) return null
        apkFile.delete()
        var entryName: String? = null
        var entryCount = 0
        var written = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        return try {
            ZipInputStream(FileInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount += 1
                    if (
                        entryCount > 1 || entry.isDirectory || !isSafeApkEntry(entry.name) ||
                        (expectedEntryName != null && entry.name != expectedEntryName)
                    ) return null
                    entryName = entry.name
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            if (count == 0) return null
                            written += count
                            if (written > ArtifactManifestValidator.MAX_APK_SIZE_BYTES) return null
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                }
            }
            val name = entryName ?: return null
            if (entryCount != 1 || written <= 0L || !apkFile.isFile || apkFile.length() != written) return null
            DynamicArchiveInspection(
                entryName = name,
                apkFile = apkFile,
                apkSizeBytes = written,
                apkSha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) },
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun isSafeApkEntry(value: String): Boolean =
        value.isNotBlank() &&
            value.endsWith(".apk", ignoreCase = true) &&
            !value.startsWith('/') && !value.startsWith('\\') &&
            !value.contains('/') && !value.contains('\\') &&
            !value.contains('\u0000') && !value.contains(':')

    private data class LocalCandidate(
        val file: File,
        val metadata: ApkMetadata,
        val identity: com.ninepointnine.helper.domain.artifact.TrustedArtifactIdentity,
    )

    private data class DynamicArchiveInspection(
        val entryName: String,
        val apkFile: File,
        val apkSizeBytes: Long,
        val apkSha256: String,
    )

    private data class PlanAttemptSuccess(
        val manifest: ArtifactManifest,
        val sourceKind: ArtifactSourceKind,
        val downloaded: ArtifactDownloadResult.Completed?,
        val verifiedArchive: VerifiedArchive?,
        val extractedApk: ExtractedApk?,
        val verifiedApk: VerifiedApk,
    )

    private sealed interface PlanAttemptResult {
        data class Success(val value: PlanAttemptSuccess) : PlanAttemptResult
        data class Failed(val failure: ArtifactFailure) : PlanAttemptResult
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

}
