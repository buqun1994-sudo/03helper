package com.ninepointnine.helper.application

import android.content.Context
import android.os.Environment
import com.ninepointnine.helper.BuildConfig
import com.ninepointnine.helper.application.artifact.ArtifactCatalogSessionAdapter
import com.ninepointnine.helper.application.artifact.ArtifactPreparationCoordinator
import com.ninepointnine.helper.application.artifact.ArtifactPreparationResult
import com.ninepointnine.helper.application.artifact.PreparedArtifact
import com.ninepointnine.helper.application.device.DeviceConnectionSessionAdapter
import com.ninepointnine.helper.application.device.DeviceDiscoverySessionAdapter
import com.ninepointnine.helper.application.device.DeviceInstallationCoordinator
import com.ninepointnine.helper.application.session.InstallationSessionBoundary
import com.ninepointnine.helper.application.maintenance.MaintenanceController
import com.ninepointnine.helper.application.maintenance.MaintenanceDiagnosticStore
import com.ninepointnine.helper.application.maintenance.MaintenanceSessionStore
import com.ninepointnine.helper.data.artifact.AndroidApkMetadataReader
import com.ninepointnine.helper.data.artifact.ApkIconRepository
import com.ninepointnine.helper.data.artifact.ArchiveIdentityVerifier
import com.ninepointnine.helper.data.artifact.ArtifactArchiveExtractor
import com.ninepointnine.helper.data.artifact.ArtifactIdentityVerifier
import com.ninepointnine.helper.data.catalog.FolderArtifactCatalogAdapter
import com.ninepointnine.helper.data.catalog.FileCatalogRevisionStore
import com.ninepointnine.helper.data.device.DadbDeviceTransport
import com.ninepointnine.helper.data.device.DadbDeviceConnectionFactory
import com.ninepointnine.helper.data.device.JdkLocalIpv4SubnetProvider
import com.ninepointnine.helper.data.device.LanAdbDeviceDiscovery
import com.ninepointnine.helper.data.download.ArtifactCache
import com.ninepointnine.helper.data.download.ArtifactDownloader
import com.ninepointnine.helper.data.download.UrlConnectionArtifactTransport
import com.ninepointnine.helper.data.web.AndroidLanzouWebViewHost
import com.ninepointnine.helper.data.web.LanzouFolderSourceAdapter
import com.ninepointnine.helper.data.web.LanzouFolderWebViewHostFactory
import com.ninepointnine.helper.data.web.LanzouWebSourceAdapter
import com.ninepointnine.helper.data.web.LanzouWebViewHostFactory
import com.ninepointnine.helper.data.web.LanzouWebViewMountRegistry
import com.ninepointnine.helper.data.selfupdate.AndroidSelfUpdateInstaller
import com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.ArtifactFailure
import com.ninepointnine.helper.domain.artifact.ArtifactFailurePhase
import com.ninepointnine.helper.domain.device.DeviceActionFailure
import com.ninepointnine.helper.domain.device.DeviceConnectionLease
import com.ninepointnine.helper.domain.session.InstallationSession
import com.ninepointnine.helper.domain.session.InstallationBatchPlan
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.domain.session.InstallationSessionState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Production composition root. Missing Cloud trust data remains explicitly unavailable. */
object ProductionInstallerRuntimeFactory {
    fun create(
        context: Context,
        webViewMountRegistry: LanzouWebViewMountRegistry,
    ): InstallerRuntime {
        val applicationContext = context.applicationContext
        val catalogRuntime = ReleaseCatalogRuntimeConfig
        val sourcePolicy = catalogRuntime.sourcePolicy
        val artifactCache = ArtifactCache(
            root = File(applicationContext.cacheDir, ARTIFACT_CACHE_DIRECTORY),
            publicDownloadRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            contentResolver = applicationContext.contentResolver,
        )
        val maintenanceSessionStore = MaintenanceSessionStore(
            file = File(applicationContext.filesDir, MAINTENANCE_SESSION_FILE),
            sourcePolicy = sourcePolicy,
        )
        val persistedMaintenanceSnapshot = maintenanceSessionStore.load()
        val apkMetadataReader = AndroidApkMetadataReader(applicationContext)
        // Discovery probes must fail quickly, while a retained installation lease
        // needs enough read time for ADB sync and the car's package manager.
        val discoveryDeviceConnectionFactory = DadbDeviceConnectionFactory(
            readTimeoutMillis = DISCOVERY_READ_TIMEOUT_MILLIS,
        )
        val installationDeviceConnectionFactory = DadbDeviceConnectionFactory(
            readTimeoutMillis = INSTALLATION_READ_TIMEOUT_MILLIS,
            installedApkCacheDirectory = File(applicationContext.cacheDir, INSTALLED_APK_VERIFICATION_DIRECTORY),
            installedApkMetadataReader = apkMetadataReader,
        )
        val distributionConfigAdapter = catalogRuntime.createDistributionConfigAdapter(applicationContext).withRevisionStore(
            FileCatalogRevisionStore(File(applicationContext.filesDir, CATALOG_REVISION_FILE)),
        )
        val folderSourceAdapter = LanzouFolderSourceAdapter(
            hostFactory = LanzouFolderWebViewHostFactory {
                AndroidLanzouWebViewHost(webViewMountRegistry, sourcePolicy)
            },
            sourcePolicy = sourcePolicy,
        )
        val lanzouSourceAdapter = LanzouWebSourceAdapter(
            hostFactory = LanzouWebViewHostFactory {
                AndroidLanzouWebViewHost(webViewMountRegistry, sourcePolicy)
            },
            sourcePolicy = sourcePolicy,
        )
        val artifactDownloader = ArtifactDownloader(
            transport = UrlConnectionArtifactTransport(sourcePolicy = sourcePolicy),
            cache = artifactCache,
            sourcePolicy = sourcePolicy,
        )
        val folderCatalogAdapter = FolderArtifactCatalogAdapter(
            configAdapter = distributionConfigAdapter,
        )

        val maintenanceController = MaintenanceController(
            artifactCache = artifactCache,
            diagnosticStore = MaintenanceDiagnosticStore(
                File(applicationContext.cacheDir, DIAGNOSTIC_CACHE_DIRECTORY),
            ),
            loadDistributionConfig = { folderCatalogAdapter.loadConfiguration() },
            loadDistributionSelection = { folderCatalogAdapter.loadSelection() },
            selfVersion = ArtifactVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong()),
        )

