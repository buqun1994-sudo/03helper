package com.tcrrry.helper.domain.device

import com.tcrrry.helper.domain.artifact.ArtifactManifest
import com.tcrrry.helper.domain.artifact.ArtifactVersion
import java.io.File

/**
 * The only device-action port exposed after a connection lease has completed
 * its fixed identity handshake. It deliberately has no arbitrary shell API.
 */
interface DeviceActionConnectionLease : DeviceConnectionLease {
    val commandGateway: AdbCommandGateway
}

interface AdbCommandGateway {
    suspend fun install(artifacts: List<InstallableArtifact>): DeviceInstallResult

    /**
     * Runs the one fixed post-install command for the selected components.
     * The caller supplies validated component ids, never shell text.
     */
    suspend fun runShortcut(
        shortcut: DeviceShortcut,
        selectedComponentIds: Set<String>,
    ): DeviceShortcutResult
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
    val apkFile: File,
    val declarations: ApkDeclarationMetadata? = null,
)

sealed interface DeviceInstallResult {
    data class Installed(val evidence: List<InstalledArtifactEvidence>) : DeviceInstallResult

    data class Failed(val failure: DeviceActionFailure) : DeviceInstallResult
}

data class InstalledArtifactEvidence(
    val componentId: String,
    val packageName: String,
    val version: ArtifactVersion,
    val apkSizeBytes: Long,
    val apkSha256: String,
    val certificateSha256: String,
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

/** Versioned, fixed action schema. Catalog data can select components but cannot add actions. */
data class AuthorizationPlan(
    val version: Int,
    val components: List<ManagedComponent>,
    val actions: List<AuthorizationAction>,
)

data class ManagedComponent(
    val componentId: String,
    val packageName: String,
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

/**
 * The release manifest supplies artifact identity, not system commands. This
 * factory is the sole mapping from a verified component to its approved setup.
 */
object AuthorizationPlanFactory {
    const val CURRENT_VERSION = 1

    fun create(artifacts: List<InstallableArtifact>): AuthorizationPlanBuildResult =
        createComponents(
            artifacts.map { ManagedComponent(it.manifest.componentId, it.manifest.packageName) },
        )

    fun createForManifests(manifests: List<ArtifactManifest>): AuthorizationPlanBuildResult =
        createComponents(
            manifests.map { ManagedComponent(it.componentId, it.packageName) },
        )

    fun createForComponents(components: List<ManagedComponent>): AuthorizationPlanBuildResult =
        createComponents(components)

    /** Stable registry order for the fixed command; catalog data cannot add components here. */
    fun allManagedComponents(): List<ManagedComponent> = listOf(
        ManagedComponent(DESKTOP_COMPONENT_ID, DESKTOP_PACKAGE_NAME),
        ManagedComponent(LYRICS_COMPONENT_ID, LYRICS_PACKAGE_NAME),
        ManagedComponent(FILE_MANAGER_COMPONENT_ID, FILE_MANAGER_PACKAGE_NAME),
    )

    fun validateEvidence(
        plan: AuthorizationPlan,
        evidence: List<AuthorizationActionEvidence>,
    ): Boolean {
        if (evidence.size != plan.actions.size || evidence.map { it.actionId }.toSet().size != evidence.size) {
            return false
        }
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

    private fun createComponents(components: List<ManagedComponent>): AuthorizationPlanBuildResult {
        if (components.isEmpty()) return AuthorizationPlanBuildResult.Rejected("authorization_artifacts_missing")
        if (components.map { it.componentId }.toSet().size != components.size) {
            return AuthorizationPlanBuildResult.Rejected("authorization_component_duplicate")
        }
        if (components.any { component -> managedComponent(component) == null }) {
            return AuthorizationPlanBuildResult.Rejected("authorization_component_unapproved")
        }
        if (components.none { it.componentId == DESKTOP_COMPONENT_ID }) {
            return AuthorizationPlanBuildResult.Rejected("authorization_desktop_missing")
        }
        val orderedComponents = components.sortedBy { managedComponent(checkNotNull(it))?.order }
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
        if (plan.components.any { managedComponent(it) == null }) return false
        val expectedComponents = plan.components.sortedBy { managedComponent(checkNotNull(it))?.order }
        return plan.components == expectedComponents &&
            plan.actions == expectedComponents.flatMap(::actionsFor)
    }

    fun requiredRuntimeService(component: ManagedComponent): String? =
        requiredRuntimeServices(component).firstOrNull()

    fun requiredRuntimeServices(component: ManagedComponent): List<String> =
        managedComponent(component)?.requiredRuntimeServices.orEmpty()

    fun fixedLaunchComponent(component: ManagedComponent): String? =
        managedComponent(component)?.fixedLaunchComponent

    private fun actionsFor(component: ManagedComponent): List<AuthorizationAction> {
        val contract = managedComponent(component) ?: return emptyList()
        return when (component.componentId) {
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
    }

    private fun managedComponent(component: ManagedComponent): ManagedComponentContract? =
        when (component.componentId) {
            LYRICS_COMPONENT_ID -> ManagedComponentContract(
                packageName = LYRICS_PACKAGE_NAME,
                order = 1,
                requiredRuntimeServices = listOf(LYRICS_NOTIFICATION_LISTENER, LYRICS_ACCESSIBILITY_SERVICE),
                fixedLaunchComponent = null,
            )

            DESKTOP_COMPONENT_ID -> ManagedComponentContract(
                packageName = DESKTOP_PACKAGE_NAME,
                order = 0,
                requiredRuntimeServices = listOf(DESKTOP_ACCESSIBILITY_SERVICE),
                fixedLaunchComponent = DESKTOP_MAIN_ACTIVITY,
            )

            FILE_MANAGER_COMPONENT_ID -> ManagedComponentContract(
                packageName = FILE_MANAGER_PACKAGE_NAME,
                order = 2,
                requiredRuntimeServices = emptyList(),
                fixedLaunchComponent = FILE_MANAGER_MAIN_ACTIVITY,
            )

            else -> null
        }?.takeIf { it.packageName == component.packageName }

    private data class ManagedComponentContract(
        val packageName: String,
        val order: Int,
        val requiredRuntimeServices: List<String>,
        val fixedLaunchComponent: String?,
    )

    const val LYRICS_COMPONENT_ID = "lyrics"
    const val DESKTOP_COMPONENT_ID = "desktop"
    const val FILE_MANAGER_COMPONENT_ID = "file-manager"
    const val LYRICS_PACKAGE_NAME = "com.tcrrry.desktoplyrics"
    const val DESKTOP_PACKAGE_NAME = "com.tcrrry.desktop"
    const val FILE_MANAGER_PACKAGE_NAME = "org.fossify.filemanager.debug"
    const val DESKTOP_MAIN_ACTIVITY = "com.tcrrry.desktop/.MainActivity"
    const val FILE_MANAGER_MAIN_ACTIVITY =
        "org.fossify.filemanager.debug/org.fossify.filemanager.activities.MainActivity"
    const val LYRICS_NOTIFICATION_LISTENER =
        "com.tcrrry.desktoplyrics/com.tcrrry.desktoplyrics.MediaListenerService"
    const val LYRICS_ACCESSIBILITY_SERVICE =
        "com.tcrrry.desktoplyrics/com.tcrrry.desktoplyrics.IcarDockAccessibilityService"
    const val DESKTOP_ACCESSIBILITY_SERVICE =
        "com.tcrrry.desktop/com.tcrrry.desktop.debug.NavigationDemoAccessibilityService"
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
        plan.actions.forEach { action ->
            val artifact = byComponent[action.componentId]
                ?: return DeviceActionFailure("authorization_artifact_missing", action.componentId, retryable = false)
            val declarations = artifact.declarations
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
