package com.tcrrry.helper.data.download

import com.tcrrry.helper.domain.artifact.ArtifactManifest
import java.io.File
import java.security.MessageDigest

data class ArtifactCachePaths(
    val archivePart: File,
    val resumeMetadata: File,
    val apkPart: File,
    val apk: File,
)

/** Owns only the installer's private cache subtree. */
class ArtifactCache(
    private val root: File,
) {
    init {
        require(root.mkdirs() || root.isDirectory) { "artifact_cache_unavailable" }
    }

    fun paths(manifest: ArtifactManifest): ArtifactCachePaths {
        val key = stableKey(manifest)
        return ArtifactCachePaths(
            archivePart = root.resolve("$key.zip.part"),
            resumeMetadata = root.resolve("$key.zip.part.meta"),
            apkPart = root.resolve("$key.apk.part"),
            apk = root.resolve("$key.apk"),
        )
    }

    fun clearArchive(manifest: ArtifactManifest) {
        val paths = paths(manifest)
        paths.archivePart.delete()
        paths.resumeMetadata.delete()
    }

    fun clearApk(manifest: ArtifactManifest) {
        val paths = paths(manifest)
        paths.apkPart.delete()
        paths.apk.delete()
    }

    fun clearArtifact(manifest: ArtifactManifest) {
        clearArchive(manifest)
        clearApk(manifest)
    }

    fun clearAll() {
        root.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
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
}
