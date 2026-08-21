package com.tcrrry.helper.application

import android.content.Context
import com.tcrrry.helper.BuildConfig
import com.tcrrry.helper.data.catalog.CloudInstallerDistributionConfigAdapter
import com.tcrrry.helper.data.catalog.InstallerComponentSource
import com.tcrrry.helper.data.catalog.InstallerDistributionConfig
import com.tcrrry.helper.data.catalog.JcaCatalogSignatureVerifier
import com.tcrrry.helper.data.catalog.TrustedCatalogKeyResolver
import com.tcrrry.helper.data.catalog.UnavailableReleaseCatalogTransport
import com.tcrrry.helper.data.catalog.UrlConnectionReleaseCatalogTransport
import com.tcrrry.helper.domain.artifact.ReleaseSourceMode
import com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy
import java.net.URL
import java.time.Instant
import java.util.Base64

/** Debug build can use the local folder override while Cloud is under construction. */
internal object ReleaseCatalogRuntimeConfig {
    val sourcePolicy: ReleaseSourcePolicy = ReleaseSourcePolicy(
        mode = ReleaseSourceMode.FOLDER_CONFIG,
    )

    fun createDistributionConfigAdapter(context: Context): CloudInstallerDistributionConfigAdapter {
        if (BuildConfig.DEBUG_FOLDER_PASSWORD.isNotBlank()) {
            return CloudInstallerDistributionConfigAdapter(
                transport = UnavailableReleaseCatalogTransport(),
                signatureVerifier = JcaCatalogSignatureVerifier(TrustedCatalogKeyResolver { null }),
                expectedChannel = DEBUG_CHANNEL,
                staticConfig = debugFolderConfig(BuildConfig.DEBUG_FOLDER_PASSWORD),
            )
        }
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
        signatureVerifier = JcaCatalogSignatureVerifier(TrustedCatalogKeyResolver { null }),
        expectedChannel = DEBUG_CHANNEL,
    )

    private fun debugFolderConfig(password: String): InstallerDistributionConfig = InstallerDistributionConfig(
        configVersion = "debug-folder-override",
        channel = DEBUG_CHANNEL,
        expiresAt = Instant.parse("2099-12-31T00:00:00Z"),
        folderUrl = DEBUG_FOLDER_URL,
        folderPassword = password,
        components = listOf(
            InstallerComponentSource(
                componentId = "desktop",
                archiveFileName = "03desktop-debug.zip",
                required = true,
                displayName = "03桌面",
                minAndroidSdk = 28,
                packageName = "com.tcrrry.desktop",
                certificateSha256 = "2990047fddf6d6ec1eb7f83731fcc1398616e5fb83aec97542a4f132c35a1a27",
            ),
            InstallerComponentSource(
                componentId = "lyrics",
                archiveFileName = "03lyrics-debug.zip",
                required = false,
                displayName = "03歌词",
                minAndroidSdk = 26,
                packageName = "com.tcrrry.desktoplyrics",
                certificateSha256 = "2990047fddf6d6ec1eb7f83731fcc1398616e5fb83aec97542a4f132c35a1a27",
            ),
            InstallerComponentSource(
                componentId = "file-manager",
                archiveFileName = "fossify-file-manager-car-debug.zip",
                required = false,
                displayName = "文件管理器",
                minAndroidSdk = 26,
                packageName = "org.fossify.filemanager.debug",
                certificateSha256 = "2990047fddf6d6ec1eb7f83731fcc1398616e5fb83aec97542a4f132c35a1a27",
            ),
        ),
        keyId = DEBUG_CONFIG_KEY_ID,
        signatureAlgorithm = "SHA256withECDSA",
    )

    private const val DEBUG_PUBLIC_KEY_ASSET = "real-debug/catalog-public-key.b64"
    private const val DEBUG_CHANNEL = "debug"
    private const val DEBUG_CONFIG_KEY_ID = "03helper-real-debug-2026-08-20-v4"
    private const val DISTRIBUTION_CONFIG_URL = "https://api.9.9studio.fun/api/03helper/android-config"
    private const val DEBUG_FOLDER_URL = "https://wwatl.lanzouw.com/b0fqlrcyb"
}
