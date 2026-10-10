package com.ninepointnine.helper.domain.input

/** Readiness and delivery results owned by the installation session. */
enum class RemoteInputStatus {
    DISCONNECTED,
    SYNCING,
    READY,
    UNAVAILABLE,
    SENDING,
    UNKNOWN_RESULT,
}

/** Immutable target and text captured before a single device write starts. */
data class RemoteInputRequest(
    val generation: Long,
    val requestId: String,
    val text: String,
    val epoch: Long,
    val targetPackage: String,
    val targetWindow: Int,
) {
    val connectionGeneration: Long
        get() = generation
}

data class RemoteInputSnapshot(
    val connectionGeneration: Long = 0,
    val status: RemoteInputStatus = RemoteInputStatus.DISCONNECTED,
    val keyboardOpen: Boolean = false,
    val epoch: Long = 0,
    val targetPackage: String = "",
    val targetWindow: Int = -1,
    val sequence: Long = 0,
    val draft: String = "",
    val requestId: String? = null,
    val sentText: String? = null,
    val notice: String? = null,
    val noticeRevision: Long = 0,
    /** Actual compatibility/readiness from the latest target observation. */
    val available: Boolean = false,
    /** Distinguishes an accepted protocol sequence=0 from the default snapshot. */
    val hasState: Boolean = false,
    val reason: String? = null,
    val pendingSend: RemoteInputRequest? = null,
    /** The device lease and its identity handshake are confirmed. */
    val leaseConfirmed: Boolean = false,
    /** A device action is running in the same session and owns the lease. */
    val deviceBusy: Boolean = false,
    val vehicleSending: Boolean = false,
    /** Bounded replay guard; request IDs are opaque, never derived from text. */
    val recentRequestIds: List<String> = emptyList(),
    /** Generation whose send result is no longer knowable. */
    val unknownResultGeneration: Long? = null,
) {
    companion object {
        /** The car-side protocol accepts at most 32 KiB of UTF-8 bytes. */
        const val MAX_TEXT_BYTES: Int = 32 * 1024
    }

    val canSend: Boolean
        get() = status == RemoteInputStatus.READY && leaseConfirmed && !deviceBusy && targetReady && draft.isNotEmpty()

    val sending: Boolean
        get() = status == RemoteInputStatus.SENDING

    /** The one explanation consumed by the page and by rejected send clicks. */
    val statusReason: String?
        get() = when {
            status == RemoteInputStatus.SENDING -> "remote_input_sending"
            status == RemoteInputStatus.UNKNOWN_RESULT -> "remote_input_unknown_result"
            deviceBusy || vehicleSending -> "remote_input_busy"
            status == RemoteInputStatus.DISCONNECTED -> "remote_input_disconnected"
            status == RemoteInputStatus.SYNCING -> "remote_input_syncing"
            status == RemoteInputStatus.UNAVAILABLE -> reason ?: targetUnavailableReason
            !targetReady -> targetUnavailableReason
            draft.isEmpty() -> "remote_input_empty"
            else -> null
        }

    private val targetReady: Boolean
        get() = connectionGeneration > 0 && hasState && keyboardOpen && available && !vehicleSending &&
            targetPackage.isNotBlank() && targetWindow >= 0

    private val targetUnavailableReason: String
        get() = when {
            !leaseConfirmed -> "remote_input_disconnected"
            !available -> reason ?: "remote_input_unavailable"
            !keyboardOpen -> "remote_input_keyboard_closed"
            else -> "remote_input_target_unavailable"
        }

    internal fun updateDraft(text: String): RemoteInputSnapshot =
        if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
            withNotice("remote_input_text_too_long")
        } else {
            copy(draft = text)
        }

    internal fun beginSend(id: String): RemoteInputSnapshot {
        // Repeated clicks and a replay of an accepted request cannot create a
        // second write, even when its completion arrived before the next click.
        if (sending || id in recentRequestIds || id == requestId) return this
        if (id.isBlank()) return withNotice("remote_input_request_invalid")
        if (!canSend) return withNotice(statusReason ?: "remote_input_unavailable")
        val request = RemoteInputRequest(connectionGeneration, id, draft, epoch, targetPackage, targetWindow)
        return copy(
            status = RemoteInputStatus.SENDING,
            requestId = id,
            sentText = draft,
            pendingSend = request,
            recentRequestIds = (recentRequestIds + id).takeLast(128),
            notice = null,
        )
    }

    internal fun connectionChanged(
        generation: Long,
        newStatus: RemoteInputStatus,
        reason: String?,
    ): RemoteInputSnapshot {
        if (generation < connectionGeneration || generation <= 0 || newStatus == RemoteInputStatus.SENDING) {
            return this
        }
        // An ended lease stays ended. Only a new connection generation may
        // consume target observations or restore send readiness.
        if (generation == connectionGeneration && status == RemoteInputStatus.DISCONNECTED &&
            newStatus != RemoteInputStatus.DISCONNECTED
        ) return this
        if (generation == connectionGeneration && sending && newStatus == RemoteInputStatus.UNAVAILABLE) {
            return copy(keyboardOpen = false, available = false, vehicleSending = false, reason = reason)
        }
        val replacedLease = generation > connectionGeneration
        val interrupted = sending
        val previousGeneration = connectionGeneration
        val unknown = interrupted ||
            status == RemoteInputStatus.UNKNOWN_RESULT && (!replacedLease || newStatus != RemoteInputStatus.SYNCING) ||
            newStatus == RemoteInputStatus.UNKNOWN_RESULT
        val next = copy(
            connectionGeneration = generation,
            status = if (unknown) RemoteInputStatus.UNKNOWN_RESULT else newStatus,
            keyboardOpen = false,
            epoch = 0,
            targetPackage = "",
            targetWindow = -1,
            sequence = if (replacedLease) 0 else sequence,
            hasState = if (replacedLease || newStatus == RemoteInputStatus.DISCONNECTED) false else hasState,
            available = false,
            vehicleSending = false,
            reason = reason,
            requestId = if (interrupted || replacedLease) null else requestId,
            sentText = if (interrupted || replacedLease) null else sentText,
            pendingSend = if (interrupted || replacedLease) null else pendingSend,
            unknownResultGeneration = when {
                newStatus == RemoteInputStatus.UNKNOWN_RESULT -> generation
                interrupted -> previousGeneration
                unknown -> unknownResultGeneration ?: previousGeneration
                replacedLease -> null
                else -> unknownResultGeneration
            },
        )
        return if (interrupted) next.withNotice("remote_input_unknown_result") else next
    }

    internal fun disconnected(reason: String? = null): RemoteInputSnapshot {
        val interrupted = sending
        val alreadyUnknown = status == RemoteInputStatus.UNKNOWN_RESULT
        return copy(
            status = if (interrupted || alreadyUnknown) RemoteInputStatus.UNKNOWN_RESULT else RemoteInputStatus.DISCONNECTED,
            keyboardOpen = false,
            epoch = 0,
            targetPackage = "",
            targetWindow = -1,
            sequence = 0,
            hasState = false,
            available = false,
            vehicleSending = false,
            reason = reason,
            requestId = null,
            sentText = null,
            pendingSend = null,
            unknownResultGeneration = if (interrupted) connectionGeneration else unknownResultGeneration,
            notice = if (interrupted) "remote_input_unknown_result" else notice,
            noticeRevision = if (interrupted) noticeRevision + 1 else noticeRevision,
        )
    }

    internal fun stateReceived(
        generation: Long,
        sequence: Long,
        keyboardOpen: Boolean,
        epoch: Long,
        targetPackage: String,
        targetWindow: Int,
        available: Boolean,
        vehicleSending: Boolean = false,
    ): RemoteInputSnapshot {
        if (generation != connectionGeneration ||
            (hasState && sequence <= this.sequence) ||
            status == RemoteInputStatus.DISCONNECTED ||
            status == RemoteInputStatus.UNAVAILABLE && !hasState ||
            epoch < this.epoch
        ) return this
        val next = copy(
            sequence = sequence,
            keyboardOpen = keyboardOpen,
            epoch = epoch,
            targetPackage = targetPackage,
            targetWindow = targetWindow,
            available = available,
            vehicleSending = vehicleSending,
            hasState = true,
            reason = null,
        )
        val resyncedAfterUnknown = unknownResultGeneration != generation
        return next.copy(
            status = when {
                sending -> RemoteInputStatus.SENDING
                !resyncedAfterUnknown -> RemoteInputStatus.UNKNOWN_RESULT
                next.targetReady -> RemoteInputStatus.READY
                else -> RemoteInputStatus.UNAVAILABLE
            },
            unknownResultGeneration = if (resyncedAfterUnknown) null else unknownResultGeneration,
        )
    }

    internal fun sendFinished(
        generation: Long,
        id: String,
        committed: Boolean?,
        restored: Boolean,
        reason: String?,
    ): RemoteInputSnapshot {
        if (generation != connectionGeneration || !sending || id != requestId ||
            pendingSend?.requestId != id
        ) return this
        val next = copy(
            draft = if (committed == true && draft == sentText) "" else draft,
            requestId = null,
            sentText = null,
            pendingSend = null,
            available = available && restored,
            reason = if (!restored) "remote_input_restore_failed" else null,
            unknownResultGeneration = if (committed == null) generation else null,
        )
        val result = next.copy(
            status = when {
                committed == null -> RemoteInputStatus.UNKNOWN_RESULT
                next.targetReady -> RemoteInputStatus.READY
                else -> RemoteInputStatus.UNAVAILABLE
            },
        )
        return result.withNotice(
            when {
                committed == null -> "remote_input_unknown_result"
                committed && !restored -> "remote_input_sent_restore_failed"
                committed -> "remote_input_sent"
                else -> reason ?: "remote_input_send_failed"
            },
        )
    }

    internal fun dismissNotice(): RemoteInputSnapshot = copy(notice = null)

    private fun withNotice(message: String): RemoteInputSnapshot = copy(
        notice = message,
        noticeRevision = noticeRevision + 1,
    )
}
