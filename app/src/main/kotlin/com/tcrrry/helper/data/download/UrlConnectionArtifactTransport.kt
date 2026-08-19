package com.tcrrry.helper.data.download

import android.util.Log
import com.tcrrry.helper.domain.artifact.ResolvedDownloadRequest
import com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UrlConnectionArtifactTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
    private val sourcePolicy: ReleaseSourcePolicy = ReleaseSourcePolicy(),
) : ArtifactTransport {
    override suspend fun open(
        request: ResolvedDownloadRequest,
        rangeStartBytes: Long,
    ): ArtifactTransportResponse = withContext(Dispatchers.IO) {
        var currentRequest = request
        var currentUrl = request.url
        Log.d(TAG, "open_start host=${safeHost(currentUrl)} range=$rangeStartBytes")
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val uri = URI(currentUrl)
            require(uri.scheme.equals("https", ignoreCase = true))
            require(uri.userInfo == null)
            val connection = (URL(currentUrl).openConnection() as? HttpURLConnection)
                ?: throw IllegalStateException("artifact_http_required")
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            currentRequest.userAgent?.takeIf { it.isNotBlank() }?.let {
                connection.setRequestProperty("User-Agent", it)
            }
            currentRequest.cookie?.takeIf { it.isNotBlank() }?.let {
                connection.setRequestProperty("Cookie", it)
            }
            currentRequest.referer?.takeIf { it.isNotBlank() }?.let {
                connection.setRequestProperty("Referer", it)
            }
            if (rangeStartBytes > 0L) {
                connection.setRequestProperty("Range", "bytes=$rangeStartBytes-")
            }
            val status = connection.responseCode
            Log.d(
                TAG,
                "response host=${safeHost(currentUrl)} status=$status type=${connection.contentType?.substringBefore(';') ?: "unknown"}",
            )
            if (status in REDIRECT_STATUSES) {
                val location = connection.getHeaderField("Location")
                    ?: throw IllegalStateException("artifact_redirect_location_missing")
                val redirectedUrl = uri.resolve(location).toString()
                val nextUri = URI(redirectedUrl)
                val validation = sourcePolicy.validateResolvedRequest(currentRequest.copy(url = redirectedUrl))
                Log.d(TAG, "redirect host=${safeHost(redirectedUrl)} accepted=${validation is com.tcrrry.helper.domain.artifact.SourcePolicyValidation.Accepted}")
                connection.disconnect()
                if (validation is com.tcrrry.helper.domain.artifact.SourcePolicyValidation.Rejected) {
                    throw IllegalStateException(validation.reasonCode)
                }
                if (redirectCount == MAX_REDIRECTS) throw IllegalStateException("artifact_redirect_limit")
                currentUrl = redirectedUrl
                currentRequest = currentRequest.copy(
                    url = redirectedUrl,
                    cookie = currentRequest.cookie?.takeIf { nextUri.host.equals(uri.host, ignoreCase = true) },
                    referer = currentRequest.referer?.takeIf { nextUri.host.equals(uri.host, ignoreCase = true) },
                )
                return@repeat
            }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                ?: java.io.ByteArrayInputStream(ByteArray(0))
            return@withContext ArtifactTransportResponse(
                statusCode = status,
                contentLength = connection.contentLengthLong.takeIf { it >= 0L },
                contentType = connection.contentType,
                body = stream,
                closeAction = { connection.disconnect() },
            )
        }
        throw IllegalStateException("artifact_redirect_limit")
    }

    private fun safeHost(url: String): String = runCatching { URI(url).host }
        .getOrNull()
        ?.lowercase()
        .orEmpty()

    companion object {
        private const val MAX_REDIRECTS = 3
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        private const val TAG = "03helper.Download"
    }
}
