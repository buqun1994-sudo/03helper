package com.ninepointnine.helper.application.device

import com.ninepointnine.helper.application.session.InstallationSessionEventPort
import com.ninepointnine.helper.domain.device.ConnectedDevice
import com.ninepointnine.helper.domain.device.AdbCommandGateway
import com.ninepointnine.helper.domain.device.AuthorizationPlan
import com.ninepointnine.helper.domain.device.DeviceCapability
import com.ninepointnine.helper.domain.device.DeviceConnectionAttempt
import com.ninepointnine.helper.domain.device.DeviceConnectionCheck
import com.ninepointnine.helper.domain.device.DeviceConnectionFactory
import com.ninepointnine.helper.domain.device.DeviceConnectionLease
import com.ninepointnine.helper.domain.device.DeviceActionConnectionLease
import com.ninepointnine.helper.domain.device.DeviceDiscovery
import com.ninepointnine.helper.domain.device.DeviceDiscoveryResult
import com.ninepointnine.helper.domain.device.DeviceEndpoint
import com.ninepointnine.helper.domain.device.DeviceIdentity
import com.ninepointnine.helper.domain.device.DeviceInstallResult
import com.ninepointnine.helper.domain.device.DeviceShortcut
import com.ninepointnine.helper.domain.device.DeviceShortcutResult
import com.ninepointnine.helper.domain.device.InstallableArtifact
import com.ninepointnine.helper.domain.device.WirelessAdbEnableResult
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import com.ninepointnine.helper.domain.session.InstallationStrategy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceConnectionSessionAdapterTest {
    @Test
    fun `successful second handshake emits confirmation and returns retained lease`() = runBlocking {
        val events = mutableListOf<InstallationSessionEvent>()
        val device = device("adb:vehicle-1")
        val lease = FakeLease(device)
        val adapter = DeviceConnectionSessionAdapter(
            connectionFactory = DeviceConnectionFactory {
                DeviceConnectionAttempt.Connected(lease)
            },
            eventPort = InstallationSessionEventPort { events += it },
        )

        val result = adapter.connect(device)

        assertEquals(lease, result)
        val confirmed = events.single() as InstallationSessionEvent.DeviceConnectionConfirmed
        assertEquals(device.id(), confirmed.device.id)
        assertEquals(DeviceConnectionStatus.CONFIRMED, confirmed.device.connectionStatus)
        assertTrue(!lease.closed)
    }

    @Test
    fun `identity change closes lease and fails closed`() = runBlocking {
        val events = mutableListOf<InstallationSessionEvent>()
        val expected = device("adb:vehicle-1")
        val lease = FakeLease(device("adb:other"))
        val adapter = DeviceConnectionSessionAdapter(
            connectionFactory = DeviceConnectionFactory {
                DeviceConnectionAttempt.Connected(lease)
            },
            eventPort = InstallationSessionEventPort { events += it },
        )

        val result = adapter.connect(expected)

        assertNull(result)
        assertTrue(lease.closed)
        val failed = events.single() as InstallationSessionEvent.DeviceConnectionFailed
        assertEquals("device_identity_changed", failed.reasonCode)
    }

    @Test
    fun `usb lease is upgraded to the same device over wireless adb`() = runBlocking {
        val events = mutableListOf<InstallationSessionEvent>()
        val identity = DeviceIdentity("adb:vehicle-1", "S56_HQX", 28)
        val wired = ActionLease(
            ConnectedDevice(
                endpoint = DeviceEndpoint.usb("/dev/bus/usb/001/002"),
                identity = identity,
                capabilities = setOf(DeviceCapability.ADB_USB, DeviceCapability.IDENTITY_READ),
            ),
        )
        val wireless = FakeLease(
            ConnectedDevice(
                endpoint = DeviceEndpoint("192.168.1.203"),
                identity = identity,
                capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
            ),
        )
        val adapter = DeviceConnectionSessionAdapter(
            connectionFactory = DeviceConnectionFactory { endpoint ->
                if (endpoint.transport == com.ninepointnine.helper.domain.device.DeviceTransportKind.USB) {
                    DeviceConnectionAttempt.Connected(wired)
                } else {
                    DeviceConnectionAttempt.Connected(wireless)
                }
            },
            eventPort = InstallationSessionEventPort { events += it },
            wirelessDiscovery = object : DeviceDiscovery {
                override suspend fun discover(onDevice: suspend (ConnectedDevice) -> Unit): DeviceDiscoveryResult {
                    onDevice(wireless.device)
                    return DeviceDiscoveryResult(1, 1)
                }

                override fun cancel() = Unit
            },
            wirelessSearchTimeoutMillis = 1_000L,
        )

        val result = adapter.connect(wired.device)

        assertEquals(wireless, result)
        assertTrue(wired.closed)
        assertEquals(1, events.size)
        assertEquals(
            DeviceEndpoint("192.168.1.203"),
            (events.single() as InstallationSessionEvent.DeviceConnectionConfirmed).device.let {
                wireless.device.endpoint
            },
        )
    }

    @Test
    fun `usb lease remains usable when wireless adb cannot be enabled`() = runBlocking {
        val events = mutableListOf<InstallationSessionEvent>()
        val expected = device("adb:vehicle-1").copy(
            endpoint = DeviceEndpoint.usb("/dev/bus/usb/001/002"),
            capabilities = setOf(DeviceCapability.ADB_USB, DeviceCapability.IDENTITY_READ),
        )
        val wired = ActionLease(
            expected,
            wirelessEnableResult = WirelessAdbEnableResult.Failed(
                com.ninepointnine.helper.domain.device.DeviceActionFailure(
                    "wireless_adb_enable_failed",
                    retryable = true,
                ),
            ),
        )
        val adapter = DeviceConnectionSessionAdapter(
            connectionFactory = DeviceConnectionFactory {
                DeviceConnectionAttempt.Connected(wired)
            },
            eventPort = InstallationSessionEventPort { events += it },
            wirelessDiscovery = NoopDiscovery,
        )

        val result = adapter.connect(expected)

        assertEquals(wired, result)
        assertTrue(!wired.closed)
        assertTrue(events.single() is InstallationSessionEvent.DeviceConnectionConfirmed)
    }

    @Test
    fun `usb lease reopens wired connection when wireless search finds nothing`() = runBlocking {
        val events = mutableListOf<InstallationSessionEvent>()
        val identity = DeviceIdentity("adb:vehicle-1", "S56_HQX", 28)
        val endpoint = DeviceEndpoint.usb("/dev/bus/usb/001/002")
        val firstWired = ActionLease(
            ConnectedDevice(endpoint, identity, setOf(DeviceCapability.ADB_USB, DeviceCapability.IDENTITY_READ)),
        )
        val reopenedWired = ActionLease(
            ConnectedDevice(endpoint, identity, setOf(DeviceCapability.ADB_USB, DeviceCapability.IDENTITY_READ)),
        )
        var usbOpens = 0
        val adapter = DeviceConnectionSessionAdapter(
            connectionFactory = DeviceConnectionFactory { requested ->
                assertEquals(endpoint, requested)
                usbOpens += 1
                DeviceConnectionAttempt.Connected(if (usbOpens == 1) firstWired else reopenedWired)
            },
            eventPort = InstallationSessionEventPort { events += it },
            wirelessDiscovery = NoopDiscovery,
        )

        val result = adapter.connect(firstWired.device)

        assertEquals(reopenedWired, result)
        assertTrue(firstWired.closed)
        assertTrue(!reopenedWired.closed)
        assertEquals(2, usbOpens)
        assertTrue(events.single() is InstallationSessionEvent.DeviceConnectionConfirmed)
    }

    private class FakeLease(override val device: ConnectedDevice) : DeviceConnectionLease {
        var closed = false

        override suspend fun check(): DeviceConnectionCheck = DeviceConnectionCheck(true)

        override fun close() {
            closed = true
        }
    }

    private class ActionLease(
        override val device: ConnectedDevice,
        private val wirelessEnableResult: WirelessAdbEnableResult = WirelessAdbEnableResult.Enabled(
            DeviceEndpoint.DEFAULT_ADB_PORT,
        ),
    ) : DeviceActionConnectionLease {
        override val commandGateway: AdbCommandGateway = object : AdbCommandGateway {
            override suspend fun enableWirelessAdb(port: Int): WirelessAdbEnableResult =
                wirelessEnableResult

            override suspend fun installBatch(
                artifacts: List<InstallableArtifact>,
                strategy: InstallationStrategy,
            ): DeviceInstallResult = error("unused")

            override suspend fun runShortcut(
                shortcut: DeviceShortcut,
                selectedComponentIds: Set<String>,
                authorizationPlan: AuthorizationPlan,
            ): DeviceShortcutResult = error("unused")
        }
        var closed = false

        override suspend fun check(): DeviceConnectionCheck = DeviceConnectionCheck(true)

        override fun close() {
            closed = true
        }
    }

    private object NoopDiscovery : DeviceDiscovery {
        override suspend fun discover(onDevice: suspend (ConnectedDevice) -> Unit): DeviceDiscoveryResult =
            DeviceDiscoveryResult(0, 0, "no_adb_devices_found")

        override fun cancel() = Unit
    }

    private fun device(stableId: String): ConnectedDevice = ConnectedDevice(
        endpoint = DeviceEndpoint("192.168.1.203"),
        identity = DeviceIdentity(stableId, "S56_HQX", 28),
        capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
    )

    private fun ConnectedDevice.id(): String = identity.stableId
}
