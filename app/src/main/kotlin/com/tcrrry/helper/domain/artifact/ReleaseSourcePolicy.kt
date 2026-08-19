package com.tcrrry.helper.domain.artifact

import java.net.URI

/**
 * Fixed source order and host boundary. The manifest may describe all three
 * sources, but it cannot reorder them or introduce a fourth protocol.
 */
class ReleaseSourcePolicy(
    private val hostPolicy: SourceHostPolicy = SourceHostPolicy.default(),
    mode: ReleaseSourceMode = ReleaseSourceMode.PRODUCTION,
) {
    private val automaticOrder = when (mode) {
        ReleaseSourceMode.PRODUCTION -> ArtifactSourceKind.AUTOMATIC_ORDER
        ReleaseSourceMode.DEBUG_REAL_COMPONENTS -> listOf(ArtifactSourceKind.LANZOU_SHARE)
    }

    fun plan(manifest: ArtifactManifest): SourcePlan {
        val manifestValidation = ArtifactManifestValidator.validate(manifest)
        if (manifestValidation is ManifestValidation.Invalid) {
            return SourcePlan.Rejected(manifestValidation.reasonCode)
        }

        val byKind = manifest.sources.associateBy { it.kind }
        if (byKind.size != automaticOrder.size || byKind.keys != automaticOrder.toSet()) {
            return SourcePlan.Rejected("source_set_invalid")
        }

        val ordered = buildList {
            automaticOrder.forEach { kind ->
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

    /** Allows the detached WebView to distinguish share-page navigation from a short-lived file request. */
    fun isLanzouSharePage(url: String): Boolean = hostPolicy.isLanzouSharePage(url)

    /** A transient target may be downloaded, but can never appear in a signed manifest. */
    fun isLanzouTransientDownloadUrl(url: String): Boolean = hostPolicy.isLanzouTransientDownloadUrl(url)

    /** Lanzou's numbered lanrar page is a short-lived verification step, not a file response. */
    fun isLanzouVerificationPage(url: String): Boolean = hostPolicy.isLanzouVerificationPage(url)
}

/** Compile-time selected policy; catalog data cannot enable a debug source mode. */
enum class ReleaseSourceMode {
    PRODUCTION,
    DEBUG_REAL_COMPONENTS,
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
    private val lanzouShareSuffixes: Set<String>,
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
                    isLanzouTransientDownloadHost(host) && requireSingleSharePath -> "lanzou_manifest_host_forbidden"
                    !matchesSuffix(host, lanzouShareSuffixes) && !isLanzouTransientDownloadHost(host) -> "lanzou_host_forbidden"
                    isLanzouTransientDownloadHost(host) && !hasDownloadPath(uri) -> "lanzou_transient_download_path_invalid"
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

    fun isLanzouSharePage(url: String): Boolean = parseHttpsUrl(url)?.let { uri ->
        matchesSuffix(checkNotNull(uri.host).lowercase(), lanzouShareSuffixes)
    } ?: false

    fun isLanzouTransientDownloadUrl(url: String): Boolean = parseHttpsUrl(url)?.let { uri ->
        isLanzouFileHost(checkNotNull(uri.host).lowercase()) && hasDownloadPath(uri)
    } ?: false

    fun isLanzouVerificationPage(url: String): Boolean = parseHttpsUrl(url)?.let { uri ->
        isLanzouVerificationHost(checkNotNull(uri.host).lowercase()) &&
            uri.path.orEmpty().trim('/').equals("file", ignoreCase = true) &&
            !uri.query.isNullOrBlank()
    } ?: false

    private fun matchesSuffix(host: String, suffixes: Set<String>): Boolean =
        suffixes.any { suffix -> host == suffix || host.endsWith(".$suffix") }

    private fun isLanzouTransientDownloadHost(host: String): Boolean =
        isLanzouVerificationHost(host) || isLanzouFileHost(host)

    private fun isLanzouVerificationHost(host: String): Boolean =
        isNumberedSubdomain(host, prefix = "developer", suffix = "lanrar.com")

    private fun isLanzouFileHost(host: String): Boolean =
        isNumberedSubdomain(host, prefix = "zip", suffix = "webgetstore.com")

    private fun isNumberedSubdomain(host: String, prefix: String, suffix: String): Boolean {
        val suffixWithDot = ".$suffix"
        if (!host.endsWith(suffixWithDot)) return false
        val subdomain = host.removeSuffix(suffixWithDot)
        return subdomain.matches(Regex("${Regex.escape(prefix)}[0-9]+"))
    }

    private fun hasDownloadPath(uri: URI): Boolean = uri.path.orEmpty().trim('/').isNotBlank()

    private fun parseHttpsUrl(value: String): URI? {
        val uri = try {
            URI(value)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return uri.takeIf {
            it.scheme.equals("https", ignoreCase = true) &&
                it.userInfo == null &&
                !it.host.isNullOrBlank() &&
                it.fragment.isNullOrBlank()
        }
    }

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
            lanzouShareSuffixes = setOf(
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
