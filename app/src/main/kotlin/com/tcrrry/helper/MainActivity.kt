package com.tcrrry.helper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tcrrry.helper.application.InstallerRuntime
import com.tcrrry.helper.domain.session.InstallationSessionCommand
import com.tcrrry.helper.ui.InstallApp
import com.tcrrry.helper.ui.state.InstallUiIntent

class MainActivity : ComponentActivity() {
    private val installerRuntime: InstallerRuntime
        get() = (application as InstallerApplication).installerRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Seed the first frame with the same bounded discovery used on foreground re-entry.
        installerRuntime.onForeground()
        setContent {
            InstallerRoot(installerRuntime)
        }
    }

    override fun onStart() {
        super.onStart()
        installerRuntime.onForeground()
    }

}

@Composable
private fun InstallerRoot(runtime: InstallerRuntime) {
    val snapshot by runtime.session.snapshots.collectAsStateWithLifecycle()
    InstallApp(
        snapshot = snapshot,
        onIntent = { intent -> runtime.dispatch(intent.toInstallationSessionCommand()) },
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
    InstallUiIntent.Reconfigure -> InstallationSessionCommand.ReconfigureInstallation
    InstallUiIntent.EnterMaintenance -> InstallationSessionCommand.EnterMaintenance
    is InstallUiIntent.MaintenanceAction -> InstallationSessionCommand.MaintenanceAction(actionId)
}
