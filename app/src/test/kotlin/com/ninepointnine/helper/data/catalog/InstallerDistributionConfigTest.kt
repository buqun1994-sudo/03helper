package com.ninepointnine.helper.data.catalog

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import com.ninepointnine.helper.domain.artifact.formatArtifactSizeLabel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallerDistributionConfigTest {
    @Test
    fun `v3 verifies exact payload bytes and exposes dynamic apps`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val payloadBytes = payloadJsonWithWhitespace().toByteArray(Charsets.UTF_8)

        val result = adapter(signedEnvelope(keys, "SHA256withECDSA", payloadBytes), keys).load()
        val config = (result as DistributionConfigLoadResult.Success).config

        assertEquals(listOf("desktop", "lyrics", "notes"), config.apps.map { it.appId })
        assertEquals(7L, config.catalogRevision)
        assertFalse(config.toString().contains("test-password"))
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
    fun `Ed25519 is accepted with the trusted public key`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val payloadBytes = payloadJsonWithWhitespace().toByteArray(Charsets.UTF_8)
        val result = adapter(signedEnvelope(keys, "Ed25519", payloadBytes), keys).load()
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

        assertEquals(
            "distribution_config_signature_invalid",
            (adapter(unknownKey, keys).load() as DistributionConfigLoadResult.Failure).reasonCode,
        )
        assertEquals(
            "distribution_config_envelope_fields_missing",
            (adapter(unknownAlgorithm, keys).load() as DistributionConfigLoadResult.Failure).reasonCode,
        )
    }

    @Test
    fun `expiry and identity fields are rejected`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val expired = payloadJsonWithWhitespace().replace("2099-12-31T00:00:00Z", "2020-01-01T00:00:00Z")
        val identityOverride = payloadJsonWithWhitespace().replace(
            "\"appId\":\"desktop\",\"archiveFileName\":\"03desktop-debug.zip\"",
            "\"appId\":\"desktop\",\"archiveFileName\":\"03desktop-debug.zip\",\"packageName\":\"com.attacker.app\"",
        )

        assertEquals(
            "distribution_config_expired",
            (adapter(signedEnvelope(keys, "SHA256withECDSA", expired.toByteArray()), keys).load()
                as DistributionConfigLoadResult.Failure).reasonCode,
        )
        assertEquals(
            "distribution_config_payload_invalid",
            (adapter(signedEnvelope(keys, "SHA256withECDSA", identityOverride.toByteArray()), keys).load()
                as DistributionConfigLoadResult.Failure).reasonCode,
        )
    }

    @Test
    fun `revision store rejects rollback`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val store = InMemoryCatalogRevisionStore()
        val first = adapter(signedEnvelope(keys, "SHA256withECDSA", payloadJsonWithWhitespace().toByteArray()), keys, store).load()
        assertTrue(first is DistributionConfigLoadResult.Success)
        val older = payloadJsonWithWhitespace().replace(Regex("\\\"catalogRevision\\\"\\s*:\\s*7"), "\"catalogRevision\": 6")
        assertEquals(
            "distribution_config_rollback",
            reason(
                adapter(
                    signedEnvelope(keys, "SHA256withECDSA", older.toByteArray()).copy(catalogRevision = 6L),
                    keys,
                    store,
                ),
            ),
        )
    }

    @Test
    fun `v3 rejects duplicate ids unsafe archive names and invalid desktop policy`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

        assertEquals(
            "distribution_config_app_duplicate",
            failureReason(
                keys,
                payloadDocument(apps = defaultApps().toMutableList().apply {
                    this[1] = this[1].copy(appId = "desktop")
                }),
            ),
        )
        assertEquals(
            "distribution_config_archive_file_name_invalid",
            failureReason(
                keys,
                payloadDocument(apps = defaultApps().toMutableList().apply {
                    this[1] = this[1].copy(archiveFileName = "../lyrics.zip")
                }),
            ),
        )
        assertEquals(
            "distribution_config_desktop_missing",
            failureReason(
                keys,
                payloadDocument(apps = defaultApps().map { app ->
                    if (app.appId == "desktop") app.copy(enabled = false) else app
                }),
            ),
        )
        assertEquals(
            "distribution_config_desktop_missing",
            failureReason(
                keys,
                payloadDocument(apps = defaultApps().map { app ->
                    if (app.appId == "desktop") app.copy(installPolicy = "optional") else app
                }),
            ),
        )
    }

    @Test
    fun `debug profile rejects a production environment`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

        assertEquals(
            "distribution_config_environment_invalid",
            failureReason(keys, payloadDocument(environment = "production")),
        )
    }

    @Test
    fun `desktop capability is fatal but optional capability is isolated`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        assertEquals(
            "distribution_config_desktop_client_schema_unsupported",
            failureReason(
                keys,
                payloadDocument(apps = defaultApps().map { app ->
                    if (app.appId == "desktop") app.copy(minClientSchemaVersion = 5) else app
                }),
            ),
        )

        val optionalUnsupported = defaultApps().map { app ->
            if (app.appId == "lyrics") app.copy(minClientSchemaVersion = 5) else app
        }
        val result = loadDocument(keys, payloadDocument(apps = optionalUnsupported))
        val config = (result as DistributionConfigLoadResult.Success).config
        assertFalse(config.apps.single { it.appId == "lyrics" }.clientSupported)
        assertTrue(config.apps.single { it.appId == "desktop" }.clientSupported)
    }

    @Test
    fun `v3 rejects duplicate typed setup and an inverted time window`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val duplicateSetup = InstallerDeviceSetupDocument(
            appOps = listOf("SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW"),
        )
        assertEquals(
            "distribution_config_device_setup_duplicate",
            failureReason(
                keys,
                payloadDocument(apps = defaultApps().map { app ->
                    if (app.appId == "notes") app.copy(deviceSetup = duplicateSetup) else app
                }),
            ),
        )
        assertEquals(
            "distribution_config_time_window_invalid",
            failureReason(
                keys,
                payloadDocument(
                    issuedAtUtc = "2026-08-22T00:04:00Z",
                    expiresAt = "2026-08-22T00:03:00Z",
                ),
            ),
        )
    }

    @Test
    fun `v4 accepts a signed app icon and rejects an unbound digest`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val v4 = payloadDocument(
            schemaVersion = 4,
            apps = defaultApps().mapIndexed { index, app -> app.copy(icon = icon(index)) },
        )
        val config = (loadDocument(keys, v4) as DistributionConfigLoadResult.Success).config
        assertEquals("03desktop-staging-logo-app-icon", config.apps.first().iconAsset?.assetId)
        assertEquals(216, config.apps.first().iconAsset?.width)

        val invalid = v4.copy(
            apps = v4.apps.mapIndexed { index, app ->
                if (index == 0) app.copy(icon = app.icon?.copy(sha256 = "b".repeat(64))) else app
            },
        )
        assertEquals("distribution_config_icon_digest_mismatch", failureReason(keys, invalid))

        val productMismatch = v4.copy(
            apps = v4.apps.mapIndexed { index, app ->
                if (index == 0) app.copy(icon = app.icon?.copy(url = app.icon.url.replace("/03desktop/", "/03lyrics/"))) else app
            },
        )
        assertEquals("distribution_config_icon_product_mismatch", failureReason(keys, productMismatch))
    }

    @Test
    fun `v4 accepts the approved Fossify file manager icon namespace`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val digest = "36552cb434f400ee43406203011ac6364a7ff0fc9308e6e653d6f3518ed0f2db"
        val fileManager = InstallerAppSourceDocument(
            appId = "file-manager",
            archiveFileName = "fossify-file-manager-car-release-1.6.1-car175.1.zip",
            displayName = "文件管理器",
            description = "Fossify 文件管理器车机适配版",
            versionCode = 14L,
            versionName = "1.6.1-car175.1",
            apkSizeBytes = 9_982_465L,
            enabled = true,
            installPolicy = "optional",
            sortOrder = 40,
            minClientSchemaVersion = 4,
            trustProfileId = "fossify-approved",
            deviceSetup = InstallerDeviceSetupDocument(),
            icon = InstallerAppIconDocument(
                assetId = "fossify-file-manager-production-logo-app-icon",
                url = "https://download.9.9studio.fun/03-apps/logos/fossify-file-manager/sha256-$digest.webp",
                mimeType = "image/webp",
                width = 192,
                height = 192,
                sizeBytes = 7_310L,
                sha256 = digest,
            ),
        )
        val document = payloadDocument(
            schemaVersion = 4,
            apps = defaultApps().mapIndexed { index, app -> app.copy(icon = icon(index)) } + fileManager,
        )

        val config = (loadDocument(keys, document) as DistributionConfigLoadResult.Success).config

        assertEquals("fossify-file-manager-production-logo-app-icon", config.apps.last().iconAsset?.assetId)
    }

    @Test
    fun `same revision with a different catalog version is rejected`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val store = InMemoryCatalogRevisionStore()
        assertTrue(loadDocument(keys, payloadDocument(catalogVersion = "catalog-a"), store) is DistributionConfigLoadResult.Success)
        assertEquals(
            "distribution_config_revision_store_failed",
            failureReason(
                keys,
                payloadDocument(catalogVersion = "catalog-b"),
                store,
            ),
        )
    }

    @Test
    fun `Cloud release metadata is projected as compact version and APK size`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val document = payloadDocument(
            apps = defaultApps().map { app ->
                if (app.appId == "desktop") {
                    app.copy(versionCode = 123L, versionName = "1.2.3", apkSizeBytes = 2_726_400L)
                } else {
                    app
                }
            },
        )

        val config = (loadDocument(keys, document) as DistributionConfigLoadResult.Success).config
        val desktop = config.apps.single { it.appId == "desktop" }

        assertEquals(123L, desktop.versionCode)
        assertEquals("1.2.3", desktop.versionName)
        assertEquals("v1.2.3", desktop.displayVersionLabel)
        assertEquals("2.6M", desktop.displaySizeLabel)
        assertEquals("2.6M", formatArtifactSizeLabel(2_726_400L))
    }

    @Test
    fun `file revision store survives recreation and rejects an older snapshot`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val root = Files.createTempDirectory("catalog-revision-store").toFile()
        try {
            val file = root.resolve("revisions.properties")
            assertTrue(loadDocument(keys, payloadDocument(catalogRevision = 9L), FileCatalogRevisionStore(file)) is DistributionConfigLoadResult.Success)
            val older = payloadDocument(catalogRevision = 8L)
            assertEquals(
                "distribution_config_rollback",
                failureReason(keys, older, FileCatalogRevisionStore(file)),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun reason(adapter: CloudInstallerDistributionConfigAdapter): String =
        (runBlocking { adapter.load() } as DistributionConfigLoadResult.Failure).reasonCode

    private fun adapter(
        envelope: SignedInstallerConfigEnvelope,
        trustedKeyPair: KeyPair,
        revisionStore: CatalogRevisionStore = InMemoryCatalogRevisionStore(),
    ): CloudInstallerDistributionConfigAdapter {
        val body = CloudReleaseCatalogAdapter.STRICT_JSON.encodeToString(envelope).toByteArray(Charsets.UTF_8)
        return CloudInstallerDistributionConfigAdapter(
            transport = ReleaseCatalogTransport { CatalogHttpResponse(200, body, "application/json") },
            signatureVerifier = JcaCatalogSignatureVerifier(
                TrustedCatalogKeyResolver { keyId -> trustedKeyPair.public.encoded.takeIf { keyId == "test-key" } },
            ),
            expectedChannel = "debug",
            now = { Instant.parse("2026-08-22T00:00:00Z") },
            revisionStore = revisionStore,
        )
    }

    private fun failureReason(
        keys: KeyPair,
        document: InstallerDistributionConfigPayload,
        revisionStore: CatalogRevisionStore = InMemoryCatalogRevisionStore(),
    ): String = (loadDocument(keys, document, revisionStore) as DistributionConfigLoadResult.Failure).reasonCode

    private fun loadDocument(
        keys: KeyPair,
        document: InstallerDistributionConfigPayload,
        revisionStore: CatalogRevisionStore = InMemoryCatalogRevisionStore(),
    ): DistributionConfigLoadResult = runBlocking {
        val payload = CloudReleaseCatalogAdapter.STRICT_JSON.encodeToString(document).toByteArray(Charsets.UTF_8)
        adapter(
            signedEnvelopeForDocument(keys, payload, document.schemaVersion, document.catalogVersion, document.catalogRevision),
            keys,
            revisionStore,
        ).load()
    }

    private fun signedEnvelopeForDocument(
        keys: KeyPair,
        payloadBytes: ByteArray,
        schemaVersion: Int,
        catalogVersion: String,
        catalogRevision: Long,
    ): SignedInstallerConfigEnvelope {
        val signer = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keys.private)
            update(payloadBytes)
        }
        return SignedInstallerConfigEnvelope(
            schemaVersion = schemaVersion,
            configVersion = catalogVersion,
            keyId = "test-key",
            signatureAlgorithm = "SHA256withECDSA",
            payloadBase64 = Base64.getEncoder().encodeToString(payloadBytes),
            signatureBase64 = Base64.getEncoder().encodeToString(signer.sign()),
            catalogVersion = catalogVersion,
            catalogRevision = catalogRevision,
        )
    }

    private fun payloadDocument(
        apps: List<InstallerAppSourceDocument> = defaultApps(),
        schemaVersion: Int = 3,
        environment: String = "staging",
        issuedAtUtc: String = "2026-08-22T00:00:00Z",
        expiresAt: String = "2099-12-31T00:00:00Z",
        catalogVersion: String = "android-debug-test-007",
        catalogRevision: Long = 7L,
    ): InstallerDistributionConfigPayload = InstallerDistributionConfigPayload(
        schemaVersion = schemaVersion,
        environment = environment,
        channel = "debug",
        issuedAtUtc = issuedAtUtc,
        expiresAt = expiresAt,
        catalogVersion = catalogVersion,
        catalogRevision = catalogRevision,
        folderUrl = "https://wwatl.lanzouw.com/b0fqlrcyb",
        folderPassword = "test-password",
        previousVersionsUrl = "",
        previousVersionsPassword = "",
        apps = apps,
    )

    private fun defaultApps(): List<InstallerAppSourceDocument> = listOf(
        InstallerAppSourceDocument(
            appId = "desktop",
            archiveFileName = "03desktop-debug.zip",
            displayName = "03桌面",
            description = "车机桌面",
            versionCode = 1L,
            versionName = "0.1.0",
            apkSizeBytes = 1_200_000L,
            enabled = true,
            installPolicy = "required",
            sortOrder = 10,
            minClientSchemaVersion = 3,
            trustProfileId = "nine-studio",
            deviceSetup = InstallerDeviceSetupDocument(),
        ),
        InstallerAppSourceDocument(
            appId = "lyrics",
            archiveFileName = "03lyrics-debug.zip",
            displayName = "03歌词",
            description = "歌词",
            versionCode = 114L,
            versionName = "1.14",
            apkSizeBytes = 1_800_000L,
            enabled = true,
            installPolicy = "optional",
            sortOrder = 20,
            minClientSchemaVersion = 3,
            trustProfileId = "nine-studio",
            deviceSetup = InstallerDeviceSetupDocument(),
        ),
        InstallerAppSourceDocument(
            appId = "notes",
            archiveFileName = "03notes-debug.zip",
            displayName = "Notes",
            description = "Notes",
            versionCode = 1L,
            versionName = "1.0",
            apkSizeBytes = 900_000L,
            enabled = true,
            installPolicy = "optional",
            sortOrder = 30,
            minClientSchemaVersion = 3,
            trustProfileId = "nine-studio",
            deviceSetup = InstallerDeviceSetupDocument(),
        ),
    )

    private fun icon(index: Int): InstallerAppIconDocument {
        val desktop = index == 0
        val productId = when (index) {
            0 -> "03desktop"
            1 -> "03lyrics"
            else -> "03notes"
        }
        val sha256 = if (desktop) {
            "956002f63794d3e1e98b6ec9b4608aa8a526e033f6b830fd28c6d43a585aebb7"
        } else {
            "cc38255660f381d79957b1fd0b6b26e98e8f0f906af64bd2e2d2dd14200dc119"
        }
        return InstallerAppIconDocument(
            assetId = "$productId-staging-logo-app-icon",
            url = "https://download.9.9studio.fun/03-apps/logos/$productId/sha256-$sha256.png",
            mimeType = "image/png",
            width = 216,
            height = 216,
            sizeBytes = if (desktop) 44_699L else 43_778L,
            sha256 = sha256,
        )
    }

    private fun signedEnvelope(keys: KeyPair, algorithm: String, payloadBytes: ByteArray): SignedInstallerConfigEnvelope {
        val signer = Signature.getInstance(algorithm).apply {
            initSign(keys.private)
            update(payloadBytes)
        }
        return SignedInstallerConfigEnvelope(
            schemaVersion = 3,
            configVersion = "android-debug-test-007",
            keyId = "test-key",
            signatureAlgorithm = algorithm,
            payloadBase64 = Base64.getEncoder().encodeToString(payloadBytes),
            signatureBase64 = Base64.getEncoder().encodeToString(signer.sign()),
            catalogRevision = 7L,
        )
    }

    private fun payloadJsonWithWhitespace(): String = """
        {
          "schemaVersion": 3,
          "environment": "staging",
          "channel": "debug",
          "issuedAtUtc": "2026-08-22T00:00:00Z",
          "expiresAt": "2099-12-31T00:00:00Z",
          "catalogVersion": "android-debug-test-007",
          "catalogRevision": 7,
          "folderUrl": "https://wwatl.lanzouw.com/b0fqlrcyb",
          "folderPassword": "test-password",
          "previousVersionsUrl": "",
          "previousVersionsPassword": "",
          "apps": [
            {"appId":"desktop","archiveFileName":"03desktop-debug.zip","displayName":"03桌面","description":"车机桌面","versionCode":1,"versionName":"0.1.0","apkSizeBytes":1200000,"enabled":true,"installPolicy":"required","sortOrder":10,"minClientSchemaVersion":3,"trustProfileId":"nine-studio","deviceSetup":{"profileId":"","actionIds":[]}},
            {"appId":"lyrics","archiveFileName":"03lyrics-debug.zip","displayName":"03歌词","description":"歌词","versionCode":114,"versionName":"1.14","apkSizeBytes":1800000,"enabled":true,"installPolicy":"optional","sortOrder":20,"minClientSchemaVersion":3,"trustProfileId":"nine-studio","deviceSetup":{"profileId":"","actionIds":[]}},
            {"appId":"notes","archiveFileName":"03notes-debug.zip","displayName":"Notes","description":"Notes","versionCode":1,"versionName":"1.0","apkSizeBytes":900000,"enabled":true,"installPolicy":"optional","sortOrder":30,"minClientSchemaVersion":3,"trustProfileId":"nine-studio","deviceSetup":{"profileId":"","actionIds":[]}}
          ]
        }
    """.trimIndent()
}
