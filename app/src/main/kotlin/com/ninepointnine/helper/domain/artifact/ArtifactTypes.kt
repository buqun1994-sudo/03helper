package com.ninepointnine.helper.domain.artifact

import java.util.Locale

enum class ArtifactSourceKind(val wireName: String) {
    LANZOU_SHARE("lanzou-share"),
    R2("r2"),
    GITHUB_RELEASES("github"),
    /** A verified APK already present in the user's public Download folder. */
    LOCAL_DOWNLOAD("local-download"),
    ;

    companion object {
        fun fromWire(value: String): ArtifactSourceKind? = entries.firstOrNull { it.wireName == value }

        val AUTOMATIC_ORDER: List<ArtifactSourceKind> = listOf(
            LANZOU_SHARE,
            R2,
            GITHUB_RELEASES,
        )
    }
}

data class ArtifactVersion(
    val name: String,
    val code: Long,
)

data class CompatibilityRange(
    val minAndroidSdk: Int,
    val maxAndroidSdk: Int? = null,
    val minInstallerVersion: String? = null,
    val maxInstallerVersion: String? = null,
)

data class ArtifactSource(
    val kind: ArtifactSourceKind,
    val url: String,
)

/** A source choice recorded by the session without retaining transient URLs. */
data class SourceSelectionEvidence(
    val componentId: String,
    val sourceKind: ArtifactSourceKind,
)

data class SourceFailureRecord(
    val componentId: String,
    val sourceKind: ArtifactSourceKind,
    val reasonCode: String,
    val retryable: Boolean,
)

data class ArchiveDownloadEvidence(
    val componentId: String,
    val sizeBytes: Long,
    val sha256: String,
    val resumed: Boolean = false,
)

data class ArchiveVerificationEvidence(
    val componentId: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class ApkExtractionEvidence(
    val componentId: String,
    val entryName: String,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * The only artifact identity proof allowed to cross into InstallationSession.
 * No file path is retained here; the path remains inside the private cache
 * owner for the current session.
 */
data class ArtifactVerification(
    val componentId: String,
    val sourceKind: ArtifactSourceKind,
    val archiveSizeBytes: Long,
    val archiveSha256: String,
    val apkSizeBytes: Long,
    val apkSha256: String,
    val packageName: String,
    val apkVersion: ArtifactVersion,
    val certificateSha256: String,
    val archiveDeleted: Boolean,
    /** True when the APK was verified from public Download without a ZIP. */
    val localDownload: Boolean = false,
)

/** In-memory only context returned by a source adapter to the downloader. */
data class ResolvedDownloadRequest(
    val sourceKind: ArtifactSourceKind,
    val url: String,
    val userAgent: String? = null,
    val cookie: String? = null,
    val referer: String? = null,
    val contentDisposition: String? = null,
    val mimeType: String? = null,
    val contentLength: Long? = null,
) {
    override fun toString(): String =
        "ResolvedDownloadRequest(sourceKind=$sourceKind, url=<redacted>, userAgent=<redacted>, " +
            "cookie=<redacted>, referer=<redacted>, contentDisposition=<redacted>, " +
            "mimeType=$mimeType, contentLength=$contentLength)"
}

enum class ArtifactFailurePhase {
    CATALOG,
    SOURCE_RESOLUTION,
    DOWNLOAD,
    ARCHIVE_VERIFICATION,
    EXTRACTION,
    APK_VERIFICATION,
    CACHE,
}

data class ArtifactFailure(
    val phase: ArtifactFailurePhase,
    val componentId: String? = null,
    val sourceKind: ArtifactSourceKind? = null,
    val reasonCode: String,
    val retryable: Boolean,
)

fun ArtifactManifest.toComponentDescriptor(
    androidSdk: Int? = null,
): com.ninepointnine.helper.domain.session.ComponentDescriptor =
    com.ninepointnine.helper.domain.session.ComponentDescriptor(
        id = componentId,
        displayName = displayName,
        required = required,
        description = description,
        versionLabel = formatArtifactVersionLabel(version.name),
        // The selection and result surfaces describe the installable APK, not
        // its transport ZIP. The APK size is the signed Cloud metadata field.
        sizeLabel = formatArtifactSizeLabel(apkSizeBytes),
        // Keep the compatibility gate in the domain while exposing only plain language.
        compatibilityLabel = when {
            androidSdk == null -> null
            androidSdk < compatibility.minAndroidSdk -> "当前车机暂不支持此应用"
            compatibility.maxAndroidSdk?.let { androidSdk > it } == true -> "当前车机暂不支持此应用"
            else -> "适用于当前车机"
        },
        compatibilityState = when {
            androidSdk == null -> com.ninepointnine.helper.domain.session.ComponentCompatibility.UNKNOWN
            androidSdk < compatibility.minAndroidSdk -> com.ninepointnine.helper.domain.session.ComponentCompatibility.UNSUPPORTED
            compatibility.maxAndroidSdk?.let { androidSdk > it } == true ->
                com.ninepointnine.helper.domain.session.ComponentCompatibility.UNSUPPORTED
            else -> com.ninepointnine.helper.domain.session.ComponentCompatibility.SUPPORTED
        },
        iconKey = componentId,
        status = com.ninepointnine.helper.domain.session.ComponentStatus.AVAILABLE,
    )

/** Formats the only version text that may reach the user-facing app rows. */
fun formatArtifactVersionLabel(versionName: String): String? {
    val normalized = versionName.trim()
        .removePrefix("V")
        .removePrefix("v")
        .trim()
    return normalized.takeIf { it.isNotEmpty() }?.let { "V$it" }
}

/** Formats APK bytes compactly, using one decimal place and no padding space. */
fun formatArtifactSizeLabel(bytes: Long): String? {
    if (bytes <= 0L) return null
    val units = arrayOf("B", "K", "M", "G", "T")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    val number = if (unitIndex == 0) {
        bytes.toString()
    } else {
        String.format(Locale.US, "%.1f", value)
            .trimEnd('0')
            .trimEnd('.')
    }
    return "$number${units[unitIndex]}"
}
