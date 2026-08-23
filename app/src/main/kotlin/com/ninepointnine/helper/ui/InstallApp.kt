package com.ninepointnine.helper.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.ui.screens.FirstInstallScreen
import com.ninepointnine.helper.ui.screens.MaintenanceActionFlowPage
import com.ninepointnine.helper.ui.screens.MaintenanceHome
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.ui.state.InstallUiIntent
import com.ninepointnine.helper.ui.state.InstallUiState
import com.ninepointnine.helper.ui.state.InstallUiStateMapper
import com.ninepointnine.helper.ui.theme.InstallerColors
import com.ninepointnine.helper.ui.theme.InstallerMotion
import com.ninepointnine.helper.ui.theme.InstallerTheme

@Composable
fun InstallApp(
    snapshot: InstallationSessionSnapshot,
    onIntent: (InstallUiIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState = InstallUiStateMapper.map(snapshot)
    var maintenanceAction by remember { mutableStateOf<MaintenanceActionId?>(null) }
    val renderTarget = InstallRenderTarget(uiState = uiState, maintenanceAction = maintenanceAction)
    val density = LocalDensity.current
    InstallerTheme {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(InstallerColors.PageBlue)
                .safeDrawingPadding(),
        ) {
            AnimatedContent(
                targetState = renderTarget,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val enteringMaintenanceAction =
                        initialState.maintenanceAction == null && targetState.maintenanceAction != null
                    val leavingMaintenanceAction =
                        initialState.maintenanceAction != null && targetState.maintenanceAction == null
                    val maintenanceRoute = enteringMaintenanceAction || leavingMaintenanceAction
                    val enter = if (maintenanceRoute) {
                        fadeIn(InstallerMotion.pageEnter()) +
                            slideInHorizontally(
                                animationSpec = InstallerMotion.pageEnter(),
                                initialOffsetX = { width ->
                                    if (leavingMaintenanceAction) -width else width
                                },
                            )
                    } else {
                        fadeIn(InstallerMotion.pageEnter()) +
                            slideInVertically(
                                animationSpec = InstallerMotion.pageEnter(),
                                initialOffsetY = { with(density) { 12.dp.roundToPx() } },
                            )
                    }
                    val exit = if (maintenanceRoute) {
                        fadeOut(InstallerMotion.pageExit()) +
                            slideOutHorizontally(
                                animationSpec = InstallerMotion.pageExit(),
                                targetOffsetX = { width ->
                                    if (leavingMaintenanceAction) width else -width
                                },
                            )
                    } else {
                        fadeOut(InstallerMotion.pageExit()) +
                            slideOutVertically(
                                animationSpec = InstallerMotion.pageExit(),
                                targetOffsetY = { with(density) { -8.dp.roundToPx() } },
                            )
                    }
                    ContentTransform(enter, exit, sizeTransform = null)
                },
                contentKey = { "${it.uiState.screen}:${it.maintenanceAction?.name ?: "home"}" },
                label = "installScreen",
            ) { target ->
                when {
                    target.uiState is com.ninepointnine.helper.ui.state.InstallUiState.Maintenance -> {
                        MaintenanceHome(
                            state = target.uiState,
                            onIntent = onIntent,
                            selectedAction = target.maintenanceAction,
                            onSelectedActionChange = { maintenanceAction = it },
                        )
                    }

                    target.maintenanceAction != null &&
                        (
                            target.uiState is InstallUiState.Installing ||
                                target.uiState is InstallUiState.Result
                            ) -> {
                        MaintenanceActionFlowPage(
                            action = target.maintenanceAction,
                            state = target.uiState,
                            onIntent = onIntent,
                            onBack = { maintenanceAction = null },
                        )
                    }

                    else -> FirstInstallScreen(target.uiState, onIntent)
                }
            }
        }
    }
}

private data class InstallRenderTarget(
    val uiState: InstallUiState,
    val maintenanceAction: MaintenanceActionId?,
)
