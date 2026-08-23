package com.tcrrry.helper.application.artifact

import com.tcrrry.helper.data.catalog.CatalogLoadResult
import com.tcrrry.helper.data.catalog.CatalogPreparationProgress
import com.tcrrry.helper.data.catalog.TrustedArtifactCatalog
import com.tcrrry.helper.domain.artifact.toComponentDescriptor
import com.tcrrry.helper.domain.session.ComponentCompatibility
import com.tcrrry.helper.domain.session.ComponentDescriptor
import com.tcrrry.helper.domain.session.ComponentStatus
import com.tcrrry.helper.domain.session.InstallationSessionEvent

fun interface InstallerCatalogLoader {
    suspend fun load(): CatalogLoadResult
}

class ArtifactCatalogSessionAdapter(
    private val catalogLoader: InstallerCatalogLoader,
    private val eventPort: ArtifactSessionEventPort,
    private val selectionLoader: (suspend () -> CatalogLoadResult)? = null,
    private val selectedCatalogLoader: (suspend (Set<String>, (CatalogPreparationProgress) -> Unit) -> CatalogLoadResult)? = null,
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
                    catalogRevision = result.catalog.catalogRevision,
                ),
            )

            is CatalogLoadResult.Failure -> eventPort.emit(
                InstallationSessionEvent.CatalogFailed(result.reasonCode),
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
                    catalogRevision = result.catalog.catalogRevision,
                ),
            )

            is CatalogLoadResult.Failure -> eventPort.emit(InstallationSessionEvent.CatalogFailed(result.reasonCode))
        }
        return result
    }

    suspend fun prepareSelected(selectedIds: Set<String>): CatalogLoadResult {
        val loader = selectedCatalogLoader ?: return CatalogLoadResult.Failure(
            "selected_catalog_preparer_unavailable",
            retryable = false,
        )
        val result = loader.invoke(selectedIds) { progress ->
            eventPort.emit(
                InstallationSessionEvent.ComponentProgressUpdated(
                    componentId = progress.componentId,
                    phase = progress.phase,
                    status = progress.status,
                    bytesWritten = progress.bytesWritten,
                    totalBytes = progress.totalBytes,
                    indeterminate = progress.indeterminate,
                ),
            )
        }
        when (result) {
            is CatalogLoadResult.Success -> eventPort.emit(
                InstallationSessionEvent.SelectedCatalogResolved(
                    catalogVersion = result.catalog.catalogVersion,
                    keyId = result.catalog.keyId,
                    signatureAlgorithm = result.catalog.signatureAlgorithm,
                    manifests = result.catalog.manifests,
                    apps = result.catalog.toComponentDescriptors(),
                    appFailures = result.catalog.appFailures.associate { it.componentId to it.reasonCode },
                    catalogRevision = result.catalog.catalogRevision,
                ),
            )

            is CatalogLoadResult.Failure -> eventPort.emit(InstallationSessionEvent.CatalogFailed(result.reasonCode))
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
            app.minClientSchemaVersion > 3 -> ComponentStatus.CLIENT_CAPABILITY_INSUFFICIENT
            failure?.reasonCode?.contains("missing") == true -> ComponentStatus.DIRECTORY_MISSING
            failure?.reasonCode?.contains("certificate") == true -> ComponentStatus.APK_SIGNATURE_MISMATCH
            failure != null -> ComponentStatus.ZIP_VALIDATION_FAILED
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
            status = status,
            errorReason = failure?.reasonCode,
        )
    }
}
