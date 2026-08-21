package com.tcrrry.helper.application.maintenance

import com.tcrrry.helper.application.session.InstallationSessionEventPort
import com.tcrrry.helper.data.catalog.CatalogLoadResult
import com.tcrrry.helper.data.download.ArtifactCache
import com.tcrrry.helper.domain.device.DeviceActionConnectionLease
import com.tcrrry.helper.domain.device.ManagedApplicationProbe
import com.tcrrry.helper.domain.device.ManagedApplicationsResult
import com.tcrrry.helper.domain.device.MaintenanceDeviceResult
import com.tcrrry.helper.domain.session.InstallationSessionEvent
import com.tcrrry.helper.domain.session.InstallationSessionSnapshot
import com.tcrrry.helper.domain.session.MaintenanceActionId
import com.tcrrry.helper.domain.session.ManagedApplicationStatus
import com.tcrrry.helper.domain.session.requiresConnectedDevice
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
        connection: com.tcrrry.helper.domain.device.DeviceConnectionLease?,
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
                MaintenanceActionId.MANAGE_APPS -> inspectApplications(actionId, connection, eventPort)
                MaintenanceActionId.LAUNCH_LYRICS -> launch(actionId, "lyrics", connection, eventPort)
                MaintenanceActionId.LAUNCH_DESKTOP -> launch(actionId, "desktop", connection, eventPort)
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
                    ),
                )
                val currentById = snapshot.artifactManifests.associateBy { it.componentId }
                val nextById = result.catalog.manifests.associateBy { it.componentId }
                val changed = currentById.keys != nextById.keys || nextById.any { (componentId, manifest) ->
                    currentById[componentId]?.let { current ->
                        current.version != manifest.version ||
                            !current.archiveSha256.equals(manifest.archiveSha256, ignoreCase = true) ||
                            !current.apkSha256.equals(manifest.apkSha256, ignoreCase = true) ||
                            !current.certificateSha256.equals(manifest.certificateSha256, ignoreCase = true) ||
                            current.packageName != manifest.packageName
                    } ?: true
                }
                complete(actionId, if (changed) "updates_available" else "up_to_date", eventPort)
            }
        }
    }

    private suspend fun repairConfiguration(
        actionId: MaintenanceActionId,
        snapshot: InstallationSessionSnapshot,
        connection: com.tcrrry.helper.domain.device.DeviceConnectionLease?,
        eventPort: InstallationSessionEventPort,
    ) {
        val selected = snapshot.components
            .filter { it.required || it.id in snapshot.selectedOptionalComponentIds }
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
        when (val result = gateway.repairAuthorization(manifests)) {
            is MaintenanceDeviceResult.Completed -> complete(actionId, result.resultCode, eventPort)
            is MaintenanceDeviceResult.Failed -> fail(actionId, result.failure.reasonCode, result.failure.retryable, eventPort)
        }
    }

    private suspend fun inspectApplications(
        actionId: MaintenanceActionId,
        connection: com.tcrrry.helper.domain.device.DeviceConnectionLease?,
        eventPort: InstallationSessionEventPort,
    ) {
        val gateway = maintenanceGateway(connection)
        if (gateway == null) {
            fail(actionId, "device_action_gateway_unavailable", retryable = false, eventPort)
            return
        }
        when (val result = gateway.inspectManagedApplications()) {
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
        connection: com.tcrrry.helper.domain.device.DeviceConnectionLease?,
        eventPort: InstallationSessionEventPort,
    ) {
        val gateway = maintenanceGateway(connection)
        if (gateway == null) {
            fail(actionId, "device_action_gateway_unavailable", retryable = false, eventPort)
            return
        }
        when (val result = gateway.launchManagedComponent(componentId)) {
            is MaintenanceDeviceResult.Completed -> complete(actionId, result.resultCode, eventPort)
            is MaintenanceDeviceResult.Failed -> fail(actionId, result.failure.reasonCode, result.failure.retryable, eventPort)
        }
    }

    private fun maintenanceGateway(
        connection: com.tcrrry.helper.domain.device.DeviceConnectionLease?,
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
