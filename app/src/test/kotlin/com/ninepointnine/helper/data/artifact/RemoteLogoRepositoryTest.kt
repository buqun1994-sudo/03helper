package com.ninepointnine.helper.data.artifact

import com.ninepointnine.helper.domain.artifact.AppIconAsset
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLogoRepositoryTest {
    @Test
    fun `rejects missing or mismatched mime and digest without caching`() = runBlocking {
        val root = Files.createTempDirectory("remote-logo-cache").toFile()
        val body = byteArrayOf(1, 2, 3, 4)
        val digest = sha256(body)
        val responses = ConcurrentLinkedQueue(
            listOf(
                LogoAssetResponse(statusCode = 200, contentType = null, body = body),
                LogoAssetResponse(statusCode = 200, contentType = "image/jpeg", body = body),
                LogoAssetResponse(statusCode = 200, contentType = "image/png", body = byteArrayOf(9, 8, 7, 6)),
            ),
        )
        val calls = AtomicInteger()
        val repository = RemoteLogoRepository(
            root = root,
            transport = LogoAssetTransport { _, _ ->
                calls.incrementAndGet()
                responses.remove() ?: error("logo test response missing")
            },
        )
        val requests = listOf(
            request(assetId = "missing-content-type", sha256 = digest),
            request(assetId = "wrong-content-type", sha256 = digest),
            request(assetId = "wrong-body-digest", sha256 = digest),
        )

        assertTrue(repository.loadIcons(requests).isEmpty())
        assertEquals(3, calls.get())
        assertEquals(0, root.listFiles()?.size ?: 0)
    }

    private fun request(assetId: String, sha256: String): RemoteLogoRequest = RemoteLogoRequest(
        componentId = assetId,
        environment = "staging",
        channel = "debug",
        catalogRevision = 1L,
        asset = AppIconAsset(
            assetId = assetId,
            assetVersion = 1,
            url = "https://download.9.9studio.fun/03-apps/logos/03desktop/sha256-$sha256.png",
            mimeType = "image/png",
            width = 2,
            height = 2,
            sizeBytes = 4,
            sha256 = sha256,
        ),
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
