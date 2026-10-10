package com.ninepointnine.helper.data.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputRestoreReasonTest {
    @Test fun restoreCompetitionIsAStableNonCommitFailure() {
        val id = "f495c2a8-7b10-4876-877c-123456789abc"
        val parsed = RemoteInputReceiptParser.parse(
            listOf("{\"v\":1,\"type\":\"result\",\"id\":\"$id\",\"committed\":false,\"reason\":\"restore_claimed\"}", "03INPUT_RESTORED=1"),
            id,
        )
        assertEquals(false, parsed.committed)
        assertTrue(parsed.verifiedTerminal)
        assertEquals("restore_claimed", parsed.reason)
    }
}
