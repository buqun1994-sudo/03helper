package com.ninepointnine.helper.application.maintenance

import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactManifestValidator
import com.ninepointnine.helper.domain.artifact.ArtifactSource
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.CompatibilityRange
import com.ninepointnine.helper.domain.artifact.ManifestValidation
import com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy
import com.ninepointnine.helper.domain.artifact.InstallerSelfIdentity
import com.ninepointnine.helper.domain.artifact.SourcePlan
import com.ninepointnine.helper.domain.artifact.toComponentDescriptor
import com.ninepointnine.helper.domain.device.AuthorizationPlanFactory
import com.ninepointnine.helper.domain.device.DeviceCapability
import com.ninepointnine.helper.domain.session.ComponentDescriptor
import com.ninepointnine.helper.domain.session.ComponentCompatibility
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.session.DeviceSummary
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.domain.session.InstallationSessionState
import com.ninepointnine.helper.domain.session.ArtifactCatalogStage
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.domain.session.MaintenanceActionRecord
import com.ninepointnine.helper.domain.session.MaintenanceActionStatus
import com.ninepointnine.helper.domain.session.MaintenanceInventoryState
import com.ninepointnine.helper.domain.session.MaintenanceSnapshot
import com.ninepointnine.helper.domain.session.ManagedApplicationStatus
import com.ninepointnine.helper.domain.session.ComponentStatus
import com.ninepointnine.helper.data.catalog.DeviceSetupWireDocument
import com.ninepointnine.helper.data.catalog.InstallerAppIconDocument
import com.ninepointnine.helper.data.catalog.SecureComponentWireDocument
import com.ninepointnine.helper.domain.session.SessionEvidence
import com.ninepointnine.helper.domain.session.toDurableMaintenanceBaseline
import com.ninepointnine.helper.domain.session.withVerifiedInstallations
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
        val baseline = MaintenanceBaselineProjector.project(snapshot) ?: return false
        return try {
            val stored = StoredMaintenanceState.from(baseline)
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

/**
 * Pure projection from the live session to the only state allowed on disk.
 * The result is idempotent and intentionally cannot resume an installation.
 */
internal object MaintenanceBaselineProjector {
    fun project(snapshot: InstallationSessionSnapshot): InstallationSessionSnapshot? {
        val device = snapshot.device ?: return null
        val durableState = snapshot.state in setOf(
            InstallationSessionState.MAINTENANCE,
            InstallationSessionState.SUCCEEDED,
            InstallationSessionState.COMPLETED_WITH_ERRORS,
        )
        val hasDurableInventory = snapshot.maintenance.installedManifests.isNotEmpty() ||
            snapshot.maintenance.managedApplicationsState == MaintenanceInventoryState.READY
        if (!durableState && !hasDurableInventory) return null

        val declaredSource = snapshot.maintenance.availableComponents.ifEmpty { snapshot.components }
        val declaredById = linkedMapOf<String, ComponentDescriptor>()
        declaredSource.forEach { component -> declaredById[component.id] = component.toBaselineComponent() }
        snapshot.components.forEach { component ->
            declaredById.putIfAbsent(component.id, component.toBaselineComponent())
        }
        snapshot.maintenance.installedManifests.forEach { manifest ->
            if (manifest.componentId !in declaredById) {
                declaredById[manifest.componentId] = manifest
                    .toComponentDescriptor(device.androidSdk)
                    .copy(status = ComponentStatus.UNLISTED, errorReason = "unlisted")
            }
        }
        snapshot.maintenance.managedApplications
            .filter { it.installed }
            .forEach { application ->
                if (application.componentId !in declaredById) {
                    declaredById[application.componentId] = ComponentDescriptor(
                        id = application.componentId,
                        displayName = application.componentId,
                        required = false,
                        versionLabel = application.versionLabel,
                        status = ComponentStatus.UNLISTED,
                        errorReason = "unlisted",
                    )
                }
            }
        val components = declaredById.values.toList()
        val availableComponents = declaredSource
            .map { component -> component.toBaselineComponent() }
            .filterNot { InstallerSelfIdentity.isSelfComponentId(it.id) || it.status == ComponentStatus.UNLISTED }
            .distinctBy { it.id }
        val mayMigrateLegacyBatch = snapshot.state != InstallationSessionState.MAINTENANCE ||
            snapshot.maintenance.managedApplicationsState != MaintenanceInventoryState.READY
        val verifiedCurrentBatch = if (mayMigrateLegacyBatch) {
            snapshot.artifactManifests.filter { manifest ->
                manifest.componentId in snapshot.evidence.installed
            }
        } else {
            emptyList()
        }
        val maintenance = snapshot.maintenance
            .copy(availableComponents = availableComponents)
            .withVerifiedInstallations(verifiedCurrentBatch)
            .toDurableMaintenanceBaseline()
        val installedIds = buildSet {
            addAll(snapshot.evidence.installed)
            addAll(maintenance.installedManifests.map { it.componentId })
        } intersect components.mapTo(mutableSetOf()) { it.id }
        val configuredIds = snapshot.evidence.configured intersect installedIds
        val availableIds = snapshot.evidence.available intersect configuredIds
        val controlPlaneReady = snapshot.catalogRevision > 0L &&
            !snapshot.catalogVersion.isNullOrBlank() &&
            !snapshot.catalogKeyId.isNullOrBlank() &&
            !snapshot.catalogSignatureAlgorithm.isNullOrBlank()

        return InstallationSessionSnapshot(
            state = InstallationSessionState.MAINTENANCE,
            device = device.copy(connectionStatus = DeviceConnectionStatus.DISCONNECTED),
            components = components,
            artifactCatalogStage = if (controlPlaneReady) {
                ArtifactCatalogStage.CONTROL_PLANE_READY
            } else {
                ArtifactCatalogStage.NOT_LOADED
            },
            catalogVersion = snapshot.catalogVersion,
            catalogRevision = snapshot.catalogRevision,
            catalogKeyId = snapshot.catalogKeyId,
            catalogSignatureAlgorithm = snapshot.catalogSignatureAlgorithm,
            evidence = SessionEvidence(
                installed = installedIds,
                configured = configuredIds,
                available = availableIds,
            ),
            maintenance = maintenance,
        )
    }

    private fun ComponentDescriptor.toBaselineComponent(): ComponentDescriptor = copy(
        status = when (status) {
            ComponentStatus.UNLISTED,
            ComponentStatus.CLIENT_CAPABILITY_INSUFFICIENT,
            -> status

            else -> ComponentStatus.AVAILABLE
        },
        errorReason = errorReason.takeIf { status == ComponentStatus.UNLISTED },
    )
}

@Serializable
private data class StoredMaintenanceState(
    val schemaVersion: Int,
    val device: StoredDevice,
    val components: List<StoredComponent>,
    val selectedOptionalComponentIds: Set<String>,
    val artifactManifests: List<StoredManifest>,
    val installedManifests: List<StoredManifest> = emptyList(),
    val availableManifests: List<StoredManifest>,
    val catalogVersion: String?,
    val catalogRevision: Long = 0L,
    val catalogKeyId: String?,
    val catalogSignatureAlgorithm: String?,
    val availableCatalogVersion: String?,
    val availableCatalogRevision: Long = 0L,
    val availableCatalogKeyId: String?,
    val availableCatalogSignatureAlgorithm: String?,
    val catalogControlPlaneOnly: Boolean = false,
    val installed: Set<String>,
    val configured: Set<String>,
    val available: Set<String>,
    val managedApplications: List<StoredApplication>,
    val lastAction: StoredMaintenanceAction? = null,
    /** Domain-owned secondary route; absent in older schema-1 records means home. */
    val routeAction: String? = null,
    /** Added with defaults so schema-1 records written by older builds remain readable. */
    val managedApplicationsState: String = MaintenanceInventoryState.NOT_STARTED.name,
    val managedApplicationsFailureReason: String? = null,
    val managedApplicationsFailureRetryable: Boolean = false,
    val availableComponents: List<StoredComponent> = emptyList(),
    val artifactCatalogStage: String = ArtifactCatalogStage.NOT_LOADED.name,
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
            components = (snapshot.components + snapshot.maintenance.availableComponents)
                .distinctBy { it.id }
                .map(StoredComponent::from),
            selectedOptionalComponentIds = snapshot.selectedOptionalComponentIds,
            artifactManifests = emptyList(),
            installedManifests = snapshot.maintenance.installedManifests.map(StoredManifest::from),
            availableManifests = snapshot.maintenance.availableManifests.map(StoredManifest::from),
            catalogVersion = snapshot.catalogVersion,
            catalogRevision = snapshot.catalogRevision,
            catalogKeyId = snapshot.catalogKeyId,
            catalogSignatureAlgorithm = snapshot.catalogSignatureAlgorithm,
            availableCatalogVersion = snapshot.maintenance.availableCatalogVersion,
            availableCatalogRevision = snapshot.maintenance.availableCatalogRevision,
            availableCatalogKeyId = snapshot.maintenance.availableCatalogKeyId,
            availableCatalogSignatureAlgorithm = snapshot.maintenance.availableCatalogSignatureAlgorithm,
            catalogControlPlaneOnly = snapshot.maintenance.catalogControlPlaneOnly,
            installed = snapshot.evidence.installed,
            configured = snapshot.evidence.configured,
            available = snapshot.evidence.available,
            managedApplications = snapshot.maintenance.managedApplications.map(StoredApplication::from),
            lastAction = snapshot.maintenance.lastAction?.let(StoredMaintenanceAction::from),
            // Secondary-page route data is intentionally process-local. The
            // selection, update rows and detail payloads are not persisted as
            // one atomic wire model, so restoring a route would reopen an
            // incomplete page after process death. Cold start always returns
            // to the maintenance home and keeps the durable session data.
            routeAction = null,
            managedApplicationsState = snapshot.maintenance.managedApplicationsState.name,
            managedApplicationsFailureReason = snapshot.maintenance.managedApplicationsFailureReason,
            managedApplicationsFailureRetryable = snapshot.maintenance.managedApplicationsFailureRetryable,
            availableComponents = (snapshot.maintenance.availableComponents
                .ifEmpty {
                    snapshot.components.filter {
                        !InstallerSelfIdentity.isSelfComponentId(it.id) && it.status != ComponentStatus.UNLISTED
                    }
                })
                .map(StoredComponent::from),
            artifactCatalogStage = snapshot.artifactCatalogStage.name,
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
        val parsedAvailableComponents = availableComponents.mapNotNull { it.toDomainOrNull() }
        if (parsedAvailableComponents.size != availableComponents.size) return null
        val manifests = artifactManifests.mapNotNull { it.toDomainOrNull() }
        if (manifests.size != artifactManifests.size) return null
        if (manifests.isNotEmpty() && !validateBatchManifests(manifests, sourcePolicy)) {
            return null
        }
        val parsedAvailableManifests = availableManifests.mapNotNull { it.toDomainOrNull() }
        if (parsedAvailableManifests.size != availableManifests.size) return null
        if (parsedAvailableManifests.isNotEmpty() && !validateManifests(parsedAvailableManifests, sourcePolicy)) {
            return null
        }
        val parsedInstalledManifests = installedManifests.mapNotNull { it.toDomainOrNull() }
        if (parsedInstalledManifests.size != installedManifests.size) return null
        if (parsedInstalledManifests.isNotEmpty() && !validateManifestEntries(parsedInstalledManifests, sourcePolicy)) {
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
            parsedAvailableComponents.map { it.id }.toSet().size != parsedAvailableComponents.size ||
            parsedAvailableComponents.any { it.id !in componentIds }
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
                !parsedAvailableManifests.map { it.componentId }.toSet().all {
                    it in componentIds || InstallerSelfIdentity.isSelfComponentId(it)
                })
        ) {
            return null
        }
        if (
            parsedInstalledManifests.isNotEmpty() &&
            (parsedInstalledManifests.any { it.componentId.isBlank() } ||
                !parsedInstalledManifests.map { it.componentId }.toSet().all {
                    it in componentIds || InstallerSelfIdentity.isSelfComponentId(it)
                })
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
        val parsedInventoryState = try {
            MaintenanceInventoryState.valueOf(managedApplicationsState)
        } catch (_: Exception) {
            return null
        }
        val parsedLastAction = lastAction?.toDomainOrNull() ?: if (lastAction == null) null else return null
        routeAction?.let {
            if (runCatching { MaintenanceActionId.valueOf(it) }.getOrNull() == null) {
                return null
            }
        }
        val effectiveInventoryState = if (
            parsedInventoryState == MaintenanceInventoryState.NOT_STARTED &&
            parsedLastAction?.actionId in INVENTORY_ACTIONS &&
            parsedLastAction?.status == MaintenanceActionStatus.SUCCEEDED
        ) {
            // Older schema-1 records had no explicit inventory state. A
            // successful inventory action, including an empty result, is a
            // durable READY proof rather than an implicit loading state.
            MaintenanceInventoryState.READY
        } else {
            parsedInventoryState
        }
        val parsedArtifactCatalogStage = try {
            ArtifactCatalogStage.valueOf(artifactCatalogStage)
        } catch (_: Exception) {
            return null
        }
        val effectiveArtifactCatalogStage = if (
            parsedArtifactCatalogStage == ArtifactCatalogStage.CONTROL_PLANE_READY ||
            catalogControlPlaneOnly ||
            catalogRevision > 0L
        ) {
            ArtifactCatalogStage.CONTROL_PLANE_READY
        } else {
            ArtifactCatalogStage.NOT_LOADED
        }
        val effectiveInstalledManifests = parsedInstalledManifests.ifEmpty {
            // Schema-1 records may predate installedManifests. Migrate only
            // identities backed by explicit installation evidence or by an
            // exact package/version inventory match. A visible inventory row
            // alone must never acquire missing-only reuse privileges.
            val manifestById = manifests.associateBy { it.componentId }
            val exactInventoryIds = applications.asSequence()
                .filter { it.installed }
                .mapNotNull { application ->
                    val manifest = manifestById[application.componentId] ?: return@mapNotNull null
                    application.componentId.takeIf {
                        application.packageName == manifest.packageName &&
                            application.versionCode == manifest.apkVersion.code
                    }
                }
                .toSet()
            val trustedLegacyIds = installed + exactInventoryIds
            manifests.filter { it.componentId in trustedLegacyIds }
        }
        if (manifests.isNotEmpty() && !validateBatchAuthorization(manifests)) {
            return null
        }
        if (parsedAvailableManifests.isNotEmpty() &&
            AuthorizationPlanFactory.createForManifests(parsedAvailableManifests) !is
                com.ninepointnine.helper.domain.device.AuthorizationPlanBuildResult.Ready
        ) {
            return null
        }
        val effectiveApplications = com.ninepointnine.helper.domain.session.mergeVerifiedManagedApplications(
            applications,
            effectiveInstalledManifests,
        )
        val effectiveInstalledIds = installed + effectiveInstalledManifests.map { it.componentId }
        val unlistedComponents = effectiveApplications
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
            selectedOptionalComponentIds = emptySet(),
            artifactManifests = emptyList(),
            artifactCatalogStage = effectiveArtifactCatalogStage,
            catalogVersion = catalogVersion,
            catalogRevision = catalogRevision,
            catalogKeyId = catalogKeyId,
            catalogSignatureAlgorithm = catalogSignatureAlgorithm,
            evidence = SessionEvidence(
                installed = effectiveInstalledIds,
                configured = configured intersect effectiveInstalledIds,
                available = available intersect configured intersect effectiveInstalledIds,
            ),
            maintenance = MaintenanceSnapshot(
                // Route fields from older records remain strictly parsed above
                // for schema validation, but are never restored without the
                // matching page wire model. The durable entry point is home.
                routeAction = null,
                lastAction = null,
                managedApplicationsState = effectiveInventoryState,
                managedApplicationsFailureReason = null,
                managedApplicationsFailureRetryable = false,
                managedApplications = effectiveApplications,
                installedManifests = effectiveInstalledManifests,
                availableComponents = parsedAvailableComponents.ifEmpty {
                    parsedComponents.filter {
                        !InstallerSelfIdentity.isSelfComponentId(it.id) && it.status != ComponentStatus.UNLISTED
                    }
                },
                availableManifests = parsedAvailableManifests,
                availableCatalogVersion = availableCatalogVersion,
                availableCatalogRevision = availableCatalogRevision,
                availableCatalogKeyId = availableCatalogKeyId,
                availableCatalogSignatureAlgorithm = availableCatalogSignatureAlgorithm,
                catalogControlPlaneOnly = catalogControlPlaneOnly,
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

    private fun validateManifestEntries(
        manifests: List<ArtifactManifest>,
        sourcePolicy: ReleaseSourcePolicy,
    ): Boolean = manifests.all { manifest ->
        ArtifactManifestValidator.validate(manifest) is ManifestValidation.Valid &&
            sourcePolicy.plan(manifest) is SourcePlan.Accepted
    }

    /** A resumable batch may contain only the newly selected components. */
    private fun validateBatchManifests(
        manifests: List<ArtifactManifest>,
        sourcePolicy: ReleaseSourcePolicy,
    ): Boolean = validateManifestEntries(manifests, sourcePolicy)

    /** Validate each typed component without imposing the full-catalog desktop invariant. */
    private fun validateBatchAuthorization(manifests: List<ArtifactManifest>): Boolean = manifests.all { manifest ->
        AuthorizationPlanFactory.validateComponent(
            com.ninepointnine.helper.domain.device.ManagedComponent(
                componentId = manifest.componentId,
                packageName = manifest.packageName,
                setup = manifest.deviceSetup,
                order = manifest.sortOrder,
            ),
        )
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

private val INVENTORY_ACTIONS = setOf(
    MaintenanceActionId.MANAGE_APPS,
    MaintenanceActionId.REPAIR_CONFIGURATION,
    MaintenanceActionId.INSTALL_APPLICATIONS,
    MaintenanceActionId.INSTALL_FILE_MANAGER,
)

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
            val parsedAction = if (actionId == "INSTALL_FILE_MANAGER") {
                MaintenanceActionId.INSTALL_APPLICATIONS
            } else {
                MaintenanceActionId.valueOf(actionId)
            }
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
    /** Signed preview metadata retained so a cold maintenance page is stable. */
    val icon: InstallerAppIconDocument? = null,
    val compatibilityState: String = ComponentCompatibility.SUPPORTED.name,
    val iconKey: String = "",
) {
    companion object {
        fun from(component: ComponentDescriptor): StoredComponent = StoredComponent(
            id = component.id,
            displayName = component.displayName,
            required = component.required,
            versionLabel = component.versionLabel,
            sizeLabel = component.sizeLabel,
            compatibilityLabel = component.compatibilityLabel,
            description = component.description,
            status = component.status.name,
            errorReason = component.errorReason,
            icon = component.iconAsset?.let { asset ->
                InstallerAppIconDocument(
                    assetId = asset.assetId,
                    assetVersion = asset.assetVersion,
                    url = asset.url,
                    mimeType = asset.mimeType,
                    width = asset.width,
                    height = asset.height,
                    sizeBytes = asset.sizeBytes,
                    sha256 = asset.sha256,
                )
            },
            compatibilityState = component.compatibilityState.name,
            iconKey = component.iconKey,
        )
    }

    fun toDomainOrNull(): ComponentDescriptor? = runCatching {
        ComponentDescriptor(
            id = id,
            displayName = displayName,
            required = required,
            versionLabel = versionLabel,
            sizeLabel = sizeLabel,
            compatibilityLabel = compatibilityLabel,
            compatibilityState = ComponentCompatibility.valueOf(compatibilityState),
            iconKey = iconKey.ifBlank { id },
            iconAsset = icon?.toDomain(),
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
    val versionLabel: String? = null,
    val versionCode: Long? = null,
    val fileSizeBytes: Long? = null,
    val installTimeEpochMillis: Long? = null,
    val updateTimeEpochMillis: Long? = null,
    val filePath: String? = null,
    val uid: Int? = null,
) {
        companion object {
        fun from(application: ManagedApplicationStatus): StoredApplication = StoredApplication(
            componentId = application.componentId,
            packageName = application.packageName,
            installed = application.installed,
            versionLabel = application.versionLabel,
            versionCode = application.versionCode,
            fileSizeBytes = application.fileSizeBytes,
            installTimeEpochMillis = application.installTimeEpochMillis,
            updateTimeEpochMillis = application.updateTimeEpochMillis,
            filePath = application.filePath,
            uid = application.uid,
        )
    }

    fun toDomainOrNull(): ManagedApplicationStatus? {
        val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        return if (componentId.matches(Regex("^[a-z][a-z0-9-]{0,63}$")) && packageName.matches(packagePattern)) {
            ManagedApplicationStatus(
                componentId = componentId,
                packageName = packageName,
                installed = installed,
                versionLabel = versionLabel,
                versionCode = versionCode,
                fileSizeBytes = fileSizeBytes,
                installTimeEpochMillis = installTimeEpochMillis,
                updateTimeEpochMillis = updateTimeEpochMillis,
                filePath = filePath,
                uid = uid,
            )
        } else {
            null
        }
    }
}
