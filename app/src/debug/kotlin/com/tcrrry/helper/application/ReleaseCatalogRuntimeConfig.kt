package com.tcrrry.helper.application

import android.content.Context
import com.tcrrry.helper.data.catalog.CatalogHttpResponse
import com.tcrrry.helper.data.catalog.CloudReleaseCatalogAdapter
import com.tcrrry.helper.data.catalog.JcaCatalogSignatureVerifier
import com.tcrrry.helper.data.catalog.ReleaseCatalogTransport
import com.tcrrry.helper.data.catalog.SignedCatalogEnvelope
import com.tcrrry.helper.data.catalog.TrustedCatalogKeyResolver
import com.tcrrry.helper.data.catalog.UnavailableReleaseCatalogTransport
import com.tcrrry.helper.domain.artifact.ReleaseSourceMode
import com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy
import java.util.Base64
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Debug-only signed profile for the three user-verified real component ZIP shares. */
internal object ReleaseCatalogRuntimeConfig {
    val sourcePolicy: ReleaseSourcePolicy = ReleaseSourcePolicy(
        mode = ReleaseSourceMode.DEBUG_REAL_COMPONENTS,
    )

    fun createCatalogAdapter(context: Context): CloudReleaseCatalogAdapter {
        val bundle = runCatching {
            val envelope = context.assets.open(DEBUG_CATALOG_ASSET).use { input ->
                CloudReleaseCatalogAdapter.STRICT_JSON.decodeFromString<SignedCatalogEnvelope>(
                    input.readBytes().toString(Charsets.UTF_8),
                )
            }
            val publicKey = context.assets.open(DEBUG_PUBLIC_KEY_ASSET).use { input ->
                Base64.getDecoder().decode(input.readBytes().toString(Charsets.UTF_8).trim())
            }
            envelope to publicKey
        }.getOrNull() ?: return unavailableCatalogAdapter()
        return CloudReleaseCatalogAdapter(
            transport = ReleaseCatalogTransport {
                CatalogHttpResponse(
                    statusCode = 200,
                    body = CloudReleaseCatalogAdapter.STRICT_JSON.encodeToString(bundle.first).toByteArray(),
                    contentType = "application/json",
                )
            },
            signatureVerifier = JcaCatalogSignatureVerifier(
                TrustedCatalogKeyResolver { keyId ->
                    bundle.second.takeIf { keyId == bundle.first.keyId }
                },
            ),
            sourcePolicy = sourcePolicy,
        )
    }

    private fun unavailableCatalogAdapter(): CloudReleaseCatalogAdapter = CloudReleaseCatalogAdapter(
        transport = UnavailableReleaseCatalogTransport(),
        signatureVerifier = JcaCatalogSignatureVerifier(
            TrustedCatalogKeyResolver { null },
        ),
        sourcePolicy = sourcePolicy,
    )

    private const val DEBUG_CATALOG_ASSET = "real-debug/android-profile.json"
    private const val DEBUG_PUBLIC_KEY_ASSET = "real-debug/catalog-public-key.b64"
}
