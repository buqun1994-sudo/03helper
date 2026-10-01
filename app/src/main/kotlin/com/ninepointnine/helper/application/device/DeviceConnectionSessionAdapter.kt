package com.ninepointnine.helper.application.device

import com.ninepointnine.helper.application.session.InstallationSessionEventPort
import com.ninepointnine.helper.domain.device.ConnectedDevice
import com.ninepointnine.helper.domain.device.DeviceActionConnectionLease
import com.ninepointnine.helper.domain.device.DeviceConnectionAttempt
import com.ninepointnine.helper.domain.device.DeviceConnectionFactory
import com.ninepointnine.helper.domain.device.DeviceConnectionLease
import com.ninepointnine.helper.domain.device.DeviceDiscovery
import com.ninepointnine.helper.domain.device.DeviceTransportKind
import com.ninepointnine.helper.domain.device.WirelessAdbEnableResult
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/** Opens the selected endpoint and reports only structured connection evidence. */
class DeviceConnectionSessionAdapter(
    private val connectionFactory: DeviceConnectionFactory,
    private val eventPort: InstallationSessionEventPort,
    private val wirelessDiscovery: DeviceDiscovery? = null,
    private val wirelessSearchTimeoutMillis: Long = DEFAULT_WIRELESS_SEARCH_TIMEOUT_MILLIS,
) {
    init {
        require(wirelessSearchTimeoutMillis in 1_000L..30_000L) {
            "wireless_search_timeout_invalid"
        }
    }

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
                } else if (expected.endpoint.transport == DeviceTransportKind.USB) {
                    upgradeUsbConnection(expected, connection)
                } else {
                    confirm(connection)
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

    private suspend fun upgradeUsbConnection(
        expected: ConnectedDevice,
        wiredConnection: DeviceConnectionLease,
    ): DeviceConnectionLease? {
        val actionLease = wiredConnection as? DeviceActionConnectionLease
        val discovery = wirelessDiscovery
        if (actionLease == null || discovery == null) return confirm(wiredConnection)

        val enableResult = try {
            actionLease.commandGateway.enableWirelessAdb()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WirelessAdbEnableResult.Failed(
                com.ninepointnine.helper.domain.device.DeviceActionFailure(
                    "wireless_adb_enable_failed",
                    retryable = true,
                ),
            )
        }
        if (enableResult is WirelessAdbEnableResult.Failed) {
            // USB is the bounded fallback when the target rejects or does not
            // support the temporary wireless-ADB control action. The verified
            // wired lease remains usable; a later explicit reconnect can retry
            // the wireless upgrade.
            return confirm(wiredConnection)
        }

        val wirelessDevice = findWirelessDevice(discovery, expected.identity.stableId)
        if (wirelessDevice != null) {
            when (val wirelessAttempt = connectionFactory.open(wirelessDevice.endpoint)) {
                is DeviceConnectionAttempt.Connected -> {
                    val wirelessConnection = wirelessAttempt.connection
                    if (wirelessConnection.device.identity == expected.identity) {
                        wiredConnection.close()
                        return confirm(wirelessConnection)
                    }
                    wirelessConnection.close()
                }

                is DeviceConnectionAttempt.Failed -> Unit
            }
        }

        // Restarting adbd normally tears down the first USB session. Re-open
        // the same authorized endpoint before allowing the wired fallback.
        wiredConnection.close()
        return when (val fallbackAttempt = connectionFactory.open(expected.endpoint)) {
            is DeviceConnectionAttempt.Connected -> {
                val fallback = fallbackAttempt.connection
                if (fallback.device.identity == expected.identity) {
                    confirm(fallback)
                } else {
                    fallback.close()
                    failAfterUsbUpgrade(expected)
                }
            }

            is DeviceConnectionAttempt.Failed -> failAfterUsbUpgrade(expected)
        }
    }

    private suspend fun findWirelessDevice(
        discovery: DeviceDiscovery,
        stableId: String,
    ): ConnectedDevice? {
        val match = AtomicReference<ConnectedDevice?>(null)
        try {
            withTimeoutOrNull(wirelessSearchTimeoutMillis) {
                discovery.discover { device ->
                    if (device.identity.stableId == stableId && match.compareAndSet(null, device)) {
                        discovery.cancel()
                    }
                }
            }
        } finally {
            discovery.cancel()
        }
        return match.get()
    }

    private suspend fun confirm(connection: DeviceConnectionLease): DeviceConnectionLease {
        eventPort.emit(
            InstallationSessionEvent.DeviceConnectionConfirmed(
                connection.device.toDeviceSummary(lastConfirmedLabel = "已连接"),
            ),
        )
        return connection
    }

    private suspend fun failAfterUsbUpgrade(
        expected: ConnectedDevice,
        reasonCode: String = "wireless_adb_not_found_after_usb_enable",
        retryable: Boolean = true,
    ): DeviceConnectionLease? {
        eventPort.emit(
            InstallationSessionEvent.DeviceConnectionFailed(
                deviceId = expected.identity.stableId,
                reasonCode = reasonCode,
                retryable = retryable,
            ),
        )
        return null
    }

    private companion object {
        const val DEFAULT_WIRELESS_SEARCH_TIMEOUT_MILLIS = 10_000L
    }
}
