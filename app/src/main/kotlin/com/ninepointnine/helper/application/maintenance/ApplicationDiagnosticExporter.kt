package com.ninepointnine.helper.application.maintenance

import com.ninepointnine.helper.domain.device.ApplicationDiagnosticReport
import com.ninepointnine.helper.domain.device.ApplicationDiagnosticSectionId
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes a completed, bounded diagnostic report to a user-selected destination. */
fun interface ApplicationDiagnosticExporter {
    suspend fun export(destinationUri: String, report: ApplicationDiagnosticReport): Boolean
}

/** Pure archive renderer shared by the Android adapter and JVM security tests. */
internal object ApplicationDiagnosticArchive {
    private const val SCHEMA_VERSION = 1
    private const val MAX_SECTION_BYTES = 512 * 1024
    private const val MAX_TOTAL_SECTION_BYTES = 4 * 1024 * 1024
    private const val TRUNCATION_NOTICE = "\n[content truncated by 03helper]\n"

    fun write(report: ApplicationDiagnosticReport, output: OutputStream) {
        require(PACKAGE_NAME_PATTERN.matches(report.packageName)) { "diagnostic_package_invalid" }
        require(report.capturedAtEpochMillis > 0L) { "diagnostic_capture_time_invalid" }

        ZipOutputStream(output.buffered()).use { zip ->
            zip.writeTextEntry("README.txt", readme())
            zip.writeTextEntry("summary.txt", summary(report))

            var remainingBytes = MAX_TOTAL_SECTION_BYTES
            report.sections
                .distinctBy { it.id }
                .sortedBy { it.id.ordinal }
                .forEach { section ->
                    if (remainingBytes <= 0) return@forEach
                    val redacted = ApplicationDiagnosticRedactor.redact(section.content)
                    val limit = minOf(MAX_SECTION_BYTES, remainingBytes)
                    val needsArchiveNotice = section.truncated ||
                        redacted.toByteArray(Charsets.UTF_8).size > limit
                    val noticeBytes = if (needsArchiveNotice) {
                        TRUNCATION_NOTICE.toByteArray(Charsets.UTF_8).size
                    } else {
                        0
                    }
                    if (limit < noticeBytes) return@forEach
                    val rendered = truncateUtf8(redacted, (limit - noticeBytes).coerceAtLeast(0))
                    val content = buildString {
                        append(rendered.text)
                        if (needsArchiveNotice || rendered.truncated) append(TRUNCATION_NOTICE)
                    }
                    val bytes = content.toByteArray(Charsets.UTF_8)
                    zip.writeEntry(section.id.fileName, bytes)
                    remainingBytes -= bytes.size
                }

            if (report.warnings.isNotEmpty()) {
                val warnings = report.warnings
                    .map(ApplicationDiagnosticRedactor::redact)
                    .joinToString(separator = "\n", postfix = "\n")
                zip.writeTextEntry("warnings.txt", truncateUtf8(warnings, MAX_SECTION_BYTES).text)
            }
        }
    }

    private fun readme(): String = """
        03helper application diagnostic archive

        This archive was created only after the user selected a save location.
        It is not uploaded to Cloud. Values that commonly contain credentials or
        personal identifiers are redacted before writing.

        application-logcat.txt contains Logcat rows attributable to the selected
        package or its observed process IDs. related-system-logcat.txt contains
        system-process rows that explicitly reference that package. Android logs
        do not identify every historical row perfectly, so unrelated context may
        be omitted. Private files under /data/user/0 are never read.
    """.trimIndent() + "\n"

    private fun summary(report: ApplicationDiagnosticReport): String = buildString {
        appendLine("schema=$SCHEMA_VERSION")
        appendLine("captured_at=${Instant.ofEpochMilli(report.capturedAtEpochMillis)}")
        appendLine("package=${report.packageName}")
        appendLine("version_name=${safeMetadata(report.versionLabel)}")
        appendLine("version_code=${report.versionCode ?: "unknown"}")
        appendLine("uid=${report.uid ?: "unknown"}")
        appendLine("process_ids=${report.processIds.distinct().sorted().joinToString(",").ifBlank { "none" }}")
        appendLine("section_count=${report.sections.distinctBy { it.id }.size}")
        appendLine("warning_count=${report.warnings.size}")
    }

