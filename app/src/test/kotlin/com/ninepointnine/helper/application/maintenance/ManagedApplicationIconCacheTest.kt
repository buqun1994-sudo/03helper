package com.ninepointnine.helper.application.maintenance

import com.ninepointnine.helper.domain.session.ManagedApplicationStatus
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ManagedApplicationIconCacheTest {
    @Test
    fun `cache is keyed by package version and update time`() {
        val cache = ManagedApplicationIconCache(Files.createTempDirectory("managed-icons").toFile())
        val application = application("com.example.player", 1L, 10L)

        assertEquals(true, cache.write(application, "aGVsbG8"))
        assertEquals("aGVsbG8", cache.read(application))
        assertNull(cache.read(application.copy(versionCode = 2L)))
        assertNull(cache.read(application.copy(updateTimeEpochMillis = 11L)))
    }

    @Test
    fun `prune removes only packages absent from the latest inventory`() {
        val cache = ManagedApplicationIconCache(Files.createTempDirectory("managed-icons").toFile())
        val retained = application("com.example.retained", 1L, 10L)
        val removed = application("com.example.removed", 1L, 10L)
        cache.write(retained, "cmV0YWluZWQ")
        cache.write(removed, "cmVtb3ZlZA")

        cache.pruneTo(listOf(retained))

        assertNotNull(cache.read(retained))
        assertNull(cache.read(removed))
    }

    private fun application(packageName: String, versionCode: Long, updateTime: Long) =
        ManagedApplicationStatus(
            componentId = "app-${packageName.hashCode()}",
            packageName = packageName,
            installed = true,
            versionCode = versionCode,
            updateTimeEpochMillis = updateTime,
        )
}
