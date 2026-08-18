package com.tcrrry.helper.application

import com.tcrrry.helper.application.device.DeviceDiscoverySessionAdapter
import com.tcrrry.helper.application.device.DeviceConnectionSessionAdapter
import com.tcrrry.helper.application.session.InstallationSessionEventPort
import com.tcrrry.helper.domain.artifact.ApkExtractionEvidence
import com.tcrrry.helper.domain.artifact.ArchiveDownloadEvidence
import com.tcrrry.helper.domain.artifact.ArchiveVerificationEvidence
import com.tcrrry.helper.domain.artifact.ArtifactManifest
import com.tcrrry.helper.domain.artifact.ArtifactSource
import com.tcrrry.helper.domain.artifact.ArtifactSourceKind
import com.tcrrry.helper.domain.artifact.ArtifactVerification
import com.tcrrry.helper.domain.artifact.ArtifactVersion
import com.tcrrry.helper.domain.artifact.CompatibilityRange
import com.tcrrry.helper.domain.artifact.SourceSelectionEvidence
import com.tcrrry.helper.domain.device.ConnectedDevice
import com.tcrrry.helper.domain.device.DeviceCapability
import com.tcrrry.helper.domain.device.DeviceConnectionAttempt
import com.tcrrry.helper.domain.device.DeviceConnectionCheck
import com.tcrrry.helper.domain.device.DeviceConnectionFactory
import com.tcrrry.helper.domain.device.DeviceConnectionLease
import com.tcrrry.helper.domain.device.DeviceDiscovery
import com.tcrrry.helper.domain.device.DeviceDiscoveryResult
import com.tcrrry.helper.domain.device.DeviceEndpoint
import com.tcrrry.helper.domain.device.DeviceIdentity
import com.tcrrry.helper.domain.session.ComponentCheck
import com.tcrrry.helper.domain.session.DeviceConnectionStatus
import com.tcrrry.helper.domain.session.InstallationSession
import com.tcrrry.helper.domain.session.InstallationSessionCommand
import com.tcrrry.helper.domain.session.InstallationSessionEvent
import com.tcrrry.helper.domain.session.InstallationSessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InstallerRuntimeTest {
    @Test
    fun `foreground entry starts discovery from the initial connection page`() = runTest {
        val runtime = InstallerRuntime(
            session = InstallationSession(),
            createDiscoveryAdapter = { port -> DeviceDiscoverySessionAdapter(fakeDiscovery(), port) },
            createConnectionAdapter = { port -> fakeConnectionAdapter(port) },
            loadCatalog = { error("catalog must not start before a device is selected") },
            prepareArtifacts = { _, _ -> error("artifact preparation must not start") },
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.onForeground()
        advanceUntilIdle()

        val firstForegroundSnapshot = runtime.session.currentSnapshot()
        assertEquals(InstallationSessionState.DISCOVERING, firstForegroundSnapshot.state)
        assertEquals("adb:vehicle-1", firstForegroundSnapshot.discoveredDevices.single().id)

        runtime.onForeground()
        assertEquals(firstForegroundSnapshot.sessionId, runtime.session.currentSnapshot().sessionId)

        runtime.close()
    }

    @Test
    fun `runtime composes discovery catalog and artifact preparation through one session generation`() = runTest {
        val manifests = listOf(manifest("lyrics"), manifest("desktop"))
        val preparedIds = mutableListOf<String>()
        val runtime = InstallerRuntime(
            session = InstallationSession(),
            createDiscoveryAdapter = { port -> DeviceDiscoverySessionAdapter(fakeDiscovery(), port) },
            createConnectionAdapter = { port -> fakeConnectionAdapter(port) },
            loadCatalog = { port ->
                port.emit(
                    InstallationSessionEvent.CatalogResolved(
                        catalogVersion = "android-v1",
                        keyId = "test-key",
                        signatureAlgorithm = "SHA256withECDSA",
                        manifests = manifests,
                    ),
                )
            },
            prepareArtifacts = { selected, port ->
                preparedIds += selected.map { it.componentId }
                port.emit(
                    InstallationSessionEvent.SourceResolved(
                        sourceId = "fixed-release-source-policy",
                        selections = selected.map {
                            SourceSelectionEvidence(it.componentId, ArtifactSourceKind.LANZOU_SHARE)
                        },
                    ),
                )
                port.emit(
                    InstallationSessionEvent.ArchiveDownloaded(
                        sizeBytes = selected.sumOf { it.archiveSizeBytes },
                        sha256 = "batch",
                        archives = selected.map {
                            ArchiveDownloadEvidence(it.componentId, it.archiveSizeBytes, it.archiveSha256)
                        },
                    ),
                )
                port.emit(
                    InstallationSessionEvent.ArchiveVerified(
                        verified = true,
                        verifications = selected.map {
                            ArchiveVerificationEvidence(it.componentId, it.archiveSizeBytes, it.archiveSha256)
                        },
                    ),
                )
                port.emit(
                    InstallationSessionEvent.ApkExtracted(
                        entryName = "batch.apk",
                        sizeBytes = selected.sumOf { it.apkSizeBytes },
                        sha256 = "batch",
                        extractions = selected.map {
                            ApkExtractionEvidence(it.componentId, it.apkEntryName, it.apkSizeBytes, it.apkSha256)
                        },
                    ),
                )
                port.emit(
                    InstallationSessionEvent.ArtifactsVerified(
                        checks = selected.map { ComponentCheck(it.componentId, true) },
                        verifications = selected.map { manifest ->
                            ArtifactVerification(
                                componentId = manifest.componentId,
                                sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                                archiveSizeBytes = manifest.archiveSizeBytes,
                                archiveSha256 = manifest.archiveSha256,
                                apkSizeBytes = manifest.apkSizeBytes,
                                apkSha256 = manifest.apkSha256,
                                packageName = manifest.packageName,
                                apkVersion = manifest.apkVersion,
                                certificateSha256 = manifest.certificateSha256,
                                archiveDeleted = true,
                            )
                        },
                        archiveDeleted = true,
                    ),
                )
            },
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.dispatch(InstallationSessionCommand.StartDiscovery)
        advanceUntilIdle()
        val discoverySnapshot = runtime.session.currentSnapshot()
        assertEquals(InstallationSessionState.DISCOVERING, discoverySnapshot.state)
        val deviceId = discoverySnapshot.discoveredDevices.single().id

        runtime.dispatch(InstallationSessionCommand.SelectDevice(deviceId))
        advanceUntilIdle()
        assertEquals(InstallationSessionState.CONNECTED, runtime.session.currentSnapshot().state)
        assertEquals(manifests.map { it.componentId }.toSet(), runtime.session.currentSnapshot().artifactManifests.map { it.componentId }.toSet())

        runtime.dispatch(InstallationSessionCommand.StartInstallation)
        advanceUntilIdle()
        val prepared = runtime.session.currentSnapshot()
        assertEquals(listOf("lyrics", "desktop"), preparedIds)
        assertEquals(InstallationSessionState.VERIFYING_ARTIFACTS, prepared.state)
        assertTrue(prepared.lastEventSequence >= 6L)

        runtime.close()
    }

    @Test
    fun `runtime keeps confirmed device connected when catalog is unavailable`() = runTest {
        val runtime = InstallerRuntime(
            session = InstallationSession(),
            createDiscoveryAdapter = { port -> DeviceDiscoverySessionAdapter(fakeDiscovery(), port) },
            createConnectionAdapter = { port -> fakeConnectionAdapter(port) },
            loadCatalog = { port ->
                port.emit(InstallationSessionEvent.CatalogFailed("catalog_android_profile_missing"))
            },
            prepareArtifacts = { _, _ -> error("artifact preparation must not start") },
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.dispatch(InstallationSessionCommand.StartDiscovery)
        advanceUntilIdle()
        val deviceId = runtime.session.currentSnapshot().discoveredDevices.single().id

        runtime.dispatch(InstallationSessionCommand.SelectDevice(deviceId))
        advanceUntilIdle()

        val snapshot = runtime.session.currentSnapshot()
        assertEquals(InstallationSessionState.CONNECTED, snapshot.state)
        assertEquals("catalog_android_profile_missing", snapshot.failure?.reasonCode)
        assertEquals(null, snapshot.checkpoint)

        runtime.close()
    }

    @Test
    fun `runtime waits for the selected connection and retains the lease until close`() = runTest {
        val connectionGate = CompletableDeferred<DeviceConnectionAttempt>()
        val lease = TrackingConnectionLease()
        var catalogLoads = 0
        val runtime = InstallerRuntime(
            session = InstallationSession(),
            createDiscoveryAdapter = { port -> DeviceDiscoverySessionAdapter(fakeDiscovery(), port) },
            createConnectionAdapter = { port ->
                DeviceConnectionSessionAdapter(
                    connectionFactory = DeviceConnectionFactory { connectionGate.await() },
                    eventPort = port,
                )
            },
            loadCatalog = { port ->
                catalogLoads += 1
                port.emit(InstallationSessionEvent.CatalogFailed("catalog_android_profile_missing"))
            },
            prepareArtifacts = { _, _ -> error("artifact preparation must not start") },
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.dispatch(InstallationSessionCommand.StartDiscovery)
        advanceUntilIdle()
        runtime.dispatch(
            InstallationSessionCommand.SelectDevice(
                runtime.session.currentSnapshot().discoveredDevices.single().id,
            ),
        )
        assertEquals(InstallationSessionState.CONNECTING, runtime.session.currentSnapshot().state)
        assertEquals(0, catalogLoads)

        connectionGate.complete(DeviceConnectionAttempt.Connected(lease))
        advanceUntilIdle()

        assertEquals(InstallationSessionState.CONNECTED, runtime.session.currentSnapshot().state)
        assertEquals(1, catalogLoads)
        assertEquals("catalog_android_profile_missing", runtime.session.currentSnapshot().failure?.reasonCode)
        assertTrue(!lease.closed)

        runtime.close()
        assertTrue(lease.closed)
    }

    private class TrackingConnectionLease : DeviceConnectionLease {
        override val device = ConnectedDevice(
            endpoint = DeviceEndpoint("192.168.1.203"),
            identity = DeviceIdentity("adb:vehicle-1", "S56_HQX", 28),
            capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
        )
        var closed: Boolean = false

        override suspend fun check(): DeviceConnectionCheck = DeviceConnectionCheck(true)

        override fun close() {
            closed = true
        }
    }

    private fun fakeDiscovery(): DeviceDiscovery = object : DeviceDiscovery {
        override suspend fun discover(onDevice: suspend (ConnectedDevice) -> Unit): DeviceDiscoveryResult {
            onDevice(
                ConnectedDevice(
                    endpoint = DeviceEndpoint("192.168.1.203"),
                    identity = DeviceIdentity("adb:vehicle-1", "S56_HQX", 28),
                    capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
                ),
            )
            return DeviceDiscoveryResult(scannedCount = 1, confirmedCount = 1)
        }

        override fun cancel() = Unit
    }

    private fun fakeConnectionAdapter(eventPort: InstallationSessionEventPort): DeviceConnectionSessionAdapter =
        DeviceConnectionSessionAdapter(
            connectionFactory = DeviceConnectionFactory {
                DeviceConnectionAttempt.Connected(
                    object : DeviceConnectionLease {
                        override val device = ConnectedDevice(
                            endpoint = DeviceEndpoint("192.168.1.203"),
                            identity = DeviceIdentity("adb:vehicle-1", "S56_HQX", 28),
                            capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
                        )

                        override suspend fun check(): DeviceConnectionCheck = DeviceConnectionCheck(true)

                        override fun close() = Unit
                    },
                )
            },
            eventPort = eventPort,
        )

    private fun manifest(componentId: String): ArtifactManifest = ArtifactManifest(
        schemaVersion = 1,
        componentId = componentId,
        displayName = componentId,
        required = true,
        version = ArtifactVersion("1.0.0", 1),
        compatibility = CompatibilityRange(minAndroidSdk = 26, maxAndroidSdk = 30),
        archiveFileName = "$componentId.zip",
        archiveSizeBytes = 100L,
        archiveSha256 = "11".repeat(32),
        apkEntryName = "$componentId.apk",
        apkSizeBytes = 50L,
        apkSha256 = "22".repeat(32),
        packageName = "com.example.$componentId",
        apkVersion = ArtifactVersion("1.0.0", 1),
        certificateSha256 = "33".repeat(32),
        sources = listOf(
            ArtifactSource(ArtifactSourceKind.LANZOU_SHARE, "https://wwatl.lanzouw.com/i$componentId"),
            ArtifactSource(ArtifactSourceKind.R2, "https://assets.r2.dev/$componentId.zip"),
            ArtifactSource(ArtifactSourceKind.GITHUB_RELEASES, "https://github.com/example/repo/releases/download/v1/$componentId.zip"),
        ),
    )
}
