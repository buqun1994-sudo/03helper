package com.ninepointnine.helper.data.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ninepointnine.helper.domain.device.ConnectedDevice
import com.ninepointnine.helper.domain.device.DeviceConnectResult
import com.ninepointnine.helper.domain.device.DeviceDiscovery
import com.ninepointnine.helper.domain.device.DeviceDiscoveryResult
import com.ninepointnine.helper.domain.device.DeviceEndpoint
import com.ninepointnine.helper.domain.device.DeviceTransport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Owns the one Android system USB permission dialog used by the ADB adapter. */
class UsbAdbPermissionBroker(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val usbManager = checkNotNull(
        applicationContext.getSystemService(Context.USB_SERVICE) as? UsbManager,
    ) { "usb_manager_unavailable" }
    private val pending = ConcurrentHashMap<String, kotlinx.coroutines.CancellableContinuation<Boolean>>()
    private val action = "${applicationContext.packageName}.USB_ADB_PERMISSION"
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != action) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val deviceName = device?.deviceName ?: return
            pending.remove(deviceName)?.let { continuation ->
                runCatching {
                    continuation.resume(
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false),
                    )
                }
            }
        }
    }
    private val closed = AtomicBoolean(false)

    init {
        ContextCompat.registerReceiver(
            applicationContext,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (closed.get() || !usbManager.deviceList.containsKey(device.deviceName)) return false
        if (usbManager.hasPermission(device)) return true
        return suspendCancellableCoroutine { continuation ->
            val previous = pending.putIfAbsent(device.deviceName, continuation)
            if (previous != null) {
                runCatching { continuation.resume(false) }
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                pending.remove(device.deviceName, continuation)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val permissionIntent = PendingIntent.getBroadcast(
                applicationContext,
                device.deviceName.hashCode(),
                Intent(action).setPackage(applicationContext.packageName),
                flags,
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { applicationContext.unregisterReceiver(receiver) }
        pending.values.toList().forEach { continuation ->
            runCatching { continuation.resume(false) }
        }
        pending.clear()
    }
}

/** Keeps the current USB device handles out of domain endpoints and UI state. */
class UsbAdbDeviceRegistry(internal val usbManager: UsbManager) {
    private val devices = ConcurrentHashMap<String, UsbDevice>()

    fun refresh(): List<UsbDevice> {
        val current = usbManager.deviceList.values
            .filter(UsbAdbProtocolTransport::hasAdbInterface)
            .sortedBy(UsbDevice::getDeviceName)
        devices.clear()
        current.forEach { devices[it.deviceName] = it }
        return current
    }

    fun resolve(deviceName: String): UsbDevice? = devices[deviceName]

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)
}

/** USB fallback discovery. It runs only after the bounded LAN pass has no result. */
class UsbAdbDeviceDiscovery(
    private val registry: UsbAdbDeviceRegistry,
    private val permissionBroker: UsbAdbPermissionBroker,
    private val transport: DeviceTransport,
    private val probeTimeoutMillis: Long = DEFAULT_PROBE_TIMEOUT_MILLIS,
) : DeviceDiscovery {
    private val cancelled = AtomicBoolean(false)

    init {
        require(probeTimeoutMillis in 500L..10_000L) { "usb_discovery_timeout_invalid" }
    }

    override suspend fun discover(onDevice: suspend (ConnectedDevice) -> Unit): DeviceDiscoveryResult =
        withContext(Dispatchers.IO) {
            cancelled.set(false)
            val devices = registry.refresh()
            if (devices.isEmpty()) {
                return@withContext DeviceDiscoveryResult(0, 0, "no_usb_adb_devices_found")
            }
            var confirmedCount = 0
            var permissionDenied = false
            var permissionTimedOut = false
            devices.forEach { usbDevice ->
                if (cancelled.get()) return@forEach
                val permission = withTimeoutOrNull(probeTimeoutMillis) {
                    permissionBroker.requestPermission(usbDevice)
                }
                if (permission == null) {
                    permissionTimedOut = true
                    return@forEach
                }
                if (!permission) {
                    permissionDenied = true
                    return@forEach
                }
                val endpoint = DeviceEndpoint.usb(usbDevice.deviceName)
                val result = withTimeoutOrNull(probeTimeoutMillis) {
                    transport.connect(endpoint)
                }
                if (result is DeviceConnectResult.Connected) {
                    confirmedCount += 1
                    onDevice(result.device)
                }
            }
            DeviceDiscoveryResult(
                scannedCount = devices.size,
                confirmedCount = confirmedCount,
                reasonCode = when {
                    cancelled.get() -> "usb_discovery_cancelled"
                    confirmedCount > 0 -> null
                    permissionTimedOut -> "usb_permission_timeout"
                    permissionDenied -> "usb_permission_denied"
                    else -> "usb_adb_identity_unavailable"
                },
            )
        }

    override fun cancel() {
        cancelled.set(true)
    }

    private companion object {
        const val DEFAULT_PROBE_TIMEOUT_MILLIS = 5_000L
    }
}
