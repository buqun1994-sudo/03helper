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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ninepointnine.helper.BuildConfig
import com.ninepointnine.helper.data.artifact.ApkIconRepository
import com.ninepointnine.helper.data.artifact.ApkIconRequest
import com.ninepointnine.helper.data.artifact.RemoteLogoRepository
import com.ninepointnine.helper.data.artifact.RemoteLogoRequest
import com.ninepointnine.helper.domain.artifact.InstallerSelfIdentity
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.ui.screens.FirstInstallScreen
import com.ninepointnine.helper.ui.screens.MaintenanceActionFlowPage
import com.ninepointnine.helper.ui.screens.MaintenanceHome
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.ui.state.InstallUiIntent
import com.ninepointnine.helper.ui.state.InstallUiState
import com.ninepointnine.helper.ui.state.InstallUiStateMapper
import com.ninepointnine.helper.ui.components.LocalApkIcons
import com.ninepointnine.helper.ui.theme.InstallerColors
import com.ninepointnine.helper.ui.theme.InstallerMotion
import com.ninepointnine.helper.ui.theme.InstallerTheme

@Composable
fun InstallApp(
    snapshot: InstallationSessionSnapshot,
    onIntent: (InstallUiIntent) -> Unit,
    modifier: Modifier = Modifier,
    apkIconRepository: ApkIconRepository? = null,
    remoteLogoRepository: RemoteLogoRepository? = null,
) {
    val uiState = InstallUiStateMapper.map(snapshot)
    var maintenanceAction by remember { mutableStateOf<MaintenanceActionId?>(null) }
    val renderTarget = InstallRenderTarget(uiState = uiState, maintenanceAction = maintenanceAction)
    val density = LocalDensity.current
    val iconRequests = remember(snapshot.components, snapshot.artifactManifests, snapshot.maintenance) {
        buildIconRequests(snapshot)
    }
    val iconRevision = snapshot.revision
    val remoteIconRequests = remember(snapshot.components, snapshot.catalogRevision, snapshot.catalogVersion) {
        buildRemoteIconRequests(snapshot)
    }
    val iconLoadKey = (iconRevision to iconRequests) to remoteIconRequests
    val apkIcons by produceState<Map<String, ImageBitmap>>(
        initialValue = emptyMap(),
        key1 = apkIconRepository,
        key2 = remoteLogoRepository,
        key3 = iconLoadKey,
    ) {
        val remote = remoteLogoRepository
            ?.loadIcons(remoteIconRequests)
            ?.mapValues { (_, bitmap) -> bitmap.asImageBitmap() }
            .orEmpty()
        val local = apkIconRepository
            ?.loadIcons(iconRequests, snapshot.artifactManifests)
            ?.mapValues { (_, bitmap) -> bitmap.asImageBitmap() }
            .orEmpty()
        value = remote + local
    }
    InstallerTheme {
        CompositionLocalProvider(LocalApkIcons provides apkIcons) {
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
}

private fun buildIconRequests(snapshot: InstallationSessionSnapshot): List<ApkIconRequest> {
    val manifests = snapshot.artifactManifests.associateBy { it.componentId }
    val installedPackages = snapshot.maintenance.managedApplications.associate { it.componentId to it.packageName }
    val ids = buildSet {
        addAll(snapshot.components.map { it.id })
        addAll(snapshot.maintenance.managedApplications.map { it.componentId })
        addAll(snapshot.maintenance.availableManifests.map { it.componentId })
        addAll(snapshot.maintenance.installationSelection?.options.orEmpty().map { it.componentId })
    }
    return ids.map { componentId ->
        manifests[componentId]
            ?.let { manifest ->
                ApkIconRequest(
                    componentId = componentId,
                    packageName = manifest.packageName,
                    certificateSha256 = manifest.certificateSha256,
                )
            }
            ?: ApkIconRequest(
                componentId = componentId,
                packageName = installedPackages[componentId]
                    ?: InstallerSelfIdentity.PACKAGE_NAME.takeIf { InstallerSelfIdentity.isSelfComponentId(componentId) },
            )
    }.sortedBy { it.componentId }
}

private fun buildRemoteIconRequests(snapshot: InstallationSessionSnapshot): List<RemoteLogoRequest> {
    val environment = if (BuildConfig.DEBUG) "staging" else "production"
    val channel = if (BuildConfig.DEBUG) "debug" else "release"
    return snapshot.components.mapNotNull { component ->
        component.iconAsset?.let { asset ->
            RemoteLogoRequest(
                componentId = component.id,
                environment = environment,
                channel = channel,
                catalogRevision = snapshot.catalogRevision,
                asset = asset,
            )
        }
    }.distinctBy { it.componentId to it.asset.sha256 }
}

private data class InstallRenderTarget(
    val uiState: InstallUiState,
    val maintenanceAction: MaintenanceActionId?,
)
