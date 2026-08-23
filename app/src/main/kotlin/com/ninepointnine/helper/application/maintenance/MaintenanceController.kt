package com.ninepointnine.helper.application.maintenance

import com.ninepointnine.helper.application.session.InstallationSessionEventPort
import com.ninepointnine.helper.application.artifact.toComponentDescriptors
import com.ninepointnine.helper.data.catalog.CatalogLoadResult
import com.ninepointnine.helper.data.download.ArtifactCache
import com.ninepointnine.helper.domain.device.DeviceActionConnectionLease
import com.ninepointnine.helper.domain.device.ManagedApplicationProbe
import com.ninepointnine.helper.domain.device.ManagedApplicationsResult
import com.ninepointnine.helper.domain.device.MaintenanceDeviceResult
import com.ninepointnine.helper.domain.device.ManagedComponent
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.domain.session.ManagedApplicationStatus
import com.ninepointnine.helper.domain.session.requiresConnectedDevice
import kotlinx.coroutines.CancellationException

/**
 * Executes the fixed maintenance actions around the same retained device lease.
 * It emits structured results only; the session remains the state owner.
 */
class MaintenanceController(
    private val artifactCache: ArtifactCache,
    private val diagnosticStore: MaintenanceDiagnosticStore,
    private val loadCatalog: (suspend () -> CatalogLoadResult)? = null,
) {
    suspend fun execute(
        actionId: MaintenanceActionId,
        snapshot: InstallationSessionSnapshot,
        connection: com.ninepointnine.helper.domain.device.DeviceConnectionLease?,
        eventPort: InstallationSessionEventPort,
    ) {
        try {
            if (
                actionId.requiresConnectedDevice &&
                (connection == null || (connection as? DeviceActionConnectionLease)?.maintenanceGateway == null)
            ) {
                fail(actionId, "device_action_gateway_unavailable", retryable = true, eventPort)
                return
            }
            when (actionId) {
                MaintenanceActionId.CHECK_UPDATES -> checkUpdates(actionId, snapshot, eventPort)
                MaintenanceActionId.REPAIR_CONFIGURATION -> repairConfiguration(actionId, snapshot, connection, eventPort)
                MaintenanceActionId.MANAGE_APPS -> inspectApplications(actionId, snapshot, connection, eventPort)
                MaintenanceActionId.LAUNCH_LYRICS -> launch(actionId, "lyrics", snapshot, connection, eventPort)
                MaintenanceActionId.LAUNCH_DESKTOP -> launch(actionId, "desktop", snapshot, connection, eventPort)
                MaintenanceActionId.CLEANUP -> {
                    artifactCache.clearAll()
                    complete(actionId, "cache_cleared", eventPort)
                }

                MaintenanceActionId.EXPORT_DIAGNOSTICS -> {
                    if (diagnosticStore.export(snapshot)) {
                        complete(actionId, "diagnostics_ready", eventPort)
                    } else {
                        fail(actionId, "diagnostic_export_failed", retryable = true, eventPort)
                    }
                }

                // These two actions are converted to the existing installation pipeline by the session.
                MaintenanceActionId.REINSTALL,
                MaintenanceActionId.INSTALL_FILE_MANAGER,
                -> fail(actionId, "maintenance_install_transition_invalid", retryable = false, eventPort)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            fail(actionId, "maintenance_action_failed", retryable = true, eventPort)
        }
    }

    private suspend fun checkUpdates(
        actionId: MaintenanceActionId,
        snapshot: InstallationSessionSnapshot,
        eventPort: InstallationSessionEventPort,
    ) {
        val loader = loadCatalog ?: run {
            fail(actionId, "catalog_android_profile_missing", retryable = false, eventPort)
            return
        }
        when (val result = loader()) {
            is CatalogLoadResult.Failure -> fail(actionId, result.reasonCode, result.retryable, eventPort)
            is CatalogLoadResult.Success -> {
                eventPort.emit(
                    InstallationSessionEvent.MaintenanceCatalogRefreshed(
                        catalogVersion = result.catalog.catalogVersion,
                        keyId = result.catalog.keyId,
                        signatureAlgorithm = result.catalog.signatureAlgorithm,
                        manifests = result.catalog.manifests,
                        catalogRevision = result.catalog.catalogRevision,
                        apps = result.catalog.toComponentDescriptors(snapshot.device?.androidSdk),
                        appFailures = result.catalog.appFailures.associate { it.componentId to it.reasonCode },
                    ),
                )
                val currentById = snapshot.artifactManifests.associateBy { it.componentId }
                val nextById = result.catalog.manifests.associateBy { it.componentId }
                val currentComponents = snapshot.components
                    .filter { it.status != com.ninepointnine.helper.domain.session.ComponentStatus.UNLISTED }
                    .associateBy { it.id }
                val nextComponents = result.catalog.toComponentDescriptors(snapshot.device?.androidSdk)
                    .associateBy { it.id }
                val currentDeclaredIds = currentComponents.keys
                val nextDeclaredIds = if (result.catalog.apps.isNotEmpty()) {
                    result.catalog.apps.filter { it.enabled }.map { it.componentId }.toSet()
                } else {
                    nextById.keys
                }
                val catalogChanged = snapshot.catalogVersion != result.catalog.catalogVersion ||
                    snapshot.catalogRevision != result.catalog.catalogRevision ||
                    currentDeclaredIds != nextDeclaredIds
                val descriptorChanged = nextComponents.any { (componentId, next) ->
                    val current = currentComponents[componentId] ?: return@any true
                    current.displayName != next.displayName ||
                        current.description != next.description ||
                        current.required != next.required ||
                        current.versionLabel != next.versionLabel ||
                        current.sizeLabel != next.sizeLabel ||
                        current.compatibilityLabel != next.compatibilityLabel ||
                        current.compatibilityState != next.compatibilityState ||
                        current.errorReason != next.errorReason
                }
                val manifestChanged = currentById.keys != nextById.keys || nextById.any { (componentId, manifest) ->
                    currentById[componentId]?.let { current ->
                        current.version != manifest.version ||
                        current.archiveSizeBytes != manifest.archiveSizeBytes ||
                            !current.archiveSha256.equals(manifest.archiveSha256, ignoreCase = true) ||
                            current.apkSizeBytes != manifest.apkSizeBytes ||
                            !current.apkSha256.equals(manifest.apkSha256, ignoreCase = true) ||
                            !current.certificateSha256.equals(manifest.certificateSha256, ignoreCase = true) ||
                            current.packageName != manifest.packageName
                    } ?: true
                }
                complete(
                    actionId,
                    if (catalogChanged || descriptorChanged || manifestChanged) {
                        "updates_available"
                    } else {
                        "up_to_date"
                    },
                    eventPort,
                )
            }
        }
    }

    private suspend fun repairConfiguration(
        actionId: MaintenanceActionId,
        snapshot: InstallationSessionSnapshot,
        connection: com.ninepointnine.helper.domain.device.DeviceConnectionLease?,
        eventPort: InstallationSessionEventPort,
    ) {
        val selected = snapshot.components
            .filter { it.id == com.ninepointnine.helper.domain.device.AuthorizationPlanFactory.DESKTOP_COMPONENT_ID ||
                it.id in snapshot.selectedOptionalComponentIds }
            .map { it.id }
            .toSet()
        if (selected.isEmpty() || !snapshot.evidence.installed.containsAll(selected)) {
            fail(actionId, "maintenance_installed_evidence_missing", retryable = false, eventPort)
            return
        }
        val manifests = snapshot.artifactManifests.filter { it.componentId in selected }
        if (manifests.map { it.componentId }.toSet() != selected) {
            fail(actionId, "maintenance_manifest_selection_mismatch", retryable = false, eventPort)
            return
        }
        val gateway = maintenanceGateway(connection)
        if (gateway == null) {
            fail(actionId, "device_action_gateway_unavailable", retryable = false, eventPort)
            return
        }
        val declarations = snapshot.evidence.installation.mapNotNull { (componentId, evidence) ->
            evidence.declarations?.let { componentId to it }
        }.toMap()
        when (val result = gateway.repairAuthorization(manifests, declarations)) {
            is MaintenanceDeviceResult.Completed -> complete(actionId, result.resultCode, eventPort)
            is MaintenanceDeviceResult.Failed -> fail(actionId, result.failure.reasonCode, result.failure.retryable, eventPort)
        }
    }

    private suspend fun inspectApplications(
        actionId: MaintenanceActionId,
        snapshot: InstallationSessionSnapshot,
        connection: com.ninepointnine.helper.domain.device.DeviceConnectionLease?,
        eventPort: InstallationSessionEventPort,
    ) {
        val gateway = maintenanceGateway(connection)
        if (gateway == null) {
            fail(actionId, "device_action_gateway_unavailable", retryable = false, eventPort)
            return
        }
        val components = (snapshot.artifactManifests.map {
            ManagedComponent(
                componentId = it.componentId,
                packageName = it.packageName,
                setup = it.deviceSetup,
                order = it.sortOrder,
            )
        } + snapshot.maintenance.availableManifests.map {
            ManagedComponent(
                componentId = it.componentId,
                packageName = it.packageName,
                setup = it.deviceSetup,
                order = it.sortOrder,
            )
        } + snapshot.maintenance.managedApplications
            .filter { application -> snapshot.artifactManifests.none { it.componentId == application.componentId } }
            .filter { application -> snapshot.maintenance.availableManifests.none { it.componentId == application.componentId } }
            .map { application -> ManagedComponent(application.componentId, application.packageName) })
            .distinctBy { it.componentId }
        when (val result = gateway.inspectManagedApplications(components)) {
            is ManagedApplicationsResult.Failed -> fail(actionId, result.failure.reasonCode, result.failure.retryable, eventPort)
            is ManagedApplicationsResult.Completed -> {
                eventPort.emit(
                    InstallationSessionEvent.MaintenanceApplicationsResolved(
                        applications = result.applications.map { it.toSnapshotStatus() },
                    ),
                )
                complete(actionId, "applications_checked", eventPort)
            }
        }
    }

    private suspend fun launch(
        actionId: MaintenanceActionId,
        componentId: String,
        snapshot: InstallationSessionSnapshot,
        connection: com.ninepointnine.helper.domain.device.DeviceConnectionLease?,
        eventPort: InstallationSessionEventPort,
    ) {
        val gateway = maintenanceGateway(connection)
        if (gateway == null) {
            fail(actionId, "device_action_gateway_unavailable", retryable = false, eventPort)
            return
        }
        val manifest = snapshot.artifactManifests.firstOrNull { it.componentId == componentId }
        val component = if (manifest != null) {
            ManagedComponent(
                componentId = manifest.componentId,
                packageName = manifest.packageName,
                setup = manifest.deviceSetup,
                order = manifest.sortOrder,
            )
        } else {
            com.ninepointnine.helper.domain.device.AuthorizationPlanFactory.allManagedComponents()
                .firstOrNull { it.componentId == componentId }
        }
        if (component == null) {
            fail(actionId, "maintenance_component_unavailable", retryable = false, eventPort)
            return
        }
        when (val result = gateway.launchManagedComponent(component)) {
            is MaintenanceDeviceResult.Completed -> complete(actionId, result.resultCode, eventPort)
            is MaintenanceDeviceResult.Failed -> fail(actionId, result.failure.reasonCode, result.failure.retryable, eventPort)
        }
    }

    private fun maintenanceGateway(
        connection: com.ninepointnine.helper.domain.device.DeviceConnectionLease?,
    ) = (connection as? DeviceActionConnectionLease)?.maintenanceGateway

    private fun complete(
        actionId: MaintenanceActionId,
        resultCode: String,
        eventPort: InstallationSessionEventPort,
    ) {
        eventPort.emit(InstallationSessionEvent.MaintenanceActionCompleted(actionId, resultCode))
    }

    private fun fail(
        actionId: MaintenanceActionId,
        reasonCode: String,
        retryable: Boolean,
        eventPort: InstallationSessionEventPort,
    ) {
        eventPort.emit(InstallationSessionEvent.MaintenanceActionFailed(actionId, reasonCode, retryable))
    }

    private fun ManagedApplicationProbe.toSnapshotStatus(): ManagedApplicationStatus = ManagedApplicationStatus(
        componentId = componentId,
        packageName = packageName,
        installed = installed,
    )
}
