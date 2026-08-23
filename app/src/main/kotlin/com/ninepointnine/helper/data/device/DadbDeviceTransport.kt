package com.ninepointnine.helper.data.device

import com.ninepointnine.helper.domain.device.DeviceConnectResult
import com.ninepointnine.helper.domain.device.DeviceConnectionAttempt
import com.ninepointnine.helper.domain.device.DeviceConnectionFactory
import com.ninepointnine.helper.domain.device.DeviceEndpoint

/**
 * Performs a bounded discovery probe and immediately releases its connection.
 * A selected device uses [DadbDeviceConnectionFactory] for a retained lease.
 */
class DadbDeviceTransport(
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    connectionFactory: DeviceConnectionFactory? = null,
) : com.ninepointnine.helper.domain.device.DeviceTransport {
    private val connectionFactory: DeviceConnectionFactory = connectionFactory ?: DadbDeviceConnectionFactory(
        connectTimeoutMillis = connectTimeoutMillis,
        readTimeoutMillis = readTimeoutMillis,
    )

    override suspend fun connect(endpoint: DeviceEndpoint): DeviceConnectResult = when (val attempt = connectionFactory.open(endpoint)) {
        is DeviceConnectionAttempt.Connected -> attempt.connection.use {
            DeviceConnectResult.Connected(it.device)
        }

        is DeviceConnectionAttempt.Failed -> DeviceConnectResult.Failed(
            reasonCode = attempt.reasonCode,
            retryable = attempt.retryable,
        )
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 350
        const val DEFAULT_READ_TIMEOUT_MILLIS = 900
    }
}
