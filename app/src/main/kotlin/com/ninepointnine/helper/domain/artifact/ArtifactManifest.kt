package com.ninepointnine.helper.domain.artifact

import com.ninepointnine.helper.BuildConfig
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
    val certificateSha256s: Set<String> = setOf(certificateSha256),
) {
    fun matchesCertificates(actual: Set<String>): Boolean =
        certificateSha256s.isNotEmpty() &&
            actual.map { it.lowercase(java.util.Locale.ROOT) }.toSet() ==
            certificateSha256s.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
}

/** Stable identity for the installer APK when Cloud publishes it alongside car apps. */
object InstallerSelfIdentity {
    const val COMPONENT_ID = "03helper"
    const val LEGACY_COMPONENT_ID = "helper"
    const val PRODUCTION_PACKAGE_NAME = "com.ninepointnine.helper"
    const val TEST_PACKAGE_NAME = "com.ninepointnine.helper.test"
    /** The package identity of the running variant (Release or Debug/test). */
    val PACKAGE_NAME: String = BuildConfig.APPLICATION_ID

    private val componentIds = setOf(COMPONENT_ID, LEGACY_COMPONENT_ID)

    fun isSelfComponentId(componentId: String): Boolean = componentId.lowercase() in componentIds
}

/** Known product aliases for existing device capabilities; never distribution approval. */
object KnownApplicationPackages {
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
    const val CURRENT_DESKTOP_TEST_PACKAGE_NAME = "com.ninepointnine.desktop.test"
    const val CURRENT_LYRICS_TEST_PACKAGE_NAME = "com.ninepointnine.desktoplyrics.test"
    const val CURRENT_CAST_TEST_PACKAGE_NAME = "com.ninepointnine.desktopcast.test"
    const val FILE_MANAGER_PACKAGE_NAME = "org.fossify.filemanager.debug"

    fun ids(): Set<String> = setOf(DESKTOP_COMPONENT_ID, LYRICS_COMPONENT_ID, CAST_COMPONENT_ID, FILE_MANAGER_COMPONENT_ID)

    /** Package aliases used to identify existing device capabilities across application variants. */
    fun aliasesFor(componentId: String): Set<String> = when (componentId) {
        DESKTOP_COMPONENT_ID -> setOf(DESKTOP_PACKAGE_NAME, CURRENT_DESKTOP_PACKAGE_NAME, CURRENT_DESKTOP_TEST_PACKAGE_NAME)
        LYRICS_COMPONENT_ID -> setOf(LYRICS_PACKAGE_NAME, CURRENT_LYRICS_PACKAGE_NAME, CURRENT_LYRICS_TEST_PACKAGE_NAME)
        CAST_COMPONENT_ID -> setOf(CAST_PACKAGE_NAME, "com.tcrrry.desktopcast", CURRENT_CAST_TEST_PACKAGE_NAME)
        FILE_MANAGER_COMPONENT_ID -> setOf(FILE_MANAGER_PACKAGE_NAME)
        else -> emptySet()
    }

    fun matchesComponent(componentId: String, packageName: String): Boolean =
        packageName in aliasesFor(componentId)

}
