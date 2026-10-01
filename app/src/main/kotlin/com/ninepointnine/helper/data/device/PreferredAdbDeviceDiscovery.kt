package com.ninepointnine.helper.data.device

import com.ninepointnine.helper.domain.device.ConnectedDevice
import com.ninepointnine.helper.domain.device.DeviceDiscovery
import com.ninepointnine.helper.domain.device.DeviceDiscoveryResult
import java.util.concurrent.atomic.AtomicBoolean

/** One bounded preference rule: LAN ADB first, USB ADB only when LAN has no candidate. */
class PreferredAdbDeviceDiscovery(
    private val wireless: DeviceDiscovery,
    private val wired: DeviceDiscovery,
) : DeviceDiscovery {
    private val cancelled = AtomicBoolean(false)

    override suspend fun discover(onDevice: suspend (ConnectedDevice) -> Unit): DeviceDiscoveryResult {
        cancelled.set(false)
        val wirelessResult = wireless.discover(onDevice)
        if (cancelled.get()) return wirelessResult.copy(reasonCode = "discovery_cancelled")
        if (wirelessResult.confirmedCount > 0) return wirelessResult

        val wiredResult = wired.discover(onDevice)
        return DeviceDiscoveryResult(
            scannedCount = wirelessResult.scannedCount + wiredResult.scannedCount,
            confirmedCount = wiredResult.confirmedCount,
            reasonCode = when {
                cancelled.get() -> "discovery_cancelled"
                wiredResult.confirmedCount > 0 -> null
                wiredResult.reasonCode != null -> wiredResult.reasonCode
                else -> wirelessResult.reasonCode ?: "no_adb_devices_found"
            },
        )
    }

    override fun cancel() {
        cancelled.set(true)
        wireless.cancel()
        wired.cancel()
    }
}
