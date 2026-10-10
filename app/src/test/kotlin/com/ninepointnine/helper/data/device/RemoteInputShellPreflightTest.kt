package com.ninepointnine.helper.data.device

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputShellPreflightTest {
    @Test fun preflightUsesExactReadLoopAndBoundedCommands() {
        val command = RemoteInputShellPreflight.command()
        assertTrue(command.contains("settings get secure default_input_method"))
        assertTrue(command.contains("cmd input_method ime list -s"))
        assertTrue(command.contains("nativeOutput="))
        assertTrue(command.contains("nativeStatus="))
        assertTrue(command.contains("if [ \"${'$'}nativeStatus\" -eq 0 ]"))
        assertTrue(command.contains("while IFS= read -r line"))
        assertTrue(command.contains("[ \"${'$'}line\" = \"${'$'}original\" ]"))
        assertTrue(command.contains("03INPUT_NATIVE_IME=%s"))
        assertTrue(command.contains("timeout 3"))
        assertFalse(command.contains("grep"))
        assertFalse(command.contains("case"))
        val cached = RemoteInputShellPreflight.command(probeNative = false)
        assertTrue(cached.contains("settings get secure default_input_method"))
        assertFalse(cached.contains("cmd input_method ime list -s"))
        assertFalse(cached.contains("03INPUT_NATIVE_IME"))
    }

    @Test fun nativeProbeRequiresSuccessfulExactComponentLine() {
        val original = "example.keyboard/.KeyboardService"
        for ((output, status, expected) in listOf(
            Triple("No shell command implementation.", 0, false),
            Triple("${original}Extra", 0, false),
            Triple(original, 124, false),
            Triple("other.ime/.Service\n$original\n", 0, true),
        )) {
            val script = fixture(original, 0, output, status)
            val (exit, actual) = runShell(script)
            assertEquals(0, exit)
            assertTrue(actual.lineSequence().any { it == "03INPUT_NATIVE_IME=${if (expected) 1 else 0}" })
        }
    }

    @Test fun failedOriginalReadStopsBeforeAnyNativeProbe() {
        val (exit, output) = runShell(fixture("", 124, "example.keyboard/.KeyboardService", 0))
        assertEquals(1, exit)
        assertFalse(output.contains("03INPUT_NATIVE_IME"))
    }

    @Test fun fixedTransactionHasValidPosixShellSyntax() {
        assumeTrue(File("/bin/sh").canExecute())
        for (native in listOf(false, true)) {
            val script = RemoteInputShellTransaction.build(
                "example.desktop.remoteinput",
                "example.desktop/com.ninepointnine.desktop.input.RemoteInputMethodService",
                "example.keyboard/.KeyboardService",
                "f495c2a8-7b10-4876-877c-123456789abc",
                nativeImeCommand = native,
            )
            val process = ProcessBuilder("/bin/sh", "-n", "-c", script).redirectErrorStream(true).start()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            val errors = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(errors, 0, process.exitValue())
        }
    }

    private fun fixture(original: String, originalStatus: Int, nativeOutput: String, nativeStatus: Int): String =
        "test() { return 0; }\n" +
            "settings() { printf '%s\\n' ${RemoteInputShellTransaction.quote(original)}; return $originalStatus; }\n" +
            "cmd() { printf '%s\\n' ${RemoteInputShellTransaction.quote(nativeOutput)}; return $nativeStatus; }\n" +
            "bounded() { shift; \"${'$'}@\"; }\n" +
            RemoteInputShellPreflight.command().replace("/system/bin/timeout", "bounded")

    private fun runShell(script: String): Pair<Int, String> {
        assumeTrue(File("/bin/sh").canExecute())
        val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue("Fixed shell fixture must finish within five seconds", finished)
        return process.exitValue() to process.inputStream.bufferedReader().use { it.readText() }
    }
}
