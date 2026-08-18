package com.tcrrry.helper.domain.session

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
    PAUSED,
    DOWNLOAD_FAILED,
    INSTALLATION_FAILED,
    CONFIGURATION_FAILED,
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

enum class MaintenanceGroupId {
    COMMON,
    APPS,
    STORAGE,
}
