package com.ninepointnine.helper.data.device

/**
 * Pure validation of the fixed transaction's mixed stdout.
 * The restore marker is intentionally parsed independently from the JSON receipt:
 * a bad receipt must not erase evidence that the original IME was restored.
 */
internal data class ParsedRemoteInputReceipt(
    val committed: Boolean?,
    val restored: Boolean,
    val reason: String,
    /** Only a valid terminal receipt with exactly one valid restore marker may skip cancel. */
    val verifiedTerminal: Boolean,
)

internal object RemoteInputReceiptParser {
    private val markerPattern = Regex("03INPUT_RESTORED=[01]")
    private val terminalReasons = setOf(
        "committed",
        "commit_failed",
        "target_changed",
        "focus_changed",
        "invalid_editor",
        "text_unavailable",
        "unavailable",
        "accessibility_lost",
        "ime_disconnected",
        "deadline",
        "deadline_unavailable",
        "restore_in_progress",
        "restore_claimed",
        "focus_unverified",
        "unknown_request",
    )

    fun parse(lines: List<String>, requestId: String): ParsedRemoteInputReceipt {
        val normalized = lines.map(String::trim)
        val markerLines = normalized.filter { it.startsWith("03INPUT_RESTORED=") }
        val markerValid = markerLines.size == 1 && markerPattern.matches(markerLines.single())
        val restored = markerValid && markerLines.single() == "03INPUT_RESTORED=1"

        // A transaction emits exactly one JSON result line. More or fewer lines are
        // treated as an unknown outcome; never choose the first line and cancel.
        val resultLines = normalized.filter { it.startsWith("{") }
        if (resultLines.size != 1) {
            return ParsedRemoteInputReceipt(null, restored, "remote_input_result_unknown", false)
        }
        val frame = runCatching { RemoteInputFrames.decode(resultLines.single()) }.getOrNull()
            ?: return ParsedRemoteInputReceipt(null, restored, "remote_input_result_unknown", false)
        val valid = runCatching {
            RemoteInputFrames.string(frame, "type") == "result" &&
                RemoteInputFrames.string(frame, "id") == requestId &&
                RemoteInputFrames.string(frame, "reason") in terminalReasons &&
                semanticallyValid(frame)
        }.getOrDefault(false)
        if (!valid) {
            return ParsedRemoteInputReceipt(null, restored, "remote_input_result_unknown", false)
        }
        val reason = RemoteInputFrames.string(frame, "reason")
        val committed = if (reason == "unknown_request") null else RemoteInputFrames.boolean(frame, "committed")
        return ParsedRemoteInputReceipt(
            committed = committed,
            restored = restored,
            reason = reason,
            verifiedTerminal = markerValid && reason != "unknown_request",
        )
    }

    private fun semanticallyValid(frame: kotlinx.serialization.json.JsonObject): Boolean {
        val reason = RemoteInputFrames.string(frame, "reason")
        val committed = RemoteInputFrames.boolean(frame, "committed")
        return when (reason) {
            "committed" -> committed
            "unknown_request" -> !committed
            else -> !committed
        }
    }
}
