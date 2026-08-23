package com.ninepointnine.helper.application.device

import com.ninepointnine.helper.application.session.InstallationSessionEventPort
import com.ninepointnine.helper.domain.device.ConnectedDevice
import com.ninepointnine.helper.domain.device.DeviceCapability
import com.ninepointnine.helper.domain.device.DeviceConnectionAttempt
import com.ninepointnine.helper.domain.device.DeviceConnectionCheck
import com.ninepointnine.helper.domain.device.DeviceConnectionFactory
import com.ninepointnine.helper.domain.device.DeviceConnectionLease
import com.ninepointnine.helper.domain.device.DeviceEndpoint
import com.ninepointnine.helper.domain.device.DeviceIdentity
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
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

    private class FakeLease(override val device: ConnectedDevice) : DeviceConnectionLease {
        var closed = false

        override suspend fun check(): DeviceConnectionCheck = DeviceConnectionCheck(true)

        override fun close() {
            closed = true
        }
    }

    private fun device(stableId: String): ConnectedDevice = ConnectedDevice(
        endpoint = DeviceEndpoint("192.168.1.203"),
        identity = DeviceIdentity(stableId, "S56_HQX", 28),
        capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
    )

    private fun ConnectedDevice.id(): String = identity.stableId
}
