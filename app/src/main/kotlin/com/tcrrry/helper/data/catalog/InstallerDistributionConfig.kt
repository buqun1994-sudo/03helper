package com.tcrrry.helper.data.catalog

import com.tcrrry.helper.domain.artifact.InstallerComponentTrustRegistry
import com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy
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
) {
    override fun toString(): String =
        "SignedInstallerConfigEnvelope(schemaVersion=$schemaVersion, configVersion=$configVersion, " +
            "keyId=$keyId, signatureAlgorithm=$signatureAlgorithm, payloadBase64=<redacted>, " +
            "signatureBase64=<redacted>)"
}

@Serializable
data class InstallerDistributionConfigPayload(
    val schemaVersion: Int,
    val channel: String,
    val expiresAt: String,
    val folderUrl: String,
    val folderPassword: String,
    val previousVersionsUrl: String,
    val previousVersionsPassword: String,
    val components: List<InstallerComponentSourceDocument>,
) {
    override fun toString(): String =
        "InstallerDistributionConfigPayload(schemaVersion=$schemaVersion, channel=$channel, " +
            "expiresAt=$expiresAt, folderUrl=<redacted>, folderPassword=<redacted>, " +
            "previousVersionsUrl=<redacted>, previousVersionsPassword=<redacted>, components=$components)"
}

/** V2 deliberately contains no package, certificate or compatibility fields. */
@Serializable
data class InstallerComponentSourceDocument(
    val componentId: String,
    val archiveFileName: String,
    val required: Boolean,
)

data class InstallerDistributionConfig(
    val configVersion: String,
    val channel: String,
    val expiresAt: Instant,
    val folderUrl: String,
    val folderPassword: String,
    val previousVersionsUrl: String,
    val previousVersionsPassword: String,
    val components: List<InstallerComponentSource>,
    val keyId: String,
    val signatureAlgorithm: String,
) {
    override fun toString(): String =
        "InstallerDistributionConfig(configVersion=$configVersion, channel=$channel, expiresAt=$expiresAt, " +
            "folderUrl=<redacted>, folderPassword=<redacted>, previousVersionsUrl=<redacted>, " +
            "previousVersionsPassword=<redacted>, components=$components, keyId=$keyId, " +
            "signatureAlgorithm=$signatureAlgorithm)"
}

