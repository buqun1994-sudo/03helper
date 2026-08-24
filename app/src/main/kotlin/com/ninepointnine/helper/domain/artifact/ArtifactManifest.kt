package com.ninepointnine.helper.domain.artifact

import com.ninepointnine.helper.domain.device.AuthorizationSetupDeclaration

/**
 * The signed release identity consumed by the installer.
 *
 * This type intentionally contains release metadata only. It cannot carry a
 * command, a script, a credential, or a runtime-generated download URL.
 */
data class ArtifactManifest(
    val schemaVersion: Int,
    val componentId: String,
    val displayName: String,
    val description: String = "",
    val required: Boolean,
    val version: ArtifactVersion,
    val compatibility: CompatibilityRange,
    val archiveFormat: String = "zip",
    val archiveFileName: String,
    val archiveSizeBytes: Long,
    val archiveSha256: String,
    val apkEntryName: String,
    val apkSizeBytes: Long,
    val apkSha256: String,
    val packageName: String,
    val apkVersion: ArtifactVersion,
    val certificateSha256: String,
    val sources: List<ArtifactSource>,
    val rollbackId: String? = null,
    /** Typed setup compiled from the signed app entry; never shell text. */
    val deviceSetup: AuthorizationSetupDeclaration? = null,
    val sortOrder: Int = 0,
    /** A local-only manifest is built from a verified public Download APK. */
    val localOnly: Boolean = false,
)

/**
 * Package, signing and compatibility identities shipped with this installer.
 * Cloud configuration may select a fixed archive, but it can never replace
 * any value in this registry.
 */
data class TrustedInstallerComponent(
    val componentId: String,
    val archiveFileName: String,
    val apkEntryName: String,
    val required: Boolean,
    val displayName: String,
    val minAndroidSdk: Int,
    val packageName: String,
    val certificateSha256: String,
    val trustProfileId: String = "nine-studio",
)

/** Stable identity for the installer APK when Cloud publishes it alongside car apps. */
object InstallerSelfIdentity {
    const val COMPONENT_ID = "03helper"
    const val LEGACY_COMPONENT_ID = "helper"
    const val PACKAGE_NAME = "com.ninepointnine.helper"
    const val TRUST_PROFILE_ID = "helper-approved"

    private val componentIds = setOf(COMPONENT_ID, LEGACY_COMPONENT_ID)

    fun isSelfComponentId(componentId: String): Boolean = componentId.lowercase() in componentIds
}

object InstallerComponentTrustRegistry {
    const val DESKTOP_COMPONENT_ID = "desktop"
    const val LYRICS_COMPONENT_ID = "lyrics"
    const val FILE_MANAGER_COMPONENT_ID = "file-manager"

    const val DESKTOP_PACKAGE_NAME = "com.tcrrry.desktop"
    const val LYRICS_PACKAGE_NAME = "com.tcrrry.desktoplyrics"
    const val FILE_MANAGER_PACKAGE_NAME = "org.fossify.filemanager.debug"

    private const val DEBUG_CERTIFICATE_SHA256 =
        "2990047fddf6d6ec1eb7f83731fcc1398616e5fb83aec97542a4f132c35a1a27"

    val components: List<TrustedInstallerComponent> = listOf(
        TrustedInstallerComponent(
            componentId = DESKTOP_COMPONENT_ID,
            archiveFileName = "03desktop-debug.zip",
            apkEntryName = "03desktop-debug.apk",
            required = true,
            displayName = "03桌面",
            minAndroidSdk = 28,
            packageName = DESKTOP_PACKAGE_NAME,
            certificateSha256 = DEBUG_CERTIFICATE_SHA256,
            trustProfileId = "nine-studio",
        ),
        TrustedInstallerComponent(
            componentId = LYRICS_COMPONENT_ID,
            archiveFileName = "03lyrics-debug.zip",
            apkEntryName = "03lyrics-debug.apk",
            required = false,
            displayName = "03歌词",
            minAndroidSdk = 26,
            packageName = LYRICS_PACKAGE_NAME,
            certificateSha256 = DEBUG_CERTIFICATE_SHA256,
            trustProfileId = "nine-studio",
        ),
        TrustedInstallerComponent(
            componentId = FILE_MANAGER_COMPONENT_ID,
            archiveFileName = "fossify-file-manager-car-debug.zip",
            apkEntryName = "fossify-file-manager-car-debug.apk",
            required = false,
            displayName = "文件管理器",
            minAndroidSdk = 26,
            packageName = FILE_MANAGER_PACKAGE_NAME,
            certificateSha256 = DEBUG_CERTIFICATE_SHA256,
            trustProfileId = "fossify-approved",
        ),
    )

