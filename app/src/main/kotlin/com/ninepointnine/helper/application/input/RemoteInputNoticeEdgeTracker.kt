package com.ninepointnine.helper.application.input

/**
 * The small projection used by the input reminder. Drafts, notices and send
 * receipts are deliberately absent so they cannot retrigger notification work.
 */
data class RemoteInputNoticeProjection(
    val connected: Boolean,
    val connectionGeneration: Long,
    val hasState: Boolean,
    val keyboardOpen: Boolean,
    val available: Boolean,
    val localSending: Boolean,
    val vehicleSending: Boolean,
    val deviceBusy: Boolean,
    val unknownResult: Boolean,
    val pageVisible: Boolean,
) {
    val keyboardReady: Boolean
        get() = connected && hasState && keyboardOpen && available

    val sending: Boolean
        get() = localSending || vehicleSending
}

enum class RemoteInputNoticeAction {
    SHOW,
    CANCEL,
    NONE,
}

/**
 * Tracks real keyboard edges independently of the notification implementation.
 *
 * A send or a vehicle-side input transition freezes the baseline. The first
 * non-sending observation establishes that baseline and is intentionally quiet,
 * because the input method can briefly reopen while the transaction settles.
 */
internal class RemoteInputNoticeEdgeTracker {
    private var baselineGeneration: Long? = null
    private var baselineKeyboardOpen = false
    private var baselineInitialized = false
    private var establishBaselineAfterSending = false

    fun update(projection: RemoteInputNoticeProjection): RemoteInputNoticeAction {
        if (projection.sending) {
            establishBaselineAfterSending = true
            return RemoteInputNoticeAction.CANCEL
        }

        if (projection.unknownResult) {
            return RemoteInputNoticeAction.CANCEL
        }

        val generationChanged = baselineGeneration != projection.connectionGeneration
        if (generationChanged) {
            baselineGeneration = projection.connectionGeneration
            baselineKeyboardOpen = false
            baselineInitialized = true
        }

        if (projection.pageVisible) {
            baselineKeyboardOpen = projection.keyboardReady
            baselineInitialized = true
            establishBaselineAfterSending = false
            return RemoteInputNoticeAction.CANCEL
        }

        if (projection.deviceBusy) {
            return RemoteInputNoticeAction.CANCEL
        }

        if (establishBaselineAfterSending) {
            baselineKeyboardOpen = projection.keyboardReady
            baselineInitialized = true
            establishBaselineAfterSending = false
            return RemoteInputNoticeAction.CANCEL
        }

        if (!projection.keyboardReady) {
            baselineKeyboardOpen = false
            baselineInitialized = true
            return RemoteInputNoticeAction.CANCEL
        }

        val shouldShow = !baselineInitialized || generationChanged || !baselineKeyboardOpen
        baselineKeyboardOpen = true
        baselineInitialized = true
        return if (shouldShow) RemoteInputNoticeAction.SHOW else RemoteInputNoticeAction.NONE
    }
}
