package com.ninepointnine.helper.data.artifact

import android.content.ContentResolver
import android.net.Uri
import com.ninepointnine.helper.application.artifact.PreparedArtifact
import com.ninepointnine.helper.application.artifact.UserSelectedApkPreparationResult
import com.ninepointnine.helper.application.artifact.UserSelectedApkPreparer
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactManifestValidator
import com.ninepointnine.helper.domain.artifact.ArtifactSource
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ArtifactVerification
import com.ninepointnine.helper.domain.artifact.CompatibilityRange
import com.ninepointnine.helper.domain.artifact.ManifestValidation
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface UserSelectedApkSource {
    fun accepts(uri: String): Boolean

    fun declaredLength(uri: String): Long?

    fun open(uri: String): InputStream?
}

private class ContentResolverUserSelectedApkSource(
    private val contentResolver: ContentResolver,
) : UserSelectedApkSource {
    override fun accepts(uri: String): Boolean =
        runCatching { Uri.parse(uri) }.getOrNull()?.scheme == ContentResolver.SCHEME_CONTENT

    override fun declaredLength(uri: String): Long? =
        contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")
            ?.use { descriptor -> descriptor.length }

    override fun open(uri: String): InputStream? =
        contentResolver.openInputStream(Uri.parse(uri))
}

