package com.ninepointnine.helper.data.catalog

import com.ninepointnine.helper.domain.artifact.InstallerComponentTrustRegistry
import com.ninepointnine.helper.domain.artifact.InstallerPublisherTrustRegistry
import com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy
import com.ninepointnine.helper.domain.artifact.formatArtifactSizeLabel
import com.ninepointnine.helper.domain.artifact.formatArtifactVersionLabel
import com.ninepointnine.helper.domain.device.AuthorizationSetupDeclaration
import com.ninepointnine.helper.domain.device.AuthorizationPlanFactory
import com.ninepointnine.helper.domain.device.ManagedComponent
import com.ninepointnine.helper.domain.device.ManagedAppOp
import com.ninepointnine.helper.domain.device.ManagedRuntimePermission
import com.ninepointnine.helper.domain.device.ManagedSecureComponentList
import com.ninepointnine.helper.domain.device.ManagedSecureFlag
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.Properties
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Signed control-plane envelope for the dynamic Android application folder. */
@Serializable
data class SignedInstallerConfigEnvelope(
    val schemaVersion: Int,
    /** Kept as an envelope alias for older release tooling; v3 uses payload.catalogVersion. */
    val configVersion: String = "",
    val keyId: String,
    val signatureAlgorithm: String,
    val payloadBase64: String,
    val signatureBase64: String,
    val catalogVersion: String = "",
    /** Monotonic anti-rollback revision duplicated at the envelope boundary. */
    val catalogRevision: Long = 0L,
) {
    override fun toString(): String =
        "SignedInstallerConfigEnvelope(schemaVersion=$schemaVersion, configVersion=$configVersion, " +
            "keyId=$keyId, signatureAlgorithm=$signatureAlgorithm, payloadBase64=<redacted>, " +
            "signatureBase64=<redacted>, catalogVersion=$catalogVersion, catalogRevision=$catalogRevision)"
}

/**
 * The v3 payload is a complete signed snapshot.  The legacy fields remain
 * deserializable only so source-compatible test/build tooling can be migrated;
 * [CloudInstallerDistributionConfigAdapter] rejects schema v2 and only reads
 * the dynamic [apps] list.
 */
@Serializable
data class InstallerDistributionConfigPayload(
    val schemaVersion: Int,
    val environment: String = "",
    val channel: String,
    val issuedAtUtc: String = "",
    val expiresAt: String,
    val catalogVersion: String = "",
    val catalogRevision: Long = 0L,
    val folderUrl: String,
    val folderPassword: String,
    val previousVersionsUrl: String = "",
    val previousVersionsPassword: String = "",
    val apps: List<InstallerAppSourceDocument> = emptyList(),
) {
    override fun toString(): String =
        "InstallerDistributionConfigPayload(schemaVersion=$schemaVersion, environment=$environment, " +
            "channel=$channel, issuedAtUtc=$issuedAtUtc, expiresAt=$expiresAt, catalogVersion=$catalogVersion, " +
            "catalogRevision=$catalogRevision, folderUrl=<redacted>, folderPassword=<redacted>, " +
            "previousVersionsUrl=<redacted>, previousVersionsPassword=<redacted>, " +
            "apps=$apps)"
}

/** Dynamic application entry. appId is a server directory identifier, not a package name. */
@Serializable
data class InstallerAppSourceDocument(
    val appId: String,
    val archiveFileName: String,
    val displayName: String,
    val description: String = "",
    /** Android integer version code entered by Cloud release tooling. */
    val versionCode: Long = 0L,
    /** Android version name entered by Cloud release tooling. */
    val versionName: String = "",
    /** APK body size in bytes entered by Cloud release tooling. */
    val apkSizeBytes: Long = 0L,
    val enabled: Boolean = true,
    val installPolicy: String = "optional",
    val sortOrder: Int = 0,
    val minClientSchemaVersion: Int = 3,
    val trustProfileId: String = "",
    val deviceSetup: InstallerDeviceSetupDocument? = null,
)

