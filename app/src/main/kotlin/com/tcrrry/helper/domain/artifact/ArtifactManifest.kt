package com.tcrrry.helper.domain.artifact

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
)
