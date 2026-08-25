package com.ninepointnine.helper

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ninepointnine.helper.application.InstallerRuntime
import com.ninepointnine.helper.domain.session.InstallationSessionCommand
import com.ninepointnine.helper.ui.InstallApp
import com.ninepointnine.helper.ui.state.InstallUiIntent

class MainActivity : ComponentActivity() {
    private val installerRuntime: InstallerRuntime
        get() = (application as InstallerApplication).installerRuntime
    private var storagePermissionPrompted = false
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Start the normal foreground flow even when permission is denied so
        // the session can expose a recoverable public-Download error.
        installerRuntime.onForeground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            InstallerRoot(installerRuntime)
        }
    }

    override fun onResume() {
        super.onResume()
        // onResume is the single foreground boundary: it covers first entry and
        // the fast recovery path after a lock-screen pause without duplicate work.
        if (requiresLegacyStoragePermission()) {
            if (!storagePermissionPrompted) {
                storagePermissionPrompted = true
                storagePermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ),
                )
            }
            return
        }
        installerRuntime.onForeground()
    }

    private fun requiresLegacyStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return false
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    }
}

@Composable
private fun InstallerRoot(runtime: InstallerRuntime) {
    val snapshot by runtime.session.snapshots.collectAsStateWithLifecycle()
    InstallApp(
        snapshot = snapshot,
        onIntent = { intent -> runtime.dispatch(intent.toInstallationSessionCommand()) },
        apkIconRepository = runtime.apkIconRepository,
    )
}

/** Application-layer mapping; the domain session never depends on Compose UI types. */
internal fun InstallUiIntent.toInstallationSessionCommand(): InstallationSessionCommand = when (this) {
    InstallUiIntent.StopDiscovery -> InstallationSessionCommand.StopDiscovery
    InstallUiIntent.CancelConnection -> InstallationSessionCommand.CancelConnection
    InstallUiIntent.RetryDiscovery -> InstallationSessionCommand.StartDiscovery
    InstallUiIntent.Reconnect -> InstallationSessionCommand.Reconnect
    InstallUiIntent.DisconnectDevice -> InstallationSessionCommand.DisconnectDevice
    is InstallUiIntent.SelectDevice -> InstallationSessionCommand.SelectDevice(deviceId)
    is InstallUiIntent.ToggleOptionalComponent -> InstallationSessionCommand.ToggleOptionalComponent(componentId, selected)
    InstallUiIntent.StartInstallation -> InstallationSessionCommand.StartInstallation
    InstallUiIntent.CancelInstallation -> InstallationSessionCommand.CancelInstallation
    InstallUiIntent.ContinueInstallation -> InstallationSessionCommand.ContinueInstallation
    InstallUiIntent.RetryInstallation -> InstallationSessionCommand.RetryInstallation
    InstallUiIntent.ReturnToSelection -> InstallationSessionCommand.ReturnToSelection
    InstallUiIntent.ReturnToMaintenanceInstallationSelection ->
        InstallationSessionCommand.ReturnToMaintenanceInstallationSelection
    InstallUiIntent.Reconfigure -> InstallationSessionCommand.ReconfigureInstallation
    InstallUiIntent.EnterMaintenance -> InstallationSessionCommand.EnterMaintenance
    InstallUiIntent.LeaveMaintenanceAction -> InstallationSessionCommand.LeaveMaintenanceAction
    is InstallUiIntent.MaintenanceAction -> InstallationSessionCommand.MaintenanceAction(actionId)
    is InstallUiIntent.MaintenanceApplicationAction -> InstallationSessionCommand.MaintenanceApplicationAction(
        componentId = componentId,
        actionId = actionId,
    )
    is InstallUiIntent.ToggleMaintenanceInstallationComponent ->
        InstallationSessionCommand.ToggleMaintenanceInstallationComponent(componentId, selected)
    InstallUiIntent.StartMaintenanceInstallation -> InstallationSessionCommand.StartMaintenanceInstallation
}
