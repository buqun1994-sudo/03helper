package com.ninepointnine.helper.data.artifact

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * A small, identity-bound cache for labels and icons read from a car APK.
 *
 * The car APK itself is never retained here. The device adapter owns the
 * temporary pull and deletes it after [capture] returns; this class keeps only
 * a bounded PNG and a sidecar describing the package, version and remote base
 * APK path that produced it.
 */
data class ThirdPartyApplicationAsset(
    val packageName: String,
    val versionCode: Long?,
    val remoteFilePath: String,
    val displayName: String,
    val iconKey: String?,
)

class ThirdPartyApplicationAssetStore(
    context: Context,
    private val metadataReader: ApkMetadataReader,
) {
    private val packageManager = context.applicationContext.packageManager
    private val root = File(context.applicationContext.filesDir, CACHE_DIRECTORY).apply {
        require(mkdirs() || isDirectory) { "third_party_asset_cache_unavailable" }
    }
    private val bitmapCache = ConcurrentHashMap<String, Bitmap>()

    init {
        // Bound data left by older sessions before the first inventory read.
        trimPersistentCache()
    }

    /**
     * Returns a previously captured label/icon for the exact car base APK.
     * A nullable expected version is intentionally accepted for older package
     * manager responses; the remote path still binds the cache entry.
     */
    fun lookup(
        packageName: String,
        expectedVersionCode: Long?,
        remoteFilePath: String,
    ): ThirdPartyApplicationAsset? {
        if (!isValidIdentity(packageName, expectedVersionCode, remoteFilePath)) return null
        return root.listFiles { file -> file.extension == META_EXTENSION }
            .orEmpty()
            .asSequence()
            .mapNotNull { metaFile -> readEntry(metaFile) }
            .filter { entry ->
                entry.packageName == packageName &&
                    entry.remoteFilePath == remoteFilePath &&
                    (expectedVersionCode == null || entry.versionCode == expectedVersionCode)
            }
            .sortedByDescending { it.modified }
            .map { entry -> entry.toAsset(readIcon(entry)) }
            .firstOrNull()
    }

    /**
     * Reads one pulled APK, validates that it is the package named by the car
     * inventory, and stores only its display label plus a bounded PNG icon.
     * A malformed/changed APK returns null and is never cached.
     */
    fun capture(
        apkFile: File,
        expectedPackageName: String,
        expectedVersionCode: Long?,
        remoteFilePath: String,
    ): ThirdPartyApplicationAsset? {
        if (!apkFile.isFile || !isValidIdentity(expectedPackageName, expectedVersionCode, remoteFilePath)) {
            return null
        }
        val metadata = runCatching { metadataReader.read(apkFile) }.getOrNull() ?: return null
        if (metadata.packageName != expectedPackageName ||
            expectedVersionCode != null && metadata.version.code != expectedVersionCode ||
            metadata.version.code < 0L
        ) {
            return null
        }
        val displayName = normalizeDisplayName(metadata.displayName, expectedPackageName)
        val versionCode = metadata.version.code
        val key = cacheKey(expectedPackageName, versionCode, remoteFilePath)
        val icon = runCatching { loadIconBitmap(apkFile) }.getOrNull()
        val image = root.resolve("$key.$PNG_EXTENSION")
        val meta = root.resolve("$key.$META_EXTENSION")
        val wroteIcon = writeEntry(
            image = image,
            meta = meta,
            packageName = expectedPackageName,
            versionCode = versionCode,
            remoteFilePath = remoteFilePath,
            displayName = displayName,
            icon = icon,
        )
        return ThirdPartyApplicationAsset(
            packageName = expectedPackageName,
            versionCode = versionCode,
            remoteFilePath = remoteFilePath,
            displayName = displayName,
            iconKey = key.takeIf { wroteIcon },
        )
    }

    /** Loads a cache entry after the UI request has supplied its full identity. */
    fun loadIcon(
        iconKey: String?,
        packageName: String,
        expectedVersionCode: Long?,
        remoteFilePath: String,
    ): Bitmap? {
        if (iconKey.isNullOrBlank() || !isValidKey(iconKey) ||
            !isValidIdentity(packageName, expectedVersionCode, remoteFilePath)
        ) return null
        val metaFile = root.resolve("$iconKey.$META_EXTENSION")
        val entry = readEntry(metaFile) ?: return null
        if (entry.packageName != packageName ||
            entry.remoteFilePath != remoteFilePath ||
            expectedVersionCode != null && entry.versionCode != expectedVersionCode
        ) return null
        return readIcon(entry)
    }

    private fun writeEntry(
        image: File,
        meta: File,
        packageName: String,
        versionCode: Long,
        remoteFilePath: String,
        displayName: String,
        icon: Bitmap?,
    ): Boolean {
        val temporaryImage = image.resolveSibling(".${image.name}.part")
        val temporaryMeta = meta.resolveSibling(".${meta.name}.part")
        var wroteIcon = false
        return try {
            if (icon != null) {
                FileOutputStream(temporaryImage).use { output ->
                    check(icon.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
                if (temporaryImage.length() in 1..MAX_ICON_BYTES) {
                    wroteIcon = true
                } else {
                    temporaryImage.delete()
                }
            }
            val properties = Properties().apply {
                setProperty("packageName", packageName)
                setProperty("versionCode", versionCode.toString())
                setProperty("remoteFilePath", remoteFilePath)
                setProperty("displayName", displayName)
                setProperty("iconSha256", if (wroteIcon) sha256(temporaryImage) else "")
            }
            temporaryMeta.outputStream().use { output -> properties.store(output, null) }
            if (wroteIcon) {
                moveReplacing(temporaryImage, image)
            } else {
                image.delete()
            }
            moveReplacing(temporaryMeta, meta)
            bitmapCache.remove(image.nameWithoutExtension)
            trimPersistentCache()
            wroteIcon
        } catch (_: Exception) {
            temporaryImage.delete()
            temporaryMeta.delete()
            false
        } finally {
            temporaryImage.delete()
            temporaryMeta.delete()
        }
    }

    private fun readEntry(metaFile: File): CacheEntry? {
        if (!metaFile.isFile || !isValidKey(metaFile.nameWithoutExtension)) return null
        val properties = runCatching {
            Properties().also { metaFile.inputStream().use(it::load) }
        }.getOrNull() ?: return null
        val packageName = properties.getProperty("packageName").orEmpty()
        val versionCode = properties.getProperty("versionCode")?.toLongOrNull()
        val remoteFilePath = properties.getProperty("remoteFilePath").orEmpty()
        val displayName = normalizeDisplayName(properties.getProperty("displayName"), packageName)
        val iconSha256 = properties.getProperty("iconSha256").orEmpty()
        if (!isValidIdentity(packageName, versionCode, remoteFilePath) ||
            displayName.isBlank() ||
            iconSha256.isNotEmpty() && !isDigest(iconSha256)
        ) return null
        return CacheEntry(
            key = metaFile.nameWithoutExtension,
            packageName = packageName,
            versionCode = versionCode,
            remoteFilePath = remoteFilePath,
            displayName = displayName,
            iconSha256 = iconSha256,
            modified = metaFile.lastModified(),
            image = metaFile.parentFile?.resolve(metaFile.nameWithoutExtension + "." + PNG_EXTENSION),
        )
    }

    private fun readIcon(entry: CacheEntry): Bitmap? {
        val image = entry.image ?: return null
        if (entry.iconSha256.isBlank() || !image.isFile || image.length() !in 1..MAX_ICON_BYTES) return null
        bitmapCache[entry.key]?.let { return it }
        if (!runCatching { sha256(image).equals(entry.iconSha256, ignoreCase = true) }.getOrDefault(false)) {
            image.delete()
            return null
        }
        val bitmap = BitmapFactory.decodeFile(image.absolutePath)?.takeIf { decoded ->
            decoded.width in 1..MAX_ICON_EDGE && decoded.height in 1..MAX_ICON_EDGE
        } ?: run {
            image.delete()
            null
        }
        if (bitmap != null) cacheBitmap(entry.key, bitmap)
        return bitmap
    }

    private fun cacheBitmap(key: String, bitmap: Bitmap) {
        bitmapCache[key] = bitmap
        while (bitmapCache.size > MAX_BITMAP_CACHE_ENTRIES) {
            val evictionKey = bitmapCache.keys.firstOrNull { it != key } ?: break
            bitmapCache.remove(evictionKey)
        }
    }

    /** Removes stale/orphaned entries while retaining the newest bounded set. */
    private fun trimPersistentCache() {
        val metadataFiles = root.listFiles { file -> file.extension == META_EXTENSION }.orEmpty()
        val entries = metadataFiles
            .mapNotNull(::readEntry)
            .sortedWith(compareByDescending<CacheEntry> { it.modified }.thenBy { it.key })
        val keptKeys = HashSet<String>(MAX_CACHE_ENTRIES)
        var keptBytes = 0L
        entries.forEach { entry ->
            val iconBytes = entry.image?.takeIf(File::isFile)?.length() ?: 0L
            val withinCount = keptKeys.size < MAX_CACHE_ENTRIES
            val withinBytes = iconBytes == 0L || keptBytes <= MAX_CACHE_BYTES - iconBytes
            if (withinCount && withinBytes) {
                keptKeys += entry.key
                keptBytes += iconBytes
            } else {
                deleteEntry(entry)
            }
        }
        // A failed write or an interrupted process can leave an orphan PNG or
        // temporary file behind; neither is a usable cache entry.
        root.listFiles().orEmpty().forEach { file ->
            val key = file.nameWithoutExtension
            if ((file.extension == PNG_EXTENSION || file.extension == META_EXTENSION) &&
                key !in keptKeys ||
                file.name.endsWith(".part")
            ) {
                file.delete()
            }
        }
        bitmapCache.keys.removeIf { it !in keptKeys }
    }

    private fun deleteEntry(entry: CacheEntry) {
        entry.image?.delete()
        root.resolve("${entry.key}.$META_EXTENSION").delete()
        bitmapCache.remove(entry.key)
    }

    private fun loadIconBitmap(apkFile: File): Bitmap? {
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val packageInfo = packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return null
        val applicationInfo = packageInfo.applicationInfo ?: return null
        applicationInfo.sourceDir = apkFile.absolutePath
        applicationInfo.publicSourceDir = apkFile.absolutePath
        val drawable = applicationInfo.loadIcon(packageManager)
        val width = drawable.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(MAX_ICON_EDGE) ?: DEFAULT_ICON_EDGE
        val height = drawable.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(MAX_ICON_EDGE) ?: DEFAULT_ICON_EDGE
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
        }
    }

    private fun CacheEntry.toAsset(icon: Bitmap?): ThirdPartyApplicationAsset =
        ThirdPartyApplicationAsset(
            packageName = packageName,
            versionCode = versionCode,
            remoteFilePath = remoteFilePath,
            displayName = displayName,
            iconKey = key.takeIf { icon != null },
        )

    private fun cacheKey(packageName: String, versionCode: Long, remoteFilePath: String): String =
        "third-party-icon-" + hashKey(listOf(packageName, versionCode, remoteFilePath).joinToString("|"))

    private fun isValidIdentity(packageName: String, versionCode: Long?, remoteFilePath: String): Boolean =
        PACKAGE_NAME_PATTERN.matches(packageName) &&
            versionCode?.let { it >= 0L } != false &&
            remoteFilePath.startsWith("/data/app/") &&
            remoteFilePath.endsWith("/base.apk") &&
            remoteFilePath.length <= MAX_REMOTE_PATH_LENGTH &&
            remoteFilePath.none { it == '\\' || it == '\u0000' || it.isWhitespace() } &&
            remoteFilePath.split('/').none { it == "." || it == ".." }

    private fun normalizeDisplayName(value: String?, packageName: String): String {
        val normalized = value?.trim().orEmpty()
        return normalized
            .takeIf { it.isNotEmpty() && it.length <= MAX_DISPLAY_NAME_LENGTH && it.none(Char::isISOControl) }
            ?: packageName
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
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun hashKey(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun isValidKey(value: String): Boolean = value.matches(ASSET_KEY_PATTERN)

    private fun isDigest(value: String): Boolean = value.matches(SHA256_PATTERN)

    private data class CacheEntry(
        val key: String,
        val packageName: String,
        val versionCode: Long?,
        val remoteFilePath: String,
        val displayName: String,
        val iconSha256: String,
        val modified: Long,
        val image: File?,
    )

    private companion object {
        const val CACHE_DIRECTORY = "03-third-party-app-assets"
        const val PNG_EXTENSION = "png"
        const val META_EXTENSION = "meta"
        const val MAX_ICON_EDGE = 256
        const val DEFAULT_ICON_EDGE = 128
        const val MAX_ICON_BYTES = 512L * 1024L
        const val MAX_CACHE_ENTRIES = 128
        const val MAX_CACHE_BYTES = 16L * 1024L * 1024L
        const val MAX_BITMAP_CACHE_ENTRIES = 128
        const val MAX_DISPLAY_NAME_LENGTH = 256
        const val MAX_REMOTE_PATH_LENGTH = 512
        val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        val ASSET_KEY_PATTERN = Regex("^third-party-icon-[a-f0-9]{64}$")
        val SHA256_PATTERN = Regex("^[a-fA-F0-9]{64}$")
    }
}
