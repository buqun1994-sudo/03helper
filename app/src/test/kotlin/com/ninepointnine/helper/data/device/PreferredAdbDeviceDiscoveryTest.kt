package com.ninepointnine.helper.data.device

import com.ninepointnine.helper.domain.device.ConnectedDevice
import com.ninepointnine.helper.domain.device.DeviceCapability
import com.ninepointnine.helper.domain.device.DeviceDiscovery
import com.ninepointnine.helper.domain.device.DeviceDiscoveryResult
import com.ninepointnine.helper.domain.device.DeviceEndpoint
import com.ninepointnine.helper.domain.device.DeviceIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferredAdbDeviceDiscoveryTest {
    @Test
    fun `wireless candidate prevents usb fallback`() = runBlocking {
        val wireless = StubDiscovery(
            result = DeviceDiscoveryResult(scannedCount = 2, confirmedCount = 1),
            device = device(DeviceEndpoint("192.168.1.203")),
        )
        val wired = StubDiscovery(
            result = DeviceDiscoveryResult(scannedCount = 1, confirmedCount = 1),
            device = device(DeviceEndpoint.usb("/dev/bus/usb/001/002")),
        )
        val emitted = mutableListOf<ConnectedDevice>()

        val result = PreferredAdbDeviceDiscovery(wireless, wired).discover { emitted += it }

        assertEquals(1, wireless.calls)
        assertEquals(0, wired.calls)
        assertEquals(1, result.confirmedCount)
        assertEquals(DeviceEndpoint("192.168.1.203"), emitted.single().endpoint)
    }

    @Test
    fun `usb fallback runs only after wireless has no candidate`() = runBlocking {
        val wireless = StubDiscovery(DeviceDiscoveryResult(253, 0, "no_adb_devices_found"))
        val wired = StubDiscovery(
            result = DeviceDiscoveryResult(1, 1),
            device = device(DeviceEndpoint.usb("/dev/bus/usb/001/002")),
        )
        val emitted = mutableListOf<ConnectedDevice>()

        val result = PreferredAdbDeviceDiscovery(wireless, wired).discover { emitted += it }

        assertEquals(1, wireless.calls)
        assertEquals(1, wired.calls)
        assertEquals(254, result.scannedCount)
        assertEquals(1, result.confirmedCount)
        assertTrue(emitted.single().endpoint.transport == com.ninepointnine.helper.domain.device.DeviceTransportKind.USB)
    }

    private class StubDiscovery(
        private val result: DeviceDiscoveryResult,
        private val device: ConnectedDevice? = null,
    ) : DeviceDiscovery {
        var calls = 0

        override suspend fun discover(onDevice: suspend (ConnectedDevice) -> Unit): DeviceDiscoveryResult {
            calls += 1
            device?.let { onDevice(it) }
            return result
        }

        override fun cancel() = Unit
    }

    private fun device(endpoint: DeviceEndpoint) = ConnectedDevice(
        endpoint = endpoint,
        identity = DeviceIdentity("adb:vehicle-1", "S56_HQX", 28),
        capabilities = setOf(DeviceCapability.IDENTITY_READ),
    )
}