class AndroidUserSelectedApkPreparer internal constructor(
    private val source: UserSelectedApkSource,
    private val metadataReader: ApkMetadataReader,
    private val workspace: File,
) : UserSelectedApkPreparer {
    constructor(
        contentResolver: ContentResolver,
        metadataReader: ApkMetadataReader,
        workspace: File,
    ) : this(ContentResolverUserSelectedApkSource(contentResolver), metadataReader, workspace)

    override suspend fun prepare(
        uri: String,
        targetAndroidSdk: Int,
    ): UserSelectedApkPreparationResult = withContext(Dispatchers.IO) {
        if (!source.accepts(uri)) return@withContext failed("local_apk_uri_invalid")
        if (targetAndroidSdk <= 0) return@withContext failed("local_apk_target_sdk_unknown", retryable = true)
        if (!workspace.mkdirs() && !workspace.isDirectory) {
            return@withContext failed("local_apk_workspace_unavailable", retryable = true)
        }

        val target = workspace.resolve(APK_FILE_NAME)
        target.delete()
        var keepPreparedFile = false
        try {
            val declaredLength = runCatching {
                source.declaredLength(uri)
            }.getOrNull()
            if (declaredLength != null && declaredLength >= 0L && declaredLength !in 1L..MAX_APK_BYTES) {
                return@withContext failed("local_apk_size_invalid")
            }
            val input = source.open(uri)
                ?: return@withContext failed("local_apk_read_failed", retryable = true)
            input.use { source ->
                FileOutputStream(target).use { sink ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > MAX_APK_BYTES) {
                            return@withContext failed("local_apk_size_invalid")
                        }
                        sink.write(buffer, 0, count)
                    }
                    sink.fd.sync()
                }
            }
            if (target.length() !in 1L..MAX_APK_BYTES) return@withContext failed("local_apk_size_invalid")

            val metadata = runCatching { metadataReader.read(target) }.getOrNull()
                ?: return@withContext failed("local_apk_metadata_unreadable")
            if (!PACKAGE_NAME_PATTERN.matches(metadata.packageName)) return@withContext failed("local_apk_package_invalid")
            if (metadata.version.code <= 0L || metadata.version.name.isBlank()) {
                return@withContext failed("local_apk_version_invalid")
            }
            if (metadata.splitName != null) return@withContext failed("local_apk_split_not_supported")
            val minAndroidSdk = metadata.minAndroidSdk
                ?: return@withContext failed("local_apk_min_sdk_unavailable")
            if (minAndroidSdk > targetAndroidSdk) return@withContext failed("local_apk_incompatible")
            val certificates = metadata.certificateSha256s.map { it.lowercase() }.toSet()
            if (certificates.isEmpty() || certificates.size > MAX_CERTIFICATE_COUNT ||
                certificates.any { !SHA256_PATTERN.matches(it) }
            ) {
                return@withContext failed("local_apk_signature_invalid")
            }
            val digest = runCatching { sha256(target) }.getOrNull()
                ?: return@withContext failed("local_apk_hash_failed")
            val componentId = localComponentId(metadata.packageName)
            val certificate = certificates.sorted().first()
            val manifest = ArtifactManifest(
                schemaVersion = ArtifactManifestValidator.SUPPORTED_SCHEMA_VERSION,
                componentId = componentId,
                displayName = safeDisplayName(metadata.displayName) ?: metadata.packageName,
                required = false,
                version = metadata.version,
                compatibility = CompatibilityRange(minAndroidSdk = minAndroidSdk),
                archiveFileName = "$componentId.zip",
                archiveSizeBytes = 0L,
                archiveSha256 = "",
                apkEntryName = APK_FILE_NAME,
                apkSizeBytes = target.length(),
                apkSha256 = digest,
                packageName = metadata.packageName,
                apkVersion = metadata.version,
                certificateSha256 = certificate,
                certificateSha256s = certificates,
                sources = listOf(
                    ArtifactSource(
                        kind = ArtifactSourceKind.USER_SELECTED_APK,
                        url = ArtifactManifestValidator.USER_SELECTED_APK_URL,
                    ),
                ),
                localOnly = true,
            )
            if (ArtifactManifestValidator.validate(manifest) !is ManifestValidation.Valid) {
                return@withContext failed("local_apk_manifest_invalid")
            }
            val verification = ArtifactVerification(
                componentId = componentId,
                sourceKind = ArtifactSourceKind.USER_SELECTED_APK,
                archiveSizeBytes = 0L,
                archiveSha256 = "",
                apkSizeBytes = target.length(),
                apkSha256 = digest,
                packageName = metadata.packageName,
                apkVersion = metadata.version,
                certificateSha256 = certificate,
                archiveDeleted = true,
                certificateSha256s = certificates,
                userSelected = true,
            )
            keepPreparedFile = true
            UserSelectedApkPreparationResult.Ready(
                artifact = PreparedArtifact(
                    manifest = manifest,
                    sourceKind = ArtifactSourceKind.USER_SELECTED_APK,
                    finalApk = target,
                    declarations = metadata.declarations,
                ),
                verification = verification,
            )
        } catch (_: SecurityException) {
            failed("local_apk_read_denied", retryable = true)
        } catch (_: Exception) {
            failed("local_apk_read_failed", retryable = true)
        } finally {
            if (!keepPreparedFile) {
                target.delete()
                workspace.delete()
            }
        }
    }

    override fun clear(artifact: PreparedArtifact) {
        val file = artifact.finalApk ?: return
        if (file.parentFile == workspace && file.name == APK_FILE_NAME) {
            file.delete()
            workspace.delete()
        }
    }

    private fun failed(reasonCode: String, retryable: Boolean = false) =
        UserSelectedApkPreparationResult.Failed(reasonCode, retryable)

    private fun localComponentId(packageName: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(packageName.toByteArray(Charsets.UTF_8))
        return "local-" + digest.take(12).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun safeDisplayName(value: String?): String? = value
        ?.trim()
        ?.takeIf { name ->
            name.isNotEmpty() &&
                name.none(Char::isISOControl) &&
                name.toByteArray(Charsets.UTF_8).size <= MAX_DISPLAY_NAME_BYTES
        }

    private companion object {
        const val APK_FILE_NAME = "selected.apk"
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_APK_BYTES = 1L shl 30
        const val MAX_CERTIFICATE_COUNT = 16
        const val MAX_DISPLAY_NAME_BYTES = 256
        val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}
