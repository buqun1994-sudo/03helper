package com.ninepointnine.helper.data.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationDiagnosticLogFilterTest {
    @Test
    fun `logcat split keeps observed and crash pids plus package related system rows`() {
        val packageName = "com.example.player"
        val raw = """
            09-20 10:00:00.000  321  400 I Player: application row
            09-20 10:00:00.010 1000 1001 I ActivityManager: Start proc $packageName
            09-20 10:00:00.020  777  778 I Other: unrelated row
            09-20 10:00:00.030  654  655 E AndroidRuntime: Process: $packageName, PID: 654
            09-20 10:00:00.040  654  655 E AndroidRuntime: java.lang.IllegalStateException
            09-20 10:00:00.050 1000 1001 I ActivityManager: Start proc $packageName.other
        """.trimIndent()

        val split = ApplicationDiagnosticLogFilter.split(raw, packageName, listOf(321))

        assertEquals(listOf(321, 654), split.processIds)
        assertTrue(split.application.contains("application row"))
        assertTrue(split.application.contains("IllegalStateException"))
        assertTrue(split.relatedSystem.contains("Start proc $packageName"))
        assertFalse(split.application.contains("unrelated row"))
        assertFalse(split.relatedSystem.contains("unrelated row"))
        assertFalse(split.relatedSystem.contains("$packageName.other"))
    }

    @Test
    fun `process filter preserves the header and exact package processes only`() {
        val output = """
            USER PID PPID VSZ RSS WCHAN ADDR S NAME
            u0_a1 11 1 0 0 0 0 S com.example.player
            u0_a1 12 1 0 0 0 0 S com.example.player:worker
            u0_a2 13 1 0 0 0 0 S com.example.player2
        """.trimIndent()

        val filtered = ApplicationDiagnosticLogFilter.filterProcessRows(output, "com.example.player")

        assertTrue(filtered.startsWith("USER PID"))
        assertTrue(filtered.contains("com.example.player\n"))
        assertTrue(filtered.contains("com.example.player:worker"))
        assertFalse(filtered.contains("com.example.player2"))
    }
}
