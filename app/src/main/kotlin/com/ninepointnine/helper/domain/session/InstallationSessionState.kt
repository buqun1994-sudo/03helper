package com.ninepointnine.helper.domain.session

/** The only lifecycle state set that the application flow is allowed to own. */
enum class InstallationSessionState {
    IDLE,
    DISCOVERING,
    CONNECTING,
    CONNECTED,
    SELECTION_CONFIRMED,
    PREPARING_ARTIFACTS,
    ARTIFACTS_READY,
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
    /** Device write completed but one or more installed identities need a retrying readback. */
    CONFIRMATION_PENDING,
    PARTIAL_FAILURE,
    PAUSED,
    DOWNLOAD_FAILED,
    INSTALLATION_FAILED,
    CONFIGURATION_FAILED,
}

/**
 * The terminal boundary at which a batch stopped. This is domain semantics,
 * not a screen styling decision: an APK write that is still awaiting identity
 * proof must never be presented as an ordinary installation rejection.
 */
enum class InstallationResultFailureStage {
    NONE,
    INSTALLATION,
    POST_INSTALL,
    MIXED,
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

/**
 * Maps an external failure identifier to the only component status the
 * selection/result domain exposes. Matching is deliberately exact (or uses a
 * documented reason-family prefix) so a later identity readback failure can
 * never become a misleading "directory missing" state.
 */
fun componentStatusForReasonCode(reasonCode: String): ComponentStatus = when {
    reasonCode == "lanzou_folder_missing" || reasonCode.startsWith("lanzou_folder_missing_") ||
        reasonCode == "lanzou_folder_empty" || reasonCode.startsWith("lanzou_folder_empty_") ->
        ComponentStatus.DIRECTORY_MISSING

    reasonCode in setOf(
        "install_apk_certificate_mismatch",
        "distribution_apk_certificate_mismatch",
        "installation_installed_certificate_mismatch",
        "maintenance_installed_certificate_mismatch",
        "certificate_sha256_invalid",
        "apk_certificate_mismatch",
    ) -> ComponentStatus.APK_SIGNATURE_MISMATCH

    reasonCode.startsWith("archive_") ||
        reasonCode.startsWith("dynamic_archive_") ||
        reasonCode.startsWith("distribution_archive_") ||
        reasonCode.startsWith("apk_entry_") ||
        reasonCode.startsWith("apk_extraction_") ||
        reasonCode in setOf(
            "download_non_archive_response",
            "download_not_zip",
            "archive_verification_evidence_invalid",
            "archive_verification_failed",
            "apk_identity_missing",
            "installation_installed_apk_hash_mismatch",
            "maintenance_installed_apk_hash_mismatch",
            "apk_hash_mismatch",
            "apk_size_mismatch",
        ) -> ComponentStatus.ZIP_VALIDATION_FAILED

    reasonCode in setOf(
        "component_incompatible",
        "device_capability_missing",
        "device_android_sdk_missing",
        "manifest_schema_unsupported",
        "distribution_config_schema_unsupported",
        "distribution_config_payload_schema_unsupported",
        "distribution_config_desktop_client_schema_unsupported",
        "lanzou_folder_client_schema_unsupported",
        "catalog_signature_algorithm_unsupported",
    ) -> ComponentStatus.CLIENT_CAPABILITY_INSUFFICIENT

    else -> ComponentStatus.TEMPORARILY_UNAVAILABLE
}

/** Maps a reason to the phase that produced the user-visible fact. */
fun installPhaseForReasonCode(reasonCode: String): InstallPhase = when {
    reasonCode in setOf(
        "installation_package_path_missing",
        "installation_installed_apk_metadata_unreadable",
        "installation_installed_apk_read_failed",
        "installation_installed_apk_verify_failed",
        "installation_installed_package_mismatch",
        "installation_installed_certificate_mismatch",
        "installation_installed_version_mismatch",
        "installation_installed_apk_hash_mismatch",
        "maintenance_package_path_missing",
        "maintenance_installed_apk_metadata_unreadable",
        "maintenance_installed_apk_read_failed",
        "maintenance_installed_apk_verify_failed",
        "maintenance_installed_package_mismatch",
        "maintenance_installed_certificate_mismatch",
        "maintenance_installed_version_mismatch",
        "maintenance_installed_apk_hash_mismatch",
        "installation_detail_invalid",
        "installation_evidence_invalid",
        "installation_evidence_missing",
        "installation_write_receipt_invalid",
        "success_evidence_incomplete",
        "availability_detail_invalid",
        "availability_evidence_missing",
        "device_verification_out_of_order",
        "desktop_process_not_running",
        "desktop_service_not_bound",
        "desktop_service_readback_failed",
        "desktop_verification_not_completed",
        "desktop_launch_failed",
        "desktop_launch_evidence_invalid",
    ) -> InstallPhase.VERIFY

    reasonCode in setOf(
        "adb_pm_install_failed",
        "adb_install_transport_failed",
        "adb_install_failed",
        "install_exception",
        "install_apk_file_invalid",
        "install_manifest_invalid",
        "installation_batch_plan_invalid",
    ) -> InstallPhase.SEND

    reasonCode.startsWith("authorization_") ||
        reasonCode.startsWith("configuration_") ||
        reasonCode in setOf(
            "desktop_prerequisite_failed",
            "desktop_missing",
            "desktop_service_missing",
            "authorization_not_attempted",
            "shortcut_selected_component_missing",
            "unexpected_desktop_launch",
        ) -> InstallPhase.CONFIGURE

    reasonCode.startsWith("archive_") ||
        reasonCode.startsWith("dynamic_archive_") ||
        reasonCode.startsWith("distribution_archive_") ||
        reasonCode.startsWith("apk_entry_") ||
        reasonCode.startsWith("apk_extraction_") ||
        reasonCode == "download_not_zip" ||
        reasonCode.startsWith("artifact_") ||
        reasonCode.startsWith("catalog_") ||
        reasonCode.startsWith("distribution_config_") ||
        reasonCode.startsWith("download_") ||
        reasonCode.startsWith("lanzou_") ||
        reasonCode.startsWith("source_") ||
        reasonCode in setOf(
            "all_sources_failed",
            "local_download_candidate_missing",
            "local_download_unavailable",
            "public_download_publish_failed",
            "public_download_output_unavailable",
        ) -> InstallPhase.FETCH

    else -> InstallPhase.VERIFY
}

/** Converts a concrete reason into the top-level session failure category. */
fun failureCategoryForReasonCode(reasonCode: String): FailureCategory = when {
    reasonCode.startsWith("archive_") ||
    reasonCode.startsWith("dynamic_archive_") ||
        reasonCode.startsWith("distribution_archive_") ||
        reasonCode.startsWith("apk_entry_") ||
        reasonCode.startsWith("apk_extraction_") ||
        reasonCode == "download_not_zip" -> FailureCategory.ARCHIVE

    reasonCode.startsWith("download_") ||
        reasonCode.startsWith("lanzou_") ||
        reasonCode.startsWith("source_") ||
        reasonCode in setOf(
            "all_sources_failed",
            "local_download_candidate_missing",
            "local_download_unavailable",
            "public_download_publish_failed",
            "public_download_output_unavailable",
        ) -> FailureCategory.DOWNLOAD

    reasonCode.startsWith("authorization_") ||
        reasonCode.startsWith("configuration_") ||
        reasonCode in setOf("desktop_prerequisite_failed", "authorization_not_attempted") ->
        FailureCategory.CONFIGURATION

    reasonCode.startsWith("adb_") ||
        reasonCode.startsWith("install_") ||
        reasonCode.startsWith("installation_") -> FailureCategory.INSTALLATION

    else -> FailureCategory.VERIFICATION
}
