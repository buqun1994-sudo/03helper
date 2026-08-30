package com.ninepointnine.helper.data.artifact

import com.ninepointnine.helper.data.download.ArtifactCache
import com.ninepointnine.helper.data.download.ArtifactDownloader
import com.ninepointnine.helper.data.download.ArtifactDownloadResult
import com.ninepointnine.helper.data.download.DynamicArchiveDownloadResult
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
        assertTrue(
            InstallerPublisherTrustRegistry.isTrusted(
                "fossify-approved",
                "org.fossify.filemanager.debug",
                setOf("be75daa9799eaa4bbe0592a59ce66d3aaef9931d7f7f76ef732907665408a10f"),
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
            ArtifactReleaseTrack.DEBUG,
            InstallerPublisherTrustRegistry.matchComponentIdentity(
                componentId = "file-manager",
                profileId = "fossify-approved",
                environment = "staging",
                channel = "debug",
                packageName = "org.fossify.filemanager.debug",
                certificateDigests = setOf("2990047fddf6d6ec1eb7f83731fcc1398616e5fb83aec97542a4f132c35a1a27"),
            )?.track,
        )
        assertEquals(
            ArtifactReleaseTrack.RELEASE,
            InstallerPublisherTrustRegistry.matchComponentIdentity(
                componentId = "file-manager",
                profileId = "fossify-approved",
                environment = "production",
                channel = "release",
                packageName = "org.fossify.filemanager.debug",
                certificateDigests = setOf("be75daa9799eaa4bbe0592a59ce66d3aaef9931d7f7f76ef732907665408a10f"),
            )?.track,
        )
        assertNull(
            InstallerPublisherTrustRegistry.matchComponentIdentity(
                componentId = "file-manager",
                profileId = "fossify-approved",
                environment = "production",
                channel = "release",
                packageName = "org.fossify.filemanager.debug",
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
    fun `legacy private verified APKs are removed and never become public candidates`() {
        val privateRoot = Files.createTempDirectory("artifact-cache-legacy-private").toFile()
        val publicRoot = Files.createTempDirectory("artifact-cache-legacy-public").toFile()
        try {
            val legacyRoot = privateRoot.resolve("verified-apks").apply { mkdirs() }
            val legacyApk = legacyRoot.resolve("legacy.apk").apply { writeBytes(byteArrayOf(1)) }
            val publicApk = publicRoot.resolve("manual.apk").apply { writeBytes(byteArrayOf(2)) }

            val cache = ArtifactCache(privateRoot, publicRoot)

            assertFalse(legacyApk.exists())
            assertEquals(listOf(publicApk.canonicalFile), cache.publicApkCandidates().map { it.canonicalFile })

            // A directory with the old name created after startup is still
            // outside the public source boundary and must not be scanned.
            val lateLegacyRoot = privateRoot.resolve("verified-apks").apply { mkdirs() }
            lateLegacyRoot.resolve("late-legacy.apk")
                .writeBytes(byteArrayOf(3))
            assertTrue(cache.refreshPublicApkCandidates().none { it.name == "late-legacy.apk" })
        } finally {
            privateRoot.deleteRecursively()
            publicRoot.deleteRecursively()
        }
    }

    @Test
    fun `private cache cleanup removes working files without deleting public Download APKs`() {
        val privateRoot = Files.createTempDirectory("artifact-cache-clean-private").toFile()
        val publicRoot = Files.createTempDirectory("artifact-cache-clean-public").toFile()
        try {
            val cache = ArtifactCache(privateRoot, publicRoot)
            privateRoot.resolve("archive.zip.part").writeBytes(byteArrayOf(1))
            privateRoot.resolve(".public-candidate-7.apk").writeBytes(byteArrayOf(2))
            privateRoot.resolve(".icon-candidates/.icon-candidate-7.apk").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(6))
            }
            privateRoot.resolve("verified-apks").apply { mkdirs() }
                .resolve("legacy.apk")
                .writeBytes(byteArrayOf(3))
            val helperApk = publicRoot.resolve("03helper-desktop-1.apk").apply { writeBytes(byteArrayOf(4)) }
            val userApk = publicRoot.resolve("manual-desktop.apk").apply { writeBytes(byteArrayOf(5)) }

            cache.clearPrivateCache()

            assertTrue(privateRoot.listFiles().isNullOrEmpty())
            assertTrue(helperApk.exists())
            assertTrue(userApk.exists())
        } finally {
            privateRoot.deleteRecursively()
            publicRoot.deleteRecursively()
        }
    }

    @Test
    fun `cache startup removes stale private APK material but keeps a valid fixed resume pair`() {
        val privateRoot = Files.createTempDirectory("artifact-cache-startup-private").toFile()
        val publicRoot = Files.createTempDirectory("artifact-cache-startup-public").toFile()
        try {
            val firstCache = ArtifactCache(privateRoot, publicRoot)
            val manifest = manifestFor(
                byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2),
                byteArrayOf(3),
            )
            val paths = firstCache.paths(manifest)
            paths.archivePart.writeBytes(byteArrayOf(0x50, 0x4b, 0x03))
            firstCache.writeResumeMetadata(manifest, ArtifactSourceKind.R2.wireName)
            privateRoot.resolve("0123456789abcdef01234567.apk").writeBytes(byteArrayOf(1))
            privateRoot.resolve("old.apk.part").writeBytes(byteArrayOf(2))
            privateRoot.resolve(".public-candidate-7.apk").writeBytes(byteArrayOf(3))
            privateRoot.resolve("prepare-lyrics.zip").writeBytes(byteArrayOf(4))
            privateRoot.resolve("prepare-lyrics.zip.part").writeBytes(byteArrayOf(5))
            privateRoot.resolve("prepare-lyrics.apk").writeBytes(byteArrayOf(6))
            privateRoot.resolve("installed-verification/old.apk").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(7))
            }
            privateRoot.resolve("verified-apks/legacy.apk").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(8))
            }
            val publicApk = publicRoot.resolve("manual.apk").apply { writeBytes(byteArrayOf(9)) }

            ArtifactCache(privateRoot, publicRoot)

            assertTrue(paths.archivePart.exists())
            assertTrue(paths.resumeMetadata.exists())
            assertTrue(publicApk.exists())
            assertFalse(privateRoot.resolve("0123456789abcdef01234567.apk").exists())
            assertFalse(privateRoot.resolve("old.apk.part").exists())
            assertFalse(privateRoot.resolve(".public-candidate-7.apk").exists())
            assertFalse(privateRoot.resolve("prepare-lyrics.zip").exists())
            assertFalse(privateRoot.resolve("prepare-lyrics.zip.part").exists())
            assertFalse(privateRoot.resolve("prepare-lyrics.apk").exists())
            assertFalse(privateRoot.resolve("installed-verification").exists())
            assertFalse(privateRoot.resolve("verified-apks").exists())
        } finally {
            privateRoot.deleteRecursively()
            publicRoot.deleteRecursively()
        }
    }

    @Test
    fun `clear all with a shared root preserves user APKs`() {
        val root = Files.createTempDirectory("artifact-cache-shared-root").toFile()
        try {
            val userApk = root.resolve("manual-download.apk").apply { writeBytes(byteArrayOf(1)) }
            val helperApk = root.resolve("03helper-desktop-1.apk").apply { writeBytes(byteArrayOf(2)) }
            root.resolve("prepare-desktop.apk").writeBytes(byteArrayOf(3))

            ArtifactCache(root).clearAll()

            assertTrue(userApk.exists())
            assertFalse(helperApk.exists())
            assertFalse(root.resolve("prepare-desktop.apk").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `private cleanup never walks into a public Download subtree`() {
        val privateRoot = Files.createTempDirectory("artifact-cache-public-child-private").toFile()
        val publicRoot = privateRoot.resolve("Download").apply { mkdirs() }
        try {
            val cache = ArtifactCache(privateRoot, publicRoot)
            val publicApk = publicRoot.resolve("manual.apk").apply { writeBytes(byteArrayOf(1)) }
            val privateZip = privateRoot.resolve("prepare-desktop.zip").apply { writeBytes(byteArrayOf(2)) }

            cache.clearPrivateCache()

            assertTrue(publicApk.exists())
            assertFalse(privateZip.exists())
        } finally {
            privateRoot.deleteRecursively()
        }
    }

    @Test
    fun `private cleanup still removes a workspace nested below public Download`() {
        val publicRoot = Files.createTempDirectory("artifact-cache-public-parent").toFile()
        val privateRoot = publicRoot.resolve(".03helper-work").apply { mkdirs() }
        try {
            val cache = ArtifactCache(privateRoot, publicRoot)
            val publicApk = publicRoot.resolve("03helper-desktop-1.apk").apply { writeBytes(byteArrayOf(1)) }
            val privateZip = privateRoot.resolve("prepare-desktop.zip").apply { writeBytes(byteArrayOf(2)) }

            cache.clearPrivateCache()

            assertTrue(publicApk.exists())
            assertFalse(privateZip.exists())
        } finally {
            publicRoot.deleteRecursively()
        }
    }

    @Test
    fun `shared root cleanup does not delete an unrelated hexadecimal APK`() {
        val root = Files.createTempDirectory("artifact-cache-shared-hex-apk").toFile()
        try {
            val userApk = root.resolve("0123456789abcdef01234567.apk").apply { writeBytes(byteArrayOf(1)) }
            root.resolve("prepare-desktop.apk").writeBytes(byteArrayOf(2))
            val cache = ArtifactCache(root)

            cache.clearPrivateCache()

            assertTrue(userApk.exists())
            assertFalse(root.resolve("prepare-desktop.apk").exists())
        } finally {
            root.deleteRecursively()
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
        val verificationRequest = ResolvedDownloadRequest(
            sourceKind = ArtifactSourceKind.LANZOU_SHARE,
            url = "https://developer2.lanrar.com/file/?short-lived-token",
            userAgent = "Android System WebView",
        )
        assertTrue(
            policy.validateResolvedRequest(verificationRequest) is
                com.ninepointnine.helper.domain.artifact.SourcePolicyValidation.Rejected,
        )
        val transientCdnRequest = verificationRequest.copy(
            url = "https://zip1.webgetstore.com/2026/8/19/archive.zip?short-lived-token",
        )
        assertTrue(
            policy.validateResolvedRequest(transientCdnRequest) is
                com.ninepointnine.helper.domain.artifact.SourcePolicyValidation.Accepted,
        )
        assertTrue(policy.isLanzouSharePage("https://wwatl.lanzouw.com/tp/iabc123?token"))
        assertFalse(policy.isLanzouSharePage(verificationRequest.url))
        assertFalse(policy.isLanzouTransientDownloadUrl(verificationRequest.url))
        assertTrue(policy.isLanzouVerificationPage("https://developer2.lanrar.com/file/?short-lived-token"))
        assertTrue(policy.isLanzouTransientDownloadUrl(transientCdnRequest.url))
        assertFalse(policy.isLanzouTransientDownloadUrl("https://zip1.webgetstore.com/"))
        assertFalse(policy.isLanzouTransientDownloadUrl("https://zip1.evilwebgetstore.com/2026/archive.zip"))
        assertEquals(
            "lanzou_manifest_host_forbidden",
            (
                policy.validateManifestSource(
                    ArtifactSource(ArtifactSourceKind.LANZOU_SHARE, verificationRequest.url),
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
    fun `extractor preserves UTF-8 apk entry names`() {
        val directory = Files.createTempDirectory("artifact-extract-utf8").toFile()
        val apk = byteArrayOf(7, 8, 9)
        val entryName = "文件管理器-v1.6.1-icar03.apk"
        val archiveBytes = zipBytes(listOf(entryName to apk))
        val archive = directory.resolve("文件管理器-v1.6.1-icar03.zip").apply {
            writeBytes(archiveBytes)
        }
        val manifest = manifestFor(archiveBytes, apk).copy(
            archiveFileName = archive.name,
            apkEntryName = entryName,
        )

        val result = ArtifactArchiveExtractor { Long.MAX_VALUE }.extract(
            manifest,
            VerifiedArchive(manifest.componentId, archive, archive.length(), sha256(archiveBytes)),
            directory.resolve("extracted.apk.part"),
        ) as ArchiveExtractionResult.Extracted

        assertEquals(entryName, result.apk.entryName)
        assertEquals(apk.toList(), result.apk.file.readBytes().toList())
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
    fun `identity verifier checks package publisher certificate and declared version`() {
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
            expectedFailure = "apk_version_mismatch",
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
    fun `downloader removes a stale resume pair after a non retryable HTTP response`() = runBlocking {
        val directory = Files.createTempDirectory("artifact-download-http-terminal").toFile()
        val archive = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2)
        val manifest = manifestFor(archive, byteArrayOf(4))
        val cache = ArtifactCache(directory)
        val paths = cache.paths(manifest)
        paths.archivePart.writeBytes(archive.copyOfRange(0, 3))
        cache.writeResumeMetadata(manifest, ArtifactSourceKind.R2.wireName)
        val downloader = ArtifactDownloader(
            transport = ArtifactTransport { _, rangeStart ->
                if (rangeStart > 0L) {
                    assertEquals(3L, rangeStart)
                    return@ArtifactTransport ArtifactTransportResponse(
                        statusCode = 200,
                        contentLength = archive.size.toLong(),
                        contentType = "application/zip",
                        body = ByteArrayInputStream(archive),
                    )
                }
                ArtifactTransportResponse(
                    statusCode = 404,
                    contentLength = 0L,
                    contentType = "text/plain",
                    body = ByteArrayInputStream(ByteArray(0)),
                )
            },
            cache = cache,
        )

        val result = downloader.download(
            manifest,
            ResolvedDownloadRequest(ArtifactSourceKind.R2, "https://assets.r2.dev/item.zip"),
        ) as ArtifactDownloadResult.Failed

        assertEquals("download_http_404", result.failure.reasonCode)
        assertFalse(result.failure.retryable)
        assertFalse(paths.archivePart.exists())
        assertFalse(paths.resumeMetadata.exists())
    }

    @Test
    fun `downloader rejects binary content without a zip signature`() = runBlocking {
        val directory = Files.createTempDirectory("artifact-download-magic").toFile()
        val bytes = "not an archive".toByteArray()
        val manifest = manifestFor(bytes, byteArrayOf(4))
        val cache = ArtifactCache(directory)
        val downloader = ArtifactDownloader(
            transport = ArtifactTransport { _, _ ->
                ArtifactTransportResponse(
                    statusCode = 200,
                    contentLength = bytes.size.toLong(),
                    contentType = "application/octet-stream",
                    body = ByteArrayInputStream(bytes),
                )
            },
            cache = cache,
        )

        val result = downloader.download(
            manifest,
            ResolvedDownloadRequest(ArtifactSourceKind.R2, "https://assets.r2.dev/item.zip"),
        ) as ArtifactDownloadResult.Failed

        assertEquals("download_not_zip", result.failure.reasonCode)
        assertFalse(cache.paths(manifest).archivePart.exists())
    }

    @Test
    fun `dynamic downloader rejects binary content without a zip signature`() = runBlocking {
        val directory = Files.createTempDirectory("dynamic-download-magic").toFile()
        val destination = directory.resolve("dynamic.zip")
        val bytes = "not an archive".toByteArray()
        val downloader = ArtifactDownloader(
            transport = ArtifactTransport { _, _ ->
                ArtifactTransportResponse(
                    statusCode = 200,
                    contentLength = bytes.size.toLong(),
                    contentType = "application/octet-stream",
                    body = ByteArrayInputStream(bytes),
                )
            },
            cache = ArtifactCache(directory),
        )

        val result = downloader.downloadDynamic(
            ResolvedDownloadRequest(ArtifactSourceKind.R2, "https://assets.r2.dev/item.zip"),
            destination,
        ) as DynamicArchiveDownloadResult.Failed

        assertEquals("dynamic_archive_not_zip", result.reasonCode)
        assertFalse(destination.exists())
    }

    @Test
    fun `dynamic downloader removes a stale destination when the retry fails`() = runBlocking {
        val directory = Files.createTempDirectory("dynamic-download-stale").toFile()
        val destination = directory.resolve("dynamic.zip").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        val downloader = ArtifactDownloader(
            transport = ArtifactTransport { _, _ ->
                ArtifactTransportResponse(
                    statusCode = 200,
                    contentLength = 18,
                    contentType = "text/html",
                    body = ByteArrayInputStream("<html>challenge</html>".toByteArray()),
                )
            },
            cache = ArtifactCache(directory),
        )

        val result = downloader.downloadDynamic(
            ResolvedDownloadRequest(ArtifactSourceKind.R2, "https://assets.r2.dev/item.zip"),
            destination,
        ) as DynamicArchiveDownloadResult.Failed

        assertEquals("dynamic_archive_non_binary", result.reasonCode)
        assertFalse(destination.exists())
        assertFalse(destination.resolveSibling("${destination.name}.part").exists())
    }

    @Test
    fun `dynamic downloader rejects a content length mismatch and leaves no working file`() = runBlocking {
        val directory = Files.createTempDirectory("dynamic-download-length").toFile()
        val destination = directory.resolve("dynamic.zip")
        val bytes = zipBytes(listOf("app.apk" to byteArrayOf(1, 2, 3)))
        val downloader = ArtifactDownloader(
            transport = ArtifactTransport { _, _ ->
                ArtifactTransportResponse(
                    statusCode = 200,
                    contentLength = bytes.size.toLong() + 1L,
                    contentType = "application/zip",
                    body = ByteArrayInputStream(bytes),
                )
            },
            cache = ArtifactCache(directory),
        )

        val result = downloader.downloadDynamic(
            ResolvedDownloadRequest(ArtifactSourceKind.R2, "https://assets.r2.dev/item.zip"),
            destination,
        ) as DynamicArchiveDownloadResult.Failed

        assertEquals("dynamic_archive_incomplete", result.reasonCode)
        assertTrue(result.retryable)
        assertFalse(destination.exists())
        assertFalse(destination.resolveSibling("${destination.name}.part").exists())
    }

    @Test
    fun `downloader fails closed when the transport returns a zero byte read`() = runBlocking {
        val directory = Files.createTempDirectory("artifact-download-zero-read").toFile()
        val archive = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        val manifest = manifestFor(archive, byteArrayOf(9))
        val cache = ArtifactCache(directory)
        val downloader = ArtifactDownloader(
            transport = ArtifactTransport { _, _ ->
                ArtifactTransportResponse(
                    statusCode = 200,
                    contentLength = archive.size.toLong(),
                    contentType = "application/zip",
                    body = object : InputStream() {
                        override fun read(): Int = 0

                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
                    },
                )
            },
            cache = cache,
        )

        val result = downloader.download(
            manifest,
            ResolvedDownloadRequest(ArtifactSourceKind.R2, "https://assets.r2.dev/item.zip"),
        ) as ArtifactDownloadResult.Failed

        assertEquals("download_io_failed", result.failure.reasonCode)
        assertTrue(result.failure.retryable)
        assertEquals(0L, result.partialBytes)
    }

    @Test
    fun `resumed downloader rejects a successful response that is not partial content`() = runBlocking {
        val directory = Files.createTempDirectory("artifact-download-range").toFile()
        val bytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 5, 6)
        val manifest = manifestFor(bytes, byteArrayOf(9))
        val cache = ArtifactCache(directory)
        val paths = cache.paths(manifest)
        paths.archivePart.writeBytes(bytes.copyOfRange(0, 3))
        cache.writeResumeMetadata(manifest, ArtifactSourceKind.R2.wireName)
        val downloader = ArtifactDownloader(
            transport = ArtifactTransport { _, rangeStart ->
                assertEquals(3L, rangeStart)
                ArtifactTransportResponse(
                    statusCode = 201,
                    contentLength = 3,
                    contentType = "application/zip",
                    body = ByteArrayInputStream(bytes.copyOfRange(3, bytes.size)),
                )
            },
            cache = cache,
        )

        val result = downloader.download(
            manifest,
            ResolvedDownloadRequest(ArtifactSourceKind.R2, "https://assets.r2.dev/item.zip"),
        ) as ArtifactDownloadResult.Failed

        assertEquals("download_range_response_invalid", result.failure.reasonCode)
        assertFalse(paths.archivePart.exists())
        assertFalse(paths.resumeMetadata.exists())
    }

    @Test
    fun `resumed downloader rejects a partial response for the wrong byte range`() = runBlocking {
        val directory = Files.createTempDirectory("artifact-download-range-header").toFile()
        val bytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 5, 6)
        val manifest = manifestFor(bytes, byteArrayOf(9))
        val cache = ArtifactCache(directory)
        val paths = cache.paths(manifest)
        paths.archivePart.writeBytes(bytes.copyOfRange(0, 3))
        cache.writeResumeMetadata(manifest, ArtifactSourceKind.R2.wireName)
        val downloader = ArtifactDownloader(
            transport = ArtifactTransport { _, rangeStart ->
                assertEquals(3L, rangeStart)
                ArtifactTransportResponse(
                    statusCode = 206,
                    contentLength = 3,
                    contentType = "application/zip",
                    body = ByteArrayInputStream(bytes.copyOfRange(3, bytes.size)),
                    contentRangeStartBytes = 2L,
                    contentRangeTotalBytes = bytes.size.toLong(),
                )
            },
            cache = cache,
        )

        val result = downloader.download(
            manifest,
            ResolvedDownloadRequest(ArtifactSourceKind.R2, "https://assets.r2.dev/item.zip"),
        ) as ArtifactDownloadResult.Failed

        assertEquals("download_range_response_invalid", result.failure.reasonCode)
        assertFalse(paths.archivePart.exists())
        assertFalse(paths.resumeMetadata.exists())
    }

    @Test
    fun `incomplete resumed downloader clears an unsatisfiable range response`() = runBlocking {
        val directory = Files.createTempDirectory("artifact-download-range-416").toFile()
        val bytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 5, 6)
        val manifest = manifestFor(bytes, byteArrayOf(9))
        val cache = ArtifactCache(directory)
        val paths = cache.paths(manifest)
        paths.archivePart.writeBytes(bytes.copyOfRange(0, 3))
        cache.writeResumeMetadata(manifest, ArtifactSourceKind.R2.wireName)
        val downloader = ArtifactDownloader(
            transport = ArtifactTransport { _, rangeStart ->
                assertEquals(3L, rangeStart)
                ArtifactTransportResponse(
                    statusCode = 416,
                    contentLength = 0L,
                    contentType = "text/plain",
                    body = ByteArrayInputStream(ByteArray(0)),
                )
            },
            cache = cache,
        )

        val result = downloader.download(
            manifest,
            ResolvedDownloadRequest(ArtifactSourceKind.R2, "https://assets.r2.dev/item.zip"),
        ) as ArtifactDownloadResult.Failed

        assertEquals("download_range_response_invalid", result.failure.reasonCode)
        assertTrue(result.failure.retryable)
        assertFalse(paths.archivePart.exists())
        assertFalse(paths.resumeMetadata.exists())
    }

    @Test
    fun `downloader resumes same source without persisting url or cookie`() = runBlocking {
        val directory = Files.createTempDirectory("artifact-download-resume").toFile()
        val bytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 5, 6)
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
        val bytes = ByteArray(32) { it.toByte() }.also {
            it[0] = 0x50
            it[1] = 0x4b
            it[2] = 0x03
            it[3] = 0x04
        }
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
            url = "https://zip1.webgetstore.com/2026/8/19/archive.zip?short-lived-token",
            userAgent = "Android System WebView",
            mimeType = "application/zip",
        )
        val host = object : LanzouWebViewHost {
            override fun start(
                shareUrl: String,
                onDownload: (ResolvedDownloadRequest) -> Unit,
                onFailure: (com.ninepointnine.helper.domain.artifact.ArtifactFailure) -> Unit,
            ) {
                onDownload(request)
                onFailure(
                    com.ninepointnine.helper.domain.artifact.ArtifactFailure(
                        phase = com.ninepointnine.helper.domain.artifact.ArtifactFailurePhase.SOURCE_RESOLUTION,
                        sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                        reasonCode = "late_failure",
                        retryable = false,
                    ),
                )
            }

            override fun stopAndDestroy() {
                if (destroyed.incrementAndGet() == 1) error("webview_already_destroyed")
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
