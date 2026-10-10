package com.ninepointnine.helper.data.device

/** Fixed shell preflight; native IME matching is an exact line comparison. */
internal object RemoteInputShellPreflight {
    fun command(probeNative: Boolean = true): String = buildString {
        appendLine("test -x /system/bin/timeout || exit 1")
        appendLine("test -x /system/bin/nohup || exit 1")
        appendLine("original=${'$'}(/system/bin/timeout 3 settings get secure default_input_method) || exit 1")
        appendLine("printf '%s\\n' \"${'$'}original\"")
        if (probeNative) {
            appendLine("nativeOutput=${'$'}(/system/bin/timeout 3 cmd input_method ime list -s 2>/dev/null)")
            appendLine("nativeStatus=${'$'}?")
            appendLine("native=0")
            appendLine("if [ \"${'$'}nativeStatus\" -eq 0 ]; then")
            appendLine("    while IFS= read -r line; do")
            appendLine("        if [ \"${'$'}line\" = \"${'$'}original\" ]; then native=1; break; fi")
            appendLine("    done <<EOF")
            appendLine("${'$'}nativeOutput")
            appendLine("EOF")
            appendLine("fi")
            appendLine("printf '\\n03INPUT_NATIVE_IME=%s\\n' \"${'$'}native\"")
        }
    }
}
