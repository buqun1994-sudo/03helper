package com.ninepointnine.helper.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ninepointnine.helper.data.artifact.ApkIconRepository
import com.ninepointnine.helper.data.artifact.ApkIconRequest
import com.ninepointnine.helper.domain.artifact.InstallerSelfIdentity
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.domain.session.ArtifactCatalogStage
import com.ninepointnine.helper.domain.session.InstallationFlow
import com.ninepointnine.helper.ui.screens.FirstInstallScreen
import com.ninepointnine.helper.ui.screens.MaintenanceActionFlowPage
import com.ninepointnine.helper.ui.screens.MaintenanceHome
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.domain.session.isApplicationInstallation
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
) {
    val uiState = InstallUiStateMapper.map(snapshot)
    // The session snapshot is the sole owner of the secondary maintenance
    // route. No remembered action may survive a flow transition or process
    // recreation and redirect an initial-install result into maintenance.
    val routedMaintenanceAction = when {
        uiState is InstallUiState.Maintenance -> uiState.routeAction
        uiState.ownsMaintenanceInstallation() -> snapshot.maintenance.routeAction
        else -> null
    }
    // The system back gesture is part of the same flow-level contract as the
    // page button. Use the latest mapped state here rather than a remembered
    // page-local flag, so a recreated or animated page cannot route a
    // maintenance result through the initial-install selection.
    val currentFlowBack: (() -> Unit)? = when (val state = uiState) {
        is InstallUiState.Result -> {
            {
                onIntent(
                    if (state.installationFlow == InstallationFlow.MAINTENANCE_INSTALL) {
                        maintenanceResultBackIntent(routedMaintenanceAction, state)
                    } else {
                        InstallUiIntent.ReturnToSelection
                    },
                )
            }
        }

        else -> null
    }
    BackHandler(enabled = currentFlowBack != null) {
        currentFlowBack?.invoke()
    }
    val renderTarget = InstallRenderTarget(uiState = uiState, maintenanceAction = routedMaintenanceAction)
    val density = LocalDensity.current
    val iconManifests = remember(
        snapshot.artifactManifests,
        snapshot.maintenance.availableManifests,
        snapshot.artifactCatalogStage,
    ) {
        iconManifests(snapshot)
    }
    val iconRequests = remember(snapshot.components, iconManifests, snapshot.maintenance) {
        buildIconRequests(snapshot)
    }
    // Keep the request identity with each decoded bitmap. A component id by
    // itself is not enough: a new installed version or a new signed cloud
    // asset must invalidate the previous image immediately.
    var localApkIcons by remember { mutableStateOf<Map<ApkIconRequest, ImageBitmap>>(emptyMap()) }
    val localRequestsByComponent = iconRequests.associateBy { it.componentId }
    LaunchedEffect(apkIconRepository, iconRequests, iconManifests) {
        val localResult = apkIconRepository
            ?.loadIcons(iconRequests, iconManifests)
            .orEmpty()
        val localResults = localResult.mapNotNull { (componentId, bitmap) ->
            localRequestsByComponent[componentId]?.let { request ->
                request to bitmap.asImageBitmap()
            }
        }.toMap()
        val nextLocalIcons = localApkIcons
            .filterKeys { localRequestsByComponent[it.componentId] == it } + localResults
        localApkIcons = nextLocalIcons
    }
    val activeLocalIcons = localApkIcons.mapNotNull { (request, bitmap) ->
        (localRequestsByComponent[request.componentId] == request).takeIf { it }
            ?.let { request.componentId to bitmap }
    }.toMap()
    val visibleIcons = activeLocalIcons
    InstallerTheme {
        CompositionLocalProvider(LocalApkIcons provides visibleIcons) {
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
                            )
                        }

                        target.maintenanceAction != null && target.uiState.ownsMaintenanceInstallation() -> {
                            MaintenanceActionFlowPage(
                                action = target.maintenanceAction,
                                state = target.uiState,
                                onIntent = onIntent,
                                onBack = {
                                    currentFlowBack?.invoke()
                                        ?: onIntent(InstallUiIntent.ReturnToMaintenanceInstallationSelection)
                                },
                            )
                        }

                        else -> FirstInstallScreen(target.uiState, onIntent)
                    }
                }
            }
        }
    }
}

private fun InstallUiState.ownsMaintenanceInstallation(): Boolean = when (this) {
    is InstallUiState.Installing -> installationFlow == InstallationFlow.MAINTENANCE_INSTALL
    is InstallUiState.Result -> installationFlow == InstallationFlow.MAINTENANCE_INSTALL
    else -> false
}

