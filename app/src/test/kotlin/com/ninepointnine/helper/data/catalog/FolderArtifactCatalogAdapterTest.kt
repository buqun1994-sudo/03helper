package com.ninepointnine.helper.data.catalog

import com.ninepointnine.helper.data.artifact.ApkMetadata
import com.ninepointnine.helper.data.artifact.ApkMetadataReader
import com.ninepointnine.helper.data.download.ArtifactCache
import com.ninepointnine.helper.data.download.ArtifactTransport
import com.ninepointnine.helper.data.download.ArtifactTransportResponse
import com.ninepointnine.helper.data.download.DynamicArtifactDownloader
import com.ninepointnine.helper.data.web.LanzouFolderEntry
import com.ninepointnine.helper.data.web.LanzouFolderSourceAdapter
import com.ninepointnine.helper.data.web.LanzouFolderWebViewHost
import com.ninepointnine.helper.data.web.LanzouFolderWebViewHostFactory
import com.ninepointnine.helper.data.web.LanzouWebSourceAdapter
import com.ninepointnine.helper.data.web.LanzouWebViewHost
import com.ninepointnine.helper.data.web.LanzouWebViewHostFactory
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.InstallerComponentTrustRegistry
import com.ninepointnine.helper.domain.artifact.ReleaseSourceMode
import com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy
import com.ninepointnine.helper.domain.artifact.ResolvedDownloadRequest
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
import java.util.concurrent.atomic.AtomicInteger

class FolderArtifactCatalogAdapterTest {
    @Test
    fun `control plane configuration load does not enumerate the folder or touch artifact preparation`() = runBlocking {
        val config = config()
        val folderCalls = AtomicInteger(0)
        val root = java.nio.file.Files.createTempDirectory("03helper-control-plane").toFile()
        try {
            val sourcePolicy = ReleaseSourcePolicy(mode = ReleaseSourceMode.FOLDER_CONFIG)
            val adapter = FolderArtifactCatalogAdapter(
                configAdapter = configAdapter(config),
                folderSourceAdapter = LanzouFolderSourceAdapter(
                    hostFactory = LanzouFolderWebViewHostFactory {
                        object : LanzouFolderWebViewHost {
                            override fun startFolder(
                                folderUrl: String,
                                password: String,
                                expectedArchiveFileNames: Set<String>,
                                onEntries: (List<LanzouFolderEntry>) -> Unit,
                                onFailure: (com.ninepointnine.helper.domain.artifact.ArtifactFailure) -> Unit,
                            ) {
                                folderCalls.incrementAndGet()
                                error("folder_must_not_be_opened")
                            }

                            override fun stopAndDestroy() = Unit
                        }
                    },
                    sourcePolicy = sourcePolicy,
                ),
                lanzouSourceAdapter = LanzouWebSourceAdapter(
                    hostFactory = LanzouWebViewHostFactory {
                        DownloadHost { error("share_must_not_be_opened") }
                    },
                    sourcePolicy = sourcePolicy,
                ),
                downloader = DynamicArtifactDownloader(
                    transport = ArtifactTransport { _, _ -> error("download_must_not_run") },
                    sourcePolicy = sourcePolicy,
                ),
                metadataReader = ApkMetadataReader { error("apk_must_not_be_read") },
                sourcePolicy = sourcePolicy,
                artifactCache = ArtifactCache(root.resolve("artifacts")),
                workingDirectory = root.resolve("working"),
            )

            val result = adapter.loadConfiguration()

            assertTrue(result is DistributionConfigLoadResult.Success)
            assertEquals(0, folderCalls.get())
        } finally {
            root.deleteRecursively()
        }
    }

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

