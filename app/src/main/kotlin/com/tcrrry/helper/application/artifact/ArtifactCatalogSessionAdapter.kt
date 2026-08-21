package com.tcrrry.helper.application.artifact

import com.tcrrry.helper.data.catalog.CatalogLoadResult
import com.tcrrry.helper.domain.session.InstallationSessionEvent

fun interface InstallerCatalogLoader {
    suspend fun load(): CatalogLoadResult
}

class ArtifactCatalogSessionAdapter(
    private val catalogLoader: InstallerCatalogLoader,
    private val eventPort: ArtifactSessionEventPort,
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
                ),
            )

            is CatalogLoadResult.Failure -> eventPort.emit(
                InstallationSessionEvent.CatalogFailed(result.reasonCode),
            )
        }
        return result
    }
}
