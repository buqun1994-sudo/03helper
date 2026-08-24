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
    val iconManifests = remember(snapshot.artifactManifests, snapshot.maintenance.availableManifests) {
        (snapshot.artifactManifests + snapshot.maintenance.availableManifests)
            .distinctBy { it.componentId }
    }
    val iconRequests = remember(snapshot.components, iconManifests, snapshot.maintenance) {
        buildIconRequests(snapshot)
    }
    val remoteSuppressedComponentIds = remember(
        iconRequests,
        snapshot.maintenance.managedApplications,
        snapshot.maintenance.activeAction,
    ) {
        buildRemoteIconSuppression(snapshot, iconRequests)
    }
    val remoteIconRequests = remember(
        snapshot.components,
        snapshot.catalogRevision,
        snapshot.catalogVersion,
        remoteSuppressedComponentIds,
    ) {
        buildRemoteIconRequests(snapshot, remoteSuppressedComponentIds)
    }
    // Keep the request identity with each decoded bitmap. A component id by
    // itself is not enough: a new installed version or a new signed cloud
    // asset must invalidate the previous image immediately.
    var localApkIcons by remember { mutableStateOf<Map<ApkIconRequest, ImageBitmap>>(emptyMap()) }
    var remoteIcons by remember { mutableStateOf<Map<RemoteLogoRequest, ImageBitmap>>(emptyMap()) }
    val localRequestsByComponent = iconRequests.associateBy { it.componentId }
    val remoteRequestsByComponent = remoteIconRequests.associateBy { it.componentId }
    LaunchedEffect(apkIconRepository, remoteLogoRepository, iconRequests, remoteIconRequests) {
        // Read immutable remote cache entries before scanning local APK files.
        // The latter may inspect several public Download files; it must not
        // delay a logo that is already available locally on disk.
        val remoteCachedResult = remoteLogoRepository
            ?.loadCachedIcons(remoteIconRequests)
            .orEmpty()
        val remoteCached = remoteCachedResult.mapNotNull { (componentId, bitmap) ->
            remoteRequestsByComponent[componentId]?.let { request ->
                request to bitmap.asImageBitmap()
            }
        }.toMap()
        val cachedLocalComponents = localApkIcons.keys.mapTo(mutableSetOf()) { it.componentId }
        remoteIcons = remoteIcons
            .filterKeys { request ->
                remoteRequestsByComponent[request.componentId] == request &&
                    request.componentId !in remoteSuppressedComponentIds &&
                    request.componentId !in cachedLocalComponents
            } + remoteCached.filterKeys { request ->
            request.componentId !in remoteSuppressedComponentIds && request.componentId !in cachedLocalComponents
        }

        // A verified APK or a persisted APK icon always wins once its scan
        // completes. The UI's visible-icon projection also enforces this
        // precedence while both stages are in flight.
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

        val localComponents = nextLocalIcons.keys.mapTo(mutableSetOf()) { it.componentId }
        remoteIcons = remoteIcons.filterKeys { request ->
            remoteRequestsByComponent[request.componentId] == request &&
                request.componentId !in remoteSuppressedComponentIds &&
                request.componentId !in localComponents
        }

        val remoteResult = remoteLogoRepository
            ?.loadIcons(remoteIconRequests.filterNot { remoteCached.containsKey(it) })
            .orEmpty()
        val remoteResults = remoteResult.mapNotNull { (componentId, bitmap) ->
            remoteRequestsByComponent[componentId]?.let { request ->
                request to bitmap.asImageBitmap()
            }
        }.toMap()
        remoteIcons = remoteIcons + remoteResults.filterKeys { request ->
            request.componentId !in remoteSuppressedComponentIds && request.componentId !in localComponents
        }
    }
    val activeLocalIcons = localApkIcons.mapNotNull { (request, bitmap) ->
        (localRequestsByComponent[request.componentId] == request).takeIf { it }
            ?.let { request.componentId to bitmap }
    }.toMap()
    val activeLocalComponents = activeLocalIcons.keys
    val activeRemoteIcons = remoteIcons.mapNotNull { (request, bitmap) ->
        if (remoteRequestsByComponent[request.componentId] == request &&
            request.componentId !in remoteSuppressedComponentIds &&
            request.componentId !in activeLocalComponents
        ) {
            request.componentId to bitmap
        } else {
            null
        }
    }.toMap()
    val visibleIcons = buildMap {
        putAll(activeLocalIcons)
        putAll(activeRemoteIcons)
    }
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
    val manifests = (snapshot.artifactManifests + snapshot.maintenance.availableManifests)
        .distinctBy { it.componentId }
        .associateBy { it.componentId }
    val installedApplications = snapshot.maintenance.managedApplications.associateBy { it.componentId }
    val ids = buildSet {
        addAll(snapshot.components.map { it.id })
        addAll(snapshot.maintenance.managedApplications.map { it.componentId })
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

private fun buildRemoteIconSuppression(
    snapshot: InstallationSessionSnapshot,
    iconRequests: List<ApkIconRequest>,
): Set<String> = buildSet {
    addAll(
        snapshot.maintenance.managedApplications
            .filter { it.installed }
            .map { it.componentId },
    )
    if (snapshot.maintenance.activeAction in INVENTORY_REFRESH_ACTIONS) {
        // While a fresh car inventory is being read, an old remote preview
        // must not appear for a component whose installed state is unknown.
        addAll(iconRequests.map { it.componentId })
    }
}

private fun buildRemoteIconRequests(
    snapshot: InstallationSessionSnapshot,
    suppressedComponentIds: Set<String>,
): List<RemoteLogoRequest> {
    val environment = if (BuildConfig.DEBUG) "staging" else "production"
    val channel = if (BuildConfig.DEBUG) "debug" else "release"
    return snapshot.components.mapNotNull { component ->
        if (component.id in suppressedComponentIds) return@mapNotNull null
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

private val INVENTORY_REFRESH_ACTIONS = setOf(
    MaintenanceActionId.MANAGE_APPS,
    MaintenanceActionId.REPAIR_CONFIGURATION,
    MaintenanceActionId.INSTALL_FILE_MANAGER,
)

private data class InstallRenderTarget(
    val uiState: InstallUiState,
    val maintenanceAction: MaintenanceActionId?,
)
