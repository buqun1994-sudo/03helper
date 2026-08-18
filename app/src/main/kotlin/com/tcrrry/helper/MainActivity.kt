package com.tcrrry.helper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import com.tcrrry.helper.domain.session.InstallationSessionSnapshot
import com.tcrrry.helper.domain.session.InstallationSessionState
import com.tcrrry.helper.ui.InstallApp
import com.tcrrry.helper.ui.state.InstallUiIntent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            InstallerRoot()
        }
    }
}

@Composable
private fun InstallerRoot() {
    InstallApp(
        snapshot = InstallationSessionSnapshot(state = InstallationSessionState.IDLE),
        onIntent = { _: InstallUiIntent ->
            // F0 exposes the intent boundary; the InstallationSession owner arrives in F1.
        },
    )
}
