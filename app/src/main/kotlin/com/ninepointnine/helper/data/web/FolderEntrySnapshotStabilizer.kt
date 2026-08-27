package com.ninepointnine.helper.data.web

import java.util.Locale

/** Turns progressive folder DOM reads into one immutable request-scoped snapshot. */
internal class FolderEntrySnapshotStabilizer(
    expectedArchiveFileNames: Set<String>,
    private val stableSampleCount: Int = DEFAULT_STABLE_SAMPLE_COUNT,
) {
    private val expectedNames = expectedArchiveFileNames
        .mapTo(mutableSetOf()) { it.trim().lowercase(Locale.ROOT) }
    private var previousEntries: List<LanzouFolderEntry>? = null
    private var consecutiveSamples = 0
    private val accumulatedEntries = linkedMapOf<Pair<String, String>, LanzouFolderEntry>()

    init {
        require(stableSampleCount >= 2)
    }

    fun observe(entries: List<LanzouFolderEntry>): FolderSnapshotDecision {
        val normalized = entries
            .map { entry ->
                entry.copy(
                    id = entry.id.trim(),
                    name = entry.name.trim(),
                    sizeLabel = entry.sizeLabel?.trim()?.takeIf(String::isNotBlank),
                    modifiedLabel = entry.modifiedLabel?.trim()?.takeIf(String::isNotBlank),
                )
            }
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .distinctBy { it.id to it.name.lowercase(Locale.ROOT) }
            .sortedWith(compareBy<LanzouFolderEntry> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })

        normalized.forEach { entry ->
            accumulatedEntries[entry.id to entry.name.lowercase(Locale.ROOT)] = entry
        }
        val accumulated = accumulatedEntries.values.sortedWith(ENTRY_ORDER)
        val visibleNames = accumulated.mapTo(mutableSetOf()) { it.name.lowercase(Locale.ROOT) }
        if (expectedNames.isNotEmpty() && visibleNames.containsAll(expectedNames)) {
            return FolderSnapshotDecision.Complete(accumulated)
        }

        consecutiveSamples = if (normalized == previousEntries) consecutiveSamples + 1 else 1
        previousEntries = normalized
        // Repeated partial DOM reads are not proof that a signed ZIP is absent.
        // Only the request deadline may finalize a partial folder snapshot.
        return FolderSnapshotDecision.Wait
    }

    fun completeAtDeadline(): FolderSnapshotDecision {
        val entries = accumulatedEntries.values.sortedWith(ENTRY_ORDER)
        return if (entries.isEmpty()) FolderSnapshotDecision.Wait else FolderSnapshotDecision.Complete(entries)
    }

    private companion object {
        const val DEFAULT_STABLE_SAMPLE_COUNT = 3
        val ENTRY_ORDER = compareBy<LanzouFolderEntry> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id }
    }
}

internal sealed interface FolderSnapshotDecision {
    data class Complete(val entries: List<LanzouFolderEntry>) : FolderSnapshotDecision
    data object Wait : FolderSnapshotDecision
}