        val success = result as com.ninepointnine.helper.data.web.LanzouFolderResolutionResult.Success
        assertEquals(listOf("desktop", "lyrics", "file-manager"), success.artifacts.map { it.component.componentId })
    }

    @Test
    fun `folder source keeps Cloud APK size instead of ZIP listing size`() = runBlocking {
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

        val result = adapter.resolve(config) as com.ninepointnine.helper.data.web.LanzouFolderResolutionResult.Success

        assertEquals(config.apps.first { it.componentId == "desktop" }.displaySizeLabel,
            result.artifacts.single().component.displaySizeLabel)
    }

    @Test
    fun `folder source allows missing optional archives and records them`() = runBlocking {
        val config = config()
        val adapter = LanzouFolderSourceAdapter(
            hostFactory = LanzouFolderWebViewHostFactory {
                FolderHost(listOf(LanzouFolderEntry("idesktop", "03desktop-debug.zip")))
            },
            sourcePolicy = ReleaseSourcePolicy(mode = ReleaseSourceMode.FOLDER_CONFIG),
        )

        val result = adapter.resolve(config)

        val success = result as com.ninepointnine.helper.data.web.LanzouFolderResolutionResult.Success
        val artifacts = success.artifacts
        assertEquals(listOf("desktop"), artifacts.map { it.component.componentId })
        assertEquals(setOf("lyrics", "file-manager", "cast"), success.appFailures.map { it.componentId }.toSet())
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
            versionCode = 1L,
            versionName = "1.0",
            apkSizeBytes = 1L,
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

        val result = adapter.resolve(dynamicConfig) as com.ninepointnine.helper.data.web.LanzouFolderResolutionResult.Success

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
                        expectedArchiveFileNames: Set<String>,
                        onEntries: (List<LanzouFolderEntry>) -> Unit,
                        onFailure: (com.ninepointnine.helper.domain.artifact.ArtifactFailure) -> Unit,
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

        assertTrue(result is com.ninepointnine.helper.data.web.LanzouFolderResolutionResult.Success)
        assertEquals("", observedPassword)
    }

    @Test
    fun `folder source records a missing desktop archive for later local reuse`() = runBlocking {
        val config = config()
        val adapter = LanzouFolderSourceAdapter(
            hostFactory = LanzouFolderWebViewHostFactory {
                FolderHost(listOf(LanzouFolderEntry("ilyrics", "03lyrics-debug.zip")))
            },
            sourcePolicy = ReleaseSourcePolicy(mode = ReleaseSourceMode.FOLDER_CONFIG),
        )

        val result = adapter.resolve(config)

        val success = result as com.ninepointnine.helper.data.web.LanzouFolderResolutionResult.Success
        assertTrue(success.artifacts.none { it.component.componentId == "desktop" })
        assertEquals(
            "lanzou_folder_missing_desktop",
            success.appFailures.single { it.componentId == "desktop" }.reasonCode,
        )
    }

    @Test
    fun `folder source records duplicate names case insensitively`() = runBlocking {
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

        val success = result as com.ninepointnine.helper.data.web.LanzouFolderResolutionResult.Success
        assertTrue(success.artifacts.isEmpty())
        assertEquals(
            "lanzou_folder_duplicate_file",
            success.appFailures.single { it.componentId == "desktop" }.reasonCode,
        )
    }

    @Test
    fun `catalog reads a replacement archive without using Cloud size as an APK gate`() = runBlocking {
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

            val firstResult = adapter.load()
            val first = firstResult as CatalogLoadResult.Success
            assertTrue(
                tempRoot.resolve("artifacts").listFiles().orEmpty().any {
                    it.name.startsWith("03helper-desktop-") && it.extension == "apk"
                },
            )
            val firstDesktop = first.catalog.manifests.single { it.componentId == "desktop" }
            versions["desktop"] = ArtifactVersion("1.0", 1L)
            archives["desktop"] = zip("03desktop-debug.apk", "updated-desktop".toByteArray())

            val second = adapter.load() as CatalogLoadResult.Success
            val secondDesktop = second.catalog.manifests.single { it.componentId == "desktop" }
            assertEquals(1L, firstDesktop.apkVersion.code)
            assertEquals(1L, secondDesktop.apkVersion.code)
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

            // A verified local APK may be reused on a later refresh; the
            // replacement archive is therefore not selected until its cache
            // entry is explicitly invalidated.
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `selected preparation reuses a verified public Download APK before remote ZIP`() = runBlocking {
        val config = config()
        val root = java.nio.file.Files.createTempDirectory("03helper-local-download").toFile()
        try {
            val publicDownload = root.resolve("Download").apply { mkdirs() }
            val desktop = config.apps.single { it.componentId == "desktop" }
            val localApk = publicDownload.resolve("manual-desktop.apk").apply {
                writeBytes("initial-desktop".toByteArray())
            }
            val remoteAttempts = AtomicInteger(0)
            val sourcePolicy = ReleaseSourcePolicy(mode = ReleaseSourceMode.FOLDER_CONFIG)
            val adapter = FolderArtifactCatalogAdapter(
                configAdapter = configAdapter(config),
                folderSourceAdapter = LanzouFolderSourceAdapter(
                    hostFactory = LanzouFolderWebViewHostFactory {
                        FolderHost(emptyList())
                    },
                    sourcePolicy = sourcePolicy,
                ),
                lanzouSourceAdapter = LanzouWebSourceAdapter(
                    hostFactory = LanzouWebViewHostFactory {
                        DownloadHost {
                            remoteAttempts.incrementAndGet()
                            ResolvedDownloadRequest(
                                sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                                url = "https://zip1.webgetstore.com/desktop",
                            )
                        }
                    },
                    sourcePolicy = sourcePolicy,
                ),
                downloader = DynamicArtifactDownloader(
                    transport = ArtifactTransport { _, _ ->
                        error("remote_download_should_not_run")
                    },
                    sourcePolicy = sourcePolicy,
                ),
                metadataReader = ApkMetadataReader {
                    ApkMetadata(
                        packageName = desktop.packageName,
                        // The local APK metadata is intentionally newer than
                        // the Cloud display fields. Identity, not the stale
                        // config version, decides whether it can be reused.
                        version = ArtifactVersion("9.9.9", 99L),
                        certificateSha256s = setOf(desktop.certificateSha256),
                    )
                },
                sourcePolicy = sourcePolicy,
                artifactCache = ArtifactCache(root.resolve("cache"), publicDownload),
                workingDirectory = root.resolve("working"),
            )

            val resultValue = adapter.prepareSelected(setOf("desktop"))
            val result = resultValue as CatalogLoadResult.Success
            val manifest = result.catalog.manifests.single()

            assertTrue(manifest.localOnly)
            assertEquals(ArtifactSourceKind.LOCAL_DOWNLOAD, manifest.sources.single().kind)
            assertEquals(99L, manifest.apkVersion.code)
            assertEquals(0, remoteAttempts.get())
            assertTrue(localApk.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `selected preparation skips installed component before local or remote artifact work`() = runBlocking {
        val config = config()
        val lyrics = config.apps.single { it.componentId == "lyrics" }
        val root = java.nio.file.Files.createTempDirectory("03helper-skipped-component").toFile()
        val remoteAttempts = AtomicInteger(0)
        val observedFolderEntries = mutableListOf<String>()
        try {
            val sourcePolicy = ReleaseSourcePolicy(mode = ReleaseSourceMode.FOLDER_CONFIG)
            val adapter = FolderArtifactCatalogAdapter(
                configAdapter = configAdapter(config),
                folderSourceAdapter = LanzouFolderSourceAdapter(
                    hostFactory = LanzouFolderWebViewHostFactory {
                        object : LanzouFolderWebViewHost {
                            override fun startFolder(
                                folderUrl: String,
                                password: String,
                                expectedArchiveFileNames: Set<String>,
                                onEntries: (List<LanzouFolderEntry>) -> Unit,
                                onFailure: (com.ninepointnine.helper.domain.artifact.ArtifactFailure) -> Unit,
                            ) {
                                observedFolderEntries += lyrics.archiveFileName
                                onEntries(listOf(LanzouFolderEntry("ilyrics", lyrics.archiveFileName)))
                            }

                            override fun stopAndDestroy() = Unit
                        }
                    },
                    sourcePolicy = sourcePolicy,
                ),
                lanzouSourceAdapter = LanzouWebSourceAdapter(
                    hostFactory = LanzouWebViewHostFactory {
                        DownloadHost {
                            remoteAttempts.incrementAndGet()
                            ResolvedDownloadRequest(
                                sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                                url = "https://zip1.webgetstore.com/lyrics",
                                userAgent = "03helper-test",
                            )
                        }
                    },
                    sourcePolicy = sourcePolicy,
                ),
                downloader = DynamicArtifactDownloader(
                    transport = ArtifactTransport { _, _ ->
                        val bytes = zip(lyrics.apkEntryName, "lyrics-apk".toByteArray())
                        ArtifactTransportResponse(
                            statusCode = 200,
                            contentLength = bytes.size.toLong(),
                            contentType = "application/zip",
                            body = ByteArrayInputStream(bytes),
                        )
                    },
                    sourcePolicy = sourcePolicy,
                ),
                metadataReader = ApkMetadataReader {
                    ApkMetadata(
                        packageName = lyrics.packageName,
                        version = ArtifactVersion(lyrics.versionName, lyrics.versionCode),
                        certificateSha256s = setOf(lyrics.certificateSha256),
                    )
                },
                sourcePolicy = sourcePolicy,
                artifactCache = ArtifactCache(root.resolve("cache"), root.resolve("Download")),
                workingDirectory = root.resolve("working"),
            )

            val result = adapter.prepareSelected(
                selectedIds = setOf("desktop", "lyrics"),
                skippedIds = setOf("desktop"),
            ) as CatalogLoadResult.Success

            assertEquals(
                "failures=${result.catalog.appFailures}, folder=$observedFolderEntries, remote=${remoteAttempts.get()}",
                listOf("lyrics"),
                result.catalog.manifests.map { it.componentId },
            )
            assertEquals(1, remoteAttempts.get())
            assertEquals(listOf(lyrics.archiveFileName), observedFolderEntries)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `stale public Download identity is ignored and the declared remote archive is used`() = runBlocking {
        val config = config()
        val root = java.nio.file.Files.createTempDirectory("03helper-stale-download").toFile()
        try {
            val publicDownload = root.resolve("Download").apply { mkdirs() }
            val desktop = config.apps.single { it.componentId == "desktop" }
            val lyrics = config.apps.single { it.componentId == "lyrics" }
            val staleLyrics = publicDownload.resolve("old-lyrics.apk").apply {
                writeBytes("stale-lyrics".toByteArray())
            }
            publicDownload.resolve("manual-desktop.apk").writeBytes("desktop".toByteArray())
            val remoteAttempts = AtomicInteger(0)
            val sourcePolicy = ReleaseSourcePolicy(mode = ReleaseSourceMode.FOLDER_CONFIG)
            val adapter = FolderArtifactCatalogAdapter(
                configAdapter = configAdapter(config),
                folderSourceAdapter = LanzouFolderSourceAdapter(
                    hostFactory = LanzouFolderWebViewHostFactory {
                        FolderHost(listOf(LanzouFolderEntry("ilyrics", lyrics.archiveFileName)))
                    },
                    sourcePolicy = sourcePolicy,
                ),
                lanzouSourceAdapter = LanzouWebSourceAdapter(
                    hostFactory = LanzouWebViewHostFactory {
                        DownloadHost {
                            remoteAttempts.incrementAndGet()
                            ResolvedDownloadRequest(
                                sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                                url = "https://zip1.webgetstore.com/lyrics",
                                userAgent = "03helper-test",
                            )
                        }
                    },
                    sourcePolicy = sourcePolicy,
                ),
                downloader = DynamicArtifactDownloader(
                    transport = ArtifactTransport { _, _ ->
                        val bytes = zip(lyrics.apkEntryName, "remote-lyrics".toByteArray())
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
                    when (apk.name) {
                        staleLyrics.name -> ApkMetadata(
                            packageName = "com.tcrrry.desktoplyrics",
                            version = ArtifactVersion("1.14-icar03", 114L),
                            certificateSha256s = setOf("2990047fddf6d6ec1eb7f83731fcc1398616e5fb83aec97542a4f132c35a1a27"),
                        )

                        "manual-desktop.apk" -> ApkMetadata(
                            packageName = desktop.packageName,
                            version = ArtifactVersion(desktop.versionName, desktop.versionCode),
                            certificateSha256s = setOf(desktop.certificateSha256),
                        )

                        else -> ApkMetadata(
                            packageName = lyrics.packageName,
                            version = ArtifactVersion(lyrics.versionName, lyrics.versionCode),
                            certificateSha256s = setOf(lyrics.certificateSha256),
                        )
                    }
                },
                sourcePolicy = sourcePolicy,
                artifactCache = ArtifactCache(root.resolve("cache"), publicDownload),
                workingDirectory = root.resolve("working"),
            )

            val result = adapter.prepareSelected(setOf("desktop", "lyrics")) as CatalogLoadResult.Success
            val lyricsManifest = result.catalog.manifests.single { it.componentId == "lyrics" }

            assertEquals(1, remoteAttempts.get())
            assertFalse(lyricsManifest.localOnly)
            assertEquals(ArtifactSourceKind.LANZOU_SHARE, lyricsManifest.sources.single().kind)
            assertTrue(staleLyrics.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a later installation batch resolves a fresh folder snapshot`() = runBlocking {
        val config = config()
        val desktop = config.apps.single { it.componentId == "desktop" }
        val lyrics = config.apps.single { it.componentId == "lyrics" }
        val cast = config.apps.single { it.componentId == "cast" }
        val folderSnapshots = listOf(
            listOf(
                LanzouFolderEntry("idesktop", desktop.archiveFileName),
                LanzouFolderEntry("ilyrics", lyrics.archiveFileName),
            ),
            listOf(
                LanzouFolderEntry("idesktop", desktop.archiveFileName),
                LanzouFolderEntry("ilyrics", lyrics.archiveFileName),
                LanzouFolderEntry("icast", cast.archiveFileName),
            ),
        )
        val folderCalls = AtomicInteger(0)
        val root = java.nio.file.Files.createTempDirectory("03helper-fresh-folder-batch").toFile()
        try {
            val sourcePolicy = ReleaseSourcePolicy(mode = ReleaseSourceMode.FOLDER_CONFIG)
            val adapter = FolderArtifactCatalogAdapter(
                configAdapter = configAdapter(config),
                folderSourceAdapter = LanzouFolderSourceAdapter(
                    hostFactory = LanzouFolderWebViewHostFactory {
                        val index = folderCalls.getAndIncrement().coerceAtMost(folderSnapshots.lastIndex)
                        FolderHost(folderSnapshots[index])
                    },
                    sourcePolicy = sourcePolicy,
                ),
                lanzouSourceAdapter = LanzouWebSourceAdapter(
                    hostFactory = LanzouWebViewHostFactory {
                        DownloadHost { shareUrl ->
                            ResolvedDownloadRequest(
                                sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                                url = "https://zip1.webgetstore.com/${shareUrl.substringAfterLast('/')}",
                                userAgent = "03helper-test",
                            )
                        }
                    },
                    sourcePolicy = sourcePolicy,
                ),
                downloader = DynamicArtifactDownloader(
                    transport = ArtifactTransport { request, _ ->
                        val component = when (request.url.substringAfterLast('/')) {
                            "idesktop" -> desktop
                            "ilyrics" -> lyrics
                            "icast" -> cast
                            else -> error("unexpected_share")
                        }
                        val bytes = zip(component.apkEntryName, component.componentId.toByteArray())
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
                    val componentId = apk.readText(Charsets.UTF_8)
                    val component = config.apps.single { it.componentId == componentId }
                    ApkMetadata(
                        packageName = component.packageName,
                        version = ArtifactVersion(component.versionName, component.versionCode),
                        certificateSha256s = setOf(component.certificateSha256),
                    )
                },
                sourcePolicy = sourcePolicy,
                artifactCache = ArtifactCache(root.resolve("cache"), root.resolve("Download")),
                workingDirectory = root.resolve("working"),
            )

            assertTrue(adapter.loadSelection() is CatalogLoadResult.Success)
            val first = adapter.prepareSelected(setOf("desktop", "lyrics")) as CatalogLoadResult.Success
            val second = adapter.prepareSelected(
                selectedIds = setOf("desktop", "cast"),
                skippedIds = setOf("desktop"),
            ) as CatalogLoadResult.Success

            assertEquals(setOf("desktop", "lyrics"), first.catalog.manifests.map { it.componentId }.toSet())
            assertEquals(
                "failures=${second.catalog.appFailures}",
                listOf("cast"),
                second.catalog.manifests.map { it.componentId },
            )
            assertEquals(2, folderCalls.get())
        } finally {
            root.deleteRecursively()
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
                    versionCode = component.versionCode,
                    versionName = component.versionName,
                    apkSizeBytes = component.apkSizeBytes,
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
        val value = InstallerDistributionConfig(
            configVersion = "debug-test-v1",
            channel = "debug",
            expiresAt = Instant.now().plusSeconds(3_600L),
            folderUrl = "https://wwatl.lanzouw.com/b0fqlrcyb",
            folderPassword = "test-password",
            previousVersionsUrl = "",
            previousVersionsPassword = "",
            apps = InstallerComponentTrustRegistry.components.map { definition ->
                val stagingPackage = when (definition.componentId) {
                    "desktop" -> "com.ninepointnine.desktop"
                    "lyrics" -> "com.ninepointnine.desktoplyrics"
                    "file-manager" -> definition.packageName
                    else -> definition.packageName
                }
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
                    packageName = stagingPackage,
                    certificateSha256 = stagingCertificate,
                    apkEntryName = definition.apkEntryName,
                    trustProfileId = definition.trustProfileId,
                    versionCode = 1L,
                    versionName = "1.0",
                    apkSizeBytes = "initial-${definition.componentId}".toByteArray().size.toLong(),
                )
            },
            keyId = "test-key",
            signatureAlgorithm = "SHA256withECDSA",
            environment = "staging",
            issuedAtUtc = Instant.now().minusSeconds(60),
            catalogVersion = "debug-test-v1",
            catalogRevision = 1L,
        )
        return value
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
            expectedArchiveFileNames: Set<String>,
            onEntries: (List<LanzouFolderEntry>) -> Unit,
            onFailure: (com.ninepointnine.helper.domain.artifact.ArtifactFailure) -> Unit,
        ) = onEntries(entries)

        override fun stopAndDestroy() = Unit
    }

    private class DownloadHost(
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