/** Runtime component identity after the V2 payload has been joined to trust data. */
data class InstallerComponentSource(
    val componentId: String,
    val archiveFileName: String,
    val required: Boolean,
    val displayName: String,
    val minAndroidSdk: Int,
    val packageName: String,
    val certificateSha256: String,
    val apkEntryName: String,
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
    private val sourcePolicy: ReleaseSourcePolicy = ReleaseSourcePolicy(),
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
        if (envelope.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return DistributionConfigLoadResult.Failure("distribution_config_schema_unsupported", retryable = false)
        }
        if (envelope.configVersion.isBlank() || envelope.keyId.isBlank()) {
            return DistributionConfigLoadResult.Failure("distribution_config_envelope_fields_missing", retryable = false)
        }
        if (envelope.signatureAlgorithm !in SUPPORTED_SIGNATURE_ALGORITHMS) {
            return DistributionConfigLoadResult.Failure(
                "distribution_config_signature_algorithm_invalid",
                retryable = false,
            )
        }

        val payload = try {
            Base64.getDecoder().decode(envelope.payloadBase64)
        } catch (_: IllegalArgumentException) {
            return DistributionConfigLoadResult.Failure("distribution_config_payload_encoding_invalid", retryable = false)
        }
        if (payload.isEmpty() || payload.size > MAX_CONFIG_BYTES) {
            return DistributionConfigLoadResult.Failure("distribution_config_payload_size_invalid", retryable = false)
        }
        val signature = try {
            Base64.getDecoder().decode(envelope.signatureBase64)
        } catch (_: IllegalArgumentException) {
            return DistributionConfigLoadResult.Failure("distribution_config_signature_encoding_invalid", retryable = false)
        }
        if (signature.isEmpty() || !signatureVerifier.verify(envelope.keyId, envelope.signatureAlgorithm, payload, signature)) {
            return DistributionConfigLoadResult.Failure("distribution_config_signature_invalid", retryable = false)
        }

        val document = try {
            json.decodeFromString<InstallerDistributionConfigPayload>(payload.decodeUtf8Strict())
        } catch (_: Exception) {
            return DistributionConfigLoadResult.Failure("distribution_config_payload_invalid", retryable = false)
        }
        if (document.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return DistributionConfigLoadResult.Failure("distribution_config_payload_schema_unsupported", retryable = false)
        }
        if (document.channel != expectedChannel) {
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
        if (!isValidPassword(document.folderPassword)) {
            return DistributionConfigLoadResult.Failure("distribution_config_password_invalid", retryable = false)
        }
        if (!sourcePolicy.isLanzouFolderUrl(document.folderUrl)) {
            return DistributionConfigLoadResult.Failure("distribution_config_folder_url_invalid", retryable = false)
        }
        if (!isValidPassword(document.previousVersionsPassword)) {
            return DistributionConfigLoadResult.Failure(
                "distribution_config_previous_password_invalid",
                retryable = false,
            )
        }
        if (document.previousVersionsUrl.isEmpty()) {
            if (document.previousVersionsPassword.isNotEmpty()) {
                return DistributionConfigLoadResult.Failure(
                    "distribution_config_previous_password_without_url",
                    retryable = false,
                )
            }
        } else if (!sourcePolicy.isLanzouFolderUrl(document.previousVersionsUrl)) {
            return DistributionConfigLoadResult.Failure(
                "distribution_config_previous_url_invalid",
                retryable = false,
            )
        }

        val components = when (val result = trustedComponents(document.components)) {
            is TrustedComponentsResult.Failure -> {
                return DistributionConfigLoadResult.Failure(result.reasonCode, retryable = false)
            }

            is TrustedComponentsResult.Success -> result.components
        }
        return DistributionConfigLoadResult.Success(
            InstallerDistributionConfig(
                configVersion = envelope.configVersion,
                channel = document.channel,
                expiresAt = expiresAt,
                folderUrl = document.folderUrl,
                folderPassword = document.folderPassword,
                previousVersionsUrl = document.previousVersionsUrl,
                previousVersionsPassword = document.previousVersionsPassword,
                components = components,
                keyId = envelope.keyId,
                signatureAlgorithm = envelope.signatureAlgorithm,
            ),
        )
    }

    private fun trustedComponents(
        documents: List<InstallerComponentSourceDocument>,
    ): TrustedComponentsResult {
        if (documents.size != InstallerComponentTrustRegistry.components.size) {
            return TrustedComponentsResult.Failure("distribution_config_component_count_invalid")
        }
        val ids = documents.map { it.componentId }
        if (ids.size != ids.toSet().size) {
            return TrustedComponentsResult.Failure("distribution_config_component_duplicate")
        }
        if (ids.toSet() != InstallerComponentTrustRegistry.ids()) {
            return TrustedComponentsResult.Failure("distribution_config_component_set_invalid")
        }
        val byId = documents.associateBy { it.componentId }
        val trusted = InstallerComponentTrustRegistry.components.map { definition ->
            val document = byId[definition.componentId]
                ?: return TrustedComponentsResult.Failure("distribution_config_component_set_invalid")
            if (document.archiveFileName != definition.archiveFileName || document.required != definition.required) {
                return TrustedComponentsResult.Failure("distribution_config_component_identity_invalid")
            }
            InstallerComponentSource(
                componentId = definition.componentId,
                archiveFileName = definition.archiveFileName,
                required = definition.required,
                displayName = definition.displayName,
                minAndroidSdk = definition.minAndroidSdk,
                packageName = definition.packageName,
                certificateSha256 = definition.certificateSha256,
                apkEntryName = definition.apkEntryName,
            )
        }
        return TrustedComponentsResult.Success(trusted)
    }

    private fun isValidPassword(value: String): Boolean =
        value.toByteArray(Charsets.UTF_8).size <= MAX_PASSWORD_BYTES

    private companion object {
        const val SUPPORTED_SCHEMA_VERSION = 2
        const val MAX_CONFIG_BYTES = 256 * 1024
        const val MAX_PASSWORD_BYTES = 512
        val SUPPORTED_SIGNATURE_ALGORITHMS = setOf("SHA256withECDSA", "Ed25519")
    }

    private sealed interface TrustedComponentsResult {
        data class Success(val components: List<InstallerComponentSource>) : TrustedComponentsResult
        data class Failure(val reasonCode: String) : TrustedComponentsResult
    }
}

private fun ByteArray.decodeUtf8Strict(): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(this))
    .toString()
