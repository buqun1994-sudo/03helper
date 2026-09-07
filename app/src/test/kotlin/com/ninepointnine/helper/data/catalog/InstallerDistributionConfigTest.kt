package com.ninepointnine.helper.data.catalog

import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallerDistributionConfigTest {
    private val json = CloudReleaseCatalogAdapter.STRICT_JSON
    private val now = Instant.parse("2026-09-07T00:00:00Z")
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    @Test
    fun `cloud generated V5 fixture verifies with Java without reserializing payload bytes`() = runBlocking {
        val fixture = json.parseToJsonElement(requireNotNull(javaClass.classLoader
            ?.getResourceAsStream("android-config-v5-contract.json")).bufferedReader().use { it.readText() }).jsonObject
        val envelope = fixture.getValue("envelope").jsonObject
        val payloadBytes = Base64.getDecoder().decode(envelope.getValue("payloadBase64").jsonPrimitive.content)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(payloadBytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(fixture.getValue("payloadSha256").jsonPrimitive.content, digest)
        val adapter = CloudInstallerDistributionConfigAdapter(
            transport = ReleaseCatalogTransport { CatalogHttpResponse(200, envelope.toString().toByteArray(), "application/json") },
            signatureVerifier = JcaCatalogSignatureVerifier(TrustedCatalogKeyResolver { id ->
                Base64.getDecoder().decode(fixture.getValue("publicKeySpkiBase64").jsonPrimitive.content)
                    .takeIf { id == "v5-contract-fixture" }
            }),
            expectedChannel = "debug", now = { now },
        )
        val result = adapter.load() as DistributionConfigLoadResult.Success
        assertEquals(7L, result.config.catalogRevision)
        assertEquals("org.independent.player", result.config.apps.single().packageName)
        assertEquals(setOf("ab".repeat(32), "cd".repeat(32)), result.config.apps.single().certificateSha256s)
    }

    @Test
    fun `v5 accepts an unknown publisher without a desktop or profile`() {
        val result = load(payload()) as DistributionConfigLoadResult.Success
        val app = result.config.apps.single()
        assertEquals("org.independent.player", app.packageName)
        assertEquals("third-party", app.componentId)
        assertTrue(app.required)
        assertTrue(app.matchesApprovedIdentity(app.packageName, setOf("ab".repeat(32))))
        assertFalse(app.matchesApprovedIdentity("org.other.player", setOf("ab".repeat(32))))
        assertFalse(app.matchesApprovedIdentity(app.packageName, setOf("ab".repeat(32), "cd".repeat(32))))
        assertFalse(result.config.toString().contains("test-password"))
        assertEquals("v5-7", result.config.effectiveCatalogVersion())
    }

    @Test
    fun `full signing set is exact and order independent`() {
        val app = (load(payload(app().copy(certificateSha256s = listOf("ab".repeat(32), "cd".repeat(32)))))
            as DistributionConfigLoadResult.Success).config.apps.single()
        assertTrue(app.matchesApprovedIdentity(app.packageName, linkedSetOf("cd".repeat(32), "ab".repeat(32))))
        assertFalse(app.matchesApprovedIdentity(app.packageName, setOf("ab".repeat(32))))
        assertFalse(app.matchesApprovedIdentity(app.packageName, setOf("ab".repeat(32), "cd".repeat(32), "ef".repeat(32))))
    }

    @Test
    fun `version size and hash must match the approved release`() {
        val app = (load(payload()) as DistributionConfigLoadResult.Success).config.apps.single()
        assertTrue(app.matchesDeclaredVersion(ArtifactVersion("1.2.3", 42)))
        assertFalse(app.matchesDeclaredVersion(ArtifactVersion("1.2.4", 42)))
        assertFalse(app.matchesDeclaredVersion(ArtifactVersion("1.2.3", 41)))
        assertTrue(app.matchesApprovedFile(1234, "cd".repeat(32)))
        assertFalse(app.matchesApprovedFile(1235, "cd".repeat(32)))
        assertFalse(app.matchesApprovedFile(1234, "ef".repeat(32)))
    }

    @Test
    fun `signature verifies original whitespace and rejects byte changes`() = runBlocking {
        val bytes = ("\n " + json.encodeToString(payload()) + "\n").toByteArray()
        val envelope = sign(bytes)
        assertTrue(adapter(envelope).load() is DistributionConfigLoadResult.Success)
        val changed = envelope.copy(payloadBase64 = Base64.getEncoder().encodeToString(bytes + byteArrayOf(32)))
        assertEquals("distribution_config_signature_invalid", reason(adapter(changed).load()))
    }

    @Test
    fun `unknown key algorithm and invalid encoding fail closed`() = runBlocking {
        val envelope = sign(json.encodeToString(payload()).toByteArray())
        assertEquals("distribution_config_signature_invalid", reason(adapter(envelope.copy(keyId = "unknown")).load()))
        assertEquals("distribution_config_envelope_fields_missing", reason(adapter(envelope.copy(signatureAlgorithm = "SHA512withECDSA")).load()))
        assertEquals("distribution_config_payload_encoding_invalid", reason(adapter(envelope.copy(payloadBase64 = "!")).load()))
        assertEquals("distribution_config_signature_encoding_invalid", reason(adapter(envelope.copy(signatureBase64 = "!")).load()))
    }

    @Test
    fun `environment schema and clock bounds cannot be bypassed`() {
        assertEquals("distribution_config_environment_invalid", reason(load(payload().copy(environment = "production"))))
        assertEquals("distribution_config_payload_schema_unsupported", reason(load(payload().copy(schemaVersion = 4))))
        assertEquals("distribution_config_expired", reason(load(payload().copy(expiresAt = now.toString()))))
        assertEquals("distribution_config_issued_at_in_future", reason(load(payload().copy(issuedAtUtc = now.plusSeconds(301).toString()))))
        assertTrue(load(payload().copy(issuedAtUtc = now.plusSeconds(300).toString())) is DistributionConfigLoadResult.Success)
        assertEquals("distribution_config_folder_invalid", reason(load(payload().copy(folderUrl = "http://example.org/apps"))))
    }

    @Test
    fun `old and removed fields are rejected at the protocol boundary`() = runBlocking {
        val bytes = json.encodeToString(payload()).dropLast(1) + ",\"trustProfileId\":\"old\"}"
        assertEquals("distribution_config_payload_invalid", reason(adapter(sign(bytes.toByteArray())).load()))
        val oldEnvelope = json.encodeToString(sign(json.encodeToString(payload()).toByteArray()))
            .dropLast(1) + ",\"schemaVersion\":4}"
        assertEquals("distribution_config_envelope_invalid", reason(adapterBody(oldEnvelope.toByteArray()).load()))
    }

    @Test
    fun `duplicate identities and unsafe archive names are rejected`() {
        val first = app()
        assertEquals("distribution_config_app_duplicate", reason(load(payload().copy(apps = listOf(first, first)))))
        assertEquals("distribution_config_archive_file_name_invalid", reason(load(payload(first.copy(archiveFileName = "../app.zip")))))
        assertEquals("distribution_config_archive_file_name_invalid", reason(load(payload(first.copy(archiveFileName = "a".repeat(125) + ".zip")))))
        assertEquals("distribution_config_apk_package_invalid", reason(load(payload().copy(
            apps = listOf(first, first.copy(appId = "other", archiveFileName = "other.zip")),
        ))))
        assertEquals("distribution_config_apk_identity_invalid", reason(load(payload(first.copy(
            certificateSha256s = listOf("ab".repeat(32), "ab".repeat(32)),
        )))))
    }

    @Test
    fun `metadata limits are shared with cloud and disabled drafts remain inert`() {
        val first = app()
        assertTrue(load(payload(first.copy(apkSizeBytes = 1L shl 30))) is DistributionConfigLoadResult.Success)
        assertEquals("distribution_config_apk_metadata_invalid", reason(load(payload(first.copy(apkSizeBytes = (1L shl 30) + 1)))))
        assertEquals("distribution_config_apk_metadata_invalid", reason(load(payload(first.copy(versionCode = 9007199254740992L)))))
        assertEquals("distribution_config_apk_identity_invalid", reason(load(payload(first.copy(certificateSha256s = emptyList())))))
        val draft = first.copy(enabled = false, installPolicy = "optional", packageName = "", certificateSha256s = emptyList(), apkSha256 = "", versionCode = 0)
        assertTrue((load(payload(draft)) as DistributionConfigLoadResult.Success).config.apps.isEmpty())
        assertTrue((load(payload().copy(apps = emptyList())) as DistributionConfigLoadResult.Success).config.apps.isEmpty())
    }

    @Test
    fun `cloud owns required optional and disabled policy for every app id`() {
        val optionalDesktop = app().copy(appId = "desktop", installPolicy = "optional")
        assertFalse((load(payload(optionalDesktop)) as DistributionConfigLoadResult.Success).config.apps.single().required)
        assertEquals("distribution_config_install_policy_invalid", reason(load(payload(app().copy(enabled = false)))))
    }

    @Test
    fun `revision store rejects rollback and same revision content replacement after restart`() {
        val directory = Files.createTempDirectory("v5-revisions").toFile()
        try {
            val file = directory.resolve("revisions.properties")
            assertTrue(load(payload(), FileCatalogRevisionStore(file)) is DistributionConfigLoadResult.Success)
            assertTrue(load(payload(), FileCatalogRevisionStore(file)) is DistributionConfigLoadResult.Success)
            assertEquals("distribution_config_rollback", reason(load(payload().copy(catalogRevision = 6), FileCatalogRevisionStore(file))))
            assertEquals("distribution_config_revision_store_failed", reason(load(payload(app().copy(displayName = "Changed")), FileCatalogRevisionStore(file))))
            assertTrue(load(payload().copy(catalogRevision = 8), FileCatalogRevisionStore(file)) is DistributionConfigLoadResult.Success)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `transport html and oversized payloads never become configuration`() = runBlocking {
        val body = json.encodeToString(sign(json.encodeToString(payload()).toByteArray())).toByteArray()
        assertEquals("distribution_config_http_503", reason(adapterBody(body, 503).load()))
        assertEquals("distribution_config_html_response", reason(adapterBody(body, contentType = "text/html").load()))
        assertEquals("distribution_config_size_invalid", reason(adapterBody(ByteArray(768 * 1024 + 1)).load()))
        assertEquals("distribution_config_payload_size_invalid", reason(adapter(sign(ByteArray(512 * 1024 + 1))).load()))
    }

    private fun app() = InstallerAppV5Document(
        appId = "third-party", archiveFileName = "player.zip", displayName = "Player",
        enabled = true, installPolicy = "required", sortOrder = 0, versionCode = 42,
        versionName = "1.2.3", apkSizeBytes = 1234, packageName = "org.independent.player",
        certificateSha256s = listOf("ab".repeat(32)), apkSha256 = "cd".repeat(32),
    )

    private fun payload(app: InstallerAppV5Document = app()) = InstallerDistributionConfigV5Payload(
        schemaVersion = 5, environment = "staging", catalogRevision = 7,
        issuedAtUtc = now.minusSeconds(60).toString(), expiresAt = "2099-12-31T00:00:00Z",
        folderUrl = "https://wwatl.lanzouw.com/b0fqlrcyb", folderPassword = "test-password", apps = listOf(app),
    )

    private fun sign(bytes: ByteArray): SignedInstallerConfigV5Envelope = SignedInstallerConfigV5Envelope(
        keyId = "test-key", signatureAlgorithm = "SHA256withECDSA",
        payloadBase64 = Base64.getEncoder().encodeToString(bytes),
        signatureBase64 = Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(bytes)
            sign()
        }),
    )

    private fun adapter(envelope: SignedInstallerConfigV5Envelope, store: CatalogRevisionStore = InMemoryCatalogRevisionStore()) =
        adapterBody(json.encodeToString(envelope).toByteArray(), store = store)

    private fun adapterBody(body: ByteArray, status: Int = 200, contentType: String = "application/json",
        store: CatalogRevisionStore = InMemoryCatalogRevisionStore()) = CloudInstallerDistributionConfigAdapter(
        transport = ReleaseCatalogTransport { CatalogHttpResponse(status, body, contentType) },
        signatureVerifier = JcaCatalogSignatureVerifier(TrustedCatalogKeyResolver { id ->
            keys.public.encoded.takeIf { id == "test-key" }
        }),
        expectedChannel = "debug", now = { now }, revisionStore = store,
    )

    private fun load(payload: InstallerDistributionConfigV5Payload, store: CatalogRevisionStore = InMemoryCatalogRevisionStore()) =
        runBlocking { adapter(sign(json.encodeToString(payload).toByteArray()), store).load() }

    private fun reason(result: DistributionConfigLoadResult) = (result as DistributionConfigLoadResult.Failure).reasonCode
}
