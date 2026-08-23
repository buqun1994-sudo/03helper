package com.tcrrry.helper.application

import android.content.Context
import com.tcrrry.helper.data.catalog.CloudInstallerDistributionConfigAdapter
import com.tcrrry.helper.data.catalog.JcaCatalogSignatureVerifier
import com.tcrrry.helper.data.catalog.TrustedCatalogKeyResolver
import com.tcrrry.helper.data.catalog.UnavailableReleaseCatalogTransport
import com.tcrrry.helper.data.catalog.UrlConnectionReleaseCatalogTransport
import com.tcrrry.helper.domain.artifact.ReleaseSourceMode
import com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy
import dadb.AdbKeyPair
import java.io.File
import java.net.URL
import java.util.Base64

/** Debug composition for the signed Cloud schema-v3 configuration. */
internal object ReleaseCatalogRuntimeConfig {
    val sourcePolicy: ReleaseSourcePolicy = ReleaseSourcePolicy(
        mode = ReleaseSourceMode.FOLDER_CONFIG,
    )

    /** Reads a locally provisioned lab credential; it is never packaged or generated. */
    fun createDeviceAdbKeyPair(context: Context): AdbKeyPair? = runCatching {
        val directory = File(context.filesDir, DEBUG_ADB_DIRECTORY)
        val privateKey = File(directory, DEBUG_ADB_PRIVATE_KEY)
        val publicKey = File(directory, DEBUG_ADB_PUBLIC_KEY)
        if (!privateKey.isFile || !publicKey.isFile) return@runCatching null
        AdbKeyPair.read(privateKey, publicKey)
    }.getOrNull()

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
                TrustedCatalogKeyResolver { keyId ->
                    publicKey.takeIf { keyId == DEBUG_CONFIG_KEY_ID }
                },
            ),
            expectedChannel = DEBUG_CHANNEL,
            expectedEnvironment = DEBUG_ENVIRONMENT,
        )
    }

    private fun unavailableConfigAdapter(): CloudInstallerDistributionConfigAdapter =
        CloudInstallerDistributionConfigAdapter(
            transport = UnavailableReleaseCatalogTransport(),
            signatureVerifier = JcaCatalogSignatureVerifier(TrustedCatalogKeyResolver { null }),
            expectedChannel = DEBUG_CHANNEL,
            expectedEnvironment = DEBUG_ENVIRONMENT,
        )

    private const val DEBUG_PUBLIC_KEY_ASSET = "real-debug/catalog-public-key.b64"
    private const val DEBUG_CHANNEL = "debug"
    private const val DEBUG_ENVIRONMENT = "staging"
    private const val DEBUG_CONFIG_KEY_ID = "03helper-staging-config-2026-08-22-v1"
    private const val DISTRIBUTION_CONFIG_URL = "https://api-staging.9studio.fun/api/03helper/android-config"
    private const val DEBUG_ADB_DIRECTORY = "debug-adb"
    private const val DEBUG_ADB_PRIVATE_KEY = "adbkey"
    private const val DEBUG_ADB_PUBLIC_KEY = "adbkey.pub"
}
