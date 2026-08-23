package com.tcrrry.helper.data.download

import android.annotation.TargetApi
import android.content.ContentResolver
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import com.tcrrry.helper.domain.artifact.ArtifactManifest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

data class ArtifactCachePaths(
    val archivePart: File,
    val resumeMetadata: File,
    val apkPart: File,
    val apk: File,
)

/**
 * Owns the installer's temporary cache and the APK files published to the
 * user's public Download directory. The two roots intentionally have
 * different cleanup rules: temporary files are disposable, while user-visible
 * APKs are retained for reuse and are never bulk-deleted with other files.
 */
class ArtifactCache(
    private val root: File,
    private val publicDownloadRoot: File = root,
    private val contentResolver: ContentResolver? = null,
) {
    private val mediaStoreEnabled: Boolean = contentResolver != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Evaluated on demand because Android 9 permission may be granted after construction. */
    val publicDirectoryAvailable: Boolean
        get() = mediaStoreEnabled || ensureDirectory(publicDownloadRoot)

    init {
        require(root.mkdirs() || root.isDirectory) { "artifact_cache_unavailable" }
    }

    fun paths(manifest: ArtifactManifest): ArtifactCachePaths {
        val key = stableKey(manifest)
        return ArtifactCachePaths(
            archivePart = root.resolve("$key.zip.part"),
            resumeMetadata = root.resolve("$key.zip.part.meta"),
            apkPart = root.resolve("$key.apk.part"),
            apk = if (mediaStoreEnabled) {
                root.resolve("${key}.apk")
            } else {
                publicDownloadRoot.resolve(managedApkName(manifest))
            },
        )
    }

    /** Returns APK files visible directly in the public Download directory. */
    fun publicApkCandidates(): List<File> = if (mediaStoreEnabled) {
        mediaStoreApkCandidates()
    } else {
        runCatching {
            publicDownloadRoot.listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                .sortedWith(compareBy<File> { it.name }.thenByDescending { it.lastModified() })
        }.getOrDefault(emptyList())
    }

    fun publicDownloadDirectory(): File = publicDownloadRoot

    /** Publishes a verified APK to the user's public Download collection. */
    @TargetApi(Build.VERSION_CODES.Q)
    fun publishApk(manifest: ArtifactManifest, source: File): Boolean {
        if (!mediaStoreEnabled) {
            if (!source.isFile || !ensureDirectory(publicDownloadRoot)) return false
            val target = publicDownloadRoot.resolve(managedApkName(manifest))
            // The source can already be the managed public file when a
            // previous attempt resumed in place. In that case there is
            // nothing to copy and, importantly, no self-overwrite to risk.
            if (runCatching { source.canonicalFile == target.canonicalFile }.getOrDefault(false)) {
                return target.isFile && target.length() == source.length()
            }
            val temporary = publicDownloadRoot.resolve(".${target.name}.part")
            return try {
                Files.copy(
                    source.toPath(),
                    temporary.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                if (!temporary.isFile || temporary.length() != source.length()) {
                    false
                } else {
                    try {
                        Files.move(
                            temporary.toPath(),
                            target.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(
                            temporary.toPath(),
                            target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                    target.isFile && target.length() == source.length()
                }
            } catch (_: Exception) {
                false
            } finally {
                temporary.delete()
            }
        }
        val resolver = contentResolver ?: return false
        if (!source.isFile) return false
        val displayName = managedApkName(manifest)
        var pendingUri: android.net.Uri? = null
        return try {
            val previousUri = findMediaStoreApk(displayName)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, APK_MIME_TYPE)
                put(MediaStore.Downloads.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            pendingUri = uri
            val copiedBytes = resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("public_download_output_unavailable")
            if (copiedBytes != source.length()) {
                throw IOException("public_download_size_mismatch")
            }
            val complete = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            if (resolver.update(uri, complete, null, null) < 1) {
                throw IOException("public_download_publish_failed")
            }
            if (previousUri != null && previousUri != uri) {
                resolver.delete(previousUri, null, null)
            }
            true
        } catch (_: Exception) {
            pendingUri?.let { runCatching { resolver.delete(it, null, null) } }
            false
        }
    }

    fun clearArchive(manifest: ArtifactManifest) {
        val paths = paths(manifest)
        paths.archivePart.delete()
        paths.resumeMetadata.delete()
    }

    fun clearApk(manifest: ArtifactManifest) {
        val paths = paths(manifest)
        paths.apkPart.delete()
        // Verified APKs are deliberately retained in the public Download
        // collection for maintenance and future reuse. On MediaStore builds
        // only the private working copy is disposable; on Android 9 and below
        // paths.apk already points directly at the public directory.
        if (mediaStoreEnabled) {
            paths.apk.delete()
        }
    }

    fun clearArtifact(manifest: ArtifactManifest) {
        clearArchive(manifest)
        clearApk(manifest)
    }

    fun clearAll() {
        root.listFiles()?.forEach { file ->
            file.deleteRecursively()
        }
        if (publicDownloadRoot != root) {
            if (mediaStoreEnabled) {
                runCatching {
                    mediaStoreApkUris()
                        .filter { displayName(it).startsWith(MANAGED_APK_PREFIX) }
                        .forEach { contentResolver?.delete(it, null, null) }
                }
            } else {
                publicDownloadRoot.listFiles()
                    .orEmpty()
                    .filter { it.isFile && it.name.startsWith(MANAGED_APK_PREFIX) }
                    .forEach { it.delete() }
            }
        }
    }

    fun writeResumeMetadata(manifest: ArtifactManifest, sourceKind: String) {
        val paths = paths(manifest)
        paths.resumeMetadata.writeText(
            buildString {
                appendLine("componentId=${manifest.componentId}")
                appendLine("versionCode=${manifest.version.code}")
                appendLine("archiveSizeBytes=${manifest.archiveSizeBytes}")
                appendLine("archiveSha256=${manifest.archiveSha256.lowercase()}")
                appendLine("sourceKind=$sourceKind")
            },
            Charsets.UTF_8,
        )
    }

    fun resumeMetadataMatches(manifest: ArtifactManifest, sourceKind: String): Boolean {
        val paths = paths(manifest)
        if (!paths.archivePart.isFile || !paths.resumeMetadata.isFile) return false
        val expected = mapOf(
            "componentId" to manifest.componentId,
            "versionCode" to manifest.version.code.toString(),
            "archiveSizeBytes" to manifest.archiveSizeBytes.toString(),
            "archiveSha256" to manifest.archiveSha256.lowercase(),
            "sourceKind" to sourceKind,
        )
        val actual = paths.resumeMetadata.readLines(Charsets.UTF_8)
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
            }
            .toMap()
        return actual == expected
    }

    private fun stableKey(manifest: ArtifactManifest): String {
        val input = "${manifest.componentId}:${manifest.version.name}:${manifest.version.code}"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }.take(24)
    }

    private fun managedApkName(manifest: ArtifactManifest): String =
        "$MANAGED_APK_PREFIX${manifest.componentId}-${manifest.apkVersion.code}.apk"

    @TargetApi(Build.VERSION_CODES.Q)
    private fun mediaStoreApkCandidates(): List<File> = runCatching {
        val resolver = contentResolver ?: return@runCatching emptyList()
        val uris = mediaStoreApkUris()
        val activeNames = uris.map { uri -> ".public-candidate-${uri.lastPathSegment ?: displayName(uri)}.apk" }.toSet()
        root.listFiles()
            .orEmpty()
            .filter { it.name.startsWith(".public-candidate-") && it.name !in activeNames }
            .forEach { it.delete() }
        uris.mapNotNull { uri ->
            runCatching {
                val name = displayName(uri)
                if (!name.endsWith(".apk", ignoreCase = true)) return@runCatching null
                val target = root.resolve(".public-candidate-${uri.lastPathSegment ?: name}.apk")
                // Always refresh the private candidate. A user can replace a
                // MediaStore item without changing its byte length, so a
                // length-only cache check could reuse stale content.
                target.delete()
                resolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                } ?: return@runCatching null
                target.takeIf { it.isFile && it.length() > 0L }
            }.getOrNull()
        }.sortedBy { it.name }
    }.getOrDefault(emptyList())

    @TargetApi(Build.VERSION_CODES.Q)
    private fun mediaStoreApkUris(): List<android.net.Uri> {
        val resolver = contentResolver ?: return emptyList()
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.MIME_TYPE,
        )
        return resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Downloads.IS_PENDING} = ?",
            arrayOf("0"),
            "${MediaStore.Downloads.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex).orEmpty()
                    val mime = cursor.getString(mimeIndex).orEmpty()
                    if (mime == APK_MIME_TYPE || name.endsWith(".apk", ignoreCase = true)) {
                        add(android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(idIndex)))
                    }
                }
            }
        }.orEmpty()
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun findMediaStoreApk(name: String): android.net.Uri? =
        mediaStoreApkUris().firstOrNull { displayName(it) == name }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun displayName(uri: android.net.Uri): String = runCatching {
        val resolver = contentResolver ?: return@runCatching ""
        resolver.query(
            uri,
            arrayOf(MediaStore.Downloads.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }.orEmpty()
    }.getOrDefault("")

    private fun ensureDirectory(directory: File): Boolean = runCatching {
        directory.mkdirs() || directory.isDirectory
    }.getOrDefault(false)

    private companion object {
        const val MANAGED_APK_PREFIX = "03helper-"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
