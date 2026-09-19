package com.ninepointnine.helper.data.device

/** Parses the target PackageManager's finite dangerous-permission capability table. */
internal object TargetDangerousPermissionParser {
    fun parse(output: String): Set<String>? {
        val lines = output.lineSequence().map(String::trim).toList()
        if (lines.none { it == HEADER }) return null
        return lines.mapNotNull { line ->
            line.removePrefix(PERMISSION_PREFIX)
                .takeIf { line.startsWith(PERMISSION_PREFIX) }
                ?.takeIf(PERMISSION_PATTERN::matches)
        }.toCollection(linkedSetOf())
    }

    private const val HEADER = "Dangerous Permissions:"
    private const val PERMISSION_PREFIX = "permission:"
    private val PERMISSION_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
}
