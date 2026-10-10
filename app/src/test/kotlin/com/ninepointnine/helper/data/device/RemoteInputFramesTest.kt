package com.ninepointnine.helper.data.device

import java.io.IOException
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class RemoteInputFramesTest {
    private val state = """{"v":1,"type":"state","available":true,"keyboard":"open","epoch":3,"targetPackage":"example.search","targetWindow":12,"sequence":8,"sending":false}"""
    private val unknownOpenState = """{"v":1,"type":"state","available":true,"keyboard":"open","epoch":18,"targetPackage":"","targetWindow":-1,"sequence":49,"sending":false}"""
    @Test fun validOpenTargetRejectsIncompleteTargetPairs() {
        val frame = RemoteInputFrames.state(RemoteInputFrames.decode(state))
        assertEquals("open", frame.keyboard)
        assertEquals(3L, frame.epoch)
        assertThrows(IOException::class.java) {
            RemoteInputFrames.state(RemoteInputFrames.decode(state.replace("example.search", "")))
        }
        assertThrows(IOException::class.java) {
            RemoteInputFrames.state(RemoteInputFrames.decode(state.replace("\"targetWindow\":12", "\"targetWindow\":-1")))
        }
    }
    @Test fun canonicalUnknownOpenTargetIsNotAProtocolError() {
        val frame = RemoteInputFrames.state(RemoteInputFrames.decode(unknownOpenState))
        assertTrue(frame.available)
        assertEquals("open", frame.keyboard)
        assertEquals(18L, frame.epoch)
        assertEquals("", frame.targetPackage)
        assertEquals(-1, frame.targetWindow)
        assertEquals(49L, frame.sequence)
        assertFalse(frame.sending)
    }
    @Test fun sameSubscriptionParsesUnknownOpenThenKnownOpen() {
        val knownOpenState = unknownOpenState
            .replace("\"targetPackage\":\"\"", "\"targetPackage\":\"example.search\"")
            .replace("\"targetWindow\":-1", "\"targetWindow\":12")
            .replace("\"sequence\":49", "\"sequence\":50")
        val source = okio.Buffer().writeUtf8("$unknownOpenState\n$knownOpenState\n")

        val unknown = RemoteInputFrames.state(RemoteInputFrames.read(source))
        val known = RemoteInputFrames.state(RemoteInputFrames.read(source))

        assertEquals("open", unknown.keyboard)
        assertEquals("", unknown.targetPackage)
        assertEquals(-1, unknown.targetWindow)
        assertEquals(49L, unknown.sequence)
        assertEquals("open", known.keyboard)
        assertEquals("example.search", known.targetPackage)
        assertEquals(12, known.targetWindow)
        assertEquals(50L, known.sequence)
        assertEquals(unknown.epoch, known.epoch)
        assertTrue(source.exhausted())
    }
    @Test fun malformedOrUnsupportedFramesNeverEnableSend() {
        listOf("{", state.replace("\"v\":1", "\"v\":2"), state.replace("\"v\":1", "\"v\":\"1\""), "x".repeat(65537)).forEach { text ->
            assertThrows(IOException::class.java) { RemoteInputFrames.decode(text) }
        }
        assertThrows(IOException::class.java) { RemoteInputFrames.state(RemoteInputFrames.decode(state.replace("open", "visible"))) }
    }
    @Test fun bundleRequiresOneUnambiguousFrame() {
        val encoded = Base64.getEncoder().encodeToString(state.toByteArray())
        assertEquals("state", RemoteInputFrames.string(RemoteInputFrames.decodeProvider("Result: Bundle[{frame=$encoded}]"), "type"))
        assertThrows(IOException::class.java) { RemoteInputFrames.decodeProvider("Bundle[{frame=$encoded, frame=$encoded}]") }
    }
    @Test fun wrongTypesAndBrokenEncodingAreRejected() {
        listOf(state, unknownOpenState).forEach { valid ->
            listOf(
                valid.replace("\"available\":true", "\"available\":\"true\""),
                valid.replace(Regex("\"sequence\":\\d+"), "\"sequence\":\"8\""),
                valid.replace(Regex("\"epoch\":\\d+"), "\"epoch\":[]"),
                valid.replace(Regex("\"targetPackage\":\"[^\"]*\""), "\"targetPackage\":42"),
                valid.replace(Regex("\"targetWindow\":-?\\d+"), "\"targetWindow\":\"12\""),
                valid.replace("\"sending\":false", "\"sending\":\"false\""),
            ).forEach { text -> assertThrows(IOException::class.java) { RemoteInputFrames.state(RemoteInputFrames.decode(text)) } }
        }
        val invalid = Base64.getEncoder().encodeToString(byteArrayOf(0xc3.toByte(), 0x28))
        assertThrows(IOException::class.java) { RemoteInputFrames.decodeProvider("Bundle[{frame=$invalid}]") }
    }
    @Test fun stateNumericRangesRemainStrictForKnownAndUnknownTargets() {
        listOf(state, unknownOpenState).forEach { valid ->
            listOf(
                valid.replace(Regex("\"epoch\":\\d+"), "\"epoch\":-1"),
                valid.replace(Regex("\"sequence\":\\d+"), "\"sequence\":-1"),
                valid.replace(Regex("\"sequence\":\\d+"), "\"sequence\":9223372036854775808"),
                valid.replace(Regex("\"targetWindow\":-?\\d+"), "\"targetWindow\":-2"),
                valid.replace(Regex("\"targetWindow\":-?\\d+"), "\"targetWindow\":2147483648"),
            ).forEach { text -> assertThrows(IOException::class.java) { RemoteInputFrames.state(RemoteInputFrames.decode(text)) } }
        }
    }
    @Test fun streamRequiresCompleteBoundedUtf8Frame() {
        assertEquals("state", RemoteInputFrames.string(RemoteInputFrames.read(okio.Buffer().writeUtf8("$state\n")), "type"))
        assertThrows(IOException::class.java) { RemoteInputFrames.read(okio.Buffer().writeUtf8(state)) }
        assertThrows(IOException::class.java) { RemoteInputFrames.read(okio.Buffer().writeUtf8("x".repeat(65_537) + "\n")) }
        assertThrows(IOException::class.java) {
            RemoteInputFrames.read(okio.Buffer().write(byteArrayOf(0xc3.toByte(), 0x28, 10)))
        }
    }
    @Test fun fixedTransactionIncludesIndependentRecoveryAndDoesNotAcceptCommands() {
        val id = "f495c2a8-7b10-4876-877c-123456789abc"
        val script = RemoteInputShellTransaction.build("com.ninepointnine.desktop.test.remoteinput", "com.ninepointnine.desktop.test/com.ninepointnine.desktop.input.RemoteInputMethodService", "example.keyboard/.KeyboardService", id)
        assertTrue(script.contains("nohup sh -c"))
        assertTrue(script.contains("claim_restore"))
        assertTrue(script.contains("release_restore"))
        assertTrue(script.contains("HUP INT TERM; claim="))
        assertTrue(script.contains("restore_worker"))
        assertTrue(script.contains("wait"))
        assertTrue(script.contains("trap 'restore' EXIT HUP INT TERM"))
        assertTrue(script.contains("03INPUT_RESTORED=%s"))
        assertTrue(script.contains("if [ \"\$restore_ok\" = 1 ]; then kill"))
        assertEquals(3, Regex("settings get secure default_input_method").findAll(script).count())
        val native = RemoteInputShellTransaction.build("com.ninepointnine.desktop.test.remoteinput", "com.ninepointnine.desktop.test/com.ninepointnine.desktop.input.RemoteInputMethodService", "example.keyboard/.KeyboardService", id, nativeImeCommand = true)
        assertTrue(native.contains("cmd input_method ime enable"))
        assertTrue(native.contains("cmd input_method ime set"))
        assertTrue(native.contains("trap 'restore' EXIT HUP INT TERM"))
        assertTrue(script.contains("if ime enable"))
        assertThrows(IllegalArgumentException::class.java) {
            RemoteInputShellTransaction.build("example;reboot.remoteinput", "example/Service", "keyboard/Service", id)
        }
    }
}
