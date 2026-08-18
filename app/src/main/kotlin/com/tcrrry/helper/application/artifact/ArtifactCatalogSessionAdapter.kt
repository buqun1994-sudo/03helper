package com.tcrrry.helper.application.artifact

import com.tcrrry.helper.data.catalog.CatalogLoadResult
import com.tcrrry.helper.data.catalog.CloudReleaseCatalogAdapter
import com.tcrrry.helper.domain.session.InstallationSessionEvent

class ArtifactCatalogSessionAdapter(
    private val catalogAdapter: CloudReleaseCatalogAdapter,
    private val eventPort: ArtifactSessionEventPort,
) {
    suspend fun load(): CatalogLoadResult {
        val result = catalogAdapter.load()
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
