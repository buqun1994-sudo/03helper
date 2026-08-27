package com.ninepointnine.helper.data.web

import com.ninepointnine.helper.data.catalog.InstallerComponentSource
import com.ninepointnine.helper.data.catalog.InstallerDistributionConfig
import com.ninepointnine.helper.domain.artifact.ArtifactFailure
import com.ninepointnine.helper.domain.artifact.ArtifactFailurePhase
import com.ninepointnine.helper.domain.artifact.ArtifactSource
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy
import java.net.URI
import java.util.Locale
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

data class LanzouFolderAppFailure(
    val componentId: String,
    val reasonCode: String,
    val retryable: Boolean,
)

fun interface LanzouFolderWebViewHostFactory {
    fun create(): LanzouFolderWebViewHost
}

interface LanzouFolderWebViewHost {
    fun startFolder(
        folderUrl: String,
        password: String,
        expectedArchiveFileNames: Set<String>,
        onEntries: (List<LanzouFolderEntry>) -> Unit,
        onFailure: (ArtifactFailure) -> Unit,
    )

    fun stopAndDestroy()
}

sealed interface LanzouFolderResolutionResult {
    data class Success(
        val artifacts: List<LanzouFolderArtifact>,
        val appFailures: List<LanzouFolderAppFailure> = emptyList(),
    ) : LanzouFolderResolutionResult

    data class Failure(val failure: ArtifactFailure) : LanzouFolderResolutionResult
}

/** Resolves the enabled dynamic app entries in one configured Lanzou folder. */
class LanzouFolderSourceAdapter(
    private val hostFactory: LanzouFolderWebViewHostFactory,
    private val sourcePolicy: ReleaseSourcePolicy = ReleaseSourcePolicy(),
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    suspend fun resolve(
        config: InstallerDistributionConfig,
        expectedComponentIds: Set<String>? = null,
    ): LanzouFolderResolutionResult {
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
                        val expectedArchiveFileNames = config.declaredApps()
                            .filter { component ->
                                component.enabled && component.clientSupported &&
                                    (expectedComponentIds == null || component.componentId in expectedComponentIds)
                            }
                            .mapTo(mutableSetOf()) { it.archiveFileName }
                        host.startFolder(
                            config.folderUrl,
                            config.folderPassword,
                            expectedArchiveFileNames,
                            completeEntries,
                            completeFailure,
                        )
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
        // Lanzou can contain readme files, old releases, or other operator
        // artifacts. They are outside the signed app set and are ignored.
        val zipEntries = entries.filter { it.name.endsWith(".zip", ignoreCase = true) }
        val configuredApps = config.declaredApps().filter { it.enabled && it.clientSupported }
        val byName = zipEntries.groupBy { it.name.lowercase(Locale.ROOT) }
        val origin = "${folderUri.scheme}://${folderUri.authority}"
        val artifacts = mutableListOf<LanzouFolderArtifact>()
        val appFailures = mutableListOf<LanzouFolderAppFailure>()
        val matchedIds = mutableMapOf<String, String>()
        config.declaredApps().filter { it.enabled && !it.clientSupported }.forEach { component ->
            appFailures += LanzouFolderAppFailure(
                componentId = component.componentId,
                reasonCode = "lanzou_folder_client_schema_unsupported",
                retryable = false,
            )
        }
        for (component in configuredApps) {
            val entry = byName[component.archiveFileName.lowercase(Locale.ROOT)]?.singleOrNull()
            if (entry == null) {
                val duplicate = byName[component.archiveFileName.lowercase(Locale.ROOT)].orEmpty().size > 1
                val reason = if (duplicate) "lanzou_folder_duplicate_file" else "lanzou_folder_missing_${component.componentId}"
                appFailures += LanzouFolderAppFailure(component.componentId, reason, retryable = false)
                continue
            }
            if (!entry.id.matches(SHARE_ID_PATTERN)) {
                appFailures += LanzouFolderAppFailure(component.componentId, "lanzou_folder_entry_id_invalid", false)
                continue
            }
            val previousApp = matchedIds.putIfAbsent(entry.id, component.componentId)
            if (previousApp != null) {
                // One share entry cannot safely satisfy two configured apps.
                // Remove the first match as well and expose both failures so a
                // later local-Download check can independently recover either
                // app without accepting an ambiguous remote source.
                artifacts.removeAll { it.component.componentId == previousApp }
                appFailures.removeAll { it.componentId == previousApp }
                appFailures += LanzouFolderAppFailure(previousApp, "lanzou_folder_duplicate_entry", false)
                appFailures += LanzouFolderAppFailure(component.componentId, "lanzou_folder_duplicate_entry", false)
                continue
            }
            val source = ArtifactSource(
                kind = ArtifactSourceKind.LANZOU_SHARE,
                url = "$origin/${entry.id}",
            )
            when (val validation = sourcePolicy.validateManifestSource(source)) {
                com.ninepointnine.helper.domain.artifact.SourcePolicyValidation.Accepted -> Unit
                is com.ninepointnine.helper.domain.artifact.SourcePolicyValidation.Rejected -> {
                    appFailures += LanzouFolderAppFailure(component.componentId, validation.reasonCode, false)
                    continue
                }
            }
            // The folder listing only locates the ZIP. Version and APK size
            // remain the signed Cloud metadata and must not be replaced by the
            // ZIP row size.
            artifacts += LanzouFolderArtifact(
                component = component,
                entry = entry,
                source = source,
            )
        }
        if (artifacts.isEmpty()) {
            // A valid folder may contain no matching ZIP when every selected
            // application is already available in the user's public Download
            // directory. Keep the signed app list and defer each missing item
            // to the per-application preparation path instead of blocking the
            // whole installation session here.
            return LanzouFolderResolutionResult.Success(emptyList(), appFailures)
        }
        return LanzouFolderResolutionResult.Success(artifacts, appFailures)
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
        val SIZE_LABEL_PATTERN = Regex(
            "([0-9]+(?:\\.[0-9]+)?)\\s*(B|K|KB|M|MB|G|GB|T|TB)",
            RegexOption.IGNORE_CASE,
        )

        fun normalizeSizeLabel(value: String): String? {
            val trimmed = value.trim()
            if (trimmed.length > 32) return null
            val match = SIZE_LABEL_PATTERN.find(trimmed) ?: return null
            val unit = when (match.groupValues[2].uppercase()) {
                "K" -> "KB"
                "M" -> "MB"
                "G" -> "GB"
                "T" -> "TB"
                else -> match.groupValues[2].uppercase()
            }
            return "${match.groupValues[1]} $unit"
        }
    }
}
