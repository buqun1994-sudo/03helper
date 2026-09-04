package com.ninepointnine.helper.application

import com.ninepointnine.helper.application.artifact.ArtifactPreparationResult
import com.ninepointnine.helper.application.artifact.PreparedArtifact
import com.ninepointnine.helper.application.device.DeviceDiscoverySessionAdapter
import com.ninepointnine.helper.application.device.DeviceConnectionSessionAdapter
import com.ninepointnine.helper.application.device.toDeviceSummary
import com.ninepointnine.helper.application.maintenance.MaintenanceController
import com.ninepointnine.helper.application.maintenance.MaintenanceDiagnosticStore
import com.ninepointnine.helper.application.session.InstallationSessionBoundary
import com.ninepointnine.helper.application.session.InstallationSessionEventPort
import com.ninepointnine.helper.data.catalog.DistributionConfigLoadResult
import com.ninepointnine.helper.data.catalog.InstallerComponentSource
import com.ninepointnine.helper.data.catalog.InstallerDistributionConfig
import com.ninepointnine.helper.domain.artifact.ApkExtractionEvidence
import com.ninepointnine.helper.domain.artifact.ArchiveDownloadEvidence
import com.ninepointnine.helper.domain.artifact.ArchiveVerificationEvidence
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactFailure
import com.ninepointnine.helper.domain.artifact.ArtifactFailurePhase
import com.ninepointnine.helper.domain.artifact.ArtifactSource
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ArtifactVerification
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.CompatibilityRange
import com.ninepointnine.helper.domain.artifact.InstallerSelfIdentity
import com.ninepointnine.helper.domain.artifact.SourceSelectionEvidence
import com.ninepointnine.helper.domain.artifact.toComponentDescriptor
import com.ninepointnine.helper.domain.device.ConnectedDevice
import com.ninepointnine.helper.domain.device.AdbCommandGateway
import com.ninepointnine.helper.domain.device.AuthorizationPlan
import com.ninepointnine.helper.domain.device.DeviceCapability
import com.ninepointnine.helper.domain.device.DeviceActionConnectionLease
import com.ninepointnine.helper.domain.device.DeviceConnectionAttempt
import com.ninepointnine.helper.domain.device.DeviceConnectionCheck
import com.ninepointnine.helper.domain.device.DeviceConnectionFactory
import com.ninepointnine.helper.domain.device.DeviceConnectionLease
import com.ninepointnine.helper.domain.device.DeviceActionFailure
import com.ninepointnine.helper.domain.device.DeviceInstallResult
import com.ninepointnine.helper.domain.device.DeviceDiscovery
import com.ninepointnine.helper.domain.device.DeviceDiscoveryResult
import com.ninepointnine.helper.domain.device.DeviceEndpoint
import com.ninepointnine.helper.domain.device.DeviceIdentity
import com.ninepointnine.helper.domain.device.DeviceShortcut
import com.ninepointnine.helper.domain.device.DeviceShortcutResult
import com.ninepointnine.helper.domain.device.InstallableArtifact
import com.ninepointnine.helper.domain.device.InstalledArtifactEvidence
import com.ninepointnine.helper.domain.device.MaintenanceCommandGateway
import com.ninepointnine.helper.domain.device.MaintenanceAuthorizationResult
import com.ninepointnine.helper.domain.device.MaintenanceDeviceResult
import com.ninepointnine.helper.domain.device.ManagedApplicationDetailsProbeResult
import com.ninepointnine.helper.domain.device.ManagedApplicationProbe
import com.ninepointnine.helper.domain.device.ManagedApplicationsResult
import com.ninepointnine.helper.domain.device.ManagedComponent
import com.ninepointnine.helper.domain.session.ComponentDescriptor
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.session.InstallationSession
import com.ninepointnine.helper.domain.session.InstallationBatchPlan
import com.ninepointnine.helper.domain.session.InstallationBatchReceipt
import com.ninepointnine.helper.domain.session.InstallationComponentReceipt
import com.ninepointnine.helper.domain.session.InstallationSessionCommand
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.domain.session.InstallationSessionState
import com.ninepointnine.helper.domain.session.InstallationStrategy
import com.ninepointnine.helper.domain.session.InstallationFlow
import com.ninepointnine.helper.domain.session.InstallationStageReceipt
import com.ninepointnine.helper.domain.session.InstallationStageReceiptStatus
import com.ninepointnine.helper.domain.session.AuthorizationStageReceipt
import com.ninepointnine.helper.domain.session.AuthorizationStageReceiptStatus
import com.ninepointnine.helper.domain.session.AvailabilityStageReceipt
import com.ninepointnine.helper.domain.session.AvailabilityStageReceiptStatus
import com.ninepointnine.helper.domain.session.MaintenanceInstallationOption
import com.ninepointnine.helper.domain.session.MaintenanceInstallationSelection
import com.ninepointnine.helper.domain.session.MaintenanceInventoryState
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.domain.session.MaintenanceApplicationActionId
import com.ninepointnine.helper.domain.session.MaintenanceSnapshot
import com.ninepointnine.helper.domain.session.MaintenanceUpdateState
import com.ninepointnine.helper.domain.session.MaintenanceUpdateStatus
import com.ninepointnine.helper.domain.session.ManagedApplicationStatus
import com.ninepointnine.helper.data.download.ArtifactCache
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InstallerRuntimeTest {
    @Test
    fun `replacement device work waits until the cancelled owner fully exits`() = runTest {
        val owner = SerializedDeviceWorkOwner(this)
        val firstStarted = CompletableDeferred<Unit>()
        val firstExiting = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        owner.replace {
            firstStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    firstExiting.complete(Unit)
                    releaseFirst.await()
                }
            }
        }
        firstStarted.await()

        owner.replace { secondStarted.complete(Unit) }
        firstExiting.await()
        assertFalse(secondStarted.isCompleted)

        releaseFirst.complete(Unit)
        advanceUntilIdle()
        assertTrue(secondStarted.isCompleted)
    }

    @Test
    fun `foreground entry starts discovery from the initial connection page`() = runTest {
        var discoveryStarts = 0
        val runtime = InstallerRuntime(
            session = InstallationSession(),
            createDiscoveryAdapter = { port ->
                discoveryStarts += 1
                DeviceDiscoverySessionAdapter(fakeDiscovery(), port)
            },
            createConnectionAdapter = { port -> fakeConnectionAdapter(port) },
            loadCatalog = { error("catalog must not start before a device is selected") },
            prepareInstallationBatch = { _, _ -> error("artifact preparation must not start") },
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.onForeground()
        advanceUntilIdle()

        val firstForegroundSnapshot = runtime.session.currentSnapshot()
        assertEquals(InstallationSessionState.DISCOVERING, firstForegroundSnapshot.state)
        assertEquals("adb:vehicle-1", firstForegroundSnapshot.discoveredDevices.single().id)
        assertEquals(1, discoveryStarts)

        runtime.onForeground()
        assertEquals(firstForegroundSnapshot.sessionId, runtime.session.currentSnapshot().sessionId)
        assertEquals(1, discoveryStarts)

        runtime.close()
    }

    @Test
    fun `initial inventory failure waits for an explicit retry`() = runTest {
        var inventoryReads = 0
        var catalogLoads = 0
        val runtime = InstallerRuntime(
            session = InstallationSession(),
            createDiscoveryAdapter = { port -> DeviceDiscoverySessionAdapter(fakeDiscovery(), port) },
            createConnectionAdapter = { port -> fakeConnectionAdapter(port) },
            loadCatalog = {
                catalogLoads += 1
            },
            loadInitialInventory = { _, _, port ->
                inventoryReads += 1
                port.emit(
                    InstallationSessionEvent.InitialInstalledApplicationsFailed(
                        reasonCode = "initial_inventory_package_inventory_failed",
                    ),
                )
            },
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.onForeground()
        advanceUntilIdle()
        runtime.dispatch(InstallationSessionCommand.SelectDevice("adb:vehicle-1"))
        advanceUntilIdle()

        assertEquals(InstallationSessionState.CONNECTED, runtime.session.currentSnapshot().state)
        assertEquals(1, inventoryReads)
        assertEquals(0, catalogLoads)

        advanceUntilIdle()
        assertEquals(1, inventoryReads)

        runtime.dispatch(InstallationSessionCommand.RetryInstallation)
        advanceUntilIdle()

        assertEquals(2, inventoryReads)
        assertEquals(0, catalogLoads)
        assertEquals(
            "initial_inventory_package_inventory_failed",
            runtime.session.currentSnapshot().failure?.reasonCode,
        )
        runtime.close()
    }

    @Test
    fun `foreground reestablishes a connected checkpoint instead of resetting the install flow`() = runTest {
        val seed = InstallationSession()
        seed.dispatch(InstallationSessionCommand.StartDiscovery)
        seed.dispatchEvent(InstallationSessionEvent.DeviceDiscovered(fakeVehicle().toDeviceSummary()))
        seed.dispatch(InstallationSessionCommand.SelectDevice("adb:vehicle-1"))
        val connecting = seed.currentSnapshot()
        seed.dispatchEvent(
            InstallationSessionEvent.DeviceConnectionConfirmed(fakeVehicle().toDeviceSummary()),
            sessionId = connecting.sessionId,
            sequence = connecting.lastEventSequence + 1L,
        )
        val runtimeSession = InstallationSession(seed.currentSnapshot())
        var catalogLoads = 0
        val runtime = InstallerRuntime(
            session = runtimeSession,
            createDiscoveryAdapter = { port -> DeviceDiscoverySessionAdapter(fakeDiscovery(), port) },
            createConnectionAdapter = { port -> fakeConnectionAdapter(port) },
            loadCatalog = { port ->
                catalogLoads += 1
                port.emit(InstallationSessionEvent.CatalogFailed("catalog_not_ready"))
            },
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.onForeground()
        advanceUntilIdle()

        val restored = runtime.session.currentSnapshot()
        assertEquals(InstallationSessionState.CONNECTED, restored.state)
        assertEquals(DeviceConnectionStatus.CONFIRMED, restored.device?.connectionStatus)
        assertEquals(false, restored.installationReconnectPending)
        assertEquals(1, catalogLoads)
        assertEquals("catalog_not_ready", restored.failure?.reasonCode)

        runtime.close()
        seed.close()
    }

    @Test
    fun `foreground automatically reconnects a disconnected maintenance session`() = runTest {
        val runtime = InstallerRuntime(
            session = InstallationSession(
                initialSnapshot = InstallationSessionSnapshot(
                    state = InstallationSessionState.MAINTENANCE,
                    device = fakeVehicle().toDeviceSummary().copy(
                        connectionStatus = DeviceConnectionStatus.DISCONNECTED,
                    ),
                ),
            ),
            createDiscoveryAdapter = { port -> DeviceDiscoverySessionAdapter(fakeDiscovery(), port) },
            createConnectionAdapter = { port -> fakeConnectionAdapter(port) },
            loadCatalog = {},
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.onForeground()
        advanceUntilIdle()

        assertEquals(InstallationSessionState.MAINTENANCE, runtime.session.currentSnapshot().state)
        assertEquals(
            DeviceConnectionStatus.CONFIRMED,
            runtime.session.currentSnapshot().device?.connectionStatus,
        )
        runtime.close()
    }

    @Test
    fun `manual maintenance disconnect does not start a reconnect generation`() = runTest {
        var discoveryPasses = 0
        var connectionAttempts = 0
        val runtime = InstallerRuntime(
            session = InstallationSession(
                initialSnapshot = InstallationSessionSnapshot(
                    state = InstallationSessionState.MAINTENANCE,
                    device = fakeVehicle().toDeviceSummary().copy(
                        connectionStatus = DeviceConnectionStatus.DISCONNECTED,
                    ),
                ),
            ),
            createDiscoveryAdapter = { port ->
                discoveryPasses += 1
                DeviceDiscoverySessionAdapter(fakeDiscovery(), port)
            },
            createConnectionAdapter = { port ->
                connectionAttempts += 1
                fakeConnectionAdapter(port)
            },
            loadCatalog = {},
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.onForeground()
        advanceUntilIdle()
        assertEquals(DeviceConnectionStatus.CONFIRMED, runtime.session.currentSnapshot().device?.connectionStatus)
        val discoveryBeforeDisconnect = discoveryPasses
        val connectionsBeforeDisconnect = connectionAttempts

        runtime.dispatch(InstallationSessionCommand.DisconnectDevice)
        advanceUntilIdle()

        val disconnected = runtime.session.currentSnapshot()
        assertEquals(InstallationSessionState.MAINTENANCE, disconnected.state)
        assertEquals(DeviceConnectionStatus.DISCONNECTED, disconnected.device?.connectionStatus)
        assertEquals(discoveryBeforeDisconnect, discoveryPasses)
        assertEquals(connectionsBeforeDisconnect, connectionAttempts)
        runtime.close()
    }

    @Test
    fun `known maintenance reconnect falls back to one bounded discovery pass`() = runTest {
        var discoveryPasses = 0
        var connectionAttempts = 0
        val runtime = InstallerRuntime(
            session = InstallationSession(
                initialSnapshot = InstallationSessionSnapshot(
                    state = InstallationSessionState.MAINTENANCE,
                    device = fakeVehicle().toDeviceSummary().copy(
                        connectionStatus = DeviceConnectionStatus.DISCONNECTED,
                    ),
                ),
            ),
            createDiscoveryAdapter = { port ->
                discoveryPasses += 1
                DeviceDiscoverySessionAdapter(fakeDiscovery(), port)
            },
            createConnectionAdapter = { port ->
                DeviceConnectionSessionAdapter(
                    connectionFactory = DeviceConnectionFactory {
                        connectionAttempts += 1
                        if (connectionAttempts == 2) {
                            DeviceConnectionAttempt.Failed("known_endpoint_unavailable")
                        } else {
                            DeviceConnectionAttempt.Connected(
                                TrackingConnectionLease(),
                            )
                        }
                    },
                    eventPort = port,
                )
            },
            loadCatalog = {},
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.onForeground()
        advanceUntilIdle()
        assertEquals(InstallationSessionState.MAINTENANCE, runtime.session.currentSnapshot().state)
        assertEquals(DeviceConnectionStatus.CONFIRMED, runtime.session.currentSnapshot().device?.connectionStatus)

        runtime.session.dispatchEvent(InstallationSessionEvent.DeviceDisconnected("adb:vehicle-1"))
        advanceUntilIdle()

        val restored = runtime.session.currentSnapshot()
        assertEquals(2, discoveryPasses)
        assertEquals(3, connectionAttempts)
        assertEquals(InstallationSessionState.MAINTENANCE, restored.state)
        assertEquals(DeviceConnectionStatus.CONFIRMED, restored.device?.connectionStatus)
        assertEquals(null, restored.failure)
        runtime.close()
    }

    @Test
    fun `adapter disconnect resumes once from the connected checkpoint`() = runTest {
        var catalogLoads = 0
        val runtime = InstallerRuntime(
            session = InstallationSession(),
            createDiscoveryAdapter = { port -> DeviceDiscoverySessionAdapter(fakeDiscovery(), port) },
            createConnectionAdapter = { port -> fakeConnectionAdapter(port) },
            loadCatalog = { port ->
                catalogLoads += 1
                port.emit(InstallationSessionEvent.CatalogFailed("catalog_not_ready"))
            },
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.dispatch(InstallationSessionCommand.StartDiscovery)
        advanceUntilIdle()
        runtime.dispatch(InstallationSessionCommand.SelectDevice("adb:vehicle-1"))
        advanceUntilIdle()
        assertEquals(InstallationSessionState.CONNECTED, runtime.session.currentSnapshot().state)

        runtime.session.dispatchEvent(InstallationSessionEvent.DeviceDisconnected("adb:vehicle-1"))
        advanceUntilIdle()

        assertEquals(InstallationSessionState.CONNECTED, runtime.session.currentSnapshot().state)
        assertEquals(DeviceConnectionStatus.CONFIRMED, runtime.session.currentSnapshot().device?.connectionStatus)
        assertEquals(2, catalogLoads)
        assertEquals("catalog_not_ready", runtime.session.currentSnapshot().failure?.reasonCode)
        runtime.close()
    }

    @Test
    fun `runtime composes discovery catalog and artifact preparation through one session generation`() = runTest {
        val manifests = listOf(
            manifest("lyrics").copy(packageName = "com.tcrrry.desktoplyrics"),
            manifest("desktop").copy(packageName = "com.tcrrry.desktop"),
        )
        val preparedIds = mutableListOf<String>()
        val runtime = InstallerRuntime(
            session = InstallationSession(),
            createDiscoveryAdapter = { port -> DeviceDiscoverySessionAdapter(fakeDiscovery(), port) },
            createConnectionAdapter = { port -> fakeConnectionAdapter(port) },
            loadCatalog = { port ->
                port.emit(
                    InstallationSessionEvent.DistributionConfigResolved(
                        configVersion = "android-v1",
                        keyId = "test-key",
                        signatureAlgorithm = "SHA256withECDSA",
                        components = manifests.map { it.toComponentDescriptor() },
                    ),
                )
            },
            prepareInstallationBatch = { batch, port ->
                val selected = manifests.filter { it.componentId in batch.preparationComponentIds }
                preparedIds += selected.map { it.componentId }
                emitArtifactBatchPrepared(batch, selected, port = port)
                ArtifactPreparationResult.Prepared(
                    selected.map { manifest ->
                        PreparedArtifact(
                            manifest = manifest,
                            sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                            finalApk = File("${manifest.componentId}.apk"),
                        )
                    },
                )
            },
            executeDeviceInstallationWithBatch = { _, _, _, _, _ -> Unit },
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
        assertEquals(manifests.map { it.componentId }.toSet(), runtime.session.currentSnapshot().components.map { it.id }.toSet())

        runtime.dispatch(InstallationSessionCommand.ToggleOptionalComponent("lyrics", selected = true))
        runtime.dispatch(InstallationSessionCommand.StartInstallation)
        advanceUntilIdle()
        val prepared = runtime.session.currentSnapshot()
        assertEquals(listOf("lyrics", "desktop"), preparedIds)
        assertEquals(InstallationSessionState.ARTIFACTS_READY, prepared.state)
        assertEquals(setOf("lyrics", "desktop"), prepared.installationBatch?.selectedComponentIds)
        assertTrue(prepared.evidence.artifactsVerified.containsAll(setOf("lyrics", "desktop")))

        runtime.close()
    }

    @Test
    fun `verified artifacts continue through the retained connection into device installation`() = runTest {
        val manifests = listOf(manifest("lyrics"), manifest("desktop"))
        val lease = TrackingConnectionLease()
        var installationConnection: DeviceConnectionLease? = null
        var installationComponentIds = emptyList<String>()
        var installationStrategy: InstallationStrategy? = null
        val runtime = InstallerRuntime(
            session = InstallationSession(),
            createDiscoveryAdapter = { port -> DeviceDiscoverySessionAdapter(fakeDiscovery(), port) },
            createConnectionAdapter = { port ->
                DeviceConnectionSessionAdapter(
                    connectionFactory = DeviceConnectionFactory {
                        DeviceConnectionAttempt.Connected(lease)
                    },
                    eventPort = port,
                )
            },
            loadCatalog = { port ->
                port.emit(
                    InstallationSessionEvent.DistributionConfigResolved(
                        configVersion = "android-v1",
                        keyId = "test-key",
                        signatureAlgorithm = "SHA256withECDSA",
                        components = manifests.map { it.toComponentDescriptor() },
                    ),
                )
            },
            prepareInstallationBatch = { batch, port ->
                val selected = manifests.filter { it.componentId in batch.preparationComponentIds }
                emitArtifactBatchPrepared(batch, selected, port = port)
                ArtifactPreparationResult.Prepared(
                    selected.map { manifest ->
                        PreparedArtifact(
                            manifest = manifest,
                            sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                            finalApk = File("${manifest.componentId}.apk"),
                        )
                    },
                )
            },
            executeDeviceInstallationWithBatch = { connection, artifacts, batch, _, _ ->
                installationConnection = connection
                installationComponentIds = artifacts.map { it.manifest.componentId }
                installationStrategy = batch.strategy
            },
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.dispatch(InstallationSessionCommand.StartDiscovery)
        advanceUntilIdle()
        runtime.dispatch(
            InstallationSessionCommand.SelectDevice(
                runtime.session.currentSnapshot().discoveredDevices.single().id,
            ),
        )
        advanceUntilIdle()
        runtime.dispatch(InstallationSessionCommand.ToggleOptionalComponent("lyrics", selected = true))
        runtime.dispatch(InstallationSessionCommand.StartInstallation)
        advanceUntilIdle()

        assertTrue(installationConnection === lease)
        assertEquals(listOf("lyrics", "desktop"), installationComponentIds)
        assertEquals(InstallationStrategy.INSTALL_MISSING_ONLY, installationStrategy)
        assertEquals(InstallationSessionState.ARTIFACTS_READY, runtime.session.currentSnapshot().state)

        runtime.close()
    }

    @Test
    fun `runtime executes prepared components and records preparation failures in one batch receipt`() = runTest {
        val manifests = listOf(
            manifest("lyrics").copy(packageName = "com.tcrrry.desktoplyrics"),
            manifest("desktop").copy(packageName = "com.tcrrry.desktop"),
        )
        val lease = TrackingConnectionLease()
        var executionCount = 0
        var executedIds = emptySet<String>()
        var observedPreparationFailures = emptyMap<String, DeviceActionFailure>()
        val runtime = InstallerRuntime(
            session = InstallationSession(),
            createDiscoveryAdapter = { port -> DeviceDiscoverySessionAdapter(fakeDiscovery(), port) },
            createConnectionAdapter = { port ->
                DeviceConnectionSessionAdapter(
                    connectionFactory = DeviceConnectionFactory {
                        DeviceConnectionAttempt.Connected(lease)
                    },
                    eventPort = port,
                )
            },
            loadCatalog = { port ->
                port.emit(
                    InstallationSessionEvent.DistributionConfigResolved(
                        configVersion = "android-v1",
                        keyId = "test-key",
                        signatureAlgorithm = "SHA256withECDSA",
                        components = manifests.map { it.toComponentDescriptor() },
                    ),
                )
            },
            prepareInstallationBatch = { batch, port ->
                val selected = manifests.filter { it.componentId in batch.preparationComponentIds }
                val failures = listOf(
                    ArtifactFailure(
                        phase = ArtifactFailurePhase.DOWNLOAD,
                        componentId = "lyrics",
                        sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                        reasonCode = "lyrics_download_failed",
                        retryable = true,
                    ),
                )
                emitArtifactBatchPrepared(
                    batchPlan = batch,
                    prepared = selected.filter { it.componentId == "desktop" },
                    failures = failures,
                    port = port,
                )
                ArtifactPreparationResult.Prepared(
                    artifacts = listOf(
                        PreparedArtifact(
                            manifest = selected.first { it.componentId == "desktop" },
                            sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                            finalApk = File("desktop.apk"),
                        ),
                    ),
                    failures = failures,
                )
            },
            executeDeviceInstallationWithBatch = object : InstallationBatchExecutor {
                override suspend fun execute(
                    connection: DeviceConnectionLease,
                    artifacts: List<PreparedArtifact>,
                    batchPlan: InstallationBatchPlan,
                    preparationFailures: Map<String, DeviceActionFailure>,
                    eventPort: InstallationSessionBoundary,
                ) {
                    executionCount += 1
                    executedIds = artifacts.mapTo(linkedSetOf()) { it.manifest.componentId }
                    observedPreparationFailures = preparationFailures
                    // The fake is intentionally a batch boundary only: it
                    // returns one receipt with the prepared desktop identity
                    // and leaves lyrics explicitly not attempted.
                    emitBatchStartAndReceipt(
                        artifacts = artifacts,
                        batchPlan = batchPlan,
                        preparationFailures = preparationFailures,
                        eventPort = eventPort,
                    )
                }
            },
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.dispatch(InstallationSessionCommand.StartDiscovery)
        advanceUntilIdle()
        runtime.dispatch(
            InstallationSessionCommand.SelectDevice(
                runtime.session.currentSnapshot().discoveredDevices.single().id,
            ),
        )
        advanceUntilIdle()
        runtime.dispatch(InstallationSessionCommand.ToggleOptionalComponent("lyrics", selected = true))
        runtime.dispatch(InstallationSessionCommand.StartInstallation)
        advanceUntilIdle()

        val failed = runtime.session.currentSnapshot()
        assertEquals(1, executionCount)
        assertEquals(setOf("desktop"), executedIds)
        assertEquals("lyrics_download_failed", observedPreparationFailures["lyrics"]?.reasonCode)
        assertEquals(InstallationSessionState.COMPLETED_WITH_ERRORS, failed.state)
        assertTrue(failed.installationBatchReceipt != null)
        assertEquals(
            InstallationStageReceiptStatus.NOT_ATTEMPTED,
            failed.installationBatchReceipt?.components?.single { it.componentId == "lyrics" }
                ?.installation?.status,
        )
        assertEquals(
            InstallationStageReceiptStatus.VERIFIED,
            failed.installationBatchReceipt?.components?.single { it.componentId == "desktop" }
                ?.installation?.status,
        )
        assertEquals(InstallationSessionState.COMPLETED_WITH_ERRORS, failed.state)
        runtime.close()
    }

    @Test
    fun `runtime commits a complete receipt without device write when every preparation fails`() = runTest {
        val manifests = listOf(
            manifest("lyrics").copy(packageName = "com.tcrrry.desktoplyrics"),
            manifest("desktop").copy(packageName = "com.tcrrry.desktop"),
        )
        var executionCount = 0
        var executedIds = emptySet<String>()
        val runtime = InstallerRuntime(
            session = InstallationSession(),
            createDiscoveryAdapter = { port -> DeviceDiscoverySessionAdapter(fakeDiscovery(), port) },
            createConnectionAdapter = { port ->
                DeviceConnectionSessionAdapter(
                    connectionFactory = DeviceConnectionFactory {
                        DeviceConnectionAttempt.Connected(TrackingConnectionLease())
                    },
                    eventPort = port,
                )
            },
            loadCatalog = { port ->
                port.emit(
                    InstallationSessionEvent.DistributionConfigResolved(
                        configVersion = "android-v1",
                        keyId = "test-key",
                        signatureAlgorithm = "SHA256withECDSA",
                        components = manifests.map { it.toComponentDescriptor() },
                    ),
                )
            },
            prepareInstallationBatch = { batch, port ->
                val selected = manifests.filter { it.componentId in batch.preparationComponentIds }
                val failures = selected.map { manifest ->
                    ArtifactFailure(
                        phase = ArtifactFailurePhase.DOWNLOAD,
                        componentId = manifest.componentId,
                        sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                        reasonCode = "${manifest.componentId}_download_failed",
                        retryable = true,
                    )
                }
                emitArtifactBatchPrepared(
                    batchPlan = batch,
                    prepared = emptyList(),
                    failures = failures,
                    port = port,
                )
                ArtifactPreparationResult.Prepared(
                    artifacts = emptyList(),
                    failures = failures,
                )
            },
            executeDeviceInstallationWithBatch = object : InstallationBatchExecutor {
                override suspend fun execute(
                    connection: DeviceConnectionLease,
                    artifacts: List<PreparedArtifact>,
                    batchPlan: InstallationBatchPlan,
                    preparationFailures: Map<String, DeviceActionFailure>,
                    eventPort: InstallationSessionBoundary,
                ) {
                    executionCount += 1
                    executedIds = artifacts.mapTo(linkedSetOf()) { it.manifest.componentId }
                    emitBatchStartAndReceipt(
                        artifacts = artifacts,
                        batchPlan = batchPlan,
                        preparationFailures = preparationFailures,
                        eventPort = eventPort,
                    )
                }
            },
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.dispatch(InstallationSessionCommand.StartDiscovery)
        advanceUntilIdle()
        runtime.dispatch(
            InstallationSessionCommand.SelectDevice(
                runtime.session.currentSnapshot().discoveredDevices.single().id,
            ),
        )
        advanceUntilIdle()
        runtime.dispatch(InstallationSessionCommand.ToggleOptionalComponent("lyrics", selected = true))
        runtime.dispatch(InstallationSessionCommand.StartInstallation)
        advanceUntilIdle()

        val completed = runtime.session.currentSnapshot()
        assertEquals(1, executionCount)
        assertTrue(executedIds.isEmpty())
        assertEquals(InstallationSessionState.COMPLETED_WITH_ERRORS, completed.state)
        assertEquals(2, completed.installationBatchReceipt?.components?.size)
        assertTrue(completed.installationBatchReceipt?.components?.all {
            it.installation.status == InstallationStageReceiptStatus.NOT_ATTEMPTED
        } == true)
        runtime.close()
    }

    @Test
    fun `maintenance runtime prepares only missing apps and passes installed prerequisite without an apk`() = runTest {
        val desktop = manifest("desktop")
        val lyrics = manifest("lyrics")
        val manifests = listOf(desktop, lyrics)
        val descriptors = manifests.map { manifest ->
            ComponentDescriptor(
                id = manifest.componentId,
                displayName = manifest.displayName,
                required = manifest.required,
                versionLabel = "1.0.0",
                sizeLabel = "50 B",
                compatibilityLabel = "compatible",
            )
        }
        val preparedIds = mutableListOf<String>()
        var executedArtifacts: List<PreparedArtifact> = emptyList()
        var executedStrategy: InstallationStrategy? = null
        var executedBatch: InstallationBatchPlan? = null
        val runtime = InstallerRuntime(
            session = InstallationSession(
                initialSnapshot = InstallationSessionSnapshot(
                    state = InstallationSessionState.MAINTENANCE,
                    device = fakeVehicle().toDeviceSummary().copy(
                        connectionStatus = DeviceConnectionStatus.DISCONNECTED,
                    ),
                    components = descriptors,
                    artifactManifests = emptyList(),
                    installationStrategy = InstallationStrategy.INSTALL_MISSING_ONLY,
                    maintenance = MaintenanceSnapshot(
                        managedApplicationsState = MaintenanceInventoryState.READY,
                        managedApplications = listOf(
                            ManagedApplicationStatus(
                                componentId = "desktop",
                                packageName = desktop.packageName,
                                installed = true,
                                versionCode = desktop.apkVersion.code,
                            ),
                            ManagedApplicationStatus(
                                componentId = "cast",
                                packageName = "com.ninepointnine.desktopcast",
                                installed = true,
                            ),
                        ),
                        availableComponents = descriptors,
                        // Control-plane refreshes do not carry APK manifests;
                        // the installed baseline remains the identity source
                        // for the missing-only skip decision.
                        installedManifests = listOf(desktop),
                        availableManifests = emptyList(),
                        catalogControlPlaneOnly = true,
                        installationSelection = MaintenanceInstallationSelection(
                            actionId = com.ninepointnine.helper.domain.session.MaintenanceActionId.INSTALL_FILE_MANAGER,
                            options = listOf(
                                MaintenanceInstallationOption(
                                    componentId = "desktop",
                                    displayName = "desktop",
                                    installed = true,
                                    required = true,
                                ),
                                MaintenanceInstallationOption(
                                    componentId = "lyrics",
                                    displayName = "lyrics",
                                    installed = false,
                                ),
                            ),
                            selectedComponentIds = setOf("lyrics"),
                        ),
                    ),
                ),
            ),
            createDiscoveryAdapter = { port -> DeviceDiscoverySessionAdapter(fakeDiscovery(), port) },
            createConnectionAdapter = { port -> fakeConnectionAdapter(port) },
            loadCatalog = {},
            prepareInstallationBatch = { batch, port ->
                val selected = listOf(lyrics).filter { it.componentId in batch.preparationComponentIds }
                preparedIds += selected.map { it.componentId }
                emitArtifactBatchPrepared(batch, selected, port = port)
                ArtifactPreparationResult.Prepared(
                    selected.map { manifest ->
                        PreparedArtifact(
                            manifest = manifest,
                            sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                            finalApk = File("${manifest.componentId}.apk"),
                        )
                    },
                )
            },
            executeDeviceInstallationWithBatch = { _, artifacts, batch, _, _ ->
                executedArtifacts = artifacts
                executedStrategy = batch.strategy
                executedBatch = batch
            },
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.dispatch(InstallationSessionCommand.Reconnect)
        advanceUntilIdle()
        assertEquals(InstallationSessionState.MAINTENANCE, runtime.session.currentSnapshot().state)

        runtime.dispatch(InstallationSessionCommand.StartMaintenanceInstallation)
        advanceUntilIdle()

        assertEquals(null, runtime.session.currentSnapshot().components.single { it.id == "desktop" }.errorReason)
        assertEquals(listOf("lyrics"), preparedIds)
        assertEquals(InstallationStrategy.INSTALL_MISSING_ONLY, executedStrategy)
        assertEquals(InstallationFlow.MAINTENANCE_INSTALL, executedBatch?.flow)
        assertEquals(setOf("desktop", "lyrics"), executedBatch?.selectedComponentIds)
        assertEquals(setOf("desktop"), executedBatch?.reusableComponentIds)
        assertEquals(setOf("lyrics"), executedBatch?.preparationComponentIds)
        assertEquals(setOf("desktop", "lyrics"), executedArtifacts.map { it.manifest.componentId }.toSet())
        assertTrue(executedArtifacts.single { it.manifest.componentId == "desktop" }.finalApk == null)
        assertTrue(executedArtifacts.single { it.manifest.componentId == "lyrics" }.finalApk != null)
        runtime.close()
    }

    @Test
    fun `maintenance runtime terminal artifact failure contains only the current batch`() = runTest {
        val desktop = manifest("desktop")
        val cast = manifest("cast")
        val descriptors = listOf(desktop, cast).map { manifest ->
            ComponentDescriptor(
                id = manifest.componentId,
                displayName = manifest.displayName,
                required = manifest.required,
                versionLabel = "1.0.0",
                sizeLabel = "50 B",
                compatibilityLabel = "compatible",
            )
        }
        val runtime = InstallerRuntime(
            session = InstallationSession(
                initialSnapshot = InstallationSessionSnapshot(
                    state = InstallationSessionState.MAINTENANCE,
                    device = fakeVehicle().toDeviceSummary().copy(
                        connectionStatus = DeviceConnectionStatus.DISCONNECTED,
                    ),
                    components = descriptors,
                    maintenance = MaintenanceSnapshot(
                        managedApplicationsState = MaintenanceInventoryState.READY,
                        managedApplications = listOf(
                            ManagedApplicationStatus(
                                componentId = desktop.componentId,
                                packageName = desktop.packageName,
                                installed = true,
                                versionCode = desktop.apkVersion.code,
                            ),
                        ),
                        availableComponents = descriptors,
                        installedManifests = listOf(desktop),
                        availableManifests = emptyList(),
                        catalogControlPlaneOnly = true,
                        installationSelection = MaintenanceInstallationSelection(
                            actionId = com.ninepointnine.helper.domain.session.MaintenanceActionId.INSTALL_APPLICATIONS,
                            options = descriptors.map { descriptor ->
                                MaintenanceInstallationOption(
                                    componentId = descriptor.id,
                                    displayName = descriptor.displayName,
                                    installed = descriptor.id == desktop.componentId,
                                    required = descriptor.required,
                                )
                            },
                            selectedComponentIds = setOf(cast.componentId),
                        ),
                    ),
                ),
            ),
            createDiscoveryAdapter = { port -> DeviceDiscoverySessionAdapter(fakeDiscovery(), port) },
            createConnectionAdapter = { port -> fakeConnectionAdapter(port) },
            loadCatalog = {},
            prepareInstallationBatch = { batch, _ ->
                assertEquals(setOf(desktop.componentId, cast.componentId), batch.selectedComponentIds)
                assertEquals(setOf(desktop.componentId), batch.reusableComponentIds)
                assertEquals(setOf(cast.componentId), batch.preparationComponentIds)
                ArtifactPreparationResult.Failed(
                    ArtifactFailure(
                        phase = ArtifactFailurePhase.ARCHIVE_VERIFICATION,
                        componentId = cast.componentId,
                        reasonCode = "distribution_archive_invalid",
                        retryable = true,
                    ),
                )
            },
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.dispatch(InstallationSessionCommand.Reconnect)
        advanceUntilIdle()
        runtime.dispatch(InstallationSessionCommand.StartMaintenanceInstallation)
        advanceUntilIdle()

        val failed = runtime.session.currentSnapshot()
        assertEquals(InstallationSessionState.FAILED, failed.state)
        assertEquals("distribution_archive_invalid", failed.failure?.reasonCode)
        assertEquals(listOf(cast.componentId), failed.componentResults.map { it.componentId })
        assertEquals("distribution_archive_invalid", failed.componentResults.single().failureReason)
        assertTrue(failed.componentResults.none { it.componentId == desktop.componentId })
        runtime.close()
    }

    @Test
    fun `single maintenance update hands only its target to the device executor`() = runTest {
        val desktop = manifest("desktop")
        val cast = manifest("cast")
        val descriptors = listOf(desktop, cast).map { it.toComponentDescriptor() }
        var executedArtifacts: List<PreparedArtifact> = emptyList()
        var executedBatch: InstallationBatchPlan? = null
        val runtime = InstallerRuntime(
            session = InstallationSession(
                initialSnapshot = InstallationSessionSnapshot(
                    state = InstallationSessionState.MAINTENANCE,
                    device = fakeVehicle().toDeviceSummary().copy(
                        connectionStatus = DeviceConnectionStatus.DISCONNECTED,
                    ),
                    components = descriptors,
                    catalogVersion = "catalog-1",
                    catalogRevision = 1L,
                    catalogKeyId = "key-1",
                    catalogSignatureAlgorithm = "Ed25519",
                    evidence = com.ninepointnine.helper.domain.session.SessionEvidence(
                        installed = setOf("desktop", "cast"),
                        configured = setOf("desktop", "cast"),
                        available = setOf("desktop", "cast"),
                    ),
                    maintenance = MaintenanceSnapshot(
                        managedApplicationsState = MaintenanceInventoryState.READY,
                        managedApplications = listOf(desktop, cast).map { manifest ->
                            ManagedApplicationStatus(
                                componentId = manifest.componentId,
                                packageName = manifest.packageName,
                                installed = true,
                                versionCode = manifest.apkVersion.code,
                            )
                        },
                        availableComponents = descriptors,
                        installedManifests = listOf(desktop, cast),
                        availableManifests = listOf(desktop, cast),
                        availableCatalogVersion = "catalog-1",
                        availableCatalogRevision = 1L,
                        availableCatalogKeyId = "key-1",
                        availableCatalogSignatureAlgorithm = "Ed25519",
                        updateStatuses = listOf(
                            MaintenanceUpdateStatus(
                                componentId = "cast",
                                displayName = "cast",
                                versionLabel = "1.0.0",
                                installedVersionLabel = "0.9.0",
                                state = MaintenanceUpdateState.UPDATE_AVAILABLE,
                            ),
                        ),
                    ),
                ),
            ),
            createDiscoveryAdapter = { port -> DeviceDiscoverySessionAdapter(fakeDiscovery(), port) },
            createConnectionAdapter = { port -> fakeConnectionAdapter(port) },
            loadCatalog = {},
            prepareInstallationBatch = { batch, port ->
                assertEquals(setOf("cast"), batch.selectedComponentIds)
                assertEquals(setOf("desktop"), batch.preinstalledComponentIds)
                emitArtifactBatchPrepared(batch, listOf(cast), port = port)
                ArtifactPreparationResult.Prepared(
                    artifacts = listOf(
                        PreparedArtifact(
                            manifest = cast,
                            sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                            finalApk = File("cast.apk"),
                        ),
                    ),
                )
            },
            executeDeviceInstallationWithBatch = { _, artifacts, batch, _, _ ->
                executedArtifacts = artifacts
                executedBatch = batch
            },
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.dispatch(InstallationSessionCommand.Reconnect)
        advanceUntilIdle()
        runtime.dispatch(InstallationSessionCommand.StartMaintenanceComponentUpdate("cast"))
        advanceUntilIdle()

        assertEquals(setOf("cast"), executedBatch?.selectedComponentIds)
        assertEquals(setOf("desktop"), executedBatch?.preinstalledComponentIds)
        assertEquals(listOf("cast"), executedArtifacts.map { it.manifest.componentId })
        runtime.close()
    }

    @Test
    fun `failed cast update can return check again and retry without reinstalling desktop`() = runTest {
        val desktop = manifest("desktop").copy(
            version = ArtifactVersion("1.0.2", 3L),
            apkVersion = ArtifactVersion("1.0.2", 3L),
            packageName = "com.ninepointnine.desktop.test",
        )
        val cast = manifest("cast").copy(
            version = ArtifactVersion("1.0.3", 4L),
            apkVersion = ArtifactVersion("1.0.3", 4L),
            packageName = "com.ninepointnine.desktopcast.test",
        )
        val descriptors = listOf(desktop, cast).map { it.toComponentDescriptor() }
        val gateway = UpdateInventoryGateway(desktop, cast, installedCastVersionCode = 3L)
        val root = Files.createTempDirectory("runtime-cast-retry").toFile()
        val executedIds = mutableListOf<Set<String>>()
        var executionCount = 0
        val runtime = InstallerRuntime(
            session = InstallationSession(
                initialSnapshot = InstallationSessionSnapshot(
                    state = InstallationSessionState.MAINTENANCE,
                    device = fakeVehicle().toDeviceSummary().copy(
                        connectionStatus = DeviceConnectionStatus.DISCONNECTED,
                    ),
                    components = descriptors,
                    catalogVersion = "catalog-1",
                    catalogRevision = 1L,
                    catalogKeyId = "key-1",
                    catalogSignatureAlgorithm = "Ed25519",
                    evidence = com.ninepointnine.helper.domain.session.SessionEvidence(
                        installed = setOf("desktop", "cast"),
                        configured = setOf("desktop", "cast"),
                        available = setOf("desktop", "cast"),
                    ),
                    maintenance = MaintenanceSnapshot(
                        managedApplicationsState = MaintenanceInventoryState.READY,
                        managedApplications = listOf(
                            ManagedApplicationStatus(
                                componentId = "desktop",
                                packageName = desktop.packageName,
                                installed = true,
                                versionCode = desktop.apkVersion.code,
                            ),
                            ManagedApplicationStatus(
                                componentId = "cast",
                                packageName = cast.packageName,
                                installed = true,
                                versionCode = 3L,
                            ),
                        ),
                        initialInstallationCompleted = true,
                        availableComponents = descriptors,
                        installedManifests = listOf(desktop, cast.copy(
                            version = ArtifactVersion("1.0.2", 3L),
                            apkVersion = ArtifactVersion("1.0.2", 3L),
                        )),
                        availableManifests = listOf(desktop, cast),
                        availableCatalogVersion = "catalog-1",
                        availableCatalogRevision = 1L,
                        availableCatalogKeyId = "key-1",
                        availableCatalogSignatureAlgorithm = "Ed25519",
                        updateStatuses = listOf(
                            MaintenanceUpdateStatus(
                                componentId = "cast",
                                displayName = "03投屏",
                                versionLabel = cast.version.name,
                                installedVersionLabel = "1.0.2",
                                state = MaintenanceUpdateState.UPDATE_AVAILABLE,
                            ),
                        ),
                    ),
                ),
            ),
            createDiscoveryAdapter = { port -> DeviceDiscoverySessionAdapter(fakeDiscovery(), port) },
            createConnectionAdapter = { port -> fakeActionConnectionAdapter(port, gateway) },
            loadCatalog = {},
            prepareInstallationBatch = { batch, port ->
                assertEquals(setOf("cast"), batch.selectedComponentIds)
                assertEquals(setOf("desktop"), batch.preinstalledComponentIds)
                emitArtifactBatchPrepared(batch, listOf(cast), port = port)
                ArtifactPreparationResult.Prepared(
                    artifacts = listOf(
                        PreparedArtifact(
                            manifest = cast,
                            sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                            finalApk = File("cast.apk"),
                        ),
                    ),
                )
            },
            executeDeviceInstallationWithBatch = { _, artifacts, batch, _, port ->
                executionCount += 1
                executedIds += artifacts.mapTo(linkedSetOf()) { it.manifest.componentId }
                port.emit(InstallationSessionEvent.InstallationStarted(listOf("cast")))
                val installation = if (executionCount == 1) {
                    InstallationStageReceipt(
                        status = InstallationStageReceiptStatus.FAILED,
                        reasonCode = "adb_pm_install_failed",
                        retryable = true,
                    )
                } else {
                    InstallationStageReceipt(
                        status = InstallationStageReceiptStatus.VERIFIED,
                        evidence = InstalledArtifactEvidence(
                            componentId = "cast",
                            packageName = cast.packageName,
                            version = cast.apkVersion,
                            apkSizeBytes = cast.apkSizeBytes,
                            apkSha256 = cast.apkSha256,
                            certificateSha256 = cast.certificateSha256,
                        ),
                        writeConfirmed = true,
                        operationConfirmed = true,
                    )
                }
                port.emit(
                    InstallationSessionEvent.InstallationBatchCompleted(
                        InstallationBatchReceipt(
                            batchId = batch.batchId,
                            components = listOf(
                                InstallationComponentReceipt(
                                    componentId = "cast",
                                    installation = installation,
                                    authorization = AuthorizationStageReceipt(
                                        status = if (executionCount == 1) {
                                            AuthorizationStageReceiptStatus.NOT_ATTEMPTED
                                        } else {
                                            AuthorizationStageReceiptStatus.NOT_REQUIRED
                                        },
                                        reasonCode = "installation_failed".takeIf { executionCount == 1 },
                                    ),
                                    availability = AvailabilityStageReceipt(
                                        status = if (executionCount == 1) {
                                            AvailabilityStageReceiptStatus.NOT_ATTEMPTED
                                        } else {
                                            AvailabilityStageReceiptStatus.NOT_REQUIRED
                                        },
                                        reasonCode = "installation_failed".takeIf { executionCount == 1 },
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            },
            maintenanceController = MaintenanceController(
                artifactCache = ArtifactCache(root.resolve("artifacts")),
                diagnosticStore = MaintenanceDiagnosticStore(root.resolve("diagnostics")),
                loadDistributionConfig = { maintenanceUpdateConfig(desktop, cast) },
            ),
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.dispatch(InstallationSessionCommand.Reconnect)
        advanceUntilIdle()
        runtime.dispatch(InstallationSessionCommand.StartMaintenanceComponentUpdate("cast"))
        advanceUntilIdle()

        assertEquals(InstallationSessionState.COMPLETED_WITH_ERRORS, runtime.session.currentSnapshot().state)
        assertEquals(listOf("cast"), runtime.session.currentSnapshot().componentResults.map { it.componentId })

        runtime.dispatch(InstallationSessionCommand.ReturnToMaintenanceInstallationSelection)
        advanceUntilIdle()
        assertEquals(InstallationSessionState.MAINTENANCE, runtime.session.currentSnapshot().state)
        assertEquals(null, runtime.session.currentSnapshot().maintenance.routeAction)
        assertEquals(null, runtime.session.currentSnapshot().installationBatchReceipt)

        runtime.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.CHECK_UPDATES))
        advanceUntilIdle()
        assertEquals(
            "inventoryReads=${gateway.inventoryReads}, action=${runtime.session.currentSnapshot().maintenance.lastAction}",
            null,
            runtime.session.currentSnapshot().maintenance.activeAction,
        )
        assertEquals(
            MaintenanceUpdateState.UPDATE_AVAILABLE,
            runtime.session.currentSnapshot().maintenance.updateStatuses.single { it.componentId == "cast" }.state,
        )

        runtime.dispatch(InstallationSessionCommand.StartMaintenanceComponentUpdate("cast"))
        advanceUntilIdle()

        val completed = runtime.session.currentSnapshot()
        assertEquals(InstallationSessionState.SUCCEEDED, completed.state)
        assertEquals(listOf(setOf("cast"), setOf("cast")), executedIds)
        assertEquals(listOf("cast"), completed.installationBatchReceipt?.components?.map { it.componentId })
        assertEquals(listOf("cast"), completed.componentResults.map { it.componentId })
        runtime.close()
    }

    @Test
    fun `self update stages without touching the vehicle and commits through the ordinary receipt`() = runTest {
        val self = selfManifest(versionCode = 2)
        val installer = RecordingSelfUpdateInstaller()
        var deviceExecutionCalls = 0
        val runtime = createSelfUpdateRuntime(
            self = self,
            installer = installer,
            onDeviceExecution = { deviceExecutionCalls += 1 },
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.dispatch(
            InstallationSessionCommand.StartMaintenanceComponentUpdate(
                InstallerSelfIdentity.COMPONENT_ID,
            ),
        )
        advanceUntilIdle()

        val prepared = runtime.session.currentSnapshot()
        assertEquals(InstallationSessionState.ARTIFACTS_READY, prepared.state)
        assertEquals(InstallationFlow.SELF_UPDATE, prepared.installationFlow)
        assertEquals(1, installer.stageCalls)
        assertEquals(0, installer.launchCalls)
        assertEquals(0, deviceExecutionCalls)

        runtime.dispatch(InstallationSessionCommand.InstallPreparedSelfUpdate)
        assertEquals(InstallationSessionState.INSTALLING, runtime.session.currentSnapshot().state)
        assertEquals(1, installer.launchCalls)

        runtime.onForeground()
        advanceUntilIdle()

        val completed = runtime.session.currentSnapshot()
        assertEquals(InstallationSessionState.SUCCEEDED, completed.state)
        assertEquals(1, installer.verifyCalls)
        assertEquals(1, installer.clearCalls)
        assertEquals(
            MaintenanceUpdateState.CURRENT,
            completed.maintenance.updateStatuses.single().state,
        )
        assertEquals(
            InstallerSelfIdentity.COMPONENT_ID,
            completed.installationBatchReceipt?.components?.single()?.componentId,
        )
        runtime.close()
    }

    @Test
    fun `self update readback mismatch fails closed and never reports success`() = runTest {
        val self = selfManifest(versionCode = 2)
        val installer = RecordingSelfUpdateInstaller { manifest ->
            SelfUpdateVerificationResult.Confirmed(
                InstalledArtifactEvidence(
                    componentId = InstallerSelfIdentity.COMPONENT_ID,
                    packageName = InstallerSelfIdentity.PACKAGE_NAME,
                    version = ArtifactVersion("1.999.0", 999),
                    apkSizeBytes = manifest.apkSizeBytes,
                    apkSha256 = manifest.apkSha256,
                    certificateSha256 = manifest.certificateSha256,
                ),
            )
        }
        val runtime = createSelfUpdateRuntime(
            self = self,
            installer = installer,
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.dispatch(
            InstallationSessionCommand.StartMaintenanceComponentUpdate(
                InstallerSelfIdentity.COMPONENT_ID,
            ),
        )
        advanceUntilIdle()
        runtime.dispatch(InstallationSessionCommand.InstallPreparedSelfUpdate)
        runtime.onForeground()
        advanceUntilIdle()

        val failed = runtime.session.currentSnapshot()
        assertEquals(InstallationSessionState.FAILED, failed.state)
        assertEquals("self_update_readback_version_mismatch", failed.failure?.reasonCode)
        assertEquals(1, installer.clearCalls)
        runtime.close()
    }

    @Test
    fun `self update without a system installer fails before launch`() = runTest {
        val runtime = createSelfUpdateRuntime(
            self = selfManifest(versionCode = 2),
            installer = null,
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.dispatch(
            InstallationSessionCommand.StartMaintenanceComponentUpdate(
                InstallerSelfIdentity.COMPONENT_ID,
            ),
        )
        advanceUntilIdle()

        val failed = runtime.session.currentSnapshot()
        assertEquals(InstallationSessionState.FAILED, failed.state)
        assertEquals("self_update_installer_unavailable", failed.failure?.reasonCode)
        runtime.close()
    }

    @Test
    fun `cancelling a self update clears the staged package and ignores a late readback`() = runTest {
        val self = selfManifest(versionCode = 2)
        val installer = RecordingSelfUpdateInstaller()
        val runtime = createSelfUpdateRuntime(
            self = self,
            installer = installer,
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.dispatch(
            InstallationSessionCommand.StartMaintenanceComponentUpdate(
                InstallerSelfIdentity.COMPONENT_ID,
            ),
        )
        advanceUntilIdle()
        runtime.dispatch(InstallationSessionCommand.InstallPreparedSelfUpdate)
        assertEquals(InstallationSessionState.INSTALLING, runtime.session.currentSnapshot().state)

        runtime.dispatch(InstallationSessionCommand.CancelInstallation)
        advanceUntilIdle()
        assertEquals(InstallationSessionState.PAUSED, runtime.session.currentSnapshot().state)
        assertEquals(1, installer.clearCalls)

        runtime.onForeground()
        advanceUntilIdle()
        assertEquals(InstallationSessionState.PAUSED, runtime.session.currentSnapshot().state)
        assertEquals(0, installer.verifyCalls)
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
            prepareInstallationBatch = { _, _ -> error("artifact preparation must not start") },
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
    fun `runtime clears the complete private workspace after preparation returns`() = runTest {
        val desktop = manifest("desktop").copy(packageName = "com.tcrrry.desktop")
        val cleanupModes = mutableListOf<ArtifactWorkspaceCleanupMode>()
        val runtime = InstallerRuntime(
            session = InstallationSession(),
            createDiscoveryAdapter = { port -> DeviceDiscoverySessionAdapter(fakeDiscovery(), port) },
            createConnectionAdapter = { port -> fakeConnectionAdapter(port) },
            loadCatalog = { port ->
                port.emit(
                    InstallationSessionEvent.DistributionConfigResolved(
                        configVersion = "runtime-cleanup-test",
                        keyId = "test-key",
                        signatureAlgorithm = "SHA256withECDSA",
                        components = listOf(desktop.toComponentDescriptor()),
                    ),
                )
            },
            prepareInstallationBatch = { batch, port ->
                emitArtifactBatchPrepared(batch, listOf(desktop), port = port)
                ArtifactPreparationResult.Prepared(
                    artifacts = listOf(
                        PreparedArtifact(
                            manifest = desktop,
                            sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                            finalApk = File("desktop.apk"),
                        ),
                    ),
                )
            },
            // No executor is supplied: the result has crossed the boundary,
            // so the runtime must still release all private preparation files.
            cleanupArtifactWorkspace = { mode -> cleanupModes += mode },
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.dispatch(InstallationSessionCommand.StartDiscovery)
        advanceUntilIdle()
        runtime.dispatch(
            InstallationSessionCommand.SelectDevice(
                runtime.session.currentSnapshot().discoveredDevices.single().id,
            ),
        )
        advanceUntilIdle()
        runtime.dispatch(InstallationSessionCommand.StartInstallation)
        advanceUntilIdle()

        assertEquals(listOf(ArtifactWorkspaceCleanupMode.COMPLETE), cleanupModes)
        assertEquals(InstallationSessionState.FAILED, runtime.session.currentSnapshot().state)
        runtime.close()
    }

    @Test
    fun `runtime keeps resumable cleanup path when preparation throws`() = runTest {
        val cleanupModes = mutableListOf<ArtifactWorkspaceCleanupMode>()
        val runtime = InstallerRuntime(
            session = InstallationSession(),
            createDiscoveryAdapter = { port -> DeviceDiscoverySessionAdapter(fakeDiscovery(), port) },
            createConnectionAdapter = { port -> fakeConnectionAdapter(port) },
            loadCatalog = { port ->
                port.emit(
                    InstallationSessionEvent.DistributionConfigResolved(
                        configVersion = "runtime-interrupted-cleanup-test",
                        keyId = "test-key",
                        signatureAlgorithm = "SHA256withECDSA",
                        components = listOf(manifest("desktop").toComponentDescriptor()),
                    ),
                )
            },
            prepareInstallationBatch = { _, _ -> error("preparation exploded") },
            cleanupArtifactWorkspace = { mode -> cleanupModes += mode },
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.dispatch(InstallationSessionCommand.StartDiscovery)
        advanceUntilIdle()
        runtime.dispatch(
            InstallationSessionCommand.SelectDevice(
                runtime.session.currentSnapshot().discoveredDevices.single().id,
            ),
        )
        advanceUntilIdle()
        runtime.dispatch(InstallationSessionCommand.StartInstallation)
        advanceUntilIdle()

        assertEquals(listOf(ArtifactWorkspaceCleanupMode.PRESERVE_RESUMABLE_DOWNLOADS), cleanupModes)
        assertEquals("artifact_preparation_failed", runtime.session.currentSnapshot().failure?.reasonCode)
        runtime.close()
    }

    @Test
    fun `runtime preserves the fixed resume pair after a retryable preparation failure`() = runTest {
        val root = Files.createTempDirectory("runtime-retryable-resume").toFile()
        try {
            val cache = ArtifactCache(root)
            val desktop = manifest("desktop").copy(packageName = "com.tcrrry.desktop")
            val paths = cache.paths(desktop)
            paths.archivePart.writeBytes(byteArrayOf(0x50, 0x4b, 0x03))
            cache.writeResumeMetadata(desktop, ArtifactSourceKind.R2.wireName)
            paths.apkPart.writeBytes(byteArrayOf(7, 7, 7))
            val cleanupModes = mutableListOf<ArtifactWorkspaceCleanupMode>()
            val runtime = InstallerRuntime(
                session = InstallationSession(),
                createDiscoveryAdapter = { port -> DeviceDiscoverySessionAdapter(fakeDiscovery(), port) },
                createConnectionAdapter = { port -> fakeConnectionAdapter(port) },
                loadCatalog = { port ->
                    port.emit(
                        InstallationSessionEvent.DistributionConfigResolved(
                            configVersion = "runtime-retryable-resume",
                            keyId = "test-key",
                            signatureAlgorithm = "SHA256withECDSA",
                            components = listOf(desktop.toComponentDescriptor()),
                        ),
                    )
                },
                prepareInstallationBatch = { _, _ ->
                    ArtifactPreparationResult.Failed(
                        ArtifactFailure(
                            phase = ArtifactFailurePhase.DOWNLOAD,
                            componentId = desktop.componentId,
                            sourceKind = ArtifactSourceKind.R2,
                            reasonCode = "download_io_failed",
                            retryable = true,
                        ),
                    )
                },
                cleanupArtifactWorkspace = { mode ->
                    cleanupModes += mode
                    when (mode) {
                        ArtifactWorkspaceCleanupMode.COMPLETE -> cache.clearPrivateCache()
                        ArtifactWorkspaceCleanupMode.PRESERVE_RESUMABLE_DOWNLOADS ->
                            cache.clearPrivateApkCopies()
                    }
                },
                coroutineContext = UnconfinedTestDispatcher(testScheduler),
            )

            runtime.dispatch(InstallationSessionCommand.StartDiscovery)
            advanceUntilIdle()
            runtime.dispatch(
                InstallationSessionCommand.SelectDevice(
                    runtime.session.currentSnapshot().discoveredDevices.single().id,
                ),
            )
            advanceUntilIdle()
            runtime.dispatch(InstallationSessionCommand.StartInstallation)
            advanceUntilIdle()

            assertEquals(
                listOf(ArtifactWorkspaceCleanupMode.PRESERVE_RESUMABLE_DOWNLOADS),
                cleanupModes,
            )
            assertTrue(paths.archivePart.isFile)
            assertTrue(paths.resumeMetadata.isFile)
            assertFalse(paths.apkPart.exists())
            assertEquals("download_io_failed", runtime.session.currentSnapshot().failure?.reasonCode)
            runtime.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `runtime removes the fixed resume pair after a non retryable preparation failure`() = runTest {
        val root = Files.createTempDirectory("runtime-terminal-resume").toFile()
        try {
            val cache = ArtifactCache(root)
            val desktop = manifest("desktop").copy(packageName = "com.tcrrry.desktop")
            val paths = cache.paths(desktop)
            paths.archivePart.writeBytes(byteArrayOf(0x50, 0x4b, 0x03))
            cache.writeResumeMetadata(desktop, ArtifactSourceKind.R2.wireName)
            val cleanupModes = mutableListOf<ArtifactWorkspaceCleanupMode>()
            val runtime = InstallerRuntime(
                session = InstallationSession(),
                createDiscoveryAdapter = { port -> DeviceDiscoverySessionAdapter(fakeDiscovery(), port) },
                createConnectionAdapter = { port -> fakeConnectionAdapter(port) },
                loadCatalog = { port ->
                    port.emit(
                        InstallationSessionEvent.DistributionConfigResolved(
                            configVersion = "runtime-terminal-resume",
                            keyId = "test-key",
                            signatureAlgorithm = "SHA256withECDSA",
                            components = listOf(desktop.toComponentDescriptor()),
                        ),
                    )
                },
                prepareInstallationBatch = { _, _ ->
                    ArtifactPreparationResult.Failed(
                        ArtifactFailure(
                            phase = ArtifactFailurePhase.CATALOG,
                            componentId = desktop.componentId,
                            reasonCode = "distribution_manifest_invalid",
                            retryable = false,
                        ),
                    )
                },
                cleanupArtifactWorkspace = { mode ->
                    cleanupModes += mode
                    when (mode) {
                        ArtifactWorkspaceCleanupMode.COMPLETE -> cache.clearPrivateCache()
                        ArtifactWorkspaceCleanupMode.PRESERVE_RESUMABLE_DOWNLOADS ->
                            cache.clearPrivateApkCopies()
                    }
                },
                coroutineContext = UnconfinedTestDispatcher(testScheduler),
            )

            runtime.dispatch(InstallationSessionCommand.StartDiscovery)
            advanceUntilIdle()
            runtime.dispatch(
                InstallationSessionCommand.SelectDevice(
                    runtime.session.currentSnapshot().discoveredDevices.single().id,
                ),
            )
            advanceUntilIdle()
            runtime.dispatch(InstallationSessionCommand.StartInstallation)
            advanceUntilIdle()

            assertEquals(listOf(ArtifactWorkspaceCleanupMode.COMPLETE), cleanupModes)
            assertFalse(paths.archivePart.exists())
            assertFalse(paths.resumeMetadata.exists())
            runtime.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `runtime serializes a replacement preparation behind the previous cleanup`() = runTest {
        val desktop = manifest("desktop")
        val firstPreparationStarted = CompletableDeferred<Unit>()
        val firstPreparationGate = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleanupModes = mutableListOf<ArtifactWorkspaceCleanupMode>()
        var preparationCalls = 0
        val runtime = InstallerRuntime(
            session = InstallationSession(),
            createDiscoveryAdapter = { port -> DeviceDiscoverySessionAdapter(fakeDiscovery(), port) },
            createConnectionAdapter = { port -> fakeConnectionAdapter(port) },
            loadCatalog = { port ->
                port.emit(
                    InstallationSessionEvent.DistributionConfigResolved(
                        configVersion = "runtime-preparation-serialization",
                        keyId = "test-key",
                        signatureAlgorithm = "SHA256withECDSA",
                        components = listOf(desktop.toComponentDescriptor()),
                    ),
                )
            },
            prepareInstallationBatch = { batch, _ ->
                if (preparationCalls++ == 0) {
                    firstPreparationStarted.complete(Unit)
                    firstPreparationGate.await()
                }
                ArtifactPreparationResult.Failed(
                    ArtifactFailure(
                        phase = ArtifactFailurePhase.DOWNLOAD,
                        componentId = checkNotNull(batch.preparationComponentIds.single()),
                        reasonCode = "download_io_failed",
                        retryable = true,
                    ),
                )
            },
            cleanupArtifactWorkspace = { mode ->
                cleanupModes += mode
                if (cleanupModes.size == 1) {
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                }
            },
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )

        runtime.dispatch(InstallationSessionCommand.StartDiscovery)
        advanceUntilIdle()
        runtime.dispatch(
            InstallationSessionCommand.SelectDevice(
                runtime.session.currentSnapshot().discoveredDevices.single().id,
            ),
        )
        advanceUntilIdle()
        runtime.dispatch(InstallationSessionCommand.StartInstallation)
        firstPreparationStarted.await()

        runtime.dispatch(InstallationSessionCommand.CancelInstallation)
        advanceUntilIdle()
        cleanupStarted.await()
        assertEquals(InstallationSessionState.PAUSED, runtime.session.currentSnapshot().state)

        runtime.dispatch(InstallationSessionCommand.ContinueInstallation)
        advanceUntilIdle()
        assertFalse("replacement preparation must wait for cleanup", preparationCalls > 1)

        releaseCleanup.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, preparationCalls)
        assertEquals(2, cleanupModes.size)
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
            prepareInstallationBatch = { _, _ -> error("artifact preparation must not start") },
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
            onDevice(fakeVehicle())
            return DeviceDiscoveryResult(scannedCount = 1, confirmedCount = 1)
        }

        override fun cancel() = Unit
    }

    private fun fakeVehicle(): ConnectedDevice = ConnectedDevice(
        endpoint = DeviceEndpoint("192.168.1.203"),
        identity = DeviceIdentity("adb:vehicle-1", "S56_HQX", 28),
        capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
    )

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

    private fun fakeActionConnectionAdapter(
        eventPort: InstallationSessionEventPort,
        gateway: UpdateInventoryGateway,
    ): DeviceConnectionSessionAdapter = DeviceConnectionSessionAdapter(
        connectionFactory = DeviceConnectionFactory {
            DeviceConnectionAttempt.Connected(
                object : DeviceActionConnectionLease {
                    override val device: ConnectedDevice = fakeVehicle()
                    override val commandGateway: AdbCommandGateway = gateway
                    override val maintenanceGateway: MaintenanceCommandGateway = gateway

                    override suspend fun check(): DeviceConnectionCheck = DeviceConnectionCheck(true)

                    override fun close() = Unit
                },
            )
        },
        eventPort = eventPort,
    )

    private class UpdateInventoryGateway(
        private val desktop: ArtifactManifest,
        private val cast: ArtifactManifest,
        private val installedCastVersionCode: Long,
    ) : AdbCommandGateway, MaintenanceCommandGateway {
        var inventoryReads: Int = 0

        override suspend fun installBatch(
            artifacts: List<InstallableArtifact>,
            strategy: InstallationStrategy,
        ): DeviceInstallResult = error("installation is owned by the runtime executor in this test")

        override suspend fun runShortcut(
            shortcut: DeviceShortcut,
            selectedComponentIds: Set<String>,
            authorizationPlan: AuthorizationPlan,
        ): DeviceShortcutResult = error("authorization is owned by the runtime executor in this test")

        override suspend fun repairAuthorization(
            manifests: List<ArtifactManifest>,
            declarationsByComponent: Map<String, com.ninepointnine.helper.domain.device.ApkDeclarationMetadata>,
        ): MaintenanceDeviceResult = error("authorization repair is outside this test")

        override suspend fun inspectManagedApplications(
            components: List<ManagedComponent>,
        ): ManagedApplicationsResult = inspectInstalledApplicationInventory(components)

        override suspend fun inspectInstalledApplicationInventory(
            components: List<ManagedComponent>,
        ): ManagedApplicationsResult {
            inventoryReads += 1
            val installed = mapOf(
                desktop.componentId to ManagedApplicationProbe(
                    componentId = desktop.componentId,
                    packageName = desktop.packageName,
                    installed = true,
                    versionLabel = desktop.apkVersion.name,
                    versionCode = desktop.apkVersion.code,
                ),
                cast.componentId to ManagedApplicationProbe(
                    componentId = cast.componentId,
                    packageName = cast.packageName,
                    installed = true,
                    versionLabel = "1.0.2",
                    versionCode = installedCastVersionCode,
                ),
            )
            return ManagedApplicationsResult.Completed(
                components.mapNotNull { component -> installed[component.componentId] },
            )
        }

        override suspend fun launchManagedComponent(
            component: ManagedComponent,
        ): MaintenanceDeviceResult = error("launch is outside this test")

        override suspend fun inspectComponentAuthorization(
            components: List<ManagedComponent>,
            installedApplications: List<ManagedApplicationProbe>,
        ): MaintenanceAuthorizationResult = error("authorization inspection is outside this test")

        override suspend fun performApplicationAction(
            component: ManagedComponent,
            actionId: MaintenanceApplicationActionId,
        ): MaintenanceDeviceResult = error("application actions are outside this test")

        override suspend fun inspectManagedApplicationDetails(
            component: ManagedComponent,
        ): ManagedApplicationDetailsProbeResult = error("application details are outside this test")
    }

    private fun maintenanceUpdateConfig(
        desktop: ArtifactManifest,
        cast: ArtifactManifest,
    ): DistributionConfigLoadResult.Success = DistributionConfigLoadResult.Success(
        InstallerDistributionConfig(
            channel = "debug",
            environment = "staging",
            expiresAt = Instant.parse("2099-01-01T00:00:00Z"),
            catalogVersion = "catalog-2",
            catalogRevision = 2L,
            keyId = "test-key",
            signatureAlgorithm = "Ed25519",
            folderUrl = "https://wwatl.lanzouw.com/b0fqlrcyb",
            apps = listOf(desktop, cast).map { manifest ->
                InstallerComponentSource(
                    componentId = manifest.componentId,
                    archiveFileName = manifest.archiveFileName,
                    required = manifest.required,
                    displayName = manifest.displayName,
                    versionCode = manifest.apkVersion.code,
                    versionName = manifest.apkVersion.name,
                    apkSizeBytes = manifest.apkSizeBytes,
                    packageName = manifest.packageName,
                    certificateSha256 = manifest.certificateSha256,
                    apkEntryName = manifest.apkEntryName,
                    trustProfileId = "nine-studio",
                )
            },
        ),
    )

    private fun emitArtifactBatchPrepared(
        batchPlan: InstallationBatchPlan,
        prepared: List<ArtifactManifest>,
        failures: List<ArtifactFailure> = emptyList(),
        port: InstallationSessionEventPort,
    ) {
        port.emit(
            InstallationSessionEvent.ArtifactBatchPrepared(
                batchId = batchPlan.batchId,
                manifests = prepared,
                sourceSelections = prepared.map {
                    SourceSelectionEvidence(it.componentId, ArtifactSourceKind.LANZOU_SHARE)
                },
                archives = prepared.map {
                    ArchiveDownloadEvidence(it.componentId, it.archiveSizeBytes, it.archiveSha256)
                },
                archiveVerifications = prepared.map {
                    ArchiveVerificationEvidence(it.componentId, it.archiveSizeBytes, it.archiveSha256)
                },
                extractions = prepared.map {
                    ApkExtractionEvidence(it.componentId, it.apkEntryName, it.apkSizeBytes, it.apkSha256)
                },
                verifications = prepared.map { manifest ->
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
                failures = failures,
            ),
        )
    }

    private fun emitBatchStartAndReceipt(
        artifacts: List<PreparedArtifact>,
        batchPlan: InstallationBatchPlan,
        preparationFailures: Map<String, DeviceActionFailure>,
        eventPort: InstallationSessionEventPort,
    ) {
        eventPort.emit(
            InstallationSessionEvent.InstallationStarted(
                artifacts.map { it.manifest.componentId }.sorted(),
            ),
        )
        val artifactsById = artifacts.associateBy { it.manifest.componentId }
        val components = batchPlan.selectedComponentIds.sorted().map { componentId ->
            val preparationFailure = preparationFailures[componentId]
            val installation = if (preparationFailure != null) {
                InstallationStageReceipt(
                    status = InstallationStageReceiptStatus.NOT_ATTEMPTED,
                    reasonCode = preparationFailure.reasonCode,
                    retryable = preparationFailure.retryable,
                )
            } else {
                val manifest = checkNotNull(artifactsById[componentId]).manifest
                InstallationStageReceipt(
                    status = InstallationStageReceiptStatus.VERIFIED,
                    evidence = InstalledArtifactEvidence(
                        componentId = componentId,
                        packageName = manifest.packageName,
                        version = manifest.apkVersion,
                        apkSizeBytes = manifest.apkSizeBytes,
                        apkSha256 = manifest.apkSha256,
                        certificateSha256 = manifest.certificateSha256,
                    ),
                )
            }
            InstallationComponentReceipt(
                componentId = componentId,
                installation = installation,
                authorization = AuthorizationStageReceipt(
                    status = AuthorizationStageReceiptStatus.NOT_ATTEMPTED,
                    reasonCode = "authorization_not_attempted",
                ),
                availability = AvailabilityStageReceipt(
                    status = AvailabilityStageReceiptStatus.NOT_ATTEMPTED,
                    reasonCode = "availability_not_attempted",
                ),
            )
        }
        eventPort.emit(
            InstallationSessionEvent.InstallationBatchCompleted(
                InstallationBatchReceipt(
                    batchId = batchPlan.batchId,
                    components = components,
                ),
            ),
        )
    }

    private fun createSelfUpdateRuntime(
        self: ArtifactManifest,
        installer: SelfUpdateInstaller?,
        onDeviceExecution: () -> Unit = {},
        coroutineContext: CoroutineContext,
    ): InstallerRuntime {
        val descriptor = self.toComponentDescriptor()
        val snapshot = InstallationSessionSnapshot(
            state = InstallationSessionState.MAINTENANCE,
            components = listOf(descriptor),
            catalogVersion = "self-update-catalog",
            catalogRevision = 1L,
            catalogKeyId = "test-key",
            catalogSignatureAlgorithm = "Ed25519",
            maintenance = MaintenanceSnapshot(
                managedApplicationsState = MaintenanceInventoryState.READY,
                availableComponents = listOf(descriptor),
                availableManifests = listOf(self),
                availableCatalogVersion = "self-update-catalog",
                availableCatalogRevision = 1L,
                availableCatalogKeyId = "test-key",
                availableCatalogSignatureAlgorithm = "Ed25519",
                updateStatuses = listOf(
                    MaintenanceUpdateStatus(
                        componentId = InstallerSelfIdentity.COMPONENT_ID,
                        displayName = self.displayName,
                        versionLabel = self.version.name,
                        installedVersionLabel = "1.0.0",
                        state = MaintenanceUpdateState.UPDATE_AVAILABLE,
                        isSelf = true,
                    ),
                ),
            ),
        )
        return InstallerRuntime(
            session = InstallationSession(initialSnapshot = snapshot),
            createDiscoveryAdapter = { port -> DeviceDiscoverySessionAdapter(fakeDiscovery(), port) },
            createConnectionAdapter = { port -> fakeConnectionAdapter(port) },
            loadCatalog = {},
            prepareInstallationBatch = { batch, port ->
                emitArtifactBatchPrepared(batch, listOf(self), port = port)
                ArtifactPreparationResult.Prepared(
                    artifacts = listOf(
                        PreparedArtifact(
                            manifest = self,
                            sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                            finalApk = File("self-update.apk"),
                        ),
                    ),
                )
            },
            executeDeviceInstallationWithBatch = { _, _, _, _, _ -> onDeviceExecution() },
            selfUpdateInstaller = installer,
            coroutineContext = coroutineContext,
        )
    }

    private class RecordingSelfUpdateInstaller(
        private val verification: (ArtifactManifest) -> SelfUpdateVerificationResult = { manifest ->
            SelfUpdateVerificationResult.Confirmed(
                InstalledArtifactEvidence(
                    componentId = InstallerSelfIdentity.COMPONENT_ID,
                    packageName = InstallerSelfIdentity.PACKAGE_NAME,
                    version = manifest.apkVersion,
                    apkSizeBytes = manifest.apkSizeBytes,
                    apkSha256 = manifest.apkSha256,
                    certificateSha256 = manifest.certificateSha256,
                ),
            )
        },
    ) : SelfUpdateInstaller {
        var stageCalls = 0
        var launchCalls = 0
        var verifyCalls = 0
        var clearCalls = 0

        override fun stage(artifact: PreparedArtifact): SelfUpdateStageResult {
            stageCalls += 1
            return SelfUpdateStageResult.Ready(
                StagedSelfUpdate(
                    manifest = artifact.manifest,
                    apkFile = artifact.finalApk ?: File("self-update.apk"),
                ),
            )
        }

        override fun launch(staged: StagedSelfUpdate): SelfUpdateLaunchResult {
            launchCalls += 1
            return SelfUpdateLaunchResult.Started
        }

        override fun verifyInstalled(manifest: ArtifactManifest): SelfUpdateVerificationResult {
            verifyCalls += 1
            return verification(manifest)
        }

        override fun clear(staged: StagedSelfUpdate) {
            clearCalls += 1
        }
    }

    private fun selfManifest(versionCode: Long): ArtifactManifest = ArtifactManifest(
        schemaVersion = 1,
        componentId = InstallerSelfIdentity.COMPONENT_ID,
        displayName = "03车机助手",
        required = false,
        version = ArtifactVersion("1.$versionCode.0", versionCode),
        compatibility = CompatibilityRange(minAndroidSdk = 26, maxAndroidSdk = 35),
        archiveFileName = "03helper.zip",
        archiveSizeBytes = 100L,
        archiveSha256 = "11".repeat(32),
        apkEntryName = "03helper.apk",
        apkSizeBytes = 50L,
        apkSha256 = "22".repeat(32),
        packageName = InstallerSelfIdentity.PACKAGE_NAME,
        apkVersion = ArtifactVersion("1.$versionCode.0", versionCode),
        certificateSha256 = "33".repeat(32),
        sources = listOf(
            ArtifactSource(ArtifactSourceKind.LANZOU_SHARE, "https://wwatl.lanzouw.com/i03helper"),
            ArtifactSource(ArtifactSourceKind.R2, "https://assets.r2.dev/03helper.zip"),
            ArtifactSource(
                ArtifactSourceKind.GITHUB_RELEASES,
                "https://github.com/example/repo/releases/download/v1/03helper.zip",
            ),
        ),
    )

    private fun manifest(componentId: String): ArtifactManifest = ArtifactManifest(
        schemaVersion = 1,
        componentId = componentId,
        displayName = componentId,
        required = componentId == "desktop",
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
