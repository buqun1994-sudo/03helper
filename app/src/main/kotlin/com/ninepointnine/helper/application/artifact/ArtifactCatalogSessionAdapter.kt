package com.ninepointnine.helper.application.artifact

import com.ninepointnine.helper.data.catalog.CatalogLoadResult
import com.ninepointnine.helper.data.catalog.CatalogPreparationProgress
import com.ninepointnine.helper.data.catalog.CloudInstallerDistributionConfigAdapter
import com.ninepointnine.helper.data.catalog.TrustedArtifactCatalog
import com.ninepointnine.helper.domain.artifact.toComponentDescriptor
import com.ninepointnine.helper.domain.session.ComponentCompatibility
import com.ninepointnine.helper.domain.session.ComponentDescriptor
import com.ninepointnine.helper.domain.session.ComponentStatus
import com.ninepointnine.helper.domain.session.InstallationBatchPlan
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import com.ninepointnine.helper.domain.session.componentStatusForReasonCode

fun interface InstallerCatalogLoader {
    suspend fun load(): CatalogLoadResult
}

class ArtifactCatalogSessionAdapter(
    private val catalogLoader: InstallerCatalogLoader,
    private val eventPort: ArtifactSessionEventPort,
    private val selectionLoader: (suspend () -> CatalogLoadResult)? = null,
    private val selectedCatalogLoader: (suspend (Set<String>, (CatalogPreparationProgress) -> Unit) -> CatalogLoadResult)? = null,
    private val selectedCatalogLoaderWithSkipped: (suspend (Set<String>, Set<String>, (CatalogPreparationProgress) -> Unit) -> CatalogLoadResult)? = null,
    private val selectedCatalogLoaderWithBatch: (suspend (InstallationBatchPlan, (CatalogPreparationProgress) -> Unit) -> CatalogLoadResult)? = null,
) {
    suspend fun load(): CatalogLoadResult {
        val result = catalogLoader.load()
        when (result) {
            is CatalogLoadResult.Success -> eventPort.emit(
                InstallationSessionEvent.CatalogResolved(
                    catalogVersion = result.catalog.catalogVersion,
                    keyId = result.catalog.keyId,
                    signatureAlgorithm = result.catalog.signatureAlgorithm,
                    manifests = result.catalog.manifests,
                    apps = result.catalog.toComponentDescriptors(),
                    appFailures = result.catalog.appFailures.associate { it.componentId to it.reasonCode },
                    appFailureRetryable = result.catalog.appFailures.associate { it.componentId to it.retryable },
                    catalogRevision = result.catalog.catalogRevision,
                ),
            )

            is CatalogLoadResult.Failure -> eventPort.emit(
                InstallationSessionEvent.CatalogFailed(result.reasonCode, retryable = result.retryable),
            )
        }
        return result
    }

    suspend fun loadSelection(): CatalogLoadResult {
        val result = selectionLoader?.invoke() ?: catalogLoader.load()
        when (result) {
            is CatalogLoadResult.Success -> eventPort.emit(
                InstallationSessionEvent.DistributionConfigResolved(
                    configVersion = result.catalog.catalogVersion,
                    keyId = result.catalog.keyId,
                    signatureAlgorithm = result.catalog.signatureAlgorithm,
                    components = result.catalog.toComponentDescriptors(),
                    appFailures = result.catalog.appFailures.associate { it.componentId to it.reasonCode },
                    appFailureRetryable = result.catalog.appFailures.associate { it.componentId to it.retryable },
                    catalogRevision = result.catalog.catalogRevision,
                ),
            )

            is CatalogLoadResult.Failure -> eventPort.emit(
                InstallationSessionEvent.CatalogFailed(result.reasonCode, retryable = result.retryable),
            )
        }
        return result
    }

    suspend fun prepareSelected(
        selectedIds: Set<String>,
        skippedIds: Set<String> = emptySet(),
    ): CatalogLoadResult = prepareSelectedResult(batch = null) { progress ->
        when {
            selectedCatalogLoaderWithSkipped != null ->
                selectedCatalogLoaderWithSkipped.invoke(selectedIds, skippedIds, progress)

            selectedCatalogLoader != null && skippedIds.isEmpty() ->
                selectedCatalogLoader.invoke(selectedIds, progress)

            else -> CatalogLoadResult.Failure(
                "selected_catalog_preparer_unavailable",
                retryable = false,
            )
        }
    }

    suspend fun prepareSelected(batch: InstallationBatchPlan): CatalogLoadResult =
        prepareSelectedResult(batch) { progress ->
            selectedCatalogLoaderWithBatch?.invoke(batch, progress)
                ?: CatalogLoadResult.Failure(
                    "selected_catalog_preparer_unavailable",
                    retryable = false,
                )
        }

    private suspend fun prepareSelectedResult(
        batch: InstallationBatchPlan?,
        load: suspend ((CatalogPreparationProgress) -> Unit) -> CatalogLoadResult,
    ): CatalogLoadResult {
        val progress: (CatalogPreparationProgress) -> Unit = { update ->
            eventPort.emit(
                InstallationSessionEvent.ComponentProgressUpdated(
                    componentId = update.componentId,
                    phase = update.phase,
                    status = update.status,
                    bytesWritten = update.bytesWritten,
                    totalBytes = update.totalBytes,
                    indeterminate = update.indeterminate,
                ),
            )
        }
        val result = load(progress)
        when (result) {
            is CatalogLoadResult.Success -> eventPort.emit(
                InstallationSessionEvent.SelectedCatalogResolved(
                    catalogVersion = result.catalog.catalogVersion,
                    keyId = result.catalog.keyId,
                    signatureAlgorithm = result.catalog.signatureAlgorithm,
                    manifests = result.catalog.manifests,
                    apps = result.catalog.toComponentDescriptors(),
                    appFailures = result.catalog.appFailures.associate { it.componentId to it.reasonCode },
                    appFailureRetryable = result.catalog.appFailures.associate { it.componentId to it.retryable },
                    catalogRevision = result.catalog.catalogRevision,
                    batch = batch,
                ),
            )

            is CatalogLoadResult.Failure -> eventPort.emit(
                InstallationSessionEvent.CatalogFailed(result.reasonCode, retryable = result.retryable),
            )
        }
        return result
    }
}

