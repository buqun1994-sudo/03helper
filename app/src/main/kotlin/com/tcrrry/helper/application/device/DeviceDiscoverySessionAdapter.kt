package com.tcrrry.helper.application.device

import com.tcrrry.helper.domain.device.ConnectedDevice
import com.tcrrry.helper.domain.device.DeviceDiscovery
import com.tcrrry.helper.domain.device.DeviceDiscoveryResult
import com.tcrrry.helper.application.session.InstallationSessionEventPort
import com.tcrrry.helper.domain.session.InstallationSessionEvent
import java.util.concurrent.ConcurrentHashMap

/** Bridges one discovery generation into the existing session event contract. */
class DeviceDiscoverySessionAdapter(
    private val discovery: DeviceDiscovery,
    private val eventPort: InstallationSessionEventPort,
) {
    private val confirmedDevices = ConcurrentHashMap<String, ConnectedDevice>()

    suspend fun discover(): DeviceDiscoveryResult {
        confirmedDevices.clear()
        val result = discovery.discover { device ->
            confirmedDevices[device.identity.stableId] = device
            eventPort.emit(
                InstallationSessionEvent.DeviceDiscovered(device.toDeviceSummary()),
            )
        }
        eventPort.emit(
            InstallationSessionEvent.DiscoveryFinished(
                scannedCount = result.scannedCount,
                confirmedCount = result.confirmedCount,
                reasonCode = result.reasonCode,
            ),
        )
        return result
    }

    fun cancel() {
        discovery.cancel()
    }

    fun confirmedDevice(deviceId: String): ConnectedDevice? = confirmedDevices[deviceId]
}
