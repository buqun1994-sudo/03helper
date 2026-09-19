package com.ninepointnine.helper.application.artifact

import com.ninepointnine.helper.domain.artifact.ArtifactVerification

/** One-operation boundary for copying and validating an APK selected by the user. */
interface UserSelectedApkPreparer {
    suspend fun prepare(uri: String, targetAndroidSdk: Int): UserSelectedApkPreparationResult

    fun clear(artifact: PreparedArtifact)
}

sealed interface UserSelectedApkPreparationResult {
    data class Ready(
        val artifact: PreparedArtifact,
        val verification: ArtifactVerification,
    ) : UserSelectedApkPreparationResult

    data class Failed(
        val reasonCode: String,
        val retryable: Boolean = false,
    ) : UserSelectedApkPreparationResult
}
