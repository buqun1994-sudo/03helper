package com.tcrrry.helper.application

import android.content.Context
import com.tcrrry.helper.data.catalog.CloudInstallerDistributionConfigAdapter
import com.tcrrry.helper.data.catalog.JcaCatalogSignatureVerifier
import com.tcrrry.helper.data.catalog.TrustedCatalogKeyResolver
import com.tcrrry.helper.data.catalog.UnavailableReleaseCatalogTransport
import com.tcrrry.helper.data.catalog.UrlConnectionReleaseCatalogTransport
import com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy
import com.tcrrry.helper.domain.artifact.ReleaseSourceMode
import java.net.URL

/** Release composition keeps production trust data unavailable until Cloud supplies it. */
internal object ReleaseCatalogRuntimeConfig {
    val sourcePolicy: ReleaseSourcePolicy = ReleaseSourcePolicy(mode = ReleaseSourceMode.FOLDER_CONFIG)

    @Suppress("UNUSED_PARAMETER")
    fun createDistributionConfigAdapter(context: Context): CloudInstallerDistributionConfigAdapter =
        CloudInstallerDistributionConfigAdapter(
            transport = runCatching {
                UrlConnectionReleaseCatalogTransport(URL(DISTRIBUTION_CONFIG_URL))
            }.getOrElse { UnavailableReleaseCatalogTransport() },
            signatureVerifier = JcaCatalogSignatureVerifier(
                TrustedCatalogKeyResolver { null },
            ),
            expectedChannel = RELEASE_CHANNEL,
        )

    private const val RELEASE_CHANNEL = "release"
    private const val DISTRIBUTION_CONFIG_URL = "https://api.9.9studio.fun/api/03helper/android-config"
}
