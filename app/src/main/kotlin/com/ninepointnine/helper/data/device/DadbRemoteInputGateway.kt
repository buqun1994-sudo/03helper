package com.ninepointnine.helper.data.device

import com.ninepointnine.helper.domain.artifact.KnownApplicationPackages
import com.ninepointnine.helper.domain.device.RemoteInputGateway
import com.ninepointnine.helper.domain.device.RemoteInputSubscription
import com.ninepointnine.helper.domain.device.VehicleInputRequest
import com.ninepointnine.helper.domain.device.VehicleInputReceipt
import com.ninepointnine.helper.domain.device.VehicleInputState
import dadb.Dadb
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Fixed input adapter. User text enters a bounded JSON data stream, never shell argv. */
internal class DadbRemoteInputGateway(
    private val adb: Dadb,
    private val leaseClosed: AtomicBoolean,
    private val ioMutex: Mutex,
) : RemoteInputGateway {
    @Volatile private var capability: Capability? = null
    @Volatile private var nativeImeSupported: Boolean? = null

    override suspend fun subscribe(): RemoteInputSubscription = withContext(Dispatchers.IO) {
        capability = null
        val ready = ioMutex.withLock {
            checkOpen()
            PACKAGES.firstNotNullOfOrNull { packageName ->
                runCatching {
                    val authority = "$packageName.remoteinput"
                    val frame = call(authority, "get_capability")
                    require(RemoteInputFrames.string(frame, "type") == "capability")
                    require(RemoteInputFrames.string(frame, "requestTransport") == "socket")
                    require(RemoteInputFrames.string(frame, "restoreProtocol") == "exclusive")
                    val ime = RemoteInputFrames.string(frame, "ime")
                    val socket = RemoteInputFrames.string(frame, "socket")
                    require(ime == "$packageName/com.ninepointnine.desktop.input.RemoteInputMethodService")
                    require(socket == "$packageName.remoteinput")
                    Capability(authority, ime, socket)
                }.getOrNull()
            } ?: throw IOException("remote_input_desktop_unavailable")
        }
        capability = ready
        val transport = adb as? RetainedAdbTransport ?: throw IOException("remote_input_transport_unsupported")
        val stream = transport.openInputSubscription("localabstract:${ready.socket}")
        try {
            stream.sink.writeUtf8("{\"v\":1,\"op\":\"subscribe\"}\n").flush()
            val initial = RemoteInputFrames.state(RemoteInputFrames.read(stream.source))
            stream.markSynchronized()
            Subscription(stream, initial) { capability = null }
        } catch (failure: Exception) {
            capability = null
            stream.close()
            throw failure
        }
    }

    override suspend fun send(request: VehicleInputRequest): VehicleInputReceipt = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            checkOpen()
            require(RemoteInputFrames.requestIdPattern.matches(request.id))
            require(request.epoch > 0 && request.text.isNotEmpty())
            require(request.text.toByteArray(Charsets.UTF_8).size <= RemoteInputFrames.MAX_TEXT_BYTES)
            val ready = capability ?: return@withLock VehicleInputReceipt(request.id, false, true, "remote_input_not_ready")
            val originalResponse = adb.shell(
                RemoteInputShellPreflight.command(probeNative = nativeImeSupported == null),
            )
            val original = originalResponse.output.lineSequence().map(String::trim).filter(IME_PATTERN::matches).singleOrNull()
            if (originalResponse.exitCode != 0 || original == null || original == ready.ime) {
                return@withLock VehicleInputReceipt(request.id, false, true, "remote_input_original_ime_unavailable")
            }
            val nativeImeCommand = nativeImeSupported ?: originalResponse.output.lineSequence()
                .any { it.trim() == "03INPUT_NATIVE_IME=1" }
                .also { nativeImeSupported = it }
            val payload = buildJsonObject {
                put("v", 1); put("op", "begin"); put("id", request.id); put("epoch", request.epoch); put("text", request.text)
            }.toString()
            val accepted = begin(ready, payload)
            if (RemoteInputFrames.string(accepted, "type") != "accepted" ||
                RemoteInputFrames.string(accepted, "id") != request.id) throw IOException("remote_input_protocol_invalid")
            if (!RemoteInputFrames.boolean(accepted, "accepted")) {
                return@withLock VehicleInputReceipt(request.id, false, true, mapReason(RemoteInputFrames.string(accepted, "reason")))
            }
            var verifiedTerminal = false
            try {
                val response = adb.shell(RemoteInputShellTransaction.build(ready.authority, ready.ime, original, request.id, nativeImeCommand))
                val parsed = RemoteInputReceiptParser.parse(response.output.lineSequence().toList(), request.id)
                verifiedTerminal = parsed.verifiedTerminal
                VehicleInputReceipt(
                    request.id,
                    parsed.committed,
                    parsed.restored,
                    mapReason(parsed.reason),
                )
            } catch (_: IOException) {
                // A watchdog already runs on the vehicle. Never replay after losing a receipt.
                VehicleInputReceipt(request.id, null, false, "remote_input_result_unknown")
            } finally {
                if (!verifiedTerminal) {
                    runCatching { call(ready.authority, "cancel", encode(buildJsonObject { put("v", 1); put("id", request.id) })) }
                }
            }
        }
    }

    private fun mapReason(reason: String): String? = when (reason) {
        "committed" -> null
        "keyboard_not_open" -> "remote_input_keyboard_closed"
        "busy" -> "remote_input_busy"
        "empty_text" -> "remote_input_empty"
        "text_too_large" -> "remote_input_text_too_long"
        "unknown_request" -> "remote_input_unknown_result"
        "remote_input_result_unknown" -> "remote_input_result_unknown"
        "target_changed", "focus_changed", "focus_unverified", "stale_epoch", "target_unknown", "invalid_editor" -> "remote_input_target_changed"
        "restore_in_progress" -> "remote_input_busy"
        "restore_claimed" -> "remote_input_send_failed"
        "unavailable", "accessibility_lost", "owner_checkpoint_unavailable" -> "remote_input_unavailable"
        else -> "remote_input_send_failed"
    }

    private fun begin(ready: Capability, payload: String): JsonObject {
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) throw IOException("remote_input_request_too_large")
        val transport = adb as? RetainedAdbTransport ?: throw IOException("remote_input_transport_unsupported")
        return transport.openInputRequest("localabstract:${ready.socket}").use { stream ->
            stream.sink.writeUtf8(payload).writeUtf8("\n").flush()
            RemoteInputFrames.read(stream.source)
        }
    }

    private fun checkOpen() { if (leaseClosed.get()) throw IOException("adb_connection_closed") }
    private fun call(authority: String, method: String, arg: String? = null): JsonObject {
        val command = "content call --uri content://$authority --method $method" +
            (arg?.let { " --arg ${RemoteInputShellTransaction.quote(it)}" } ?: "")
        val response = adb.shell("/system/bin/timeout 4 $command")
        if (response.exitCode != 0) throw IOException("remote_input_bridge_failed")
        return RemoteInputFrames.decodeProvider(response.output)
    }
    private fun encode(frame: JsonObject): String = Base64.getEncoder().encodeToString(frame.toString().toByteArray(Charsets.UTF_8))

    private data class Capability(val authority: String, val ime: String, val socket: String)
    private class Subscription(
        private val stream: RemoteInputEventStream,
        private var initial: VehicleInputState?,
        private val invalidateCapability: () -> Unit,
    ) : RemoteInputSubscription {
        override suspend fun next(): VehicleInputState = withContext(Dispatchers.IO) {
            try {
                initial?.also { initial = null } ?: RemoteInputFrames.state(RemoteInputFrames.read(stream.source))
            } catch (failure: Exception) {
                invalidateCapability()
                throw failure
            }
        }
        override fun close() { runCatching { stream.close() } }
    }
    companion object {
        const val MAX_REQUEST_BYTES = 262_144
        private val IME_PATTERN = Regex("[A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+")
        private val PACKAGES = listOf(KnownApplicationPackages.CURRENT_DESKTOP_TEST_PACKAGE_NAME, KnownApplicationPackages.CURRENT_DESKTOP_PACKAGE_NAME)
    }
}

