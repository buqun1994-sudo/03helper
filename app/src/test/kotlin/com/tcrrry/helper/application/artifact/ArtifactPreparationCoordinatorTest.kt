package com.tcrrry.helper.application.artifact

import com.tcrrry.helper.data.artifact.ArchiveIdentityVerifier
import com.tcrrry.helper.data.artifact.ArtifactArchiveExtractor
import com.tcrrry.helper.data.artifact.ArtifactIdentityVerifier
import com.tcrrry.helper.data.artifact.ApkMetadata
import com.tcrrry.helper.data.download.ArtifactCache
import com.tcrrry.helper.data.download.ArtifactDownloader
import com.tcrrry.helper.data.download.ArtifactTransport
import com.tcrrry.helper.data.download.ArtifactTransportResponse
import com.tcrrry.helper.data.web.LanzouWebSourceAdapter
import com.tcrrry.helper.data.web.LanzouWebViewHost
import com.tcrrry.helper.data.web.LanzouWebViewHostFactory
import com.tcrrry.helper.domain.artifact.ArtifactManifest
import com.tcrrry.helper.domain.artifact.ArtifactSource
import com.tcrrry.helper.domain.artifact.ArtifactSourceKind
import com.tcrrry.helper.domain.artifact.ArtifactVersion
import com.tcrrry.helper.domain.artifact.CompatibilityRange
import com.tcrrry.helper.domain.session.InstallationSessionEvent
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
                onDownload: (com.tcrrry.helper.domain.artifact.ResolvedDownloadRequest) -> Unit,
                onFailure: (com.tcrrry.helper.domain.artifact.ArtifactFailure) -> Unit,
            ) {
                onFailure(
                    com.tcrrry.helper.domain.artifact.ArtifactFailure(
                        phase = com.tcrrry.helper.domain.artifact.ArtifactFailurePhase.SOURCE_RESOLUTION,
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
            sourcePolicy = com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy(),
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
