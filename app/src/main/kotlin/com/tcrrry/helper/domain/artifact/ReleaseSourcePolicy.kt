package com.tcrrry.helper.domain.artifact

import java.net.URI

/**
 * Fixed source order and host boundary. The manifest may describe all three
 * sources, but it cannot reorder them or introduce a fourth protocol.
 */
class ReleaseSourcePolicy(
    private val hostPolicy: SourceHostPolicy = SourceHostPolicy.default(),
) {
    fun plan(manifest: ArtifactManifest): SourcePlan {
        val manifestValidation = ArtifactManifestValidator.validate(manifest)
        if (manifestValidation is ManifestValidation.Invalid) {
            return SourcePlan.Rejected(manifestValidation.reasonCode)
        }

        val byKind = manifest.sources.associateBy { it.kind }
        if (byKind.size != ArtifactSourceKind.AUTOMATIC_ORDER.size) {
            return SourcePlan.Rejected("source_set_invalid")
        }

        val ordered = buildList {
            ArtifactSourceKind.AUTOMATIC_ORDER.forEach { kind ->
                val source = byKind[kind] ?: return SourcePlan.Rejected("source_missing_${kind.wireName}")
                val reason = hostPolicy.rejectReason(source, requireSingleSharePath = true)
                if (reason != null) return SourcePlan.Rejected(reason)
                add(source)
            }
        }
        return SourcePlan.Accepted(ordered)
    }

    fun orderedSources(manifest: ArtifactManifest): List<ArtifactSource> = when (val result = plan(manifest)) {
        is SourcePlan.Accepted -> result.sources
        is SourcePlan.Rejected -> emptyList()
    }

    fun validateResolvedRequest(request: ResolvedDownloadRequest): SourcePolicyValidation {
        val source = ArtifactSource(request.sourceKind, request.url)
        val reason = hostPolicy.rejectReason(source, requireSingleSharePath = false)
        return if (reason == null) {
            SourcePolicyValidation.Accepted
        } else {
            SourcePolicyValidation.Rejected(reason)
        }
    }

    fun validateManifestSource(source: ArtifactSource): SourcePolicyValidation {
        val reason = hostPolicy.rejectReason(source, requireSingleSharePath = true)
        return if (reason == null) {
            SourcePolicyValidation.Accepted
        } else {
            SourcePolicyValidation.Rejected(reason)
        }
    }
}

sealed interface SourcePlan {
    data class Accepted(val sources: List<ArtifactSource>) : SourcePlan
    data class Rejected(val reasonCode: String) : SourcePlan
}

sealed interface SourcePolicyValidation {
    data object Accepted : SourcePolicyValidation
    data class Rejected(val reasonCode: String) : SourcePolicyValidation
}

class SourceHostPolicy(
    private val lanzouSuffixes: Set<String>,
    private val r2Suffixes: Set<String>,
    private val githubHosts: Set<String>,
) {
    fun rejectReason(source: ArtifactSource, requireSingleSharePath: Boolean): String? {
        val uri = try {
            URI(source.url)
        } catch (_: IllegalArgumentException) {
            return "source_url_invalid"
        }
        if (uri.scheme?.lowercase() != "https" || uri.userInfo != null || uri.host.isNullOrBlank()) {
            return "source_url_not_https"
        }
        if (!uri.fragment.isNullOrBlank()) return "source_url_fragment_forbidden"
        val host = uri.host.lowercase()
        return when (source.kind) {
            ArtifactSourceKind.LANZOU_SHARE -> {
                when {
                    !matchesSuffix(host, lanzouSuffixes) -> "lanzou_host_forbidden"
                    requireSingleSharePath && !isSingleSharePath(uri.path) -> "lanzou_share_not_single_file"
                    else -> null
                }
            }

            ArtifactSourceKind.R2 ->
                if (!matchesSuffix(host, r2Suffixes)) "r2_host_forbidden" else null

            ArtifactSourceKind.GITHUB_RELEASES ->
                if (host !in githubHosts) "github_host_forbidden" else null
        }
    }

    private fun matchesSuffix(host: String, suffixes: Set<String>): Boolean =
        suffixes.any { suffix -> host == suffix || host.endsWith(".$suffix") }

    private fun isSingleSharePath(path: String?): Boolean {
        val normalized = path.orEmpty().trim('/').lowercase()
        if (normalized.isBlank()) return false
        if (normalized.startsWith("f/") || normalized.contains("folder") || normalized.contains("password")) {
            return false
        }
        return normalized.startsWith("i") && normalized.split('/').size == 1
    }

    companion object {
        fun default(): SourceHostPolicy = SourceHostPolicy(
            lanzouSuffixes = setOf(
                "lanzou.com",
                "lanzouw.com",
                "lanzoux.com",
                "lanzoui.com",
                "lanzouy.com",
            ),
            r2Suffixes = setOf(
                "r2.dev",
                "r2.cloudflarestorage.com",
                "9.9studio.fun",
            ),
            githubHosts = setOf(
                "github.com",
                "objects.githubusercontent.com",
                "githubusercontent.com",
            ),
        )
    }
}
