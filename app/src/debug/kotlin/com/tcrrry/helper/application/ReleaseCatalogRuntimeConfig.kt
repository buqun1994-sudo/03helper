package com.tcrrry.helper.application

import android.content.Context
import com.tcrrry.helper.data.catalog.CloudInstallerDistributionConfigAdapter
import com.tcrrry.helper.data.catalog.JcaCatalogSignatureVerifier
import com.tcrrry.helper.data.catalog.TrustedCatalogKeyResolver
import com.tcrrry.helper.data.catalog.UrlConnectionReleaseCatalogTransport
import com.tcrrry.helper.data.catalog.UnavailableReleaseCatalogTransport
import com.tcrrry.helper.domain.artifact.ReleaseSourceMode
import com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy
import java.util.Base64
import java.net.URL

/** Debug-only trust profile for the remotely configured Lanzou folder. */
internal object ReleaseCatalogRuntimeConfig {
    val sourcePolicy: ReleaseSourcePolicy = ReleaseSourcePolicy(
        mode = ReleaseSourceMode.FOLDER_CONFIG,
    )

    fun createDistributionConfigAdapter(context: Context): CloudInstallerDistributionConfigAdapter {
        val publicKey = runCatching {
            context.assets.open(DEBUG_PUBLIC_KEY_ASSET).use { input ->
                Base64.getDecoder().decode(input.readBytes().toString(Charsets.UTF_8).trim())
            }
        }.getOrNull()
        if (publicKey == null) return unavailableConfigAdapter()
        return CloudInstallerDistributionConfigAdapter(
            transport = runCatching {
                UrlConnectionReleaseCatalogTransport(URL(DISTRIBUTION_CONFIG_URL))
            }.getOrElse { return unavailableConfigAdapter() },
            signatureVerifier = JcaCatalogSignatureVerifier(
                TrustedCatalogKeyResolver { keyId -> publicKey.takeIf { keyId == DEBUG_CONFIG_KEY_ID } },
            ),
            expectedChannel = DEBUG_CHANNEL,
        )
    }

    private fun unavailableConfigAdapter(): CloudInstallerDistributionConfigAdapter = CloudInstallerDistributionConfigAdapter(
        transport = UnavailableReleaseCatalogTransport(),
        signatureVerifier = JcaCatalogSignatureVerifier(
            TrustedCatalogKeyResolver { null },
        ),
        expectedChannel = DEBUG_CHANNEL,
    )

    private const val DEBUG_PUBLIC_KEY_ASSET = "real-debug/catalog-public-key.b64"
    private const val DEBUG_CHANNEL = "debug"
    private const val DEBUG_CONFIG_KEY_ID = "03helper-real-debug-2026-08-20-v4"
    private const val DISTRIBUTION_CONFIG_URL = "https://api.9.9studio.fun/api/03helper/android-config"
}
