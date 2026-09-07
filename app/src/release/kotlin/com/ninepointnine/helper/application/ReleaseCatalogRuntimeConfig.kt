package com.ninepointnine.helper.application

import android.content.Context
import com.ninepointnine.helper.data.catalog.CloudInstallerDistributionConfigAdapter
import com.ninepointnine.helper.data.catalog.JcaCatalogSignatureVerifier
import com.ninepointnine.helper.data.catalog.ReleaseCatalogTransport
import com.ninepointnine.helper.data.catalog.TrustedCatalogKeyResolver
import com.ninepointnine.helper.data.catalog.UnavailableReleaseCatalogTransport
import com.ninepointnine.helper.data.catalog.UrlConnectionReleaseCatalogTransport
import com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy
import com.ninepointnine.helper.domain.artifact.ReleaseSourceMode
import java.net.URL
import java.time.Instant
import java.util.Base64

/** Release composition trusts only the pinned production V5 configuration root. */
internal object ReleaseCatalogRuntimeConfig {
    val sourcePolicy: ReleaseSourcePolicy = ReleaseSourcePolicy(mode = ReleaseSourceMode.FOLDER_CONFIG)
    internal val acceptedSchemaVersions: Set<Int> = setOf(5)
    internal val acceptedSignatureAlgorithms: Set<String> = setOf(PRODUCTION_CONFIG_ALGORITHM)
    private val productionPublicKey: ByteArray? = runCatching {
        Base64.getDecoder().decode(PRODUCTION_PUBLIC_KEY_SPKI_BASE64)
    }.getOrNull()

    @Suppress("UNUSED_PARAMETER")
    fun createDistributionConfigAdapter(context: Context): CloudInstallerDistributionConfigAdapter =
        createDistributionConfigAdapter(
            runCatching {
                UrlConnectionReleaseCatalogTransport(URL(DISTRIBUTION_CONFIG_URL))
            }.getOrElse { UnavailableReleaseCatalogTransport() },
        )

    internal fun createDistributionConfigAdapter(
        transport: ReleaseCatalogTransport,
        now: () -> Instant = Instant::now,
    ): CloudInstallerDistributionConfigAdapter =
        CloudInstallerDistributionConfigAdapter(
            transport = transport,
            signatureVerifier = JcaCatalogSignatureVerifier(
                TrustedCatalogKeyResolver(::resolveTrustedKey),
            ),
            expectedChannel = EXPECTED_CHANNEL,
            acceptedSignatureAlgorithms = acceptedSignatureAlgorithms,
            expectedEnvironment = EXPECTED_ENVIRONMENT,
            now = now,
        )

    internal fun resolveTrustedKey(keyId: String): ByteArray? =
        productionPublicKey?.takeIf { keyId == PRODUCTION_CONFIG_KEY_ID }

    internal const val EXPECTED_CHANNEL = "release"
    internal const val EXPECTED_ENVIRONMENT = "production"
    private const val PRODUCTION_CONFIG_KEY_ID = "03helper-production-config-2026-08-30-v1"
    private const val PRODUCTION_CONFIG_ALGORITHM = "SHA256withECDSA"
    private const val PRODUCTION_PUBLIC_KEY_SPKI_BASE64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEirb4nuUiVR2VNowtaPzkKEIipA5lrITQzjCyQWuFSSUjUtX67Zc5tUqkuN/ONG2/jCkIAMKMHPg2xg3AcPrYig=="
    private const val DISTRIBUTION_CONFIG_URL = "https://api.9.9studio.fun/api/03helper/android-config?schemaVersion=5"
}
