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
    const val CAST_COMPONENT_ID = "cast"
    const val FILE_MANAGER_COMPONENT_ID = "file-manager"

    /** Legacy package names retained for local Debug fixtures and old installs. */
    const val DESKTOP_PACKAGE_NAME = "com.tcrrry.desktop"
    const val LYRICS_PACKAGE_NAME = "com.tcrrry.desktoplyrics"
    const val CAST_PACKAGE_NAME = "com.ninepointnine.desktopcast"
    /** Canonical package names used by the current staging/release builds. */
    const val CURRENT_DESKTOP_PACKAGE_NAME = "com.ninepointnine.desktop"
    const val CURRENT_LYRICS_PACKAGE_NAME = "com.ninepointnine.desktoplyrics"
    const val CURRENT_CAST_PACKAGE_NAME = "com.ninepointnine.desktopcast"
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
        TrustedInstallerComponent(
            componentId = CAST_COMPONENT_ID,
            archiveFileName = "03cast-debug.zip",
            apkEntryName = "03cast-debug.apk",
            required = false,
            displayName = "03投屏",
            minAndroidSdk = 28,
            packageName = CURRENT_CAST_PACKAGE_NAME,
            certificateSha256 = DEBUG_CERTIFICATE_SHA256,
            trustProfileId = "nine-studio",
        ),
    )

    private val byId = components.associateBy { it.componentId }

    fun get(componentId: String): TrustedInstallerComponent? = byId[componentId]

    fun ids(): Set<String> = byId.keys

    fun expectedTrustProfileId(componentId: String): String? = byId[componentId]?.trustProfileId
        ?: "nine-studio".takeIf { componentId == CAST_COMPONENT_ID }
        ?: InstallerSelfIdentity.TRUST_PROFILE_ID.takeIf { InstallerSelfIdentity.isSelfComponentId(componentId) }

    /** Package names accepted for a component across debug, staging and release channels. */
    fun allowedPackageNames(componentId: String): Set<String> = when (componentId) {
        DESKTOP_COMPONENT_ID -> setOf(DESKTOP_PACKAGE_NAME, CURRENT_DESKTOP_PACKAGE_NAME)
        LYRICS_COMPONENT_ID -> setOf(LYRICS_PACKAGE_NAME, CURRENT_LYRICS_PACKAGE_NAME)
        CAST_COMPONENT_ID -> setOf(CAST_PACKAGE_NAME, "com.tcrrry.desktopcast")
        FILE_MANAGER_COMPONENT_ID -> setOf(FILE_MANAGER_PACKAGE_NAME)
        else -> emptySet()
    }

    fun isAllowedPackageName(componentId: String, packageName: String): Boolean =
        packageName in allowedPackageNames(componentId)

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

/** Release track used to select an exact package/certificate identity. */
enum class ArtifactReleaseTrack {
    DEBUG,
    STAGING,
    RELEASE,
}

data class TrustedArtifactIdentity(
    val componentId: String,
    val packageName: String,
    val certificateSha256: String,
    val track: ArtifactReleaseTrack,
)

/** Minimal local trust root shared by every dynamically discovered app. */
object InstallerPublisherTrustRegistry {
    private const val DEBUG_CERTIFICATE_SHA256 =
        "2990047fddf6d6ec1eb7f83731fcc1398616e5fb83aec97542a4f132c35a1a27"

