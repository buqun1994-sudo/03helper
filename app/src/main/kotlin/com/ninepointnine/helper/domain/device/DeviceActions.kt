package com.ninepointnine.helper.domain.device

import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.InstallerComponentTrustRegistry
import com.ninepointnine.helper.domain.session.MaintenanceApplicationActionId
import com.ninepointnine.helper.domain.session.InstallationStrategy
import java.io.File
import java.util.LinkedHashSet

/**
 * The only device-action port exposed after a connection lease has completed
 * its fixed identity handshake. It deliberately has no arbitrary shell API.
 */
interface DeviceActionConnectionLease : DeviceConnectionLease {
    val commandGateway: AdbCommandGateway

    /** Optional F4 port; old connection fakes remain valid and fail closed. */
    val maintenanceGateway: MaintenanceCommandGateway?
        get() = commandGateway as? MaintenanceCommandGateway
}

interface AdbCommandGateway {
    suspend fun install(artifacts: List<InstallableArtifact>): DeviceInstallResult

    /**
     * Installs a verified batch according to the session's explicit intent.
     * Existing gateways remain source-compatible through the legacy overload.
     */
    suspend fun install(
        artifacts: List<InstallableArtifact>,
        strategy: InstallationStrategy,
    ): DeviceInstallResult = install(artifacts)

    /**
     * Runs the one versioned authorization plan for the selected components.
     * The caller supplies validated component ids, never shell text.
     */
    suspend fun runShortcut(
        shortcut: DeviceShortcut,
        selectedComponentIds: Set<String>,
    ): DeviceShortcutResult

    /** Dynamic v3 path; old fakes can continue to implement the narrow method. */
    suspend fun runShortcut(
        shortcut: DeviceShortcut,
        selectedComponentIds: Set<String>,
        authorizationPlan: AuthorizationPlan,
    ): DeviceShortcutResult = runShortcut(shortcut, selectedComponentIds)
}

/** Fixed, non-shell maintenance operations available after a confirmed lease. */
interface MaintenanceCommandGateway {
    suspend fun repairAuthorization(manifests: List<ArtifactManifest>): MaintenanceDeviceResult

    /** Optional declaration receipt from the verified install; implementations may use it to avoid re-pulling APKs. */
    suspend fun repairAuthorization(
        manifests: List<ArtifactManifest>,
        declarationsByComponent: Map<String, ApkDeclarationMetadata>,
    ): MaintenanceDeviceResult = repairAuthorization(manifests)

    suspend fun inspectManagedApplications(): ManagedApplicationsResult

    suspend fun inspectManagedApplications(
        components: List<ManagedComponent>,
    ): ManagedApplicationsResult = inspectManagedApplications()

    /**
     * Reads the device package inventory once and returns only recognized,
     * currently installed components. The default keeps older test gateways
     * source-compatible while production gateways can use package discovery.
     */
    suspend fun inspectInstalledApplicationInventory(
        components: List<ManagedComponent>,
    ): ManagedApplicationsResult = inspectManagedApplications(components)

    suspend fun launchManagedComponent(componentId: String): MaintenanceDeviceResult

    /** Dynamic maintenance launch uses the verified package identity plus a typed catalog setup. */
    suspend fun launchManagedComponent(component: ManagedComponent): MaintenanceDeviceResult =
        launchManagedComponent(component.componentId)

    /** Read-only authorization inspection. Implementations must not mutate the device. */
    suspend fun inspectAuthorization(
        manifests: List<ArtifactManifest>,
    ): MaintenanceAuthorizationResult = MaintenanceAuthorizationResult.Completed(emptyList())

    /** Read-only authorization probe for the components found in the live inventory. */
    suspend fun inspectComponentAuthorization(
        components: List<ManagedComponent>,
    ): MaintenanceAuthorizationResult = MaintenanceAuthorizationResult.Completed(emptyList())

    /**
     * Probes authorization against the inventory read by the same maintenance
     * action. Production uses this overload to avoid a second package scan and
     * to keep version and authorization state tied to one car snapshot.
     */
    suspend fun inspectComponentAuthorization(
        components: List<ManagedComponent>,
        installedApplications: List<ManagedApplicationProbe>,
    ): MaintenanceAuthorizationResult = inspectComponentAuthorization(components)

    /** Fixed application operation selected by the maintenance UI. */
    suspend fun performApplicationAction(
        component: ManagedComponent,
        actionId: MaintenanceApplicationActionId,
    ): MaintenanceDeviceResult = when (actionId) {
        MaintenanceApplicationActionId.START -> launchManagedComponent(component)
        MaintenanceApplicationActionId.FORCE_STOP,
        MaintenanceApplicationActionId.UNINSTALL,
        MaintenanceApplicationActionId.DETAILS,
        -> MaintenanceDeviceResult.Failed(
            DeviceActionFailure("maintenance_application_action_unavailable", component.componentId, retryable = false),
        )
    }

    suspend fun inspectManagedApplicationDetails(
        component: ManagedComponent,
    ): ManagedApplicationDetailsProbeResult = ManagedApplicationDetailsProbeResult.Failed(
        DeviceActionFailure("maintenance_application_details_unavailable", component.componentId, retryable = false),
    )
}

