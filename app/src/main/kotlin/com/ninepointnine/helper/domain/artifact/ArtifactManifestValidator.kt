package com.ninepointnine.helper.domain.artifact

/** Strict, deterministic validation for signed catalog data. */
object ArtifactManifestValidator {
    const val SUPPORTED_SCHEMA_VERSION = 1
    const val MAX_ARCHIVE_SIZE_BYTES = 1L shl 30
    const val MAX_APK_SIZE_BYTES = 1L shl 30

    private val componentIdPattern = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    private val packageNamePattern = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
    private val sha256Pattern = Regex("^[0-9a-fA-F]{64}$")

    fun validate(manifest: ArtifactManifest): ManifestValidation = when {
        manifest.schemaVersion != SUPPORTED_SCHEMA_VERSION -> invalid("manifest_schema_unsupported")
        !componentIdPattern.matches(manifest.componentId) &&
            !InstallerSelfIdentity.isSelfComponentId(manifest.componentId) -> invalid("component_id_invalid")
        manifest.displayName.isBlank() -> invalid("component_display_name_missing")
        manifest.description.toByteArray(Charsets.UTF_8).size > 512 -> invalid("component_description_invalid")
        manifest.version.isInvalid() -> invalid("component_version_invalid")
        manifest.apkVersion.isInvalid() -> invalid("apk_version_invalid")
        manifest.compatibility.isInvalid() -> invalid("compatibility_range_invalid")
        manifest.archiveFormat != "zip" -> invalid("archive_format_unsupported")
        !isSimpleZipName(manifest.archiveFileName) -> invalid("archive_file_name_invalid")
        !manifest.localOnly && (manifest.archiveSizeBytes <= 0L || manifest.archiveSizeBytes > MAX_ARCHIVE_SIZE_BYTES) ->
            invalid("archive_size_invalid")
        !manifest.localOnly && !sha256Pattern.matches(manifest.archiveSha256) -> invalid("archive_sha256_invalid")
        !isSimpleApkName(manifest.apkEntryName) -> invalid("apk_entry_name_invalid")
        manifest.apkSizeBytes <= 0L || manifest.apkSizeBytes > MAX_APK_SIZE_BYTES ->
            invalid("apk_size_invalid")
        !sha256Pattern.matches(manifest.apkSha256) -> invalid("apk_sha256_invalid")
        !packageNamePattern.matches(manifest.packageName) -> invalid("apk_package_name_invalid")
        !sha256Pattern.matches(manifest.certificateSha256) -> invalid("certificate_sha256_invalid")
        manifest.certificateSha256s.isEmpty() || manifest.certificateSha256s.size > 16 ||
            manifest.certificateSha256s.any { !sha256Pattern.matches(it) } -> invalid("certificate_sha256_invalid")
        manifest.sources.isEmpty() || manifest.sources.size > ArtifactSourceKind.AUTOMATIC_ORDER.size ->
            invalid("source_count_invalid")
        manifest.sources.map { it.kind }.toSet().size != manifest.sources.size ->
            invalid("source_duplicate")
        manifest.localOnly && (
            manifest.sources.size != 1 ||
                manifest.sources.singleOrNull()?.kind != ArtifactSourceKind.LOCAL_DOWNLOAD ||
                manifest.sources.singleOrNull()?.url != LOCAL_DOWNLOAD_URL
            ) -> invalid("local_source_invalid")
        !manifest.localOnly && manifest.sources.any { it.url.isBlank() } -> invalid("source_url_missing")
        else -> ManifestValidation.Valid
    }

    fun validateCatalog(manifests: List<ArtifactManifest>): ManifestValidation {
        if (manifests.isEmpty()) return invalid("catalog_empty")
        if (manifests.map { it.componentId }.toSet().size != manifests.size) {
            return invalid("catalog_component_duplicate")
        }
        manifests.forEach { manifest ->
            val result = validate(manifest)
            if (result is ManifestValidation.Invalid) return result
        }
        return ManifestValidation.Valid
    }

    private fun ArtifactVersion.isInvalid(): Boolean = name.isBlank() || code <= 0L

    private fun CompatibilityRange.isInvalid(): Boolean =
        minAndroidSdk < 1 || (maxAndroidSdk != null && maxAndroidSdk < minAndroidSdk)

    private fun isSimpleZipName(value: String): Boolean =
        value.isNotBlank() &&
            value.endsWith(".zip", ignoreCase = true) &&
            value.none {
                it == '/' || it == '\\' || it == '\u0000' || it == ':' || it.isISOControl()
            } &&
            !value.startsWith('.') &&
            value != "." &&
            value != ".."

    private fun isSimpleApkName(value: String): Boolean =
        value.isNotBlank() &&
            value.endsWith(".apk", ignoreCase = true) &&
            value.none {
                it == '/' || it == '\\' || it == '\u0000' || it == ':' || it.isISOControl()
            } &&
            !value.startsWith('.') &&
            value != "." &&
            value != ".."

    private fun invalid(reasonCode: String): ManifestValidation.Invalid =
        ManifestValidation.Invalid(reasonCode)

    const val LOCAL_DOWNLOAD_URL = "content://public-download"
}

sealed interface ManifestValidation {
    data object Valid : ManifestValidation
    data class Invalid(val reasonCode: String) : ManifestValidation
}
