package com.tcrrry.helper.data.catalog

import com.tcrrry.helper.domain.artifact.ArtifactManifestValidator
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Signed control-plane configuration for the Lanzou folder source. */
@Serializable
data class SignedInstallerConfigEnvelope(
    val schemaVersion: Int,
    val configVersion: String,
    val keyId: String,
    val signatureAlgorithm: String,
    val payloadBase64: String,
    val signatureBase64: String,
)

@Serializable
data class InstallerDistributionConfigPayload(
    val schemaVersion: Int,
    val channel: String,
    val expiresAt: String,
    val folderUrl: String,
    val folderPassword: String,
    val components: List<InstallerComponentSourceDocument>,
)

@Serializable
data class InstallerComponentSourceDocument(
    val componentId: String,
    val archiveFileName: String,
    val required: Boolean,
    val displayName: String? = null,
    val minAndroidSdk: Int? = null,
    val packageName: String? = null,
    val certificateSha256: String? = null,
)

data class InstallerDistributionConfig(
    val configVersion: String,
    val channel: String,
    val expiresAt: Instant,
    val folderUrl: String,
    val folderPassword: String,
    val components: List<InstallerComponentSource>,
    val keyId: String,
    val signatureAlgorithm: String,
)

data class InstallerComponentSource(
    val componentId: String,
    val archiveFileName: String,
    val required: Boolean,
    val displayName: String,
    val minAndroidSdk: Int,
    val packageName: String,
    val certificateSha256: String,
)

sealed interface DistributionConfigLoadResult {
    data class Success(val config: InstallerDistributionConfig) : DistributionConfigLoadResult

    data class Failure(
        val reasonCode: String,
        val retryable: Boolean,
    ) : DistributionConfigLoadResult
}