        val apkIconRepository = ApkIconRepository(
            context = applicationContext,
            artifactCache = artifactCache,
            metadataReader = apkMetadataReader,
            preferredTrack = catalogRuntime.artifactReleaseTrack,
        )

        return InstallerRuntime(
            session = InstallationSession(
                initialSnapshot = persistedMaintenanceSnapshot
                    ?: InstallationSessionSnapshot(InstallationSessionState.IDLE),
                sourcePolicy = sourcePolicy,
            ),
            createDiscoveryAdapter = { eventPort ->
                DeviceDiscoverySessionAdapter(
                    discovery = LanAdbDeviceDiscovery(
                        subnetProvider = JdkLocalIpv4SubnetProvider(),
                        transport = DadbDeviceTransport(connectionFactory = discoveryDeviceConnectionFactory),
                    ),
                    eventPort = eventPort,
                )
            },
            createConnectionAdapter = { eventPort ->
                DeviceConnectionSessionAdapter(installationDeviceConnectionFactory, eventPort)
            },
            loadCatalog = { eventPort ->
                ArtifactCatalogSessionAdapter(
                    eventPort = eventPort,
                    selectionLoader = { folderCatalogAdapter.loadSelection() },
                ).loadSelection()
            },
            loadInitialInventory = { snapshot, connection, eventPort ->
                // The first-install probe shares the same retained lease and
                // fixed package inventory reader as maintenance actions.
                maintenanceController.inspectInitialApplications(snapshot, connection, eventPort)
            },
            prepareInstallationBatch = { batch, eventPort ->
                when (val planResult = folderCatalogAdapter.buildPreparationPlan(batch)) {
                    is com.ninepointnine.helper.data.catalog.ArtifactPreparationPlanResult.Failure -> {
                        val failure = ArtifactFailure(
                            phase = ArtifactFailurePhase.CATALOG,
                            reasonCode = planResult.reasonCode,
                            retryable = planResult.retryable,
                        )
                        ArtifactPreparationResult.Failed(failure)
                    }

                    is com.ninepointnine.helper.data.catalog.ArtifactPreparationPlanResult.Ready -> {
                        val prepared = ArtifactPreparationCoordinator(
                            sourcePolicy = sourcePolicy,
                            lanzouSourceAdapter = lanzouSourceAdapter,
                            downloader = artifactDownloader,
                            archiveVerifier = ArchiveIdentityVerifier(),
                            archiveExtractor = ArtifactArchiveExtractor(),
                            identityVerifier = ArtifactIdentityVerifier(apkMetadataReader),
                            cache = artifactCache,
                            eventPort = eventPort,
                            folderSourceAdapter = folderSourceAdapter,
                            metadataReader = apkMetadataReader,
                        ).prepare(planResult.plan)
                        // The APK identity gate has completed before the car
                        // write starts, so icons can survive a partial device
                        // step without retaining an APK privately.
                        if (prepared is ArtifactPreparationResult.Prepared) {
                            apkIconRepository.persistIcons(prepared.artifacts.map {
                                com.ninepointnine.helper.domain.device.InstallableArtifact(
                                    manifest = it.manifest,
                                    apkFile = it.finalApk,
                                    declarations = it.declarations,
                                )
                            })
                        }
                        prepared
                    }
                }
            },
            cleanupArtifactWorkspace = { mode ->
                when (mode) {
                    ArtifactWorkspaceCleanupMode.COMPLETE -> artifactCache.clearPrivateCache()
                    ArtifactWorkspaceCleanupMode.PRESERVE_RESUMABLE_DOWNLOADS ->
                        artifactCache.clearPrivateApkCopies()
                }
            },
            executeDeviceInstallationWithBatch = object : InstallationBatchExecutor {
                override suspend fun execute(
                    connection: DeviceConnectionLease,
                    artifacts: List<PreparedArtifact>,
                    batchPlan: InstallationBatchPlan,
                    preparationFailures: Map<String, DeviceActionFailure>,
                    eventPort: InstallationSessionBoundary,
                ) {
                    DeviceInstallationCoordinator(eventPort).executeBatch(
                        connection = connection,
                        artifacts = artifacts,
                        batchPlan = batchPlan,
                        preparationFailures = preparationFailures,
                    )
                }
            },
            maintenanceController = maintenanceController,
            persistMaintenanceSnapshot = { snapshot ->
                withContext(Dispatchers.IO) {
                    check(maintenanceSessionStore.save(snapshot)) { "maintenance_baseline_rejected" }
                }
            },
            clearMaintenanceSnapshot = {
                withContext(Dispatchers.IO) {
                    check(maintenanceSessionStore.clear()) { "maintenance_baseline_clear_failed" }
                }
            },
            coroutineContext = Dispatchers.Main.immediate,
            apkIconRepository = apkIconRepository,
            selfUpdateInstaller = AndroidSelfUpdateInstaller(
                context = applicationContext,
                metadataReader = apkMetadataReader,
            ),
        )
    }

    private const val ARTIFACT_CACHE_DIRECTORY = "install-artifacts"
    private const val INSTALLED_APK_VERIFICATION_DIRECTORY = "install-artifacts/installed-verification"
    private const val DIAGNOSTIC_CACHE_DIRECTORY = "maintenance-diagnostics"
    private const val MAINTENANCE_SESSION_FILE = "maintenance-session.json"
    private const val CATALOG_REVISION_FILE = "android-catalog-revisions.properties"
    private const val DISCOVERY_READ_TIMEOUT_MILLIS = 900
    private const val INSTALLATION_READ_TIMEOUT_MILLIS = 60_000

}
