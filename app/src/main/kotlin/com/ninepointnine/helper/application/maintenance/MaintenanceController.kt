package com.ninepointnine.helper.application.maintenance

import com.ninepointnine.helper.application.session.InstallationSessionEventPort
import com.ninepointnine.helper.application.artifact.toComponentDescriptors
import com.ninepointnine.helper.data.catalog.CatalogLoadResult
import com.ninepointnine.helper.data.catalog.DistributionConfigLoadResult
import com.ninepointnine.helper.data.catalog.InstallerDistributionConfig
import com.ninepointnine.helper.data.catalog.TrustedArtifactCatalog
import com.ninepointnine.helper.data.download.ArtifactCache
import com.ninepointnine.helper.domain.device.DeviceActionConnectionLease
import com.ninepointnine.helper.domain.device.ManagedApplicationProbe
import com.ninepointnine.helper.domain.device.ManagedApplicationsResult
import com.ninepointnine.helper.domain.device.InstalledApplicationIconResult
import com.ninepointnine.helper.domain.device.ManagedApplicationAuthorizationStatus
import com.ninepointnine.helper.domain.device.ApplicationAuthorizationResult
import com.ninepointnine.helper.domain.device.ManagedApplicationDetailsProbeResult
import com.ninepointnine.helper.domain.device.MaintenanceAuthorizationResult
import com.ninepointnine.helper.domain.device.MaintenanceAuthorizationState
import com.ninepointnine.helper.domain.device.MaintenanceDeviceResult
import com.ninepointnine.helper.domain.device.ManagedComponent
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.InstallerSelfIdentity
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.domain.session.ArtifactCatalogStage
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.domain.session.MaintenanceApplicationActionId
import com.ninepointnine.helper.domain.session.ManagedApplicationStatus
import com.ninepointnine.helper.domain.session.requiresConnectedDevice
import com.ninepointnine.helper.domain.session.MaintenanceUpdateState
import com.ninepointnine.helper.domain.session.MaintenanceUpdateStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield

/**
 * Executes the fixed maintenance actions around the same retained device lease.
 * It emits structured results only; the session remains the state owner.
 */
