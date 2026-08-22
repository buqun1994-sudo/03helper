package com.tcrrry.helper.data.catalog

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallerDistributionConfigTest {
    @Test
    fun `ECDSA verifies the exact payload bytes without reserialization`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val payloadBytes = payloadJsonWithWhitespace().toByteArray(Charsets.UTF_8)
        val envelope = signedEnvelope(keys, "SHA256withECDSA", payloadBytes)

        val result = adapter(envelope, keys).load()

        val config = (result as DistributionConfigLoadResult.Success).config
        assertEquals(3, config.components.size)
        assertEquals("com.tcrrry.desktop", config.components.first().packageName)
        assertEquals("", config.previousVersionsUrl)
        assertEquals("", config.previousVersionsPassword)
        assertFalse(config.toString().contains("test-password"))
        assertFalse(envelope.toString().contains(payloadBytes.decodeToString()))
    }

    @Test
    fun `payload bytes changed after signing are rejected`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val original = payloadJsonWithWhitespace().toByteArray(Charsets.UTF_8)
        val envelope = signedEnvelope(keys, "SHA256withECDSA", original).copy(
            payloadBase64 = Base64.getEncoder().encodeToString(
                String(original, Charsets.UTF_8).replace("debug", "debug ").toByteArray(Charsets.UTF_8),
            ),
        )

        val result = adapter(envelope, keys).load()

        assertEquals("distribution_config_signature_invalid", (result as DistributionConfigLoadResult.Failure).reasonCode)
    }

    @Test
    fun `Ed25519 is accepted only with its trusted public key`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val payloadBytes = payloadJsonWithWhitespace().toByteArray(Charsets.UTF_8)
        val envelope = signedEnvelope(keys, "Ed25519", payloadBytes)

        val result = adapter(envelope, keys).load()

        assertTrue(result is DistributionConfigLoadResult.Success)
    }

    @Test
    fun `unknown key and algorithm fail closed before payload use`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val payloadBytes = payloadJsonWithWhitespace().toByteArray(Charsets.UTF_8)
        val unknownKey = signedEnvelope(keys, "SHA256withECDSA", payloadBytes).copy(keyId = "not-built-in")
        val unknownAlgorithm = signedEnvelope(keys, "SHA256withECDSA", payloadBytes).copy(
            signatureAlgorithm = "SHA512withECDSA",
        )

        val keyResult = adapter(unknownKey, keys).load()
        val algorithmResult = adapter(unknownAlgorithm, keys).load()

        assertEquals("distribution_config_signature_invalid", (keyResult as DistributionConfigLoadResult.Failure).reasonCode)
        assertEquals(
            "distribution_config_signature_algorithm_invalid",
            (algorithmResult as DistributionConfigLoadResult.Failure).reasonCode,
        )
    }

    @Test
    fun `schema expiry and network identity overrides are rejected`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val validPayload = payloadJsonWithWhitespace()
        val expiredPayload = validPayload.replace("2099-12-31T00:00:00Z", "2020-01-01T00:00:00Z")
        val identityOverridePayload = validPayload.replace(
            "{\"componentId\":\"desktop\",\"archiveFileName\":\"03desktop-debug.zip\",\"required\":true}",
            "{\"componentId\":\"desktop\",\"archiveFileName\":\"03desktop-debug.zip\",\"required\":true,\"packageName\":\"com.attacker.app\"}",
        )

        val expired = adapter(
            signedEnvelope(keys, "SHA256withECDSA", expiredPayload.toByteArray(Charsets.UTF_8)),
            keys,
        ).load()
        val identityOverride = adapter(
            signedEnvelope(keys, "SHA256withECDSA", identityOverridePayload.toByteArray(Charsets.UTF_8)),
            keys,
        ).load()

        assertEquals("distribution_config_expired", (expired as DistributionConfigLoadResult.Failure).reasonCode)
        assertEquals("distribution_config_payload_invalid", (identityOverride as DistributionConfigLoadResult.Failure).reasonCode)
    }

    @Test
    fun `empty passwords and a protected previous folder are accepted`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val payload = payloadJsonWithWhitespace()
            .replace("\"folderPassword\": \"test-password\"", "\"folderPassword\": \"\"")
            .replace(
                "\"previousVersionsUrl\": \"\"",
                "\"previousVersionsUrl\": \"https://wwatl.lanzouw.com/bprevious123\"",
            )
            .replace(
                "\"previousVersionsPassword\": \"\"",
                "\"previousVersionsPassword\": \"history-password\"",
            )

        val result = adapter(
            signedEnvelope(keys, "SHA256withECDSA", payload.toByteArray(Charsets.UTF_8)),
            keys,
        ).load()

        val config = (result as DistributionConfigLoadResult.Success).config
        assertEquals("", config.folderPassword)
        assertEquals("https://wwatl.lanzouw.com/bprevious123", config.previousVersionsUrl)
        assertEquals("history-password", config.previousVersionsPassword)
    }

    @Test
    fun `previous password without a URL is rejected`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val payload = payloadJsonWithWhitespace().replace(
            "\"previousVersionsPassword\": \"\"",
            "\"previousVersionsPassword\": \"orphaned-password\"",
        )

        val result = adapter(
            signedEnvelope(keys, "SHA256withECDSA", payload.toByteArray(Charsets.UTF_8)),
            keys,
        ).load()

        assertEquals(
            "distribution_config_previous_password_without_url",
            (result as DistributionConfigLoadResult.Failure).reasonCode,
        )
    }

    @Test
    fun `previous URL query and non-folder path are rejected`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val queryPayload = payloadJsonWithWhitespace().replace(
            "\"previousVersionsUrl\": \"\"",
            "\"previousVersionsUrl\": \"https://wwatl.lanzouw.com/bprevious123?x=1\"",
        )
        val filePayload = payloadJsonWithWhitespace().replace(
            "\"previousVersionsUrl\": \"\"",
            "\"previousVersionsUrl\": \"https://wwatl.lanzouw.com/i123\"",
        )

        val queryResult = adapter(
            signedEnvelope(keys, "SHA256withECDSA", queryPayload.toByteArray(Charsets.UTF_8)),
            keys,
        ).load()
        val fileResult = adapter(
            signedEnvelope(keys, "SHA256withECDSA", filePayload.toByteArray(Charsets.UTF_8)),
            keys,
        ).load()

        assertEquals(
            "distribution_config_previous_url_invalid",
            (queryResult as DistributionConfigLoadResult.Failure).reasonCode,
        )
        assertEquals(
            "distribution_config_previous_url_invalid",
            (fileResult as DistributionConfigLoadResult.Failure).reasonCode,
        )
    }

    @Test
    fun `payload without the historical fields is rejected as an incomplete V2 document`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val legacyPayload = payloadJsonWithWhitespace()
            .replace("\"previousVersionsUrl\": \"\",", "")
            .replace("\"previousVersionsPassword\": \"\",", "")

        val result = adapter(
            signedEnvelope(keys, "SHA256withECDSA", legacyPayload.toByteArray(Charsets.UTF_8)),
            keys,
        ).load()

        assertEquals("distribution_config_payload_invalid", (result as DistributionConfigLoadResult.Failure).reasonCode)
    }

    private fun adapter(
        envelope: SignedInstallerConfigEnvelope,
        trustedKeyPair: KeyPair,
    ): CloudInstallerDistributionConfigAdapter {
        val body = CloudReleaseCatalogAdapter.STRICT_JSON.encodeToString(envelope).toByteArray(Charsets.UTF_8)
        return CloudInstallerDistributionConfigAdapter(
            transport = ReleaseCatalogTransport {
                CatalogHttpResponse(200, body, "application/json")
            },
            signatureVerifier = JcaCatalogSignatureVerifier(
                TrustedCatalogKeyResolver { keyId ->
                    trustedKeyPair.public.encoded.takeIf { keyId == "test-key" }
                },
            ),
            expectedChannel = "debug",
            now = { Instant.parse("2026-08-22T00:00:00Z") },
        )
    }

    private fun signedEnvelope(
        keys: KeyPair,
        algorithm: String,
        payloadBytes: ByteArray,
    ): SignedInstallerConfigEnvelope {
        val signer = Signature.getInstance(algorithm).apply {
            initSign(keys.private)
            update(payloadBytes)
        }
        return SignedInstallerConfigEnvelope(
            schemaVersion = 2,
            configVersion = "android-debug-test-001",
            keyId = "test-key",
            signatureAlgorithm = algorithm,
            payloadBase64 = Base64.getEncoder().encodeToString(payloadBytes),
            signatureBase64 = Base64.getEncoder().encodeToString(signer.sign()),
        )
    }

    private fun payloadJsonWithWhitespace(): String = """
        {
          "schemaVersion": 2,
          "channel": "debug",
          "expiresAt": "2099-12-31T00:00:00Z",
          "folderUrl": "https://wwatl.lanzouw.com/b0fqlrcyb",
          "folderPassword": "test-password",
          "previousVersionsUrl": "",
          "previousVersionsPassword": "",
          "components": [
            {"componentId":"desktop","archiveFileName":"03desktop-debug.zip","required":true},
            {"componentId":"lyrics","archiveFileName":"03lyrics-debug.zip","required":false},
            {"componentId":"file-manager","archiveFileName":"fossify-file-manager-car-debug.zip","required":false}
          ]
        }
    """.trimIndent()
}
