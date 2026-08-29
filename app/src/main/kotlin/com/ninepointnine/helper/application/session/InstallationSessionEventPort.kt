package com.ninepointnine.helper.application.session

import com.ninepointnine.helper.domain.session.InstallationSession
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import com.ninepointnine.helper.domain.session.InstallationSessionState
import java.util.concurrent.atomic.AtomicLong

fun interface InstallationSessionEventPort {
    fun emit(event: InstallationSessionEvent)
}

/** Activity checks for work that can write to the device or publish artifacts. */
interface InstallationSessionActivityGate {
    fun isBatchActive(batchId: Long): Boolean

    fun isArtifactPreparationActive(batchId: Long): Boolean
}

/**
 * The generation-bound boundary required by installation work. Lightweight
 * adapters only need [InstallationSessionEventPort]; write-capable owners must
 * receive this stronger contract from the runtime.
 */
interface InstallationSessionBoundary : InstallationSessionEventPort, InstallationSessionActivityGate

/** One monotonic event sequence shared by all adapters in a session generation. */
class InstallationSessionEventDispatcher(
    private val session: InstallationSession,
    private val sessionId: Long,
) : InstallationSessionBoundary {
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

    override fun isArtifactPreparationActive(batchId: Long): Boolean {
        val snapshot = session.currentSnapshot()
        return snapshot.sessionId == sessionId &&
            snapshot.state == InstallationSessionState.PREPARING_ARTIFACTS &&
            snapshot.installationBatch?.batchId == batchId &&
            snapshot.installationBatchReceipt == null
    }
}