    private fun safeMetadata(value: String?): String = value
        ?.let(ApplicationDiagnosticRedactor::redact)
        ?.replace(Regex("[\\r\\n\\t]"), "_")
        ?.take(160)
        ?.takeIf(String::isNotBlank)
        ?: "unknown"

    private fun ZipOutputStream.writeTextEntry(name: String, value: String) =
        writeEntry(name, value.toByteArray(Charsets.UTF_8))

    private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name).apply { time = 0L })
        write(bytes)
        closeEntry()
    }

    private fun truncateUtf8(value: String, maxBytes: Int): TruncatedText {
        if (maxBytes <= 0) return TruncatedText("", value.isNotEmpty())
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return TruncatedText(value, truncated = false)
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end -= 1
        return TruncatedText(String(bytes, 0, end, Charsets.UTF_8), truncated = true)
    }

    private data class TruncatedText(val text: String, val truncated: Boolean)

    private val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
}

internal object ApplicationDiagnosticRedactor {
    private const val REDACTED = "[REDACTED]"
    private val authorizationHeaderPattern = Regex("(?im)\\b(authorization\\s*:\\s*)[^\\r\\n]+")
    private val cookieHeaderPattern = Regex("(?im)\\b((?:set-cookie|cookie)\\s*:\\s*)[^\\r\\n]+")
    private val bearerPattern = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*")
    private val jwtPattern = Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b")
    private val secretAssignmentPattern = Regex(
        "(?i)([\\\"']?)(authorization|access[_-]?token|refresh[_-]?token|token|api[_-]?key|password|passwd|cookie|set-cookie)" +
            "\\1(\\s*[:=]\\s*)(\\\"[^\\\"\\r\\n]*\\\"|'[^'\\r\\n]*'|[^\\r\\n,;&}\\]]+)",
    )
    private val identifierAssignmentPattern = Regex(
        "(?i)([\\\"']?)(android[_ -]?id|device[_ -]?id|serial|imei|imsi)" +
            "\\1(\\s*[:=]\\s*)(\\\"[^\\\"\\r\\n]*\\\"|'[^'\\r\\n]*'|[^\\r\\n,;}\\]]+)",
    )
    private val sensitiveQueryPattern = Regex(
        "(?i)([?&](?:access[_-]?token|refresh[_-]?token|token|api[_-]?key|password|passwd|signature|sig)=)[^&#\\s]*",
    )
    private val emailPattern = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
    private val phonePattern = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
    private val macPattern = Regex("(?i)\\b(?:[0-9A-F]{2}:){5}[0-9A-F]{2}\\b")

    fun redact(value: String): String = value
        .filter { character -> character == '\n' || character == '\r' || character == '\t' || !character.isISOControl() }
        .replace(authorizationHeaderPattern) { match -> "${match.groupValues[1]}$REDACTED" }
        .replace(cookieHeaderPattern) { match -> "${match.groupValues[1]}$REDACTED" }
        .replace(bearerPattern, "Bearer $REDACTED")
        .replace(jwtPattern, REDACTED)
        .replace(secretAssignmentPattern) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}${match.groupValues[1]}${match.groupValues[3]}$REDACTED"
        }
        .replace(identifierAssignmentPattern) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}${match.groupValues[1]}${match.groupValues[3]}$REDACTED"
        }
        .replace(sensitiveQueryPattern) { match -> "${match.groupValues[1]}$REDACTED" }
        .replace(emailPattern, REDACTED)
        .replace(phonePattern, REDACTED)
        .replace(macPattern, REDACTED)
}