/** Single vehicle-side operation with EXIT recovery and a detached, bounded backup. */
internal object RemoteInputShellTransaction {
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    fun build(authority: String, remote: String, original: String, id: String, nativeImeCommand: Boolean = false): String {
        require(Regex("[A-Za-z][A-Za-z0-9_.]*\\.remoteinput").matches(authority))
        require(RemoteInputFrames.requestIdPattern.matches(id))
        require(remote.substringBefore('/') + ".remoteinput" == authority)
        require(Regex("[A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+").matches(original))
        val imeCommand = if (nativeImeCommand) "cmd input_method ime" else "ime"
        val restoration = "current=\$(/system/bin/timeout 3 settings get secure default_input_method); " +
            "if [ \"\$current\" = ${quote(remote)} ]; then /system/bin/timeout 3 $imeCommand set ${quote(original)} >/dev/null 2>&1; fi"
        val claimArg = Base64.getEncoder().encodeToString("{\"v\":1,\"id\":\"$id\"}".toByteArray(Charsets.UTF_8))
        val claim = "content call --uri content://$authority --method claim_restore --arg ${quote(claimArg)}"
        val release = "content call --uri content://$authority --method release_restore --arg ${quote(claimArg)}"
        val guardedRestore = "/system/bin/timeout 9 sh -c ${quote(restoration)} & restore_worker=\$!; " +
            "while kill -0 \"\$restore_worker\" 2>/dev/null; do wait \"\$restore_worker\" || true; done; " +
            "/system/bin/timeout 4 $release >/dev/null 2>&1"
        val guard = "sleep 15; trap ':' HUP INT TERM; claim=\$(/system/bin/timeout 4 $claim); " +
            "case \"\$claim\" in *restoreAllowed=true*) $guardedRestore ;; esac"
        val commit = "if $imeCommand enable ${quote(remote)} >/dev/null 2>&1 && $imeCommand set ${quote(remote)} >/dev/null 2>&1; then " +
            "content read --uri content://$authority/results/$id; fi"
        return """
            original=${quote(original)}
            remote=${quote(remote)}
            restore() {
                $restoration
                current=${'$'}(/system/bin/timeout 3 settings get secure default_input_method)
                if [ "${'$'}current" = "${'$'}original" ]; then restore_ok=1; else restore_ok=0; fi
                printf '\n03INPUT_RESTORED=%s\n' "${'$'}restore_ok"
            }
            trap 'restore' EXIT HUP INT TERM
            nohup sh -c ${quote(guard)} </dev/null >/dev/null 2>&1 &
            guard=${'$'}!
            if ! kill -0 "${'$'}guard" 2>/dev/null; then exit 1; fi
            /system/bin/timeout 12 sh -c ${quote(commit)}
            restore
            if [ "${'$'}restore_ok" = 1 ]; then kill "${'$'}guard" 2>/dev/null || true; fi
            trap - EXIT HUP INT TERM
        """.trimIndent()
    }
}
