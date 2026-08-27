package com.ninepointnine.helper.data.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderEntrySnapshotStabilizerTest {
    @Test
    fun `a progressive folder waits until every expected archive is visible`() {
        val stabilizer = FolderEntrySnapshotStabilizer(
            expectedArchiveFileNames = setOf("03desktop-debug.zip", "03lyrics-debug.zip", "03cast-debug.zip"),
            stableSampleCount = 3,
        )
        val partial = listOf(
            LanzouFolderEntry("idesktop", "03desktop-debug.zip"),
            LanzouFolderEntry("ilyrics", "03lyrics-debug.zip"),
        )
        val complete = partial + LanzouFolderEntry("icast", "03cast-debug.zip")

        assertTrue(stabilizer.observe(partial) is FolderSnapshotDecision.Wait)
        assertTrue(stabilizer.observe(complete) is FolderSnapshotDecision.Complete)
    }

    @Test
    fun `a stable partial folder cannot declare a selected archive missing before the deadline`() {
        val stabilizer = FolderEntrySnapshotStabilizer(
            expectedArchiveFileNames = setOf("03cast-debug.zip"),
            stableSampleCount = 3,
        )
        val first = listOf(
            LanzouFolderEntry("ilyrics", " 03lyrics-debug.zip ", sizeLabel = "18 MB"),
            LanzouFolderEntry("idesktop", "03desktop-debug.zip"),
        )
        val reordered = first.reversed().map { it.copy(name = it.name.trim()) }

        assertTrue(stabilizer.observe(first) is FolderSnapshotDecision.Wait)
        assertTrue(stabilizer.observe(reordered) is FolderSnapshotDecision.Wait)
        assertTrue(stabilizer.observe(first) is FolderSnapshotDecision.Wait)
        val decision = stabilizer.completeAtDeadline() as FolderSnapshotDecision.Complete

        assertEquals(setOf("03desktop-debug.zip", "03lyrics-debug.zip"), decision.entries.map { it.name }.toSet())
    }

    @Test
    fun `progressive partial snapshots accumulate until every selected archive appears`() {
        val stabilizer = FolderEntrySnapshotStabilizer(
            expectedArchiveFileNames = setOf("03cast-debug.zip", "03notes-debug.zip"),
        )

        assertTrue(
            stabilizer.observe(listOf(LanzouFolderEntry("icast", "03cast-debug.zip"))) is
                FolderSnapshotDecision.Wait,
        )
        val decision = stabilizer.observe(
            listOf(LanzouFolderEntry("inotes", "03notes-debug.zip")),
        ) as FolderSnapshotDecision.Complete

        assertEquals(setOf("03cast-debug.zip", "03notes-debug.zip"), decision.entries.map { it.name }.toSet())
    }

    @Test
    fun `a folder without any parsed entry remains a timeout at the deadline`() {
        val stabilizer = FolderEntrySnapshotStabilizer(setOf("03cast-debug.zip"))

        assertTrue(stabilizer.observe(emptyList()) is FolderSnapshotDecision.Wait)
        assertTrue(stabilizer.completeAtDeadline() is FolderSnapshotDecision.Wait)
    }
}
