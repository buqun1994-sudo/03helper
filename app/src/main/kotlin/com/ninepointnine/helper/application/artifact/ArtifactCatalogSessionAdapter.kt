package com.ninepointnine.helper.application.artifact

import com.ninepointnine.helper.data.catalog.CatalogLoadResult
import com.ninepointnine.helper.data.catalog.TrustedArtifactCatalog
import com.ninepointnine.helper.domain.artifact.toComponentDescriptor
import com.ninepointnine.helper.domain.session.ComponentCompatibility
import com.ninepointnine.helper.domain.session.ComponentDescriptor
import com.ninepointnine.helper.domain.session.ComponentStatus
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import com.ninepointnine.helper.domain.session.componentStatusForReasonCode
import com.ninepointnine.helper.application.session.InstallationSessionEventPort

class ArtifactCatalogSessionAdapter(
    private val eventPort: InstallationSessionEventPort,
    private val selectionLoader: suspend () -> CatalogLoadResult,
) {
    suspend fun loadSelection(): CatalogLoadResult {
        val result = selectionLoader.invoke()
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
            failure != null -> componentStatusForReasonCode(failure.reasonCode)
            base != null -> ComponentStatus.AVAILABLE
            manifest == null -> ComponentStatus.READING
            else -> ComponentStatus.TEMPORARILY_UNAVAILABLE
        }
        ComponentDescriptor(
            id = app.componentId,
            packageName = app.packageName,
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
