package com.ninepointnine.helper.data.device

import com.ninepointnine.helper.domain.device.ConnectedDevice
import com.ninepointnine.helper.domain.device.DeviceDiscovery
import com.ninepointnine.helper.domain.device.DeviceDiscoveryResult
import com.ninepointnine.helper.domain.device.DeviceEndpoint
import com.ninepointnine.helper.domain.device.DeviceTransport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Bounded LAN probing for legacy TCP ADB devices, started by foreground or explicit retry actions. */
class LanAdbDeviceDiscovery(
    private val subnetProvider: LocalIpv4SubnetProvider,
    private val transport: DeviceTransport,
    private val maxParallel: Int = DEFAULT_MAX_PARALLEL,
    private val probeTimeoutMillis: Long = DEFAULT_PROBE_TIMEOUT_MILLIS,
    private val overallTimeoutMillis: Long = DEFAULT_OVERALL_TIMEOUT_MILLIS,
) : DeviceDiscovery {
    private val cancelled = AtomicBoolean(false)

    init {
        require(maxParallel in 1..64) { "discovery_parallelism_invalid" }
        require(probeTimeoutMillis in 100L..5_000L) { "discovery_probe_timeout_invalid" }
        require(overallTimeoutMillis in 1_000L..30_000L) { "discovery_timeout_invalid" }
    }

    override suspend fun discover(onDevice: suspend (ConnectedDevice) -> Unit): DeviceDiscoveryResult =
        withContext(Dispatchers.IO) {
            cancelled.set(false)
            val subnets = subnetProvider.subnets()
            val hasOversizedSubnet = subnets.any { it.exceedsHostLimit() }
            val endpoints = subnets
                .flatMap { subnet -> subnet.hostAddresses().map { DeviceEndpoint(it) } }
                .distinct()
            if (endpoints.isEmpty()) {
                return@withContext DeviceDiscoveryResult(
                    scannedCount = 0,
                    confirmedCount = 0,
                    reasonCode = if (hasOversizedSubnet) "discovery_scope_too_large" else "discovery_scope_unavailable",
                )
            }
            if (endpoints.size > Ipv4Subnet.MAX_TOTAL_HOSTS) {
                return@withContext DeviceDiscoveryResult(
                    scannedCount = endpoints.size,
                    confirmedCount = 0,
                    reasonCode = "discovery_scope_too_large",
                )
            }

            val confirmedIds = ConcurrentHashMap.newKeySet<String>()
            var timedOut = false
            try {
                withTimeout(overallTimeoutMillis) {
                    coroutineScope {
                        val semaphore = Semaphore(maxParallel)
                        endpoints.forEach { endpoint ->
                            launch {
                                if (cancelled.get()) return@launch
                                semaphore.withPermit {
                                    if (cancelled.get()) return@withPermit
                                    val result = withTimeoutOrNull(probeTimeoutMillis) {
                                        transport.connect(endpoint)
                                    }
                                    if (
                                        result is com.ninepointnine.helper.domain.device.DeviceConnectResult.Connected &&
                                        confirmedIds.add(result.device.identity.stableId)
                                    ) {
                                        onDevice(result.device)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                timedOut = true
            } catch (cancelledException: CancellationException) {
                throw cancelledException
            }
            val confirmedCount = confirmedIds.size
            DeviceDiscoveryResult(
                scannedCount = endpoints.size,
                confirmedCount = confirmedCount,
                reasonCode = when {
                    cancelled.get() -> "discovery_cancelled"
                    confirmedCount > 0 -> null
                    timedOut -> "discovery_timeout"
                    else -> "no_adb_devices_found"
                },
            )
        }

    override fun cancel() {
        cancelled.set(true)
    }

    private companion object {
        const val DEFAULT_MAX_PARALLEL = 32
        const val DEFAULT_PROBE_TIMEOUT_MILLIS = 700L
        const val DEFAULT_OVERALL_TIMEOUT_MILLIS = 8_000L
    }
}
