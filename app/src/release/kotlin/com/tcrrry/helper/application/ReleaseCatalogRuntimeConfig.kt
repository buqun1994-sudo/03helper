package com.tcrrry.helper.application

import android.content.Context
import com.tcrrry.helper.data.catalog.CloudReleaseCatalogAdapter
import com.tcrrry.helper.data.catalog.JcaCatalogSignatureVerifier
import com.tcrrry.helper.data.catalog.TrustedCatalogKeyResolver
import com.tcrrry.helper.data.catalog.UnavailableReleaseCatalogTransport
import com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy

/** Release composition keeps production trust data unavailable until Cloud supplies it. */
internal object ReleaseCatalogRuntimeConfig {
    val sourcePolicy: ReleaseSourcePolicy = ReleaseSourcePolicy()

    fun createCatalogAdapter(context: Context): CloudReleaseCatalogAdapter = CloudReleaseCatalogAdapter(
        transport = UnavailableReleaseCatalogTransport(),
        signatureVerifier = JcaCatalogSignatureVerifier(
            TrustedCatalogKeyResolver { null },
        ),
        sourcePolicy = sourcePolicy,
    )
}
