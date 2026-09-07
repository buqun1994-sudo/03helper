package com.ninepointnine.helper.domain.device

import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.KnownApplicationPackages
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

    /** Optional maintenance port exposed by a connection that supports maintenance actions. */
    val maintenanceGateway: MaintenanceCommandGateway?
        get() = commandGateway as? MaintenanceCommandGateway
}

interface AdbCommandGateway {
    /** Installs exactly one verified business batch with its frozen intent. */
    suspend fun installBatch(
        artifacts: List<InstallableArtifact>,
        strategy: InstallationStrategy,
    ): DeviceInstallResult

    /** Runs the one versioned authorization plan for the selected components. */
    suspend fun runShortcut(
        shortcut: DeviceShortcut,
        selectedComponentIds: Set<String>,
        authorizationPlan: AuthorizationPlan,
    ): DeviceShortcutResult
}

/** Fixed, non-shell maintenance operations available after a confirmed lease. */
interface MaintenanceCommandGateway {
    /** Repairs authorization using the verified install identity and declarations. */
    suspend fun repairAuthorization(
        manifests: List<ArtifactManifest>,
        declarationsByComponent: Map<String, ApkDeclarationMetadata>,
    ): MaintenanceDeviceResult

    suspend fun inspectManagedApplications(
        components: List<ManagedComponent>,
    ): ManagedApplicationsResult

    /** Reads the device package inventory once for the supplied components. */
    suspend fun inspectInstalledApplicationInventory(
        components: List<ManagedComponent>,
    ): ManagedApplicationsResult

    /** Reads every third-party application installed for the active car user. */
    suspend fun inspectAllInstalledApplications(): ManagedApplicationsResult =
        inspectManagedApplications(emptyList())

    /** Reads one icon after the metadata inventory has already been published. */
    suspend fun inspectInstalledApplicationIcon(packageName: String): InstalledApplicationIconResult =
        InstalledApplicationIconResult.Failed(
            DeviceActionFailure("maintenance_app_icon_unavailable", retryable = false),
        )

    /** Scans one installed APK declaration set, applies fixed grants, and reads it back. */
    suspend fun inspectApplicationAuthorization(
        component: ManagedComponent,
    ): ApplicationAuthorizationResult = ApplicationAuthorizationResult.Failed(
        DeviceActionFailure("maintenance_authorization_unavailable", component.componentId, retryable = false),
    )

    /** Scans one installed APK declaration set, applies supported grants, and reads it back. */
    suspend fun authorizeApplication(
        component: ManagedComponent,
    ): ApplicationAuthorizationResult = ApplicationAuthorizationResult.Failed(
        DeviceActionFailure("maintenance_authorization_unavailable", component.componentId, retryable = false),
    )

    /** Reports the complete per-requirement snapshot after each authorization step. */
    suspend fun authorizeApplication(
        component: ManagedComponent,
        onProgress: (ApplicationAuthorizationResultValue) -> Unit,
    ): ApplicationAuthorizationResult = authorizeApplication(component).also { result ->
        if (result is ApplicationAuthorizationResult.Completed) onProgress(result.value)
    }

    /** Launches a verified component using its typed catalog identity and setup. */
    suspend fun launchManagedComponent(component: ManagedComponent): MaintenanceDeviceResult

    /** Probes authorization against the inventory read by the same maintenance action. */
    suspend fun inspectComponentAuthorization(
        components: List<ManagedComponent>,
        installedApplications: List<ManagedApplicationProbe>,
    ): MaintenanceAuthorizationResult

    /**
     * Inspects authorization using declarations read from the exact APKs when
     * available. The two-argument form remains the compatibility entry point
     * for adapters that do not have declaration evidence yet.
     */
    suspend fun inspectComponentAuthorization(
        components: List<ManagedComponent>,
        installedApplications: List<ManagedApplicationProbe>,
        declarationsByComponent: Map<String, ApkDeclarationMetadata>,
    ): MaintenanceAuthorizationResult = inspectComponentAuthorization(components, installedApplications)

    /** Fixed application operation selected by the maintenance UI. */
    suspend fun performApplicationAction(
        component: ManagedComponent,
        actionId: MaintenanceApplicationActionId,
    ): MaintenanceDeviceResult