/** Maps every maintenance result gesture to its owning recovery boundary. */
internal fun maintenanceResultBackIntent(
    action: MaintenanceActionId?,
    state: InstallUiState.Result,
): InstallUiIntent {
    // A result carrying the initial-install identity can never be routed by a
    // maintenance action argument. This guard prevents a stale page callback
    // from sending an initial failure into the maintenance recovery command.
    if (state.installationFlow != InstallationFlow.MAINTENANCE_INSTALL) {
        return if (state.kind in setOf(
                com.ninepointnine.helper.domain.session.ResultKind.SUCCESS,
                com.ninepointnine.helper.domain.session.ResultKind.CONFIRMATION_PENDING,
            ) &&
            state.canEnterMaintenance
        ) {
            InstallUiIntent.EnterMaintenance
        } else {
            InstallUiIntent.ReturnToSelection
        }
    }
    return when {
        state.kind in setOf(
            com.ninepointnine.helper.domain.session.ResultKind.SUCCESS,
            com.ninepointnine.helper.domain.session.ResultKind.CONFIRMATION_PENDING,
        ) && state.canEnterMaintenance ->
            InstallUiIntent.EnterMaintenance

        action?.isApplicationInstallation == true ->
            InstallUiIntent.ReturnToMaintenanceInstallationSelection

        state.kind == com.ninepointnine.helper.domain.session.ResultKind.PARTIAL_FAILURE &&
            state.canEnterMaintenance -> InstallUiIntent.EnterMaintenance

        else -> InstallUiIntent.ReturnToMaintenanceInstallationSelection
    }
}

private fun buildIconRequests(snapshot: InstallationSessionSnapshot): List<ApkIconRequest> {
    val manifests = iconManifests(snapshot).associateBy { it.componentId }
    val installedApplications = snapshot.maintenance.managedApplications.associateBy { it.componentId }
    val ids = buildSet {
        addAll(snapshot.components.map { it.id })
        addAll(snapshot.maintenance.managedApplications.map { it.componentId })
        addAll(snapshot.maintenance.installedManifests.map { it.componentId })
        addAll(snapshot.maintenance.availableManifests.map { it.componentId })
        addAll(snapshot.maintenance.installationSelection?.options.orEmpty().map { it.componentId })
        addAll(snapshot.maintenance.updateStatuses.map { it.componentId })
    }
    return ids.map { componentId ->
        val manifest = manifests[componentId]
        val installed = installedApplications[componentId]
        when {
            installed != null -> {
                val exactManifest = manifest?.takeIf { it.packageName == installed.packageName }
                ApkIconRequest(
                    componentId = componentId,
                    packageName = installed.packageName,
                    certificateSha256 = exactManifest?.certificateSha256,
                    apkSha256 = exactManifest
                        ?.takeIf { installed.versionCode == null || it.apkVersion.code == installed.versionCode }
                        ?.apkSha256,
                    versionCode = installed.versionCode,
                    preferPersisted = true,
                )
            }

            manifest != null -> ApkIconRequest(
                componentId = componentId,
                packageName = manifest.packageName,
                certificateSha256 = manifest.certificateSha256,
                apkSha256 = manifest.apkSha256,
                versionCode = manifest.apkVersion.code,
            )

            else -> ApkIconRequest(
                componentId = componentId,
                packageName = InstallerSelfIdentity.PACKAGE_NAME.takeIf {
                    InstallerSelfIdentity.isSelfComponentId(componentId)
                },
            )
        }
    }.sortedBy { it.componentId }
}

/** The icon resolver sees the current prepared batch first, then the signed maintenance catalog. */
private fun iconManifests(snapshot: InstallationSessionSnapshot): List<ArtifactManifest> {
    val byId = linkedMapOf<String, ArtifactManifest>()
    snapshot.maintenance.availableManifests.forEach { manifest -> byId[manifest.componentId] = manifest }
    snapshot.maintenance.installedManifests.forEach { manifest -> byId[manifest.componentId] = manifest }
    if (snapshot.artifactCatalogStage == ArtifactCatalogStage.PREPARED) {
        snapshot.artifactManifests.forEach { manifest -> byId[manifest.componentId] = manifest }
    }
    return byId.values.toList()
}

private data class InstallRenderTarget(
    val uiState: InstallUiState,
    val maintenanceAction: MaintenanceActionId?,
)
