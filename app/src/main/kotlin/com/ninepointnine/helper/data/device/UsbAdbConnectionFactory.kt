package com.ninepointnine.helper.data.device

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.ninepointnine.helper.data.artifact.ApkMetadataReader
import com.ninepointnine.helper.domain.device.DeviceConnectionAttempt
import com.ninepointnine.helper.domain.device.DeviceConnectionFactory
import com.ninepointnine.helper.domain.device.DeviceEndpoint
import com.ninepointnine.helper.domain.device.DeviceTransportKind
import dadb.Dadb
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Opens one authorized USB ADB interface and reuses the existing Dadb lease owner. */
class UsbAdbDeviceConnectionFactory(
    private val registry: UsbAdbDeviceRegistry,
    private val tcpLeaseFactory: DadbDeviceConnectionFactory,
) : DeviceConnectionFactory {
    override suspend fun open(endpoint: DeviceEndpoint): DeviceConnectionAttempt = withContext(Dispatchers.IO) {
        if (endpoint.transport != DeviceTransportKind.USB) {
            return@withContext DeviceConnectionAttempt.Failed(
                "usb_factory_received_non_usb_endpoint",
                retryable = false,
            )
        }
        val device = registry.refresh().firstOrNull { it.deviceName == endpoint.host }
            ?: registry.resolve(endpoint.host)
            ?: return@withContext DeviceConnectionAttempt.Failed("usb_device_missing", retryable = true)
        if (!registry.hasPermission(device)) {
            return@withContext DeviceConnectionAttempt.Failed("usb_permission_required", retryable = true)
        }
        var adb: Dadb? = null
        try {
            adb = UsbAdbProtocolTransport.open(registry.usbManager, device)
            val attempt = tcpLeaseFactory.openDadb(endpoint, adb, allowWirelessAdb = true)
            if (attempt is DeviceConnectionAttempt.Connected) adb = null
            attempt
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: UsbAdbTransportException) {
            DeviceConnectionAttempt.Failed(exception.reasonCode, exception.retryable)
        } catch (_: IOException) {
            DeviceConnectionAttempt.Failed("usb_adb_connect_failed", retryable = true)
        } catch (_: Exception) {
            DeviceConnectionAttempt.Failed("usb_adb_identity_read_failed", retryable = true)
        } finally {
            adb?.close()
        }
    }
}

/** Selects the already-confirmed LAN or USB factory without leaking transport details upward. */
class PreferredAdbConnectionFactory(
    private val tcpFactory: DeviceConnectionFactory,
    private val usbFactory: DeviceConnectionFactory,
) : DeviceConnectionFactory {
    override suspend fun open(endpoint: DeviceEndpoint): DeviceConnectionAttempt = when (endpoint.transport) {
        DeviceTransportKind.TCP -> tcpFactory.open(endpoint)
        DeviceTransportKind.USB -> usbFactory.open(endpoint)
    }
}

/** Discovery uses the same connection protocol but always closes the returned lease. */
class UsbAdbDeviceTransport(
    private val connectionFactory: DeviceConnectionFactory,
) : com.ninepointnine.helper.domain.device.DeviceTransport {
    override suspend fun connect(endpoint: DeviceEndpoint) = when (val attempt = connectionFactory.open(endpoint)) {
        is DeviceConnectionAttempt.Connected -> attempt.connection.use {
            com.ninepointnine.helper.domain.device.DeviceConnectResult.Connected(it.device)
        }

        is DeviceConnectionAttempt.Failed -> com.ninepointnine.helper.domain.device.DeviceConnectResult.Failed(
            reasonCode = attempt.reasonCode,
            retryable = attempt.retryable,
        )
    }
}

/** Thin ADB-over-USB transport. Dadb continues to own all shell, sync and stream semantics. */
internal object UsbAdbProtocolTransport {
    private const val ADB_INTERFACE_CLASS = 0xff
    private const val ADB_INTERFACE_SUBCLASS = 0x42
    private const val ADB_INTERFACE_PROTOCOL = 0x01
    private const val USB_TRANSFER_TIMEOUT_MILLIS = 5_000
    private const val USB_BUFFER_BYTES = 16 * 1024

    fun hasAdbInterface(device: UsbDevice): Boolean = findInterface(device) != null