sealed interface MaintenanceDeviceResult {
    data class Completed(val resultCode: String = "completed") : MaintenanceDeviceResult

    data class Failed(val failure: DeviceActionFailure) : MaintenanceDeviceResult
}

data class ManagedApplicationProbe(
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
)

data class ManagedApplicationDetailsProbe(
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
)

sealed interface ManagedApplicationDetailsProbeResult {
    data class Completed(val details: ManagedApplicationDetailsProbe) : ManagedApplicationDetailsProbeResult

    data class Failed(val failure: DeviceActionFailure) : ManagedApplicationDetailsProbeResult
}

enum class MaintenanceAuthorizationState {
    CHECKING,
    AUTHORIZED,
    NOT_AUTHORIZED,
    UNKNOWN,
    ERROR,
}

data class ManagedApplicationAuthorizationStatus(
    val componentId: String,
    val packageName: String,
    val authorized: Boolean?,
    val state: MaintenanceAuthorizationState = when (authorized) {
        true -> MaintenanceAuthorizationState.AUTHORIZED
        false -> MaintenanceAuthorizationState.NOT_AUTHORIZED
        null -> MaintenanceAuthorizationState.UNKNOWN
    },
    val reasonCode: String? = null,
)

sealed interface MaintenanceAuthorizationResult {
    data class Completed(
        val applications: List<ManagedApplicationAuthorizationStatus>,
    ) : MaintenanceAuthorizationResult

    data class Failed(val failure: DeviceActionFailure) : MaintenanceAuthorizationResult
}

sealed interface ManagedApplicationsResult {
    data class Completed(val applications: List<ManagedApplicationProbe>) : ManagedApplicationsResult

    data class Failed(val failure: DeviceActionFailure) : ManagedApplicationsResult
}

/** Capabilities read from an APK Manifest before any device-side write. */
data class ApkDeclarationMetadata(
    val requestedPermissions: Set<String> = emptySet(),
    val services: Set<ApkServiceDeclaration> = emptySet(),
)

data class ApkServiceDeclaration(
    val componentName: String,
    val permission: String?,
)

data class InstallableArtifact(
    val manifest: ArtifactManifest,
    /** Null only when the device inventory already contains this exact package. */
    val apkFile: File?,
    val declarations: ApkDeclarationMetadata? = null,
)

/**
 * Cloud may request only these typed setup primitives. The declaration is
 * compiled into [AuthorizationAction] values before it can reach ADB; it can
 * never carry shell text, a package identity, or an arbitrary setting name.
 */
data class AuthorizationSetupDeclaration(
    val appOps: Set<ManagedAppOp> = emptySet(),
    val runtimePermissions: Set<ManagedRuntimePermission> = emptySet(),
    val secureSettings: Set<ManagedSecureFlag> = emptySet(),
    val secureComponents: Set<SecureComponent> = emptySet(),
    val launchComponent: String? = null,
    val requiredServices: Set<String> = emptySet(),
    /** Cloud profile selector compiled against the local authorization registry. */
    val profileId: String = "",
    /** Cloud action selectors; values are identifiers, never shell text. */
    val actionIds: Set<String> = emptySet(),
) {
    data class SecureComponent(
        val setting: ManagedSecureComponentList,
        val targetComponent: String,
    )
}

sealed interface DeviceInstallResult {
    data class Installed(
        val evidence: List<InstalledArtifactEvidence>,
        /** Housekeeping warnings never invalidate the verified package write. */
        val warnings: List<DeviceInstallWarning> = emptyList(),
        /** Components for which PackageManager accepted a fresh device write. */
        val writeConfirmedComponentIds: Set<String> = emptySet(),
    ) : DeviceInstallResult

    data class Failed(
        val failure: DeviceActionFailure,
        /** Confirmed writes that happened before a later command failed. */
        val writeConfirmedComponentIds: Set<String> = emptySet(),
        /** Verified identities collected before a later command failed. */
        val verifiedEvidence: List<InstalledArtifactEvidence> = emptyList(),
        val warnings: List<DeviceInstallWarning> = emptyList(),
    ) : DeviceInstallResult

    /**
     * PackageManager accepted a write, but the installed APK identity could
     * not be read back yet. The component is pending confirmation, not an
     * ordinary install failure; a package/certificate mismatch still carries
     * its concrete failure in [failure].
     */
    data class WrittenButUnverified(
        val writeConfirmedComponentIds: Set<String>,
        val failure: DeviceActionFailure,
        val verifiedEvidence: List<InstalledArtifactEvidence> = emptyList(),
        val warnings: List<DeviceInstallWarning> = emptyList(),
        val confirmationPendingComponentIds: Set<String> = emptySet(),
    ) : DeviceInstallResult
}

data class DeviceInstallWarning(
    val reasonCode: String,
    val componentId: String? = null,
)

data class InstalledArtifactEvidence(
    val componentId: String,
    val packageName: String,
    val version: ArtifactVersion,
    val apkSizeBytes: Long,
    val apkSha256: String,
    val certificateSha256: String,
    val declarations: ApkDeclarationMetadata? = null,
)

