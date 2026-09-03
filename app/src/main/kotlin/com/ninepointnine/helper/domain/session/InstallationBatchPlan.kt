package com.ninepointnine.helper.domain.session

/**
 * Immutable decisions for one installation attempt.
 *
 * The session creates this once after the user confirms a selection. Runtime
 * adapters must consume these sets instead of deriving a second interpretation
 * from the retained catalog or the live UI rows.
 */
data class InstallationBatchPlan(
    /** Stable business-attempt id; retained across adapter event generations. */
    val batchId: Long,
    val flow: InstallationFlow,
    val strategy: InstallationStrategy,
    /** All components that participate in the device-side batch. */
    val selectedComponentIds: Set<String>,
    /** Components proven reusable by the inventory snapshot used for this batch. */
    val reusableComponentIds: Set<String>,
    /** Components already present on the vehicle before this batch started. */
    val preinstalledComponentIds: Set<String> = emptySet(),
    /** Components that need a verified APK before device installation. */
    val preparationComponentIds: Set<String>,
    /** Rows that belong to the user's current result, excluding reused maintenance items. */
    val resultComponentIds: Set<String>,
    /** Signed control-plane identity frozen with the component decisions. */
    val catalogIdentity: InstallationCatalogIdentity? = null,
) {
    init {
        require(batchId >= 0L)
        require(selectedComponentIds.isNotEmpty())
        require(preinstalledComponentIds.all(String::isNotBlank))
        require(preinstalledComponentIds.intersect(selectedComponentIds).isEmpty())
        require(reusableComponentIds.all { it in selectedComponentIds })
        require(preparationComponentIds == selectedComponentIds - reusableComponentIds)
        require(resultComponentIds == selectedComponentIds - reusableComponentIds)
    }
}

/** Complete identity of the signed catalog used to create an installation batch. */
data class InstallationCatalogIdentity(
    val version: String,
    val revision: Long,
    val keyId: String,
    val signatureAlgorithm: String,
) {
    init {
        require(version.isNotBlank())
        require(revision > 0L)
        require(keyId.isNotBlank())
        require(signatureAlgorithm.isNotBlank())
    }

    fun matches(
        version: String,
        revision: Long,
        keyId: String,
        signatureAlgorithm: String,
    ): Boolean =
        this.version == version &&
            this.revision == revision &&
            this.keyId == keyId &&
            this.signatureAlgorithm == signatureAlgorithm
}
