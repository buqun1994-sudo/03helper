package com.tcrrry.helper.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.tcrrry.helper.domain.session.InstallationSessionSnapshot
import com.tcrrry.helper.ui.screens.FirstInstallScreen
import com.tcrrry.helper.ui.screens.MaintenanceHome
import com.tcrrry.helper.ui.state.InstallUiIntent
import com.tcrrry.helper.ui.state.InstallUiStateMapper
import com.tcrrry.helper.ui.theme.InstallerColors
import com.tcrrry.helper.ui.theme.InstallerMotion
import com.tcrrry.helper.ui.theme.InstallerTheme

@Composable
fun InstallApp(
    snapshot: InstallationSessionSnapshot,
    onIntent: (InstallUiIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState = InstallUiStateMapper.map(snapshot)
    val density = LocalDensity.current
    InstallerTheme {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(InstallerColors.PageBlue)
                .safeDrawingPadding(),
        ) {
            AnimatedContent(
                targetState = uiState,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val enter = fadeIn(InstallerMotion.pageEnter()) +
                        slideInVertically(
                            animationSpec = InstallerMotion.pageEnter(),
                            initialOffsetY = { with(density) { 12.dp.roundToPx() } },
                        )
                    val exit = fadeOut(InstallerMotion.pageExit()) +
                        slideOutVertically(
                            animationSpec = InstallerMotion.pageExit(),
                            targetOffsetY = { with(density) { -8.dp.roundToPx() } },
                        )
                    ContentTransform(enter, exit, sizeTransform = null)
                },
                contentKey = { it.screen },
                label = "installScreen",
            ) { state ->
                when (state) {
                    is com.tcrrry.helper.ui.state.InstallUiState.Maintenance -> MaintenanceHome(state, onIntent)
                    else -> FirstInstallScreen(state, onIntent)
                }
            }
        }
    }
}
