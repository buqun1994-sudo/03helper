package com.ninepointnine.helper.application.maintenance

import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactManifestValidator
import com.ninepointnine.helper.domain.artifact.ArtifactSource
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.CompatibilityRange
import com.ninepointnine.helper.domain.artifact.ManifestValidation
import com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy
import com.ninepointnine.helper.domain.artifact.SourcePlan
import com.ninepointnine.helper.domain.device.AuthorizationPlanFactory
import com.ninepointnine.helper.domain.device.DeviceCapability
import com.ninepointnine.helper.domain.session.ComponentDescriptor
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.session.DeviceSummary
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.domain.session.InstallationSessionState
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.domain.session.MaintenanceActionRecord
import com.ninepointnine.helper.domain.session.MaintenanceActionStatus
import com.ninepointnine.helper.domain.session.MaintenanceSnapshot
import com.ninepointnine.helper.domain.session.ManagedApplicationStatus
import com.ninepointnine.helper.domain.session.ComponentStatus
import com.ninepointnine.helper.data.catalog.DeviceSetupWireDocument
import com.ninepointnine.helper.data.catalog.SecureComponentWireDocument
import com.ninepointnine.helper.domain.session.SessionEvidence
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists only the successful maintenance boundary in the app-private files
 * directory. A malformed or stale record is discarded rather than projected
 * as a usable installation.
 */
class MaintenanceSessionStore(
    private val file: File,
    private val json: Json = STORE_JSON,
    private val sourcePolicy: ReleaseSourcePolicy = ReleaseSourcePolicy(),
) {
    fun load(): InstallationSessionSnapshot? {
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_STORE_BYTES) return null
        return try {
            val stored = json.decodeFromString<StoredMaintenanceState>(file.readText(Charsets.UTF_8))
            stored.toSnapshotOrNull(sourcePolicy)
        } catch (_: Exception) {
            null
        }
    }

    fun save(snapshot: InstallationSessionSnapshot): Boolean {
        if (snapshot.state != InstallationSessionState.MAINTENANCE &&
            snapshot.state != InstallationSessionState.SUCCEEDED
        ) {
            return false
        }
        return try {
            val stored = StoredMaintenanceState.from(snapshot)
            if (stored.toSnapshotOrNull(sourcePolicy) == null) return false
            val temporary = file.resolveSibling("${file.name}.part")
            file.parentFile?.mkdirs()
            temporary.writeText(
                json.encodeToString(stored),
                Charsets.UTF_8,
            )
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            true
        } catch (_: Exception) {
            file.resolveSibling("${file.name}.part").delete()
            false
        }
    }

    fun clear(): Boolean {
        val removed = !file.exists() || file.delete()
        val temporaryRemoved = !file.resolveSibling("${file.name}.part").exists() ||
            file.resolveSibling("${file.name}.part").delete()
        return removed && temporaryRemoved
    }

    private companion object {
        val STORE_JSON: Json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
            isLenient = false
            explicitNulls = false
        }
    }
}

private const val CURRENT_SCHEMA = 1
private const val MAX_STORE_BYTES = 2L * 1024L * 1024L

