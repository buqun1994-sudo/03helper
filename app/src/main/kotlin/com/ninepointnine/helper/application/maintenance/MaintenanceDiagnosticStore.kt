package com.ninepointnine.helper.application.maintenance

import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Writes one small, redacted diagnostic record inside the installer's cache. */
class MaintenanceDiagnosticStore(
    private val directory: File,
) {
    init {
        require(directory.mkdirs() || directory.isDirectory) { "diagnostic_store_unavailable" }
    }

    fun export(snapshot: InstallationSessionSnapshot): Boolean {
        val temporary = directory.resolve("diagnostics.txt.part")
        val output = directory.resolve("diagnostics.txt")
        return try {
            temporary.writeText(render(snapshot), Charsets.UTF_8)
            try {
                Files.move(
                    temporary.toPath(),
                    output.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    output.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            true
        } catch (_: Exception) {
            temporary.delete()
            false
        }
    }

    fun clear(): Int {
        val files = directory.listFiles().orEmpty().filter(File::isFile)
        files.forEach { it.delete() }
        return files.count { !it.exists() }
    }

    private fun render(snapshot: InstallationSessionSnapshot): String = buildString {
        appendLine("schema=1")
        appendLine("state=${snapshot.state.name}")
        appendLine("revision=${snapshot.revision}")
        appendLine("session_generation=${snapshot.sessionId}")
        snapshot.device?.let { device ->
            appendLine("device_model=${safe(device.displayName)}")
            appendLine("device_android_sdk=${device.androidSdk ?: "unknown"}")
            appendLine("device_connection=${device.connectionStatus.name}")
        } ?: appendLine("device_model=unknown")
        appendLine("catalog_version=${safe(snapshot.catalogVersion ?: "unknown")}")
        appendLine("components=${snapshot.components.joinToString(",") { safe(it.id) }}")
        appendLine("installed=${snapshot.evidence.installed.sorted().joinToString(",") { safe(it) }}")
        appendLine("configured=${snapshot.evidence.configured.sorted().joinToString(",") { safe(it) }}")
        appendLine("available=${snapshot.evidence.available.sorted().joinToString(",") { safe(it) }}")
        snapshot.failure?.let { failure ->
            appendLine("failure_category=${failure.category.name}")
            appendLine("failure_reason=${safe(failure.reasonCode ?: "unknown")}")
        }
        snapshot.maintenance.lastAction?.let { action ->
            appendLine("maintenance_action=${action.actionId.name}")
            appendLine("maintenance_status=${action.status.name}")
            action.resultCode?.let { appendLine("maintenance_result=${safe(it)}") }
            action.reasonCode?.let { appendLine("maintenance_reason=${safe(it)}") }
        }
    }

    private fun safe(value: String): String = value
        .replace(Regex("[^A-Za-z0-9_.:-]"), "_")
        .take(160)
}
