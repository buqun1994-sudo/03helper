package com.ninepointnine.helper.data.catalog

import com.ninepointnine.helper.domain.artifact.InstallerSelfIdentity
import com.ninepointnine.helper.domain.session.InstallationBatchPlan
import java.time.Instant

/**
 * Builds the signed-catalog equivalent from the configured Lanzou folder.
 *
 * The signed control plane owns component identities, versions and sizes. The
 * folder is resolved lazily during preparation only for components that are
 * not already satisfied by a verified APK in public Download; archive and APK
 * digests are then derived from the bytes that were actually downloaded.
 */
class FolderArtifactCatalogAdapter(
    private val configAdapter: CloudInstallerDistributionConfigAdapter,
    private val now: () -> Instant = Instant::now,
) {
    private var selectionConfig: InstallerDistributionConfig? = null
    private var lastConfigFailure: CatalogLoadResult.Failure =
        CatalogLoadResult.Failure("distribution_config_unavailable", retryable = true)

    private suspend fun loadConfig(): InstallerDistributionConfig? {
        return when (val result = configAdapter.load()) {
            is DistributionConfigLoadResult.Success -> {
                if (!result.config.expiresAt.isAfter(now())) {
                    lastConfigFailure = CatalogLoadResult.Failure("distribution_config_expired", retryable = true)
                    null
                } else {
                    result.config
                }
            }

            is DistributionConfigLoadResult.Failure -> {
                lastConfigFailure = CatalogLoadResult.Failure(result.reasonCode, result.retryable)
                null
            }
        }
    }

    /**
     * Loads only the signed control-plane snapshot. It deliberately does not
     * resolve the folder, open WebView, enumerate files, download ZIPs, or read
     * APK metadata; those steps belong to selected installation preparation.
     */
    suspend fun loadConfiguration(): DistributionConfigLoadResult = when (val result = configAdapter.load()) {
        is DistributionConfigLoadResult.Success -> {
            if (!result.config.expiresAt.isAfter(now())) {
                lastConfigFailure = CatalogLoadResult.Failure("distribution_config_expired", retryable = true)
                DistributionConfigLoadResult.Failure("distribution_config_expired", retryable = true)
            } else {
                result
            }
        }

        is DistributionConfigLoadResult.Failure -> {
            lastConfigFailure = CatalogLoadResult.Failure(result.reasonCode, result.retryable)
            result
        }
    }

    /**
     * Reads and projects the signed control-plane snapshot. The Lanzou folder
     * is intentionally lazy: preparation opens it only for components that
     * remain unresolved after the public-Download comparison.
     */
    suspend fun loadSelection(): CatalogLoadResult {
        val config = loadConfig() ?: return lastConfigFailure
        selectionConfig = config
        return CatalogLoadResult.Success(
            TrustedArtifactCatalog(
                catalogVersion = config.effectiveCatalogVersion(),
                keyId = config.keyId,
                signatureAlgorithm = config.signatureAlgorithm,
                manifests = emptyList(),
                apps = config.declaredApps()
                    .filter { it.enabled }
                    .filterNot { InstallerSelfIdentity.isSelfComponentId(it.componentId) }
                    .map { app ->
                        // catalogVersion is an internal revision identifier,
                        // never a user-facing application version. Cloud's
                        // signed release metadata is enough to render the
                        // complete choice list before a remote page is needed.
                        app
                    },
                appFailures = emptyList(),
                catalogRevision = config.catalogRevision,
            ),
        )
    }

    /**
     * Freezes the signed configuration and the user's batch decisions for the
     * preparation coordinator. This method performs no folder I/O, APK reads,
     * ZIP downloads, or cache writes.
     */
    suspend fun buildPreparationPlan(batch: InstallationBatchPlan): ArtifactPreparationPlanResult {
        val config = selectionConfig ?: run {
            val config = loadConfig() ?: return ArtifactPreparationPlanResult.Failure(
                lastConfigFailure.reasonCode,
                lastConfigFailure.retryable,
            )
            selectionConfig = config
            config
        }
        if (!config.expiresAt.isAfter(now())) {
            selectionConfig = null
            return ArtifactPreparationPlanResult.Failure("distribution_config_expired", retryable = true)
        }
        val identity = batch.catalogIdentity
        if (identity == null || !identity.matches(
                version = config.effectiveCatalogVersion(),
                revision = config.catalogRevision,
                keyId = config.keyId,
                signatureAlgorithm = config.signatureAlgorithm,
            )
        ) {
            return ArtifactPreparationPlanResult.Failure("selected_catalog_identity_mismatch", retryable = false)
        }
        // The helper update deliberately does not borrow the vehicle desktop
        // invariant. It is still prepared by the same coordinator, but its
        // only legal component is the canonical helper identity and its final
        // APK package is fixed by the local trust root.
        if (batch.flow == com.ninepointnine.helper.domain.session.InstallationFlow.SELF_UPDATE) {
            if (batch.selectedComponentIds != setOf(InstallerSelfIdentity.COMPONENT_ID)) {
                return ArtifactPreparationPlanResult.Failure("self_update_component_set_invalid", retryable = false)
            }
            val selfSource = config.declaredApps()
                .firstOrNull { it.enabled && InstallerSelfIdentity.isSelfComponentId(it.componentId) }
                ?: return ArtifactPreparationPlanResult.Failure("self_update_source_unavailable", retryable = true)
            val normalized = selfSource.copy(
                componentId = InstallerSelfIdentity.COMPONENT_ID,
            )
            return ArtifactPreparationPlanResult.Ready(
                ArtifactPreparationPlan(
                    batch = batch,
                    config = config,
                    components = listOf(normalized),
                ),
            )
        }
        val declared = config.declaredApps()
            .filter { it.enabled }
            .filterNot { InstallerSelfIdentity.isSelfComponentId(it.componentId) }
            .associateBy { it.componentId }
        val batchContextIds = batch.selectedComponentIds + batch.preinstalledComponentIds
        if (!batchContextIds.all { it in declared }) {
            return ArtifactPreparationPlanResult.Failure("selected_catalog_component_set_mismatch", retryable = false)
        }
        val components = batch.preparationComponentIds
            .mapNotNull { declared[it] }
            .sortedWith(compareBy<InstallerComponentSource> { it.sortOrder }.thenBy { it.componentId })
        if (components.map { it.componentId }.toSet() != batch.preparationComponentIds) {
            return ArtifactPreparationPlanResult.Failure("selected_catalog_component_set_mismatch", retryable = false)
        }
        return ArtifactPreparationPlanResult.Ready(
            ArtifactPreparationPlan(
                batch = batch,
                config = config,
                components = components,
            ),
        )
    }

    private companion object {
        const val DESKTOP_APP_ID = "desktop"
    }
}
