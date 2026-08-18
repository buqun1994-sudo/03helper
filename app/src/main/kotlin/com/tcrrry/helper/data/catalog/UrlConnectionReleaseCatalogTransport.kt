package com.tcrrry.helper.data.catalog

import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UrlConnectionReleaseCatalogTransport(
    private val endpoint: URL,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 20_000,
) : ReleaseCatalogTransport {
    init {
        require(endpoint.protocol.equals("https", ignoreCase = true))
        require(endpoint.userInfo == null)
    }

    override suspend fun fetch(): CatalogHttpResponse = withContext(Dispatchers.IO) {
        val connection = (endpoint.openConnection() as? HttpsURLConnection)
            ?: throw IllegalStateException("catalog_https_required")
        try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > CloudReleaseCatalogAdapter.MAX_CATALOG_BYTES) {
                        throw IllegalStateException("catalog_size_invalid")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            CatalogHttpResponse(status, body, connection.contentType)
        } finally {
            connection.disconnect()
        }
    }
}
