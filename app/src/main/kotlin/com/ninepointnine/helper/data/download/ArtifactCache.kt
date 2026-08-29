package com.ninepointnine.helper.data.download

import android.annotation.TargetApi
import android.content.ContentResolver
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
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
 * Owns the installer's temporary working files and APKs published to the
 * user's public Download directory. APKs in the public directory are the only
 * cross-session reuse source; everything under [root] is disposable working
 * state for the current operation.
 */
class ArtifactCache(
    private val root: File,
    private val publicDownloadRoot: File = root,
    private val contentResolver: ContentResolver? = null,
) {
    private val mediaStoreEnabled: Boolean = contentResolver != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    private val candidateLock = Any()
    @Volatile
    private var candidateSnapshot: List<File>? = null

    /** Evaluated on demand because Android 9 permission may be granted after construction. */
    val publicDirectoryAvailable: Boolean
        get() = mediaStoreEnabled || ensureDirectory(publicDownloadRoot)

    init {
        require(root.mkdirs() || root.isDirectory) { "artifact_cache_unavailable" }
        // Versions before the public-Download-only policy left complete APKs
        // under this private directory. Remove that legacy source once the
        // cache owner starts so an upgrade cannot silently reuse it.
        if (!rootsShareDirectory()) {
            root.resolve(LEGACY_VERIFIED_APK_DIRECTORY).deleteRecursively()
        }
        // A process can be killed after preparation has produced a private
        // APK or while a MediaStore item is being copied. Those files are not
        // a reusable source. Keep only a valid fixed-download resume pair.
        clearPrivateApkCopies()
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

    /**
     * Returns one immutable-in-use inventory snapshot of locally available APKs.
     * A refresh is explicit: callers that begin a new install/maintenance visit
     * use [refreshPublicApkCandidates], while all phases in that visit reuse the
     * same snapshot and never race a MediaStore copy operation.
     */
    fun publicApkCandidates(forceRefresh: Boolean = false): List<File> = synchronized(candidateLock) {
        if (!forceRefresh) {
            candidateSnapshot?.let { return@synchronized it }
        }
        val next = scanPublicApkCandidates()
        candidateSnapshot = next
        next
    }

    fun refreshPublicApkCandidates(): List<File> = publicApkCandidates(forceRefresh = true)

    fun invalidatePublicApkCandidates() {
        synchronized(candidateLock) { candidateSnapshot = null }
    }

    /** Executes a candidate read while the MediaStore snapshot cannot be replaced. */
    fun <T> withPublicApkCandidates(
        forceRefresh: Boolean = false,
        block: (List<File>) -> T,
    ): T = synchronized(candidateLock) {
        block(publicApkCandidates(forceRefresh))
    }

    /**
     * Reads public APKs for a short-lived consumer such as icon extraction.
     * MediaStore rows must be copied to a File for the APK parser, but those
     * copies are unrelated to an active installation preparation and must not
     * share its candidate snapshot or survive this callback.
     */
    fun <T> withEphemeralPublicApkCandidates(
        block: (List<File>) -> T,
    ): T = synchronized(candidateLock) {
        if (!mediaStoreEnabled) {
            return@synchronized block(
                publicDownloadRoot.listFiles()
                    .orEmpty()
                    .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                    .sortedWith(compareBy<File> { it.name }.thenByDescending { it.lastModified() }),
            )
        }
        val ephemeralRoot = root.resolve(EPHEMERAL_CANDIDATE_DIRECTORY)
        try {
            // A process can be killed between the copy and callback. Remove
            // only this namespace before and after the ownership window so a
            // later icon read never observes stale private APK bytes.
            ephemeralRoot.listFiles().orEmpty().forEach { it.deleteRecursively() }
            val candidates = scanPublicApkCandidates(
                candidateRoot = ephemeralRoot,
                candidatePrefix = EPHEMERAL_CANDIDATE_PREFIX,
            )
            block(candidates)
        } finally {
            // The callback is the complete ownership window for these parser
            // inputs. A later UI recomposition therefore cannot accumulate APK
            // copies in the installer's private cache.
            ephemeralRoot.deleteRecursively()
        }
    }

    fun publicDownloadDirectory(): File = publicDownloadRoot

    /** Private working root used only for the current preparation operation. */
    fun privateWorkingDirectory(): File = root

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
            val published = try {
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
            if (published) invalidatePublicApkCandidates()
            return published
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
            invalidatePublicApkCandidates()
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
        invalidatePublicApkCandidates()
        clearPrivateCache()
        if (!rootsShareDirectory()) {
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
        } else {
            // Tests and pre-Q callers may intentionally use one directory for
            // both roots. In that configuration the directory is public, so
            // only the installer's managed APK names are removable.
            root.listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.startsWith(MANAGED_APK_PREFIX) }
                .forEach { it.delete() }
        }
    }

    /** Removes only the installer's private working files after an install run. */
    fun clearPrivateCache() {
        invalidatePublicApkCandidates()
        cleanupPrivateWorkingFiles(preserveResumableArchives = false)
    }

    /**
     * Removes private APK material while retaining fixed-download resume
     * pairs. This is safe to call when a preparation attempt is interrupted;
     * public Download files are never removed.
     */
    fun clearPrivateApkCopies() {
        invalidatePublicApkCandidates()
        cleanupPrivateWorkingFiles(preserveResumableArchives = true)
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

    private fun scanPublicApkCandidates(
        candidateRoot: File = root,
        candidatePrefix: String = PUBLIC_CANDIDATE_PREFIX,
    ): List<File> = runCatching {
        val public = if (mediaStoreEnabled) {
            mediaStoreApkCandidates(candidateRoot, candidatePrefix)
        } else {
            publicDownloadRoot.listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
        }
        public
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
            .sortedWith(compareBy<File> { it.name }.thenByDescending { it.lastModified() })
    }.getOrDefault(emptyList())

    @TargetApi(Build.VERSION_CODES.Q)
    private fun mediaStoreApkCandidates(
        candidateRoot: File,
        candidatePrefix: String,
    ): List<File> = runCatching {
        val resolver = contentResolver ?: return@runCatching emptyList()
        if (!ensureDirectory(candidateRoot)) return@runCatching emptyList()
        val uris = mediaStoreApkUris()
        uris.mapNotNull { uri ->
            runCatching {
                val name = displayName(uri)
                if (!name.endsWith(".apk", ignoreCase = true)) return@runCatching null
                // Provider-controlled URI segments must never become path
                // components. A stable digest also lets a later refresh
                // replace the same candidate atomically without trusting the
                // provider's display name.
                val target = candidateRoot.resolve("$candidatePrefix${candidateKey(uri)}.apk")
                val temporary = target.resolveSibling(".${target.name}.part")
                // Always refresh through a private atomic temporary file. A
                // user can replace a MediaStore item without changing its byte
                // length, and a killed copy must never look installable.
                target.delete()
                temporary.delete()
                try {
                    resolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(temporary).use { output -> input.copyTo(output) }
                    } ?: return@runCatching null
                    if (!temporary.isFile || temporary.length() <= 0L) return@runCatching null
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
                    target.takeIf { it.isFile && it.length() > 0L }
                } finally {
                    temporary.delete()
                }
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

    private fun cleanupPrivateWorkingFiles(preserveResumableArchives: Boolean) = synchronized(candidateLock) {
        candidateSnapshot = null
        val sharedRoot = rootsShareDirectory()
        val resumableKeys = if (preserveResumableArchives) {
            root.listFiles()
                .orEmpty()
                .filter { it.isFile && RESUMABLE_PART_PATTERN.matches(it.name) }
                .filter { it.resolveSibling("${it.name}.meta").isFile }
                .mapTo(mutableSetOf()) { it.name.removeSuffix(".zip.part") }
        } else {
            emptySet()
        }
        root.listFiles().orEmpty().forEach { file ->
            cleanupEntry(
                file = file,
                sharedRoot = sharedRoot,
                preserveResumableArchives = preserveResumableArchives,
                resumableKeys = resumableKeys,
            )
        }
    }

    private fun cleanupEntry(
        file: File,
        sharedRoot: Boolean,
        preserveResumableArchives: Boolean,
        resumableKeys: Set<String>,
    ) {
        // A custom public root may live below the private root. Never walk
        // into that subtree while cleaning the installer's workspace.
        if (!sharedRoot && isUnderPublicRoot(file)) return
        if (file.isDirectory) {
            if (file.name == LEGACY_VERIFIED_APK_DIRECTORY ||
                file.name == INSTALLED_VERIFICATION_DIRECTORY
            ) {
                file.deleteRecursively()
                return
            }
            file.listFiles().orEmpty().forEach { child ->
                cleanupEntry(child, sharedRoot, preserveResumableArchives, resumableKeys)
            }
            if (file.listFiles().isNullOrEmpty() && (!sharedRoot || isKnownPrivateDirectory(file))) {
                file.delete()
            }
            return
        }

        val isFixedPart = RESUMABLE_PART_PATTERN.matches(file.name)
        val isFixedMetadata = RESUMABLE_META_PATTERN.matches(file.name)
        val fixedKey = when {
            isFixedPart -> file.name.removeSuffix(".zip.part")
            isFixedMetadata -> file.name.removeSuffix(".zip.part.meta")
            else -> null
        }
        if (preserveResumableArchives && fixedKey != null && fixedKey in resumableKeys) return

        val delete = if (!sharedRoot) {
            // The private root is application-owned in production.
            true
        } else {
            isKnownPrivateFile(file.name)
        }
        if (delete) file.delete()
    }

    private fun isKnownPrivateDirectory(file: File): Boolean =
        file.name == LEGACY_VERIFIED_APK_DIRECTORY ||
            file.name == INSTALLED_VERIFICATION_DIRECTORY ||
            file.name == EPHEMERAL_CANDIDATE_DIRECTORY ||
            file.name.startsWith("prepare-", ignoreCase = true)

    private fun isKnownPrivateFile(name: String): Boolean =
        name.startsWith(".public-candidate-", ignoreCase = true) ||
            name.startsWith(".icon-candidate-", ignoreCase = true) ||
            name.startsWith("prepare-", ignoreCase = true) ||
            name.endsWith(".apk.part", ignoreCase = true) ||
            name.endsWith(".zip", ignoreCase = true) ||
            name.endsWith(".zip.part", ignoreCase = true) ||
            name.endsWith(".zip.part.meta", ignoreCase = true)

    private fun rootsShareDirectory(): Boolean = runCatching {
        root.canonicalFile == publicDownloadRoot.canonicalFile
    }.getOrDefault(root.absoluteFile == publicDownloadRoot.absoluteFile)

    private fun isUnderPublicRoot(file: File): Boolean = runCatching {
        val rootPath = root.canonicalPath
        val publicPath = publicDownloadRoot.canonicalPath
        val filePath = file.canonicalPath
        // Only a public directory nested inside the private root is excluded
        // from private cleanup. If the private root itself is nested below the
        // public directory, it is still an application-owned workspace and
        // must be cleaned normally.
        if (publicPath == rootPath || !publicPath.startsWith(rootPath + File.separator)) {
            false
        } else {
            filePath == publicPath || filePath.startsWith(publicPath + File.separator)
        }
    }.getOrDefault(false)

    private fun candidateKey(uri: android.net.Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }.take(32)
    }

    private companion object {
        const val MANAGED_APK_PREFIX = "03helper-"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val PUBLIC_CANDIDATE_PREFIX = ".public-candidate-"
        const val EPHEMERAL_CANDIDATE_DIRECTORY = ".icon-candidates"
        const val EPHEMERAL_CANDIDATE_PREFIX = ".icon-candidate-"
        const val LEGACY_VERIFIED_APK_DIRECTORY = "verified-apks"
        const val INSTALLED_VERIFICATION_DIRECTORY = "installed-verification"
        val RESUMABLE_PART_PATTERN = Regex("^[0-9a-f]{24}\\.zip\\.part$", RegexOption.IGNORE_CASE)
        val RESUMABLE_META_PATTERN = Regex("^[0-9a-f]{24}\\.zip\\.part\\.meta$", RegexOption.IGNORE_CASE)
    }
}