@Serializable
private data class StoredMaintenanceState(
    val schemaVersion: Int,
    val device: StoredDevice,
    val components: List<StoredComponent>,
    val selectedOptionalComponentIds: Set<String>,
    val artifactManifests: List<StoredManifest>,
    val availableManifests: List<StoredManifest>,
    val catalogVersion: String?,
    val catalogRevision: Long = 0L,
    val catalogKeyId: String?,
    val catalogSignatureAlgorithm: String?,
    val availableCatalogVersion: String?,
    val availableCatalogRevision: Long = 0L,
    val availableCatalogKeyId: String?,
    val availableCatalogSignatureAlgorithm: String?,
    val installed: Set<String>,
    val configured: Set<String>,
    val available: Set<String>,
    val managedApplications: List<StoredApplication>,
    val lastAction: StoredMaintenanceAction? = null,
) {
    companion object {
        fun from(snapshot: InstallationSessionSnapshot): StoredMaintenanceState = StoredMaintenanceState(
            schemaVersion = CURRENT_SCHEMA,
            device = StoredDevice(
                id = snapshot.device?.id.orEmpty(),
                displayName = snapshot.device?.displayName.orEmpty(),
                androidSdk = snapshot.device?.androidSdk,
                capabilities = snapshot.device?.capabilities.orEmpty().map { it.name },
            ),
            components = snapshot.components.map { component ->
                StoredComponent(
                    id = component.id,
                    displayName = component.displayName,
                    required = component.required,
                    versionLabel = component.versionLabel,
                    sizeLabel = component.sizeLabel,
                    compatibilityLabel = component.compatibilityLabel,
                    description = component.description,
                    status = component.status.name,
                    errorReason = component.errorReason,
                )
            },
            selectedOptionalComponentIds = snapshot.selectedOptionalComponentIds,
            artifactManifests = snapshot.artifactManifests.map(StoredManifest::from),
            availableManifests = snapshot.maintenance.availableManifests.map(StoredManifest::from),
            catalogVersion = snapshot.catalogVersion,
            catalogRevision = snapshot.catalogRevision,
            catalogKeyId = snapshot.catalogKeyId,
            catalogSignatureAlgorithm = snapshot.catalogSignatureAlgorithm,
            availableCatalogVersion = snapshot.maintenance.availableCatalogVersion,
            availableCatalogRevision = snapshot.maintenance.availableCatalogRevision,
            availableCatalogKeyId = snapshot.maintenance.availableCatalogKeyId,
            availableCatalogSignatureAlgorithm = snapshot.maintenance.availableCatalogSignatureAlgorithm,
            installed = snapshot.evidence.installed,
            configured = snapshot.evidence.configured,
            available = snapshot.evidence.available,
            managedApplications = snapshot.maintenance.managedApplications.map(StoredApplication::from),
            lastAction = snapshot.maintenance.lastAction?.let(StoredMaintenanceAction::from),
        )
    }

    fun toSnapshotOrNull(sourcePolicy: ReleaseSourcePolicy): InstallationSessionSnapshot? {
        if (
            schemaVersion != CURRENT_SCHEMA ||
            device.id.isBlank() ||
            device.displayName.isBlank() ||
            device.androidSdk?.let { it !in 1..1000 } == true ||
            components.isEmpty()
        ) {
            return null
        }
        val parsedCapabilities = try {
            val values = device.capabilities.map { DeviceCapability.valueOf(it) }
            if (values.toSet().size != values.size) return null
            values.toSet()
        } catch (_: Exception) {
            return null
        }
        val parsedComponents = components.mapNotNull { it.toDomainOrNull() }
        if (parsedComponents.size != components.size) return null
        val manifests = artifactManifests.mapNotNull { it.toDomainOrNull() }
        if (manifests.size != artifactManifests.size) return null
        if (manifests.isNotEmpty() && !validateManifests(manifests, sourcePolicy)) {
            return null
        }
        val parsedAvailableManifests = availableManifests.mapNotNull { it.toDomainOrNull() }
        if (parsedAvailableManifests.size != availableManifests.size) return null
        if (parsedAvailableManifests.isNotEmpty() && !validateManifests(parsedAvailableManifests, sourcePolicy)) {
            return null
        }
        val componentIds = parsedComponents.map { it.id }.toSet()
        if (
            componentIds.size != parsedComponents.size ||
            parsedComponents.any { it.id.isBlank() || it.displayName.isBlank() } ||
            parsedComponents.none {
                it.id == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID && it.required
            } ||
            selectedOptionalComponentIds.any {
                it !in componentIds || it == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID
            }
        ) {
            return null
        }
        if (
            installed.any { it !in componentIds } ||
            configured.any { it !in componentIds } ||
            available.any { it !in componentIds }
        ) {
            return null
        }
        if (
            manifests.isNotEmpty() &&
            !manifests.map { it.componentId }.toSet().all { it in componentIds }
        ) {
            return null
        }
        if (
            parsedAvailableManifests.isNotEmpty() &&
            (parsedAvailableManifests.any { it.componentId.isBlank() } ||
                !parsedAvailableManifests.map { it.componentId }.toSet().all { it in componentIds })
        ) {
            return null
        }
        if (catalogRevision < 0L || availableCatalogRevision < 0L ||
            (catalogRevision > 0L && catalogVersion == null) ||
            (availableCatalogRevision > 0L && availableCatalogVersion == null) ||
            (catalogRevision > 0L && availableCatalogRevision > 0L &&
                availableCatalogRevision < catalogRevision) ||
            !validCatalogMetadata(catalogVersion, catalogKeyId, catalogSignatureAlgorithm) ||
            !validCatalogMetadata(
                availableCatalogVersion,
                availableCatalogKeyId,
                availableCatalogSignatureAlgorithm,
            )
        ) {
            return null
        }
        val applications = managedApplications.mapNotNull { it.toDomainOrNull() }
        if (
            applications.size != managedApplications.size ||
            applications.map { it.componentId }.toSet().size != applications.size ||
            applications.any { it.componentId.isBlank() }
        ) {
            return null
        }
        val parsedLastAction = lastAction?.toDomainOrNull() ?: if (lastAction == null) null else return null
        if (manifests.isNotEmpty() &&
            AuthorizationPlanFactory.createForManifests(manifests) !is
                com.ninepointnine.helper.domain.device.AuthorizationPlanBuildResult.Ready
        ) {
            return null
        }
        if (parsedAvailableManifests.isNotEmpty() &&
            AuthorizationPlanFactory.createForManifests(parsedAvailableManifests) !is
                com.ninepointnine.helper.domain.device.AuthorizationPlanBuildResult.Ready
        ) {
            return null
        }
        val unlistedComponents = applications
            .filter { it.installed && it.componentId !in componentIds }
            .map { it.componentId }
            .distinct()
            .map { id ->
                ComponentDescriptor(
                    id = id,
                    displayName = id,
                    required = false,
                    status = ComponentStatus.UNLISTED,
                    errorReason = "unlisted",
                )
            }
        return InstallationSessionSnapshot(
            state = InstallationSessionState.MAINTENANCE,
            device = DeviceSummary(
                id = device.id,
                displayName = device.displayName,
                connectionStatus = DeviceConnectionStatus.DISCONNECTED,
                androidSdk = device.androidSdk,
                capabilities = parsedCapabilities,
            ),
            components = parsedComponents + unlistedComponents.filter { unlisted ->
                parsedComponents.none { component -> component.id == unlisted.id }
            },
            selectedOptionalComponentIds = selectedOptionalComponentIds,
            artifactManifests = manifests,
            catalogVersion = catalogVersion,
            catalogRevision = catalogRevision,
            catalogKeyId = catalogKeyId,
            catalogSignatureAlgorithm = catalogSignatureAlgorithm,
            evidence = SessionEvidence(
                installed = installed,
                configured = configured,
                available = available,
            ),
            maintenance = MaintenanceSnapshot(
                lastAction = parsedLastAction,
                managedApplications = applications,
                availableManifests = parsedAvailableManifests,
                availableCatalogVersion = availableCatalogVersion,
                availableCatalogRevision = availableCatalogRevision,
                availableCatalogKeyId = availableCatalogKeyId,
                availableCatalogSignatureAlgorithm = availableCatalogSignatureAlgorithm,
            ),
        )
    }

    private fun validateManifests(
        manifests: List<ArtifactManifest>,
        sourcePolicy: ReleaseSourcePolicy,
    ): Boolean {
        if (ArtifactManifestValidator.validateCatalog(manifests) !is ManifestValidation.Valid) {
            return false
        }
        if (manifests.any { it.componentId.isBlank() } ||
            manifests.none { it.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID }
        ) {
            return false
        }
        return manifests.all { manifest ->
            sourcePolicy.plan(manifest) is SourcePlan.Accepted
        }
    }

    private fun validCatalogMetadata(
        version: String?,
        keyId: String?,
        signatureAlgorithm: String?,
    ): Boolean {
        if (version == null && keyId == null && signatureAlgorithm == null) return true
        return !version.isNullOrBlank() &&
            !keyId.isNullOrBlank() &&
            signatureAlgorithm in SUPPORTED_SIGNATURE_ALGORITHMS
    }
}

