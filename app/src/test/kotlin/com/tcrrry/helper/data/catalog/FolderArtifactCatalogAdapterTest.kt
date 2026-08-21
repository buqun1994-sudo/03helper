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
    fun `catalog reads a replacement archive as a new APK version`() = runBlocking {
        val config = config()
        val archives = mutableMapOf<String, ByteArray>()
        val versions = mutableMapOf<String, ArtifactVersion>()
        config.components.forEach { component ->
            archives[component.componentId] = zip(component.componentId, "initial-${component.componentId}".toByteArray())
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
                    ApkMetadata(
                        packageName = config.components.single { it.componentId == componentId }.packageName,
                        version = versions.getValue(componentId),
                        certificateSha256s = setOf(config.components.single { it.componentId == componentId }.certificateSha256),
                    )
                },
                sourcePolicy = sourcePolicy,
                artifactCache = ArtifactCache(tempRoot.resolve("artifacts")),
                workingDirectory = tempRoot.resolve("working"),
            )

            val first = adapter.load() as CatalogLoadResult.Success
            val firstDesktop = first.catalog.manifests.single { it.componentId == "desktop" }
            versions["desktop"] = ArtifactVersion("2.0", 2L)
            archives["desktop"] = zip("desktop", "replacement-desktop".toByteArray())

            val second = adapter.load() as CatalogLoadResult.Success
            val secondDesktop = second.catalog.manifests.single { it.componentId == "desktop" }
            assertEquals(1L, firstDesktop.apkVersion.code)
            assertEquals(2L, secondDesktop.apkVersion.code)
            assertNotEquals(firstDesktop.archiveSha256, secondDesktop.archiveSha256)
            assertTrue(second.catalog.manifests.all { it.sources.single().kind == ArtifactSourceKind.LANZOU_SHARE })
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun configAdapter(config: InstallerDistributionConfig): CloudInstallerDistributionConfigAdapter {
        val payload = InstallerDistributionConfigPayload(
            schemaVersion = 1,
            channel = config.channel,
            expiresAt = config.expiresAt.toString(),
            folderUrl = config.folderUrl,
            folderPassword = config.folderPassword,
            components = config.components.map { component ->
                InstallerComponentSourceDocument(
                    componentId = component.componentId,
                    archiveFileName = component.archiveFileName,
                    required = component.required,
                    displayName = component.displayName,
                    minAndroidSdk = component.minAndroidSdk,
                    packageName = component.packageName,
                    certificateSha256 = component.certificateSha256,
                )
            },
        )
        val payloadBytes = CloudReleaseCatalogAdapter.STRICT_JSON.encodeToString(payload).toByteArray()
        val envelope = SignedInstallerConfigEnvelope(
            schemaVersion = 1,
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
        val certificate = "aa".repeat(32)
        return InstallerDistributionConfig(
            configVersion = "debug-test-v1",
            channel = "debug",
            expiresAt = Instant.now().plusSeconds(3_600L),
            folderUrl = "https://wwatl.lanzouw.com/b0fqlrcyb",
            folderPassword = "test-password",
            components = listOf(
                InstallerComponentSource("desktop", "03desktop-debug.zip", true, "Desktop", 28, "com.example.desktop", certificate),
                InstallerComponentSource("lyrics", "03lyrics-debug.zip", false, "Lyrics", 26, "com.example.lyrics", certificate),
                InstallerComponentSource("file-manager", "fossify-file-manager-car-debug.zip", false, "File Manager", 26, "com.example.filemanager", certificate),
            ),
            keyId = "test-key",
            signatureAlgorithm = "SHA256withECDSA",
        )
    }

    private fun zip(componentId: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("$componentId.apk"))
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
