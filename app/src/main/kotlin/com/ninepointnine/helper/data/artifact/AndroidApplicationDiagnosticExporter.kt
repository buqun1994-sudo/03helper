package com.ninepointnine.helper.data.artifact

import android.content.ContentResolver
import android.net.Uri
import com.ninepointnine.helper.application.maintenance.ApplicationDiagnosticArchive
import com.ninepointnine.helper.application.maintenance.ApplicationDiagnosticExporter
import com.ninepointnine.helper.domain.device.ApplicationDiagnosticReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Writes one completed diagnostic archive through Android's document-provider boundary. */
class AndroidApplicationDiagnosticExporter(
    private val contentResolver: ContentResolver,
) : ApplicationDiagnosticExporter {
    override suspend fun export(
        destinationUri: String,
        report: ApplicationDiagnosticReport,
    ): Boolean = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(destinationUri) }.getOrNull()
            ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
            ?: return@withContext false
        runCatching {
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                ApplicationDiagnosticArchive.write(report, output)
            } != null
        }.getOrDefault(false)
    }
}
