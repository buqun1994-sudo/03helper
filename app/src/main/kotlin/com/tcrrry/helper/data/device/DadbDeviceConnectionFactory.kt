package com.tcrrry.helper.data.device

import dadb.Dadb
import com.tcrrry.helper.domain.device.ConnectedDevice
import com.tcrrry.helper.domain.device.DeviceCapability
import com.tcrrry.helper.domain.device.DeviceConnectionAttempt
import com.tcrrry.helper.domain.device.DeviceConnectionCheck
import com.tcrrry.helper.domain.device.DeviceConnectionFactory
import com.tcrrry.helper.domain.device.DeviceConnectionLease
import com.tcrrry.helper.domain.device.DeviceEndpoint
import com.tcrrry.helper.domain.device.DeviceIdentity
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Opens and owns one real TCP ADB transport after the user selects a device. */
class DadbDeviceConnectionFactory(
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
) : DeviceConnectionFactory {
    override suspend fun open(endpoint: DeviceEndpoint): DeviceConnectionAttempt = withContext(Dispatchers.IO) {
        var adb: Dadb? = null
        try {
            adb = Dadb.create(
                endpoint.host,
                endpoint.port,
                null,
                connectTimeoutMillis,
                readTimeoutMillis,
            )
            when (val identity = readIdentity(adb)) {
                is IdentityReadResult.Success -> {
                    val lease = DadbDeviceConnection(
                        adb = adb,
                        device = ConnectedDevice(
                            endpoint = endpoint,
                            identity = identity.value,
                            capabilities = setOf(
                                DeviceCapability.ADB_TCP,
                                DeviceCapability.IDENTITY_READ,
                            ),
                        ),
                    )
                    adb = null
                    DeviceConnectionAttempt.Connected(lease)
                }

                is IdentityReadResult.Failure -> DeviceConnectionAttempt.Failed(
                    reasonCode = identity.reasonCode,
                    retryable = identity.retryable,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            DeviceConnectionAttempt.Failed("adb_connect_failed")
        } catch (_: Exception) {
            DeviceConnectionAttempt.Failed("adb_identity_read_failed")
        } finally {
            adb?.close()
        }
    }

    private class DadbDeviceConnection(
        private val adb: Dadb,
        override val device: ConnectedDevice,
    ) : DeviceConnectionLease {
        private val closed = AtomicBoolean(false)
        private val ioMutex = Mutex()

        override suspend fun check(): DeviceConnectionCheck = withContext(Dispatchers.IO) {
            if (closed.get()) return@withContext DeviceConnectionCheck(false, "adb_connection_closed")
            ioMutex.withLock {
                if (closed.get()) return@withLock DeviceConnectionCheck(false, "adb_connection_closed")
                try {
                    when (val identity = readIdentity(adb)) {
                        is IdentityReadResult.Success -> when {
                            identity.value.stableId != device.identity.stableId ->
                                DeviceConnectionCheck(false, "adb_identity_changed")

                            else -> DeviceConnectionCheck(true)
                        }

                        is IdentityReadResult.Failure -> DeviceConnectionCheck(false, identity.reasonCode)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    DeviceConnectionCheck(false, "adb_connection_lost")
                } catch (_: Exception) {
                    DeviceConnectionCheck(false, "adb_connection_check_failed")
                }
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                runCatching { adb.close() }
            }
        }
    }

    private sealed interface IdentityReadResult {
        data class Success(val value: DeviceIdentity) : IdentityReadResult

        data class Failure(
            val reasonCode: String,
            val retryable: Boolean,
        ) : IdentityReadResult
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 350
        const val DEFAULT_READ_TIMEOUT_MILLIS = 900
        const val MIN_ANDROID_SDK = 1
        const val MAX_ANDROID_SDK = 100
        const val PROPERTY_SERIAL = "ro.serialno"
        const val PROPERTY_MODEL = "ro.product.model"
        const val PROPERTY_SDK = "ro.build.version.sdk"

        fun readIdentity(adb: Dadb): IdentityReadResult {
            val serial = readProperty(adb, PROPERTY_SERIAL)
                ?: return IdentityReadResult.Failure("adb_identity_serial_missing", retryable = false)
            val model = readProperty(adb, PROPERTY_MODEL)
                ?: return IdentityReadResult.Failure("adb_identity_model_missing", retryable = false)
            val sdk = readProperty(adb, PROPERTY_SDK)?.toIntOrNull()
                ?: return IdentityReadResult.Failure("adb_identity_sdk_invalid", retryable = false)
            if (
                sdk !in MIN_ANDROID_SDK..MAX_ANDROID_SDK ||
                !isSafeSerial(serial) ||
                !isSafeModel(model)
            ) {
                return IdentityReadResult.Failure("adb_identity_invalid", retryable = false)
            }
            return IdentityReadResult.Success(
                DeviceIdentity(
                    stableId = "adb:${serial.trim()}",
                    model = model.trim(),
                    androidSdk = sdk,
                ),
            )
        }

        fun readProperty(adb: Dadb, property: String): String? {
            val response = adb.shell("getprop $property")
            if (response.exitCode != 0 || response.errorOutput.isNotBlank()) return null
            return response.output.trim().takeIf { it.isNotBlank() }
        }

        fun isSafeSerial(value: String): Boolean = value.trim().let { serial ->
            serial.length in 1..128 && !serial.equals("unknown", ignoreCase = true) && serial.all { character ->
                character.isLetterOrDigit() || character in setOf('-', '_', '.', ':')
            }
        }

        fun isSafeModel(value: String): Boolean = value.trim().let { model ->
            model.length in 1..128 && model.none(Char::isISOControl)
        }
    }
}
