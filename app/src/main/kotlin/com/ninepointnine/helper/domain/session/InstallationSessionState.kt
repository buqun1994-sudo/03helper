package com.ninepointnine.helper.domain.session

/** The only lifecycle state set that the application flow is allowed to own. */
enum class InstallationSessionState {
    IDLE,
    DISCOVERING,
    CONNECTING,
    CONNECTED,
    SELECTION_CONFIRMED,
    RESOLVING_SOURCE,
    DOWNLOADING_ARCHIVE,
    VERIFYING_ARCHIVE,
    EXTRACTING_APK,
    VERIFYING_ARTIFACTS,
    INSTALLING,
    AUTHORIZING,
    VERIFYING_DEVICE,
    SUCCEEDED,
    COMPLETED_WITH_ERRORS,
    PAUSED,
    FAILED,
    MAINTENANCE,
}

enum class DeviceConnectionStatus {
    CONFIRMED,
    CONNECTING,
    DISCONNECTED,
}

enum class FailureCategory {
    CONNECTION,
    DOWNLOAD,
    ARCHIVE,
    INSTALLATION,
    CONFIGURATION,
    VERIFICATION,
    UNKNOWN,
}

enum class InstallPhase {
    FETCH,
    CHECK,
    SEND,
    CONFIGURE,
    VERIFY,
}

/**
 * Explicit lifecycle for artifact metadata. A non-empty manifest list is data,
 * not a safe substitute for knowing which catalog stage produced it.
 */
enum class ArtifactCatalogStage {
    NOT_LOADED,
    CONTROL_PLANE_READY,
    PREPARED,
}

/**
 * Installation intent is part of the session contract so a resumed batch
 * cannot accidentally turn a missing-app install into a reinstall.
 */
enum class InstallationStrategy {
    INSTALL_MISSING_ONLY,
    REINSTALL_SELECTED,
}

/** Identifies the business flow that owns the current installation batch. */
enum class InstallationFlow {
    INITIAL_INSTALL,
    MAINTENANCE_INSTALL,
}

enum class ResultKind {
    SUCCESS,
    PARTIAL_FAILURE,
    PAUSED,
    DOWNLOAD_FAILED,
    INSTALLATION_FAILED,
    CONFIGURATION_FAILED,
}

enum class MaintenanceActionStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
}

enum class MaintenanceApplicationActionId {
    START,
    FORCE_STOP,
    UNINSTALL,
    DETAILS,
}

enum class MaintenanceActionId {
    CHECK_UPDATES,
    REINSTALL,
    REPAIR_CONFIGURATION,
    MANAGE_APPS,
    INSTALL_APPLICATIONS,
    /** Legacy persisted name; new UI and flows use [INSTALL_APPLICATIONS]. */
    INSTALL_FILE_MANAGER,
    LAUNCH_LYRICS,
    LAUNCH_DESKTOP,
    CLEANUP,
    EXPORT_DIAGNOSTICS,
}

val MaintenanceActionId.isApplicationInstallation: Boolean
    get() = this == MaintenanceActionId.INSTALL_APPLICATIONS ||
        this == MaintenanceActionId.INSTALL_FILE_MANAGER

/** Whether the action needs the retained, confirmed car connection. */
val MaintenanceActionId.requiresConnectedDevice: Boolean
    get() = when (this) {
        MaintenanceActionId.CHECK_UPDATES,
        MaintenanceActionId.CLEANUP,
        MaintenanceActionId.EXPORT_DIAGNOSTICS,
        -> false

        MaintenanceActionId.REINSTALL,
        MaintenanceActionId.REPAIR_CONFIGURATION,
        MaintenanceActionId.MANAGE_APPS,
        MaintenanceActionId.INSTALL_APPLICATIONS,
        MaintenanceActionId.INSTALL_FILE_MANAGER,
        MaintenanceActionId.LAUNCH_LYRICS,
        MaintenanceActionId.LAUNCH_DESKTOP,
        -> true
    }

enum class MaintenanceGroupId {
    COMMON,
    APPS,
    STORAGE,
}
