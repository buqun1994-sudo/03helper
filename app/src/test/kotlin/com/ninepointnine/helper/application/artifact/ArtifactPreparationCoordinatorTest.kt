package com.ninepointnine.helper.application.artifact

import com.ninepointnine.helper.data.artifact.ArchiveIdentityVerifier
import com.ninepointnine.helper.data.artifact.ArtifactArchiveExtractor
import com.ninepointnine.helper.data.artifact.ArtifactIdentityVerifier
import com.ninepointnine.helper.data.artifact.ApkMetadata
import com.ninepointnine.helper.data.download.ArtifactCache
import com.ninepointnine.helper.data.download.ArtifactDownloader
import com.ninepointnine.helper.data.download.ArtifactTransport
import com.ninepointnine.helper.data.download.ArtifactTransportResponse
import com.ninepointnine.helper.data.web.LanzouWebSourceAdapter
import com.ninepointnine.helper.data.web.LanzouWebViewHost
import com.ninepointnine.helper.data.web.LanzouWebViewHostFactory
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactSource
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.CompatibilityRange
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactPreparationCoordinatorTest {
    @Test
    fun `complete cached archive is verified without resolving or downloading again`() = runBlocking {
        val apkBytes = byteArrayOf(5, 6, 7, 8)
        val zipBytes = zipBytes("lyrics.apk", apkBytes)
        val manifest = manifest(zipBytes, apkBytes)
        val root = Files.createTempDirectory("artifact-coordinator-cache").toFile()
        val cache = ArtifactCache(root)
        val paths = cache.paths(manifest)
        paths.archivePart.writeBytes(zipBytes)
        cache.writeResumeMetadata(manifest, ArtifactSourceKind.LANZOU_SHARE.wireName)
        var resolveCalls = 0
        var downloadCalls = 0
        val host = object : LanzouWebViewHost {
            override fun start(
                shareUrl: String,
                onDownload: (com.ninepointnine.helper.domain.artifact.ResolvedDownloadRequest) -> Unit,
                onFailure: (com.ninepointnine.helper.domain.artifact.ArtifactFailure) -> Unit,
            ) {
                resolveCalls += 1
                error("cached_source_should_not_resolve")
            }

            override fun stopAndDestroy() = Unit
        }
        val coordinator = ArtifactPreparationCoordinator(
            sourcePolicy = com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy(),
            lanzouSourceAdapter = LanzouWebSourceAdapter(LanzouWebViewHostFactory { host }),
            downloader = ArtifactDownloader(
                transport = ArtifactTransport { _, _ ->
                    downloadCalls += 1
                    error("cached_archive_should_not_download")
                },
                cache = cache,
            ),
            archiveVerifier = ArchiveIdentityVerifier(),
            archiveExtractor = ArtifactArchiveExtractor { Long.MAX_VALUE },
            identityVerifier = ArtifactIdentityVerifier {
                ApkMetadata(
                    packageName = manifest.packageName,
                    version = manifest.apkVersion,
                    certificateSha256s = setOf(manifest.certificateSha256),
                )
            },
            cache = cache,
            eventPort = ArtifactSessionEventPort { },
        )

        val result = coordinator.prepare(listOf(manifest))

        assertTrue(result is ArtifactPreparationResult.Prepared)
        assertEquals(0, resolveCalls)
        assertEquals(0, downloadCalls)
        assertFalse(paths.archivePart.exists())
        assertTrue(paths.apk.exists())
        root.deleteRecursively()
        Unit
    }

    @Test
    fun `published local APK still emits strict archive proof for a remote manifest`() = runBlocking {
        val apkBytes = byteArrayOf(9, 8, 7, 6)
        val zipBytes = zipBytes("lyrics.apk", apkBytes)
        val manifest = manifest(zipBytes, apkBytes)
        val privateRoot = Files.createTempDirectory("artifact-coordinator-local-private").toFile()
        val publicRoot = Files.createTempDirectory("artifact-coordinator-local-public").toFile()
        try {
            publicRoot.resolve("manual-name.apk").writeBytes(apkBytes)
            val events = mutableListOf<InstallationSessionEvent>()
            val host = object : LanzouWebViewHost {
                override fun start(
                    shareUrl: String,
                    onDownload: (com.ninepointnine.helper.domain.artifact.ResolvedDownloadRequest) -> Unit,
                    onFailure: (com.ninepointnine.helper.domain.artifact.ArtifactFailure) -> Unit,
                ) = error("local_candidate_should_not_resolve")

                override fun stopAndDestroy() = Unit
            }
            val cache = ArtifactCache(privateRoot, publicRoot)
            val coordinator = ArtifactPreparationCoordinator(
                sourcePolicy = com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy(),
                lanzouSourceAdapter = LanzouWebSourceAdapter(LanzouWebViewHostFactory { host }),
                downloader = ArtifactDownloader(
                    transport = ArtifactTransport { _, _ -> error("local_candidate_should_not_download") },
                    cache = cache,
                ),
                archiveVerifier = ArchiveIdentityVerifier(),
                archiveExtractor = ArtifactArchiveExtractor { Long.MAX_VALUE },
                identityVerifier = ArtifactIdentityVerifier {
                    ApkMetadata(
                        packageName = manifest.packageName,
                        version = manifest.apkVersion,
                        certificateSha256s = setOf(manifest.certificateSha256),
                    )
                },
                cache = cache,
                eventPort = ArtifactSessionEventPort { events += it },
            )

            val result = coordinator.prepare(listOf(manifest))

            assertTrue(result is ArtifactPreparationResult.Prepared)
            assertEquals(1, events.filterIsInstance<InstallationSessionEvent.ArchiveDownloaded>().single().archives.size)
            assertEquals(1, events.filterIsInstance<InstallationSessionEvent.ArchiveVerified>().single().verifications.size)
            assertEquals(1, events.filterIsInstance<InstallationSessionEvent.ApkExtracted>().single().extractions.size)
        } finally {
            privateRoot.deleteRecursively()
            publicRoot.deleteRecursively()
        }
    }

    @Test
    fun `fixed fallback produces one session evidence batch and cleans zip`() = runBlocking {
        val apkBytes = byteArrayOf(1, 2, 3, 4)
        val zipBytes = zipBytes("lyrics.apk", apkBytes)
        val manifest = manifest(zipBytes, apkBytes)
        val root = Files.createTempDirectory("artifact-coordinator").toFile()
        val cache = ArtifactCache(root)
        val events = mutableListOf<InstallationSessionEvent>()
        val lanzouHost = object : LanzouWebViewHost {
            override fun start(
                shareUrl: String,
                onDownload: (com.ninepointnine.helper.domain.artifact.ResolvedDownloadRequest) -> Unit,
                onFailure: (com.ninepointnine.helper.domain.artifact.ArtifactFailure) -> Unit,
            ) {
                onFailure(
                    com.ninepointnine.helper.domain.artifact.ArtifactFailure(
                        phase = com.ninepointnine.helper.domain.artifact.ArtifactFailurePhase.SOURCE_RESOLUTION,
                        sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                        reasonCode = "lanzou_parse_timeout",
                        retryable = true,
                    ),
                )
            }

            override fun stopAndDestroy() = Unit
        }
        val downloader = ArtifactDownloader(
            transport = ArtifactTransport { request, _ ->
                if (request.sourceKind == ArtifactSourceKind.R2) {
                    ArtifactTransportResponse(
                        statusCode = 200,
                        contentLength = 20,
                        contentType = "text/html",
                        body = ByteArrayInputStream("blocked".toByteArray()),
                    )
                } else {
                    ArtifactTransportResponse(
                        statusCode = 200,
                        contentLength = zipBytes.size.toLong(),
                        contentType = "application/zip",
                        body = ByteArrayInputStream(zipBytes),
                    )
                }
            },
            cache = cache,
        )
        val coordinator = ArtifactPreparationCoordinator(
            sourcePolicy = com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy(),
            lanzouSourceAdapter = LanzouWebSourceAdapter(LanzouWebViewHostFactory { lanzouHost }),
            downloader = downloader,
            archiveVerifier = ArchiveIdentityVerifier(),
            archiveExtractor = ArtifactArchiveExtractor { Long.MAX_VALUE },
            identityVerifier = ArtifactIdentityVerifier {
                ApkMetadata(
                    packageName = manifest.packageName,
                    version = manifest.apkVersion,
                    certificateSha256s = setOf(manifest.certificateSha256),
                )
            },
            cache = cache,
            eventPort = ArtifactSessionEventPort { events += it },
        )

        val result = coordinator.prepare(listOf(manifest))
        assertTrue(result is ArtifactPreparationResult.Prepared)
        assertEquals(2, events.filterIsInstance<InstallationSessionEvent.SourceFailed>().size)
        assertEquals(ArtifactSourceKind.LANZOU_SHARE, events.filterIsInstance<InstallationSessionEvent.SourceFailed>()[0].sourceKind)
        assertEquals(ArtifactSourceKind.R2, events.filterIsInstance<InstallationSessionEvent.SourceFailed>()[1].sourceKind)
        assertTrue(events.last() is InstallationSessionEvent.ArtifactsVerified)
        assertTrue((events.last() as InstallationSessionEvent.ArtifactsVerified).archiveDeleted)
        val paths = cache.paths(manifest)
        assertFalse(paths.archivePart.exists())
        assertTrue(paths.apk.exists())
    }

    private fun manifest(zipBytes: ByteArray, apkBytes: ByteArray): ArtifactManifest = ArtifactManifest(
        schemaVersion = 1,
        componentId = "lyrics",
        displayName = "Lyrics",
        required = true,
        version = ArtifactVersion("1.0.0", 1),
        compatibility = CompatibilityRange(26),
        archiveFileName = "lyrics.zip",
        archiveSizeBytes = zipBytes.size.toLong(),
        archiveSha256 = sha256(zipBytes),
        apkEntryName = "lyrics.apk",
        apkSizeBytes = apkBytes.size.toLong(),
        apkSha256 = sha256(apkBytes),
        packageName = "com.example.lyrics",
        apkVersion = ArtifactVersion("1.0.0", 7),
        certificateSha256 = "aa".repeat(32),
        sources = listOf(
            ArtifactSource(ArtifactSourceKind.LANZOU_SHARE, "https://wwatl.lanzouw.com/iabc123"),
            ArtifactSource(ArtifactSourceKind.R2, "https://assets.r2.dev/lyrics.zip"),
            ArtifactSource(ArtifactSourceKind.GITHUB_RELEASES, "https://github.com/a/b/releases/download/v1/lyrics.zip"),
        ),
    )

    private fun zipBytes(name: String, bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
