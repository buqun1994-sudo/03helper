package com.ninepointnine.helper.data.artifact

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.ninepointnine.helper.data.download.ArtifactCache
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.InstallerSelfIdentity
import com.ninepointnine.helper.domain.device.InstallableArtifact
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** UI-facing request for an icon whose APK identity is already known or can be resolved locally. */
data class ApkIconRequest(
    val componentId: String,
    val packageName: String? = null,
    val certificateSha256: String? = null,
    val apkSha256: String? = null,
    val versionCode: Long? = null,
    /** A live car inventory row should prefer the icon captured from its installed APK. */
    val preferPersisted: Boolean = false,
    val certificateSha256s: Set<String> = certificateSha256?.let(::setOf).orEmpty(),
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
) {
    private val packageManager = context.applicationContext.packageManager
    private val bitmapCache = ConcurrentHashMap<String, Bitmap>()
    private val persistentRoot = File(context.applicationContext.filesDir, APK_ICON_CACHE_DIRECTORY).apply {
        require(mkdirs() || isDirectory) { "apk_icon_cache_unavailable" }
    }

    suspend fun loadIcons(
        requests: List<ApkIconRequest>,
        manifests: List<ArtifactManifest> = emptyList(),
    ): Map<String, Bitmap> = withContext(Dispatchers.IO) {
        if (requests.isEmpty()) return@withContext emptyMap()
        artifactCache.withEphemeralPublicApkCandidates { candidates ->
            val files = buildList {
                addAll(candidates)
                manifests.forEach { manifest ->
                    artifactCache.paths(manifest).apk.takeIf(File::isFile)?.let(::add)
                }
            }
                .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
            requests.mapNotNull { request -> loadRequestIcon(request, files) }.toMap()
        }
    }

    /** Resolves one icon without allowing a stale cache entry to outrank a newer APK. */
    private fun loadRequestIcon(request: ApkIconRequest, files: List<File>): Pair<String, Bitmap>? {
        // A verified current APK is the strongest visual source. Persisted
        // bytes are consulted only after that exact candidate is absent, so a
        // previous version can never mask a newly extracted logo.
        val candidate = findCandidate(request, files)
        if (candidate != null) {
            // A file can be replaced in place without changing its length or
            // timestamp. Include the bytes in the cache key so an APK icon
            // can never survive a content replacement.
            val digest = runCatching { sha256(candidate.file) }.getOrNull()
            if (digest != null) {
                val exactRequest = request.copy(
                    packageName = candidate.metadata.packageName,
                    certificateSha256 = candidate.metadata.certificateSha256s.sorted().first(),
                    apkSha256 = digest,
                    versionCode = candidate.metadata.version.code,
                    preferPersisted = true,
                )
                loadPersistedIcon(exactRequest)?.let { return request.componentId to it }
                val key = cacheKey(request.componentId, candidate.file, candidate.metadata, digest)
                val bitmap = bitmapCache[key] ?: loadBitmap(candidate.file)?.also { loaded ->
                    bitmapCache[key] = loaded
                }
                if (bitmap != null) {
                    // Preview APKs must establish the same durable cache as
                    // APKs prepared for installation. The exact request above
                    // makes this write a no-op on a same-version/same-digest hit.
                    writePersistedIcon(
                        CacheIdentity(
                            componentId = request.componentId,
                            packageName = candidate.metadata.packageName,
                            certificateSha256 = candidate.metadata.certificateSha256s.sorted().first().lowercase(),
                            versionCode = candidate.metadata.version.code,
                            apkSha256 = digest.lowercase(),
                        ),
                        bitmap,
                    )
                    return request.componentId to bitmap
                }
            }
        }
        loadPersistedIcon(request)?.let { return request.componentId to it }
        return loadInstalledIcon(request)?.let { request.componentId to it }
    }

    /** Persists icons from APKs whose manifest identity has already been verified. */
    suspend fun persistIcons(artifacts: List<InstallableArtifact>) = withContext(Dispatchers.IO) {
        artifacts.forEach { artifact ->
            val file = artifact.apkFile ?: return@forEach
            if (!file.isFile) return@forEach
            val metadata = runCatching { metadataReader.read(file) }.getOrNull() ?: return@forEach
            if (metadata.packageName != artifact.manifest.packageName ||
                !artifact.manifest.matchesCertificates(metadata.certificateSha256s) ||
                metadata.version.code != artifact.manifest.apkVersion.code ||
                runCatching { sha256(file) }.getOrNull()?.equals(artifact.manifest.apkSha256, ignoreCase = true) != true
            ) return@forEach
            val bitmap = loadBitmap(file) ?: return@forEach
            val identity = CacheIdentity(
                componentId = artifact.manifest.componentId,
                packageName = metadata.packageName,
                certificateSha256 = artifact.manifest.certificateSha256.lowercase(),
                versionCode = metadata.version.code,
                apkSha256 = artifact.manifest.apkSha256.lowercase(),
            )
            writePersistedIcon(identity, bitmap)
        }
    }

    private fun findCandidate(request: ApkIconRequest, files: List<File>): Candidate? {
        if (request.packageName.isNullOrBlank() || request.apkSha256.isNullOrBlank() ||
            request.versionCode == null || request.certificateSha256s.isEmpty()) return null
        return files.asSequence().mapNotNull { file ->
            val metadata = runCatching { metadataReader.read(file) }.getOrNull() ?: return@mapNotNull null
            if (metadata.packageName != request.packageName || metadata.version.code != request.versionCode ||
                metadata.certificateSha256s.map { it.lowercase() }.toSet() !=
                request.certificateSha256s.map { it.lowercase() }.toSet() ||
                runCatching { sha256(file) }.getOrNull()?.equals(request.apkSha256, ignoreCase = true) != true
            ) return@mapNotNull null
            Candidate(file, metadata)
        }.firstOrNull()
    }

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
        if (!InstallerSelfIdentity.isSelfComponentId(request.componentId) &&
            request.certificateSha256s.isEmpty()) return null
        val applicationInfo = runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return null
        val packageInfo = if (request.certificateSha256s.isNotEmpty() || request.versionCode != null) {
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            runCatching { packageManager.getPackageInfo(packageName, flags) }.getOrNull()
                ?: return null
        } else {
            null
        }
        request.versionCode?.let { expectedVersionCode ->
            val actualVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo?.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo?.versionCode?.toLong()
            }
            if (actualVersionCode != expectedVersionCode) return null
        }
        if (request.certificateSha256s.isNotEmpty()) {
            val info = packageInfo ?: return null
            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners?.toList().orEmpty()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.toList().orEmpty()
            }
            val actual = signatures.map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }.toSet()
            if (actual != request.certificateSha256s.map { it.lowercase() }.toSet()) return null
        }
        if (request.apkSha256 != null && !request.preferPersisted) return null
        val drawable = applicationInfo.loadIcon(packageManager)
        val width = drawable.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(MAX_ICON_EDGE) ?: DEFAULT_ICON_EDGE
        val height = drawable.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(MAX_ICON_EDGE) ?: DEFAULT_ICON_EDGE
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
        }
    }

    private fun loadPersistedIcon(request: ApkIconRequest): Bitmap? {
        // A persisted icon is a proof-bound cache, not a generic component
        // fallback. Without the complete APK identity, a previous version's
        // bytes could be shown for a currently unknown installed package.
        if (
            request.packageName.isNullOrBlank() ||
            request.certificateSha256.isNullOrBlank() ||
            request.versionCode == null ||
            request.apkSha256.isNullOrBlank()
        ) {
            return null
        }
        val expectedPackageName = checkNotNull(request.packageName)
        val expectedCertificate = checkNotNull(request.certificateSha256)
        val expectedVersionCode = checkNotNull(request.versionCode)
        val expectedApkSha256 = checkNotNull(request.apkSha256)
        val entries = persistentRoot.listFiles { file -> file.extension == "meta" }.orEmpty()
            .mapNotNull { metaFile ->
                val properties = runCatching { Properties().also { metaFile.inputStream().use(it::load) } }.getOrNull()
                    ?: return@mapNotNull null
                val identity = CacheIdentity(
                    componentId = properties.getProperty("componentId").orEmpty(),
                    packageName = properties.getProperty("packageName").orEmpty(),
                    certificateSha256 = properties.getProperty("certificateSha256").orEmpty(),
                    versionCode = properties.getProperty("versionCode")?.toLongOrNull() ?: return@mapNotNull null,
                    apkSha256 = properties.getProperty("apkSha256").orEmpty(),
                )
                val iconSha256 = properties.getProperty("iconSha256").orEmpty()
                if (identity.componentId != request.componentId ||
                    identity.packageName != expectedPackageName ||
                    !identity.certificateSha256.equals(expectedCertificate, ignoreCase = true) ||
                    identity.versionCode != expectedVersionCode ||
                    !identity.apkSha256.equals(expectedApkSha256, ignoreCase = true) ||
                    !isDigest(identity.certificateSha256) || !isDigest(identity.apkSha256) ||
                    !isDigest(iconSha256)
                ) return@mapNotNull null
                val image = persistentRoot.resolve(metaFile.nameWithoutExtension)
                if (!image.isFile) return@mapNotNull null
                if (runCatching { sha256(image) }.getOrNull()?.equals(iconSha256, ignoreCase = true) != true) {
                    image.delete()
                    metaFile.delete()
                    return@mapNotNull null
                }
                identity to image
            }
            .sortedWith(compareByDescending<Pair<CacheIdentity, File>> { it.first.versionCode }
                .thenByDescending { it.second.lastModified() })
        return entries.firstNotNullOfOrNull { (_, image) ->
            BitmapFactory.decodeFile(image.absolutePath)?.takeIf { bitmap ->
                bitmap.width in 1..MAX_ICON_EDGE && bitmap.height in 1..MAX_ICON_EDGE
            } ?: run {
                image.delete()
                null
            }
        }
    }

    private fun writePersistedIcon(identity: CacheIdentity, bitmap: Bitmap) {
        val baseName = "${hashKey(identity.cacheKey)}.png"
        val image = persistentRoot.resolve(baseName)
        val metadata = persistentRoot.resolve("$baseName.meta")
        val temporary = persistentRoot.resolve(".$baseName.part")
        val temporaryMetadata = persistentRoot.resolve(".$baseName.meta.part")
        try {
            FileOutputStream(temporary).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            val iconSha256 = sha256(temporary)
            Properties().apply {
                setProperty("componentId", identity.componentId)
                setProperty("packageName", identity.packageName)
                setProperty("certificateSha256", identity.certificateSha256)
                setProperty("versionCode", identity.versionCode.toString())
                setProperty("apkSha256", identity.apkSha256)
            }.also { properties ->
                properties.setProperty("iconSha256", iconSha256)
                temporaryMetadata.outputStream().use { output -> properties.store(output, null) }
            }
            moveReplacing(temporary, image)
            moveReplacing(temporaryMetadata, metadata)
        } catch (_: Exception) {
            temporary.delete()
            temporaryMetadata.delete()
        }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun cacheKey(componentId: String, file: File, metadata: ApkMetadata, digest: String): String = listOf(
        componentId,
        metadata.packageName,
        metadata.version.code,
        metadata.certificateSha256s.sorted().joinToString(","),
        digest,
    ).joinToString("|")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) throw IOException("apk_icon_digest_zero_read")
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun hashKey(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun isDigest(value: String): Boolean = value.matches(SHA256_PATTERN)

    private data class Candidate(
        val file: File,
        val metadata: ApkMetadata,
    )

    private data class CacheIdentity(
        val componentId: String,
        val packageName: String,
        val certificateSha256: String,
        val versionCode: Long,
        val apkSha256: String,
    ) {
        val cacheKey: String
            get() = listOf(componentId, packageName, certificateSha256, versionCode, apkSha256).joinToString("|")
    }

    private companion object {
        const val APK_ICON_CACHE_DIRECTORY = "03-app-apk-icon-cache"
        const val MAX_ICON_EDGE = 256
        const val DEFAULT_ICON_EDGE = 128
        val SHA256_PATTERN = Regex("^[a-fA-F0-9]{64}$")
    }
}
