package com.ninepointnine.helper.application

import android.content.Context
import com.ninepointnine.helper.data.catalog.CloudInstallerDistributionConfigAdapter
import com.ninepointnine.helper.data.catalog.JcaCatalogSignatureVerifier
import com.ninepointnine.helper.data.catalog.TrustedCatalogKeyResolver
import com.ninepointnine.helper.data.catalog.UnavailableReleaseCatalogTransport
import com.ninepointnine.helper.data.catalog.UrlConnectionReleaseCatalogTransport
import com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy
import com.ninepointnine.helper.domain.artifact.ReleaseSourceMode
import com.ninepointnine.helper.domain.artifact.ArtifactReleaseTrack
import java.net.URL

/** Release composition keeps production trust data unavailable until Cloud supplies v3 trust data. */
internal object ReleaseCatalogRuntimeConfig {
    val artifactReleaseTrack: ArtifactReleaseTrack = ArtifactReleaseTrack.RELEASE
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
            expectedEnvironment = RELEASE_ENVIRONMENT,
        )

    private const val RELEASE_CHANNEL = "release"
    private const val RELEASE_ENVIRONMENT = "production"
    private const val DISTRIBUTION_CONFIG_URL = "https://api.9.9studio.fun/api/03helper/android-config"
}
