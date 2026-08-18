package com.tcrrry.helper.application

import android.content.Context
import com.tcrrry.helper.application.artifact.ArtifactCatalogSessionAdapter
import com.tcrrry.helper.application.artifact.ArtifactPreparationCoordinator
import com.tcrrry.helper.application.device.DeviceConnectionSessionAdapter
import com.tcrrry.helper.application.device.DeviceDiscoverySessionAdapter
import com.tcrrry.helper.data.artifact.AndroidApkMetadataReader
import com.tcrrry.helper.data.artifact.ArchiveIdentityVerifier
import com.tcrrry.helper.data.artifact.ArtifactArchiveExtractor
import com.tcrrry.helper.data.artifact.ArtifactIdentityVerifier
import com.tcrrry.helper.data.catalog.CloudReleaseCatalogAdapter
import com.tcrrry.helper.data.catalog.JcaCatalogSignatureVerifier
import com.tcrrry.helper.data.catalog.TrustedCatalogKeyResolver
import com.tcrrry.helper.data.catalog.UnavailableReleaseCatalogTransport
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
        val sourcePolicy = ReleaseSourcePolicy()
        val artifactCache = ArtifactCache(File(applicationContext.cacheDir, ARTIFACT_CACHE_DIRECTORY))
        val deviceConnectionFactory = DadbDeviceConnectionFactory()
        val catalogAdapter = CloudReleaseCatalogAdapter(
            transport = UnavailableReleaseCatalogTransport(),
            signatureVerifier = JcaCatalogSignatureVerifier(
                TrustedCatalogKeyResolver { null },
            ),
            sourcePolicy = sourcePolicy,
        )

        return InstallerRuntime(
            session = InstallationSession(),
            createDiscoveryAdapter = { eventPort ->
                DeviceDiscoverySessionAdapter(
                    discovery = LanAdbDeviceDiscovery(
                        subnetProvider = JdkLocalIpv4SubnetProvider(),
                        transport = DadbDeviceTransport(connectionFactory = deviceConnectionFactory),
                    ),
                    eventPort = eventPort,
                )
            },
            createConnectionAdapter = { eventPort ->
                DeviceConnectionSessionAdapter(deviceConnectionFactory, eventPort)
            },
            loadCatalog = { eventPort ->
                ArtifactCatalogSessionAdapter(catalogAdapter, eventPort).load()
            },
            prepareArtifacts = { manifests, eventPort ->
                ArtifactPreparationCoordinator(
                    sourcePolicy = sourcePolicy,
                    lanzouSourceAdapter = LanzouWebSourceAdapter(
                        hostFactory = LanzouWebViewHostFactory {
                            AndroidLanzouWebViewHost(applicationContext)
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
                        AndroidApkMetadataReader(applicationContext),
                    ),
                    cache = artifactCache,
                    eventPort = eventPort,
                ).prepare(manifests)
            },
            coroutineContext = Dispatchers.Main.immediate,
        )
    }

    private const val ARTIFACT_CACHE_DIRECTORY = "install-artifacts"
}
