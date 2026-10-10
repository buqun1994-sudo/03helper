package com.ninepointnine.helper.data.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputReceiptParserTest {
    private val id = "f495c2a8-7b10-4876-877c-123456789abc"

    @Test fun validCommittedAndFailedTerminalReceiptsSkipCancel() {
        val committed = RemoteInputReceiptParser.parse(
            lines = listOf(result(id, true, "committed"), "03INPUT_RESTORED=1"),
            requestId = id,
        )
        assertEquals(true, committed.committed)
        assertTrue(committed.restored)
        assertTrue(committed.verifiedTerminal)

        val failed = RemoteInputReceiptParser.parse(
            lines = listOf(result(id, false, "commit_failed"), "03INPUT_RESTORED=0"),
            requestId = id,
        )
        assertEquals(false, failed.committed)
        assertFalse(failed.restored)
        assertTrue(failed.verifiedTerminal)
    }

    @Test fun unknownRequestIsNullButStillAValidatedTerminalReceipt() {
        val parsed = RemoteInputReceiptParser.parse(
            lines = listOf(result(id, false, "unknown_request"), "03INPUT_RESTORED=1"),
            requestId = id,
        )
        assertNull(parsed.committed)
        assertEquals("unknown_request", parsed.reason)
        assertTrue(parsed.restored)
        assertFalse(parsed.verifiedTerminal)
    }

    @Test fun malformedWrongIdOrTypePreservesRestoreMarkerButRequiresCancel() {
        val wrongId = RemoteInputReceiptParser.parse(
            lines = listOf(result("f495c2a8-7b10-4876-877c-000000000000", true, "committed"), "03INPUT_RESTORED=1"),
            requestId = id,
        )
        assertNull(wrongId.committed)
        assertTrue(wrongId.restored)
        assertFalse(wrongId.verifiedTerminal)

        val wrongType = RemoteInputReceiptParser.parse(
            lines = listOf(result(id, true, "committed", type = "accepted"), "03INPUT_RESTORED=1"),
            requestId = id,
        )
        assertTrue(wrongType.restored)
        assertFalse(wrongType.verifiedTerminal)
    }

    @Test fun missingDuplicateAndUntrustedMarkersAreConservative() {
        val missing = RemoteInputReceiptParser.parse(listOf(result(id, true, "committed")), id)
        assertEquals(true, missing.committed)
        assertFalse(missing.restored)
        assertFalse(missing.verifiedTerminal)

        val duplicate = RemoteInputReceiptParser.parse(
            listOf(result(id, true, "committed"), "03INPUT_RESTORED=1", "03INPUT_RESTORED=1"), id,
        )
        assertFalse(duplicate.restored)
        assertFalse(duplicate.verifiedTerminal)

        val arbitraryReason = RemoteInputReceiptParser.parse(
            listOf(result(id, true, "made_up_reason"), "03INPUT_RESTORED=1"), id,
        )
        assertTrue(arbitraryReason.restored)
        assertFalse(arbitraryReason.verifiedTerminal)
    }

    @Test fun unknownTransportResultStillCancelsAndDoesNotRelayText() {
        val unknown = RemoteInputReceiptParser.parse(listOf("03INPUT_RESTORED=1"), id)
        assertNull(unknown.committed)
        assertTrue(unknown.restored)
        assertFalse(unknown.verifiedTerminal)
        assertFalse(RemoteInputShellPreflight.command().contains("case"))
        assertFalse(RemoteInputShellPreflight.command().contains("*\${'$'}original*"))
    }

    private fun result(id: String, committed: Boolean, reason: String, type: String = "result"): String =
        """{"v":1,"type":"$type","id":"$id","committed":$committed,"reason":"$reason"}"""
}
