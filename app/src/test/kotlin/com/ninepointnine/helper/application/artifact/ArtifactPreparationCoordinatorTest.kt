package com.ninepointnine.helper.application.artifact

import com.ninepointnine.helper.data.artifact.ApkMetadata
import com.ninepointnine.helper.data.artifact.ApkMetadataReader
import com.ninepointnine.helper.data.artifact.ArchiveIdentityVerifier
import com.ninepointnine.helper.data.artifact.ArtifactArchiveExtractor
import com.ninepointnine.helper.data.artifact.ArtifactIdentityVerifier
import com.ninepointnine.helper.data.catalog.ArtifactPreparationPlan
import com.ninepointnine.helper.data.catalog.InstallerComponentSource
import com.ninepointnine.helper.data.catalog.InstallerDistributionConfig
import com.ninepointnine.helper.data.download.ArtifactCache
import com.ninepointnine.helper.data.download.ArtifactDownloader
import com.ninepointnine.helper.data.download.ArtifactTransport
import com.ninepointnine.helper.data.download.ArtifactTransportResponse
import com.ninepointnine.helper.data.web.LanzouFolderEntry
import com.ninepointnine.helper.data.web.LanzouFolderSourceAdapter
import com.ninepointnine.helper.data.web.LanzouFolderWebViewHost
import com.ninepointnine.helper.data.web.LanzouFolderWebViewHostFactory
import com.ninepointnine.helper.data.web.LanzouWebSourceAdapter
import com.ninepointnine.helper.data.web.LanzouWebViewHost
import com.ninepointnine.helper.data.web.LanzouWebViewHostFactory
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.InstallerComponentTrustRegistry
import com.ninepointnine.helper.domain.artifact.InstallerPublisherTrustRegistry
import com.ninepointnine.helper.domain.artifact.ReleaseSourceMode
import com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy
import com.ninepointnine.helper.domain.artifact.ResolvedDownloadRequest
import com.ninepointnine.helper.domain.session.InstallationBatchPlan
import com.ninepointnine.helper.domain.session.InstallationCatalogIdentity
import com.ninepointnine.helper.domain.session.InstallationFlow
import com.ninepointnine.helper.application.session.InstallationSessionBoundary
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import com.ninepointnine.helper.domain.session.InstallationStrategy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactPreparationCoordinatorTest {
    @Test
    fun `public Download hit is accepted before any Lanzou resolution`() = runBlocking {
        val root = Files.createTempDirectory("artifact-public-hit").toFile()
        val publicDownload = root.resolve("Download").apply { mkdirs() }
        val config = config()
        val component = component(config, "desktop")
        val apkBytes = byteArrayOf(1, 2, 3, 4)
        publicDownload.resolve("manual-desktop.apk").writeBytes(apkBytes)
        val events = mutableListOf<InstallationSessionEvent>()
        var folderCalls = 0
        var shareCalls = 0
        var downloadCalls = 0
        try {
            val reader = metadataReader(config)
            val coordinator = coordinator(
                config = config,
                cache = ArtifactCache(root.resolve("private"), publicDownload),
                metadataReader = reader,
                folderHost = {
                    folderCalls += 1
                    error("folder_should_not_open")
                },
                shareHost = {
                    shareCalls += 1
                    error("share_should_not_open")
                },
                transport = { _, _ ->
                    downloadCalls += 1
                    error("download_should_not_run")
                },
                eventPort = activeBoundary { events += it },
            )

            val result = coordinator.prepare(plan(config, setOf(component.componentId)))

            assertTrue(result is ArtifactPreparationResult.Prepared)
            assertEquals(listOf("desktop"), (result as ArtifactPreparationResult.Prepared).artifacts.map { it.manifest.componentId })
            assertEquals(0, folderCalls)
            assertEquals(0, shareCalls)
            assertEquals(0, downloadCalls)
            assertEquals(ArtifactSourceKind.LOCAL_DOWNLOAD, events.filterIsInstance<InstallationSessionEvent.ArtifactBatchPrepared>()
                .single().sourceSelections.single().sourceKind)
            assertTrue(root.resolve("private").walkTopDown().none { it.isFile && it.extension == "apk" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `trusted stale public APK is ignored and the signed target is resolved remotely`() = runBlocking {
        val root = Files.createTempDirectory("artifact-stale-target").toFile()
        val publicDownload = root.resolve("Download").apply { mkdirs() }
        val baseConfig = config()
        val target = component(baseConfig, "desktop").copy(versionCode = 2L, versionName = "2.0")
        val targetConfig = baseConfig.copy(
            apps = baseConfig.apps.map { if (it.componentId == target.componentId) target else it },
        )
        val oldBytes = "desktop-old".toByteArray()
        val targetBytes = "desktop-target".toByteArray()
        publicDownload.resolve("old-desktop.apk").writeBytes(oldBytes)
        var folderCalls = 0
        var shareCalls = 0
        var downloadCalls = 0
        val metadataReader = ApkMetadataReader { apk ->
            val version = if (apk.readBytes().contentEquals(oldBytes)) {
                com.ninepointnine.helper.domain.artifact.ArtifactVersion("1.0", 1L)
            } else {
                com.ninepointnine.helper.domain.artifact.ArtifactVersion(target.versionName, target.versionCode)
            }
            ApkMetadata(
                packageName = target.packageName,
                version = version,
                certificateSha256s = setOf(target.certificateSha256),
            )
        }
        try {
            val result = coordinator(
                config = targetConfig,
                cache = ArtifactCache(root.resolve("private"), publicDownload),
                metadataReader = metadataReader,
                folderHost = {
                    folderCalls += 1
                    FolderHost(listOf(LanzouFolderEntry("idesktop", target.archiveFileName)))
                },
                shareHost = {
                    shareCalls += 1
                    ShareHost {
                        ResolvedDownloadRequest(
                            ArtifactSourceKind.LANZOU_SHARE,
                            "https://zip1.webgetstore.com/desktop",
                            userAgent = "test",
                        )
                    }
                },
                transport = { _, _ ->
                    downloadCalls += 1
                    val bytes = zip(target.apkEntryName, targetBytes)
                    ArtifactTransportResponse(200, bytes.size.toLong(), "application/zip", ByteArrayInputStream(bytes))
                },
            ).prepare(plan(targetConfig, setOf(target.componentId)))

            assertTrue(result is ArtifactPreparationResult.Prepared)
            val prepared = result as ArtifactPreparationResult.Prepared
            assertEquals(listOf(target.componentId), prepared.artifacts.map { it.manifest.componentId })
            assertEquals(target.versionCode, prepared.artifacts.single().manifest.apkVersion.code)
            assertEquals(ArtifactSourceKind.LANZOU_SHARE, prepared.artifacts.single().sourceKind)
            assertEquals(1, folderCalls)
            assertEquals(1, shareCalls)
            assertEquals(1, downloadCalls)
            assertTrue(publicDownload.resolve("old-desktop.apk").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `remote APK with trusted identity but wrong signed target version is rejected`() = runBlocking {
        val root = Files.createTempDirectory("artifact-remote-version-mismatch").toFile()
        val publicDownload = root.resolve("Download").apply { mkdirs() }
        val baseConfig = config()
        val target = component(baseConfig, "desktop").copy(versionCode = 2L, versionName = "2.0")
        val targetConfig = baseConfig.copy(
            apps = baseConfig.apps.map { if (it.componentId == target.componentId) target else it },
        )
        val remoteBytes = "desktop-old-remote".toByteArray()
        var downloadCalls = 0
        val metadataReader = ApkMetadataReader {
            ApkMetadata(
                packageName = target.packageName,
                version = com.ninepointnine.helper.domain.artifact.ArtifactVersion("1.0", 1L),
                certificateSha256s = setOf(target.certificateSha256),
            )
        }
        try {
            val result = coordinator(
                config = targetConfig,
                cache = ArtifactCache(root.resolve("private"), publicDownload),
                metadataReader = metadataReader,
                folderHost = {
                    FolderHost(listOf(LanzouFolderEntry("idesktop", target.archiveFileName)))
                },
                shareHost = {
                    ShareHost {
                        ResolvedDownloadRequest(
                            ArtifactSourceKind.LANZOU_SHARE,
                            "https://zip1.webgetstore.com/desktop",
                            userAgent = "test",
                        )
                    }
                },
                transport = { _, _ ->
                    downloadCalls += 1
                    val bytes = zip(target.apkEntryName, remoteBytes)
                    ArtifactTransportResponse(200, bytes.size.toLong(), "application/zip", ByteArrayInputStream(bytes))
                },
            ).prepare(plan(targetConfig, setOf(target.componentId)))

            assertTrue(result is ArtifactPreparationResult.Prepared)
            val prepared = result as ArtifactPreparationResult.Prepared
            assertTrue(prepared.artifacts.isEmpty())
            assertEquals("distribution_apk_version_mismatch", prepared.failures.single().reasonCode)
            assertEquals(1, downloadCalls)
            assertTrue(publicDownload.listFiles().orEmpty().none { it.name.startsWith("03helper-desktop-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `mixed batch resolves Lanzou only for the missing component and publishes its APK`() = runBlocking {
        val root = Files.createTempDirectory("artifact-mixed-batch").toFile()
        val publicDownload = root.resolve("Download").apply { mkdirs() }
        val config = config()
        val desktop = component(config, "desktop")
        val lyrics = component(config, "lyrics")
        val apkBytes = "lyrics-apk".toByteArray()
        publicDownload.resolve("manual-desktop.apk").writeBytes(byteArrayOf(1, 2, 3, 4))
        var folderCalls = 0
        var shareCalls = 0
        var downloadCalls = 0
        try {
            val cache = ArtifactCache(root.resolve("private"), publicDownload)
            val result = coordinator(
                config = config,
                cache = cache,
                metadataReader = metadataReader(config),
                folderHost = {
                    folderCalls += 1
                    FolderHost(listOf(LanzouFolderEntry("ilyrics", lyrics.archiveFileName)))
                },
                shareHost = {
                    shareCalls += 1
                    ShareHost { ResolvedDownloadRequest(ArtifactSourceKind.LANZOU_SHARE, "https://zip1.webgetstore.com/lyrics", userAgent = "test") }
                },
                transport = { _, _ ->
                    downloadCalls += 1
                    val bytes = zip(lyrics.apkEntryName, apkBytes)
                    ArtifactTransportResponse(200, bytes.size.toLong(), "application/zip", ByteArrayInputStream(bytes))
                },
            ).prepare(plan(config, setOf(desktop.componentId, lyrics.componentId)))

            assertTrue(result is ArtifactPreparationResult.Prepared)
            val prepared = result as ArtifactPreparationResult.Prepared
            assertEquals(setOf("desktop", "lyrics"), prepared.artifacts.map { it.manifest.componentId }.toSet())
            assertEquals(1, folderCalls)
            assertEquals(1, shareCalls)
            assertEquals(1, downloadCalls)
            assertTrue(publicDownload.listFiles().orEmpty().any { it.name.startsWith("03helper-lyrics-") && it.extension == "apk" })
            assertTrue(root.resolve("private").walkTopDown().none { it.isFile && it.extension == "apk" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `stale public APK is ignored and the missing component remains an explicit preparation failure`() = runBlocking {
        val root = Files.createTempDirectory("artifact-stale-public").toFile()
        val publicDownload = root.resolve("Download").apply { mkdirs() }
        val config = config()
        val lyrics = component(config, "lyrics")
        publicDownload.resolve("stale-lyrics.apk").writeBytes(byteArrayOf(9, 9, 9))
        try {
            var folderCalls = 0
            val result = coordinator(
                config = config,
                cache = ArtifactCache(root.resolve("private"), publicDownload),
                metadataReader = ApkMetadataReader {
                    ApkMetadata(
                        packageName = "com.example.untrusted",
                        version = com.ninepointnine.helper.domain.artifact.ArtifactVersion("1.0", 1L),
                        certificateSha256s = setOf("00".repeat(32)),
                    )
                },
                folderHost = {
                    folderCalls += 1
                    FolderHost(emptyList())
                },
            ).prepare(plan(config, setOf(lyrics.componentId)))

            assertTrue(result is ArtifactPreparationResult.Prepared)
            val prepared = result as ArtifactPreparationResult.Prepared
            assertTrue(prepared.artifacts.isEmpty())
            assertEquals("lanzou_folder_missing_lyrics", prepared.failures.single().reasonCode)
            assertEquals(1, folderCalls)
            assertTrue(publicDownload.resolve("stale-lyrics.apk").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun coordinator(
        config: InstallerDistributionConfig,
        cache: ArtifactCache,
        metadataReader: ApkMetadataReader,
        folderHost: () -> LanzouFolderWebViewHost = { FolderHost(emptyList()) },
        shareHost: () -> LanzouWebViewHost = { ShareHost { error("share_not_expected") } },
        transport: suspend (ResolvedDownloadRequest, Long) -> ArtifactTransportResponse = { _, _ ->
            error("download_not_expected")
        },
        eventPort: InstallationSessionBoundary = activeBoundary(),
    ): ArtifactPreparationCoordinator {
        val policy = ReleaseSourcePolicy(mode = ReleaseSourceMode.FOLDER_CONFIG)
        return ArtifactPreparationCoordinator(
            sourcePolicy = policy,
            lanzouSourceAdapter = LanzouWebSourceAdapter(LanzouWebViewHostFactory { shareHost() }, policy),
            downloader = ArtifactDownloader(ArtifactTransport(transport), cache, policy),
            archiveVerifier = com.ninepointnine.helper.data.artifact.ArchiveIdentityVerifier(),
            archiveExtractor = com.ninepointnine.helper.data.artifact.ArtifactArchiveExtractor { Long.MAX_VALUE },
            identityVerifier = ArtifactIdentityVerifier(metadataReader),
            cache = cache,
            eventPort = eventPort,
            folderSourceAdapter = LanzouFolderSourceAdapter(
                LanzouFolderWebViewHostFactory { folderHost() },
                policy,
            ),
            metadataReader = metadataReader,
        )
    }

    private fun activeBoundary(
        onEvent: (InstallationSessionEvent) -> Unit = {},
    ): InstallationSessionBoundary = object : InstallationSessionBoundary {
        override fun emit(event: InstallationSessionEvent) = onEvent(event)

        override fun isBatchActive(batchId: Long): Boolean = true

        override fun isArtifactPreparationActive(batchId: Long): Boolean = true
    }

    private fun plan(config: InstallerDistributionConfig, selected: Set<String>): ArtifactPreparationPlan {
        val components = config.apps.filter { it.componentId in selected }
        return ArtifactPreparationPlan(
            batch = InstallationBatchPlan(
                batchId = 7L,
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
            ),
            config = config,
            components = components,
        )
    }

    private fun metadataReader(config: InstallerDistributionConfig): ApkMetadataReader = ApkMetadataReader { apk ->
        val bytes = apk.readBytes()
        val component = config.apps.firstOrNull {
            it.componentId == "lyrics" && bytes.contentEquals("lyrics-apk".toByteArray())
        }
            ?: config.apps.firstOrNull { apk.name.contains(it.componentId) }
            ?: config.apps.first { it.componentId == "desktop" }
        ApkMetadata(
            packageName = component.packageName,
            version = com.ninepointnine.helper.domain.artifact.ArtifactVersion(component.versionName, component.versionCode),
            certificateSha256s = setOf(component.certificateSha256),
        )
    }

    private fun component(config: InstallerDistributionConfig, id: String): InstallerComponentSource =
        config.apps.first { it.componentId == id }

    private fun config(): InstallerDistributionConfig = InstallerDistributionConfig(
        configVersion = "debug-test-v1",
        channel = "debug",
        expiresAt = Instant.now().plusSeconds(3_600L),
        folderUrl = "https://wwatl.lanzouw.com/b0fqlrcyb",
        folderPassword = "test-password",
        keyId = "test-key",
        signatureAlgorithm = "SHA256withECDSA",
        environment = "staging",
        issuedAtUtc = Instant.now().minusSeconds(60L),
        catalogVersion = "debug-test-v1",
        catalogRevision = 1L,
        apps = InstallerComponentTrustRegistry.components.map { definition ->
            val stagingCertificate = when (definition.componentId) {
                "desktop" -> "bfb70dc15b54ad2f1b8acd35fa26ecf552bf2ef21d416a44b7eeda5e5e9ebaa9"
                "lyrics" -> "1eb136fffd3f1e4c204d0933cab66c51ee4536a29e949b9c080925c01563b51d"
                "cast" -> "98740b95c30064f727b9401a851ecf2e576d5e5c38fcc318284578747ba50e2a"
                else -> definition.certificateSha256
            }
            InstallerComponentSource(
                componentId = definition.componentId,
                archiveFileName = definition.archiveFileName,
                required = definition.required,
                displayName = definition.displayName,
                minAndroidSdk = definition.minAndroidSdk,
                packageName = when (definition.componentId) {
                    "desktop" -> "com.ninepointnine.desktop"
                    "lyrics" -> "com.ninepointnine.desktoplyrics"
                    else -> definition.packageName
                },
                certificateSha256 = stagingCertificate,
                apkEntryName = definition.apkEntryName,
                trustProfileId = definition.trustProfileId,
                versionCode = 1L,
                versionName = "1.0",
                apkSizeBytes = 1L,
            )
        },
    )

    private fun zip(entryName: String, bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(bytes)
            zip.closeEntry()
        }
        output.toByteArray()
    }

    private class FolderHost(private val entries: List<LanzouFolderEntry>) : LanzouFolderWebViewHost {
        override fun startFolder(
            folderUrl: String,
            password: String,
            expectedArchiveFileNames: Set<String>,
            onEntries: (List<LanzouFolderEntry>) -> Unit,
            onFailure: (com.ninepointnine.helper.domain.artifact.ArtifactFailure) -> Unit,
        ) = onEntries(entries)

        override fun stopAndDestroy() = Unit
    }

    private class ShareHost(
        private val requestFor: (String) -> ResolvedDownloadRequest,
    ) : LanzouWebViewHost {
        override fun start(
            shareUrl: String,
            onDownload: (ResolvedDownloadRequest) -> Unit,
            onFailure: (com.ninepointnine.helper.domain.artifact.ArtifactFailure) -> Unit,
        ) = onDownload(requestFor(shareUrl))

        override fun stopAndDestroy() = Unit
    }
}
