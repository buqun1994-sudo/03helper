package com.ninepointnine.helper.data.device

import com.ninepointnine.helper.domain.device.ConnectedDevice
import com.ninepointnine.helper.domain.device.DeviceCapability
import com.ninepointnine.helper.domain.device.DeviceConnectionAttempt
import com.ninepointnine.helper.domain.device.DeviceConnectionCheck
import com.ninepointnine.helper.domain.device.DeviceConnectionFactory
import com.ninepointnine.helper.domain.device.DeviceConnectionLease
import com.ninepointnine.helper.domain.device.DeviceEndpoint
import com.ninepointnine.helper.domain.device.DeviceIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DadbDeviceTransportTest {
    @Test
    fun `discovery probe closes its lease after identity confirmation`() = runBlocking {
        val lease = TrackingLease()
        val transport = DadbDeviceTransport(
            connectionFactory = DeviceConnectionFactory {
                DeviceConnectionAttempt.Connected(lease)
            },
        )

        val result = transport.connect(DeviceEndpoint("192.168.1.203"))

        assertEquals("adb:vehicle-1", (result as com.ninepointnine.helper.domain.device.DeviceConnectResult.Connected).device.identity.stableId)
        assertTrue(lease.closed)
    }

    @Test
    fun `connection factory failure remains structured`() = runBlocking {
        val transport = DadbDeviceTransport(
            connectionFactory = DeviceConnectionFactory {
                DeviceConnectionAttempt.Failed("adb_connect_failed", retryable = true)
            },
        )

        val result = transport.connect(DeviceEndpoint("192.168.1.203"))

        val failed = result as com.ninepointnine.helper.domain.device.DeviceConnectResult.Failed
        assertEquals("adb_connect_failed", failed.reasonCode)
        assertTrue(failed.retryable)
    }

    private class TrackingLease : DeviceConnectionLease {
        override val device = ConnectedDevice(
            endpoint = DeviceEndpoint("192.168.1.203"),
            identity = DeviceIdentity("adb:vehicle-1", "S56_HQX", 28),
            capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
        )
        var closed = false

        override suspend fun check(): DeviceConnectionCheck = DeviceConnectionCheck(true)

        override fun close() {
            closed = true
        }
    }
}