sealed interface DeviceAuthorizationResult {
    data class Authorized(
        val componentIds: Set<String>,
        val evidence: List<AuthorizationActionEvidence>,
    ) : DeviceAuthorizationResult

    data class Failed(val failure: DeviceActionFailure) : DeviceAuthorizationResult
}

sealed interface DeviceAvailabilityResult {
    data class Available(val evidence: List<DeviceAvailabilityEvidence>) : DeviceAvailabilityResult

    data class Failed(val failure: DeviceActionFailure) : DeviceAvailabilityResult
}

enum class DeviceShortcut {
    CONFIGURE_ALL_INSTALLED_APPS_AND_START_DESKTOP,
    /** Applies only the supplied typed actions; it never launches the desktop. */
    CONFIGURE_SELECTED_APPS,
}

sealed interface DeviceShortcutResult {
    data class Completed(
        val configuredComponentIds: Set<String>,
        val skippedComponentIds: Set<String>,
        val authorizationEvidence: List<AuthorizationActionEvidence>,
        val availabilityEvidence: List<ManagedApplicationAvailabilityEvidence>,
    ) : DeviceShortcutResult

    data class Failed(
        val stage: DeviceShortcutFailureStage,
        val failure: DeviceActionFailure,
        /** Structured receipts emitted before the command stopped. */
        val configuredComponentIds: Set<String> = emptySet(),
        val skippedComponentIds: Set<String> = emptySet(),
        val authorizationEvidence: List<AuthorizationActionEvidence> = emptyList(),
        val availabilityEvidence: List<ManagedApplicationAvailabilityEvidence> = emptyList(),
    ) : DeviceShortcutResult
}

enum class DeviceShortcutFailureStage {
    AUTHORIZATION,
    VERIFICATION,
}

data class ManagedApplicationAvailabilityEvidence(
    val componentId: String,
    val packageName: String,
    val launchAttempted: Boolean,
    val launcherResolved: Boolean,
    val processRunning: Boolean,
    val requiredServiceBound: Boolean?,
)

data class DeviceActionFailure(
    val reasonCode: String,
    val componentId: String? = null,
    val retryable: Boolean = true,
)

/** A compact, non-sensitive record of the before/write/after authorization transaction. */
data class AuthorizationActionEvidence(
    val componentId: String,
    val actionId: String,
    val before: AuthorizationValueState,
    val writeApplied: Boolean,
    val after: AuthorizationValueState,
    val preservedEntryCount: Int? = null,
)

enum class AuthorizationValueState {
    ALLOWED,
    DENIED,
    GRANTED,
    DEFAULT,
    IGNORED,
    ERRORED,
    ENABLED,
    DISABLED,
    COMPONENT_PRESENT,
    COMPONENT_ABSENT,
}

data class DeviceAvailabilityEvidence(
    val componentId: String,
    val packageName: String,
    val version: ArtifactVersion,
    val installedArchiveVerified: Boolean,
    val launchAttempted: Boolean,
    val launcherResolved: Boolean,
    val processRunning: Boolean,
    val requiredServiceBound: Boolean?,
)

/** Versioned typed action schema. Catalog data can select components but cannot add actions. */
data class AuthorizationPlan(
    val version: Int,
    val components: List<ManagedComponent>,
    val actions: List<AuthorizationAction>,
)

data class ManagedComponent(
    val componentId: String,
    val packageName: String,
    val setup: AuthorizationSetupDeclaration? = null,
    val order: Int = Int.MAX_VALUE,
)

sealed interface AuthorizationAction {
    val id: String
    val componentId: String

    data class EnsureAppOpAllowed(
        override val id: String,
        override val componentId: String,
        val packageName: String,
        val operation: ManagedAppOp,
    ) : AuthorizationAction

    data class EnsureRuntimePermissionGranted(
        override val id: String,
        override val componentId: String,
        val packageName: String,
        val permission: ManagedRuntimePermission,
    ) : AuthorizationAction

    data class EnsureSecureSettingEnabled(
        override val id: String,
        override val componentId: String,
        val setting: ManagedSecureFlag,
    ) : AuthorizationAction

    data class AppendSecureComponent(
        override val id: String,
        override val componentId: String,
        val setting: ManagedSecureComponentList,
        val targetComponent: String,
    ) : AuthorizationAction
}

enum class ManagedAppOp(val wireName: String) {
    SYSTEM_ALERT_WINDOW("SYSTEM_ALERT_WINDOW"),
    REQUEST_INSTALL_PACKAGES("REQUEST_INSTALL_PACKAGES"),
    READ_EXTERNAL_STORAGE("READ_EXTERNAL_STORAGE"),
    WRITE_EXTERNAL_STORAGE("WRITE_EXTERNAL_STORAGE"),
}

enum class ManagedRuntimePermission(val wireName: String) {
    READ_EXTERNAL_STORAGE("android.permission.READ_EXTERNAL_STORAGE"),
    WRITE_EXTERNAL_STORAGE("android.permission.WRITE_EXTERNAL_STORAGE"),
}

