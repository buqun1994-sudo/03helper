package com.tcrrry.helper.data.catalog

import com.tcrrry.helper.domain.artifact.ArtifactManifest
import com.tcrrry.helper.domain.artifact.ArtifactManifestValidator
import com.tcrrry.helper.domain.artifact.ArtifactSourceKind
import com.tcrrry.helper.domain.artifact.ManifestValidation
import com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

data class CatalogHttpResponse(
    val statusCode: Int,
    val body: ByteArray,
    val contentType: String? = null,
)

fun interface ReleaseCatalogTransport {
    suspend fun fetch(): CatalogHttpResponse
}

class CatalogTransportFailure(
    val reasonCode: String,
    val retryable: Boolean,
) : IOException(reasonCode)

fun interface TrustedCatalogKeyResolver {
    /** Returns an X.509 SubjectPublicKeyInfo DER key, or null when unknown. */
    fun resolve(keyId: String): ByteArray?
}

fun interface CatalogSignatureVerifier {
    fun verify(
        keyId: String,
        algorithm: String,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean
}

data class TrustedArtifactCatalog(
    val catalogVersion: String,
    val keyId: String,
    val signatureAlgorithm: String,
    val manifests: List<ArtifactManifest>,
)

sealed interface CatalogLoadResult {
    data class Success(val catalog: TrustedArtifactCatalog) : CatalogLoadResult

    data class Failure(
        val reasonCode: String,
        val retryable: Boolean,
    ) : CatalogLoadResult
}

/** Reads the Cloud Android profile only after its detached signature passes. */
class CloudReleaseCatalogAdapter(
    private val transport: ReleaseCatalogTransport,
    private val signatureVerifier: CatalogSignatureVerifier,
    private val sourcePolicy: ReleaseSourcePolicy = ReleaseSourcePolicy(),
    private val json: Json = STRICT_JSON,
) {
    suspend fun load(): CatalogLoadResult {
        val response = try {
            transport.fetch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CatalogTransportFailure) {
            return CatalogLoadResult.Failure(failure.reasonCode, failure.retryable)
        } catch (_: Exception) {
            return CatalogLoadResult.Failure("catalog_transport_failed", retryable = true)
        }
        if (response.statusCode !in 200..299) {
            return CatalogLoadResult.Failure(
                reasonCode = "catalog_http_${response.statusCode}",
                retryable = response.statusCode >= 500,
            )
        }
        if (response.body.isEmpty() || response.body.size > MAX_CATALOG_BYTES) {
            return CatalogLoadResult.Failure("catalog_size_invalid", retryable = false)
        }
        if (response.contentType?.lowercase()?.startsWith("text/html") == true) {
            return CatalogLoadResult.Failure("catalog_html_response", retryable = true)
        }

        val envelope = try {
            json.decodeFromString<SignedCatalogEnvelope>(response.body.decodeUtf8Strict())
        } catch (_: Exception) {
            return CatalogLoadResult.Failure("catalog_envelope_invalid", retryable = false)
        }
        if (envelope.schemaVersion != SUPPORTED_CATALOG_SCHEMA || envelope.keyId.isBlank()) {
            return CatalogLoadResult.Failure("catalog_envelope_fields_missing", retryable = false)
        }
        val payload = try {
            Base64.getDecoder().decode(envelope.payloadBase64)
        } catch (_: IllegalArgumentException) {
            return CatalogLoadResult.Failure("catalog_payload_encoding_invalid", retryable = false)
        }
        val signature = try {
            Base64.getDecoder().decode(envelope.signatureBase64)
        } catch (_: IllegalArgumentException) {
            return CatalogLoadResult.Failure("catalog_signature_encoding_invalid", retryable = false)
        }
        if (!signatureVerifier.verify(envelope.keyId, envelope.signatureAlgorithm, payload, signature)) {
            return CatalogLoadResult.Failure("catalog_signature_invalid", retryable = false)
        }

        val payloadDocument = try {
            json.decodeFromString<ArtifactCatalogPayload>(payload.decodeUtf8Strict())
        } catch (_: Exception) {
            return CatalogLoadResult.Failure("catalog_payload_invalid", retryable = false)
        }
        if (payloadDocument.schemaVersion != SUPPORTED_CATALOG_SCHEMA || envelope.catalogVersion.isBlank()) {
            return CatalogLoadResult.Failure("catalog_payload_fields_missing", retryable = false)
        }
        val manifests = try {
            payloadDocument.artifacts.map { it.toDomain() }
        } catch (_: IllegalArgumentException) {
            return CatalogLoadResult.Failure("catalog_source_kind_invalid", retryable = false)
        }
        when (val validation = ArtifactManifestValidator.validateCatalog(manifests)) {
            is ManifestValidation.Invalid -> {
                return CatalogLoadResult.Failure(validation.reasonCode, retryable = false)
            }

            ManifestValidation.Valid -> Unit
        }
        val orderedManifests = manifests.map { manifest ->
            when (val plan = sourcePolicy.plan(manifest)) {
                is com.tcrrry.helper.domain.artifact.SourcePlan.Rejected -> {
                    return CatalogLoadResult.Failure(plan.reasonCode, retryable = false)
                }

                is com.tcrrry.helper.domain.artifact.SourcePlan.Accepted -> manifest.copy(sources = plan.sources)
            }
        }
        return CatalogLoadResult.Success(
            TrustedArtifactCatalog(
                catalogVersion = envelope.catalogVersion,
                keyId = envelope.keyId,
                signatureAlgorithm = envelope.signatureAlgorithm,
                manifests = orderedManifests,
            ),
        )
    }

    companion object {
        const val SUPPORTED_CATALOG_SCHEMA = 1
        const val MAX_CATALOG_BYTES = 2 * 1024 * 1024
        val STRICT_JSON: Json = Json {
            ignoreUnknownKeys = false
            isLenient = false
            explicitNulls = false
        }
    }
}

private fun ByteArray.decodeUtf8Strict(): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(this))
    .toString()
