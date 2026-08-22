package com.tcrrry.helper.data.web

import com.tcrrry.helper.data.catalog.InstallerComponentSource
import com.tcrrry.helper.data.catalog.InstallerDistributionConfig
import com.tcrrry.helper.domain.artifact.ArtifactFailure
import com.tcrrry.helper.domain.artifact.ArtifactFailurePhase
import com.tcrrry.helper.domain.artifact.ArtifactSource
import com.tcrrry.helper.domain.artifact.ArtifactSourceKind
import com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy
import java.net.URI
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

data class LanzouFolderEntry(
    val id: String,
    val name: String,
    val sizeLabel: String? = null,
    val modifiedLabel: String? = null,
)

data class LanzouFolderArtifact(
    val component: InstallerComponentSource,
    val entry: LanzouFolderEntry,
    val source: ArtifactSource,
)

fun interface LanzouFolderWebViewHostFactory {
    fun create(): LanzouFolderWebViewHost
}

interface LanzouFolderWebViewHost {
    fun startFolder(
        folderUrl: String,
        password: String,
        onEntries: (List<LanzouFolderEntry>) -> Unit,
        onFailure: (ArtifactFailure) -> Unit,
    )

    fun stopAndDestroy()
}

sealed interface LanzouFolderResolutionResult {
    data class Success(val artifacts: List<LanzouFolderArtifact>) : LanzouFolderResolutionResult

    data class Failure(val failure: ArtifactFailure) : LanzouFolderResolutionResult
}

/** Resolves one configured Lanzou folder into the fixed component set. */
class LanzouFolderSourceAdapter(
    private val hostFactory: LanzouFolderWebViewHostFactory,
    private val sourcePolicy: ReleaseSourcePolicy = ReleaseSourcePolicy(),
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    suspend fun resolve(config: InstallerDistributionConfig): LanzouFolderResolutionResult {
        val folderUri = runCatching { URI(config.folderUrl) }.getOrNull()
        if (folderUri == null || !sourcePolicy.isLanzouFolderUrl(config.folderUrl)) {
            return failure("lanzou_folder_config_invalid", retryable = false)
        }
        val host = try {
            hostFactory.create()
        } catch (_: Exception) {
            return failure("lanzou_folder_webview_create_failed", retryable = true)
        }
        return try {
            val entries = withTimeout(timeoutMillis) {
                suspendCancellableCoroutine<FolderCallbackResult> { continuation ->
                    val completeFailure: (ArtifactFailure) -> Unit = { error ->
                        if (continuation.isActive) continuation.resume(FolderCallbackResult.Failure(error))
                    }
                    val completeEntries: (List<LanzouFolderEntry>) -> Unit = { value ->
                        if (continuation.isActive) continuation.resume(FolderCallbackResult.Success(value))
                    }
                    try {
                        host.startFolder(config.folderUrl, config.folderPassword, completeEntries, completeFailure)
                    } catch (_: Exception) {
                        completeFailure(
                            ArtifactFailure(
                                phase = ArtifactFailurePhase.SOURCE_RESOLUTION,
                                sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                                reasonCode = "lanzou_folder_start_failed",
                                retryable = true,
                            ),
                        )
                    }
                    continuation.invokeOnCancellation { host.stopAndDestroy() }
                }.getOrThrow()
            }
            when (entries) {
                is FolderCallbackResult.Success -> matchEntries(config, folderUri, entries.entries)
                is FolderCallbackResult.Failure -> LanzouFolderResolutionResult.Failure(entries.failure)
            }
        } catch (failure: FolderResolutionException) {
            LanzouFolderResolutionResult.Failure(failure.failure)
        } catch (_: TimeoutCancellationException) {
            failure("lanzou_folder_parse_timeout", retryable = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failure("lanzou_folder_parse_failed", retryable = true)
        } finally {
            host.stopAndDestroy()
        }
    }

    private fun matchEntries(
        config: InstallerDistributionConfig,
        folderUri: URI,
        entries: List<LanzouFolderEntry>,
    ): LanzouFolderResolutionResult {
        val zipEntries = entries.filter { it.name.endsWith(".zip", ignoreCase = true) }
        if (zipEntries.size != entries.size) {
            return failure("lanzou_folder_unknown_file", retryable = false)
        }
        val allowedNames = config.components.associateBy { it.archiveFileName.lowercase() }
        if (zipEntries.any { it.name.lowercase() !in allowedNames }) {
            return failure("lanzou_folder_unknown_file", retryable = false)
        }
        if (zipEntries.map { it.id }.toSet().size != zipEntries.size) {
            return failure("lanzou_folder_duplicate_file", retryable = false)
        }
        val byName = zipEntries.groupBy { it.name.lowercase() }
        if (byName.values.any { it.size != 1 }) {
            return failure("lanzou_folder_duplicate_file", retryable = false)
        }
        val origin = "${folderUri.scheme}://${folderUri.authority}"
        val artifacts = mutableListOf<LanzouFolderArtifact>()
        for (component in config.components) {
            val entry = byName[component.archiveFileName.lowercase()]?.singleOrNull()
            if (entry == null) {
                if (component.required) {
                    return failure(
                        reasonCode = "lanzou_folder_missing_${component.componentId}",
                        retryable = false,
                        componentId = component.componentId,
                    )
                }
                continue
            }
            if (!entry.id.matches(SHARE_ID_PATTERN)) {
                return failure(
                    "lanzou_folder_entry_id_invalid",
                    retryable = false,
                    componentId = component.componentId,
                )
            }
            val source = ArtifactSource(
                kind = ArtifactSourceKind.LANZOU_SHARE,
                url = "$origin/${entry.id}",
            )
            when (val validation = sourcePolicy.validateManifestSource(source)) {
                com.tcrrry.helper.domain.artifact.SourcePolicyValidation.Accepted -> Unit
                is com.tcrrry.helper.domain.artifact.SourcePolicyValidation.Rejected ->
                    return failure(
                        validation.reasonCode,
                        retryable = false,
                        componentId = component.componentId,
                    )
            }
            artifacts += LanzouFolderArtifact(component, entry, source)
        }
        return LanzouFolderResolutionResult.Success(artifacts)
    }

    private fun failure(
        reasonCode: String,
        retryable: Boolean,
        componentId: String? = null,
    ): LanzouFolderResolutionResult.Failure =
        LanzouFolderResolutionResult.Failure(
            ArtifactFailure(
                phase = ArtifactFailurePhase.SOURCE_RESOLUTION,
                componentId = componentId,
                sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                reasonCode = reasonCode,
                retryable = retryable,
            ),
        )

    private class FolderResolutionException(val failure: ArtifactFailure) : RuntimeException(failure.reasonCode)

    private sealed interface FolderCallbackResult {
        data class Success(val entries: List<LanzouFolderEntry>) : FolderCallbackResult
        data class Failure(val failure: ArtifactFailure) : FolderCallbackResult

        fun getOrThrow(): FolderCallbackResult = when (this) {
            is Success -> this
            is Failure -> throw FolderResolutionException(failure)
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        val SHARE_ID_PATTERN = Regex("^i[a-zA-Z0-9]+$")
    }
}
