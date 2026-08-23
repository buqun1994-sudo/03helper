package com.ninepointnine.helper.application.device

import com.ninepointnine.helper.application.session.InstallationSessionEventPort
import com.ninepointnine.helper.domain.device.ConnectedDevice
import com.ninepointnine.helper.domain.device.DeviceCapability
import com.ninepointnine.helper.domain.device.DeviceDiscovery
import com.ninepointnine.helper.domain.device.DeviceDiscoveryResult
import com.ninepointnine.helper.domain.device.DeviceEndpoint
import com.ninepointnine.helper.domain.device.DeviceIdentity
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDiscoverySessionAdapterTest {
    @Test
    fun `confirmed device and finished result use the single structured event port`() = runBlocking {
        val events = mutableListOf<InstallationSessionEvent>()
        val device = ConnectedDevice(
            endpoint = DeviceEndpoint("192.168.1.203"),
            identity = DeviceIdentity("adb:vehicle-1", "S56_HQX", 28),
            capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
        )
        val adapter = DeviceDiscoverySessionAdapter(
            discovery = object : DeviceDiscovery {
                override suspend fun discover(onDevice: suspend (ConnectedDevice) -> Unit): DeviceDiscoveryResult {
                    onDevice(device)
                    return DeviceDiscoveryResult(scannedCount = 4, confirmedCount = 1)
                }

                override fun cancel() = Unit
            },
            eventPort = InstallationSessionEventPort { events += it },
        )

        adapter.discover()

        val discovered = events.filterIsInstance<InstallationSessionEvent.DeviceDiscovered>().single().device
        assertEquals("adb:vehicle-1", discovered.id)
        assertEquals(28, discovered.androidSdk)
        assertTrue(discovered.capabilities.contains(DeviceCapability.ADB_TCP))
        assertEquals(
            InstallationSessionEvent.DiscoveryFinished(scannedCount = 4, confirmedCount = 1),
            events.last(),
        )
    }
}
