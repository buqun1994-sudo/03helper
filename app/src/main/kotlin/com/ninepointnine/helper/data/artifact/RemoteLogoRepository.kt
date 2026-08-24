package com.ninepointnine.helper.data.artifact

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.ninepointnine.helper.domain.artifact.AppIconAsset
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class RemoteLogoRequest(
    val componentId: String,
    val environment: String,
    val channel: String,
    val catalogRevision: Long,
    val asset: AppIconAsset,
)

data class LogoAssetResponse(
    val statusCode: Int,
    val contentType: String?,
    val body: ByteArray,
)

fun interface LogoAssetTransport {
    suspend fun fetch(url: String, maxBytes: Long): LogoAssetResponse
}

/** HTTPS adapter for immutable app-icon objects. Redirects are deliberately rejected. */
class UrlConnectionLogoAssetTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 20_000,
) : LogoAssetTransport {
    override suspend fun fetch(url: String, maxBytes: Long): LogoAssetResponse = withContext(Dispatchers.IO) {
        val uri = URI(url)
        require(uri.scheme.equals("https", ignoreCase = true)) { "logo_https_required" }
        require(uri.userInfo == null && uri.host.equals(ALLOWED_HOST, ignoreCase = true)) {
            "logo_host_forbidden"
        }
        val connection = (URL(url).openConnection() as? HttpsURLConnection)
            ?: throw IllegalStateException("logo_https_required")
        try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "image/png,image/webp")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw IllegalStateException("logo_size_exceeded")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            LogoAssetResponse(status, connection.contentType, body)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val ALLOWED_HOST = "download.9.9studio.fun"
    }
}

/** Loads signed app-icon declarations and keeps the network/cache work out of Compose. */
class RemoteLogoRepository(
    private val root: File,
    private val transport: LogoAssetTransport,
    private val maxBytes: Long = MAX_ICON_BYTES,
) {
    init {
        require(root.mkdirs() || root.isDirectory) { "logo_cache_unavailable" }
    }

    suspend fun loadIcons(requests: List<RemoteLogoRequest>): Map<String, Bitmap> = withContext(Dispatchers.IO) {
        requests.mapNotNull { request ->
            try {
                currentCoroutineContext().ensureActive()
                loadOne(request)?.let { request.componentId to it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "logo_load_failed component=${request.componentId} reason=${reason(error)}")
                null
            }
        }.toMap()
    }

    private suspend fun loadOne(request: RemoteLogoRequest): Bitmap? {
        val asset = request.asset
        validateAsset(asset)
        val cacheFile = root.resolve(cacheName(request, asset))
        if (cacheFile.isFile && cacheFile.length() == asset.sizeBytes &&
            runCatching { sha256(cacheFile) == asset.sha256 }.getOrDefault(false)
        ) {
            decode(cacheFile, asset)?.let { return it }
            // A digest-valid file can still be a truncated or unsupported image
            // (for example after an interrupted platform-level file replacement).
            // Remove it so the next read can recover from the immutable source.
            cacheFile.delete()
        }

        val response = transport.fetch(asset.url, maxBytes)
        if (response.statusCode !in 200..299 || response.body.isEmpty()) return null
        if (!contentTypeMatches(response.contentType, asset.mimeType)) return null
        if (response.body.size.toLong() != asset.sizeBytes || response.body.size > maxBytes) return null
        if (sha256Bytes(response.body) != asset.sha256) return null
        val temporary = cacheFile.resolveSibling(".${cacheFile.name}.part")
        return try {
            FileOutputStream(temporary).use { it.write(response.body) }
            if (!temporary.renameTo(cacheFile)) return null
            val decoded = decode(cacheFile, asset)
            if (decoded == null) {
                cacheFile.delete()
                null
            } else {
                decoded
            }
        } finally {
            temporary.delete()
        }
    }

    private fun validateAsset(asset: AppIconAsset) {
        require(asset.assetId.matches(ASSET_ID_PATTERN)) { "logo_asset_id_invalid" }
        require(asset.assetVersion > 0) { "logo_asset_version_invalid" }
        require(asset.mimeType in ICON_MIME_TYPES) { "logo_mime_invalid" }
        require(asset.width in 1..MAX_ICON_EDGE && asset.height == asset.width) { "logo_dimensions_invalid" }
        require(asset.sizeBytes in 1..maxBytes) { "logo_size_invalid" }
        require(asset.sha256.matches(SHA256_PATTERN)) { "logo_digest_invalid" }
        val match = URL_PATTERN.matchEntire(asset.url) ?: throw IllegalArgumentException("logo_url_invalid")
        require(match.groupValues[1] == asset.sha256) { "logo_url_digest_mismatch" }
        val extension = if (asset.mimeType == "image/png") "png" else "webp"
        require(match.groupValues[2] == extension) { "logo_mime_mismatch" }
    }

    private fun decode(file: File, asset: AppIconAsset): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth != asset.width || bounds.outHeight != asset.height ||
            bounds.outWidth !in 1..MAX_ICON_EDGE || bounds.outHeight !in 1..MAX_ICON_EDGE
        ) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    private fun cacheName(request: RemoteLogoRequest, asset: AppIconAsset): String {
        val key = listOf(
            request.environment,
            request.channel,
            request.catalogRevision,
            request.componentId,
            asset.assetId,
            asset.sha256,
        ).joinToString("|")
        return "${sha256Bytes(key.toByteArray(Charsets.UTF_8))}.${if (asset.mimeType == "image/png") "png" else "webp"}"
    }

    private fun contentTypeMatches(actual: String?, expected: String): Boolean {
        val normalized = actual?.substringBefore(';')?.trim()?.lowercase()
        return normalized == expected
    }

    private fun reason(error: Exception): String = error.message?.take(48) ?: error::class.java.simpleName

    private companion object {
        const val TAG = "03helper.Logo"
        const val ALLOWED_HOST = "download.9.9studio.fun"
        const val MAX_ICON_BYTES = 256 * 1024L
        const val MAX_ICON_EDGE = 512
        val ICON_MIME_TYPES = setOf("image/png", "image/webp")
        val ASSET_ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,127}$")
        val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
        val URL_PATTERN = Regex("^https://download\\.9\\.9studio\\.fun/03-apps/logos/03[a-z]+/sha256-([a-f0-9]{64})\\.(png|webp)$")
    }
}

private fun sha256Bytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