enum class ManagedSecureFlag(val wireName: String) {
    ACCESSIBILITY_ENABLED("accessibility_enabled"),
}

enum class ManagedSecureComponentList(val wireName: String) {
    ENABLED_NOTIFICATION_LISTENERS("enabled_notification_listeners"),
    ENABLED_ACCESSIBILITY_SERVICES("enabled_accessibility_services"),
}

sealed interface AuthorizationPlanBuildResult {
    data class Ready(val plan: AuthorizationPlan) : AuthorizationPlanBuildResult

    data class Rejected(val reasonCode: String) : AuthorizationPlanBuildResult
}

data class AuthorizationCapacityLimits(
    val maxEntriesPerSecureList: Int = 32,
    val maxSerializedBytesPerSecureList: Int = 4 * 1024,
)

/**
 * Android 9 stores authorization lists as colon-delimited secure strings. The
 * policy calculates the post-write values before any mutation and can be
 * supplied with the measured limits of a target head unit.
 */
object AuthorizationCapacityPolicy {
    val ANDROID_9_DEFAULT = AuthorizationCapacityLimits()

    fun validate(
        plan: AuthorizationPlan,
        existingValues: Map<ManagedSecureComponentList, String?>,
        limits: AuthorizationCapacityLimits = ANDROID_9_DEFAULT,
    ): String? {
        if (limits.maxEntriesPerSecureList <= 0 || limits.maxSerializedBytesPerSecureList <= 0) {
            return "authorization_capacity_policy_invalid"
        }
        val actionsBySetting = plan.actions.filterIsInstance<AuthorizationAction.AppendSecureComponent>()
            .groupBy { it.setting }
        actionsBySetting.forEach { (setting, actions) ->
            val values = LinkedHashSet<String>()
            existingValues[setting].orEmpty()
                .takeUnless { it == "null" }
                ?.split(':')
                ?.filter(String::isNotBlank)
                ?.forEach(values::add)
            actions.forEach { action ->
                values += action.targetComponent
            }
            if (values.size > limits.maxEntriesPerSecureList) return "authorization_capacity_entries_exceeded"
            if (values.joinToString(":").toByteArray(Charsets.UTF_8).size > limits.maxSerializedBytesPerSecureList) {
                return "authorization_capacity_bytes_exceeded"
            }
        }
        return null
    }
}

/**
 * The release manifest supplies artifact identity, not system commands. This
 * factory is the sole mapping from a verified component to its approved setup.
 */
object AuthorizationPlanFactory {
    const val CURRENT_VERSION = 1

    fun create(artifacts: List<InstallableArtifact>): AuthorizationPlanBuildResult =
        createComponents(
            artifacts.map { artifact ->
                ManagedComponent(
                    componentId = artifact.manifest.componentId,
                    packageName = artifact.manifest.packageName,
                    setup = artifact.manifest.deviceSetup,
                    order = artifact.manifest.sortOrder,
                )
            },
        )

    fun createForManifests(
        manifests: List<ArtifactManifest>,
        requireDesktop: Boolean = true,
    ): AuthorizationPlanBuildResult =
        createComponents(
            manifests.map {
                ManagedComponent(
                    componentId = it.componentId,
                    packageName = it.packageName,
                    setup = it.deviceSetup,
                    order = it.sortOrder,
                )
            },
            requireDesktop = requireDesktop,
        )

    fun createForComponents(
        components: List<ManagedComponent>,
        requireDesktop: Boolean = true,
    ): AuthorizationPlanBuildResult = createComponents(components, requireDesktop = requireDesktop)

    /**
     * Builds the same typed action set for a read-only probe without requiring
     * the desktop component. Repair/install plans continue to require desktop.
     */
    fun createForInspection(components: List<ManagedComponent>): AuthorizationPlanBuildResult =
        createComponents(components, requireDesktop = false)

    /**
     * Validates one catalog component before it is combined with the selected
     * installation batch. The catalog layer uses this to isolate a malformed
     * optional setup instead of letting it reject unrelated applications.
     */
    fun validateComponent(component: ManagedComponent): Boolean = isAllowedDynamicComponent(component)

    /** Returns the built-in component descriptors used by the legacy overload. */
    fun allManagedComponents(): List<ManagedComponent> = listOf(
        ManagedComponent(DESKTOP_COMPONENT_ID, DESKTOP_PACKAGE_NAME, order = 0),
        ManagedComponent(LYRICS_COMPONENT_ID, LYRICS_PACKAGE_NAME, order = 1),
        ManagedComponent(FILE_MANAGER_COMPONENT_ID, FILE_MANAGER_PACKAGE_NAME, order = 2),
        ManagedComponent(
            InstallerComponentTrustRegistry.CAST_COMPONENT_ID,
            InstallerComponentTrustRegistry.CAST_PACKAGE_NAME,
            order = 3,
        ),
    )

    fun validateEvidence(
        plan: AuthorizationPlan,
        evidence: List<AuthorizationActionEvidence>,
    ): Boolean {
        if (evidence.size != plan.actions.size || evidence.map { it.actionId }.toSet().size != evidence.size) {
            return false
        }
        val actions = plan.actions.associateBy { it.id }
        if (evidence.map { it.actionId }.toSet() != actions.keys) return false
        return validateEvidenceSubset(plan, evidence)
    }

