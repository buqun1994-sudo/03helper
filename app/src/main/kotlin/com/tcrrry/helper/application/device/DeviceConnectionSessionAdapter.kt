package com.tcrrry.helper.application.device

import com.tcrrry.helper.application.session.InstallationSessionEventPort
import com.tcrrry.helper.domain.device.ConnectedDevice
import com.tcrrry.helper.domain.device.DeviceConnectionAttempt
import com.tcrrry.helper.domain.device.DeviceConnectionFactory
import com.tcrrry.helper.domain.device.DeviceConnectionLease
import com.tcrrry.helper.domain.session.InstallationSessionEvent

/** Opens the selected endpoint and reports only structured connection evidence. */
class DeviceConnectionSessionAdapter(
    private val connectionFactory: DeviceConnectionFactory,
    private val eventPort: InstallationSessionEventPort,
) {
    suspend fun connect(expected: ConnectedDevice): DeviceConnectionLease? {
        return when (val attempt = connectionFactory.open(expected.endpoint)) {
            is DeviceConnectionAttempt.Connected -> {
                val connection = attempt.connection
                if (connection.device.endpoint != expected.endpoint || connection.device.identity != expected.identity) {
                    connection.close()
                    eventPort.emit(
                        InstallationSessionEvent.DeviceConnectionFailed(
                            deviceId = expected.identity.stableId,
                            reasonCode = "device_identity_changed",
                            retryable = false,
                        ),
                    )
                    null
                } else {
                    eventPort.emit(
                        InstallationSessionEvent.DeviceConnectionConfirmed(
                            connection.device.toDeviceSummary(lastConfirmedLabel = "已连接"),
                        ),
                    )
                    connection
                }
            }

            is DeviceConnectionAttempt.Failed -> {
                eventPort.emit(
                    InstallationSessionEvent.DeviceConnectionFailed(
                        deviceId = expected.identity.stableId,
                        reasonCode = attempt.reasonCode,
                        retryable = attempt.retryable,
                    ),
                )
                null
            }
        }
    }
}
