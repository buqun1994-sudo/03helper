package com.ninepointnine.helper.data.device

import com.ninepointnine.helper.domain.device.VehicleInputState
import java.io.IOException
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Strict framing prevents malformed/old events from enabling send. */
internal object RemoteInputFrames {
    const val VERSION = 1
    const val MAX_TEXT_BYTES = com.ninepointnine.helper.domain.input.RemoteInputSnapshot.MAX_TEXT_BYTES
    const val MAX_FRAME_BYTES = 65_536L
    val requestIdPattern = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
    private val bundleFrame = Regex("(?:^|[\\s{,])frame=([A-Za-z0-9+/=]+)")
    fun read(source: okio.BufferedSource): JsonObject {
        val newline = source.indexOf('\n'.code.toByte(), 0L, MAX_FRAME_BYTES + 1L)
        if (newline < 0) throw IOException("remote_input_frame_incomplete")
        val bytes = source.readByteArray(newline)
        source.skip(1L)
        return decode(decodeUtf8(bytes))
    }
    private fun decodeUtf8(bytes: ByteArray): String = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }.getOrElse { throw IOException("remote_input_protocol_invalid", it) }
    fun decodeProvider(output: String): JsonObject {
        val frames = bundleFrame.findAll(output).toList()
        if (frames.size != 1 || frames.single().groupValues[1].length > 90_000) throw IOException("remote_input_protocol_invalid")
        val bytes = runCatching { Base64.getDecoder().decode(frames.single().groupValues[1]) }
            .getOrElse { throw IOException("remote_input_protocol_invalid", it) }
        return decode(decodeUtf8(bytes))
    }
    fun decode(line: String): JsonObject {
        if (line.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES) throw IOException("remote_input_frame_too_large")
        val frame = runCatching { Json.parseToJsonElement(line).jsonObject }
            .getOrElse { throw IOException("remote_input_protocol_invalid", it) }
        if (long(frame, "v") != VERSION.toLong()) throw IOException("remote_input_version_unsupported")
        return frame
    }
    fun state(frame: JsonObject): VehicleInputState {
        if (string(frame, "type") != "state") throw IOException("remote_input_protocol_invalid")
        val keyboard = string(frame, "keyboard")
        if (keyboard !in setOf("open", "closed", "unknown")) throw IOException("remote_input_protocol_invalid")
        val epoch = long(frame, "epoch")
        val sequence = long(frame, "sequence")
        val target = string(frame, "targetPackage")
        val windowLong = long(frame, "targetWindow")
        if (windowLong !in -1L..Int.MAX_VALUE.toLong()) throw IOException("remote_input_protocol_invalid")
        val window = windowLong.toInt()
        if (epoch < 0 || sequence < 0 || target.length > 256) throw IOException("remote_input_protocol_invalid")
        val available = boolean(frame, "available")
        // Capability availability does not imply editor readiness. The canonical
        // unknown target is a valid observation; the domain owns send readiness.
        val unknownTarget = target.isEmpty() && window == -1
        if (available && keyboard == "open" && (epoch <= 0 || !unknownTarget && (target.isEmpty() || window < 0))) {
            throw IOException("remote_input_target_invalid")
        }
        return VehicleInputState(available, keyboard, epoch, target, window, sequence, boolean(frame, "sending"))
    }
    fun string(frame: JsonObject, key: String): String = primitive(frame, key).let {
        if (it.isString) it.contentOrNull ?: throw IOException("remote_input_protocol_invalid")
        else throw IOException("remote_input_protocol_invalid")
    }
    fun boolean(frame: JsonObject, key: String): Boolean = primitive(frame, key).let {
        if (!it.isString) it.booleanOrNull ?: throw IOException("remote_input_protocol_invalid")
        else throw IOException("remote_input_protocol_invalid")
    }
    fun long(frame: JsonObject, key: String): Long = primitive(frame, key).let {
        if (!it.isString) it.longOrNull ?: throw IOException("remote_input_protocol_invalid")
        else throw IOException("remote_input_protocol_invalid")
    }
    private fun primitive(frame: JsonObject, key: String): kotlinx.serialization.json.JsonPrimitive =
        frame[key] as? kotlinx.serialization.json.JsonPrimitive ?: throw IOException("remote_input_protocol_invalid")
}