    private val byId = components.associateBy { it.componentId }

    fun get(componentId: String): TrustedInstallerComponent? = byId[componentId]

    fun ids(): Set<String> = byId.keys

    fun expectedTrustProfileId(componentId: String): String? = byId[componentId]?.trustProfileId
        ?: InstallerSelfIdentity.TRUST_PROFILE_ID.takeIf { InstallerSelfIdentity.isSelfComponentId(componentId) }

    /**
     * Legacy component metadata remains available to old local fixtures, but
     * it is no longer used to decide how many apps a Cloud catalog may contain.
     */
    val trustedPublisherCertificates: List<TrustedPublisherCertificate>
        get() = InstallerPublisherTrustRegistry.certificates
}

data class TrustedPublisherCertificate(
    val certificateSha256: String,
    val packagePrefixes: Set<String>,
)

data class TrustedPublisherProfile(
    val id: String,
    val certificates: List<TrustedPublisherCertificate>,
)

/** Minimal local trust root shared by every dynamically discovered app. */
object InstallerPublisherTrustRegistry {
    private const val DEBUG_CERTIFICATE_SHA256 =
        "2990047fddf6d6ec1eb7f83731fcc1398616e5fb83aec97542a4f132c35a1a27"

    private val nineStudioCertificate = TrustedPublisherCertificate(
        certificateSha256 = DEBUG_CERTIFICATE_SHA256,
        packagePrefixes = setOf("com.tcrrry."),
    )
    private val fossifyCertificate = TrustedPublisherCertificate(
        certificateSha256 = DEBUG_CERTIFICATE_SHA256,
        packagePrefixes = setOf("org.fossify."),
    )
    private val helperCertificates = listOf(
        // Debug certificate used by local component fixtures.
        TrustedPublisherCertificate(
            certificateSha256 = DEBUG_CERTIFICATE_SHA256,
            packagePrefixes = setOf(InstallerSelfIdentity.PACKAGE_NAME),
        ),
        // Public staging / production certificate roots are intentionally
        // package-scoped and are only used for the signed self-update entry.
        TrustedPublisherCertificate(
            certificateSha256 = "aca4f178fea11ccc97a1373c8aa5345b274a3a783398929a9340a79ee83663af",
            packagePrefixes = setOf(InstallerSelfIdentity.PACKAGE_NAME),
        ),
        TrustedPublisherCertificate(
            certificateSha256 = "31ca80dd21a5208eaabd5f3e1440a3db2f7dc79122e03eaa6ba01730fb31f18b",
            packagePrefixes = setOf(InstallerSelfIdentity.PACKAGE_NAME),
        ),
    )

    val profiles: List<TrustedPublisherProfile> = listOf(
        TrustedPublisherProfile("nine-studio", listOf(nineStudioCertificate)),
        TrustedPublisherProfile("fossify-approved", listOf(fossifyCertificate)),
        TrustedPublisherProfile(InstallerSelfIdentity.TRUST_PROFILE_ID, helperCertificates),
    )

    val certificates: List<TrustedPublisherCertificate> = listOf(
        TrustedPublisherCertificate(
            certificateSha256 = DEBUG_CERTIFICATE_SHA256,
            packagePrefixes = setOf("com.tcrrry.", "org.fossify."),
        ),
        *helperCertificates.toTypedArray(),
    )

    fun isTrusted(packageName: String, certificateDigests: Set<String>): Boolean = certificates.any { trusted ->
        trusted.packagePrefixes.any(packageName::startsWith) &&
            certificateDigests.any { it.equals(trusted.certificateSha256, ignoreCase = true) }
    }

    fun isKnownProfile(profileId: String): Boolean = profiles.any { it.id == profileId }

    fun isTrusted(profileId: String, packageName: String, certificateDigests: Set<String>): Boolean =
        profiles.firstOrNull { it.id == profileId }?.certificates?.any { trusted ->
            trusted.packagePrefixes.any(packageName::startsWith) &&
                certificateDigests.any { it.equals(trusted.certificateSha256, ignoreCase = true) }
        } == true

    fun trustedCertificateSha256(profileId: String, certificateDigests: Set<String>): String? =
        profiles.firstOrNull { it.id == profileId }?.certificates
            ?.asSequence()
            ?.map { it.certificateSha256 }
            ?.firstOrNull { expected -> certificateDigests.any { it.equals(expected, ignoreCase = true) } }
}