private val SUPPORTED_SIGNATURE_ALGORITHMS = setOf("SHA256withECDSA", "Ed25519")

@Serializable
private data class StoredMaintenanceAction(
    val actionId: String,
    val status: String,
    val resultCode: String?,
    val reasonCode: String?,
    val retryable: Boolean,
) {
    companion object {
        fun from(action: MaintenanceActionRecord): StoredMaintenanceAction = StoredMaintenanceAction(
            actionId = action.actionId.name,
            status = action.status.name,
            resultCode = action.resultCode,
            reasonCode = action.reasonCode,
            retryable = action.retryable,
        )
    }

    fun toDomainOrNull(): MaintenanceActionRecord? {
        return try {
            val parsedAction = MaintenanceActionId.valueOf(actionId)
            val parsedStatus = MaintenanceActionStatus.valueOf(status)
            when (parsedStatus) {
                MaintenanceActionStatus.RUNNING -> null
                MaintenanceActionStatus.SUCCEEDED -> if (
                    !resultCode.isNullOrBlank() && reasonCode.isNullOrBlank()
                ) {
                    MaintenanceActionRecord(
                        actionId = parsedAction,
                        status = parsedStatus,
                        resultCode = resultCode,
                        reasonCode = null,
                        retryable = false,
                    )
                } else {
                    null
                }
                MaintenanceActionStatus.FAILED -> if (
                    !reasonCode.isNullOrBlank() && resultCode.isNullOrBlank()
                ) {
                    MaintenanceActionRecord(
                        actionId = parsedAction,
                        status = parsedStatus,
                        resultCode = null,
                        reasonCode = reasonCode,
                        retryable = retryable,
                    )
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
private data class StoredDevice(
    val id: String,
    val displayName: String,
    val androidSdk: Int?,
    val capabilities: List<String>,
)

@Serializable
private data class StoredComponent(
    val id: String,
    val displayName: String,
    val required: Boolean,
    val versionLabel: String?,
    val sizeLabel: String?,
    val compatibilityLabel: String?,
    val description: String = "",
    val status: String = ComponentStatus.READING.name,
    val errorReason: String? = null,
) {
    fun toDomainOrNull(): ComponentDescriptor? = runCatching {
        ComponentDescriptor(
            id = id,
            displayName = displayName,
            required = required,
            versionLabel = versionLabel,
            sizeLabel = sizeLabel,
            compatibilityLabel = compatibilityLabel,
            description = description,
            status = ComponentStatus.valueOf(status),
            errorReason = errorReason,
        )
    }.getOrNull()
}

@Serializable
private data class StoredManifest(
    val schemaVersion: Int,
    val componentId: String,
    val displayName: String,
    val description: String = "",
    val required: Boolean,
    val versionName: String,
    val versionCode: Long,
    val minAndroidSdk: Int,
    val maxAndroidSdk: Int?,
    val minInstallerVersion: String?,
    val maxInstallerVersion: String?,
    val archiveFormat: String,
    val archiveFileName: String,
    val archiveSizeBytes: Long,
    val archiveSha256: String,
    val apkEntryName: String,
    val apkSizeBytes: Long,
    val apkSha256: String,
    val packageName: String,
    val apkVersionName: String,
    val apkVersionCode: Long,
    val certificateSha256: String,
    val sources: List<StoredSource>,
    val rollbackId: String?,
    val deviceSetup: DeviceSetupWireDocument? = null,
    val sortOrder: Int = 0,
) {
    companion object {
        fun from(manifest: ArtifactManifest): StoredManifest = StoredManifest(
            schemaVersion = manifest.schemaVersion,
            componentId = manifest.componentId,
            displayName = manifest.displayName,
            description = manifest.description,
            required = manifest.required,
            versionName = manifest.version.name,
            versionCode = manifest.version.code,
            minAndroidSdk = manifest.compatibility.minAndroidSdk,
            maxAndroidSdk = manifest.compatibility.maxAndroidSdk,
            minInstallerVersion = manifest.compatibility.minInstallerVersion,
            maxInstallerVersion = manifest.compatibility.maxInstallerVersion,
            archiveFormat = manifest.archiveFormat,
            archiveFileName = manifest.archiveFileName,
            archiveSizeBytes = manifest.archiveSizeBytes,
            archiveSha256 = manifest.archiveSha256,
            apkEntryName = manifest.apkEntryName,
            apkSizeBytes = manifest.apkSizeBytes,
            apkSha256 = manifest.apkSha256,
            packageName = manifest.packageName,
            apkVersionName = manifest.apkVersion.name,
            apkVersionCode = manifest.apkVersion.code,
            certificateSha256 = manifest.certificateSha256,
            sources = manifest.sources.map { StoredSource(it.kind.wireName, it.url) },
            rollbackId = manifest.rollbackId,
            deviceSetup = manifest.deviceSetup?.let { setup ->
                DeviceSetupWireDocument(
                    profileId = setup.profileId,
                    actionIds = setup.actionIds.toList(),
                    appOps = setup.appOps.map { it.name },
                    runtimePermissions = setup.runtimePermissions.map { it.name },
                    secureSettings = setup.secureSettings.map { it.name },
                    secureComponents = setup.secureComponents.map {
                        SecureComponentWireDocument(it.setting.name, it.targetComponent)
                    },
                    launchComponent = setup.launchComponent,
                    requiredServices = setup.requiredServices.toList(),
                )
            },
            sortOrder = manifest.sortOrder,
        )
    }

    fun toDomainOrNull(): ArtifactManifest? {
        return try {
            val parsedSetup = if (deviceSetup == null) {
                null
            } else {
                deviceSetup.toDomainOrNull() ?: return null
            }
            ArtifactManifest(
            schemaVersion = schemaVersion,
            componentId = componentId,
            displayName = displayName,
            description = description,
            required = required,
            version = ArtifactVersion(versionName, versionCode),
            compatibility = CompatibilityRange(
                minAndroidSdk = minAndroidSdk,
                maxAndroidSdk = maxAndroidSdk,
                minInstallerVersion = minInstallerVersion,
                maxInstallerVersion = maxInstallerVersion,
            ),
            archiveFormat = archiveFormat,
            archiveFileName = archiveFileName,
            archiveSizeBytes = archiveSizeBytes,
            archiveSha256 = archiveSha256,
            apkEntryName = apkEntryName,
            apkSizeBytes = apkSizeBytes,
            apkSha256 = apkSha256,
            packageName = packageName,
            apkVersion = ArtifactVersion(apkVersionName, apkVersionCode),
            certificateSha256 = certificateSha256,
            sources = sources.map { source ->
                ArtifactSource(
                    kind = ArtifactSourceKind.fromWire(source.kind) ?: throw IllegalArgumentException("source_kind"),
                    url = source.url,
                )
            },
            rollbackId = rollbackId,
            deviceSetup = parsedSetup,
                sortOrder = sortOrder,
            )
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
private data class StoredSource(val kind: String, val url: String)

@Serializable
private data class StoredApplication(
    val componentId: String,
    val packageName: String,
    val installed: Boolean,
) {
    companion object {
        fun from(application: ManagedApplicationStatus): StoredApplication = StoredApplication(
            componentId = application.componentId,
            packageName = application.packageName,
            installed = application.installed,
        )
    }

    fun toDomainOrNull(): ManagedApplicationStatus? {
        val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        return if (componentId.matches(Regex("^[a-z][a-z0-9-]{0,63}$")) && packageName.matches(packagePattern)) {
            ManagedApplicationStatus(componentId, packageName, installed)
        } else {
            null
        }
    }
}
