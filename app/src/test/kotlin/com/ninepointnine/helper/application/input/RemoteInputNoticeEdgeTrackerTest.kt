package com.ninepointnine.helper.application.input

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteInputNoticeEdgeTrackerTest {
    @Test
    fun `empty draft still shows on the first real keyboard edge`() {
        val tracker = RemoteInputNoticeEdgeTracker()

        assertEquals(RemoteInputNoticeAction.CANCEL, tracker.update(state(keyboardOpen = false)))
        assertEquals(RemoteInputNoticeAction.SHOW, tracker.update(state(keyboardOpen = true)))
        assertEquals(RemoteInputNoticeAction.NONE, tracker.update(state(keyboardOpen = true)))
    }

    @Test
    fun `closed to open and every fresh generation show once`() {
        val tracker = RemoteInputNoticeEdgeTracker()

        assertEquals(RemoteInputNoticeAction.SHOW, tracker.update(state(generation = 1L, keyboardOpen = true)))
        assertEquals(RemoteInputNoticeAction.NONE, tracker.update(state(generation = 1L, keyboardOpen = true)))
        assertEquals(RemoteInputNoticeAction.CANCEL, tracker.update(state(generation = 1L, keyboardOpen = false)))
        assertEquals(RemoteInputNoticeAction.SHOW, tracker.update(state(generation = 1L, keyboardOpen = true)))
        assertEquals(RemoteInputNoticeAction.SHOW, tracker.update(state(generation = 2L, keyboardOpen = true)))
        assertEquals(RemoteInputNoticeAction.NONE, tracker.update(state(generation = 2L, keyboardOpen = true)))
    }

    @Test
    fun `busy after a reminder cancels without reminding again`() {
        val tracker = RemoteInputNoticeEdgeTracker()

        assertEquals(RemoteInputNoticeAction.SHOW, tracker.update(state(keyboardOpen = true)))
        assertEquals(
            RemoteInputNoticeAction.CANCEL,
            tracker.update(state(keyboardOpen = true, deviceBusy = true)),
        )
        assertEquals(RemoteInputNoticeAction.NONE, tracker.update(state(keyboardOpen = true)))
        assertEquals(
            RemoteInputNoticeAction.CANCEL,
            tracker.update(state(keyboardOpen = true, vehicleSending = true)),
        )
        assertEquals(RemoteInputNoticeAction.CANCEL, tracker.update(state(keyboardOpen = true)))
    }

    @Test
    fun `page visibility suppresses the reminder and records the open baseline`() {
        val tracker = RemoteInputNoticeEdgeTracker()

        assertEquals(
            RemoteInputNoticeAction.CANCEL,
            tracker.update(state(keyboardOpen = true, pageVisible = true)),
        )
        assertEquals(RemoteInputNoticeAction.NONE, tracker.update(state(keyboardOpen = true)))
        assertEquals(RemoteInputNoticeAction.CANCEL, tracker.update(state(keyboardOpen = false)))
        assertEquals(RemoteInputNoticeAction.SHOW, tracker.update(state(keyboardOpen = true)))
    }

    @Test
    fun `first state after local send establishes baseline without a duplicate reminder`() {
        val tracker = RemoteInputNoticeEdgeTracker()

        assertEquals(RemoteInputNoticeAction.SHOW, tracker.update(state(keyboardOpen = true)))
        assertEquals(
            RemoteInputNoticeAction.CANCEL,
            tracker.update(state(keyboardOpen = true, localSending = true)),
        )
        assertEquals(RemoteInputNoticeAction.CANCEL, tracker.update(state(keyboardOpen = true)))
        assertEquals(RemoteInputNoticeAction.CANCEL, tracker.update(state(keyboardOpen = false)))
        assertEquals(RemoteInputNoticeAction.SHOW, tracker.update(state(keyboardOpen = true)))
    }

    @Test
    fun `unknown result cancels and does not create an open edge`() {
        val tracker = RemoteInputNoticeEdgeTracker()

        assertEquals(RemoteInputNoticeAction.SHOW, tracker.update(state(keyboardOpen = true)))
        assertEquals(
            RemoteInputNoticeAction.CANCEL,
            tracker.update(state(keyboardOpen = true, unknownResult = true)),
        )
        assertEquals(RemoteInputNoticeAction.NONE, tracker.update(state(keyboardOpen = true)))
        assertEquals(RemoteInputNoticeAction.CANCEL, tracker.update(state(keyboardOpen = false)))
        assertEquals(RemoteInputNoticeAction.SHOW, tracker.update(state(keyboardOpen = true)))
    }

    @Test
    fun `disconnection and unavailable state cancel without depending on draft changes`() {
        val tracker = RemoteInputNoticeEdgeTracker()

        assertEquals(RemoteInputNoticeAction.SHOW, tracker.update(state(keyboardOpen = true)))
        assertEquals(
            RemoteInputNoticeAction.CANCEL,
            tracker.update(state(connected = false, keyboardOpen = true)),
        )
        assertEquals(
            RemoteInputNoticeAction.CANCEL,
            tracker.update(state(connected = true, keyboardOpen = true, available = false)),
        )
        assertEquals(RemoteInputNoticeAction.SHOW, tracker.update(state(keyboardOpen = true)))
    }

    private fun state(
        connected: Boolean = true,
        generation: Long = 1L,
        hasState: Boolean = true,
        keyboardOpen: Boolean,
        available: Boolean = true,
        localSending: Boolean = false,
        vehicleSending: Boolean = false,
        deviceBusy: Boolean = false,
        unknownResult: Boolean = false,
        pageVisible: Boolean = false,
    ): RemoteInputNoticeProjection = RemoteInputNoticeProjection(
        connected = connected,
        connectionGeneration = generation,
        hasState = hasState,
        keyboardOpen = keyboardOpen,
        available = available,
        localSending = localSending,
        vehicleSending = vehicleSending,
        deviceBusy = deviceBusy,
        unknownResult = unknownResult,
        pageVisible = pageVisible,
    )
}
