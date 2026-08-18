package com.tcrrry.helper.application.session

import com.tcrrry.helper.domain.session.InstallationSession
import com.tcrrry.helper.domain.session.InstallationSessionEvent
import java.util.concurrent.atomic.AtomicLong

fun interface InstallationSessionEventPort {
    fun emit(event: InstallationSessionEvent)
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
}
