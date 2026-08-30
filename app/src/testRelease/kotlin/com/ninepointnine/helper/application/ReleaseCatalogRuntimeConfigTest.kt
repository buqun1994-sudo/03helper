package com.ninepointnine.helper.application

import com.ninepointnine.helper.data.catalog.CatalogHttpResponse
import com.ninepointnine.helper.data.catalog.DistributionConfigLoadResult
import com.ninepointnine.helper.data.catalog.JcaCatalogSignatureVerifier
import com.ninepointnine.helper.data.catalog.ReleaseCatalogTransport
import com.ninepointnine.helper.data.catalog.SignedInstallerConfigEnvelope
import com.ninepointnine.helper.data.catalog.TrustedCatalogKeyResolver
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseCatalogRuntimeConfigTest {
    @Test
    fun `production trust root is pinned to the Cloud handoff`() {
        val publicKey = ReleaseCatalogRuntimeConfig.resolveTrustedKey(PRODUCTION_KEY_ID)

        assertNotNull(publicKey)
        assertEquals(PRODUCTION_PUBLIC_KEY_SHA256, publicKey?.sha256())
        assertNull(ReleaseCatalogRuntimeConfig.resolveTrustedKey("unknown-production-key"))
        assertEquals(setOf(4), ReleaseCatalogRuntimeConfig.acceptedSchemaVersions)
        assertEquals(setOf("SHA256withECDSA"), ReleaseCatalogRuntimeConfig.acceptedSignatureAlgorithms)
        assertEquals("production", ReleaseCatalogRuntimeConfig.EXPECTED_ENVIRONMENT)
        assertEquals("release", ReleaseCatalogRuntimeConfig.EXPECTED_CHANNEL)
    }

    @Test
    fun `production fixture verifies the original payload bytes and exposes four apps`() = runBlocking {
        val body = fixtureBytes()
        val envelope = JSON.decodeFromString<SignedInstallerConfigEnvelope>(body.toString(Charsets.UTF_8))
        val payload = Base64.getDecoder().decode(envelope.payloadBase64)
        val signature = Base64.getDecoder().decode(envelope.signatureBase64)
        val verifier = JcaCatalogSignatureVerifier(
            TrustedCatalogKeyResolver(ReleaseCatalogRuntimeConfig::resolveTrustedKey),
        )

        assertEquals(PRODUCTION_PAYLOAD_SHA256, payload.sha256())
        assertTrue(verifier.verify(envelope.keyId, envelope.signatureAlgorithm, payload, signature))
        assertFalse(
            verifier.verify(
                envelope.keyId,
                envelope.signatureAlgorithm,
                payload + byteArrayOf('\n'.code.toByte()),
                signature,
            ),
        )

        val result = adapter(body).load()
        val config = when (result) {
            is DistributionConfigLoadResult.Success -> result.config
            is DistributionConfigLoadResult.Failure -> throw AssertionError(result.reasonCode)
        }
        assertEquals("production", config.environment)
        assertEquals("release", config.channel)
        assertEquals(5L, config.catalogRevision)
        assertEquals(listOf("desktop", "lyrics", "cast", "file-manager"), config.apps.map { it.appId })
        assertEquals("fossify-approved", config.apps.last().trustProfileId)
    }

    @Test
    fun `release composition rejects legacy schema and non production algorithm`() = runBlocking {
        val envelope = JSON.decodeFromString<SignedInstallerConfigEnvelope>(
            fixtureBytes().toString(Charsets.UTF_8),
        )
        val legacy = JSON.encodeToString(envelope.copy(schemaVersion = 3)).toByteArray()
        val wrongAlgorithm = JSON.encodeToString(
            envelope.copy(signatureAlgorithm = "Ed25519"),
        ).toByteArray()

        assertEquals("distribution_config_schema_unsupported", failureReason(legacy))
        assertEquals("distribution_config_envelope_fields_missing", failureReason(wrongAlgorithm))
    }

    private fun adapter(body: ByteArray) = ReleaseCatalogRuntimeConfig.createDistributionConfigAdapter(
        transport = ReleaseCatalogTransport {
            CatalogHttpResponse(
                statusCode = 200,
                body = body,
                contentType = "application/json",
            )
        },
        now = { Instant.parse("2026-08-31T00:00:00Z") },
    )

    private suspend fun failureReason(body: ByteArray): String =
        (adapter(body).load() as DistributionConfigLoadResult.Failure).reasonCode

    private fun fixtureBytes(): ByteArray = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(FIXTURE_RESOURCE),
    ).use { it.readBytes() }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = false }
        const val FIXTURE_RESOURCE = "production-distribution-config-v5.json"
        const val PRODUCTION_KEY_ID = "03helper-production-config-2026-08-30-v1"
        const val PRODUCTION_PUBLIC_KEY_SHA256 =
            "a971be7085a2a4a3ef8df8dd2b9df5a94b46e05b3ce85b934ffc84e42ce70051"
        const val PRODUCTION_PAYLOAD_SHA256 =
            "8b7e0a6141595a3798310f95715e1417b20eb414d3f2a6b9be0a705e0ec19b99"
    }
}