    private val nineStudioCertificate = TrustedPublisherCertificate(
        certificateSha256 = DEBUG_CERTIFICATE_SHA256,
        packagePrefixes = setOf("com.tcrrry.", "com.ninepointnine.desktop", "com.ninepointnine.desktoplyrics", "com.ninepointnine.desktopcast"),
    )
    private val nineStudioStagingCertificates = listOf(
        TrustedPublisherCertificate(
            certificateSha256 = "bfb70dc15b54ad2f1b8acd35fa26ecf552bf2ef21d416a44b7eeda5e5e9ebaa9",
            packagePrefixes = setOf("com.ninepointnine.desktop"),
        ),
        TrustedPublisherCertificate(
            certificateSha256 = "1eb136fffd3f1e4c204d0933cab66c51ee4536a29e949b9c080925c01563b51d",
            packagePrefixes = setOf("com.ninepointnine.desktoplyrics"),
        ),
        TrustedPublisherCertificate(
            certificateSha256 = "98740b95c30064f727b9401a851ecf2e576d5e5c38fcc318284578747ba50e2a",
            packagePrefixes = setOf("com.ninepointnine.desktopcast"),
        ),
    )
    private val nineStudioProductionCertificates = listOf(
        TrustedPublisherCertificate(
            certificateSha256 = "d5ed175f00bd64be4de15ecc03b3eb3d1019305a89637e0a1c6b4df314aa2417",
            packagePrefixes = setOf("com.ninepointnine.desktop"),
        ),
        TrustedPublisherCertificate(
            certificateSha256 = "934b9151fe62b39a3474a11f00c2114c7f392b18fec85f39f8d71b9596860e03",
            packagePrefixes = setOf("com.ninepointnine.desktoplyrics"),
        ),
        TrustedPublisherCertificate(
            certificateSha256 = "14e4a7cdf1481afdb871487aa830bb0dc28910c0ba681693f11f9f1dd2fd4423",
            packagePrefixes = setOf("com.ninepointnine.desktopcast"),
        ),
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

    private val componentIdentities = listOf(
        // The old package names remain accepted only for the local Debug track.
        TrustedArtifactIdentity(
            InstallerComponentTrustRegistry.DESKTOP_COMPONENT_ID,
            "com.tcrrry.desktop",
            DEBUG_CERTIFICATE_SHA256,
            ArtifactReleaseTrack.DEBUG,
        ),
        TrustedArtifactIdentity(
            InstallerComponentTrustRegistry.DESKTOP_COMPONENT_ID,
            InstallerComponentTrustRegistry.DESKTOP_PACKAGE_NAME,
            DEBUG_CERTIFICATE_SHA256,
            ArtifactReleaseTrack.DEBUG,
        ),
        TrustedArtifactIdentity(
            InstallerComponentTrustRegistry.DESKTOP_COMPONENT_ID,
            InstallerComponentTrustRegistry.CURRENT_DESKTOP_PACKAGE_NAME,
            "bfb70dc15b54ad2f1b8acd35fa26ecf552bf2ef21d416a44b7eeda5e5e9ebaa9",
            ArtifactReleaseTrack.STAGING,
        ),
        TrustedArtifactIdentity(
            InstallerComponentTrustRegistry.DESKTOP_COMPONENT_ID,
            InstallerComponentTrustRegistry.CURRENT_DESKTOP_PACKAGE_NAME,
            "d5ed175f00bd64be4de15ecc03b3eb3d1019305a89637e0a1c6b4df314aa2417",
            ArtifactReleaseTrack.RELEASE,
        ),
        TrustedArtifactIdentity(
            InstallerComponentTrustRegistry.LYRICS_COMPONENT_ID,
            "com.tcrrry.desktoplyrics",
            DEBUG_CERTIFICATE_SHA256,
            ArtifactReleaseTrack.DEBUG,
        ),
        TrustedArtifactIdentity(
            InstallerComponentTrustRegistry.LYRICS_COMPONENT_ID,
            InstallerComponentTrustRegistry.LYRICS_PACKAGE_NAME,
            DEBUG_CERTIFICATE_SHA256,
            ArtifactReleaseTrack.DEBUG,
        ),
        TrustedArtifactIdentity(
            InstallerComponentTrustRegistry.LYRICS_COMPONENT_ID,
            InstallerComponentTrustRegistry.CURRENT_LYRICS_PACKAGE_NAME,
            "1eb136fffd3f1e4c204d0933cab66c51ee4536a29e949b9c080925c01563b51d",
            ArtifactReleaseTrack.STAGING,
        ),
        TrustedArtifactIdentity(
            InstallerComponentTrustRegistry.LYRICS_COMPONENT_ID,
            InstallerComponentTrustRegistry.CURRENT_LYRICS_PACKAGE_NAME,
            "934b9151fe62b39a3474a11f00c2114c7f392b18fec85f39f8d71b9596860e03",
            ArtifactReleaseTrack.RELEASE,
        ),
        TrustedArtifactIdentity(
            InstallerComponentTrustRegistry.CAST_COMPONENT_ID,
            "com.tcrrry.desktopcast",
            DEBUG_CERTIFICATE_SHA256,
            ArtifactReleaseTrack.DEBUG,
        ),
        TrustedArtifactIdentity(
            InstallerComponentTrustRegistry.CAST_COMPONENT_ID,
            InstallerComponentTrustRegistry.CAST_PACKAGE_NAME,
            DEBUG_CERTIFICATE_SHA256,
            ArtifactReleaseTrack.DEBUG,
        ),
        TrustedArtifactIdentity(
            InstallerComponentTrustRegistry.CAST_COMPONENT_ID,
            InstallerComponentTrustRegistry.CURRENT_CAST_PACKAGE_NAME,
            "98740b95c30064f727b9401a851ecf2e576d5e5c38fcc318284578747ba50e2a",
            ArtifactReleaseTrack.STAGING,
        ),
        TrustedArtifactIdentity(
            InstallerComponentTrustRegistry.CAST_COMPONENT_ID,
            InstallerComponentTrustRegistry.CURRENT_CAST_PACKAGE_NAME,
            "14e4a7cdf1481afdb871487aa830bb0dc28910c0ba681693f11f9f1dd2fd4423",
            ArtifactReleaseTrack.RELEASE,
        ),
        // The Fossify build is currently only audited as a Debug artifact.
        TrustedArtifactIdentity(
            InstallerComponentTrustRegistry.FILE_MANAGER_COMPONENT_ID,
            InstallerComponentTrustRegistry.FILE_MANAGER_PACKAGE_NAME,
            DEBUG_CERTIFICATE_SHA256,
            ArtifactReleaseTrack.DEBUG,
        ),
    )

    val profiles: List<TrustedPublisherProfile> = listOf(
        TrustedPublisherProfile("nine-studio", listOf(nineStudioCertificate) + nineStudioStagingCertificates + nineStudioProductionCertificates),
        TrustedPublisherProfile("fossify-approved", listOf(fossifyCertificate)),
        TrustedPublisherProfile(InstallerSelfIdentity.TRUST_PROFILE_ID, helperCertificates),
    )

    val certificates: List<TrustedPublisherCertificate> = listOf(
        nineStudioCertificate,
        *nineStudioStagingCertificates.toTypedArray(),
        *nineStudioProductionCertificates.toTypedArray(),
        TrustedPublisherCertificate(
            certificateSha256 = DEBUG_CERTIFICATE_SHA256,
            packagePrefixes = setOf("org.fossify."),
        ),
        *helperCertificates.toTypedArray(),
    )

    fun isTrusted(packageName: String, certificateDigests: Set<String>): Boolean = certificates.any { trusted ->
        trusted.packagePrefixes.any { prefix -> matchesPackagePrefix(packageName, prefix) } &&
            certificateDigests.any { it.equals(trusted.certificateSha256, ignoreCase = true) }
    }

    fun isKnownProfile(profileId: String): Boolean = profiles.any { it.id == profileId }

    fun isTrusted(profileId: String, packageName: String, certificateDigests: Set<String>): Boolean =
        profiles.firstOrNull { it.id == profileId }?.certificates?.any { trusted ->
            trusted.packagePrefixes.any { prefix -> matchesPackagePrefix(packageName, prefix) } &&
                certificateDigests.any { it.equals(trusted.certificateSha256, ignoreCase = true) }
        } == true

    fun trackFor(environment: String, channel: String): ArtifactReleaseTrack = when {
        environment.equals("production", ignoreCase = true) ||
            channel.equals("release", ignoreCase = true) -> ArtifactReleaseTrack.RELEASE

        environment.equals("staging", ignoreCase = true) -> ArtifactReleaseTrack.STAGING
        else -> ArtifactReleaseTrack.DEBUG
    }

    /** Returns the exact identity that may be installed for this config. */
    fun matchComponentIdentity(
        componentId: String,
        profileId: String,
        environment: String,
        channel: String,
        packageName: String,
        certificateDigests: Set<String>,
    ): TrustedArtifactIdentity? {
        if (componentId == InstallerSelfIdentity.COMPONENT_ID ||
            componentId == InstallerSelfIdentity.LEGACY_COMPONENT_ID
        ) {
            return helperCertificates
                .asSequence()
                .filter { trusted ->
                    trusted.packagePrefixes.any { prefix -> matchesPackagePrefix(packageName, prefix) } &&
                        certificateDigests.any { it.equals(trusted.certificateSha256, ignoreCase = true) }
                }
                .map { trusted ->
                    TrustedArtifactIdentity(
                        componentId = componentId,
                        packageName = packageName,
                        certificateSha256 = trusted.certificateSha256,
                        track = trackFor(environment, channel),
                    )
                }
                .firstOrNull()
        }
        if (componentId !in InstallerComponentTrustRegistry.ids()) {
            return profiles.firstOrNull { it.id == profileId }
                ?.certificates
                ?.asSequence()
                ?.filter { trusted ->
                    trusted.packagePrefixes.any { prefix -> matchesPackagePrefix(packageName, prefix) } &&
                        certificateDigests.any { it.equals(trusted.certificateSha256, ignoreCase = true) }
                }
                ?.map { trusted ->
                    TrustedArtifactIdentity(
                        componentId = componentId,
                        packageName = packageName,
                        certificateSha256 = trusted.certificateSha256,
                        track = trackFor(environment, channel),
                    )
                }
                ?.firstOrNull()
        }
        val expectedTrack = trackFor(environment, channel)
        val acceptedTracks = buildList {
            add(expectedTrack)
            // This is an explicit, temporary product fact: Fossify has no
            // audited staging/release certificate yet, so its Debug APK is the
            // only accepted identity while the config remains staging.
            if (componentId == InstallerComponentTrustRegistry.FILE_MANAGER_COMPONENT_ID) {
                add(ArtifactReleaseTrack.DEBUG)
            }
        }.distinct()
        return componentIdentities.firstOrNull { identity ->
            identity.componentId == componentId &&
                identity.track in acceptedTracks &&
                identity.packageName == packageName &&
                certificateDigests.any { it.equals(identity.certificateSha256, ignoreCase = true) } &&
                isTrusted(profileId, packageName, setOf(identity.certificateSha256))
        }
    }

    fun identitiesFor(componentId: String): List<TrustedArtifactIdentity> =
        componentIdentities.filter { it.componentId == componentId }

    fun expectedCertificate(
        componentId: String,
        profileId: String,
        environment: String,
        channel: String,
        packageName: String,
        certificateDigests: Set<String>,
    ): String? = matchComponentIdentity(
        componentId,
        profileId,
        environment,
        channel,
        packageName,
        certificateDigests,
    )?.certificateSha256

    private fun matchesPackagePrefix(packageName: String, prefix: String): Boolean =
        if (prefix.endsWith('.')) {
            packageName.startsWith(prefix)
        } else {
            packageName == prefix || packageName.startsWith("$prefix.")
        }

    fun trustedCertificateSha256(profileId: String, certificateDigests: Set<String>): String? =
        profiles.firstOrNull { it.id == profileId }?.certificates
            ?.asSequence()
            ?.map { it.certificateSha256 }
            ?.firstOrNull { expected -> certificateDigests.any { it.equals(expected, ignoreCase = true) } }
}
