package com.ninepointnine.helper.application.input

import android.content.Context
import com.ninepointnine.helper.domain.device.ConnectedDevice
import com.ninepointnine.helper.domain.device.DeviceCapability
import com.ninepointnine.helper.domain.device.DeviceEndpoint
import com.ninepointnine.helper.domain.device.DeviceIdentity
import com.ninepointnine.helper.domain.device.DeviceTransportKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Private recovery checkpoint, never evidence that an endpoint is still connected. */
class OnlineSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("vehicle_online_session", Context.MODE_PRIVATE)
    fun save(device: ConnectedDevice?) {
        if (device == null || device.endpoint.transport != DeviceTransportKind.TCP) {
            preferences.edit().remove("endpoint").apply()
            return
        }
        val frame = buildJsonObject {
            put("v", 1); put("host", device.endpoint.host); put("port", device.endpoint.port)
            put("id", device.identity.stableId); put("model", device.identity.model); put("sdk", device.identity.androidSdk)
        }
        preferences.edit().putString("endpoint", frame.toString()).apply()
    }
    fun load(): ConnectedDevice? = runCatching {
        val text = preferences.getString("endpoint", null) ?: return null
        if (text.length > 1024) return null
        val frame = Json.parseToJsonElement(text).jsonObject
        require(frame["v"]?.jsonPrimitive?.intOrNull == 1)
        val id = frame.getValue("id").jsonPrimitive.content
        val model = frame.getValue("model").jsonPrimitive.content
        val sdk = frame.getValue("sdk").jsonPrimitive.intOrNull ?: return null
        require(id.startsWith("adb:") && id.length <= 256 && model.length in 1..128 && sdk in 1..100)
        ConnectedDevice(
            DeviceEndpoint(frame.getValue("host").jsonPrimitive.content, frame.getValue("port").jsonPrimitive.intOrNull ?: return null),
            DeviceIdentity(id, model, sdk), setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
        )
    }.getOrNull()
}
