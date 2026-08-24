package com.ninepointnine.helper.data.artifact

import com.ninepointnine.helper.data.download.ArtifactCache
import com.ninepointnine.helper.data.download.ArtifactDownloader
import com.ninepointnine.helper.data.download.ArtifactDownloadResult
import com.ninepointnine.helper.data.download.ArtifactTransport
import com.ninepointnine.helper.data.download.ArtifactTransportResponse
import com.ninepointnine.helper.data.web.LanzouResolutionResult
import com.ninepointnine.helper.data.web.LanzouWebSourceAdapter
import com.ninepointnine.helper.data.web.LanzouWebViewHost
import com.ninepointnine.helper.data.web.LanzouWebViewHostFactory
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactManifestValidator
import com.ninepointnine.helper.domain.artifact.ArtifactSource
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.CompatibilityRange
import com.ninepointnine.helper.domain.artifact.InstallerPublisherTrustRegistry
import com.ninepointnine.helper.domain.artifact.ArtifactReleaseTrack
import com.ninepointnine.helper.domain.artifact.ResolvedDownloadRequest
import com.ninepointnine.helper.domain.artifact.SourcePlan
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactSecurityTest {
    @Test
    fun `publisher trust recognizes debug staging and production component identities`() {
        assertTrue(
            InstallerPublisherTrustRegistry.isTrusted(
                "nine-studio",
                "com.ninepointnine.desktop",
                setOf("bfb70dc15b54ad2f1b8acd35fa26ecf552bf2ef21d416a44b7eeda5e5e9ebaa9"),
            ),
        )
        assertTrue(
            InstallerPublisherTrustRegistry.isTrusted(
                "nine-studio",
                "com.ninepointnine.desktoplyrics",
                setOf("934b9151fe62b39a3474a11f00c2114c7f392b18fec85f39f8d71b9596860e03"),
            ),
        )
        assertTrue(
            InstallerPublisherTrustRegistry.isTrusted(
                "nine-studio",
                "com.tcrrry.desktop",
                setOf("2990047fddf6d6ec1eb7f83731fcc1398616e5fb83aec97542a4f132c35a1a27"),
            ),
        )
        assertFalse(
            InstallerPublisherTrustRegistry.isTrusted(
                "nine-studio",
                "com.ninepointnine.desktoplyrics",
                setOf("bfb70dc15b54ad2f1b8acd35fa26ecf552bf2ef21d416a44b7eeda5e5e9ebaa9"),
            ),
        )
    }

    @Test
    fun `component identity selects the current track package and certificate exactly`() {
        val stagingDesktop = InstallerPublisherTrustRegistry.matchComponentIdentity(
            componentId = "desktop",
            profileId = "nine-studio",
            environment = "staging",
            channel = "debug",
            packageName = "com.ninepointnine.desktop",
            certificateDigests = setOf("bfb70dc15b54ad2f1b8acd35fa26ecf552bf2ef21d416a44b7eeda5e5e9ebaa9"),
        )
        assertEquals(ArtifactReleaseTrack.STAGING, stagingDesktop?.track)
        assertNull(
            InstallerPublisherTrustRegistry.matchComponentIdentity(
                componentId = "desktop",
                profileId = "nine-studio",
                environment = "staging",
                channel = "debug",
                packageName = "com.tcrrry.desktop",
                certificateDigests = setOf("2990047fddf6d6ec1eb7f83731fcc1398616e5fb83aec97542a4f132c35a1a27"),
            ),
        )
        assertEquals(
            ArtifactReleaseTrack.STAGING,
            InstallerPublisherTrustRegistry.matchComponentIdentity(
                componentId = "lyrics",
                profileId = "nine-studio",
                environment = "staging",
                channel = "debug",
                packageName = "com.ninepointnine.desktoplyrics",
                certificateDigests = setOf("1eb136fffd3f1e4c204d0933cab66c51ee4536a29e949b9c080925c01563b51d"),
            )?.track,
        )
        assertEquals(
            ArtifactReleaseTrack.STAGING,
            InstallerPublisherTrustRegistry.matchComponentIdentity(
                componentId = "cast",
                profileId = "nine-studio",
                environment = "staging",
                channel = "debug",
                packageName = "com.ninepointnine.desktopcast",
                certificateDigests = setOf("98740b95c30064f727b9401a851ecf2e576d5e5c38fcc318284578747ba50e2a"),
            )?.track,
        )
        assertNull(
            InstallerPublisherTrustRegistry.matchComponentIdentity(
                componentId = "cast",
                profileId = "nine-studio",
                environment = "staging",
                channel = "debug",
                packageName = "com.ninepointnine.desktopcast",
                certificateDigests = setOf("2990047fddf6d6ec1eb7f83731fcc1398616e5fb83aec97542a4f132c35a1a27"),
            ),
        )
    }
    @Test
    fun `transient download request string representation redacts URL and headers`() {
        val request = ResolvedDownloadRequest(
            sourceKind = ArtifactSourceKind.LANZOU_SHARE,
            url = "https://zip1.webgetstore.com/secret-token",
            userAgent = "secret-user-agent",
            cookie = "secret-cookie",
            referer = "https://wwatl.lanzouw.com/private",
            contentDisposition = "secret-disposition",
        )

        val text = request.toString()

        assertFalse(text.contains("secret-token"))
        assertFalse(text.contains("secret-cookie"))
        assertFalse(text.contains("private"))
        assertTrue(text.contains("<redacted>"))
    }

    @Test
    fun `manifest validation rejects missing digest and unsafe names`() {
        val valid = manifestFor(byteArrayOf(1), byteArrayOf(2))
        assertTrue(ArtifactManifestValidator.validate(valid) is com.ninepointnine.helper.domain.artifact.ManifestValidation.Valid)
        val local = valid.copy(
            localOnly = true,
            archiveSizeBytes = 0L,
            archiveSha256 = "",
            sources = listOf(
                ArtifactSource(
                    ArtifactSourceKind.LOCAL_DOWNLOAD,
                    ArtifactManifestValidator.LOCAL_DOWNLOAD_URL,
                ),
            ),
        )
        assertTrue(ArtifactManifestValidator.validate(local) is com.ninepointnine.helper.domain.artifact.ManifestValidation.Valid)
        assertEquals(
            "archive_sha256_invalid",
            (ArtifactManifestValidator.validate(valid.copy(archiveSha256 = "")) as com.ninepointnine.helper.domain.artifact.ManifestValidation.Invalid).reasonCode,
        )
        assertEquals(
            "apk_entry_name_invalid",
            (ArtifactManifestValidator.validate(valid.copy(apkEntryName = "../app.apk")) as com.ninepointnine.helper.domain.artifact.ManifestValidation.Invalid).reasonCode,
        )
    }

    @Test
    fun `clear all removes only helper APKs from the public Download root`() {
        val privateRoot = Files.createTempDirectory("artifact-cache-private").toFile()
        val publicRoot = Files.createTempDirectory("artifact-cache-public").toFile()
        try {
            val cache = ArtifactCache(privateRoot, publicRoot)
            val helperApk = publicRoot.resolve("03helper-desktop-1.apk").apply { writeBytes(byteArrayOf(1)) }
            val userApk = publicRoot.resolve("manual-desktop.apk").apply { writeBytes(byteArrayOf(2)) }
            privateRoot.resolve("temporary.zip.part").writeBytes(byteArrayOf(3))

            cache.clearAll()

            assertFalse(helperApk.exists())
            assertTrue(userApk.exists())
            assertFalse(privateRoot.resolve("temporary.zip.part").exists())
        } finally {
            privateRoot.deleteRecursively()
            publicRoot.deleteRecursively()
        }
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
        val plan = com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy().plan(manifest)
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
            (com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy().plan(folder) as SourcePlan.Rejected).reasonCode,
        )
        val converter = manifest.copy(
            sources = manifest.sources.map {
                if (it.kind == ArtifactSourceKind.LANZOU_SHARE) {
                    it.copy(url = "https://example.com/convert?url=https://wwatl.lanzouw.com/iabc123")
                } else it
            },
        )
        assertTrue(com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy().plan(converter) is SourcePlan.Rejected)

        val policy = com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy()
        val transientLanzouRequest = ResolvedDownloadRequest(
            sourceKind = ArtifactSourceKind.LANZOU_SHARE,
            url = "https://developer2.lanrar.com/file/?short-lived-token",
            userAgent = "Android System WebView",
        )
        assertTrue(
            policy.validateResolvedRequest(transientLanzouRequest) is
                com.ninepointnine.helper.domain.artifact.SourcePolicyValidation.Accepted,
        )
        val transientCdnRequest = transientLanzouRequest.copy(
            url = "https://zip1.webgetstore.com/2026/8/19/archive.zip?short-lived-token",
        )
        assertTrue(
            policy.validateResolvedRequest(transientCdnRequest) is
                com.ninepointnine.helper.domain.artifact.SourcePolicyValidation.Accepted,
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
                ) as com.ninepointnine.helper.domain.artifact.SourcePolicyValidation.Rejected
            ).reasonCode,
        )
        assertEquals(
            "lanzou_manifest_host_forbidden",
            (
                policy.validateManifestSource(
                    ArtifactSource(ArtifactSourceKind.LANZOU_SHARE, transientCdnRequest.url),
                ) as com.ninepointnine.helper.domain.artifact.SourcePolicyValidation.Rejected
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
    fun `identity verifier keeps package and publisher certificate as the minimum gate`() {
        val directory = Files.createTempDirectory("artifact-identity-fields").toFile()
        fun runCase(
            name: String,
            manifestChange: (ArtifactManifest) -> ArtifactManifest,
            metadata: ApkMetadata,
            expectedFailure: String?,
        ) {
            val apk = byteArrayOf(3, 4, 5)
            val archive = directory.resolve("$name.zip").apply {
                writeBytes(zipBytes(listOf("app.apk" to apk)))
            }
            val base = manifestFor(archive.readBytes(), apk)
            val changed = manifestChange(base)
            val verifiedArchive = ArchiveIdentityVerifier().verify(changed, archive) as ArchiveIdentityResult.Verified
            val extracted = ArtifactArchiveExtractor { Long.MAX_VALUE }
                .extract(changed, verifiedArchive.archive, directory.resolve("$name.apk.part")) as ArchiveExtractionResult.Extracted
            val result = ArtifactIdentityVerifier { metadata }
                .verify(changed, ArtifactSourceKind.R2, verifiedArchive.archive, extracted.apk, directory.resolve("$name.apk"))
            if (expectedFailure == null) {
                assertTrue(result is ArtifactIdentityResult.Verified)
            } else {
                assertEquals(expectedFailure, (result as ArtifactIdentityResult.Failed).failure.reasonCode)
            }
        }
        runCase(
            name = "hash_stale",
            manifestChange = { it.copy(apkSha256 = "ff".repeat(32)) },
            metadata = ApkMetadata("com.example.app", ArtifactVersion("1.0.0", 7), setOf("aa".repeat(32))),
            expectedFailure = null,
        )
        runCase(
            name = "version_stale",
            manifestChange = { it },
            metadata = ApkMetadata("com.example.app", ArtifactVersion("9.0.0", 99), setOf("aa".repeat(32))),
            expectedFailure = null,
        )
        runCase(
            name = "certificate_wrong",
            manifestChange = { it },
            metadata = ApkMetadata("com.example.app", ArtifactVersion("1.0.0", 7), setOf("bb".repeat(32))),
            expectedFailure = "apk_certificate_mismatch",
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
                onFailure: (com.ninepointnine.helper.domain.artifact.ArtifactFailure) -> Unit,
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
                        onFailure: (com.ninepointnine.helper.domain.artifact.ArtifactFailure) -> Unit,
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
