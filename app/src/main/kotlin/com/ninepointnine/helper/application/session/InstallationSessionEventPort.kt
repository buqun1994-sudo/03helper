package com.ninepointnine.helper.application.session

import com.ninepointnine.helper.domain.session.InstallationSession
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import com.ninepointnine.helper.domain.session.InstallationSessionState
import java.util.concurrent.atomic.AtomicLong

fun interface InstallationSessionEventPort {
    fun emit(event: InstallationSessionEvent)

    /**
     * Returns whether the batch that owns the current adapter call is still
     * active. Lightweight test ports keep the source-compatible default; the
     * production dispatcher binds the check to its session generation.
     */
    fun isBatchActive(batchId: Long): Boolean = true
}

/** One monotonic event sequence shared by all adapters in a session generation. */
class InstallationSessionEventDispatcher(
    private val session: InstallationSession,
    private val sessionId: Long,
) : InstallationSessionEventPort {
    private val sequence = AtomicLong(0L)

    override fun emit(event: InstallationSessionEvent) {
        session.dispatchEvent(
            event = event,
            sessionId = sessionId,
            sequence = sequence.incrementAndGet(),
        )
    }

    override fun isBatchActive(batchId: Long): Boolean {
        val snapshot = session.currentSnapshot()
        return snapshot.sessionId == sessionId &&
            snapshot.state == InstallationSessionState.INSTALLING &&
            snapshot.installationBatch?.batchId == batchId &&
            snapshot.installationBatchReceipt == null
    }
}