    /**
     * Validates evidence emitted before a device-side action failed. A partial
     * response is useful only when every retained row still proves one typed
     * action from this exact plan; unknown or duplicated rows are rejected.
     */
    fun validateEvidenceSubset(
        plan: AuthorizationPlan,
        evidence: List<AuthorizationActionEvidence>,
    ): Boolean {
        if (evidence.map { it.actionId }.toSet().size != evidence.size) return false
        val actions = plan.actions.associateBy { it.id }
        return evidence.all { item ->
            val action = actions[item.actionId] ?: return@all false
            if (item.componentId != action.componentId || item.writeApplied != (item.before != item.after)) {
                return@all false
            }
            when (action) {
                is AuthorizationAction.EnsureAppOpAllowed ->
                    item.after == AuthorizationValueState.ALLOWED && item.preservedEntryCount == null

                is AuthorizationAction.EnsureRuntimePermissionGranted ->
                    item.after == AuthorizationValueState.GRANTED && item.preservedEntryCount == null

                is AuthorizationAction.EnsureSecureSettingEnabled ->
                    item.after == AuthorizationValueState.ENABLED && item.preservedEntryCount == null

                is AuthorizationAction.AppendSecureComponent ->
                    item.after == AuthorizationValueState.COMPONENT_PRESENT &&
                        item.preservedEntryCount != null && item.preservedEntryCount >= 0
            }
        }
    }

    /** Components for which every typed action has a valid retained receipt. */
    fun configuredComponentIdsForEvidence(
        plan: AuthorizationPlan,
        evidence: List<AuthorizationActionEvidence>,
    ): Set<String> {
        if (!validateEvidenceSubset(plan, evidence)) return emptySet()
        val evidenceIds = evidence.map { it.actionId }.toSet()
        return plan.components
            .filter { component ->
                plan.actions
                    .filter { action -> action.componentId == component.componentId }
                    .all { action -> action.id in evidenceIds }
            }
            .map { it.componentId }
            .toSet()
    }

    private fun createComponents(
        components: List<ManagedComponent>,
        requireDesktop: Boolean = true,
    ): AuthorizationPlanBuildResult {
        if (components.isEmpty()) return AuthorizationPlanBuildResult.Rejected("authorization_artifacts_missing")
        if (components.map { it.componentId }.toSet().size != components.size) {
            return AuthorizationPlanBuildResult.Rejected("authorization_component_duplicate")
        }
        if (components.any { component -> !isAllowedDynamicComponent(component) }) {
            return AuthorizationPlanBuildResult.Rejected("authorization_component_unapproved")
        }
        if (requireDesktop && components.none { it.componentId == DESKTOP_COMPONENT_ID }) {
            return AuthorizationPlanBuildResult.Rejected("authorization_desktop_missing")
        }
        val orderedComponents = components.sortedWith(compareBy<ManagedComponent> { componentOrder(it) }.thenBy { it.componentId })
        return AuthorizationPlanBuildResult.Ready(
            AuthorizationPlan(
                version = CURRENT_VERSION,
                components = orderedComponents,
                actions = orderedComponents.flatMap(::actionsFor),
            ),
        )
    }

    fun validate(plan: AuthorizationPlan): Boolean {
        if (plan.version != CURRENT_VERSION || plan.components.isEmpty()) return false
        if (plan.components.map { it.componentId }.toSet().size != plan.components.size) return false
        if (plan.components.any { !isAllowedDynamicComponent(it) }) return false
        val expectedComponents = plan.components.sortedWith(compareBy<ManagedComponent> { componentOrder(it) }.thenBy { it.componentId })
        return plan.components == expectedComponents &&
            plan.actions == expectedComponents.flatMap(::actionsFor)
    }

    fun requiredRuntimeService(component: ManagedComponent): String? =
        requiredRuntimeServices(component).firstOrNull()

    fun requiredRuntimeServices(component: ManagedComponent): List<String> =
        component.setup?.requiredServices?.toList()?.sorted()
            ?.takeIf { it.isNotEmpty() }
            ?: managedComponent(component)?.requiredRuntimeServices.orEmpty()

    fun fixedLaunchComponent(component: ManagedComponent): String? =
        component.setup?.launchComponent ?: managedComponent(component)?.fixedLaunchComponent

