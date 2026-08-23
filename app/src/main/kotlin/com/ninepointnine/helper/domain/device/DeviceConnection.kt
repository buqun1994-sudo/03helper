package com.ninepointnine.helper.domain.device

/** A scoped ADB connection owned by the application layer until it is closed. */
interface DeviceConnectionLease : AutoCloseable {
    val device: ConnectedDevice

    /** Performs one fixed, read-only identity round trip against the live connection. */
    suspend fun check(): DeviceConnectionCheck
}

data class DeviceConnectionCheck(
    val healthy: Boolean,
    val reasonCode: String? = null,
)

sealed interface DeviceConnectionAttempt {
    data class Connected(val connection: DeviceConnectionLease) : DeviceConnectionAttempt

    data class Failed(
        val reasonCode: String,
        val retryable: Boolean = true,
    ) : DeviceConnectionAttempt
}

fun interface DeviceConnectionFactory {
    suspend fun open(endpoint: DeviceEndpoint): DeviceConnectionAttempt
}
