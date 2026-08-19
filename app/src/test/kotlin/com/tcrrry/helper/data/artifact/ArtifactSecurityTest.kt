package com.tcrrry.helper.data.artifact

import com.tcrrry.helper.data.download.ArtifactCache
import com.tcrrry.helper.data.download.ArtifactDownloader
import com.tcrrry.helper.data.download.ArtifactDownloadResult
import com.tcrrry.helper.data.download.ArtifactTransport
import com.tcrrry.helper.data.download.ArtifactTransportResponse
import com.tcrrry.helper.data.web.LanzouResolutionResult
import com.tcrrry.helper.data.web.LanzouWebSourceAdapter
import com.tcrrry.helper.data.web.LanzouWebViewHost
import com.tcrrry.helper.data.web.LanzouWebViewHostFactory
import com.tcrrry.helper.domain.artifact.ArtifactManifest
import com.tcrrry.helper.domain.artifact.ArtifactManifestValidator
import com.tcrrry.helper.domain.artifact.ArtifactSource
import com.tcrrry.helper.domain.artifact.ArtifactSourceKind
import com.tcrrry.helper.domain.artifact.ArtifactVersion
import com.tcrrry.helper.domain.artifact.CompatibilityRange
import com.tcrrry.helper.domain.artifact.ResolvedDownloadRequest
import com.tcrrry.helper.domain.artifact.SourcePlan
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactSecurityTest {
    @Test
    fun `manifest validation rejects missing digest and unsafe names`() {
        val valid = manifestFor(byteArrayOf(1), byteArrayOf(2))
        assertTrue(ArtifactManifestValidator.validate(valid) is com.tcrrry.helper.domain.artifact.ManifestValidation.Valid)
        assertEquals(
            "archive_sha256_invalid",
            (ArtifactManifestValidator.validate(valid.copy(archiveSha256 = "")) as com.tcrrry.helper.domain.artifact.ManifestValidation.Invalid).reasonCode,
        )
        assertEquals(
            "apk_entry_name_invalid",
            (ArtifactManifestValidator.validate(valid.copy(apkEntryName = "../app.apk")) as com.tcrrry.helper.domain.artifact.ManifestValidation.Invalid).reasonCode,
        )
    }

    @Test
    fun `source policy fixes order and recognizes only bounded Lanzou transient download hosts`() {
        val manifest = manifestFor(byteArrayOf(1), byteArrayOf(2)).copy(
            sources = listOf(
                ArtifactSource(ArtifactSourceKind.GITHUB_RELEASES, "https://github.com/a/b/releases/download/v1/a.zip"),
                ArtifactSource(ArtifactSourceKind.LANZOU_SHARE, "https://wwatl.lanzouw.com/iabc123"),
                ArtifactSource(ArtifactSourceKind.R2, "https://assets.r2.dev/a.zip"),
            ),
        )
        val plan = com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy().plan(manifest)
        assertTrue(plan is SourcePlan.Accepted)
        assertEquals(
            listOf(ArtifactSourceKind.LANZOU_SHARE, ArtifactSourceKind.R2, ArtifactSourceKind.GITHUB_RELEASES),
            (plan as SourcePlan.Accepted).sources.map { it.kind },
        )
        val folder = manifest.copy(
            sources = manifest.sources.map {
                if (it.kind == ArtifactSourceKind.LANZOU_SHARE) {
                    it.copy(url = "https://wwatl.lanzouw.com/folder-password")
                } else it
            },
        )
        assertEquals(
            "lanzou_share_not_single_file",
            (com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy().plan(folder) as SourcePlan.Rejected).reasonCode,
        )
        val converter = manifest.copy(
            sources = manifest.sources.map {
                if (it.kind == ArtifactSourceKind.LANZOU_SHARE) {
                    it.copy(url = "https://example.com/convert?url=https://wwatl.lanzouw.com/iabc123")
                } else it
            },
        )
        assertTrue(com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy().plan(converter) is SourcePlan.Rejected)

        val policy = com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy()
        val transientLanzouRequest = ResolvedDownloadRequest(
            sourceKind = ArtifactSourceKind.LANZOU_SHARE,
            url = "https://developer2.lanrar.com/file/?short-lived-token",
            userAgent = "Android System WebView",
        )
        assertTrue(
            policy.validateResolvedRequest(transientLanzouRequest) is
                com.tcrrry.helper.domain.artifact.SourcePolicyValidation.Accepted,
        )
        val transientCdnRequest = transientLanzouRequest.copy(
            url = "https://zip1.webgetstore.com/2026/8/19/archive.zip?short-lived-token",
        )
        assertTrue(
            policy.validateResolvedRequest(transientCdnRequest) is
                com.tcrrry.helper.domain.artifact.SourcePolicyValidation.Accepted,
        )
        assertTrue(policy.isLanzouSharePage("https://wwatl.lanzouw.com/tp/iabc123?token"))
        assertFalse(policy.isLanzouSharePage(transientLanzouRequest.url))
        assertFalse(policy.isLanzouTransientDownloadUrl(transientLanzouRequest.url))
        assertTrue(policy.isLanzouVerificationPage("https://developer2.lanrar.com/file/?short-lived-token"))
        assertTrue(policy.isLanzouTransientDownloadUrl(transientCdnRequest.url))
        assertFalse(policy.isLanzouTransientDownloadUrl("https://zip1.webgetstore.com/"))
        assertFalse(policy.isLanzouTransientDownloadUrl("https://zip1.evilwebgetstore.com/2026/archive.zip"))
        assertEquals(
            "lanzou_manifest_host_forbidden",
            (
                policy.validateManifestSource(
                    ArtifactSource(ArtifactSourceKind.LANZOU_SHARE, transientLanzouRequest.url),
                ) as com.tcrrry.helper.domain.artifact.SourcePolicyValidation.Rejected
            ).reasonCode,
        )
        assertEquals(
            "lanzou_manifest_host_forbidden",
            (
                policy.validateManifestSource(
                    ArtifactSource(ArtifactSourceKind.LANZOU_SHARE, transientCdnRequest.url),
                ) as com.tcrrry.helper.domain.artifact.SourcePolicyValidation.Rejected
            ).reasonCode,
        )
    }

    @Test
    fun `archive verifier fails closed on size or hash mismatch`() {
        val directory = Files.createTempDirectory("artifact-archive").toFile()
        val archive = directory.resolve("item.zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val manifest = manifestFor(byteArrayOf(1, 2), byteArrayOf(9))
        val result = ArchiveIdentityVerifier().verify(manifest, archive)
        assertEquals("archive_size_mismatch", (result as ArchiveIdentityResult.Failed).failure.reasonCode)
        assertFalse(archive.exists())

        val hashArchive = directory.resolve("hash.zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val hashManifest = manifestFor(byteArrayOf(1, 2, 3), byteArrayOf(9)).copy(archiveSha256 = "ff".repeat(32))
        val hashResult = ArchiveIdentityVerifier().verify(hashManifest, hashArchive)
        assertEquals("archive_sha256_mismatch", (hashResult as ArchiveIdentityResult.Failed).failure.reasonCode)
        assertFalse(hashArchive.exists())
    }

    @Test
    fun `extractor accepts only the manifest apk and rejects traversal or extras`() {
        val directory = Files.createTempDirectory("artifact-extract").toFile()
        val apk = byteArrayOf(7, 8, 9)
        val manifest = manifestFor(byteArrayOf(), apk)
        val traversalArchive = directory.resolve("traversal.zip").apply {
            writeBytes(zipBytes(listOf("../app.apk" to apk)))
        }
        val traversalManifest = manifest.copy(
            archiveSizeBytes = traversalArchive.length(),
            archiveSha256 = sha256(traversalArchive),
        )
        val traversalReceipt = VerifiedArchive(
            traversalManifest.componentId,
            traversalArchive,
            traversalArchive.length(),
            sha256(traversalArchive),
        )
        val traversalResult = ArtifactArchiveExtractor { Long.MAX_VALUE }
            .extract(traversalManifest, traversalReceipt, directory.resolve("traversal.apk.part"))
        assertEquals("archive_path_traversal", (traversalResult as ArchiveExtractionResult.Failed).failure.reasonCode)
        assertFalse(traversalArchive.exists())

        val extraArchive = directory.resolve("extra.zip").apply {
            writeBytes(zipBytes(listOf("app.apk" to apk, "notes.txt" to byteArrayOf(1))))
        }
        val extraManifest = manifest.copy(
            archiveSizeBytes = extraArchive.length(),
            archiveSha256 = sha256(extraArchive),
        )
        val extraResult = ArtifactArchiveExtractor { Long.MAX_VALUE }
            .extract(
                extraManifest,
                VerifiedArchive(extraManifest.componentId, extraArchive, extraArchive.length(), sha256(extraArchive)),
                directory.resolve("extra.apk.part"),
            )
        assertEquals("archive_extra_entry", (extraResult as ArchiveExtractionResult.Failed).failure.reasonCode)
        assertFalse(extraArchive.exists())
    }

    @Test
    fun `extractor rejects corrupt zip and output over manifest limit`() {
        val directory = Files.createTempDirectory("artifact-extract-limit").toFile()
        val corrupt = directory.resolve("corrupt.zip").apply { writeBytes("not-a-zip".toByteArray()) }
        val corruptManifest = manifestFor(corrupt.readBytes(), byteArrayOf(1))
        val corruptResult = ArtifactArchiveExtractor { Long.MAX_VALUE }.extract(
            corruptManifest,
            VerifiedArchive(corruptManifest.componentId, corrupt, corrupt.length(), sha256(corrupt.readBytes())),
            directory.resolve("corrupt.apk.part"),
        )
        assertEquals("archive_zip_invalid", (corruptResult as ArchiveExtractionResult.Failed).failure.reasonCode)

        val bytes = byteArrayOf(1, 2, 3)
        val oversized = directory.resolve("oversized.zip").apply { writeBytes(zipBytes(listOf("app.apk" to bytes))) }
        val oversizedManifest = manifestFor(oversized.readBytes(), byteArrayOf(1, 2))
        val oversizedResult = ArtifactArchiveExtractor { Long.MAX_VALUE }.extract(
            oversizedManifest,
            VerifiedArchive(oversizedManifest.componentId, oversized, oversized.length(), sha256(oversized.readBytes())),
            directory.resolve("oversized.apk.part"),
        )
        assertEquals("apk_size_exceeds_manifest", (oversizedResult as ArchiveExtractionResult.Failed).failure.reasonCode)
        assertFalse(oversized.exists())
    }

    @Test
    fun `identity verifier checks package version certificate and deletes archive after success`() {
        val directory = Files.createTempDirectory("artifact-identity").toFile()
        val apk = byteArrayOf(3, 4, 5, 6)
        val archive = directory.resolve("item.zip").apply { writeBytes(zipBytes(listOf("app.apk" to apk))) }
        val manifest = manifestFor(archive.readBytes(), apk)
        val verifiedArchive = ArchiveIdentityVerifier().verify(manifest, archive) as ArchiveIdentityResult.Verified
        val apkPart = directory.resolve("app.apk.part")
        val extracted = ArtifactArchiveExtractor { Long.MAX_VALUE }
            .extract(manifest, verifiedArchive.archive, apkPart) as ArchiveExtractionResult.Extracted
        val finalApk = directory.resolve("app.apk")
        val identity = ArtifactIdentityVerifier { file ->
            assertEquals(apk.toList(), file.readBytes().toList())
            ApkMetadata(
                packageName = "com.example.app",
                version = ArtifactVersion("1.0.0", 7),
                certificateSha256s = setOf("aa".repeat(32)),
            )
        }.verify(manifest, ArtifactSourceKind.R2, verifiedArchive.archive, extracted.apk, finalApk)
        assertTrue(identity is ArtifactIdentityResult.Verified)
        assertTrue(finalApk.isFile)
        assertFalse(archive.exists())
        assertFalse(apkPart.exists())
    }

    @Test
    fun `identity mismatch removes zip partial and invalid apk`() {
        val directory = Files.createTempDirectory("artifact-identity-fail").toFile()
        val apk = byteArrayOf(3, 4, 5)
        val archive = directory.resolve("item.zip").apply { writeBytes(zipBytes(listOf("app.apk" to apk))) }
        val manifest = manifestFor(archive.readBytes(), apk)
        val verifiedArchive = ArchiveIdentityVerifier().verify(manifest, archive) as ArchiveIdentityResult.Verified
        val extracted = ArtifactArchiveExtractor { Long.MAX_VALUE }
            .extract(manifest, verifiedArchive.archive, directory.resolve("app.apk.part")) as ArchiveExtractionResult.Extracted
        val finalApk = directory.resolve("app.apk").apply { writeBytes(byteArrayOf(99)) }
        val result = ArtifactIdentityVerifier {
            ApkMetadata("com.wrong.package", ArtifactVersion("1.0.0", 7), setOf("bb".repeat(32)))
        }.verify(manifest, ArtifactSourceKind.GITHUB_RELEASES, verifiedArchive.archive, extracted.apk, finalApk)
        assertEquals("apk_package_mismatch", (result as ArtifactIdentityResult.Failed).failure.reasonCode)
        assertFalse(archive.exists())
        assertFalse(extracted.apk.file.exists())
        assertFalse(finalApk.exists())
    }

    @Test
    fun `identity verifier rejects apk hash version and certificate mismatches`() {
        val directory = Files.createTempDirectory("artifact-identity-fields").toFile()
        fun runCase(
            manifestChange: (ArtifactManifest) -> ArtifactManifest,
            metadata: ApkMetadata,
            expectedReason: String,
        ) {
            val apk = byteArrayOf(3, 4, 5)
            val archive = directory.resolve("$expectedReason.zip").apply {
                writeBytes(zipBytes(listOf("app.apk" to apk)))
            }
            val base = manifestFor(archive.readBytes(), apk)
            val changed = manifestChange(base)
            val verifiedArchive = ArchiveIdentityVerifier().verify(changed, archive) as ArchiveIdentityResult.Verified
            val extracted = ArtifactArchiveExtractor { Long.MAX_VALUE }
                .extract(changed, verifiedArchive.archive, directory.resolve("$expectedReason.apk.part")) as ArchiveExtractionResult.Extracted
            val result = ArtifactIdentityVerifier { metadata }
                .verify(changed, ArtifactSourceKind.R2, verifiedArchive.archive, extracted.apk, directory.resolve("$expectedReason.apk"))
            assertEquals(expectedReason, (result as ArtifactIdentityResult.Failed).failure.reasonCode)
        }
        runCase(
            manifestChange = { it.copy(apkSha256 = "ff".repeat(32)) },
            metadata = ApkMetadata("com.example.app", ArtifactVersion("1.0.0", 7), setOf("aa".repeat(32))),
            expectedReason = "apk_sha256_mismatch",
        )
        runCase(
            manifestChange = { it },
            metadata = ApkMetadata("com.example.app", ArtifactVersion("9.0.0", 99), setOf("aa".repeat(32))),
            expectedReason = "apk_version_mismatch",
        )
        runCase(
            manifestChange = { it },
            metadata = ApkMetadata("com.example.app", ArtifactVersion("1.0.0", 7), setOf("bb".repeat(32))),
            expectedReason = "apk_certificate_mismatch",
        )
    }

    @Test
    fun `downloader rejects html and keeps no transient cache`() = runBlocking {
        val directory = Files.createTempDirectory("artifact-download-html").toFile()
        val manifest = manifestFor(byteArrayOf(1, 2, 3), byteArrayOf(4))
        val cache = ArtifactCache(directory)
        val downloader = ArtifactDownloader(
            transport = ArtifactTransport { _, _ ->
                ArtifactTransportResponse(
                    statusCode = 200,
                    contentLength = 20,
                    contentType = "text/html; charset=utf-8",
                    body = ByteArrayInputStream("<html>blocked</html>".toByteArray()),
                )
            },
            cache = cache,
        )
        val result = downloader.download(
            manifest,
            ResolvedDownloadRequest(ArtifactSourceKind.R2, "https://assets.r2.dev/item.zip"),
        ) as ArtifactDownloadResult.Failed
        assertEquals("download_non_archive_response", result.failure.reasonCode)
        val paths = cache.paths(manifest)
        assertFalse(paths.archivePart.exists())
        assertFalse(paths.apk.exists())
    }

    @Test
    fun `downloader resumes same source without persisting url or cookie`() = runBlocking {
        val directory = Files.createTempDirectory("artifact-download-resume").toFile()
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6)
        val manifest = manifestFor(bytes, byteArrayOf(9))
        val cache = ArtifactCache(directory)
        val calls = AtomicInteger(0)
        val transport = ArtifactTransport { _, rangeStart ->
            when (calls.getAndIncrement()) {
                0 -> ArtifactTransportResponse(
                    statusCode = 200,
                    contentLength = bytes.size.toLong(),
                    contentType = "application/zip",
                    body = object : InputStream() {
                        private var index = 0
                        override fun read(): Int {
                            if (index == 3) throw IOException("cut")
                            return bytes[index++].toInt()
                        }
                    },
                )

                else -> {
                    assertEquals(3L, rangeStart)
                    ArtifactTransportResponse(
                        statusCode = 206,
                        contentLength = 3,
                        contentType = "application/zip",
                        body = ByteArrayInputStream(bytes.copyOfRange(3, bytes.size)),
                    )
                }
            }
        }
        val request = ResolvedDownloadRequest(
            sourceKind = ArtifactSourceKind.R2,
            url = "https://assets.r2.dev/item.zip?token=short",
            cookie = "secret-cookie",
            referer = "https://example.invalid",
        )
        val first = ArtifactDownloader(transport, cache).download(manifest, request)
        assertTrue(first is ArtifactDownloadResult.Failed)
        val second = ArtifactDownloader(transport, cache).download(manifest, request)
        assertTrue(second is ArtifactDownloadResult.Completed)
        val metadata = cache.paths(manifest).resumeMetadata.readText()
        assertFalse(metadata.contains("token"))
        assertFalse(metadata.contains("cookie"))
        assertFalse(metadata.contains("referer"))
    }

    @Test
    fun `downloader cancellation preserves a resumable part`() = runBlocking {
        val directory = Files.createTempDirectory("artifact-download-cancel").toFile()
        val bytes = ByteArray(32) { it.toByte() }
        val manifest = manifestFor(bytes, byteArrayOf(9))
        val cache = ArtifactCache(directory)
        lateinit var downloadJob: kotlinx.coroutines.Job
        val downloader = ArtifactDownloader(
            transport = ArtifactTransport { _, _ ->
                ArtifactTransportResponse(
                    statusCode = 200,
                    contentLength = bytes.size.toLong(),
                    contentType = "application/zip",
                    body = object : InputStream() {
                        private var index = 0
                        override fun read(): Int = if (index < bytes.size) bytes[index++].toInt() else -1
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            if (index >= bytes.size) return -1
                            buffer[offset] = bytes[index++]
                            return 1
                        }
                    },
                )
            },
            cache = cache,
            onProgress = { progress ->
                if (progress.bytesWritten >= 3L) downloadJob.cancel()
            },
        )
        downloadJob = launch {
            downloader.download(
                manifest,
                ResolvedDownloadRequest(ArtifactSourceKind.R2, "https://assets.r2.dev/item.zip"),
            )
        }
        downloadJob.join()
        assertTrue(downloadJob.isCancelled)
        val part = cache.paths(manifest).archivePart
        assertTrue(part.isFile)
        assertTrue(part.length() in 1L until manifest.archiveSizeBytes)
    }

    @Test
    fun `hidden source adapter destroys host after callback and times out`() = runBlocking {
        val destroyed = AtomicInteger(0)
        val request = ResolvedDownloadRequest(
            sourceKind = ArtifactSourceKind.LANZOU_SHARE,
            url = "https://developer2.lanrar.com/file/?short-lived-token",
            userAgent = "Android System WebView",
            mimeType = "application/zip",
        )
        val host = object : LanzouWebViewHost {
            override fun start(
                shareUrl: String,
                onDownload: (ResolvedDownloadRequest) -> Unit,
                onFailure: (com.tcrrry.helper.domain.artifact.ArtifactFailure) -> Unit,
            ) = onDownload(request)

            override fun stopAndDestroy() {
                destroyed.incrementAndGet()
            }
        }
        val adapter = LanzouWebSourceAdapter(LanzouWebViewHostFactory { host }, timeoutMillis = 100)
        assertTrue(adapter.resolve(ArtifactSource(ArtifactSourceKind.LANZOU_SHARE, "https://wwatl.lanzouw.com/iabc123")) is LanzouResolutionResult.Success)
        assertTrue(destroyed.get() >= 1)

        val timeoutAdapter = LanzouWebSourceAdapter(
            LanzouWebViewHostFactory {
                object : LanzouWebViewHost {
                    override fun start(
                        shareUrl: String,
                        onDownload: (ResolvedDownloadRequest) -> Unit,
                        onFailure: (com.tcrrry.helper.domain.artifact.ArtifactFailure) -> Unit,
                    ) = Unit

                    override fun stopAndDestroy() {
                        destroyed.incrementAndGet()
                    }
                }
            },
            timeoutMillis = 1,
        )
        val timeout = timeoutAdapter.resolve(
            ArtifactSource(ArtifactSourceKind.LANZOU_SHARE, "https://wwatl.lanzouw.com/iabc123"),
        ) as LanzouResolutionResult.Failure
        assertEquals("lanzou_parse_timeout", timeout.failure.reasonCode)
    }

    private fun manifestFor(archiveBytes: ByteArray, apkBytes: ByteArray): ArtifactManifest = ArtifactManifest(
        schemaVersion = 1,
        componentId = "lyrics",
        displayName = "Lyrics",
        required = true,
        version = ArtifactVersion("1.0.0", 1),
        compatibility = CompatibilityRange(minAndroidSdk = 26),
        archiveFileName = "lyrics.zip",
        archiveSizeBytes = archiveBytes.size.toLong().coerceAtLeast(1L),
        archiveSha256 = sha256(if (archiveBytes.isEmpty()) byteArrayOf(0) else archiveBytes),
        apkEntryName = "app.apk",
        apkSizeBytes = apkBytes.size.toLong(),
        apkSha256 = sha256(apkBytes),
        packageName = "com.example.app",
        apkVersion = ArtifactVersion("1.0.0", 7),
        certificateSha256 = "aa".repeat(32),
        sources = listOf(
            ArtifactSource(ArtifactSourceKind.LANZOU_SHARE, "https://wwatl.lanzouw.com/iabc123"),
            ArtifactSource(ArtifactSourceKind.R2, "https://assets.r2.dev/lyrics.zip"),
            ArtifactSource(ArtifactSourceKind.GITHUB_RELEASES, "https://github.com/a/b/releases/download/v1/lyrics.zip"),
        ),
    )

    private fun zipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
