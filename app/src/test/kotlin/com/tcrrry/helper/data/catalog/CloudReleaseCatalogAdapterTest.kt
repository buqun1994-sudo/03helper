package com.tcrrry.helper.data.catalog

import com.tcrrry.helper.domain.artifact.ArtifactSourceKind
import com.tcrrry.helper.domain.artifact.ArtifactVersion
import com.tcrrry.helper.domain.artifact.CompatibilityRange
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudReleaseCatalogAdapterTest {
    @Test
    fun `invalid detached signature blocks catalog before session injection`() = runBlocking {
        val payload = validPayload()
        val envelope = envelope(payload, signature = byteArrayOf(1, 2, 3))
        val adapter = CloudReleaseCatalogAdapter(
            transport = ReleaseCatalogTransport {
                CatalogHttpResponse(200, CloudReleaseCatalogAdapter.STRICT_JSON.encodeToString(envelope).toByteArray())
            },
            signatureVerifier = CatalogSignatureVerifier { _, _, _, _ -> false },
        )
        val result = adapter.load() as CatalogLoadResult.Failure
        assertEquals("catalog_signature_invalid", result.reasonCode)
    }

    @Test
    fun `missing required manifest field is rejected by strict wire decoder`() = runBlocking {
        val payloadJson = """
            {"schemaVersion":1,"artifacts":[{"schemaVersion":1,"componentId":"lyrics","displayName":"Lyrics","required":true}]}
        """.trimIndent()
        val envelope = SignedCatalogEnvelope(
            schemaVersion = 1,
            catalogVersion = "v1",
            keyId = "test-key",
            signatureAlgorithm = "SHA256withECDSA",
            payloadBase64 = Base64.getEncoder().encodeToString(payloadJson.toByteArray()),
            signatureBase64 = Base64.getEncoder().encodeToString(byteArrayOf(1)),
        )
        val adapter = CloudReleaseCatalogAdapter(
            transport = ReleaseCatalogTransport {
                CatalogHttpResponse(200, CloudReleaseCatalogAdapter.STRICT_JSON.encodeToString(envelope).toByteArray())
            },
            signatureVerifier = CatalogSignatureVerifier { _, _, _, _ -> true },
        )
        val result = adapter.load() as CatalogLoadResult.Failure
        assertEquals("catalog_payload_invalid", result.reasonCode)
    }

    @Test
    fun `JCA signature verification accepts trusted payload and maps source order`() = runBlocking {
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val payload = validPayload()
        val payloadBytes = CloudReleaseCatalogAdapter.STRICT_JSON.encodeToString(payload).toByteArray()
        val signer = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(payloadBytes)
        }
        val envelope = SignedCatalogEnvelope(
            schemaVersion = 1,
            catalogVersion = "android-v1",
            keyId = "test-key",
            signatureAlgorithm = "SHA256withECDSA",
            payloadBase64 = Base64.getEncoder().encodeToString(payloadBytes),
            signatureBase64 = Base64.getEncoder().encodeToString(signer.sign()),
        )
        val adapter = CloudReleaseCatalogAdapter(
            transport = ReleaseCatalogTransport {
                CatalogHttpResponse(
                    statusCode = 200,
                    body = CloudReleaseCatalogAdapter.STRICT_JSON.encodeToString(envelope).toByteArray(),
                    contentType = "application/json",
                )
            },
            signatureVerifier = JcaCatalogSignatureVerifier(
                TrustedCatalogKeyResolver { keyId ->
                    keyPair.public.encoded.takeIf { keyId == "test-key" }
                },
            ),
        )
        val result = adapter.load() as CatalogLoadResult.Success
        assertEquals("android-v1", result.catalog.catalogVersion)
        assertEquals(
            listOf(ArtifactSourceKind.LANZOU_SHARE, ArtifactSourceKind.R2, ArtifactSourceKind.GITHUB_RELEASES),
            result.catalog.manifests.single().sources.map { it.kind },
        )
    }

    @Test
    fun `html catalog response is rejected before parsing`() = runBlocking {
        val adapter = CloudReleaseCatalogAdapter(
            transport = ReleaseCatalogTransport {
                CatalogHttpResponse(200, "<html>login</html>".toByteArray(), "text/html")
            },
            signatureVerifier = CatalogSignatureVerifier { _, _, _, _ -> true },
        )
        val result = adapter.load() as CatalogLoadResult.Failure
        assertEquals("catalog_html_response", result.reasonCode)
    }

    private fun validPayload(): ArtifactCatalogPayload = ArtifactCatalogPayload(
        schemaVersion = 1,
        artifacts = listOf(
            ArtifactManifestDocument(
                schemaVersion = 1,
                componentId = "lyrics",
                displayName = "Lyrics",
                required = true,
                version = ArtifactVersionDocument("1.0.0", 1),
                compatibility = CompatibilityRangeDocument(minAndroidSdk = 26),
                archiveFormat = "zip",
                archiveFileName = "lyrics.zip",
                archiveSizeBytes = 10,
                archiveSha256 = "11".repeat(32),
                apkEntryName = "lyrics.apk",
                apkSizeBytes = 5,
                apkSha256 = "22".repeat(32),
                packageName = "com.example.lyrics",
                apkVersion = ArtifactVersionDocument("1.0.0", 7),
                certificateSha256 = "33".repeat(32),
                sources = listOf(
                    ArtifactSourceDocument("github", "https://github.com/a/b/releases/download/v1/lyrics.zip"),
                    ArtifactSourceDocument("lanzou-share", "https://wwatl.lanzouw.com/iabc123"),
                    ArtifactSourceDocument("r2", "https://assets.r2.dev/lyrics.zip"),
                ),
            ),
        ),
    )

    private fun envelope(payload: ArtifactCatalogPayload, signature: ByteArray): SignedCatalogEnvelope {
        val payloadBytes = CloudReleaseCatalogAdapter.STRICT_JSON.encodeToString(payload).toByteArray()
        return SignedCatalogEnvelope(
            schemaVersion = 1,
            catalogVersion = "v1",
            keyId = "test-key",
            signatureAlgorithm = "SHA256withECDSA",
            payloadBase64 = Base64.getEncoder().encodeToString(payloadBytes),
            signatureBase64 = Base64.getEncoder().encodeToString(signature),
        )
    }
}
