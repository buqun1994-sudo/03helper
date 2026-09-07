package com.ninepointnine.helper.data.catalog

import com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy
import com.ninepointnine.helper.domain.artifact.AppIconAsset
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.formatArtifactSizeLabel
import com.ninepointnine.helper.domain.artifact.formatArtifactVersionLabel
import com.ninepointnine.helper.domain.session.InstallationBatchPlan
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.Properties
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class SignedInstallerConfigV5Envelope(
    val keyId: String,
    val signatureAlgorithm: String,
    val payloadBase64: String,
    val signatureBase64: String,
) {
    override fun toString(): String = "SignedInstallerConfigV5Envelope(keyId=$keyId, payload=<redacted>)"
}

@Serializable
data class InstallerDistributionConfigV5Payload(
    val schemaVersion: Int,
    val environment: String,
    val catalogRevision: Long,
    val issuedAtUtc: String,
    val expiresAt: String,
    val folderUrl: String,
    val folderPassword: String,
    val apps: List<InstallerAppV5Document>,
) {
    override fun toString(): String = "InstallerDistributionConfigV5Payload(environment=$environment, revision=$catalogRevision)"
}

@Serializable
data class InstallerAppV5Document(
    val appId: String,
    val archiveFileName: String,
    val displayName: String,
    val enabled: Boolean,
    val installPolicy: String,
    val sortOrder: Int,
    val versionCode: Long = 0,
    val versionName: String = "",
    val apkSizeBytes: Long = 0,
    val packageName: String = "",
    val certificateSha256s: List<String> = emptyList(),
    val apkSha256: String = "",
)

@Serializable
data class InstallerAppIconDocument(
    val assetId: String,
    val assetVersion: Int = 1,
    val url: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val sha256: String,
) {
    fun toDomain(): AppIconAsset = AppIconAsset(
        assetId = assetId,
        assetVersion = assetVersion,
        url = url,
        mimeType = mimeType,
        width = width,
        height = height,
        sizeBytes = sizeBytes,
        sha256 = sha256,
    )
}

/** Authenticated release selected by the existing installation session. */
data class InstallerComponentSource(
    val componentId: String,
    val archiveFileName: String,
    val required: Boolean,
    val displayName: String,
    val description: String = "",
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    /** Filled from the APK, never from Cloud. */
    val minAndroidSdk: Int = 1,
    val packageName: String = "",
    val certificateSha256: String = "",
    val apkEntryName: String = "",
    /** Cloud-declared Android version metadata. */
    val versionCode: Long = 0L,
    val versionName: String = "",
    val apkSizeBytes: Long = 0L,
    val iconAsset: AppIconAsset? = null,
    /** Exact release identity approved by the signed V5 catalog. */
    val certificateSha256s: Set<String> = emptySet(),
    val apkSha256: String = "",
) {
    val appId: String get() = componentId

    /**
     * Cloud's signed release metadata is the target for this preparation.
     * The same signed record also owns package, signer and file identity.
     */
    fun matchesDeclaredVersion(actual: ArtifactVersion): Boolean =
        versionCode > 0L &&
            actual.code == versionCode &&
            (versionName.isBlank() || actual.name == versionName)

    val displayVersionLabel: String?
        get() = formatArtifactVersionLabel(versionName)

    val displaySizeLabel: String?
        get() = formatArtifactSizeLabel(apkSizeBytes)

    val hasReleaseMetadata: Boolean
        get() = versionCode > 0L && versionName.isNotBlank() && apkSizeBytes > 0L

    fun matchesApprovedIdentity(actualPackage: String, actualCertificates: Set<String>): Boolean =
        packageName.isNotBlank() && actualPackage == packageName && certificateSha256s.isNotEmpty() &&
            actualCertificates.map { it.lowercase(Locale.ROOT) }.toSet() ==
            certificateSha256s.map { it.lowercase(Locale.ROOT) }.toSet()

    fun matchesApprovedFile(sizeBytes: Long, sha256: String): Boolean =
        apkSizeBytes == sizeBytes && apkSha256.isNotBlank() && apkSha256.equals(sha256, ignoreCase = true)

}

data class InstallerDistributionConfig(
    val configVersion: String = "",
    val channel: String = "",
    val expiresAt: Instant = Instant.EPOCH,
    val folderUrl: String = "",
    val folderPassword: String = "",
    val keyId: String = "",
    val signatureAlgorithm: String = "",
    /** Blank by default so a missing environment can never silently become production. */
    val environment: String = "",
    val issuedAtUtc: Instant = Instant.EPOCH,
    val catalogVersion: String = "",
    val catalogRevision: Long = 0L,
    val apps: List<InstallerComponentSource> = emptyList(),
) {
    fun declaredApps(): List<InstallerComponentSource> = apps

    fun effectiveCatalogVersion(): String = catalogVersion.ifBlank { configVersion }

    override fun toString(): String =
        "InstallerDistributionConfig(catalogVersion=${effectiveCatalogVersion()}, channel=$channel, " +
            "environment=$environment, expiresAt=$expiresAt, folderUrl=<redacted>, folderPassword=<redacted>, " +
            "apps=${declaredApps()}, keyId=$keyId, signatureAlgorithm=$signatureAlgorithm)"
}

