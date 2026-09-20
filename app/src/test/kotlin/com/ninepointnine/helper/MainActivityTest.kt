package com.ninepointnine.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityTest {
    @Test
    fun `diagnostic filename is bounded sanitized and timestamped`() {
        val name = applicationDiagnosticFileName(
            packageName = "com.example.player/unsafe",
            nowEpochMillis = 1_800_000_000_000L,
        )

        assertTrue(name.matches(Regex("^03helper-com\\.example\\.player_unsafe-logs-\\d{8}-\\d{6}\\.zip$")))
        assertTrue(name.length <= 200)
        assertFalse(name.contains('/'))
    }
}