/** Declarative, bounded device setup. It contains no shell text and no package identity. */
@Serializable
data class InstallerDeviceSetupDocument(
    val profileId: String = "",
    val actionIds: List<String> = emptyList(),
    val appOps: List<String> = emptyList(),
    val runtimePermissions: List<String> = emptyList(),
    val secureSettings: List<String> = emptyList(),
    val secureComponents: List<InstallerSecureComponentDocument> = emptyList(),
    val launchComponent: String? = null,
    val requiredServices: List<String> = emptyList(),
) {
    fun toDomainOrNull(): AuthorizationSetupDeclaration? {
        if (profileId.isNotBlank() && !PROFILE_ID_PATTERN.matches(profileId)) return null
        if (actionIds.any { !ACTION_ID_PATTERN.matches(it) } || actionIds.size != actionIds.toSet().size) return null
        return try {
            AuthorizationSetupDeclaration(
                appOps = appOps.map { value ->
                    ManagedAppOp.entries.first { it.name == value || it.wireName == value }
                }.toSet(),
                runtimePermissions = runtimePermissions.map { value ->
                    ManagedRuntimePermission.entries.first { it.name == value || it.wireName == value }
                }.toSet(),
                secureSettings = secureSettings.map { value ->
                    ManagedSecureFlag.entries.first { it.name == value || it.wireName == value }
                }.toSet(),
                secureComponents = secureComponents.map {
                    AuthorizationSetupDeclaration.SecureComponent(
                        setting = ManagedSecureComponentList.entries.first { setting ->
                            setting.name == it.setting || setting.wireName == it.setting
                        },
                        targetComponent = it.targetComponent,
                    )
                }.toSet(),
                launchComponent = launchComponent,
                requiredServices = requiredServices.toSet(),
                profileId = profileId,
                actionIds = actionIds.toSet(),
            )
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        val PROFILE_ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
        val ACTION_ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    }
}

@Serializable
data class InstallerSecureComponentDocument(
    val setting: String,
    val targetComponent: String,
)

/**
 * Source-compatible v2 DTO. It is not consumed by the runtime v3 parser.
 */
@Serializable
data class InstallerComponentSourceDocument(
    val componentId: String,
    val archiveFileName: String,
    val required: Boolean,
)

/**
 * Runtime application source. The componentId name is retained internally so
 * the installation state machine does not need a second parallel model.
 */
data class InstallerComponentSource(
    val componentId: String,
    val archiveFileName: String,
    val required: Boolean,
    val displayName: String,
    val description: String = "",
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val minClientSchemaVersion: Int = 3,
    val deviceSetup: AuthorizationSetupDeclaration? = null,
    /** Filled from the APK, never from Cloud. */
    val minAndroidSdk: Int = 1,
    val packageName: String = "",
    val certificateSha256: String = "",
    val apkEntryName: String = "",
    val clientSupported: Boolean = true,
    val trustProfileId: String = "",
    /** Cloud-declared Android version metadata. */
    val versionCode: Long = 0L,
    val versionName: String = "",
    val apkSizeBytes: Long = 0L,
) {
    val appId: String get() = componentId

    val displayVersionLabel: String?
        get() = formatArtifactVersionLabel(versionName)

    val displaySizeLabel: String?
        get() = formatArtifactSizeLabel(apkSizeBytes)

    val hasReleaseMetadata: Boolean
        get() = versionCode > 0L && versionName.isNotBlank() && apkSizeBytes > 0L

}

typealias InstallerAppSource = InstallerComponentSource

data class InstallerDistributionConfig(
    val configVersion: String = "",
    val channel: String = "",
    val expiresAt: Instant = Instant.EPOCH,
    val folderUrl: String = "",
    val folderPassword: String = "",
    val previousVersionsUrl: String = "",
    val previousVersionsPassword: String = "",
    /** Deprecated alias; production code uses [apps]. */
    val components: List<InstallerComponentSource> = emptyList(),
    val keyId: String = "",
    val signatureAlgorithm: String = "",
    /** Blank by default so a missing environment can never silently become production. */
    val environment: String = "",
    val issuedAtUtc: Instant = Instant.EPOCH,
    val catalogVersion: String = "",
    val catalogRevision: Long = 0L,
    val apps: List<InstallerComponentSource> = emptyList(),
) {
    /** Canonical dynamic list. Deprecated components are never consulted by runtime code. */
    fun declaredApps(): List<InstallerComponentSource> = apps

    fun effectiveCatalogVersion(): String = catalogVersion.ifBlank { configVersion }

    override fun toString(): String =
        "InstallerDistributionConfig(catalogVersion=${effectiveCatalogVersion()}, channel=$channel, " +
            "environment=$environment, expiresAt=$expiresAt, folderUrl=<redacted>, folderPassword=<redacted>, " +
            "apps=${declaredApps()}, keyId=$keyId, signatureAlgorithm=$signatureAlgorithm)"
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

/** Reads and validates the signed v3 control plane. */
class CloudInstallerDistributionConfigAdapter(
    private val transport: ReleaseCatalogTransport,
    private val signatureVerifier: CatalogSignatureVerifier,
    private val expectedChannel: String,
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

        val envelope = try {
            json.decodeFromString<SignedInstallerConfigEnvelope>(response.body.decodeUtf8Strict())
        } catch (_: Exception) {
            return DistributionConfigLoadResult.Failure("distribution_config_envelope_invalid", retryable = false)
        }
        if (envelope.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return DistributionConfigLoadResult.Failure("distribution_config_schema_unsupported", retryable = false)
        }
        if (envelope.keyId.isBlank() || envelope.signatureAlgorithm !in SUPPORTED_SIGNATURE_ALGORITHMS) {
            return DistributionConfigLoadResult.Failure("distribution_config_envelope_fields_missing", retryable = false)
        }
        if (envelope.catalogRevision <= 0L) {
            return DistributionConfigLoadResult.Failure("distribution_config_catalog_revision_invalid", retryable = false)
        }
        val payload = try {
            Base64.getDecoder().decode(envelope.payloadBase64)
        } catch (_: IllegalArgumentException) {
            return DistributionConfigLoadResult.Failure("distribution_config_payload_encoding_invalid", retryable = false)
        }
        if (payload.isEmpty() || payload.size > MAX_PAYLOAD_BYTES) {
            return DistributionConfigLoadResult.Failure("distribution_config_payload_size_invalid", retryable = false)
        }
        val signature = try {
            Base64.getDecoder().decode(envelope.signatureBase64)
        } catch (_: IllegalArgumentException) {
            return DistributionConfigLoadResult.Failure("distribution_config_signature_encoding_invalid", retryable = false)
        }
        if (signature.isEmpty() || !signatureVerifier.verify(envelope.keyId, envelope.signatureAlgorithm, payload, signature)) {
            return DistributionConfigLoadResult.Failure("distribution_config_signature_invalid", retryable = false)
        }

        val document = try {
            json.decodeFromString<InstallerDistributionConfigPayload>(payload.decodeUtf8Strict())
        } catch (_: Exception) {
            return DistributionConfigLoadResult.Failure("distribution_config_payload_invalid", retryable = false)
        }
        if (document.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return DistributionConfigLoadResult.Failure("distribution_config_payload_schema_unsupported", retryable = false)
        }
        if (document.channel != expectedChannel || !CHANNEL_PATTERN.matches(document.channel)) {
            return DistributionConfigLoadResult.Failure("distribution_config_channel_invalid", retryable = false)
        }
        if (!ENVIRONMENT_PATTERN.matches(document.environment) || document.environment != expectedEnvironment) {
            return DistributionConfigLoadResult.Failure("distribution_config_environment_invalid", retryable = false)
        }
        val issuedAt = try {
            Instant.parse(document.issuedAtUtc)
        } catch (_: Exception) {
            return DistributionConfigLoadResult.Failure("distribution_config_issued_at_invalid", retryable = false)
        }
        val expiresAt = try {
            Instant.parse(document.expiresAt)
        } catch (_: Exception) {
            return DistributionConfigLoadResult.Failure("distribution_config_expiry_invalid", retryable = false)
        }
        val currentTime = now()
        if (issuedAt.isAfter(currentTime.plusSeconds(MAX_CLOCK_SKEW_SECONDS))) {
            return DistributionConfigLoadResult.Failure("distribution_config_issued_at_in_future", retryable = false)
        }
        if (!expiresAt.isAfter(currentTime)) {
            return DistributionConfigLoadResult.Failure("distribution_config_expired", retryable = true)
        }
        if (!expiresAt.isAfter(issuedAt)) {
            return DistributionConfigLoadResult.Failure("distribution_config_time_window_invalid", retryable = false)
        }
        if (document.catalogVersion.isBlank() || document.catalogVersion.toByteArray().size > MAX_CATALOG_VERSION_BYTES ||
            !CATALOG_VERSION_PATTERN.matches(document.catalogVersion) || document.catalogRevision <= 0L
        ) {
            return DistributionConfigLoadResult.Failure("distribution_config_catalog_revision_invalid", retryable = false)
        }
        if (envelope.catalogRevision != document.catalogRevision) {
            return DistributionConfigLoadResult.Failure("distribution_config_catalog_revision_mismatch", retryable = false)
        }
        if (envelope.configVersion.isNotBlank() && envelope.configVersion != document.catalogVersion) {
            return DistributionConfigLoadResult.Failure("distribution_config_version_mismatch", retryable = false)
        }
        if (envelope.catalogVersion.isNotBlank() && envelope.catalogVersion != document.catalogVersion) {
            return DistributionConfigLoadResult.Failure("distribution_config_version_mismatch", retryable = false)
        }
        if (!isValidPassword(document.folderPassword) || !sourcePolicy.isLanzouFolderUrl(document.folderUrl) ||
            !isValidPassword(document.previousVersionsPassword) ||
            (document.previousVersionsUrl.isNotBlank() && !sourcePolicy.isLanzouFolderUrl(document.previousVersionsUrl)) ||
            (document.previousVersionsUrl.isBlank() && document.previousVersionsPassword.isNotBlank())
        ) {
            return DistributionConfigLoadResult.Failure("distribution_config_folder_invalid", retryable = false)
        }
        val apps = when (val result = validateApps(document.apps)) {
            is AppsValidation.Failure -> return DistributionConfigLoadResult.Failure(result.reasonCode, retryable = false)
            is AppsValidation.Success -> result.apps
        }
        val desktop = apps.firstOrNull {
            it.componentId == InstallerComponentTrustRegistry.DESKTOP_COMPONENT_ID
        }
        if (desktop == null || !desktop.enabled || !desktop.required) {
            return DistributionConfigLoadResult.Failure("distribution_config_desktop_missing", retryable = false)
        }
        if (!desktop.clientSupported) {
            return DistributionConfigLoadResult.Failure(
                "distribution_config_desktop_client_schema_unsupported",
                retryable = false,
            )
        }
        val highest = revisionStore.highestRevision(document.environment, document.channel)
        if (document.catalogRevision < highest) {
            return DistributionConfigLoadResult.Failure("distribution_config_rollback", retryable = false)
        }
        if (!revisionStore.recordAccepted(document.environment, document.channel, document.catalogRevision, document.catalogVersion)) {
            return DistributionConfigLoadResult.Failure("distribution_config_revision_store_failed", retryable = true)
        }
        return DistributionConfigLoadResult.Success(
            InstallerDistributionConfig(
                configVersion = document.catalogVersion,
                channel = document.channel,
                expiresAt = expiresAt,
                folderUrl = document.folderUrl,
                folderPassword = document.folderPassword,
                previousVersionsUrl = document.previousVersionsUrl,
                previousVersionsPassword = document.previousVersionsPassword,
                components = emptyList(),
                keyId = envelope.keyId,
                signatureAlgorithm = envelope.signatureAlgorithm,
                environment = document.environment,
                issuedAtUtc = issuedAt,
                catalogVersion = document.catalogVersion,
                catalogRevision = document.catalogRevision,
                apps = apps,
            ),
        )
    }

    private fun validateApps(documents: List<InstallerAppSourceDocument>): AppsValidation {
        if (documents.isEmpty()) return AppsValidation.Failure("distribution_config_apps_empty")
        val ids = documents.map { it.appId }
        if (ids.size != ids.toSet().size) return AppsValidation.Failure("distribution_config_app_duplicate")
        val names = documents.map { it.archiveFileName.lowercase(Locale.ROOT) }
        if (names.size != names.toSet().size) return AppsValidation.Failure("distribution_config_archive_duplicate")
        val apps = documents.map { document ->
            if (!APP_ID_PATTERN.matches(document.appId) || document.appId.toByteArray().size > MAX_APP_ID_BYTES) {
                return AppsValidation.Failure("distribution_config_app_id_invalid")
            }
            if (!isSafeArchiveName(document.archiveFileName) || document.archiveFileName.toByteArray().size > MAX_ARCHIVE_NAME_BYTES) {
                return AppsValidation.Failure("distribution_config_archive_file_name_invalid")
            }
            if (document.displayName.isBlank() || document.displayName.toByteArray().size > MAX_DISPLAY_NAME_BYTES) {
                return AppsValidation.Failure("distribution_config_display_name_invalid")
            }
            if (document.description.toByteArray().size > MAX_DESCRIPTION_BYTES) {
                return AppsValidation.Failure("distribution_config_description_invalid")
            }
            if (document.versionCode <= 0L) {
                return AppsValidation.Failure("distribution_config_version_code_invalid")
            }
            if (!isValidVersionName(document.versionName)) {
                return AppsValidation.Failure("distribution_config_version_name_invalid")
            }
            if (document.apkSizeBytes <= 0L || document.apkSizeBytes > MAX_APK_SIZE_BYTES) {
                return AppsValidation.Failure("distribution_config_apk_size_invalid")
            }
            if (document.minClientSchemaVersion <= 0) {
                return AppsValidation.Failure("distribution_config_min_client_schema_invalid")
            }
            if (document.enabled &&
                (!TRUST_PROFILE_PATTERN.matches(document.trustProfileId) ||
                    !InstallerPublisherTrustRegistry.isKnownProfile(document.trustProfileId))
            ) {
                return AppsValidation.Failure("distribution_config_trust_profile_invalid")
            }
            val expectedProfile = InstallerComponentTrustRegistry.expectedTrustProfileId(document.appId)
            if (expectedProfile != null && document.trustProfileId != expectedProfile) {
                return AppsValidation.Failure("distribution_config_trust_profile_mismatch")
            }
            val required = when (document.installPolicy.lowercase(Locale.ROOT)) {
                "required" -> true
                "optional" -> false
                else -> return AppsValidation.Failure("distribution_config_install_policy_invalid")
            }
            val rawSetup = document.deviceSetup
                ?: return AppsValidation.Failure("distribution_config_device_setup_missing")
            val setup = rawSetup.toDomainOrNull()
                ?: return AppsValidation.Failure("distribution_config_device_setup_invalid")
            val trustedComponent = InstallerComponentTrustRegistry.get(document.appId)
            if (trustedComponent != null && !AuthorizationPlanFactory.validateComponent(
                    ManagedComponent(
                        componentId = document.appId,
                        packageName = trustedComponent.packageName,
                        setup = setup,
                        order = document.sortOrder,
                    ),
                )
            ) {
                return AppsValidation.Failure("distribution_config_device_setup_invalid")
            }
            if (rawSetup.appOps.size != rawSetup.appOps.toSet().size ||
                    rawSetup.runtimePermissions.size != rawSetup.runtimePermissions.toSet().size ||
                    rawSetup.secureSettings.size != rawSetup.secureSettings.toSet().size ||
                    rawSetup.requiredServices.size != rawSetup.requiredServices.toSet().size ||
                    rawSetup.actionIds.size != rawSetup.actionIds.toSet().size ||
                    rawSetup.secureComponents.size != rawSetup.secureComponents
                        .distinctBy { it.setting to it.targetComponent }
                        .size
            ) {
                return AppsValidation.Failure("distribution_config_device_setup_duplicate")
            }
            if (setup?.launchComponent?.let { !isSafeComponentName(it) } == true || setup?.requiredServices?.any { !isSafeComponentName(it) } == true) {
                return AppsValidation.Failure("distribution_config_device_setup_component_invalid")
            }
            InstallerComponentSource(
                componentId = document.appId,
                archiveFileName = document.archiveFileName,
                required = required,
                displayName = document.displayName,
                description = document.description,
                enabled = document.enabled,
                sortOrder = document.sortOrder,
                minClientSchemaVersion = document.minClientSchemaVersion,
                deviceSetup = setup,
                trustProfileId = document.trustProfileId,
                clientSupported = document.minClientSchemaVersion <= SUPPORTED_SCHEMA_VERSION,
                versionCode = document.versionCode,
                versionName = document.versionName,
                apkSizeBytes = document.apkSizeBytes,
            )
        }.sortedWith(compareBy<InstallerComponentSource> { it.sortOrder }.thenBy { it.componentId })
        return AppsValidation.Success(apps)
    }

    private fun isValidPassword(value: String): Boolean = value.toByteArray(Charsets.UTF_8).size <= MAX_PASSWORD_BYTES

    private fun isValidVersionName(value: String): Boolean =
        value.toByteArray(Charsets.UTF_8).size <= MAX_VERSION_LABEL_BYTES &&
            VERSION_NAME_PATTERN.matches(value)

    private fun isSafeArchiveName(value: String): Boolean = value.isNotBlank() &&
        value.endsWith(".zip", ignoreCase = true) &&
        value.none {
            it == '/' || it == '\\' || it == '\u0000' || it == '?' || it == '&' || it == '#' ||
                it == ':' || it.isISOControl()
        } &&
        !value.startsWith('.') &&
        value !in setOf(".", "..")

    private fun isSafeComponentName(value: String): Boolean = value.isNotBlank() &&
        value.length <= MAX_COMPONENT_NAME_CHARS &&
        value.none {
            it.isISOControl() || it == '\'' || it == '"' || it == ';' || it == '|' ||
                it == '&' || it == '`' || it == '$' || it == '<' || it == '>' ||
                it == '(' || it == ')' || it == '{' || it == '}' || it == '[' || it == ']'
        }

    private companion object {
        const val SUPPORTED_SCHEMA_VERSION = 3
        const val MAX_CONFIG_BYTES = 768 * 1024
        const val MAX_PAYLOAD_BYTES = 512 * 1024
        const val MAX_PASSWORD_BYTES = 512
        const val MAX_APP_ID_BYTES = 64
        const val MAX_ARCHIVE_NAME_BYTES = 128
        const val MAX_DISPLAY_NAME_BYTES = 128
        const val MAX_DESCRIPTION_BYTES = 512
        const val MAX_VERSION_LABEL_BYTES = 64
        const val MAX_APK_SIZE_BYTES = 1L shl 30
        const val MAX_CATALOG_VERSION_BYTES = 128
        const val MAX_COMPONENT_NAME_CHARS = 256
        const val MAX_CLOCK_SKEW_SECONDS = 300L
        val VERSION_NAME_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}$")
        val SUPPORTED_SIGNATURE_ALGORITHMS = setOf("SHA256withECDSA", "Ed25519")
        val APP_ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
        val CHANNEL_PATTERN = Regex("^[a-z][a-z0-9-]{0,31}$")
        val ENVIRONMENT_PATTERN = Regex("^[a-z][a-z0-9-]{0,31}$")
        val CATALOG_VERSION_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        val TRUST_PROFILE_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
        val PROFILE_ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
        val ACTION_ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    }

    private sealed interface AppsValidation {
        data class Success(val apps: List<InstallerComponentSource>) : AppsValidation
        data class Failure(val reasonCode: String) : AppsValidation
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
