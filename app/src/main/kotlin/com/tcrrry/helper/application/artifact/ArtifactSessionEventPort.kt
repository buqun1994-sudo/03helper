package com.tcrrry.helper.application.artifact

import com.tcrrry.helper.domain.session.InstallationSession
import com.tcrrry.helper.domain.session.InstallationSessionEvent

fun interface ArtifactSessionEventPort {
    fun emit(event: InstallationSessionEvent)
}

/** The only production bridge from F2 adapters into the F1 session owner. */
class InstallationSessionArtifactEventPort(
    private val session: InstallationSession,
) : ArtifactSessionEventPort {
    override fun emit(event: InstallationSessionEvent) {
        session.dispatchEvent(event)
    }
}
