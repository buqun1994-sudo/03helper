package com.ninepointnine.helper.application.artifact

import com.ninepointnine.helper.domain.session.InstallationSession
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import com.ninepointnine.helper.application.session.InstallationSessionEventPort
import java.util.concurrent.atomic.AtomicLong

typealias ArtifactSessionEventPort = InstallationSessionEventPort

/** The only production bridge from F2 adapters into the F1 session owner. */
class InstallationSessionArtifactEventPort(
    private val session: InstallationSession,
    private val sessionId: Long? = null,
) : ArtifactSessionEventPort {
    private val sequence = AtomicLong(0L)

    override fun emit(event: InstallationSessionEvent) {
        val generation = sessionId
        if (generation == null) {
            session.dispatchEvent(event)
        } else {
            session.dispatchEvent(
                event = event,
                sessionId = generation,
                sequence = sequence.incrementAndGet(),
            )
        }
    }
}
