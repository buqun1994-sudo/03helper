package com.ninepointnine.helper.application

import com.ninepointnine.helper.application.device.DeviceConnectionSessionAdapter
import com.ninepointnine.helper.application.device.DeviceDiscoverySessionAdapter
import com.ninepointnine.helper.application.device.toDeviceSummary
import com.ninepointnine.helper.domain.device.AdbCommandGateway
import com.ninepointnine.helper.domain.device.ConnectedDevice
import com.ninepointnine.helper.domain.device.DeviceActionConnectionLease
import com.ninepointnine.helper.domain.device.DeviceCapability
import com.ninepointnine.helper.domain.device.DeviceConnectionAttempt
import com.ninepointnine.helper.domain.device.DeviceConnectionCheck
import com.ninepointnine.helper.domain.device.DeviceConnectionFactory
import com.ninepointnine.helper.domain.device.DeviceDiscovery
import com.ninepointnine.helper.domain.device.DeviceDiscoveryResult
import com.ninepointnine.helper.domain.device.DeviceEndpoint
import com.ninepointnine.helper.domain.device.DeviceIdentity
import com.ninepointnine.helper.domain.device.DeviceInstallResult
import com.ninepointnine.helper.domain.device.DeviceShortcut
import com.ninepointnine.helper.domain.device.DeviceShortcutResult
import com.ninepointnine.helper.domain.device.InstallableArtifact
import com.ninepointnine.helper.domain.device.AuthorizationPlan
import com.ninepointnine.helper.domain.device.RemoteInputGateway
import com.ninepointnine.helper.domain.device.RemoteInputSubscription
import com.ninepointnine.helper.domain.device.VehicleInputReceipt
import com.ninepointnine.helper.domain.device.VehicleInputRequest
import com.ninepointnine.helper.domain.device.VehicleInputState
import com.ninepointnine.helper.domain.input.RemoteInputStatus
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.session.InstallationSession
import com.ninepointnine.helper.domain.session.InstallationSessionCommand
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.domain.session.InstallationSessionState
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.domain.session.MaintenanceSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteInputRuntimeTest {
    @Test
    fun `subscription state is projected into the domain snapshot`() = runTest {
        val gateway = ControlledRemoteInputGateway()
        val (runtime, session) = connectedRuntime(gateway, UnconfinedTestDispatcher(testScheduler))
        advanceUntilIdle()
        try {
            gateway.states.send(
                VehicleInputState(
                    available = true,
                    keyboard = "open",
                    epoch = 1L,
                    targetPackage = "com.example.target",
                    targetWindow = 3,
                    sequence = 0L,
                ),
            )
            advanceUntilIdle()

            val input = session.currentSnapshot().remoteInput
            assertTrue(gateway.subscribeCalls > 0)
            assertEquals(RemoteInputStatus.READY, input.status)
            assertTrue(input.hasState)
            assertTrue(input.leaseConfirmed)
            assertEquals(0L, input.sequence)
        } finally {
            runtime.close()
            session.close()
        }
    }

    @Test
    fun `pure route back does not interrupt an active input send`() = runTest {
        val gateway = ControlledRemoteInputGateway()
        val (runtime, session) = connectedRuntime(gateway, UnconfinedTestDispatcher(testScheduler))
        advanceUntilIdle()
        try {
            gateway.states.send(readyState())
            advanceUntilIdle()
            runtime.dispatch(InstallationSessionCommand.UpdateRemoteInputDraft("hello"))
            runtime.dispatch(InstallationSessionCommand.SendRemoteInputText(REQUEST_ONE))
            gateway.sendStarted.await()

            runtime.dispatch(InstallationSessionCommand.LeaveMaintenanceAction)
            assertEquals(null, session.currentSnapshot().maintenance.routeAction)
            assertEquals(1, gateway.sendCalls)
            assertNotNull(session.currentSnapshot().remoteInput.pendingSend)

            gateway.releaseSend.send(Unit)
            advanceUntilIdle()
            assertEquals("", session.currentSnapshot().remoteInput.draft)
        } finally {
            runtime.close()
            session.close()
        }
    }

    @Test
    fun `state stream failure never releases a send before its receipt`() = runTest {
        val gateway = ControlledRemoteInputGateway()
        val (runtime, session) = connectedRuntime(gateway, UnconfinedTestDispatcher(testScheduler))
        try {
            gateway.states.send(readyState())
            advanceUntilIdle()
            runtime.dispatch(InstallationSessionCommand.UpdateRemoteInputDraft("once"))
            runtime.dispatch(InstallationSessionCommand.SendRemoteInputText(REQUEST_ONE))
            gateway.sendStarted.await()
            val generation = session.currentSnapshot().remoteInput.connectionGeneration
            gateway.states.close(IOException("remote_input_channel_lost"))
            advanceUntilIdle()
            assertEquals(RemoteInputStatus.SENDING, session.currentSnapshot().remoteInput.status)
            assertNotNull(session.currentSnapshot().remoteInput.pendingSend)
            assertEquals(generation, session.currentSnapshot().remoteInput.connectionGeneration)
            assertEquals(1, gateway.subscribeCalls)
            runtime.onNetworkAvailable()
            runtime.onForeground()
            advanceUntilIdle()
            assertEquals(1, gateway.subscribeCalls)
            gateway.releaseSend.send(Unit)
            advanceUntilIdle()
            assertEquals("", session.currentSnapshot().remoteInput.draft)
            assertEquals("remote_input_sent", session.currentSnapshot().remoteInput.notice)
            assertEquals(1, gateway.sendCalls)
        } finally { runtime.close() }
    }

    @Test
    fun `disconnect leaves unknown result and ignores old receipt`() = runTest {
        val gateway = ControlledRemoteInputGateway()
        val (runtime, session) = connectedRuntime(gateway, UnconfinedTestDispatcher(testScheduler))
        advanceUntilIdle()
        try {
            gateway.states.send(readyState())
            advanceUntilIdle()
            runtime.dispatch(InstallationSessionCommand.UpdateRemoteInputDraft("keep me"))
            runtime.dispatch(InstallationSessionCommand.SendRemoteInputText(REQUEST_ONE))
            gateway.sendStarted.await()

            runtime.dispatch(InstallationSessionCommand.DisconnectDevice)
            val disconnected = session.currentSnapshot().remoteInput
            assertEquals(RemoteInputStatus.UNKNOWN_RESULT, disconnected.status)
            assertEquals("keep me", disconnected.draft)
            assertEquals(null, disconnected.pendingSend)

            gateway.releaseSend.send(Unit)
            advanceUntilIdle()
            val afterLateReceipt = session.currentSnapshot().remoteInput
            assertEquals(RemoteInputStatus.UNKNOWN_RESULT, afterLateReceipt.status)
            assertEquals("keep me", afterLateReceipt.draft)
        } finally {
            runtime.close()
            session.close()
        }
    }

    @Test
    fun `duplicate send clicks do not replace serialized device work`() = runTest {
        val gateway = ControlledRemoteInputGateway()
        val (runtime, session) = connectedRuntime(gateway, UnconfinedTestDispatcher(testScheduler))
        advanceUntilIdle()
        try {
            gateway.states.send(readyState())
            advanceUntilIdle()
            runtime.dispatch(InstallationSessionCommand.UpdateRemoteInputDraft("once"))
            runtime.dispatch(InstallationSessionCommand.SendRemoteInputText(REQUEST_ONE))
            gateway.sendStarted.await()

            runtime.dispatch(InstallationSessionCommand.SendRemoteInputText(REQUEST_TWO))
            assertEquals(1, gateway.sendCalls)
            assertEquals(REQUEST_ONE, session.currentSnapshot().remoteInput.pendingSend?.requestId)

            gateway.releaseSend.send(Unit)
            advanceUntilIdle()
            assertEquals("", session.currentSnapshot().remoteInput.draft)
        } finally {
            runtime.close()
            session.close()
        }
    }

    @Test
    fun `restored snapshots wait for an explicit recovery event after the target is seeded`() = runTest {
        val vehicle = fakeVehicle()
        val discovery = FakeDiscovery(vehicle)
        var connectionCalls = 0
        val (runtime, session) = restoredRuntime(
            ControlledRemoteInputGateway(),
            UnconfinedTestDispatcher(testScheduler),
            discovery,
            DeviceConnectionFactory {
                connectionCalls += 1
                DeviceConnectionAttempt.Connected(FakeActionLease(vehicle, ControlledRemoteInputGateway()))
            },
        )
        try {
            advanceUntilIdle()
            assertEquals(0, discovery.calls)
            assertEquals(0, connectionCalls)

            runtime.onNetworkAvailable()
            advanceUntilIdle()
            assertEquals(0, discovery.calls)
            assertEquals(0, connectionCalls)

            runtime.seedOnlineTarget(vehicle)
            runtime.dispatch(InstallationSessionCommand.UpdateRemoteInputDraft("restored draft"))
            advanceUntilIdle()
            assertEquals(0, discovery.calls)
            assertEquals(0, connectionCalls)
            assertEquals(DeviceConnectionStatus.DISCONNECTED, session.currentSnapshot().device?.connectionStatus)

            runtime.onNetworkAvailable()
            advanceUntilIdle()
            assertEquals(0, discovery.calls)
            assertEquals(1, connectionCalls)
            assertEquals(DeviceConnectionStatus.CONFIRMED, session.currentSnapshot().device?.connectionStatus)
            assertEquals("restored draft", session.currentSnapshot().remoteInput.draft)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `background known endpoint failure waits and foreground may scan once`() = runTest {
        val vehicle = fakeVehicle()
        val discovery = FakeDiscovery(vehicle)
        val gateway = ControlledRemoteInputGateway()
        var connectionCalls = 0
        val (runtime, session) = restoredRuntime(
            gateway,
            UnconfinedTestDispatcher(testScheduler),
            discovery,
            DeviceConnectionFactory {
                connectionCalls += 1
                if (connectionCalls <= 2) {
                    DeviceConnectionAttempt.Failed("known_endpoint_unavailable")
                } else {
                    DeviceConnectionAttempt.Connected(FakeActionLease(vehicle, gateway))
                }
            },
        )
        try {
            runtime.seedOnlineTarget(vehicle)
            runtime.onBackground()
            runtime.onNetworkAvailable()
            advanceUntilIdle()
            assertEquals(1, connectionCalls)
            assertEquals(0, discovery.calls)
            assertEquals(InstallationSessionState.MAINTENANCE, session.currentSnapshot().state)
            assertEquals(DeviceConnectionStatus.DISCONNECTED, session.currentSnapshot().device?.connectionStatus)
            assertEquals("known_endpoint_unavailable", session.currentSnapshot().failure?.reasonCode)
            assertEquals(vehicle, runtime.onlineTarget())

            runtime.onForeground()
            advanceUntilIdle()
            assertEquals(3, connectionCalls)
            assertEquals(1, discovery.calls)
            assertEquals(DeviceConnectionStatus.CONFIRMED, session.currentSnapshot().device?.connectionStatus)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `background connection loss and network recovery use only the known endpoint`() = runTest {
        val vehicle = fakeVehicle()
        val discovery = FakeDiscovery(vehicle)
        val gateway = ControlledRemoteInputGateway()
        val leases = mutableListOf<FakeActionLease>()
        var connectionCalls = 0
        val (runtime, session) = restoredRuntime(
            gateway,
            UnconfinedTestDispatcher(testScheduler),
            discovery,
            DeviceConnectionFactory { endpoint ->
                assertEquals(vehicle.endpoint, endpoint)
                connectionCalls += 1
                if (connectionCalls == 2) {
                    DeviceConnectionAttempt.Failed("known_endpoint_unavailable")
                } else {
                    val lease = FakeActionLease(vehicle, gateway)
                    leases += lease
                    DeviceConnectionAttempt.Connected(lease)
                }
            },
        )
        try {
            runtime.seedOnlineTarget(vehicle)
            runtime.onForeground()
            advanceUntilIdle()
            assertEquals(1, connectionCalls)

            runtime.onBackground()
            session.dispatchEvent(InstallationSessionEvent.DeviceDisconnected(vehicle.identity.stableId))
            advanceUntilIdle()
            assertEquals(2, connectionCalls)
            assertEquals(0, discovery.calls)
            assertEquals(1, leases.first().closeCalls)
            assertEquals(DeviceConnectionStatus.DISCONNECTED, session.currentSnapshot().device?.connectionStatus)

            runtime.onNetworkAvailable()
            advanceUntilIdle()
            assertEquals(3, connectionCalls)
            assertEquals(0, discovery.calls)
            assertEquals(DeviceConnectionStatus.CONFIRMED, session.currentSnapshot().device?.connectionStatus)
            assertEquals(0, leases.last().closeCalls)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `network callbacks return to the runtime dispatcher and retain one in flight handshake`() = runTest {
        val vehicle = fakeVehicle()
        val discovery = FakeDiscovery(vehicle)
        val gateway = ControlledRemoteInputGateway()
        val releaseHandshake = CompletableDeferred<Unit>()
        val effectThreads = mutableListOf<Thread>()
        val runtimeThread = Thread.currentThread()
        var connectionCalls = 0
        val (runtime, session) = restoredRuntime(
            gateway,
            StandardTestDispatcher(testScheduler),
            discovery,
            DeviceConnectionFactory {
                effectThreads += Thread.currentThread()
                connectionCalls += 1
                releaseHandshake.await()
                DeviceConnectionAttempt.Connected(FakeActionLease(vehicle, gateway))
            },
        )
        try {
            runtime.seedOnlineTarget(vehicle)
            runtime.onlineSessionObserver = { hosting ->
                if (hosting) effectThreads += Thread.currentThread()
            }
            thread(name = "remote-input-network-callback") { runtime.onNetworkAvailable() }.join()
            assertEquals(0, connectionCalls)
            assertEquals(InstallationSessionState.MAINTENANCE, session.currentSnapshot().state)

            runCurrent()
            assertEquals(1, connectionCalls)
            assertEquals(InstallationSessionState.CONNECTING, session.currentSnapshot().state)
            repeat(3) { runtime.onNetworkAvailable() }
            runCurrent()
            assertEquals(1, connectionCalls)

            releaseHandshake.complete(Unit)
            runCurrent()
            assertEquals(1, connectionCalls)
            assertEquals(1, gateway.subscribeCalls)
            assertEquals(0, discovery.calls)
            assertEquals(DeviceConnectionStatus.CONFIRMED, session.currentSnapshot().device?.connectionStatus)
            assertEquals(listOf(runtimeThread, runtimeThread), effectThreads)
        } finally {
            releaseHandshake.complete(Unit)
            runtime.close()
        }
    }

    @Test
    fun `input subscription retries stop after three attempts and hints do not replace pending work`() = runTest {
        val vehicle = fakeVehicle()
        val discovery = FakeDiscovery(vehicle)
        val gateway = FailingSubscriptionGateway()
        val lease = FakeActionLease(vehicle, gateway)
        var connectionCalls = 0
        val (runtime, session) = restoredRuntime(
            gateway,
            StandardTestDispatcher(testScheduler),
            discovery,
            DeviceConnectionFactory {
                connectionCalls += 1
                DeviceConnectionAttempt.Connected(lease)
            },
        )
        try {
            runtime.seedOnlineTarget(vehicle)
            runtime.onForeground()
            runCurrent()
            assertEquals(1, gateway.subscribeCalls)

            runtime.onNetworkAvailable()
            runtime.onForeground()
            runCurrent()
            assertEquals(1, gateway.subscribeCalls)

            advanceUntilIdle()
            assertEquals(4, gateway.subscribeCalls)
            assertEquals(1, connectionCalls)
            assertEquals(0, discovery.calls)
            assertEquals(0, lease.closeCalls)
            assertEquals(RemoteInputStatus.UNAVAILABLE, session.currentSnapshot().remoteInput.status)
            val exhaustedGeneration = session.currentSnapshot().remoteInput.connectionGeneration
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(4, gateway.subscribeCalls)
            assertEquals(exhaustedGeneration, session.currentSnapshot().remoteInput.connectionGeneration)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `first subscription frame failure uses the same bounded retry budget`() = runTest {
        val gateway = FailingSubscriptionGateway(failBeforeSubscription = true)
        val (runtime, session) = restoredRuntime(gateway, StandardTestDispatcher(testScheduler))
        try {
            runtime.seedOnlineTarget(fakeVehicle())
            runtime.onForeground()
            advanceUntilIdle()
            assertEquals(4, gateway.subscribeCalls)
            assertEquals(RemoteInputStatus.UNAVAILABLE, session.currentSnapshot().remoteInput.status)
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(4, gateway.subscribeCalls)
        } finally { runtime.close() }
    }

    @Test
    fun `old retry and disconnect events cannot replace or close a new lease`() = runTest {
        val vehicle = fakeVehicle()
        val discovery = FakeDiscovery(vehicle)
        val oldGateway = FailingSubscriptionGateway()
        val newGateway = ControlledRemoteInputGateway()
        val leases = mutableListOf<FakeActionLease>()
        var connectionCalls = 0
        val (runtime, session) = restoredRuntime(
            newGateway,
            StandardTestDispatcher(testScheduler),
            discovery,
            DeviceConnectionFactory {
                connectionCalls += 1
                val gateway = if (connectionCalls == 1) oldGateway else newGateway
                val lease = FakeActionLease(vehicle, gateway)
                leases += lease
                DeviceConnectionAttempt.Connected(lease)
            },
        )
        try {
            runtime.seedOnlineTarget(vehicle)
            runtime.onForeground()
            runCurrent()
            val oldSnapshot = session.currentSnapshot()
            assertEquals(1, oldGateway.subscribeCalls)

            runtime.onBackground()
            session.dispatchEvent(InstallationSessionEvent.DeviceDisconnected(vehicle.identity.stableId))
            runCurrent()
            newGateway.states.send(readyState())
            runCurrent()
            val reconnectedGeneration = session.currentSnapshot().remoteInput.connectionGeneration
            assertEquals(2, connectionCalls)
            assertEquals(1, leases.first().closeCalls)
            assertEquals(0, leases.last().closeCalls)
            assertEquals(RemoteInputStatus.READY, session.currentSnapshot().remoteInput.status)

            runtime.dispatch(InstallationSessionCommand.AdapterEvent(
                event = InstallationSessionEvent.DeviceDisconnected(vehicle.identity.stableId),
                sessionId = oldSnapshot.sessionId,
                sequence = oldSnapshot.lastEventSequence + 100L,
            ))
            repeat(3) { runtime.onNetworkAvailable() }
            advanceUntilIdle()
            assertEquals(2, connectionCalls)
            assertEquals(0, discovery.calls)
            assertEquals(1, oldGateway.subscribeCalls)
            assertEquals(1, newGateway.subscribeCalls)
            assertEquals(0, leases.last().closeCalls)
            assertEquals(reconnectedGeneration, session.currentSnapshot().remoteInput.connectionGeneration)
            assertEquals(RemoteInputStatus.READY, session.currentSnapshot().remoteInput.status)
        } finally {
            runtime.close()
        }
    }

    private fun connectedRuntime(
        gateway: ControlledRemoteInputGateway,
        dispatcher: CoroutineDispatcher,
    ): Pair<InstallerRuntime, InstallationSession> {
        val result = restoredRuntime(gateway, dispatcher)
        result.first.seedOnlineTarget(fakeVehicle())
        result.first.onForeground()
        return result
    }

    private fun restoredRuntime(
        gateway: RemoteInputGateway,
        dispatcher: CoroutineDispatcher,
        discovery: DeviceDiscovery = FakeDiscovery(fakeVehicle()),
        connectionFactory: DeviceConnectionFactory? = null,
    ): Pair<InstallerRuntime, InstallationSession> {
        val vehicle = fakeVehicle()
        val session = InstallationSession(
            initialSnapshot = InstallationSessionSnapshot(
                state = InstallationSessionState.MAINTENANCE,
                device = vehicle.toDeviceSummary().copy(
                    connectionStatus = DeviceConnectionStatus.DISCONNECTED,
                ),
                maintenance = MaintenanceSnapshot(routeAction = MaintenanceActionId.INPUT_TEXT),
            ),
        )
        val runtime = InstallerRuntime(
            session = session,
            createDiscoveryAdapter = { port ->
                DeviceDiscoverySessionAdapter(discovery, port)
            },
            createConnectionAdapter = { port ->
                DeviceConnectionSessionAdapter(
                    connectionFactory = connectionFactory ?: DeviceConnectionFactory {
                        DeviceConnectionAttempt.Connected(
                            FakeActionLease(vehicle, gateway),
                        )
                    },
                    eventPort = port,
                )
            },
            loadCatalog = {},
            coroutineContext = dispatcher,
        )
        return runtime to session
    }

    private fun readyState(): VehicleInputState = VehicleInputState(
        available = true,
        keyboard = "open",
        epoch = 1L,
        targetPackage = "com.example.target",
        targetWindow = 3,
        sequence = 1L,
    )

    private class ControlledRemoteInputGateway : RemoteInputGateway {
        val states = Channel<VehicleInputState>(Channel.UNLIMITED)
        val sendStarted = CompletableDeferred<Unit>()
        val releaseSend = Channel<Unit>(Channel.UNLIMITED)
        var subscribeCalls = 0
        var sendCalls = 0

        override suspend fun subscribe(): RemoteInputSubscription {
            subscribeCalls += 1
            return object : RemoteInputSubscription {
                override suspend fun next(): VehicleInputState = states.receive()

                override fun close() = Unit
            }
        }

        override suspend fun send(request: VehicleInputRequest): VehicleInputReceipt {
            sendCalls += 1
            sendStarted.complete(Unit)
            releaseSend.receive()
            return VehicleInputReceipt(request.id, committed = true, restored = true)
        }
    }

    private class FailingSubscriptionGateway(private val failBeforeSubscription: Boolean = false) : RemoteInputGateway {
        var subscribeCalls = 0

        override suspend fun subscribe(): RemoteInputSubscription {
            subscribeCalls += 1
            if (failBeforeSubscription) throw IOException("remote_input_channel_lost")
            return object : RemoteInputSubscription {
                override suspend fun next(): VehicleInputState = throw IOException("remote_input_channel_lost")

                override fun close() = Unit
            }
        }

        override suspend fun send(request: VehicleInputRequest): VehicleInputReceipt =
            error("failed subscriptions cannot send text")
    }

    private class FakeDiscovery(
        private val vehicle: ConnectedDevice,
    ) : DeviceDiscovery {
        var calls = 0

        override suspend fun discover(onDevice: suspend (ConnectedDevice) -> Unit): DeviceDiscoveryResult {
            calls += 1
            onDevice(vehicle)
            return DeviceDiscoveryResult(scannedCount = 1, confirmedCount = 1)
        }

        override fun cancel() = Unit
    }

    private class FakeActionLease(
        override val device: ConnectedDevice,
        override val remoteInputGateway: RemoteInputGateway,
    ) : DeviceActionConnectionLease {
        override val commandGateway: AdbCommandGateway = NoOpCommandGateway
        var closeCalls = 0

        override suspend fun check(): DeviceConnectionCheck = DeviceConnectionCheck(healthy = true)

        override fun close() {
            closeCalls += 1
        }
    }

    private object NoOpCommandGateway : AdbCommandGateway {
        override suspend fun installBatch(
            artifacts: List<InstallableArtifact>,
            strategy: com.ninepointnine.helper.domain.session.InstallationStrategy,
        ): DeviceInstallResult = error("device install is not part of input runtime smoke")

        override suspend fun runShortcut(
            shortcut: DeviceShortcut,
            selectedComponentIds: Set<String>,
            authorizationPlan: AuthorizationPlan,
        ): DeviceShortcutResult = error("device shortcut is not part of input runtime smoke")
    }

    private fun fakeVehicle(): ConnectedDevice = ConnectedDevice(
        endpoint = DeviceEndpoint("192.168.1.203"),
        identity = DeviceIdentity("adb:vehicle-1", "S56_HQX", 28),
        capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
    )

    private companion object {
        const val REQUEST_ONE = "00000000-0000-0000-0000-000000000001"
        const val REQUEST_TWO = "00000000-0000-0000-0000-000000000002"
    }
}