    private fun actionsFor(component: ManagedComponent): List<AuthorizationAction> {
        val contract = managedComponent(component)
        if (contract == null) return component.setup?.let { compileSetupActions(component, it) }.orEmpty()
        val fixed = when (component.componentId) {
        LYRICS_COMPONENT_ID -> listOf(
            AuthorizationAction.EnsureAppOpAllowed(
                id = "lyrics-overlay-v1",
                componentId = component.componentId,
                packageName = component.packageName,
                operation = ManagedAppOp.SYSTEM_ALERT_WINDOW,
            ),
            AuthorizationAction.AppendSecureComponent(
                id = "lyrics-notification-listener-v1",
                componentId = component.componentId,
                setting = ManagedSecureComponentList.ENABLED_NOTIFICATION_LISTENERS,
                targetComponent = checkNotNull(contract.requiredRuntimeServices.firstOrNull()),
            ),
            AuthorizationAction.AppendSecureComponent(
                id = "lyrics-accessibility-service-v1",
                componentId = component.componentId,
                setting = ManagedSecureComponentList.ENABLED_ACCESSIBILITY_SERVICES,
                targetComponent = checkNotNull(contract.requiredRuntimeServices.getOrNull(1)),
            ),
        )

        DESKTOP_COMPONENT_ID -> listOf(
            AuthorizationAction.EnsureAppOpAllowed(
                id = "desktop-overlay-v1",
                componentId = component.componentId,
                packageName = component.packageName,
                operation = ManagedAppOp.SYSTEM_ALERT_WINDOW,
            ),
            AuthorizationAction.EnsureAppOpAllowed(
                id = "desktop-install-packages-v1",
                componentId = component.componentId,
                packageName = component.packageName,
                operation = ManagedAppOp.REQUEST_INSTALL_PACKAGES,
            ),
            AuthorizationAction.EnsureSecureSettingEnabled(
                id = "desktop-accessibility-master-v1",
                componentId = component.componentId,
                setting = ManagedSecureFlag.ACCESSIBILITY_ENABLED,
            ),
            AuthorizationAction.AppendSecureComponent(
                id = "desktop-accessibility-service-v1",
                componentId = component.componentId,
                setting = ManagedSecureComponentList.ENABLED_ACCESSIBILITY_SERVICES,
                targetComponent = checkNotNull(contract.requiredRuntimeServices.singleOrNull()),
            ),
        )

        FILE_MANAGER_COMPONENT_ID -> listOf(
            AuthorizationAction.EnsureRuntimePermissionGranted(
                id = "file-manager-read-permission-v1",
                componentId = component.componentId,
                packageName = component.packageName,
                permission = ManagedRuntimePermission.READ_EXTERNAL_STORAGE,
            ),
            AuthorizationAction.EnsureAppOpAllowed(
                id = "file-manager-read-appop-v1",
                componentId = component.componentId,
                packageName = component.packageName,
                operation = ManagedAppOp.READ_EXTERNAL_STORAGE,
            ),
            AuthorizationAction.EnsureRuntimePermissionGranted(
                id = "file-manager-write-permission-v1",
                componentId = component.componentId,
                packageName = component.packageName,
                permission = ManagedRuntimePermission.WRITE_EXTERNAL_STORAGE,
            ),
            AuthorizationAction.EnsureAppOpAllowed(
                id = "file-manager-write-appop-v1",
                componentId = component.componentId,
                packageName = component.packageName,
                operation = ManagedAppOp.WRITE_EXTERNAL_STORAGE,
            ),
            AuthorizationAction.EnsureAppOpAllowed(
                id = "file-manager-install-packages-v1",
                componentId = component.componentId,
                packageName = component.packageName,
                operation = ManagedAppOp.REQUEST_INSTALL_PACKAGES,
            ),
        )
        else -> emptyList()
        }
        val declared = component.setup?.let { compileSetupActions(component, it) }.orEmpty()
        if (declared.isEmpty()) return fixed
        val fixedIds = fixed.map { it.id }.toSet()
        return fixed + declared.filter { it.id !in fixedIds }
    }

    private fun managedComponent(component: ManagedComponent): ManagedComponentContract? {
        if (!InstallerComponentTrustRegistry.isAllowedPackageName(component.componentId, component.packageName)) {
            return null
        }
        val packageName = component.packageName
        return when (component.componentId) {
            LYRICS_COMPONENT_ID -> ManagedComponentContract(
                packageName = packageName,
                order = 1,
                requiredRuntimeServices = listOf(
                    "$packageName/$packageName.MediaListenerService",
                    "$packageName/$packageName.IcarDockAccessibilityService",
                ),
                fixedLaunchComponent = "$packageName/.MainActivity",
            )

            DESKTOP_COMPONENT_ID -> ManagedComponentContract(
                packageName = packageName,
                order = 0,
                requiredRuntimeServices = listOf(
                    "$packageName/$packageName.debug.NavigationDemoAccessibilityService",
                ),
                fixedLaunchComponent = "$packageName/.MainActivity",
            )

            FILE_MANAGER_COMPONENT_ID -> ManagedComponentContract(
                packageName = packageName,
                order = 2,
                requiredRuntimeServices = emptyList(),
                fixedLaunchComponent = if (packageName == FILE_MANAGER_PACKAGE_NAME) {
                    FILE_MANAGER_MAIN_ACTIVITY
                } else {
                    "$packageName/$packageName.activities.MainActivity"
                },
            )

            else -> null
        }
    }

    private fun isAllowedDynamicComponent(component: ManagedComponent): Boolean {
        if (component.componentId.isBlank() || !APP_ID_PATTERN.matches(component.componentId)) return false
        if (!PACKAGE_NAME_PATTERN.matches(component.packageName)) return false
        if (component.componentId in BUILT_IN_COMPONENT_IDS && managedComponent(component) == null) return false
        return validateSetup(component)
    }