    fun open(manager: android.hardware.usb.UsbManager, device: UsbDevice): Dadb {
        val selection = findInterface(device)
            ?: throw UsbAdbTransportException("usb_adb_interface_unavailable", retryable = false)
        val connection = manager.openDevice(device)
            ?: throw UsbAdbTransportException("usb_device_open_failed", retryable = true)
        if (!connection.claimInterface(selection.usbInterface, true)) {
            connection.close()
            throw UsbAdbTransportException("usb_adb_interface_claim_failed", retryable = true)
        }
        val proxy = UsbAdbProxy(
            connection = connection,
            usbInterface = selection.usbInterface,
            inEndpoint = selection.inEndpoint,
            outEndpoint = selection.outEndpoint,
        )
        return try {
            proxy.start()
            val dadb = Dadb.create(
                InetAddress.getLoopbackAddress().hostAddress ?: "127.0.0.1",
                proxy.port,
                null,
                connectTimeout = 2_000,
                socketTimeout = USB_TRANSFER_TIMEOUT_MILLIS,
            )
            UsbProxyDadb(dadb, proxy)
        } catch (exception: Exception) {
            proxy.close()
            if (exception is UsbAdbTransportException) throw exception
            throw UsbAdbTransportException("usb_adb_proxy_failed", retryable = true, cause = exception)
        }
    }

    private fun findInterface(device: UsbDevice): UsbAdbEndpointSelection? {
        for (interfaceIndex in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(interfaceIndex)
            if (
                usbInterface.interfaceClass != ADB_INTERFACE_CLASS ||
                usbInterface.interfaceSubclass != ADB_INTERFACE_SUBCLASS ||
                usbInterface.interfaceProtocol != ADB_INTERFACE_PROTOCOL
            ) continue
            val inEndpoint = (0 until usbInterface.endpointCount)
                .map(usbInterface::getEndpoint)
                .firstOrNull { endpoint ->
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_IN
                }
            val outEndpoint = (0 until usbInterface.endpointCount)
                .map(usbInterface::getEndpoint)
                .firstOrNull { endpoint ->
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_OUT
                }
            if (inEndpoint != null && outEndpoint != null) {
                return UsbAdbEndpointSelection(usbInterface, inEndpoint, outEndpoint)
            }
        }
        return null
    }
}

private data class UsbAdbEndpointSelection(
    val usbInterface: UsbInterface,
    val inEndpoint: UsbEndpoint,
    val outEndpoint: UsbEndpoint,
)

private class UsbAdbProxy(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
) : Closeable {
    private companion object {
        const val USB_TRANSFER_TIMEOUT_MILLIS = 5_000
        const val USB_BUFFER_BYTES = 16 * 1024
    }

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val executor: ExecutorService = Executors.newFixedThreadPool(3)
    private val closed = AtomicBoolean(false)

    val port: Int
        get() = server.localPort

    @Volatile
    private var client: Socket? = null

    fun start() {
        executor.execute {
            try {
                val accepted = server.accept()
                if (closed.get()) {
                    accepted.close()
                    return@execute
                }
                client = accepted
                executor.execute { pumpSocketToUsb(accepted) }
                executor.execute { pumpUsbToSocket(accepted) }
            } catch (_: IOException) {
                if (!closed.get()) close()
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { client?.close() }
            runCatching { server.close() }
            executor.shutdownNow()
            runCatching { connection.releaseInterface(usbInterface) }
            connection.close()
        }
    }

    private fun pumpSocketToUsb(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val bytes = ByteArray(USB_BUFFER_BYTES)
            while (!closed.get()) {
                val count = input.read(bytes)
                if (count < 0) break
                var offset = 0
                while (offset < count && !closed.get()) {
                    val written = connection.bulkTransfer(
                        outEndpoint,
                        bytes,
                        offset,
                        count - offset,
                        USB_TRANSFER_TIMEOUT_MILLIS,
                    )
                    if (written < 0) throw IOException("usb_adb_write_failed")
                    if (written == 0) continue
                    offset += written
                }
            }
        } catch (_: IOException) {
            if (!closed.get()) close()
        }
    }

    private fun pumpUsbToSocket(socket: Socket) {
        try {
            val output = socket.getOutputStream()
            val bytes = ByteArray(USB_BUFFER_BYTES)
            while (!closed.get()) {
                val count = connection.bulkTransfer(
                    inEndpoint,
                    bytes,
                    0,
                    bytes.size,
                    USB_TRANSFER_TIMEOUT_MILLIS,
                )
                if (count < 0) throw IOException("usb_adb_read_failed")
                if (count == 0) continue
                output.write(bytes, 0, count)
                output.flush()
            }
        } catch (_: IOException) {
            if (!closed.get()) close()
        }
    }
}

private class UsbProxyDadb(
    private val delegate: Dadb,
    private val proxy: UsbAdbProxy,
) : Dadb {
    override fun open(destination: String) = delegate.open(destination)

    override fun supportsFeature(feature: String): Boolean = delegate.supportsFeature(feature)

    override fun close() {
        runCatching { delegate.close() }
        proxy.close()
    }
}

internal class UsbAdbTransportException(
    val reasonCode: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : IOException(reasonCode, cause)