/**
 * Immutable hand-off from the signed control plane to the single artifact
 * preparation owner. It contains decisions only; APK/ZIP files never live in
 * this plan and are resolved by the preparation coordinator.
 */
data class ArtifactPreparationPlan(
    val batch: InstallationBatchPlan,
    val config: InstallerDistributionConfig,
    val components: List<InstallerComponentSource>,
)

sealed interface ArtifactPreparationPlanResult {
    data class Ready(val plan: ArtifactPreparationPlan) : ArtifactPreparationPlanResult

    data class Failure(
        val reasonCode: String,
        val retryable: Boolean,
    ) : ArtifactPreparationPlanResult
}

sealed interface DistributionConfigLoadResult {
    data class Success(val config: InstallerDistributionConfig) : DistributionConfigLoadResult

    data class Failure(
        val reasonCode: String,
        val retryable: Boolean,
    ) : DistributionConfigLoadResult
}

/** Small durable anti-rollback record keyed by environment and channel. */
interface CatalogRevisionStore {
    fun highestRevision(environment: String, channel: String): Long
    fun recordAccepted(environment: String, channel: String, revision: Long, catalogVersion: String): Boolean
}

class InMemoryCatalogRevisionStore : CatalogRevisionStore {
    private val values = mutableMapOf<String, Pair<Long, String>>()

    @Synchronized
    override fun highestRevision(environment: String, channel: String): Long =
        values[key(environment, channel)]?.first ?: 0L

    @Synchronized
    override fun recordAccepted(
        environment: String,
        channel: String,
        revision: Long,
        catalogVersion: String,
    ): Boolean {
        val current = values[key(environment, channel)]
        if (current != null && revision < current.first) return false
        if (current != null && revision == current.first && catalogVersion != current.second) return false
        values[key(environment, channel)] = revision to catalogVersion
        return true
    }

    private fun key(environment: String, channel: String): String = "$environment\u0000$channel"
}

/** File-backed store used by the Android composition root; only monotonic metadata is persisted. */
class FileCatalogRevisionStore(private val file: File) : CatalogRevisionStore {
    private val lock = Any()

    override fun highestRevision(environment: String, channel: String): Long = synchronized(lock) {
        read()[key(environment, channel)]?.first ?: 0L
    }

    override fun recordAccepted(
        environment: String,
        channel: String,
        revision: Long,
        catalogVersion: String,
    ): Boolean = synchronized(lock) {
        val values = read()
        val current = values[key(environment, channel)]
        if (current != null && revision < current.first) return false
        if (current != null && revision == current.first && catalogVersion != current.second) return false
        values[key(environment, channel)] = revision to catalogVersion
        val properties = Properties()
        values.forEach { (entryKey, value) ->
            properties[entryKey] = "${value.first}|${value.second}"
        }
        return try {
            file.parentFile?.mkdirs()
            val temporary = file.resolveSibling("${file.name}.part")
            temporary.outputStream().use { properties.store(it, null) }
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
            file.isFile
        } catch (_: Exception) {
            false
        }
    }

    private fun read(): MutableMap<String, Pair<Long, String>> {
        if (!file.isFile || file.length() > MAX_REVISION_STORE_BYTES) return mutableMapOf()
        return runCatching {
            Properties().also { file.inputStream().use(it::load) }
                .entries.associate { entry ->
                    val value = entry.value.toString().split('|', limit = 2)
                    entry.key.toString() to ((value.firstOrNull()?.toLongOrNull() ?: 0L) to value.getOrElse(1) { "" })
                }.toMutableMap()
        }.getOrElse { mutableMapOf() }
    }

    private fun key(environment: String, channel: String): String = "$environment\u0000$channel"

    private companion object {
        const val MAX_REVISION_STORE_BYTES = 64 * 1024L
    }
}