/** Single mapper for dynamic catalog rows used by first-install and maintenance. */
internal fun TrustedArtifactCatalog.toComponentDescriptors(androidSdk: Int? = null): List<ComponentDescriptor> {
    val manifestsById = manifests.associateBy { it.componentId }
    val failuresById = appFailures.associateBy { it.componentId }
    return apps.filter { it.enabled }.map { app ->
        val manifest = manifestsById[app.componentId]
        val failure = failuresById[app.componentId]
        val base = manifest?.toComponentDescriptor(androidSdk)
        val status = when {
            app.minClientSchemaVersion > CloudInstallerDistributionConfigAdapter.SUPPORTED_SCHEMA_VERSION -> ComponentStatus.CLIENT_CAPABILITY_INSUFFICIENT
            failure != null -> componentStatusForReasonCode(failure.reasonCode)
            base != null -> ComponentStatus.AVAILABLE
            manifest == null -> ComponentStatus.READING
            else -> ComponentStatus.TEMPORARILY_UNAVAILABLE
        }
        ComponentDescriptor(
            id = app.componentId,
            displayName = app.displayName,
            description = app.description,
            required = app.required,
            // Before preparation, these labels come directly from Cloud's
            // versionName / apkSizeBytes fields. A verified manifest replaces
            // them with the same normalized values after download.
            versionLabel = base?.versionLabel ?: app.displayVersionLabel,
            sizeLabel = base?.sizeLabel ?: app.displaySizeLabel,
            compatibilityLabel = base?.compatibilityLabel,
            compatibilityState = base?.compatibilityState ?: ComponentCompatibility.UNKNOWN,
            iconKey = app.componentId,
            iconAsset = app.iconAsset,
            status = status,
            errorReason = failure?.reasonCode,
        )
    }
}
