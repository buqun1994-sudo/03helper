package com.ninepointnine.helper.application.maintenance

import com.ninepointnine.helper.domain.session.ManagedApplicationStatus
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Small phone-local decoration cache for icons read from the car catalog.
 *
 * The cache is deliberately independent from the durable maintenance session:
 * a large Base64 image must never make the session snapshot grow, and a single
 * uninstall must only remove the matching package entry.
 */
class ManagedApplicationIconCache(
    private val directory: File,
    private val maxIconBase64Length: Int = 512 * 1024,
    private val maxTotalBytes: Long = 8L * 1024L * 1024L,
) {
    fun read(application: ManagedApplicationStatus): String? {
        val packageName = application.packageName.takeIf(PACKAGE_NAME_PATTERN::matches) ?: return null
        val file = fileFor(packageName)
        if (!file.isFile || file.length() <= 0L || file.length() > maxIconBase64Length * 2L) return null
        val record = runCatching { parse(file.readText(StandardCharsets.UTF_8)) }.getOrNull() ?: return null
        if (record.packageName != packageName ||
            record.versionCode != application.versionCode ||
            record.updateTimeEpochMillis != application.updateTimeEpochMillis
        ) return null
        return record.iconBase64
    }

    fun write(application: ManagedApplicationStatus, iconBase64: String?): Boolean {
        val packageName = application.packageName.takeIf(PACKAGE_NAME_PATTERN::matches) ?: return false
        val icon = iconBase64?.takeIf { it.length <= maxIconBase64Length && ICON_PATTERN.matches(it) }
            ?: return false
        directory.mkdirs()
        val target = fileFor(packageName)
        val temporary = target.resolveSibling("${target.name}.part")
        return runCatching {
            temporary.writeText(
                listOf(
                    packageName,
                    application.versionCode?.toString().orEmpty(),
                    application.updateTimeEpochMillis?.toString().orEmpty(),
                    icon,
                ).joinToString("\n"),
                StandardCharsets.UTF_8,
            )
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            enforceTotalLimit()
            true
        }.getOrElse {
            temporary.delete()
            false
        }
    }

    /** Removes only packages no longer present in the latest verified inventory. */
    fun pruneTo(applications: List<ManagedApplicationStatus>) {
        val currentPackages = applications.mapNotNull { it.packageName.takeIf(PACKAGE_NAME_PATTERN::matches) }.toSet()
        if (!directory.isDirectory) return
        directory.listFiles { file -> file.extension == FILE_EXTENSION }
            .orEmpty()
            .forEach { file ->
                val packageName = runCatching { parse(file.readText(StandardCharsets.UTF_8)).packageName }.getOrNull()
                if (packageName == null || packageName !in currentPackages) file.delete()
            }
        enforceTotalLimit()
    }

    fun remove(packageName: String) {
        if (!PACKAGE_NAME_PATTERN.matches(packageName)) return
        fileFor(packageName).delete()
    }

    private fun fileFor(packageName: String): File = directory.resolve(
        sha256(packageName.toByteArray(StandardCharsets.UTF_8)) + "." + FILE_EXTENSION,
    )

    private fun parse(text: String): Record {
        val fields = text.split('\n', limit = 4)
        require(fields.size == 4)
        val packageName = fields[0].takeIf(PACKAGE_NAME_PATTERN::matches) ?: error("invalid package")
        val versionCode = fields[1].takeIf(String::isNotBlank)?.toLongOrNull()
        val updateTime = fields[2].takeIf(String::isNotBlank)?.toLongOrNull()
        val icon = fields[3].takeIf { it.length <= maxIconBase64Length && ICON_PATTERN.matches(it) }
            ?: error("invalid icon")
        return Record(packageName, versionCode, updateTime, icon)
    }

    private fun enforceTotalLimit() {
        if (!directory.isDirectory) return
        val files = directory.listFiles { file -> file.extension == FILE_EXTENSION }
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .toMutableList()
        var total = files.sumOf(File::length)
        files.dropWhile {
            if (total <= maxTotalBytes) return@dropWhile false
            total -= it.length()
            it.delete()
            true
        }
    }

    private data class Record(
        val packageName: String,
        val versionCode: Long?,
        val updateTimeEpochMillis: Long?,
        val iconBase64: String,
    )

    private companion object {
        const val FILE_EXTENSION = "icon"
        val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        val ICON_PATTERN = Regex("^[A-Za-z0-9_-]*$")

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
