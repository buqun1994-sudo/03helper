package com.tcrrry.helper.application

import android.content.Context
import com.tcrrry.helper.application.artifact.ArtifactCatalogSessionAdapter
import com.tcrrry.helper.application.artifact.ArtifactPreparationCoordinator
import com.tcrrry.helper.application.artifact.InstallerCatalogLoader
import com.tcrrry.helper.application.device.DeviceConnectionSessionAdapter
import com.tcrrry.helper.application.device.DeviceDiscoverySessionAdapter
import com.tcrrry.helper.application.device.DeviceInstallationCoordinator
import com.tcrrry.helper.application.maintenance.MaintenanceController
import com.tcrrry.helper.application.maintenance.MaintenanceDiagnosticStore
import com.tcrrry.helper.application.maintenance.MaintenanceSessionStore
import com.tcrrry.helper.data.artifact.AndroidApkMetadataReader
import com.tcrrry.helper.data.artifact.ArchiveIdentityVerifier
import com.tcrrry.helper.data.artifact.ArtifactArchiveExtractor
import com.tcrrry.helper.data.artifact.ArtifactIdentityVerifier
import com.tcrrry.helper.data.catalog.FolderArtifactCatalogAdapter
import com.tcrrry.helper.data.device.DadbDeviceTransport
import com.tcrrry.helper.data.device.DadbDeviceConnectionFactory
import com.tcrrry.helper.data.device.JdkLocalIpv4SubnetProvider
import com.tcrrry.helper.data.device.LanAdbDeviceDiscovery
import com.tcrrry.helper.data.download.ArtifactCache
import com.tcrrry.helper.data.download.ArtifactDownloader
import com.tcrrry.helper.data.download.DynamicArtifactDownloader
import com.tcrrry.helper.data.download.UrlConnectionArtifactTransport
import com.tcrrry.helper.data.web.AndroidLanzouWebViewHost
import com.tcrrry.helper.data.web.LanzouFolderSourceAdapter
import com.tcrrry.helper.data.web.LanzouFolderWebViewHostFactory
import com.tcrrry.helper.data.web.LanzouWebSourceAdapter
import com.tcrrry.helper.data.web.LanzouWebViewHostFactory
import com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy
import com.tcrrry.helper.domain.session.InstallationSession
import com.tcrrry.helper.domain.session.InstallationSessionSnapshot
import com.tcrrry.helper.domain.session.InstallationSessionState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Production composition root. Missing Cloud trust data remains explicitly unavailable. */
object ProductionInstallerRuntimeFactory {
    fun create(context: Context): InstallerRuntime {
        val applicationContext = context.applicationContext
        val catalogRuntime = ReleaseCatalogRuntimeConfig
        val sourcePolicy = catalogRuntime.sourcePolicy
        val artifactCache = ArtifactCache(File(applicationContext.cacheDir, ARTIFACT_CACHE_DIRECTORY))
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
        val distributionConfigAdapter = catalogRuntime.createDistributionConfigAdapter(applicationContext)
        val folderCatalogAdapter = FolderArtifactCatalogAdapter(
            configAdapter = distributionConfigAdapter,
            folderSourceAdapter = LanzouFolderSourceAdapter(
                hostFactory = LanzouFolderWebViewHostFactory {
                    AndroidLanzouWebViewHost(applicationContext, sourcePolicy)
                },
                sourcePolicy = sourcePolicy,
            ),
            lanzouSourceAdapter = LanzouWebSourceAdapter(
                hostFactory = LanzouWebViewHostFactory {
                    AndroidLanzouWebViewHost(applicationContext, sourcePolicy)
                },
                sourcePolicy = sourcePolicy,
            ),
            downloader = DynamicArtifactDownloader(
                transport = UrlConnectionArtifactTransport(sourcePolicy = sourcePolicy),
                sourcePolicy = sourcePolicy,
            ),
            metadataReader = apkMetadataReader,
            sourcePolicy = sourcePolicy,
            artifactCache = artifactCache,
            workingDirectory = File(applicationContext.cacheDir, DISTRIBUTION_CATALOG_DIRECTORY),
        )
        val catalogLoader = InstallerCatalogLoader { folderCatalogAdapter.load() }

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
                ArtifactCatalogSessionAdapter(catalogLoader, eventPort).load()
            },
            prepareArtifactsWithResult = { manifests, eventPort ->
                ArtifactPreparationCoordinator(
                    sourcePolicy = sourcePolicy,
                    lanzouSourceAdapter = LanzouWebSourceAdapter(
                        hostFactory = LanzouWebViewHostFactory {
                            AndroidLanzouWebViewHost(applicationContext, sourcePolicy)
                        },
                        sourcePolicy = sourcePolicy,
                    ),
                    downloader = ArtifactDownloader(
                        transport = UrlConnectionArtifactTransport(sourcePolicy = sourcePolicy),
                        cache = artifactCache,
                        sourcePolicy = sourcePolicy,
                    ),
                    archiveVerifier = ArchiveIdentityVerifier(),
                    archiveExtractor = ArtifactArchiveExtractor(),
                    identityVerifier = ArtifactIdentityVerifier(
                        apkMetadataReader,
                    ),
                    cache = artifactCache,
                    eventPort = eventPort,
                ).prepare(manifests)
            },
            executeDeviceInstallation = { connection, artifacts, eventPort ->
                DeviceInstallationCoordinator(eventPort).execute(connection, artifacts)
                Unit
            },
            maintenanceController = MaintenanceController(
                artifactCache = artifactCache,
                diagnosticStore = MaintenanceDiagnosticStore(
                    File(applicationContext.cacheDir, DIAGNOSTIC_CACHE_DIRECTORY),
                ),
                loadCatalog = { catalogLoader.load() },
            ),
            persistMaintenanceSnapshot = { snapshot ->
                withContext(Dispatchers.IO) { maintenanceSessionStore.save(snapshot) }
            },
            coroutineContext = Dispatchers.Main.immediate,
        )
    }

    private const val ARTIFACT_CACHE_DIRECTORY = "install-artifacts"
    private const val INSTALLED_APK_VERIFICATION_DIRECTORY = "install-artifacts/installed-verification"
    private const val DISTRIBUTION_CATALOG_DIRECTORY = "distribution-catalog"
    private const val DIAGNOSTIC_CACHE_DIRECTORY = "maintenance-diagnostics"
    private const val MAINTENANCE_SESSION_FILE = "maintenance-session.json"
    private const val DISCOVERY_READ_TIMEOUT_MILLIS = 900
    private const val INSTALLATION_READ_TIMEOUT_MILLIS = 60_000

}
