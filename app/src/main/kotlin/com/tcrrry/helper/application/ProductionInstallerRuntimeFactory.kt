package com.tcrrry.helper.application

import android.content.Context
import com.tcrrry.helper.application.artifact.ArtifactCatalogSessionAdapter
import com.tcrrry.helper.application.artifact.ArtifactPreparationCoordinator
import com.tcrrry.helper.application.device.DeviceConnectionSessionAdapter
import com.tcrrry.helper.application.device.DeviceDiscoverySessionAdapter
import com.tcrrry.helper.application.device.DeviceInstallationCoordinator
import com.tcrrry.helper.data.artifact.AndroidApkMetadataReader
import com.tcrrry.helper.data.artifact.ArchiveIdentityVerifier
import com.tcrrry.helper.data.artifact.ArtifactArchiveExtractor
import com.tcrrry.helper.data.artifact.ArtifactIdentityVerifier
import com.tcrrry.helper.data.catalog.CloudReleaseCatalogAdapter
import com.tcrrry.helper.data.device.DadbDeviceTransport
import com.tcrrry.helper.data.device.DadbDeviceConnectionFactory
import com.tcrrry.helper.data.device.JdkLocalIpv4SubnetProvider
import com.tcrrry.helper.data.device.LanAdbDeviceDiscovery
import com.tcrrry.helper.data.download.ArtifactCache
import com.tcrrry.helper.data.download.ArtifactDownloader
import com.tcrrry.helper.data.download.UrlConnectionArtifactTransport
import com.tcrrry.helper.data.web.AndroidLanzouWebViewHost
import com.tcrrry.helper.data.web.LanzouWebSourceAdapter
import com.tcrrry.helper.data.web.LanzouWebViewHostFactory
import com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy
import com.tcrrry.helper.domain.session.InstallationSession
import java.io.File
import kotlinx.coroutines.Dispatchers

/** Production composition root. Missing Cloud trust data remains explicitly unavailable. */
object ProductionInstallerRuntimeFactory {
    fun create(context: Context): InstallerRuntime {
        val applicationContext = context.applicationContext
        val catalogRuntime = ReleaseCatalogRuntimeConfig
        val sourcePolicy = catalogRuntime.sourcePolicy
        val artifactCache = ArtifactCache(File(applicationContext.cacheDir, ARTIFACT_CACHE_DIRECTORY))
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
        val catalogAdapter = catalogRuntime.createCatalogAdapter(applicationContext)

        return InstallerRuntime(
            session = InstallationSession(
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
                ArtifactCatalogSessionAdapter(catalogAdapter, eventPort).load()
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
            coroutineContext = Dispatchers.Main.immediate,
        )
    }

    private const val ARTIFACT_CACHE_DIRECTORY = "install-artifacts"
    private const val INSTALLED_APK_VERIFICATION_DIRECTORY = "install-artifacts/installed-verification"
    private const val DISCOVERY_READ_TIMEOUT_MILLIS = 900
    private const val INSTALLATION_READ_TIMEOUT_MILLIS = 60_000

}
