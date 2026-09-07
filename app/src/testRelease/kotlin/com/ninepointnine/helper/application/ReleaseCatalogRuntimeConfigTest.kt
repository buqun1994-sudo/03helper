package com.ninepointnine.helper.application

import com.ninepointnine.helper.data.catalog.CatalogHttpResponse
import com.ninepointnine.helper.data.catalog.DistributionConfigLoadResult
import com.ninepointnine.helper.data.catalog.JcaCatalogSignatureVerifier
import com.ninepointnine.helper.data.catalog.ReleaseCatalogTransport
import com.ninepointnine.helper.data.catalog.TrustedCatalogKeyResolver
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        assertEquals(setOf(5), ReleaseCatalogRuntimeConfig.acceptedSchemaVersions)
        assertEquals(setOf("SHA256withECDSA"), ReleaseCatalogRuntimeConfig.acceptedSignatureAlgorithms)
        assertEquals("production", ReleaseCatalogRuntimeConfig.EXPECTED_ENVIRONMENT)
        assertEquals("release", ReleaseCatalogRuntimeConfig.EXPECTED_CHANNEL)
    }

    @Test
    fun `production trust root verifies historical bytes but V5 rejects the historical envelope`() = runBlocking {
        val body = fixtureBytes()
        val envelope = JSON.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
        val payload = Base64.getDecoder().decode(envelope.getValue("payloadBase64").jsonPrimitive.content)
        val signature = Base64.getDecoder().decode(envelope.getValue("signatureBase64").jsonPrimitive.content)
        val keyId = envelope.getValue("keyId").jsonPrimitive.content
        val algorithm = envelope.getValue("signatureAlgorithm").jsonPrimitive.content
        val verifier = JcaCatalogSignatureVerifier(
            TrustedCatalogKeyResolver(ReleaseCatalogRuntimeConfig::resolveTrustedKey),
        )

        assertEquals(PRODUCTION_PAYLOAD_SHA256, payload.sha256())
        assertTrue(verifier.verify(keyId, algorithm, payload, signature))
        assertFalse(
            verifier.verify(
                keyId,
                algorithm,
                payload + byteArrayOf('\n'.code.toByte()),
                signature,
            ),
        )

        assertEquals("distribution_config_envelope_invalid", failureReason(body))
    }

    @Test
    fun `release composition rejects non production algorithm`() = runBlocking {
        val envelope = JSON.parseToJsonElement(fixtureBytes().toString(Charsets.UTF_8)).jsonObject
        val wrongAlgorithm = JsonObject(
            envelope.filterKeys { it in setOf("keyId", "signatureAlgorithm", "payloadBase64", "signatureBase64") } +
                ("signatureAlgorithm" to JsonPrimitive("Ed25519")),
        ).toString().toByteArray()

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