class MaintenanceController(
    private val artifactCache: ArtifactCache,
    private val diagnosticStore: MaintenanceDiagnosticStore,
    private val loadDistributionConfig: (suspend () -> DistributionConfigLoadResult)? = null,
    private val selfVersion: ArtifactVersion = ArtifactVersion("0.1.0", 1L),
    /** Lightweight signed config + folder listing used by the maintenance install page. */
    private val loadDistributionSelection: (suspend () -> CatalogLoadResult)? = null,
) {
    /** Queries exactly the packages in the current signed installation catalog. */
    suspend fun inspectInitialApplications(
        snapshot: InstallationSessionSnapshot,
        connection: com.ninepointnine.helper.domain.device.DeviceConnectionLease?,
        eventPort: InstallationSessionEventPort,
    ) {
        val gateway = maintenanceGateway(connection)
        if (gateway == null) {
            eventPort.emit(
                InstallationSessionEvent.InitialInstalledApplicationsFailed(
                    reasonCode = "initial_inventory_connection_unavailable",
                    retryable = true,
                ),
            )
            return
        }
        val components = snapshot.components
            .filterNot { InstallerSelfIdentity.isSelfComponentId(it.id) }
            .mapIndexed { index, descriptor ->
                ManagedComponent(descriptor.id, descriptor.packageName, order = index)
            }
        when (val result = gateway.inspectInstalledApplicationInventory(components)) {
            is ManagedApplicationsResult.Failed -> eventPort.emit(
                InstallationSessionEvent.InitialInstalledApplicationsFailed(
                    reasonCode = result.failure.reasonCode,
                    retryable = result.failure.retryable,
                ),
            )

            is ManagedApplicationsResult.Completed -> eventPort.emit(
                InstallationSessionEvent.InitialInstalledApplicationsResolved(
                    applications = result.applications
                        .filter { it.installed }
                        .map { it.toSnapshotStatus() },
                ),
            )
        }
    }

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
                MaintenanceActionId.CHECK_UPDATES -> checkUpdates(actionId, snapshot, connection, eventPort)
                MaintenanceActionId.REPAIR_CONFIGURATION -> repairConfiguration(actionId, snapshot, connection, eventPort)
                MaintenanceActionId.MANAGE_APPS -> inspectApplications(actionId, snapshot, connection, eventPort)
                MaintenanceActionId.INSTALL_APPLICATIONS,
                MaintenanceActionId.INSTALL_FILE_MANAGER,
                ->
                    prepareMaintenanceApplications(actionId, snapshot, connection, eventPort)
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
                -> fail(actionId, "maintenance_install_transition_invalid", retryable = false, eventPort)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            fail(actionId, "maintenance_action_failed", retryable = true, eventPort)
        }
    }

    suspend fun executeApplicationAction(
        packageName: String,
        actionId: MaintenanceApplicationActionId,
        snapshot: InstallationSessionSnapshot,
        connection: com.ninepointnine.helper.domain.device.DeviceConnectionLease?,
        eventPort: InstallationSessionEventPort,
    ) {
        val inventoryApplication = snapshot.maintenance.managedApplications.singleOrNull {
            it.packageName == packageName && it.installed
        }
        if (inventoryApplication == null) {
            eventPort.emit(
                InstallationSessionEvent.MaintenanceApplicationActionFailed(
                    packageName = packageName,
                    actionId = actionId,
                    reasonCode = "maintenance_component_unavailable",
                    retryable = false,
                ),
            )
            return
        }
        val gateway = maintenanceGateway(connection)
        if (gateway == null) {
            eventPort.emit(
                InstallationSessionEvent.MaintenanceApplicationActionFailed(
                    packageName = packageName,
                    actionId = actionId,
                    reasonCode = "device_action_gateway_unavailable",
                    retryable = true,
                ),
            )
            return
        }
        val knownComponent = inventoryApplication?.let { live ->
            managedComponents(snapshot).singleOrNull { it.packageName == live.packageName }
        }
        val component = inventoryApplication.let { live ->
                ManagedComponent(
                    componentId = live.componentId,
                    packageName = live.packageName,
                    setup = knownComponent?.setup,
                    order = knownComponent?.order ?: Int.MAX_VALUE,
                    launchComponent = live.launchComponent ?: knownComponent?.launchComponent,
                )
            }
        try {
            if (actionId == MaintenanceApplicationActionId.INSPECT_AUTHORIZATION ||
                actionId == MaintenanceApplicationActionId.AUTHORIZE
            ) {
                val authorization = if (actionId == MaintenanceApplicationActionId.AUTHORIZE) {
                    gateway.authorizeApplication(component) { progress ->
                        eventPort.emit(
                            InstallationSessionEvent.MaintenanceApplicationAuthorizationProgress(progress),
                        )
                    }
                } else {
                    gateway.inspectApplicationAuthorization(component)
                }
                when (val result = authorization) {
                    is ApplicationAuthorizationResult.Failed -> eventPort.emit(
                        InstallationSessionEvent.MaintenanceApplicationActionFailed(
                            packageName = packageName,
                            actionId = actionId,
                            reasonCode = result.failure.reasonCode,
                            retryable = result.failure.retryable,
                        ),
                    )
                    is ApplicationAuthorizationResult.Completed -> eventPort.emit(
                        InstallationSessionEvent.MaintenanceApplicationAuthorizationResolved(actionId, result.value),
                    )
                }
                return
            }
            if (actionId == MaintenanceApplicationActionId.DETAILS) {
                when (val result = gateway.inspectManagedApplicationDetails(component)) {
                is ManagedApplicationDetailsProbeResult.Failed -> eventPort.emit(
                    InstallationSessionEvent.MaintenanceApplicationActionFailed(
                        packageName = packageName,
                        actionId = actionId,
                        reasonCode = result.failure.reasonCode,
                        retryable = result.failure.retryable,
                    ),
                )

                is ManagedApplicationDetailsProbeResult.Completed -> {
                    val details = result.details
                    eventPort.emit(
                        InstallationSessionEvent.MaintenanceApplicationDetailsResolved(
                            com.ninepointnine.helper.domain.session.ManagedApplicationDetails(
                                componentId = details.componentId,
                                displayName = inventoryApplication?.displayName
                                    ?: details.displayName
                                    ?: snapshot.components.firstOrNull { it.id == component.componentId }?.displayName
                                    ?: details.packageName,
                                packageName = details.packageName,
                                versionLabel = details.versionLabel,
                                versionCode = details.versionCode,
                                fileSizeBytes = details.fileSizeBytes,
                                installTimeEpochMillis = details.installTimeEpochMillis,
                                updateTimeEpochMillis = details.updateTimeEpochMillis,
                                filePath = details.filePath,
                                uid = details.uid,
                            ),
                        ),
                    )
                }
                }
                return
            }
            when (val result = gateway.performApplicationAction(component, actionId)) {
                is MaintenanceDeviceResult.Completed -> {
                    // The device action result is authoritative for the
                    // destructive operation. Inventory refresh is a separate,
                    // best-effort read and can never rewrite success as failure.
                    var refreshedApplications: List<ManagedApplicationStatus>? = null
                    var refreshFailureReason: String? = null
                    var refreshFailureRetryable = true
                    if (actionId == MaintenanceApplicationActionId.UNINSTALL) {
                        try {
                            when (val refreshed = gateway.inspectAllInstalledApplications()) {
                                is ManagedApplicationsResult.Completed -> {
                                    refreshedApplications = refreshed.applications
                                        .filter { it.installed }
                                        .map { it.toSnapshotStatus() }
                                }

                                is ManagedApplicationsResult.Failed -> {
                                    refreshFailureReason = refreshed.failure.reasonCode
                                    refreshFailureRetryable = refreshed.failure.retryable
                                }
                            }
                        } catch (_: Exception) {
                            refreshFailureReason = "maintenance_inventory_refresh_failed"
                        }
                    }
                    eventPort.emit(
                        InstallationSessionEvent.MaintenanceApplicationActionCompleted(
                            packageName = packageName,
                            actionId = actionId,
                            resultCode = result.resultCode,
                            refreshedApplications = refreshedApplications,
                            inventoryRefreshFailureReason = refreshFailureReason,
                            inventoryRefreshRetryable = refreshFailureRetryable,
                        ),
                    )
                }

                is MaintenanceDeviceResult.Failed -> eventPort.emit(
                    InstallationSessionEvent.MaintenanceApplicationActionFailed(
                        packageName = packageName,
                        actionId = actionId,
                        reasonCode = result.failure.reasonCode,
                        retryable = result.failure.retryable,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            eventPort.emit(
                InstallationSessionEvent.MaintenanceApplicationActionFailed(
                    packageName = packageName,
                    actionId = actionId,
                    reasonCode = "maintenance_application_action_failed",
                    retryable = true,
                ),
            )
        }
    }

    /**
     * Fills icons after the metadata inventory has already completed. The
     * runtime owns this task separately so leaving the page or starting a
     * foreground application action can cancel the remaining low-priority
     * reads without invalidating the verified application list.
     */
    suspend fun hydrateApplicationIcons(
        packageNames: List<String>,
        connection: com.ninepointnine.helper.domain.device.DeviceConnectionLease?,
        eventPort: InstallationSessionEventPort,
    ) {
        val gateway = maintenanceGateway(connection) ?: return
        packageNames.distinct().forEach { packageName ->
            // Keep a cancellation point between single-package bridge reads so
            // a foreground operation never waits behind the remaining queue.
            yield()
            when (val icon = gateway.inspectInstalledApplicationIcon(packageName)) {
                is InstalledApplicationIconResult.Completed -> eventPort.emit(
                    InstallationSessionEvent.MaintenanceApplicationIconResolved(
                        packageName = icon.packageName,
                        iconBase64 = icon.iconBase64,
                    ),
                )
                is InstalledApplicationIconResult.Failed -> Unit
            }
        }
    }

    private suspend fun checkUpdates(
        actionId: MaintenanceActionId,
        snapshot: InstallationSessionSnapshot,
        connection: com.ninepointnine.helper.domain.device.DeviceConnectionLease?,
        eventPort: InstallationSessionEventPort,
    ) {
        val configLoader = loadDistributionConfig ?: run {
            fail(actionId, "catalog_android_profile_missing", retryable = false, eventPort)
            return
        }
        val result = when (val config = configLoader()) {
            is DistributionConfigLoadResult.Failure -> CatalogLoadResult.Failure(config.reasonCode, config.retryable)
            is DistributionConfigLoadResult.Success -> config.config.toControlPlaneCatalog()
        }
        when (result) {
            is CatalogLoadResult.Failure -> fail(actionId, result.reasonCode, result.retryable, eventPort)
            is CatalogLoadResult.Success -> {
                val updateStatuses = buildUpdateStatuses(
                    catalog = result.catalog,
                    manifests = result.catalog.manifests,
                    snapshot = snapshot,
                    device = snapshot.device,
                    gateway = maintenanceGateway(connection),
                )
                eventPort.emit(
                    InstallationSessionEvent.MaintenanceCatalogRefreshed(
                        catalogVersion = result.catalog.catalogVersion,
                        keyId = result.catalog.keyId,
                        signatureAlgorithm = result.catalog.signatureAlgorithm,
                        manifests = result.catalog.manifests,
                        catalogRevision = result.catalog.catalogRevision,
                        apps = result.catalog.toComponentDescriptors(snapshot.device?.androidSdk),
                        appFailures = result.catalog.appFailures.associate { it.componentId to it.reasonCode },
                        appFailureRetryable = result.catalog.appFailures.associate { it.componentId to it.retryable },
                        updateStatuses = updateStatuses,
                        controlPlaneOnly = true,
                    ),
                )
                val currentById = snapshot.artifactManifests.associateBy { it.componentId }
                val nextById = result.catalog.manifests
                    .filterNot { InstallerSelfIdentity.isSelfComponentId(it.componentId) }
                    .associateBy { it.componentId }
                val currentComponents = snapshot.components
                    .filter { it.status != com.ninepointnine.helper.domain.session.ComponentStatus.UNLISTED }
                    .associateBy { it.id }
                val nextComponents = result.catalog.toComponentDescriptors(snapshot.device?.androidSdk)
                    .filterNot { InstallerSelfIdentity.isSelfComponentId(it.id) }
                    .associateBy { it.id }
                val currentDeclaredIds = currentComponents.keys
                val nextDeclaredIds = if (result.catalog.apps.isNotEmpty()) {
                    result.catalog.apps.filter { it.enabled }
                        .filterNot { InstallerSelfIdentity.isSelfComponentId(it.componentId) }
                        .map { it.componentId }.toSet()
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
                        current.errorReason != next.errorReason ||
                        current.iconAsset != next.iconAsset
                }
                complete(
                    actionId,
                    if (catalogChanged || descriptorChanged ||
                        updateStatuses.any { it.state == MaintenanceUpdateState.UPDATE_AVAILABLE }
                    ) {
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
        val gateway = maintenanceGateway(connection)
        if (gateway == null) {
            fail(actionId, "device_action_gateway_unavailable", retryable = false, eventPort)
            return
        }
        val candidates = managedComponents(snapshot)
        // Authorization rows need the same live inventory as the management
        // page, including version label and package metadata. The lightweight
        // inventory method intentionally omits those details for update checks.
        val inventory = gateway.inspectManagedApplications(candidates)
        val installedApplications = when (inventory) {
            is ManagedApplicationsResult.Completed -> inventory.applications.filter { it.installed }
            is ManagedApplicationsResult.Failed -> {
                fail(actionId, inventory.failure.reasonCode, inventory.failure.retryable, eventPort)
                return
            }
        }
        eventPort.emit(
            InstallationSessionEvent.MaintenanceApplicationsResolved(
                applications = installedApplications.map { it.toSnapshotStatus() },
            ),
        )
        val installedById = installedApplications.associateBy { it.componentId }
        val installedComponents = installedApplications.map { application ->
            candidates.firstOrNull { it.componentId == application.componentId }
                ?.copy(packageName = application.packageName)
                ?: ManagedComponent(application.componentId, application.packageName, launchComponent = application.launchComponent)
        }
        val declarations = snapshot.evidence.installation.mapNotNull { (componentId, evidence) ->
            evidence.declarations?.let { componentId to it }
        }.toMap()
        eventPort.emit(
            InstallationSessionEvent.MaintenanceAuthorizationCheckStarted(
                componentIds = installedComponents.map { it.componentId },
            ),
        )
        val statuses = when (val inspection = gateway.inspectComponentAuthorization(
            installedComponents,
            installedApplications,
            declarations,
        )) {
            is MaintenanceAuthorizationResult.Failed -> {
                fail(actionId, inspection.failure.reasonCode, inspection.failure.retryable, eventPort)
                return
            }

            is MaintenanceAuthorizationResult.Completed -> inspection.applications
        }
        val statusById = statuses.associateBy { it.componentId }
        val statusShapeInvalid = statuses.size != statusById.size || statuses.any { status ->
            val installed = installedById[status.componentId]
            installed == null || installed.packageName != status.packageName
        }
        if (statusShapeInvalid) {
            fail(actionId, "maintenance_authorization_result_invalid", retryable = false, eventPort)
            return
        }
        val effectiveStatuses = installedComponents.map { component ->
            statusById[component.componentId] ?: ManagedApplicationAuthorizationStatus(
                componentId = component.componentId,
                packageName = component.packageName,
                authorized = null,
                state = MaintenanceAuthorizationState.ERROR,
                reasonCode = "authorization_probe_incomplete",
            )
        }
        effectiveStatuses.forEach { status ->
            eventPort.emit(
                InstallationSessionEvent.MaintenanceAuthorizationCheckProgress(
                    componentId = status.componentId,
                    status = status,
                ),
            )
        }
        eventPort.emit(InstallationSessionEvent.MaintenanceAuthorizationChecked(effectiveStatuses))

        // The first visit is read-only. A second explicit action is the only
        // path that can write authorization values back to the vehicle.
        val shouldRepair = snapshot.maintenance.authorization.state ==
            com.ninepointnine.helper.domain.session.MaintenanceAuthorizationFlowState.REPAIRING
        if (!shouldRepair) {
            complete(actionId, "authorization_checked", eventPort)
            return
        }
        if (effectiveStatuses.isNotEmpty() && effectiveStatuses.all { it.authorized == true }) {
            // The repair action is idempotent. A live readback that already
            // satisfies every installed component needs no historical manifest
            // or device write, which also recovers older persisted sessions.
            complete(actionId, "authorization_repaired", eventPort)
            return
        }
        val verifiedManifests = maintenanceCatalogManifests(snapshot)
        val manifests = verifiedManifests.filter { it.componentId in installedById }
        if (manifests.map { it.componentId }.toSet() != installedById.keys) {
            fail(actionId, "maintenance_manifest_selection_mismatch", retryable = false, eventPort)
            return
        }
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
        completeAction: Boolean = true,
    ): Boolean {
        val gateway = maintenanceGateway(connection)
        if (gateway == null) {
            fail(actionId, "device_action_gateway_unavailable", retryable = false, eventPort)
            return false
        }
        val result = if (actionId == MaintenanceActionId.MANAGE_APPS) {
            gateway.inspectAllInstalledApplications()
        } else {
            gateway.inspectManagedApplications(managedComponents(snapshot))
        }
        when (result) {
            is ManagedApplicationsResult.Failed -> {
                fail(actionId, result.failure.reasonCode, result.failure.retryable, eventPort)
                return false
            }
            is ManagedApplicationsResult.Completed -> {
                val installedApplications = result.applications.filter { it.installed }
                eventPort.emit(
                    InstallationSessionEvent.MaintenanceApplicationsResolved(
                        applications = installedApplications.map { it.toSnapshotStatus() },
                    ),
                )
                if (completeAction) complete(actionId, "applications_checked", eventPort)
                return true
            }
        }
    }

    private suspend fun prepareMaintenanceApplications(
        actionId: MaintenanceActionId,
        snapshot: InstallationSessionSnapshot,
        connection: com.ninepointnine.helper.domain.device.DeviceConnectionLease?,
        eventPort: InstallationSessionEventPort,
    ) {
        // Resolve the signed configuration and local APK inventory first.
        // Remote folder resolution and ZIP/APK preparation wait for the user's
        // explicit selection.
        val loader = loadDistributionSelection
        var effectiveSnapshot = snapshot
        if (loader != null) {
            when (val result = loader()) {
                is CatalogLoadResult.Failure -> {
                    fail(actionId, result.reasonCode, result.retryable, eventPort)
                    return
                }

                is CatalogLoadResult.Success -> {
                    val descriptors = result.catalog.toComponentDescriptors(snapshot.device?.androidSdk)
                    effectiveSnapshot = snapshot.copy(
                        components = descriptors,
                        maintenance = snapshot.maintenance.copy(availableComponents = descriptors),
                    )
                    eventPort.emit(
                        InstallationSessionEvent.MaintenanceCatalogRefreshed(
                            catalogVersion = result.catalog.catalogVersion,
                            keyId = result.catalog.keyId,
                            signatureAlgorithm = result.catalog.signatureAlgorithm,
                            manifests = result.catalog.manifests,
                            catalogRevision = result.catalog.catalogRevision,
                            apps = descriptors,
                            appFailures = result.catalog.appFailures.associate { it.componentId to it.reasonCode },
                            appFailureRetryable = result.catalog.appFailures.associate { it.componentId to it.retryable },
                            updateStatuses = emptyList(),
                            controlPlaneOnly = true,
                        ),
                    )
                }
            }
        }
        inspectApplications(actionId, effectiveSnapshot, connection, eventPort, completeAction = false)
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
        val manifest = maintenanceCatalogManifests(snapshot).firstOrNull { it.componentId == componentId }
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
        displayName = displayName,
        versionLabel = versionLabel,
        versionCode = versionCode,
        fileSizeBytes = fileSizeBytes,
        installTimeEpochMillis = installTimeEpochMillis,
        updateTimeEpochMillis = updateTimeEpochMillis,
        filePath = filePath,
        uid = uid,
        iconBase64 = iconBase64,
        launchComponent = launchComponent,
    )

    private fun managedComponents(snapshot: InstallationSessionSnapshot): List<ManagedComponent> {
        val byId = linkedMapOf<String, ManagedComponent>()
        fun add(component: ManagedComponent) {
            if (!InstallerSelfIdentity.isSelfComponentId(component.componentId)) {
                byId.putIfAbsent(component.componentId, component)
            }
        }
        maintenanceCatalogManifests(snapshot).forEach { manifest ->
            add(ManagedComponent(manifest.componentId, manifest.packageName, manifest.deviceSetup, manifest.sortOrder))
        }
        snapshot.maintenance.availableComponents.forEach { descriptor ->
            val packageName = descriptor.packageName.takeIf { it.isNotBlank() } ?: return@forEach
            add(ManagedComponent(descriptor.id, packageName, order = byId.size))
        }
        snapshot.evidence.installation.forEach { (componentId, evidence) ->
            val current = byId[componentId]
            if (current == null) {
                add(ManagedComponent(componentId, evidence.packageName))
            } else if (evidence.packageName.isNotBlank()) {
                // A verified installation record is the most recent package
                // identity available before the next live inventory read.
                byId[componentId] = current.copy(packageName = evidence.packageName)
            }
        }
        snapshot.maintenance.managedApplications.forEach { application ->
            val currentEntry = byId.entries.singleOrNull { (_, component) ->
                component.packageName == application.packageName
            } ?: return@forEach
            currentEntry.setValue(
                currentEntry.value.copy(
                    packageName = application.packageName,
                    launchComponent = application.launchComponent ?: currentEntry.value.launchComponent,
                ),
            )
        }
        snapshot.components.forEach { descriptor ->
            if (byId[descriptor.id] == null) {
                val packageName = descriptor.packageName.takeIf { it.isNotBlank() }
                packageName?.let { add(ManagedComponent(descriptor.id, it, order = byId.size)) }
            }
        }
        return byId.values.toList()
    }

    private suspend fun buildUpdateStatuses(
        catalog: TrustedArtifactCatalog,
        manifests: List<com.ninepointnine.helper.domain.artifact.ArtifactManifest>,
        snapshot: InstallationSessionSnapshot,
        device: com.ninepointnine.helper.domain.session.DeviceSummary?,
        gateway: com.ninepointnine.helper.domain.device.MaintenanceCommandGateway?,
    ): List<MaintenanceUpdateStatus> {
        val knownPackages = buildMap<String, String> {
            snapshot.maintenance.managedApplications.forEach { application ->
                putIfAbsent(application.componentId, application.packageName)
            }
            snapshot.evidence.installation.forEach { (componentId, evidence) ->
                putIfAbsent(componentId, evidence.packageName)
            }
            maintenanceCatalogManifests(snapshot).forEach { manifest ->
                putIfAbsent(manifest.componentId, manifest.packageName)
            }
        }
        val candidates = if (catalog.apps.isNotEmpty()) {
            catalog.apps.filter { it.enabled }.map { app ->
                val packageName = app.packageName.ifBlank {
                    knownPackages[app.componentId]
                        ?: InstallerSelfIdentity.PACKAGE_NAME.takeIf {
                            InstallerSelfIdentity.isSelfComponentId(app.componentId)
                        }
                        .orEmpty()
                }
                UpdateCandidate(
                    componentId = app.componentId,
                    displayName = app.displayName,
                    packageName = packageName,
                    version = ArtifactVersion(app.versionName, app.versionCode),
                    iconKey = app.componentId,
                )
            }
        } else {
            manifests.map { manifest ->
                UpdateCandidate(
                    componentId = manifest.componentId,
                    displayName = manifest.displayName,
                    packageName = manifest.packageName,
                    version = manifest.apkVersion,
                    iconKey = manifest.componentId,
                )
            }
        }
        val configuredSelf = candidates.firstOrNull { InstallerSelfIdentity.isSelfComponentId(it.componentId) }
        val selfStatuses = listOf(
            MaintenanceUpdateStatus(
                componentId = configuredSelf?.componentId ?: InstallerSelfIdentity.COMPONENT_ID,
                displayName = configuredSelf?.displayName?.ifBlank { null } ?: "03车机助手",
                versionLabel = configuredSelf?.version?.name ?: selfVersion.name,
                installedVersionLabel = selfVersion.name,
                state = configuredSelf?.let { compareVersions(selfVersion, it.version) }
                    ?: MaintenanceUpdateState.CURRENT,
                isSelf = true,
                iconKey = InstallerSelfIdentity.COMPONENT_ID,
            ),
        )
        val appCandidates = candidates.filterNot { InstallerSelfIdentity.isSelfComponentId(it.componentId) }
        if (appCandidates.isEmpty()) return selfStatuses
        if (device?.connectionStatus != com.ninepointnine.helper.domain.session.DeviceConnectionStatus.CONFIRMED) {
            return selfStatuses
        }
        val probeableCandidates = appCandidates.filter { candidate ->
            PACKAGE_NAME_PATTERN.matches(candidate.packageName)
        }
        val manifestById = manifests.associateBy { it.componentId }
        var inventoryAvailable = gateway != null && probeableCandidates.isNotEmpty()
        val installed = if (inventoryAvailable) {
            when (val result = gateway!!.inspectInstalledApplicationInventory(
                probeableCandidates.map { candidate ->
                    val manifest = manifestById[candidate.componentId]
                    ManagedComponent(
                        candidate.componentId,
                        candidate.packageName,
                        manifest?.deviceSetup,
                        manifest?.sortOrder ?: Int.MAX_VALUE,
                    )
                },
            )) {
                is ManagedApplicationsResult.Completed -> result.applications
                    .filter { it.installed }
                    .map { it.toSnapshotStatus() }
                    .associateBy { it.componentId }
                is ManagedApplicationsResult.Failed -> {
                    inventoryAvailable = false
                    emptyMap()
                }
            }
        } else {
            emptyMap()
        }
        return selfStatuses + appCandidates.map { candidate ->
                val current = installed[candidate.componentId]
                val state = when {
                    !inventoryAvailable || !PACKAGE_NAME_PATTERN.matches(candidate.packageName) ->
                        MaintenanceUpdateState.UNAVAILABLE
                    current == null -> MaintenanceUpdateState.NOT_INSTALLED
                    !current.installed -> MaintenanceUpdateState.NOT_INSTALLED
                    current.versionCode == null -> MaintenanceUpdateState.UNAVAILABLE
                    compareVersions(
                        ArtifactVersion(current.versionLabel ?: "", current.versionCode),
                        candidate.version,
                    ) == MaintenanceUpdateState.UPDATE_AVAILABLE -> MaintenanceUpdateState.UPDATE_AVAILABLE
                    else -> MaintenanceUpdateState.CURRENT
                }
                MaintenanceUpdateStatus(
                    componentId = candidate.componentId,
                    displayName = candidate.displayName,
                    versionLabel = candidate.version.name,
                    installedVersionLabel = current?.versionLabel,
                    state = state,
                    isSelf = false,
                    iconKey = candidate.iconKey,
                )
            }
    }

    private data class UpdateCandidate(
        val componentId: String,
        val displayName: String,
        val packageName: String,
        val version: ArtifactVersion,
        val iconKey: String,
    )

    private companion object {
        val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }

    private fun compareVersions(
        installed: ArtifactVersion,
        available: ArtifactVersion,
    ): MaintenanceUpdateState = when {
        installed.code <= 0L -> MaintenanceUpdateState.NOT_INSTALLED
        installed.code < available.code -> MaintenanceUpdateState.UPDATE_AVAILABLE
        // versionCode is the only ordering key. A republished APK may carry a
        // different display name while remaining the same install identity;
        // never turn that metadata difference into a false update.
        else -> MaintenanceUpdateState.CURRENT
    }

    /**
     * Explicit maintenance catalog projection. A current prepared batch wins
     * over an older available entry with the same component id; when no batch
     * exists, the signed maintenance catalog is the sole source.
     */
    private fun maintenanceCatalogManifests(
        snapshot: InstallationSessionSnapshot,
    ): List<com.ninepointnine.helper.domain.artifact.ArtifactManifest> {
        val byId = linkedMapOf<String, com.ninepointnine.helper.domain.artifact.ArtifactManifest>()
        snapshot.maintenance.availableManifests.forEach { manifest ->
            byId[manifest.componentId] = manifest
        }
        snapshot.maintenance.installedManifests.forEach { manifest ->
            byId[manifest.componentId] = manifest
        }
        if (snapshot.artifactCatalogStage == ArtifactCatalogStage.PREPARED) {
            snapshot.artifactManifests.forEach { manifest ->
                byId[manifest.componentId] = manifest
            }
        }
        return byId.values.toList()
    }

}

private fun InstallerDistributionConfig.toControlPlaneCatalog(): CatalogLoadResult.Success =
    CatalogLoadResult.Success(
        TrustedArtifactCatalog(
            catalogVersion = effectiveCatalogVersion(),
            keyId = keyId,
            signatureAlgorithm = signatureAlgorithm,
            manifests = emptyList(),
            apps = declaredApps().filter { it.enabled },
            catalogRevision = catalogRevision,
        ),
    )
