package com.tcrrry.helper.data.catalog

import com.tcrrry.helper.data.artifact.ApkMetadata
import com.tcrrry.helper.data.artifact.ApkMetadataReader
import com.tcrrry.helper.data.download.ArtifactCache
import com.tcrrry.helper.data.download.ArtifactTransport
import com.tcrrry.helper.data.download.ArtifactTransportResponse
import com.tcrrry.helper.data.download.DynamicArtifactDownloader
import com.tcrrry.helper.data.web.LanzouFolderEntry
import com.tcrrry.helper.data.web.LanzouFolderSourceAdapter
import com.tcrrry.helper.data.web.LanzouFolderWebViewHost
import com.tcrrry.helper.data.web.LanzouFolderWebViewHostFactory
import com.tcrrry.helper.data.web.LanzouWebSourceAdapter
import com.tcrrry.helper.data.web.LanzouWebViewHost
import com.tcrrry.helper.data.web.LanzouWebViewHostFactory
import com.tcrrry.helper.domain.artifact.ArtifactSourceKind
import com.tcrrry.helper.domain.artifact.ArtifactVersion
import com.tcrrry.helper.domain.artifact.InstallerComponentTrustRegistry
import com.tcrrry.helper.domain.artifact.ReleaseSourceMode
import com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy
import com.tcrrry.helper.domain.artifact.ResolvedDownloadRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderArtifactCatalogAdapterTest {
    @Test
    fun `folder source rejects unknown files before any archive download`() = runBlocking {
        val config = config()
        val hostFactory = LanzouFolderWebViewHostFactory {
            FolderHost(
                entries = listOf(
                    LanzouFolderEntry("idesktop", "03desktop-debug.zip"),
                    LanzouFolderEntry("ilyrics", "03lyrics-debug.zip"),
                    LanzouFolderEntry("ifile", "fossify-file-manager-car-debug.zip"),
                    LanzouFolderEntry("ireadme", "readme.txt"),
                ),
            )
        }
        val adapter = LanzouFolderSourceAdapter(
            hostFactory = hostFactory,
            sourcePolicy = ReleaseSourcePolicy(mode = ReleaseSourceMode.FOLDER_CONFIG),
        )

        val result = adapter.resolve(config)

        assertEquals("lanzou_folder_unknown_file", (result as com.tcrrry.helper.data.web.LanzouFolderResolutionResult.Failure).failure.reasonCode)
    }

    @Test
    fun `folder source allows missing optional archives but requires desktop`() = runBlocking {
        val config = config()
        val adapter = LanzouFolderSourceAdapter(
            hostFactory = LanzouFolderWebViewHostFactory {
                FolderHost(listOf(LanzouFolderEntry("idesktop", "03desktop-debug.zip")))
            },
            sourcePolicy = ReleaseSourcePolicy(mode = ReleaseSourceMode.FOLDER_CONFIG),
        )

        val result = adapter.resolve(config)

        val artifacts = (result as com.tcrrry.helper.data.web.LanzouFolderResolutionResult.Success).artifacts
        assertEquals(listOf("desktop"), artifacts.map { it.component.componentId })
    }

    @Test
    fun `folder source forwards an empty password for an unprotected folder`() = runBlocking {
        val config = config().copy(folderPassword = "")
        var observedPassword: String? = null
        val adapter = LanzouFolderSourceAdapter(
            hostFactory = LanzouFolderWebViewHostFactory {
                object : LanzouFolderWebViewHost {
                    override fun startFolder(
                        folderUrl: String,
                        password: String,
                        onEntries: (List<LanzouFolderEntry>) -> Unit,
                        onFailure: (com.tcrrry.helper.domain.artifact.ArtifactFailure) -> Unit,
                    ) {
                        observedPassword = password
                        onEntries(listOf(LanzouFolderEntry("idesktop", "03desktop-debug.zip")))
                    }

                    override fun stopAndDestroy() = Unit
                }
            },
            sourcePolicy = ReleaseSourcePolicy(mode = ReleaseSourceMode.FOLDER_CONFIG),
        )

        val result = adapter.resolve(config)

        assertTrue(result is com.tcrrry.helper.data.web.LanzouFolderResolutionResult.Success)
        assertEquals("", observedPassword)
    }

    @Test
    fun `folder source rejects a missing desktop archive`() = runBlocking {
        val config = config()
        val adapter = LanzouFolderSourceAdapter(
            hostFactory = LanzouFolderWebViewHostFactory {
                FolderHost(listOf(LanzouFolderEntry("ilyrics", "03lyrics-debug.zip")))
            },
            sourcePolicy = ReleaseSourcePolicy(mode = ReleaseSourceMode.FOLDER_CONFIG),
        )

        val result = adapter.resolve(config)

        assertEquals(
            "lanzou_folder_missing_desktop",
            (result as com.tcrrry.helper.data.web.LanzouFolderResolutionResult.Failure).failure.reasonCode,
        )
    }

    @Test
    fun `folder source rejects duplicate names case insensitively`() = runBlocking {
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

        val result = adapter.resolve(config)

        assertEquals(
            "lanzou_folder_duplicate_file",
            (result as com.tcrrry.helper.data.web.LanzouFolderResolutionResult.Failure).failure.reasonCode,
        )
    }

    @Test
    fun `catalog reads a replacement archive as a new APK version`() = runBlocking {
        val config = config()
        val archives = mutableMapOf<String, ByteArray>()
        val versions = mutableMapOf<String, ArtifactVersion>()
        config.components.forEach { component ->
            archives[component.componentId] = zip(component.apkEntryName, "initial-${component.componentId}".toByteArray())
            versions[component.componentId] = ArtifactVersion("1.0", 1L)
        }
        val tempRoot = java.nio.file.Files.createTempDirectory("03helper-catalog-test").toFile()
        try {
            val sourcePolicy = ReleaseSourcePolicy(mode = ReleaseSourceMode.FOLDER_CONFIG)
            val adapter = FolderArtifactCatalogAdapter(
                configAdapter = configAdapter(config),
                folderSourceAdapter = LanzouFolderSourceAdapter(
                    hostFactory = LanzouFolderWebViewHostFactory {
                        FolderHost(config.components.map { component ->
                            LanzouFolderEntry(
                                when (component.componentId) {
                                    "file-manager" -> "ifilemanager"
                                    else -> "i${component.componentId}"
                                },
                                component.archiveFileName,
                            )
                        })
                    },
                    sourcePolicy = sourcePolicy,
                ),
                lanzouSourceAdapter = LanzouWebSourceAdapter(
                    hostFactory = LanzouWebViewHostFactory {
                        DownloadHost { sourceUrl ->
                            val componentId = sourceUrl.substringAfterLast('/').removePrefix("i")
                                .replace("filemanager", "file-manager")
                            ResolvedDownloadRequest(
                                sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                                url = "https://zip1.webgetstore.com/$componentId",
                                userAgent = "03helper-test",
                            )
                        }
                    },
                    sourcePolicy = sourcePolicy,
                ),
                downloader = DynamicArtifactDownloader(
                    transport = ArtifactTransport { request, _ ->
                        val componentId = request.url.substringAfterLast('/')
                        val bytes = archives[componentId] ?: error("archive_missing_$componentId")
                        ArtifactTransportResponse(
                            statusCode = 200,
                            contentLength = bytes.size.toLong(),
                            contentType = "application/zip",
                            body = ByteArrayInputStream(bytes),
                        )
                    },
                    sourcePolicy = sourcePolicy,
                ),
                metadataReader = ApkMetadataReader { apk ->
                    val componentId = apk.name.substringBefore('.')
                    val component = config.components.single { it.componentId == componentId }
                    ApkMetadata(
                        packageName = component.packageName,
                        version = versions.getValue(component.componentId),
                        certificateSha256s = setOf(component.certificateSha256),
                    )
                },
                sourcePolicy = sourcePolicy,
                artifactCache = ArtifactCache(tempRoot.resolve("artifacts")),
                workingDirectory = tempRoot.resolve("working"),
            )

            val first = adapter.load() as CatalogLoadResult.Success
            val firstDesktop = first.catalog.manifests.single { it.componentId == "desktop" }
            versions["desktop"] = ArtifactVersion("2.0", 2L)
            archives["desktop"] = zip("03desktop-debug.apk", "replacement-desktop".toByteArray())

            val second = adapter.load() as CatalogLoadResult.Success
            val secondDesktop = second.catalog.manifests.single { it.componentId == "desktop" }
            assertEquals(1L, firstDesktop.apkVersion.code)
            assertEquals(2L, secondDesktop.apkVersion.code)
            assertNotEquals(firstDesktop.archiveSha256, secondDesktop.archiveSha256)
            assertTrue(second.catalog.manifests.all { it.sources.single().kind == ArtifactSourceKind.LANZOU_SHARE })

            archives["desktop"] = zip("unexpected.apk", "wrong-entry".toByteArray())
            val invalid = adapter.load() as CatalogLoadResult.Failure
            assertEquals("distribution_archive_invalid", invalid.reasonCode)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun configAdapter(config: InstallerDistributionConfig): CloudInstallerDistributionConfigAdapter {
        val payload = InstallerDistributionConfigPayload(
            schemaVersion = 2,
            channel = config.channel,
            expiresAt = config.expiresAt.toString(),
            folderUrl = config.folderUrl,
            folderPassword = config.folderPassword,
            previousVersionsUrl = config.previousVersionsUrl,
            previousVersionsPassword = config.previousVersionsPassword,
            components = config.components.map { component ->
                InstallerComponentSourceDocument(
                    componentId = component.componentId,
                    archiveFileName = component.archiveFileName,
                    required = component.required,
                )
            },
        )
        val payloadBytes = CloudReleaseCatalogAdapter.STRICT_JSON.encodeToString(payload).toByteArray()
        val envelope = SignedInstallerConfigEnvelope(
            schemaVersion = 2,
            configVersion = config.configVersion,
            keyId = config.keyId,
            signatureAlgorithm = config.signatureAlgorithm,
            payloadBase64 = Base64.getEncoder().encodeToString(payloadBytes),
            signatureBase64 = Base64.getEncoder().encodeToString(byteArrayOf(1)),
        )
        val body = CloudReleaseCatalogAdapter.STRICT_JSON.encodeToString(envelope).toByteArray()
        return CloudInstallerDistributionConfigAdapter(
            transport = ReleaseCatalogTransport {
                CatalogHttpResponse(200, body, "application/json")
            },
            signatureVerifier = CatalogSignatureVerifier { _, _, _, _ -> true },
            expectedChannel = config.channel,
        )
    }

    private fun config(): InstallerDistributionConfig {
        return InstallerDistributionConfig(
            configVersion = "debug-test-v1",
            channel = "debug",
            expiresAt = Instant.now().plusSeconds(3_600L),
            folderUrl = "https://wwatl.lanzouw.com/b0fqlrcyb",
            folderPassword = "test-password",
            previousVersionsUrl = "",
            previousVersionsPassword = "",
            components = InstallerComponentTrustRegistry.components.map { definition ->
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
            },
            keyId = "test-key",
            signatureAlgorithm = "SHA256withECDSA",
        )
    }

    private fun zip(entryName: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(payload)
            zip.closeEntry()
        }
        output.toByteArray()
    }

    private class FolderHost(
        private val entries: List<LanzouFolderEntry>,
    ) : LanzouFolderWebViewHost {
        override fun startFolder(
            folderUrl: String,
            password: String,
            onEntries: (List<LanzouFolderEntry>) -> Unit,
            onFailure: (com.tcrrry.helper.domain.artifact.ArtifactFailure) -> Unit,
        ) = onEntries(entries)

        override fun stopAndDestroy() = Unit
    }

    private class DownloadHost(
        private val requestFor: (String) -> ResolvedDownloadRequest,
    ) : LanzouWebViewHost {
        override fun start(
            shareUrl: String,
            onDownload: (ResolvedDownloadRequest) -> Unit,
            onFailure: (com.tcrrry.helper.domain.artifact.ArtifactFailure) -> Unit,
        ) = onDownload(requestFor(shareUrl))

        override fun stopAndDestroy() = Unit
    }
}
