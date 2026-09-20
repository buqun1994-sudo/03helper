package com.ninepointnine.helper.data.device

/** Associates bounded Logcat and process rows with one exact Android package. */
internal object ApplicationDiagnosticLogFilter {
    private const val MAX_PROCESS_IDS = 32
    private val logcatHeader = Regex(
        "^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+(\\d+)\\s+\\d+\\s+[VDIWEAFS]\\s+",
    )

    data class Split(
        val application: String,
        val relatedSystem: String,
        val processIds: List<Int>,
    )

    fun split(
        logcat: String,
        packageName: String,
        observedProcessIds: Collection<Int>,
    ): Split {
        require(PACKAGE_NAME_PATTERN.matches(packageName)) { "diagnostic_package_invalid" }
        val historicalProcessIds = Regex(
            "(?m)\\bProcess:\\s*${Regex.escape(packageName)},\\s*PID:\\s*(\\d+)\\b",
        ).findAll(logcat).mapNotNull { match -> match.groupValues[1].toSafeProcessId() }
        val processIds = (observedProcessIds.asSequence() + historicalProcessIds)
            .filter { it in 1..MAX_PROCESS_ID }
            .distinct()
            .take(MAX_PROCESS_IDS)
            .sorted()
            .toList()
        val processIdSet = processIds.toSet()
        val packageReference = Regex(
            "(?<![A-Za-z0-9_.])${Regex.escape(packageName)}(?![A-Za-z0-9_.])",
        )
        val application = StringBuilder()
        val relatedSystem = StringBuilder()
        logcat.lineSequence().forEach { line ->
            val pid = logcatHeader.find(line)?.groupValues?.getOrNull(1)?.toSafeProcessId()
            when {
                pid != null && pid in processIdSet -> application.appendLine(line)
                packageReference.containsMatchIn(line) -> relatedSystem.appendLine(line)
            }
        }
        return Split(
            application = application.toString(),
            relatedSystem = relatedSystem.toString(),
            processIds = processIds,
        )
    }

    fun parseProcessIds(output: String): List<Int> = output
        .split(Regex("\\s+"))
        .asSequence()
        .mapNotNull { value -> value.toSafeProcessId() }
        .filter { it in 1..MAX_PROCESS_ID }
        .distinct()
        .take(MAX_PROCESS_IDS)
        .sorted()
        .toList()

    fun filterProcessRows(output: String, packageName: String): String {
        require(PACKAGE_NAME_PATTERN.matches(packageName)) { "diagnostic_package_invalid" }
        val lines = output.lineSequence().filter(String::isNotBlank).toList()
        if (lines.isEmpty()) return ""
        val header = lines.first().takeIf { line ->
            line.contains("PID", ignoreCase = true) && line.contains("NAME", ignoreCase = true)
        }
        val matching = lines.filter { line ->
            val columns = line.trim().split(Regex("\\s+"))
            columns.any { value -> value == packageName || value.startsWith("$packageName:") }
        }
        return (listOfNotNull(header) + matching.filterNot { it == header })
            .distinct()
            .joinToString(separator = "\n", postfix = if (matching.isEmpty() && header == null) "" else "\n")
    }

    private fun String.toSafeProcessId(): Int? = toIntOrNull()?.takeIf { it in 1..MAX_PROCESS_ID }

    private const val MAX_PROCESS_ID = 4_194_304
    private val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
}
