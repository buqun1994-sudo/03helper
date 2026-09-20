package com.ninepointnine.helper.application.maintenance

import com.ninepointnine.helper.domain.device.ApplicationDiagnosticReport
import com.ninepointnine.helper.domain.device.ApplicationDiagnosticSection
import com.ninepointnine.helper.domain.device.ApplicationDiagnosticSectionId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationDiagnosticExporterTest {
    @Test
    fun `archive redacts secrets and never includes private application files`() {
        val output = ByteArrayOutputStream()
        ApplicationDiagnosticArchive.write(
            ApplicationDiagnosticReport(
                packageName = "com.example.player",
                capturedAtEpochMillis = 1_800_000_000_000L,
                versionLabel = "1.2.3",
                versionCode = 7L,
                uid = 10_123,
                processIds = listOf(321),
                sections = listOf(
                    ApplicationDiagnosticSection(
                        ApplicationDiagnosticSectionId.APPLICATION_LOGCAT,
                        "Authorization: Bearer secret-token\n" +
                            "Authorization: Basic dXNlcjpwYXNz\n" +
                            "url=https://example.test/path?token=secret-query&keep=yes\n" +
                            "json={\"access_token\":\"json-secret\",\"device_id\":\"device-secret\"}\n" +
                            "email=owner@example.test phone=13800138000\n",
                    ),
                ),
                warnings = listOf("cookie=session-secret"),
            ),
            output,
        )

        val entries = unzip(output.toByteArray())
        val allText = entries.values.joinToString("\n")
        assertTrue(entries.keys.containsAll(setOf("README.txt", "summary.txt", "application-logcat.txt", "warnings.txt")))
        assertTrue(allText.contains("[REDACTED]"))
        assertTrue(allText.contains("keep=yes"))
        assertTrue(allText.contains("Private files under /data/user/0 are never read."))
        assertFalse(allText.contains("secret-token"))
        assertFalse(allText.contains("dXNlcjpwYXNz"))
        assertFalse(allText.contains("secret-query"))
        assertFalse(allText.contains("json-secret"))
        assertFalse(allText.contains("device-secret"))
        assertFalse(allText.contains("owner@example.test"))
        assertFalse(allText.contains("13800138000"))
        assertFalse(allText.contains("session-secret"))
    }

    @Test
    fun `each diagnostic section remains within its fixed uncompressed limit`() {
        val output = ByteArrayOutputStream()
        ApplicationDiagnosticArchive.write(
            ApplicationDiagnosticReport(
                packageName = "com.example.player",
                capturedAtEpochMillis = 1_800_000_000_000L,
                sections = listOf(
                    ApplicationDiagnosticSection(
                        ApplicationDiagnosticSectionId.PACKAGE_STATE,
                        "x".repeat(700 * 1024),
                    ),
                ),
            ),
            output,
        )

        val packageState = unzip(output.toByteArray()).getValue("package-state.txt")
        assertTrue(packageState.toByteArray(Charsets.UTF_8).size <= 512 * 1024)
        assertTrue(packageState.endsWith("[content truncated by 03helper]\n"))
    }

    private fun unzip(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
