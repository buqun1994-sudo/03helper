package com.tcrrry.helper.data.device

import com.tcrrry.helper.domain.device.ConnectedDevice
import com.tcrrry.helper.domain.device.DeviceCapability
import com.tcrrry.helper.domain.device.DeviceConnectResult
import com.tcrrry.helper.domain.device.DeviceEndpoint
import com.tcrrry.helper.domain.device.DeviceIdentity
import com.tcrrry.helper.domain.device.DeviceTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAdbDeviceDiscoveryTest {
    @Test
    fun `subnet excludes network and broadcast addresses`() {
        val subnet = Ipv4Subnet(0xC0A80164.toInt(), 30)

        assertEquals(listOf("192.168.1.101", "192.168.1.102"), subnet.hostAddresses())
        val oversizedSubnet = Ipv4Subnet(0xC0A80101.toInt(), 16)
        assertTrue(oversizedSubnet.hostAddresses().isEmpty())
        assertTrue(oversizedSubnet.exceedsHostLimit())
    }

    @Test
    fun `discovery emits each confirmed identity once`() = runBlocking {
        val observedHosts = mutableListOf<String>()
        val discovery = LanAdbDeviceDiscovery(
            subnetProvider = LocalIpv4SubnetProvider { listOf(Ipv4Subnet(0xC0A80164.toInt(), 30)) },
            transport = object : DeviceTransport {
                override suspend fun connect(endpoint: DeviceEndpoint): DeviceConnectResult {
                    observedHosts += endpoint.host
                    return DeviceConnectResult.Connected(device(endpoint, stableId = "adb:shared-device"))
                }
            },
            maxParallel = 1,
            probeTimeoutMillis = 1_000L,
            overallTimeoutMillis = 5_000L,
        )
        val emitted = mutableListOf<ConnectedDevice>()

        val result = discovery.discover { emitted += it }

        assertEquals(setOf("192.168.1.101", "192.168.1.102"), observedHosts.toSet())
        assertEquals(2, result.scannedCount)
        assertEquals(1, result.confirmedCount)
        assertEquals(1, emitted.size)
    }

    @Test
    fun `cancellation stops remaining probes and reports a structured result`() = runBlocking {
        lateinit var discovery: LanAdbDeviceDiscovery
        var probeCount = 0
        discovery = LanAdbDeviceDiscovery(
            subnetProvider = LocalIpv4SubnetProvider { listOf(Ipv4Subnet(0xC0A80164.toInt(), 30)) },
            transport = object : DeviceTransport {
                override suspend fun connect(endpoint: DeviceEndpoint): DeviceConnectResult {
                    probeCount += 1
                    discovery.cancel()
                    return DeviceConnectResult.Failed("test_cancelled")
                }
            },
            maxParallel = 1,
            probeTimeoutMillis = 1_000L,
            overallTimeoutMillis = 5_000L,
        )

        val result = discovery.discover { error("cancelled discovery must not emit devices") }

        assertEquals(1, probeCount)
        assertEquals("discovery_cancelled", result.reasonCode)
        assertEquals(0, result.confirmedCount)
    }

    @Test
    fun `oversized only subnet reports a bounded-scope failure`() = runBlocking {
        val discovery = LanAdbDeviceDiscovery(
            subnetProvider = LocalIpv4SubnetProvider { listOf(Ipv4Subnet(0xC0A80101.toInt(), 16)) },
            transport = object : DeviceTransport {
                override suspend fun connect(endpoint: DeviceEndpoint): DeviceConnectResult =
                    DeviceConnectResult.Failed("must_not_probe")
            },
        )

        val result = discovery.discover { error("oversized scope must not emit devices") }

        assertEquals("discovery_scope_too_large", result.reasonCode)
        assertEquals(0, result.scannedCount)
    }

    private fun device(endpoint: DeviceEndpoint, stableId: String): ConnectedDevice = ConnectedDevice(
        endpoint = endpoint,
        identity = DeviceIdentity(stableId, "S56_HQX", 28),
        capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
    )
}
