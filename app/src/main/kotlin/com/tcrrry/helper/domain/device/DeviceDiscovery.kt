package com.tcrrry.helper.domain.device

/** A bounded LAN endpoint candidate. The endpoint never crosses into UI state. */
data class DeviceEndpoint(
    val host: String,
    val port: Int = DEFAULT_ADB_PORT,
) {
    init {
        require(host.isValidIpv4()) { "device_host_invalid" }
        require(port in 1..65535) { "device_port_invalid" }
    }

    companion object {
        const val DEFAULT_ADB_PORT = 5555
    }
}

data class DeviceIdentity(
    val stableId: String,
    val model: String,
    val androidSdk: Int,
)

enum class DeviceCapability {
    ADB_TCP,
    IDENTITY_READ,
}

data class ConnectedDevice(
    val endpoint: DeviceEndpoint,
    val identity: DeviceIdentity,
    val capabilities: Set<DeviceCapability>,
)

sealed interface DeviceConnectResult {
    data class Connected(val device: ConnectedDevice) : DeviceConnectResult

    data class Failed(
        val reasonCode: String,
        val retryable: Boolean = true,
    ) : DeviceConnectResult
}

interface DeviceTransport {
    suspend fun connect(endpoint: DeviceEndpoint): DeviceConnectResult
}

data class DeviceDiscoveryResult(
    val scannedCount: Int,
    val confirmedCount: Int,
    val reasonCode: String? = null,
)

interface DeviceDiscovery {
    suspend fun discover(onDevice: suspend (ConnectedDevice) -> Unit): DeviceDiscoveryResult

    fun cancel()
}

private fun String.isValidIpv4(): Boolean {
    val parts = split('.')
    return parts.size == 4 && parts.all { part ->
        part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
    }
}
