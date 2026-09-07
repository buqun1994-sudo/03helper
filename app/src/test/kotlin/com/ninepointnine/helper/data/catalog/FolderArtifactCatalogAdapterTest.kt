package com.ninepointnine.helper.data.catalog

import com.ninepointnine.helper.data.web.LanzouFolderEntry
import com.ninepointnine.helper.data.web.LanzouFolderSourceAdapter
import com.ninepointnine.helper.data.web.LanzouFolderWebViewHost
import com.ninepointnine.helper.data.web.LanzouFolderWebViewHostFactory
import com.ninepointnine.helper.domain.artifact.ReleaseSourceMode
import com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy
import com.ninepointnine.helper.domain.session.InstallationBatchPlan
import com.ninepointnine.helper.domain.session.InstallationCatalogIdentity
import com.ninepointnine.helper.domain.session.InstallationFlow
import com.ninepointnine.helper.domain.session.InstallationStrategy
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderArtifactCatalogAdapterTest {
    @Test
    fun `control plane configuration load does not enumerate the folder`() = runBlocking {
        val config = config()
        val adapter = FolderArtifactCatalogAdapter(configAdapter(config))

        val result = adapter.loadConfiguration()

        assertTrue(result is DistributionConfigLoadResult.Success)
        val loaded = (result as DistributionConfigLoadResult.Success).config
        assertEquals(config.effectiveCatalogVersion(), loaded.effectiveCatalogVersion())
        assertEquals(config.catalogRevision, loaded.catalogRevision)
    }

    @Test
    fun `selection load exposes signed enabled apps without resolving the folder`() = runBlocking {
        val config = config()
        val adapter = FolderArtifactCatalogAdapter(configAdapter(config))

        val result = adapter.loadSelection() as CatalogLoadResult.Success

        assertTrue(result.catalog.manifests.isEmpty())
        assertEquals(
            config.apps.filter { it.enabled && it.componentId != "03helper" }
                .sortedWith(compareBy({ it.sortOrder }, { it.componentId }))
                .map { it.componentId },
            result.catalog.apps.map { it.componentId },
        )
    }

    @Test
    fun `preparation plan freezes catalog identity and selected component set`() = runBlocking {
        val config = config()
        val adapter = FolderArtifactCatalogAdapter(configAdapter(config))
        adapter.loadSelection()
        val selected = setOf("desktop", "lyrics")
        val batch = InstallationBatchPlan(
            batchId = 41L,
            flow = InstallationFlow.INITIAL_INSTALL,
            strategy = InstallationStrategy.INSTALL_MISSING_ONLY,
            selectedComponentIds = selected,
            reusableComponentIds = emptySet(),
            preparationComponentIds = selected,
            resultComponentIds = selected,
            catalogIdentity = InstallationCatalogIdentity(
                version = config.effectiveCatalogVersion(),
                revision = config.catalogRevision,
                keyId = config.keyId,
                signatureAlgorithm = config.signatureAlgorithm,
            ),
        )

        val result = adapter.buildPreparationPlan(batch) as ArtifactPreparationPlanResult.Ready

        assertEquals(batch, result.plan.batch)
        assertEquals(config.effectiveCatalogVersion(), result.plan.config.effectiveCatalogVersion())
        assertEquals(selected, result.plan.components.map { it.componentId }.toSet())
        assertEquals(listOf("desktop", "lyrics"), result.plan.components.map { it.componentId })
    }

    @Test
    fun `single optional update accepts desktop as a preinstalled prerequisite`() = runBlocking {
        val config = config()
        val adapter = FolderArtifactCatalogAdapter(configAdapter(config))
        adapter.loadSelection()
        val batch = InstallationBatchPlan(
            batchId = 42L,
            flow = InstallationFlow.MAINTENANCE_INSTALL,
            strategy = InstallationStrategy.REINSTALL_SELECTED,
            selectedComponentIds = setOf("lyrics"),
            reusableComponentIds = emptySet(),
            preinstalledComponentIds = setOf("desktop"),
            preparationComponentIds = setOf("lyrics"),
            resultComponentIds = setOf("lyrics"),
            catalogIdentity = InstallationCatalogIdentity(
                version = config.effectiveCatalogVersion(),
                revision = config.catalogRevision,
                keyId = config.keyId,
                signatureAlgorithm = config.signatureAlgorithm,
            ),
        )

        val result = adapter.buildPreparationPlan(batch) as ArtifactPreparationPlanResult.Ready

        assertEquals(setOf("lyrics"), result.plan.components.map { it.componentId }.toSet())
    }

    @Test
    fun `preparation plan rejects catalog identity drift before any artifact work`() = runBlocking {
        val config = config()
        val adapter = FolderArtifactCatalogAdapter(configAdapter(config))
        adapter.loadSelection()
        val selected = setOf("desktop")
        val batch = InstallationBatchPlan(
            batchId = 42L,
            flow = InstallationFlow.INITIAL_INSTALL,
            strategy = InstallationStrategy.INSTALL_MISSING_ONLY,
            selectedComponentIds = selected,
            reusableComponentIds = emptySet(),
            preparationComponentIds = selected,
            resultComponentIds = selected,
            catalogIdentity = InstallationCatalogIdentity(
                version = "stale-catalog",
                revision = config.catalogRevision,
                keyId = config.keyId,
                signatureAlgorithm = config.signatureAlgorithm,
            ),
        )

        val result = adapter.buildPreparationPlan(batch) as ArtifactPreparationPlanResult.Failure

        assertEquals("selected_catalog_identity_mismatch", result.reasonCode)
        assertFalse(result.retryable)
    }

    @Test
    fun `folder source ignores unknown files without blocking declared apps`() = runBlocking {
        val adapter = LanzouFolderSourceAdapter(
            hostFactory = LanzouFolderWebViewHostFactory {
                FolderHost(
                    listOf(
                        LanzouFolderEntry("idesktop", "03desktop-debug.zip"),
                        LanzouFolderEntry("ilyrics", "03lyrics-debug.zip"),
                        LanzouFolderEntry("ireadme", "readme.txt"),
                    ),
                )
            },
            sourcePolicy = ReleaseSourcePolicy(mode = ReleaseSourceMode.FOLDER_CONFIG),
        )

        val result = adapter.resolve(config()) as com.ninepointnine.helper.data.web.LanzouFolderResolutionResult.Success

        assertEquals(listOf("desktop", "lyrics"), result.artifacts.map { it.component.componentId })
    }

    @Test
    fun `folder source only reports the requested component set`() = runBlocking {
        val adapter = LanzouFolderSourceAdapter(
            hostFactory = LanzouFolderWebViewHostFactory {
                FolderHost(listOf(LanzouFolderEntry("idesktop", "03desktop-debug.zip")))
            },
            sourcePolicy = ReleaseSourcePolicy(mode = ReleaseSourceMode.FOLDER_CONFIG),
        )

        val result = adapter.resolve(config(), expectedComponentIds = setOf("desktop"))
            as com.ninepointnine.helper.data.web.LanzouFolderResolutionResult.Success

        assertEquals(listOf("desktop"), result.artifacts.map { it.component.componentId })
        assertTrue(result.appFailures.isEmpty())
    }

    @Test
    fun `folder source records missing and duplicate archive names`() = runBlocking {
        val config = config()
        val adapter = LanzouFolderSourceAdapter(
            hostFactory = LanzouFolderWebViewHostFactory {
                FolderHost(
                    listOf(
                        LanzouFolderEntry("idesktop", "03desktop-debug.zip"),
                        LanzouFolderEntry("idesktop2", "03DESKTOP-debug.zip"),
                    ),
                )
            },
            sourcePolicy = ReleaseSourcePolicy(mode = ReleaseSourceMode.FOLDER_CONFIG),
        )

        val result = adapter.resolve(config) as com.ninepointnine.helper.data.web.LanzouFolderResolutionResult.Success

        assertTrue(result.artifacts.isEmpty())
        assertEquals(
            "lanzou_folder_duplicate_file",
            result.appFailures.single { it.componentId == "desktop" }.reasonCode,
        )
        assertEquals(
            "lanzou_folder_missing_lyrics",
            result.appFailures.single { it.componentId == "lyrics" }.reasonCode,
        )
    }

    @Test
    fun `folder source accepts the first terminal callback only`() = runBlocking {
        val adapter = LanzouFolderSourceAdapter(
            hostFactory = LanzouFolderWebViewHostFactory {
                object : LanzouFolderWebViewHost {
                    override fun startFolder(
                        folderUrl: String,
                        password: String,
                        expectedArchiveFileNames: Set<String>,
                        onEntries: (List<LanzouFolderEntry>) -> Unit,
                        onFailure: (com.ninepointnine.helper.domain.artifact.ArtifactFailure) -> Unit,
                    ) {
                        onEntries(listOf(LanzouFolderEntry("idesktop", "03desktop-debug.zip")))
                        onFailure(
                            com.ninepointnine.helper.domain.artifact.ArtifactFailure(
                                phase = com.ninepointnine.helper.domain.artifact.ArtifactFailurePhase.SOURCE_RESOLUTION,
                                sourceKind = com.ninepointnine.helper.domain.artifact.ArtifactSourceKind.LANZOU_SHARE,
                                reasonCode = "late_failure",
                                retryable = false,
                            ),
                        )
                    }

                    override fun stopAndDestroy() = Unit
                }
            },
            sourcePolicy = ReleaseSourcePolicy(mode = ReleaseSourceMode.FOLDER_CONFIG),
        )

        val result = adapter.resolve(config()) as com.ninepointnine.helper.data.web.LanzouFolderResolutionResult.Success

        assertEquals(listOf("desktop"), result.artifacts.map { it.component.componentId })
    }

    private fun configAdapter(config: InstallerDistributionConfig): CloudInstallerDistributionConfigAdapter {
        val payload = InstallerDistributionConfigV5Payload(
            schemaVersion = 5,
            environment = config.environment,
            issuedAtUtc = config.issuedAtUtc.toString(),
            expiresAt = config.expiresAt.toString(),
            catalogRevision = config.catalogRevision,
            folderUrl = config.folderUrl,
            folderPassword = config.folderPassword,
            apps = config.apps.map { component ->
                InstallerAppV5Document(
                    appId = component.componentId,
                    packageName = component.packageName,
                    certificateSha256s = component.certificateSha256s.toList(),
                    apkSha256 = component.apkSha256,
                    archiveFileName = component.archiveFileName,
                    displayName = component.displayName,
                    versionCode = component.versionCode,
                    versionName = component.versionName,
                    apkSizeBytes = component.apkSizeBytes,
                    enabled = component.enabled,
                    installPolicy = if (component.required) "required" else "optional",
                    sortOrder = component.sortOrder,
                )
            },
        )
        val payloadBytes = CloudReleaseCatalogAdapter.STRICT_JSON.encodeToString(payload).toByteArray()
        val envelope = SignedInstallerConfigV5Envelope(
            keyId = config.keyId,
            signatureAlgorithm = config.signatureAlgorithm,
            payloadBase64 = Base64.getEncoder().encodeToString(payloadBytes),
            signatureBase64 = Base64.getEncoder().encodeToString(byteArrayOf(1)),
        )
        val body = CloudReleaseCatalogAdapter.STRICT_JSON.encodeToString(envelope).toByteArray()
        return CloudInstallerDistributionConfigAdapter(
            transport = ReleaseCatalogTransport { CatalogHttpResponse(200, body, "application/json") },
            signatureVerifier = CatalogSignatureVerifier { _, _, _, _ -> true },
            expectedChannel = config.channel,
        )
    }

    private fun config(): InstallerDistributionConfig = InstallerDistributionConfig(
        configVersion = "v5-1",
        channel = "debug",
        expiresAt = Instant.now().plusSeconds(3_600L),
        folderUrl = "https://wwatl.lanzouw.com/b0fqlrcyb",
        folderPassword = "test-password",
        keyId = "test-key",
        signatureAlgorithm = "SHA256withECDSA",
        environment = "staging",
        issuedAtUtc = Instant.now().minusSeconds(60L),
        catalogVersion = "v5-1",
        catalogRevision = 1L,
        apps = listOf("desktop", "lyrics").mapIndexed { index, id ->
            InstallerComponentSource(
                componentId = id, archiveFileName = "03$id-debug.zip", required = id == "desktop",
                displayName = id, packageName = "org.fixture.$id", sortOrder = index,
                certificateSha256 = "ab".repeat(32), certificateSha256s = setOf("ab".repeat(32)),
                apkEntryName = "app.apk", versionCode = 1, versionName = "1.0",
                apkSizeBytes = 1, apkSha256 = "cd".repeat(32),
            )
        },
    )

    private class FolderHost(
        private val entries: List<LanzouFolderEntry>,
    ) : LanzouFolderWebViewHost {
        override fun startFolder(
            folderUrl: String,
            password: String,
            expectedArchiveFileNames: Set<String>,
            onEntries: (List<LanzouFolderEntry>) -> Unit,
            onFailure: (com.ninepointnine.helper.domain.artifact.ArtifactFailure) -> Unit,
        ) = onEntries(entries)

        override fun stopAndDestroy() = Unit
    }
}
