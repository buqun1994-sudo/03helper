package com.ninepointnine.helper.data.catalog

/** Explicit production fail-closed transport until Cloud publishes an Android profile. */
class UnavailableReleaseCatalogTransport : ReleaseCatalogTransport {
    override suspend fun fetch(): CatalogHttpResponse = throw CatalogTransportFailure(
        reasonCode = "catalog_android_profile_missing",
        retryable = false,
    )
}