    private fun validateSetup(component: ManagedComponent): Boolean {
        val setup = component.setup ?: return true
        if (setup.profileId.isNotBlank() && PROFILE_COMPONENTS[setup.profileId] != component.componentId) {
            return false
        }
        if (setup.actionIds.size != setup.actionIds.distinct().size ||
            setup.actionIds.any { it !in knownActionIds(component) }
        ) {
            return false
        }
        fun belongsToPackage(value: String): Boolean {
            val packageName = value.substringBefore('/', missingDelimiterValue = "")
            return packageName == component.packageName && value.length <= MAX_COMPONENT_NAME_LENGTH &&
                COMPONENT_NAME_PATTERN.matches(value)
        }
        return setup.launchComponent?.let(::belongsToPackage) != false &&
            setup.requiredServices.all(::belongsToPackage) &&
            setup.secureComponents.all { it.targetComponent.let(::belongsToPackage) }
    }

    /**
     * Cloud may select only actions already compiled into the local component
     * contract. The complete fixed plan remains the local default; selectors
     * cannot add an action that the client does not know.
     */
    private fun knownActionIds(component: ManagedComponent): Set<String> = when (component.componentId) {
        DESKTOP_COMPONENT_ID -> setOf(
            "desktop-overlay-v1",
            "desktop-install-packages-v1",
            "desktop-accessibility-master-v1",
            "desktop-accessibility-service-v1",
        )

        LYRICS_COMPONENT_ID -> setOf(
            "lyrics-overlay-v1",
            "lyrics-notification-listener-v1",
            "lyrics-accessibility-service-v1",
        )

        FILE_MANAGER_COMPONENT_ID -> setOf(
            "file-manager-read-permission-v1",
            "file-manager-read-appop-v1",
            "file-manager-write-permission-v1",
            "file-manager-write-appop-v1",
            "file-manager-install-packages-v1",
        )

        else -> emptySet()
    }

    /** Dynamic manifests carry the authoritative order; legacy descriptors set it explicitly above. */
    private fun componentOrder(component: ManagedComponent): Int = component.order

    private fun compileSetupActions(
        component: ManagedComponent,
        setup: AuthorizationSetupDeclaration,
    ): List<AuthorizationAction> {
        val prefix = component.componentId
        val actions = mutableListOf<AuthorizationAction>()
        setup.appOps.sortedBy { it.name }.forEach { operation ->
            actions += AuthorizationAction.EnsureAppOpAllowed(
                id = "$prefix-appop-${operation.name.lowercase()}-v1",
                componentId = component.componentId,
                packageName = component.packageName,
                operation = operation,
            )
        }
        setup.runtimePermissions.sortedBy { it.name }.forEach { permission ->
            actions += AuthorizationAction.EnsureRuntimePermissionGranted(
                id = "$prefix-permission-${permission.name.lowercase()}-v1",
                componentId = component.componentId,
                packageName = component.packageName,
                permission = permission,
            )
        }
        setup.secureSettings.sortedBy { it.name }.forEach { setting ->
            actions += AuthorizationAction.EnsureSecureSettingEnabled(
                id = "$prefix-setting-${setting.name.lowercase()}-v1",
                componentId = component.componentId,
                setting = setting,
            )
        }
        setup.secureComponents.sortedWith(compareBy({ it.setting.name }, { it.targetComponent })).forEach { declaration ->
            actions += AuthorizationAction.AppendSecureComponent(
                id = "$prefix-component-${declaration.setting.name.lowercase()}-${actions.size}-v1",
                componentId = component.componentId,
                setting = declaration.setting,
                targetComponent = declaration.targetComponent,
            )
        }
        return actions
    }

    private data class ManagedComponentContract(
        val packageName: String,
        val order: Int,
        val requiredRuntimeServices: List<String>,
        val fixedLaunchComponent: String?,
    )

    const val LYRICS_COMPONENT_ID = InstallerComponentTrustRegistry.LYRICS_COMPONENT_ID
    const val DESKTOP_COMPONENT_ID = InstallerComponentTrustRegistry.DESKTOP_COMPONENT_ID
    const val FILE_MANAGER_COMPONENT_ID = InstallerComponentTrustRegistry.FILE_MANAGER_COMPONENT_ID
    const val LYRICS_PACKAGE_NAME = InstallerComponentTrustRegistry.LYRICS_PACKAGE_NAME
    const val DESKTOP_PACKAGE_NAME = InstallerComponentTrustRegistry.DESKTOP_PACKAGE_NAME
    const val FILE_MANAGER_PACKAGE_NAME = InstallerComponentTrustRegistry.FILE_MANAGER_PACKAGE_NAME
    const val DESKTOP_MAIN_ACTIVITY = "com.tcrrry.desktop/.MainActivity"
    const val LYRICS_MAIN_ACTIVITY = "com.tcrrry.desktoplyrics/.MainActivity"
    const val FILE_MANAGER_MAIN_ACTIVITY =
        "org.fossify.filemanager.debug/org.fossify.filemanager.activities.MainActivity"
    const val LYRICS_NOTIFICATION_LISTENER =
        "com.tcrrry.desktoplyrics/com.tcrrry.desktoplyrics.MediaListenerService"
    const val LYRICS_ACCESSIBILITY_SERVICE =
        "com.tcrrry.desktoplyrics/com.tcrrry.desktoplyrics.IcarDockAccessibilityService"
    const val DESKTOP_ACCESSIBILITY_SERVICE =
        "com.tcrrry.desktop/com.tcrrry.desktop.debug.NavigationDemoAccessibilityService"

