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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderArtifactCatalogAdapterTest {
    @Test
    fun `folder source ignores unknown files without blocking declared apps`() = runBlocking {
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

        val success = result as com.tcrrry.helper.data.web.LanzouFolderResolutionResult.Success
        assertEquals(listOf("desktop", "lyrics", "file-manager"), success.artifacts.map { it.component.componentId })
    }

    @Test
    fun `folder source keeps bounded listing size for the selection page`() = runBlocking {
        val config = config()
        val adapter = LanzouFolderSourceAdapter(
            hostFactory = LanzouFolderWebViewHostFactory {
                FolderHost(
                    listOf(
                        LanzouFolderEntry("idesktop", "03desktop-debug.zip", sizeLabel = "2.0 M"),
                    ),
                )
            },
            sourcePolicy = ReleaseSourcePolicy(mode = ReleaseSourceMode.FOLDER_CONFIG),
        )

        val result = adapter.resolve(config) as com.tcrrry.helper.data.web.LanzouFolderResolutionResult.Success

        assertEquals("2.0 MB", result.artifacts.single().component.sizeLabel)
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

        val success = result as com.tcrrry.helper.data.web.LanzouFolderResolutionResult.Success
        val artifacts = success.artifacts
        assertEquals(listOf("desktop"), artifacts.map { it.component.componentId })
        assertEquals(setOf("lyrics", "file-manager"), success.appFailures.map { it.componentId }.toSet())
    }

    @Test
    fun `disabled entries are ignored and newly declared app ids are matched dynamically`() = runBlocking {
        val base = config()
        val dynamic = InstallerComponentSource(
            componentId = "notes",
            archiveFileName = "03notes-debug.zip",
            required = false,
            displayName = "Notes",
            description = "动态应用",
            sortOrder = 40,
        )
        val dynamicConfig = base.copy(
            apps = base.apps.map { app ->
                if (app.componentId == "lyrics") app.copy(enabled = false) else app
            } + dynamic,
        )
        val adapter = LanzouFolderSourceAdapter(
            hostFactory = LanzouFolderWebViewHostFactory {
                FolderHost(
                    listOf(
                        LanzouFolderEntry("idesktop", "03desktop-debug.zip"),
                        LanzouFolderEntry("ilyrics", "03lyrics-debug.zip"),
                        LanzouFolderEntry("ifile", "fossify-file-manager-car-debug.zip"),
                        LanzouFolderEntry("inotes", "03notes-debug.zip"),
                    ),
                )
            },
            sourcePolicy = ReleaseSourcePolicy(mode = ReleaseSourceMode.FOLDER_CONFIG),
        )

        val result = adapter.resolve(dynamicConfig) as com.tcrrry.helper.data.web.LanzouFolderResolutionResult.Success

        assertEquals(listOf("desktop", "file-manager", "notes"), result.artifacts.map { it.component.componentId })
        assertTrue(result.appFailures.none { it.componentId == "lyrics" })
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
        config.apps.forEach { component ->
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
                        FolderHost(config.apps.map { component ->
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
                            val component = config.apps.single { it.componentId == componentId }
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

            archives["lyrics"] = byteArrayOf(0x01, 0x02, 0x03)
            val partial = adapter.load() as CatalogLoadResult.Success
            assertTrue(partial.catalog.manifests.none { it.componentId == "lyrics" })
            assertEquals(
                "distribution_archive_invalid",
                partial.catalog.appFailures.single { it.componentId == "lyrics" }.reasonCode,
            )
            archives["lyrics"] = zip("03lyrics-debug.apk", "restored-lyrics".toByteArray())

            archives["desktop"] = zip("unexpected.apk", "wrong-entry".toByteArray())
            val replaced = adapter.load() as CatalogLoadResult.Success
            assertEquals("unexpected.apk", replaced.catalog.manifests.single { it.componentId == "desktop" }.apkEntryName)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun configAdapter(config: InstallerDistributionConfig): CloudInstallerDistributionConfigAdapter {
        val payload = InstallerDistributionConfigPayload(
            schemaVersion = 3,
            environment = "staging",
            channel = config.channel,
            issuedAtUtc = Instant.now().minusSeconds(60).toString(),
            expiresAt = config.expiresAt.toString(),
            catalogVersion = config.effectiveCatalogVersion(),
            catalogRevision = config.catalogRevision.coerceAtLeast(1L),
            folderUrl = config.folderUrl,
            folderPassword = config.folderPassword,
            previousVersionsUrl = config.previousVersionsUrl,
            previousVersionsPassword = config.previousVersionsPassword,
            apps = config.apps.map { component ->
                InstallerAppSourceDocument(
                    appId = component.componentId,
                    archiveFileName = component.archiveFileName,
                    displayName = component.displayName,
                    description = component.description,
                    enabled = component.enabled,
                    installPolicy = if (component.required) "required" else "optional",
                    sortOrder = component.sortOrder,
                    minClientSchemaVersion = component.minClientSchemaVersion,
                    trustProfileId = component.trustProfileId,
                    deviceSetup = InstallerDeviceSetupDocument(),
                )
            },
        )
        val payloadBytes = CloudReleaseCatalogAdapter.STRICT_JSON.encodeToString(payload).toByteArray()
        val envelope = SignedInstallerConfigEnvelope(
            schemaVersion = 3,
            configVersion = config.configVersion,
            keyId = config.keyId,
            signatureAlgorithm = config.signatureAlgorithm,
            payloadBase64 = Base64.getEncoder().encodeToString(payloadBytes),
            signatureBase64 = Base64.getEncoder().encodeToString(byteArrayOf(1)),
            catalogRevision = config.catalogRevision.coerceAtLeast(1L),
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
            apps = InstallerComponentTrustRegistry.components.map { definition ->
                InstallerComponentSource(
                    componentId = definition.componentId,
                    archiveFileName = definition.archiveFileName,
                    required = definition.required,
                    displayName = definition.displayName,
                    minAndroidSdk = definition.minAndroidSdk,
                    packageName = definition.packageName,
                    certificateSha256 = definition.certificateSha256,
                    apkEntryName = definition.apkEntryName,
                    trustProfileId = definition.trustProfileId,
                )
            },
            keyId = "test-key",
            signatureAlgorithm = "SHA256withECDSA",
            environment = "staging",
            issuedAtUtc = Instant.now().minusSeconds(60),
            catalogVersion = "debug-test-v1",
            catalogRevision = 1L,
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