/** Reads and validates the signed v4 control plane, with v3 history compatibility. */
class CloudInstallerDistributionConfigAdapter(
    private val transport: ReleaseCatalogTransport,
    private val signatureVerifier: CatalogSignatureVerifier,
    private val expectedChannel: String,
    private val acceptedSignatureAlgorithms: Set<String> = SUPPORTED_SIGNATURE_ALGORITHMS,
    private val now: () -> Instant = Instant::now,
    private val json: Json = CloudReleaseCatalogAdapter.STRICT_JSON,
    private val sourcePolicy: ReleaseSourcePolicy = ReleaseSourcePolicy(),
    private val revisionStore: CatalogRevisionStore = InMemoryCatalogRevisionStore(),
    private val expectedEnvironment: String = defaultEnvironmentForChannel(expectedChannel),
) {
    fun withRevisionStore(store: CatalogRevisionStore): CloudInstallerDistributionConfigAdapter =
        CloudInstallerDistributionConfigAdapter(
            transport = transport,
            signatureVerifier = signatureVerifier,
            expectedChannel = expectedChannel,
            acceptedSignatureAlgorithms = acceptedSignatureAlgorithms,
            now = now,
            json = json,
            sourcePolicy = sourcePolicy,
            revisionStore = store,
            expectedEnvironment = expectedEnvironment,
        )

    suspend fun load(): DistributionConfigLoadResult {
        val response = try {
            transport.fetch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CatalogTransportFailure) {
            return DistributionConfigLoadResult.Failure(failure.reasonCode, failure.retryable)
        } catch (_: Exception) {
            return DistributionConfigLoadResult.Failure("distribution_config_transport_failed", retryable = true)
        }
        if (response.statusCode !in 200..299) {
            return DistributionConfigLoadResult.Failure(
                "distribution_config_http_${response.statusCode}",
                retryable = response.statusCode >= 500,
            )
        }
        if (response.body.isEmpty() || response.body.size > MAX_CONFIG_BYTES) {
            return DistributionConfigLoadResult.Failure("distribution_config_size_invalid", retryable = false)
        }
        if (response.contentType?.lowercase()?.startsWith("text/html") == true) {
            return DistributionConfigLoadResult.Failure("distribution_config_html_response", retryable = true)
        }

        return loadV5(response.body)
    }

    private fun loadV5(body: ByteArray): DistributionConfigLoadResult {
        fun failure(reason: String) = DistributionConfigLoadResult.Failure(reason, retryable = false)
        val envelope = runCatching { json.decodeFromString<SignedInstallerConfigV5Envelope>(body.decodeUtf8Strict()) }
            .getOrNull() ?: return failure("distribution_config_envelope_invalid")
        if (envelope.keyId.isBlank() || envelope.signatureAlgorithm !in acceptedSignatureAlgorithms) {
            return failure("distribution_config_envelope_fields_missing")
        }
        val bytes = runCatching { Base64.getDecoder().decode(envelope.payloadBase64) }.getOrNull()
            ?: return failure("distribution_config_payload_encoding_invalid")
        if (bytes.isEmpty() || bytes.size > MAX_PAYLOAD_BYTES) return failure("distribution_config_payload_size_invalid")
        val signature = runCatching { Base64.getDecoder().decode(envelope.signatureBase64) }.getOrNull()
            ?: return failure("distribution_config_signature_encoding_invalid")
        if (signature.isEmpty() || !signatureVerifier.verify(envelope.keyId, envelope.signatureAlgorithm, bytes, signature)) {
            return failure("distribution_config_signature_invalid")
        }
        val document = runCatching { json.decodeFromString<InstallerDistributionConfigV5Payload>(bytes.decodeUtf8Strict()) }
            .getOrNull() ?: return failure("distribution_config_payload_invalid")
        if (document.schemaVersion != 5) return failure("distribution_config_payload_schema_unsupported")
        if (document.environment != expectedEnvironment || document.environment !in setOf("staging", "production")) {
            return failure("distribution_config_environment_invalid")
        }
        val issued = runCatching { Instant.parse(document.issuedAtUtc) }.getOrNull()
            ?: return failure("distribution_config_issued_at_invalid")
        val expiry = runCatching { Instant.parse(document.expiresAt) }.getOrNull()
            ?: return failure("distribution_config_expiry_invalid")
        if (issued.isAfter(now().plusSeconds(MAX_CLOCK_SKEW_SECONDS))) return failure("distribution_config_issued_at_in_future")
        if (!expiry.isAfter(now())) return failure("distribution_config_expired")
        if (!expiry.isAfter(issued)) return failure("distribution_config_time_window_invalid")
        if (!sourcePolicy.isLanzouFolderUrl(document.folderUrl) || !isValidPassword(document.folderPassword)) {
            return failure("distribution_config_folder_invalid")
        }
        if (document.catalogRevision <= 0) return failure("distribution_config_catalog_revision_invalid")
        val ids = mutableSetOf<String>()
        val names = mutableSetOf<String>()
        val packages = mutableSetOf<String>()
        val apps = mutableListOf<InstallerComponentSource>()
        val packagePattern = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
        val digestPattern = Regex("^[a-f0-9]{64}$")
        document.apps.forEach { app ->
            if (!APP_ID_PATTERN.matches(app.appId) || !ids.add(app.appId)) return failure("distribution_config_app_duplicate")
            if (!isSafeArchiveName(app.archiveFileName) || app.archiveFileName.toByteArray().size > MAX_ARCHIVE_NAME_BYTES ||
                !names.add(app.archiveFileName.lowercase(Locale.ROOT))) return failure("distribution_config_archive_file_name_invalid")
            if (app.displayName.isBlank() || app.displayName.toByteArray().size > MAX_DISPLAY_NAME_BYTES) return failure("distribution_config_display_name_invalid")
            if (app.sortOrder !in 0..100000 || app.installPolicy !in setOf("required", "optional") ||
                (!app.enabled && app.installPolicy == "required")) return failure("distribution_config_install_policy_invalid")
            if (!app.enabled) return@forEach
            if (!packagePattern.matches(app.packageName) || app.packageName.length > 255 || !packages.add(app.packageName)) {
                return failure("distribution_config_apk_package_invalid")
            }
            if (app.certificateSha256s.isEmpty() || app.certificateSha256s.size > 16 ||
                app.certificateSha256s.distinct().size != app.certificateSha256s.size ||
                app.certificateSha256s.any { !digestPattern.matches(it) } || !digestPattern.matches(app.apkSha256)) {
                return failure("distribution_config_apk_identity_invalid")
            }
            if (app.versionCode <= 0 || app.versionCode > 9007199254740991L || app.versionName.isBlank() ||
                app.versionName.toByteArray().size > MAX_VERSION_LABEL_BYTES || app.versionName.any { it.isISOControl() } ||
                app.apkSizeBytes !in 1..MAX_APK_SIZE_BYTES) return failure("distribution_config_apk_metadata_invalid")
            apps += InstallerComponentSource(
                componentId = app.appId, archiveFileName = app.archiveFileName, displayName = app.displayName,
                required = app.installPolicy == "required", sortOrder = app.sortOrder,
                packageName = app.packageName,
                certificateSha256 = app.certificateSha256s.sorted().first(), certificateSha256s = app.certificateSha256s.toSet(),
                apkSha256 = app.apkSha256, versionCode = app.versionCode, versionName = app.versionName,
                apkSizeBytes = app.apkSizeBytes,
            )
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (document.catalogRevision < revisionStore.highestRevision(document.environment, expectedChannel)) {
            return failure("distribution_config_rollback")
        }
        if (!revisionStore.recordAccepted(document.environment, expectedChannel, document.catalogRevision, digest)) {
            return failure("distribution_config_revision_store_failed")
        }
        val version = "v5-${document.catalogRevision}"
        return DistributionConfigLoadResult.Success(InstallerDistributionConfig(
            configVersion = version, catalogVersion = version, catalogRevision = document.catalogRevision,
            channel = expectedChannel, environment = document.environment, expiresAt = expiry, issuedAtUtc = issued,
            folderUrl = document.folderUrl, folderPassword = document.folderPassword,
            keyId = envelope.keyId, signatureAlgorithm = envelope.signatureAlgorithm,
            apps = apps.sortedWith(compareBy<InstallerComponentSource> { it.sortOrder }.thenBy { it.componentId }),
        ))
    }

    private fun isValidPassword(value: String): Boolean = value.toByteArray(Charsets.UTF_8).size <= MAX_PASSWORD_BYTES

    private fun isSafeArchiveName(value: String): Boolean = value.isNotBlank() &&
        value.endsWith(".zip", ignoreCase = true) &&
        value.none {
            it == '/' || it == '\\' || it == '\u0000' || it == '?' || it == '&' || it == '#' ||
                it == ':' || it.isISOControl()
        } &&
        !value.startsWith('.') &&
        value !in setOf(".", "..")

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 5
        val SUPPORTED_SCHEMA_VERSIONS = setOf(5)
        const val MAX_CONFIG_BYTES = 768 * 1024
        const val MAX_PAYLOAD_BYTES = 512 * 1024
        const val MAX_PASSWORD_BYTES = 512
        const val MAX_ARCHIVE_NAME_BYTES = 128
        const val MAX_DISPLAY_NAME_BYTES = 128
        const val MAX_VERSION_LABEL_BYTES = 64
        const val MAX_APK_SIZE_BYTES = 1L shl 30
        const val MAX_CLOCK_SKEW_SECONDS = 300L
        val SUPPORTED_SIGNATURE_ALGORITHMS = setOf("SHA256withECDSA", "Ed25519")
        val APP_ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    }


}

private fun defaultEnvironmentForChannel(channel: String): String = when (channel) {
    "debug" -> "staging"
    "release" -> "production"
    else -> ""
}

private fun ByteArray.decodeUtf8Strict(): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(this))
    .toString()