    private val BUILT_IN_COMPONENT_IDS = setOf(
        DESKTOP_COMPONENT_ID,
        LYRICS_COMPONENT_ID,
        FILE_MANAGER_COMPONENT_ID,
    )

    private val PROFILE_COMPONENTS = mapOf(
        "desktop-default" to DESKTOP_COMPONENT_ID,
        "lyrics-default" to LYRICS_COMPONENT_ID,
        "file-manager-default" to FILE_MANAGER_COMPONENT_ID,
    )

    private val APP_ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    private val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val COMPONENT_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*/[A-Za-z0-9_.$]+$")
    private const val MAX_COMPONENT_NAME_LENGTH = 256
}

/** Rejects an authorization plan unless every required APK capability is declared. */
object AuthorizationDeclarationValidator {
    fun validate(
        plan: AuthorizationPlan,
        artifacts: List<InstallableArtifact>,
    ): DeviceActionFailure? {
        val byComponent = artifacts.associateBy { it.manifest.componentId }
        if (byComponent.size != artifacts.size || byComponent.keys != plan.components.map { it.componentId }.toSet()) {
            return DeviceActionFailure("authorization_artifacts_mismatch", retryable = false)
        }
        return validateDeclarations(
            plan = plan,
            declarationsByComponent = byComponent.mapValues { (_, artifact) -> artifact.declarations },
        )
    }

    fun validateDeclarations(
        plan: AuthorizationPlan,
        declarationsByComponent: Map<String, ApkDeclarationMetadata?>,
    ): DeviceActionFailure? {
        if (declarationsByComponent.keys != plan.components.map { it.componentId }.toSet()) {
            return DeviceActionFailure("authorization_artifacts_mismatch", retryable = false)
        }
        plan.actions.forEach { action ->
            val declarations = declarationsByComponent[action.componentId]
                ?: return DeviceActionFailure(
                    "authorization_capability_metadata_missing",
                    action.componentId,
                    retryable = false,
                )
            when (action) {
                is AuthorizationAction.EnsureAppOpAllowed -> {
                    val permission = when (action.operation) {
                        ManagedAppOp.SYSTEM_ALERT_WINDOW -> PERMISSION_SYSTEM_ALERT_WINDOW
                        ManagedAppOp.REQUEST_INSTALL_PACKAGES -> PERMISSION_REQUEST_INSTALL_PACKAGES
                        ManagedAppOp.READ_EXTERNAL_STORAGE -> PERMISSION_READ_EXTERNAL_STORAGE
                        ManagedAppOp.WRITE_EXTERNAL_STORAGE -> PERMISSION_WRITE_EXTERNAL_STORAGE
                    }
                    if (permission !in declarations.requestedPermissions) {
                        return DeviceActionFailure(
                            "authorization_permission_not_declared",
                            action.componentId,
                            retryable = false,
                        )
                    }
                }

                is AuthorizationAction.EnsureRuntimePermissionGranted -> {
                    if (action.permission.wireName !in declarations.requestedPermissions) {
                        return DeviceActionFailure(
                            "authorization_permission_not_declared",
                            action.componentId,
                            retryable = false,
                        )
                    }
                }

                is AuthorizationAction.AppendSecureComponent -> {
                    val requiredPermission = when (action.setting) {
                        ManagedSecureComponentList.ENABLED_NOTIFICATION_LISTENERS ->
                            PERMISSION_BIND_NOTIFICATION_LISTENER_SERVICE

                        ManagedSecureComponentList.ENABLED_ACCESSIBILITY_SERVICES ->
                            PERMISSION_BIND_ACCESSIBILITY_SERVICE
                    }
                    val declared = declarations.services.any { service ->
                        service.componentName == action.targetComponent && service.permission == requiredPermission
                    }
                    if (!declared) {
                        return DeviceActionFailure(
                            "authorization_service_not_declared",
                            action.componentId,
                            retryable = false,
                        )
                    }
                }

                is AuthorizationAction.EnsureSecureSettingEnabled -> Unit
            }
        }
        return null
    }

    private const val PERMISSION_SYSTEM_ALERT_WINDOW = "android.permission.SYSTEM_ALERT_WINDOW"
    private const val PERMISSION_REQUEST_INSTALL_PACKAGES = "android.permission.REQUEST_INSTALL_PACKAGES"
    private const val PERMISSION_READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
    private const val PERMISSION_WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE"
    private const val PERMISSION_BIND_NOTIFICATION_LISTENER_SERVICE =
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
    private const val PERMISSION_BIND_ACCESSIBILITY_SERVICE = "android.permission.BIND_ACCESSIBILITY_SERVICE"
}