/** Reads the small cloud control plane; credentials never leave this object as a persisted value. */
class CloudInstallerDistributionConfigAdapter(
    private val transport: ReleaseCatalogTransport,
    private val signatureVerifier: CatalogSignatureVerifier,
    private val expectedChannel: String,
    private val now: () -> Instant = Instant::now,
    private val json: Json = CloudReleaseCatalogAdapter.STRICT_JSON,
) {
    suspend fun load(): DistributionConfigLoadResult {
        val response = try {
            transport.fetch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CatalogTransportFailure) {
            return DistributionConfigLoadResult.Failure(failure.reasonCode, failure.retryable)
        } catch (_: Exception) {
            return DistributionConfigLoadResult.Failure("distribution_config_transport_failed", retryable = true)
        }
        if (response.statusCode !in 200..299) {
            return DistributionConfigLoadResult.Failure(
                "distribution_config_http_${response.statusCode}",
                retryable = response.statusCode >= 500,
            )
        }
        if (response.body.isEmpty() || response.body.size > MAX_CONFIG_BYTES) {
            return DistributionConfigLoadResult.Failure("distribution_config_size_invalid", retryable = false)
        }
        if (response.contentType?.lowercase()?.startsWith("text/html") == true) {
            return DistributionConfigLoadResult.Failure("distribution_config_html_response", retryable = true)
        }

        val envelope = try {
            json.decodeFromString<SignedInstallerConfigEnvelope>(response.body.decodeUtf8Strict())
        } catch (_: Exception) {
            return DistributionConfigLoadResult.Failure("distribution_config_envelope_invalid", retryable = false)
        }
        if (
            envelope.schemaVersion != SUPPORTED_SCHEMA_VERSION ||
            envelope.configVersion.isBlank() ||
            envelope.keyId.isBlank()
        ) {
            return DistributionConfigLoadResult.Failure("distribution_config_envelope_fields_missing", retryable = false)
        }
        val payload = try {
            Base64.getDecoder().decode(envelope.payloadBase64)
        } catch (_: IllegalArgumentException) {
            return DistributionConfigLoadResult.Failure("distribution_config_payload_encoding_invalid", retryable = false)
        }
        val signature = try {
            Base64.getDecoder().decode(envelope.signatureBase64)
        } catch (_: IllegalArgumentException) {
            return DistributionConfigLoadResult.Failure("distribution_config_signature_encoding_invalid", retryable = false)
        }
        if (!signatureVerifier.verify(envelope.keyId, envelope.signatureAlgorithm, payload, signature)) {
            return DistributionConfigLoadResult.Failure("distribution_config_signature_invalid", retryable = false)
        }

        val document = try {
            json.decodeFromString<InstallerDistributionConfigPayload>(payload.decodeUtf8Strict())
        } catch (_: Exception) {
            return DistributionConfigLoadResult.Failure("distribution_config_payload_invalid", retryable = false)
        }
        if (document.schemaVersion != SUPPORTED_SCHEMA_VERSION || document.channel != expectedChannel) {
            return DistributionConfigLoadResult.Failure("distribution_config_channel_invalid", retryable = false)
        }
        val expiresAt = try {
            Instant.parse(document.expiresAt)
        } catch (_: Exception) {
            return DistributionConfigLoadResult.Failure("distribution_config_expiry_invalid", retryable = false)
        }
        if (!expiresAt.isAfter(now())) {
            return DistributionConfigLoadResult.Failure("distribution_config_expired", retryable = true)
        }
        if (document.folderPassword.isBlank() || document.folderPassword.length > MAX_PASSWORD_LENGTH) {
            return DistributionConfigLoadResult.Failure("distribution_config_password_invalid", retryable = false)
        }
        if (!isLanzouFolderUrl(document.folderUrl)) {
            return DistributionConfigLoadResult.Failure("distribution_config_folder_url_invalid", retryable = false)
        }
        if (document.components.size != EXPECTED_COMPONENT_IDS.size) {
            return DistributionConfigLoadResult.Failure("distribution_config_component_count_invalid", retryable = false)
        }
        val componentIds = document.components.map { it.componentId }
        if (componentIds.toSet() != EXPECTED_COMPONENT_IDS) {
            return DistributionConfigLoadResult.Failure("distribution_config_component_set_invalid", retryable = false)
        }
        if (componentIds.size != componentIds.toSet().size) {
            return DistributionConfigLoadResult.Failure("distribution_config_component_duplicate", retryable = false)
        }
        if (document.components.count { it.required } != 1 || document.components.none { it.componentId == DESKTOP_COMPONENT_ID && it.required }) {
            return DistributionConfigLoadResult.Failure("distribution_config_required_component_invalid", retryable = false)
        }
        if (document.components.any { !isSimpleZipName(it.archiveFileName) }) {
            return DistributionConfigLoadResult.Failure("distribution_config_archive_name_invalid", retryable = false)
        }
        if (document.components.map { it.archiveFileName.lowercase() }.toSet().size != document.components.size) {
            return DistributionConfigLoadResult.Failure("distribution_config_archive_duplicate", retryable = false)
        }
        if (document.components.any { component ->
                component.packageName.isNullOrBlank() || !PACKAGE_NAME_PATTERN.matches(component.packageName)
            }) {
            return DistributionConfigLoadResult.Failure("distribution_config_package_invalid", retryable = false)
        }
        if (document.components.any { component ->
                component.certificateSha256.isNullOrBlank() || !SHA256_PATTERN.matches(component.certificateSha256)
            }) {
            return DistributionConfigLoadResult.Failure("distribution_config_certificate_invalid", retryable = false)
        }
        if (document.components.any { it.minAndroidSdk != null && it.minAndroidSdk !in 1..100 }) {
            return DistributionConfigLoadResult.Failure("distribution_config_compatibility_invalid", retryable = false)
        }
        val components = document.components.map { component ->
            InstallerComponentSource(
                componentId = component.componentId,
                archiveFileName = component.archiveFileName,
                required = component.required,
                displayName = component.displayName?.trim().takeUnless { it.isNullOrBlank() }
                    ?: defaultDisplayName(component.componentId),
                minAndroidSdk = component.minAndroidSdk ?: defaultMinAndroidSdk(component.componentId),
                packageName = checkNotNull(component.packageName),
                certificateSha256 = checkNotNull(component.certificateSha256).lowercase(),
            )
        }
        return DistributionConfigLoadResult.Success(
            InstallerDistributionConfig(
                configVersion = envelope.configVersion,
                channel = document.channel,
                expiresAt = expiresAt,
                folderUrl = document.folderUrl,
                folderPassword = document.folderPassword,
                components = components,
                keyId = envelope.keyId,
                signatureAlgorithm = envelope.signatureAlgorithm,
            ),
        )
    }

    private fun isLanzouFolderUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val allowedHost = setOf("lanzou.com", "lanzouw.com", "lanzoux.com", "lanzoui.com", "lanzouy.com")
            .any { host == it || host.endsWith(".$it") }
        val path = uri.path.orEmpty().trim('/').split('/').filter(String::isNotBlank)
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null &&
            uri.fragment.isNullOrBlank() &&
            allowedHost &&
            path.size == 1 &&
            path.single().matches(Regex("^b[a-zA-Z0-9]+$"))
    }

    private fun isSimpleZipName(value: String): Boolean =
        value.isNotBlank() &&
            value.endsWith(".zip", ignoreCase = true) &&
            value.none { it == '/' || it == '\\' || it == '\u0000' } &&
            value != "." && value != ".."

    private fun defaultDisplayName(componentId: String): String = when (componentId) {
        "desktop" -> "03桌面"
        "lyrics" -> "03歌词"
        "file-manager" -> "文件管理器"
        else -> componentId
    }

    private fun defaultMinAndroidSdk(componentId: String): Int = when (componentId) {
        "desktop" -> 28
        "lyrics", "file-manager" -> 26
        else -> 1
    }

    private companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val MAX_CONFIG_BYTES = 256 * 1024
        const val MAX_PASSWORD_LENGTH = 128
        const val DESKTOP_COMPONENT_ID = "desktop"
        val EXPECTED_COMPONENT_IDS = setOf("desktop", "lyrics", "file-manager")
        val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
        val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}

private fun ByteArray.decodeUtf8Strict(): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(this))
    .toString()
