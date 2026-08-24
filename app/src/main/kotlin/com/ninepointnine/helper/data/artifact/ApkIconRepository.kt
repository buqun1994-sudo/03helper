package com.ninepointnine.helper.data.artifact

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import com.ninepointnine.helper.data.download.ArtifactCache
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactReleaseTrack
import com.ninepointnine.helper.domain.artifact.InstallerComponentTrustRegistry
import com.ninepointnine.helper.domain.artifact.InstallerSelfIdentity
import com.ninepointnine.helper.domain.artifact.InstallerPublisherTrustRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** UI-facing request for an icon whose APK identity is already known or can be resolved locally. */
data class ApkIconRequest(
    val componentId: String,
    val packageName: String? = null,
    val certificateSha256: String? = null,
)

/**
 * Reads application icons from APK files that the installer has already made
 * available locally. It never falls back to a product-name drawable and never
 * downloads an APK solely to draw an icon.
 */
class ApkIconRepository(
    context: Context,
    private val artifactCache: ArtifactCache,
    private val metadataReader: ApkMetadataReader = AndroidApkMetadataReader(context.applicationContext),
    /** The active catalog channel; null is used by isolated tests/legacy callers. */
    private val preferredTrack: ArtifactReleaseTrack? = null,
) {
    private val packageManager = context.applicationContext.packageManager
    private val bitmapCache = ConcurrentHashMap<String, Bitmap>()

    suspend fun loadIcons(
        requests: List<ApkIconRequest>,
        manifests: List<ArtifactManifest> = emptyList(),
    ): Map<String, Bitmap> = withContext(Dispatchers.IO) {
        if (requests.isEmpty()) return@withContext emptyMap()
        val files = buildList {
            addAll(artifactCache.publicApkCandidates())
            manifests.forEach { manifest ->
                artifactCache.paths(manifest).apk.takeIf(File::isFile)?.let(::add)
            }
        }.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        requests.mapNotNull { request ->
            findCandidate(request, files)?.let { candidate ->
                // A file can be replaced in place without changing its length or
                // timestamp. Include the bytes in the cache key so an APK icon
                // can never survive a content replacement.
                val digest = runCatching { sha256(candidate.file) }.getOrNull()
                val key = digest?.let { cacheKey(request.componentId, candidate.file, candidate.metadata, it) }
                val bitmap = key?.let(bitmapCache::get)
                    ?: loadBitmap(candidate.file)?.also { loaded -> key?.let { bitmapCache[it] = loaded } }
                bitmap?.let { request.componentId to it }
            } ?: loadInstalledIcon(request)?.let { bitmap -> request.componentId to bitmap }
        }.toMap()
    }

    private fun findCandidate(
        request: ApkIconRequest,
        files: List<File>,
    ): Candidate? = files.asSequence()
        .mapNotNull { file ->
            val metadata = runCatching { metadataReader.read(file) }.getOrNull() ?: return@mapNotNull null
            if (request.packageName != null && metadata.packageName != request.packageName) return@mapNotNull null
            if (request.certificateSha256 != null && metadata.certificateSha256s.none { digest ->
                    digest.equals(request.certificateSha256, ignoreCase = true)
                }
            ) return@mapNotNull null

            val identity = if (request.packageName != null && request.certificateSha256 != null) {
                InstallerPublisherTrustRegistry.identitiesFor(request.componentId).firstOrNull { trusted ->
                    trusted.packageName == metadata.packageName &&
                        trusted.certificateSha256.equals(request.certificateSha256, ignoreCase = true)
                }
            } else {
                InstallerPublisherTrustRegistry.identitiesFor(request.componentId).firstOrNull { trusted ->
                    trusted.packageName == metadata.packageName && metadata.certificateSha256s.any { digest ->
                        digest.equals(trusted.certificateSha256, ignoreCase = true)
                    }
                }
            }
            // Dynamic components without a local trust identity intentionally
            // use no icon until their APK manifest supplies an exact identity.
            identity
                ?.takeIf { isTrackAllowed(request.componentId, it.track) }
                ?.let { Candidate(file, metadata, it) }
        }
        .sortedWith(
            compareByDescending<Candidate> { trackPriority(it.identity.track) }
                .thenByDescending { it.metadata.version.code }
                .thenByDescending { it.file.lastModified() }
                .thenBy { it.file.name },
        )
        .firstOrNull()

    private fun loadBitmap(file: File): Bitmap? {
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: return null
        val applicationInfo = info.applicationInfo ?: return null
        applicationInfo.sourceDir = file.absolutePath
        applicationInfo.publicSourceDir = file.absolutePath
        val drawable = applicationInfo.loadIcon(packageManager)
        val width = drawable.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(MAX_ICON_EDGE) ?: DEFAULT_ICON_EDGE
        val height = drawable.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(MAX_ICON_EDGE) ?: DEFAULT_ICON_EDGE
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
        }
    }

    private fun loadInstalledIcon(request: ApkIconRequest): Bitmap? {
        val packageName = request.packageName?.takeIf { it.isNotBlank() } ?: return null
        // Only use an installed package when its identity is one of the
        // helper's own or already-trusted component identities. This keeps a
        // maintenance row from turning an arbitrary package name into UI data.
        val trusted = request.componentId == InstallerSelfIdentity.COMPONENT_ID ||
            request.componentId == InstallerSelfIdentity.LEGACY_COMPONENT_ID ||
            InstallerPublisherTrustRegistry.identitiesFor(request.componentId).any { it.packageName == packageName }
        if (!trusted) return null
        val applicationInfo = runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return null
        request.certificateSha256?.let { expected ->
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val packageInfo = runCatching { packageManager.getPackageInfo(packageName, flags) }.getOrNull()
                ?: return null
            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners?.toList().orEmpty()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures?.toList().orEmpty()
            }
            if (signatures.none { signature ->
                    val digest = MessageDigest.getInstance("SHA-256")
                        .digest(signature.toByteArray())
                        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                    digest.equals(expected, ignoreCase = true)
                }
            ) return null
        }
        val drawable = applicationInfo.loadIcon(packageManager)
        val width = drawable.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(MAX_ICON_EDGE) ?: DEFAULT_ICON_EDGE
        val height = drawable.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(MAX_ICON_EDGE) ?: DEFAULT_ICON_EDGE
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
        }
    }

    private fun cacheKey(componentId: String, file: File, metadata: ApkMetadata, digest: String): String = listOf(
        componentId,
        metadata.packageName,
        metadata.version.code,
        metadata.certificateSha256s.sorted().joinToString(","),
        digest,
    ).joinToString("|")

    private fun isTrackAllowed(componentId: String, track: ArtifactReleaseTrack): Boolean = when {
        preferredTrack == null -> true
        track == preferredTrack -> true
        // The current file-manager release is still the audited Fossify Debug
        // artifact. Its staging config is explicitly allowed to reuse that
        // identity until a staging certificate is published.
        componentId == InstallerComponentTrustRegistry.FILE_MANAGER_COMPONENT_ID &&
            preferredTrack == ArtifactReleaseTrack.STAGING &&
            track == ArtifactReleaseTrack.DEBUG -> true
        else -> false
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun trackPriority(track: ArtifactReleaseTrack): Int = when (track) {
        ArtifactReleaseTrack.STAGING -> 3
        ArtifactReleaseTrack.RELEASE -> 2
        ArtifactReleaseTrack.DEBUG -> 1
    }

    private data class Candidate(
        val file: File,
        val metadata: ApkMetadata,
        val identity: com.ninepointnine.helper.domain.artifact.TrustedArtifactIdentity,
    )

    private companion object {
        const val MAX_ICON_EDGE = 256
        const val DEFAULT_ICON_EDGE = 128
    }
}
