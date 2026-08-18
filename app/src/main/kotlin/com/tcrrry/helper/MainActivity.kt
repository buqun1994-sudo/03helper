package com.tcrrry.helper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tcrrry.helper.domain.session.InstallationSession
import com.tcrrry.helper.domain.session.InstallationSessionCommand
import com.tcrrry.helper.ui.InstallApp
import com.tcrrry.helper.ui.state.InstallUiIntent

class MainActivity : ComponentActivity() {
    private lateinit var installationSession: InstallationSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        installationSession = InstallationSession()
        setContent {
            InstallerRoot(installationSession)
        }
    }

    override fun onDestroy() {
        installationSession.close()
        super.onDestroy()
    }
}

@Composable
private fun InstallerRoot(session: InstallationSession) {
    val snapshot by session.snapshots.collectAsStateWithLifecycle()
    InstallApp(
        snapshot = snapshot,
        onIntent = { intent -> session.dispatch(intent.toInstallationSessionCommand()) },
    )
}

/** Application-layer mapping; the domain session never depends on Compose UI types. */
internal fun InstallUiIntent.toInstallationSessionCommand(): InstallationSessionCommand = when (this) {
    InstallUiIntent.StopDiscovery -> InstallationSessionCommand.StopDiscovery
    InstallUiIntent.RetryDiscovery -> InstallationSessionCommand.StartDiscovery
    InstallUiIntent.Reconnect -> InstallationSessionCommand.Reconnect
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