    suspend fun inspectManagedApplicationDetails(
        component: ManagedComponent,
    ): ManagedApplicationDetailsProbeResult
}

sealed interface MaintenanceDeviceResult {
    data class Completed(val resultCode: String = "completed") : MaintenanceDeviceResult

    data class Failed(val failure: DeviceActionFailure) : MaintenanceDeviceResult
}

data class ManagedApplicationProbe(
    val componentId: String,
    val packageName: String,
    val installed: Boolean,
    val displayName: String? = null,
    val versionLabel: String? = null,
    val versionCode: Long? = null,
    val fileSizeBytes: Long? = null,
    val installTimeEpochMillis: Long? = null,
    val updateTimeEpochMillis: Long? = null,
    val filePath: String? = null,
    val uid: Int? = null,
    /** Optional PNG encoded by the on-car 03desktop catalog bridge. */
    val iconBase64: String? = null,
    /** Explicit MAIN/LAUNCHER component resolved by PackageManager. */
    val launchComponent: String? = null,
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
    val displayName: String? = null,
    val iconBase64: String? = null,
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

sealed interface InstalledApplicationIconResult {
    data class Completed(
        val packageName: String,
        val iconBase64: String?,
    ) : InstalledApplicationIconResult

    data class Failed(val failure: DeviceActionFailure) : InstalledApplicationIconResult
}

data class ApplicationAuthorizationRequirement(
    val permission: String,
    val grantedBefore: Boolean?,
    val grantedAfter: Boolean?,
    val reasonCode: String? = null,
    val kind: ApplicationAuthorizationRequirementKind = ApplicationAuthorizationRequirementKind.RUNTIME_PERMISSION,
    /** False for declarations that are informational or cannot be changed by ADB shell. */
    val automaticallyActionable: Boolean = true,
    /** True after this item has completed its one authorization attempt. */
    val authorizationAttempted: Boolean = false,
)

enum class ApplicationAuthorizationRequirementKind {
    RUNTIME_PERMISSION,
    APP_OP,
    ACCESSIBILITY_SERVICE,
    NOTIFICATION_LISTENER_SERVICE,
    DECLARED_PERMISSION,
}

data class ApplicationAuthorizationResultValue(
    val componentId: String,
    val packageName: String,
    val requirements: List<ApplicationAuthorizationRequirement>,
)

sealed interface ApplicationAuthorizationResult {
    data class Completed(val value: ApplicationAuthorizationResultValue) : ApplicationAuthorizationResult
    data class Failed(val failure: DeviceActionFailure) : ApplicationAuthorizationResult
}

/** Capabilities read from an APK Manifest before any device-side write. */
data class ApkDeclarationMetadata(
    val requestedPermissions: Set<String> = emptySet(),
    /** Explicit protection metadata; null keeps compatibility with older fixtures. */
    val runtimeGrantPermissions: Set<String>? = null,
    val services: Set<ApkServiceDeclaration> = emptySet(),
)

data class ApkServiceDeclaration(
    val componentName: String,
    val permission: String?,
)

data class DeclaredApplicationAuthorizationRequirement(
    val declaration: String,
    val kind: ApplicationAuthorizationRequirementKind,
    val actions: List<DeclaredApplicationAuthorizationAction>,
    val automaticallyActionable: Boolean = true,
)

sealed interface DeclaredApplicationAuthorizationAction {
    data class InspectPermission(val permission: String) : DeclaredApplicationAuthorizationAction

    data class GrantRuntimePermission(val permission: String) : DeclaredApplicationAuthorizationAction

    data class AllowAppOp(val operation: ManagedAppOp) : DeclaredApplicationAuthorizationAction

    data class EnableSecureFlag(val setting: ManagedSecureFlag) : DeclaredApplicationAuthorizationAction

    data class AppendSecureComponent(
        val setting: ManagedSecureComponentList,
        val componentName: String,
    ) : DeclaredApplicationAuthorizationAction
}

/** Compiles installed APK declarations into the finite operations supported by maintenance. */
object DeclaredApplicationAuthorizationPlanFactory {
    fun create(
        packageName: String,
        declarations: ApkDeclarationMetadata,
    ): List<DeclaredApplicationAuthorizationRequirement> {
        val permissionRequirements = declarations.requestedPermissions
            .filter(PERMISSION_PATTERN::matches)
            .distinct()
            .sorted()
            .map { permission ->
                val appOp = APP_OP_BY_PERMISSION[permission]
                val runtimeGrant = permission in runtimeGrantPermissions(declarations)
                val actions = buildList {
                    if (permission in RUNTIME_PERMISSION_WITH_APP_OP && runtimeGrant) {
                        add(DeclaredApplicationAuthorizationAction.GrantRuntimePermission(permission))
                    }
                    if (appOp != null) {
                        add(DeclaredApplicationAuthorizationAction.AllowAppOp(appOp))
                    } else if (runtimeGrant) {
                        add(DeclaredApplicationAuthorizationAction.GrantRuntimePermission(permission))
                    } else {
                        add(DeclaredApplicationAuthorizationAction.InspectPermission(permission))
                    }
                }
                DeclaredApplicationAuthorizationRequirement(
                    declaration = permission,
                    kind = if (appOp == null && runtimeGrant) {
                        ApplicationAuthorizationRequirementKind.RUNTIME_PERMISSION
                    } else if (appOp != null) {
                        ApplicationAuthorizationRequirementKind.APP_OP
                    } else {
                        ApplicationAuthorizationRequirementKind.DECLARED_PERMISSION
                    },
                    actions = actions,
                    automaticallyActionable = appOp != null || runtimeGrant,
                )
            }
        val serviceRequirements = declarations.services
            .filter { serviceBelongsToPackage(it.componentName, packageName) }
            .sortedBy { it.componentName }
            .mapNotNull { service ->
                when (service.permission) {
                    BIND_ACCESSIBILITY_SERVICE -> DeclaredApplicationAuthorizationRequirement(
                        declaration = service.componentName,
                        kind = ApplicationAuthorizationRequirementKind.ACCESSIBILITY_SERVICE,
                        actions = listOf(
                            DeclaredApplicationAuthorizationAction.AppendSecureComponent(
                                ManagedSecureComponentList.ENABLED_ACCESSIBILITY_SERVICES,
                                service.componentName,
                            ),
                            DeclaredApplicationAuthorizationAction.EnableSecureFlag(
                                ManagedSecureFlag.ACCESSIBILITY_ENABLED,
                            ),
                        ),
                    )

                    BIND_NOTIFICATION_LISTENER_SERVICE -> DeclaredApplicationAuthorizationRequirement(
                        declaration = service.componentName,
                        kind = ApplicationAuthorizationRequirementKind.NOTIFICATION_LISTENER_SERVICE,
                        actions = listOf(
                            DeclaredApplicationAuthorizationAction.AppendSecureComponent(
                                ManagedSecureComponentList.ENABLED_NOTIFICATION_LISTENERS,
                                service.componentName,
                            ),
                        ),
                    )

                    else -> null
                }
            }
        return permissionRequirements + serviceRequirements
    }

    private fun serviceBelongsToPackage(componentName: String, packageName: String): Boolean =
        componentName.substringBefore('/', missingDelimiterValue = "") == packageName &&
            COMPONENT_PATTERN.matches(componentName)

    private fun runtimeGrantPermissions(declarations: ApkDeclarationMetadata): Set<String> =
        declarations.runtimeGrantPermissions ?: declarations.requestedPermissions.filter { permission ->
            permission in DEFAULT_DANGEROUS_PERMISSIONS
        }.toSet()

    private val APP_OP_BY_PERMISSION = mapOf(
        "android.permission.SYSTEM_ALERT_WINDOW" to ManagedAppOp.SYSTEM_ALERT_WINDOW,
        "android.permission.REQUEST_INSTALL_PACKAGES" to ManagedAppOp.REQUEST_INSTALL_PACKAGES,
        "android.permission.READ_EXTERNAL_STORAGE" to ManagedAppOp.READ_EXTERNAL_STORAGE,
        "android.permission.WRITE_EXTERNAL_STORAGE" to ManagedAppOp.WRITE_EXTERNAL_STORAGE,
        "android.permission.PACKAGE_USAGE_STATS" to ManagedAppOp.GET_USAGE_STATS,
        "android.permission.WRITE_SETTINGS" to ManagedAppOp.WRITE_SETTINGS,
    )
    private val RUNTIME_PERMISSION_WITH_APP_OP = setOf(
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
    )
    private val DEFAULT_DANGEROUS_PERMISSIONS = setOf(
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR",
        "android.permission.CAMERA",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.GET_ACCOUNTS",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.RECORD_AUDIO",
        "android.permission.READ_PHONE_STATE",
        "android.permission.READ_PHONE_NUMBERS",
        "android.permission.CALL_PHONE",
        "android.permission.ANSWER_PHONE_CALLS",
        "android.permission.ADD_VOICEMAIL",
        "android.permission.USE_SIP",
        "android.permission.PROCESS_OUTGOING_CALLS",
        "android.permission.BODY_SENSORS",
        "android.permission.BODY_SENSORS_BACKGROUND",
        "android.permission.SEND_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_WAP_PUSH",
        "android.permission.RECEIVE_MMS",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.ACTIVITY_RECOGNITION",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
    )
    private val PERMISSION_PATTERN = Regex(
        "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$",
    )
    private val COMPONENT_PATTERN = Regex(
        "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*/[A-Za-z0-9_.$]+$",
    )
    private const val BIND_ACCESSIBILITY_SERVICE = "android.permission.BIND_ACCESSIBILITY_SERVICE"
    private const val BIND_NOTIFICATION_LISTENER_SERVICE =
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
}

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
        /** Components whose fresh write or trusted reuse operation was accepted. */
        val operationConfirmedComponentIds: Set<String> = emptySet(),
    ) : DeviceInstallResult

    data class Failed(
        val failure: DeviceActionFailure,
        /** Confirmed writes that happened before a later command failed. */
        val writeConfirmedComponentIds: Set<String> = emptySet(),
        /** Fresh writes or trusted reuse operations accepted before failure. */
        val operationConfirmedComponentIds: Set<String> = emptySet(),
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
        /** Fresh writes or trusted reuse operations accepted before readback stopped. */
        val operationConfirmedComponentIds: Set<String> = emptySet(),
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

/** Confidence of the independent readback after the typed authorization command. */
enum class DeviceAuthorizationConfirmation {
    CONFIRMED,
    UNKNOWN,
}

sealed interface DeviceShortcutResult {
    data class Completed(
        val configuredComponentIds: Set<String>,
        val skippedComponentIds: Set<String>,
        val authorizationEvidence: List<AuthorizationActionEvidence>,
        val availabilityEvidence: List<ManagedApplicationAvailabilityEvidence>,
        val authorizationConfirmation: DeviceAuthorizationConfirmation = DeviceAuthorizationConfirmation.CONFIRMED,
    ) : DeviceShortcutResult

    data class Failed(
        val stage: DeviceShortcutFailureStage,
        val failure: DeviceActionFailure,
        /** Structured receipts emitted before the command stopped. */
        val configuredComponentIds: Set<String> = emptySet(),
        val skippedComponentIds: Set<String> = emptySet(),
        val authorizationEvidence: List<AuthorizationActionEvidence> = emptyList(),
        val availabilityEvidence: List<ManagedApplicationAvailabilityEvidence> = emptyList(),
        val authorizationConfirmation: DeviceAuthorizationConfirmation = DeviceAuthorizationConfirmation.UNKNOWN,
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
    /** APK-derived service identities used for this one authorization transaction. */
    val runtimeServiceOverrides: Map<String, List<String>> = emptyMap(),
)

data class ManagedComponent(
    val componentId: String,
    val packageName: String,
    val setup: AuthorizationSetupDeclaration? = null,
    val order: Int = Int.MAX_VALUE,
    /** Runtime-resolved launcher identity for applications outside the catalog. */
    val launchComponent: String? = null,
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
    GET_USAGE_STATS("GET_USAGE_STATS"),
    WRITE_SETTINGS("WRITE_SETTINGS"),
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
        requireDesktop: Boolean = false,
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
        requireDesktop: Boolean = false,
        declaredServicesByComponent: Map<String, Set<ApkServiceDeclaration>> = emptyMap(),
    ): AuthorizationPlanBuildResult = createComponents(
        components,
        requireDesktop = requireDesktop,
        declaredServicesByComponent = declaredServicesByComponent,
    )

    /**
     * Builds the same typed action set for a read-only probe.
     */
    fun createForInspection(components: List<ManagedComponent>): AuthorizationPlanBuildResult =
        createComponents(components, requireDesktop = false)

    /**
     * Validates one catalog component before it is combined with the selected
     * installation batch. The catalog layer uses this to isolate a malformed
     * optional setup instead of letting it reject unrelated applications.
     */
    fun validateComponent(component: ManagedComponent): Boolean = isAllowedDynamicComponent(component)

    /** Runtime verification is a local capability, independent of catalog admission. */
    fun requiresLaunchVerification(componentId: String, packageName: String): Boolean =
        componentId == DESKTOP_COMPONENT_ID && KnownApplicationPackages.matchesComponent(componentId, packageName)

    /** Returns the built-in component descriptors used by maintenance and fallback inspection. */
    fun allManagedComponents(): List<ManagedComponent> = listOf(
        ManagedComponent(DESKTOP_COMPONENT_ID, DESKTOP_PACKAGE_NAME, order = 0),
        ManagedComponent(LYRICS_COMPONENT_ID, LYRICS_PACKAGE_NAME, order = 1),
        ManagedComponent(FILE_MANAGER_COMPONENT_ID, FILE_MANAGER_PACKAGE_NAME, order = 2),
        ManagedComponent(
            KnownApplicationPackages.CAST_COMPONENT_ID,
            KnownApplicationPackages.CAST_PACKAGE_NAME,
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
        requireDesktop: Boolean = false,
        declaredServicesByComponent: Map<String, Set<ApkServiceDeclaration>> = emptyMap(),
    ): AuthorizationPlanBuildResult {
        if (components.isEmpty()) return AuthorizationPlanBuildResult.Rejected("authorization_artifacts_missing")
        if (components.map { it.componentId }.toSet().size != components.size) {
            return AuthorizationPlanBuildResult.Rejected("authorization_component_duplicate")
        }
        val componentIds = components.mapTo(linkedSetOf()) { it.componentId }
        if (declaredServicesByComponent.keys.any { it !in componentIds } ||
            declaredServicesByComponent.any { (componentId, services) ->
                val packageName = components.firstOrNull { it.componentId == componentId }?.packageName
                packageName == null || services.any { !serviceBelongsToPackage(it.componentName, packageName) }
            }
        ) {
            return AuthorizationPlanBuildResult.Rejected("authorization_service_component_mismatch")
        }
        if (components.any { component -> !isAllowedDynamicComponent(component) }) {
            return AuthorizationPlanBuildResult.Rejected("authorization_component_unapproved")
        }
        if (declaredServicesByComponent.any { (componentId, services) ->
                val requiredComponent = components.firstOrNull { it.componentId == componentId } ?: return@any true
                val accessibilityCount = services.count {
                    it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE" &&
                        serviceBelongsToPackage(it.componentName, requiredComponent.packageName)
                }
                val notificationCount = services.count {
                    it.permission == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" &&
                        serviceBelongsToPackage(it.componentName, requiredComponent.packageName)
                }
                when (componentId.takeIf { managedComponent(requiredComponent) != null }) {
                    DESKTOP_COMPONENT_ID -> accessibilityCount > 1
                    LYRICS_COMPONENT_ID -> notificationCount > 1 || accessibilityCount > 1
                    else -> false
                }
            }) {
            return AuthorizationPlanBuildResult.Rejected("authorization_service_ambiguous")
        }
        if (requireDesktop && components.none { requiresLaunchVerification(it.componentId, it.packageName) }) {
            return AuthorizationPlanBuildResult.Rejected("authorization_desktop_missing")
        }
        val orderedComponents = components.sortedWith(compareBy<ManagedComponent> { componentOrder(it) }.thenBy { it.componentId })
        return AuthorizationPlanBuildResult.Ready(
            AuthorizationPlan(
                version = CURRENT_VERSION,
                components = orderedComponents,
                actions = orderedComponents.flatMap { component ->
                    actionsFor(component, declaredServicesByComponent[component.componentId].orEmpty())
                },
                runtimeServiceOverrides = orderedComponents.mapNotNull { component ->
                    val contract = managedComponent(component) ?: return@mapNotNull null
                    val declared = declaredServicesByComponent[component.componentId].orEmpty()
                    if (declared.isEmpty()) return@mapNotNull null
                    component.componentId to runtimeServicesFor(component, contract, declared)
                }.toMap().filterValues { it.isNotEmpty() },
            ),
        )
    }

    fun validate(plan: AuthorizationPlan): Boolean {
        if (plan.version != CURRENT_VERSION || plan.components.isEmpty()) return false
        if (plan.components.map { it.componentId }.toSet().size != plan.components.size) return false
        if (plan.components.any { !isAllowedDynamicComponent(it) }) return false
        val expectedComponents = plan.components.sortedWith(compareBy<ManagedComponent> { componentOrder(it) }.thenBy { it.componentId })
        if (plan.components != expectedComponents) return false
        if (plan.runtimeServiceOverrides.any { (componentId, services) ->
                val component = plan.components.firstOrNull { it.componentId == componentId } ?: return@any true
                services.isEmpty() || services.distinct().size != services.size ||
                    services.any { service -> !serviceBelongsToPackage(service, component.packageName) }
            }) return false
        return plan.actions == expectedComponents.flatMap { component ->
            actionsFor(
                component,
                runtimeServiceOverride = plan.runtimeServiceOverrides[component.componentId],
            )
        }
    }

    fun requiredRuntimeService(component: ManagedComponent): String? =
        requiredRuntimeServices(component).firstOrNull()

    /** Runtime verification must consume the same service frozen into the plan. */
    fun requiredRuntimeService(plan: AuthorizationPlan, component: ManagedComponent): String? =
        plan.runtimeServiceOverrides[component.componentId]
            ?.singleOrNull()
            ?: requiredRuntimeService(component)

    fun requiredRuntimeServices(component: ManagedComponent): List<String> =
        component.setup?.requiredServices?.toList()?.sorted()
            ?.takeIf { it.isNotEmpty() }
            ?: managedComponent(component)?.requiredRuntimeServices.orEmpty()

    fun fixedLaunchComponent(component: ManagedComponent): String? =
        component.launchComponent ?: component.setup?.launchComponent ?: managedComponent(component)?.fixedLaunchComponent

    private fun actionsFor(
        component: ManagedComponent,
        declaredServices: Set<ApkServiceDeclaration> = emptySet(),
        runtimeServiceOverride: List<String>? = null,
    ): List<AuthorizationAction> {
        val contract = managedComponent(component)
        if (contract == null) return component.setup?.let { compileSetupActions(component, it) }.orEmpty()
        val runtimeServices = runtimeServiceOverride ?: runtimeServicesFor(component, contract, declaredServices)
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
                targetComponent = checkNotNull(runtimeServices.firstOrNull()),
            ),
            AuthorizationAction.AppendSecureComponent(
                id = "lyrics-accessibility-service-v1",
                componentId = component.componentId,
                setting = ManagedSecureComponentList.ENABLED_ACCESSIBILITY_SERVICES,
                targetComponent = checkNotNull(runtimeServices.getOrNull(1)),
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
                targetComponent = checkNotNull(runtimeServices.singleOrNull()),
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

    /** Prefer the APK's actual declared service names; suffix compatibility is the fallback. */
    private fun runtimeServicesFor(
        component: ManagedComponent,
        contract: ManagedComponentContract,
        declaredServices: Set<ApkServiceDeclaration>,
    ): List<String> {
        if (declaredServices.isEmpty()) return contract.requiredRuntimeServices
        val accessibility = declaredServices
            .filter {
                it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE" &&
                    serviceBelongsToPackage(it.componentName, component.packageName)
            }
            .map { it.componentName }
            .sorted()
        val notification = declaredServices
            .filter {
                it.permission == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" &&
                    serviceBelongsToPackage(it.componentName, component.packageName)
            }
            .map { it.componentName }
            .sorted()
        return when (component.componentId) {
            DESKTOP_COMPONENT_ID -> accessibility.take(1).ifEmpty { contract.requiredRuntimeServices }
            LYRICS_COMPONENT_ID -> buildList {
                notification.firstOrNull()?.let(::add)
                accessibility.firstOrNull()?.let(::add)
            }.takeIf { it.size == 2 } ?: contract.requiredRuntimeServices
            else -> contract.requiredRuntimeServices
        }
    }

    private fun managedComponent(component: ManagedComponent): ManagedComponentContract? {
        if (!KnownApplicationPackages.matchesComponent(component.componentId, component.packageName)) {
            return null
        }
        val packageName = component.packageName
        return when (component.componentId) {
            LYRICS_COMPONENT_ID -> ManagedComponentContract(
                packageName = packageName,
                order = 1,
                requiredRuntimeServices = listOf(
                    "$packageName/${runtimeNamespace(packageName)}.MediaListenerService",
                    "$packageName/${runtimeNamespace(packageName)}.IcarDockAccessibilityService",
                ),
                fixedLaunchComponent = "$packageName/.MainActivity",
            )

            DESKTOP_COMPONENT_ID -> ManagedComponentContract(
                packageName = packageName,
                order = 0,
                requiredRuntimeServices = listOf(
                    "$packageName/${runtimeNamespace(packageName)}.debug.NavigationDemoAccessibilityService",
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
        return validateSetup(component)
    }

    /** Gradle applicationId suffixes do not change the Kotlin namespace. */
    private fun runtimeNamespace(packageName: String): String = packageName
        .removeSuffix(".test")
        .removeSuffix(".staging")
        .removeSuffix(".release")

    private fun serviceBelongsToPackage(service: String, packageName: String): Boolean {
        val owner = service.substringBefore('/', missingDelimiterValue = "")
        return owner == packageName &&
            service.length <= MAX_COMPONENT_NAME_LENGTH &&
            COMPONENT_NAME_PATTERN.matches(service)
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

    /** Dynamic manifests carry the authoritative order; built-in descriptors set their fixed order above. */
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

    const val LYRICS_COMPONENT_ID = KnownApplicationPackages.LYRICS_COMPONENT_ID
    const val DESKTOP_COMPONENT_ID = KnownApplicationPackages.DESKTOP_COMPONENT_ID
    const val FILE_MANAGER_COMPONENT_ID = KnownApplicationPackages.FILE_MANAGER_COMPONENT_ID
    const val LYRICS_PACKAGE_NAME = KnownApplicationPackages.LYRICS_PACKAGE_NAME
    const val DESKTOP_PACKAGE_NAME = KnownApplicationPackages.DESKTOP_PACKAGE_NAME
    const val FILE_MANAGER_PACKAGE_NAME = KnownApplicationPackages.FILE_MANAGER_PACKAGE_NAME
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
                        ManagedAppOp.GET_USAGE_STATS -> PERMISSION_PACKAGE_USAGE_STATS
                        ManagedAppOp.WRITE_SETTINGS -> PERMISSION_WRITE_SETTINGS
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
                    val componentPackage = plan.components
                        .firstOrNull { it.componentId == action.componentId }
                        ?.packageName
                    if (componentPackage == null ||
                        action.targetComponent.substringBefore('/', missingDelimiterValue = "") != componentPackage
                    ) {
                        return DeviceActionFailure(
                            "authorization_service_component_mismatch",
                            action.componentId,
                            retryable = false,
                        )
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
    private const val PERMISSION_PACKAGE_USAGE_STATS = "android.permission.PACKAGE_USAGE_STATS"
    private const val PERMISSION_WRITE_SETTINGS = "android.permission.WRITE_SETTINGS"
    private const val PERMISSION_BIND_NOTIFICATION_LISTENER_SERVICE =
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
    private const val PERMISSION_BIND_ACCESSIBILITY_SERVICE = "android.permission.BIND_ACCESSIBILITY_SERVICE"
}
