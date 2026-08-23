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

enum class MaintenanceActionId {
    CHECK_UPDATES,
    REINSTALL,
    REPAIR_CONFIGURATION,
    MANAGE_APPS,
    INSTALL_FILE_MANAGER,
    LAUNCH_LYRICS,
    LAUNCH_DESKTOP,
    CLEANUP,
    EXPORT_DIAGNOSTICS,
}

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
