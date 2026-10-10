package com.ninepointnine.helper.domain.session

import com.ninepointnine.helper.data.device.RemoteInputFrames
import com.ninepointnine.helper.domain.device.RemoteInputSubscription
import com.ninepointnine.helper.domain.device.VehicleInputState
import com.ninepointnine.helper.domain.input.RemoteInputSnapshot
import com.ninepointnine.helper.domain.input.RemoteInputStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputSessionTest {
    @Test
    fun `disconnected input cannot send and old state cannot make it ready`() {
        val session = inputSession(remote = readyInput("draft"))
        session.dispatch(InstallationSessionCommand.DisconnectDevice)
        val before = session.currentSnapshot()

        session.dispatch(InstallationSessionCommand.RemoteInputStateReceived(
            generation = 1L,
            sequence = 99L,
            keyboardOpen = true,
            epoch = 1L,
            targetPackage = "com.example.target",
            targetWindow = 1,
            available = true,
        ))
        session.dispatch(InstallationSessionCommand.SendRemoteInputText("fixed-id"))

        val after = session.currentSnapshot()
        assertEquals(RemoteInputStatus.DISCONNECTED, after.remoteInput.status)
        assertEquals("remote_input_disconnected", after.remoteInput.notice)
        assertEquals(before.remoteInput.connectionGeneration, after.remoteInput.connectionGeneration)
    }

    @Test
    fun `late generation and sequence observations are rejected`() {
        val session = inputSession()
        session.dispatch(InstallationSessionCommand.RemoteInputConnectionChanged(
            generation = 2L,
            status = RemoteInputStatus.SYNCING,
        ))
        session.dispatch(InstallationSessionCommand.RemoteInputStateReceived(
            generation = 2L,
            sequence = 0L,
            keyboardOpen = true,
            epoch = 4L,
            targetPackage = "com.example.target",
            targetWindow = 3,
            available = true,
        ))
        val ready = session.currentSnapshot()
        assertEquals(RemoteInputStatus.READY, ready.remoteInput.status)

        session.dispatch(InstallationSessionCommand.RemoteInputStateReceived(
            generation = 1L,
            sequence = 99L,
            keyboardOpen = false,
            epoch = 5L,
            targetPackage = "com.example.old",
            targetWindow = 9,
            available = false,
        ))
        session.dispatch(InstallationSessionCommand.RemoteInputStateReceived(
            generation = 2L,
            sequence = 0L,
            keyboardOpen = false,
            epoch = 5L,
            targetPackage = "com.example.old",
            targetWindow = 9,
            available = false,
        ))

        assertEquals(ready.remoteInput, session.currentSnapshot().remoteInput)
    }

    @Test
    fun `unknown open target blocks send and recovers in the same subscription`() = runBlocking<Unit> {
        val unknownOpen = """{"v":1,"type":"state","available":true,"keyboard":"open","epoch":18,"targetPackage":"","targetWindow":-1,"sequence":49,"sending":false}"""
        val knownOpen = unknownOpen
            .replace("\"targetPackage\":\"\"", "\"targetPackage\":\"com.example.target\"")
            .replace("\"targetWindow\":-1", "\"targetWindow\":3")
            .replace("\"sequence\":49", "\"sequence\":50")
        val source = okio.Buffer().writeUtf8("$unknownOpen\n$knownOpen\n")
        val subscription = object : RemoteInputSubscription {
            override suspend fun next(): VehicleInputState = RemoteInputFrames.state(RemoteInputFrames.read(source))
            override fun close() = Unit
        }
        val session = inputSession(remote = RemoteInputSnapshot(draft = "keep draft"))
        session.dispatch(InstallationSessionCommand.RemoteInputConnectionChanged(
            generation = 1L,
            status = RemoteInputStatus.SYNCING,
        ))
        suspend fun receiveNextState() {
            val state = subscription.next()
            session.dispatch(InstallationSessionCommand.RemoteInputStateReceived(
                generation = 1L,
                sequence = state.sequence,
                keyboardOpen = state.keyboard == "open",
                epoch = state.epoch,
                targetPackage = state.targetPackage,
                targetWindow = state.targetWindow,
                available = state.available,
                vehicleSending = state.sending,
            ))
        }

        receiveNextState()
        val unknown = session.currentSnapshot().remoteInput
        assertEquals(RemoteInputStatus.UNAVAILABLE, unknown.status)
        assertTrue(unknown.hasState)
        assertTrue(unknown.available)
        assertTrue(unknown.keyboardOpen)
        assertEquals(49L, unknown.sequence)
        assertEquals("", unknown.targetPackage)
        assertEquals(-1, unknown.targetWindow)
        assertEquals("remote_input_target_unavailable", unknown.statusReason)
        assertFalse(unknown.canSend)
        session.dispatch(InstallationSessionCommand.SendRemoteInputText("blocked-send"))
        assertEquals(null, session.currentSnapshot().remoteInput.pendingSend)
        assertEquals("keep draft", session.currentSnapshot().remoteInput.draft)
        assertEquals("remote_input_target_unavailable", session.currentSnapshot().remoteInput.notice)

        receiveNextState()
        val ready = session.currentSnapshot().remoteInput
        assertEquals(unknown.connectionGeneration, ready.connectionGeneration)
        assertEquals(unknown.epoch, ready.epoch)
        assertEquals(50L, ready.sequence)
        assertEquals(RemoteInputStatus.READY, ready.status)
        assertTrue(ready.canSend)
        assertEquals("keep draft", ready.draft)
        session.dispatch(InstallationSessionCommand.SendRemoteInputText("known-send"))
        val request = session.currentSnapshot().remoteInput.pendingSend
        assertEquals("known-send", request?.requestId)
        assertEquals("com.example.target", request?.targetPackage)
        assertEquals(3, request?.targetWindow)
        assertTrue(source.exhausted())
        subscription.close()
    }

    @Test
    fun `draft edits while sending do not change frozen sent text`() {
        val session = inputSession(remote = readyInput("first"))
        session.dispatch(InstallationSessionCommand.SendRemoteInputText("request-1"))
        val sending = session.currentSnapshot().remoteInput
        assertEquals(RemoteInputStatus.SENDING, sending.status)
        assertEquals("first", sending.sentText)
        assertEquals("first", sending.pendingSend?.text)

        session.dispatch(InstallationSessionCommand.UpdateRemoteInputDraft("second"))
        assertEquals("second", session.currentSnapshot().remoteInput.draft)
        assertEquals("first", session.currentSnapshot().remoteInput.sentText)

        session.dispatch(InstallationSessionCommand.RemoteInputSendFinished(
            generation = 1L,
            requestId = "request-1",
            committed = true,
            restored = true,
        ))
        val finished = session.currentSnapshot().remoteInput
        assertEquals(RemoteInputStatus.READY, finished.status)
        assertEquals("second", finished.draft)
        assertEquals(null, finished.sentText)
    }

    @Test
    fun `same request id and repeated clicks cannot create a second send`() {
        val session = inputSession(remote = readyInput("once"))
        session.dispatch(InstallationSessionCommand.SendRemoteInputText("request-1"))
        val first = session.currentSnapshot()
        session.dispatch(InstallationSessionCommand.SendRemoteInputText("request-1"))
        assertEquals(first, session.currentSnapshot())

        session.dispatch(InstallationSessionCommand.RemoteInputSendFinished(
            generation = 1L,
            requestId = "request-1",
            committed = true,
            restored = true,
        ))
        val completed = session.currentSnapshot()
        session.dispatch(InstallationSessionCommand.SendRemoteInputText("request-1"))
        assertEquals(completed, session.currentSnapshot())
    }

    @Test
    fun `state channel loss keeps pending send until its independent receipt finishes`() {
        val session = inputSession(remote = readyInput("keep"))
        session.dispatch(InstallationSessionCommand.SendRemoteInputText("request-1"))
        session.dispatch(InstallationSessionCommand.RemoteInputConnectionChanged(
            1L, RemoteInputStatus.UNAVAILABLE, "remote_input_channel_lost",
        ))
        assertEquals(RemoteInputStatus.SENDING, session.currentSnapshot().remoteInput.status)
        assertEquals("request-1", session.currentSnapshot().remoteInput.pendingSend?.requestId)
        session.dispatch(InstallationSessionCommand.RemoteInputSendFinished(1L, "request-1", true, true))
        assertEquals("", session.currentSnapshot().remoteInput.draft)
        assertEquals("remote_input_sent", session.currentSnapshot().remoteInput.notice)
        assertTrue(!session.currentSnapshot().remoteInput.canSend)
    }

    @Test
    fun `unknown outcome keeps draft and requires a new generation sync`() {
        val session = inputSession(remote = readyInput("keep me"))
        session.dispatch(InstallationSessionCommand.SendRemoteInputText("request-1"))
        session.dispatch(InstallationSessionCommand.RemoteInputSendFinished(
            generation = 1L,
            requestId = "request-1",
            committed = null,
            restored = false,
            reason = "transport_lost",
        ))
        val unknown = session.currentSnapshot().remoteInput
        assertEquals(RemoteInputStatus.UNKNOWN_RESULT, unknown.status)
        assertEquals("keep me", unknown.draft)
        assertEquals(null, unknown.pendingSend)

        session.dispatch(InstallationSessionCommand.RemoteInputStateReceived(
            generation = 1L,
            sequence = 2L,
            keyboardOpen = true,
            epoch = 2L,
            targetPackage = "com.example.target",
            targetWindow = 3,
            available = true,
        ))
        assertEquals(RemoteInputStatus.UNKNOWN_RESULT, session.currentSnapshot().remoteInput.status)

        session.dispatch(InstallationSessionCommand.RemoteInputConnectionChanged(
            generation = 2L,
            status = RemoteInputStatus.SYNCING,
        ))
        session.dispatch(InstallationSessionCommand.RemoteInputStateReceived(
            generation = 2L,
            sequence = 1L,
            keyboardOpen = true,
            epoch = 1L,
            targetPackage = "com.example.target",
            targetWindow = 3,
            available = true,
        ))
        val resynced = session.currentSnapshot().remoteInput
        assertEquals(RemoteInputStatus.READY, resynced.status)
        assertTrue(resynced.canSend)
    }

    @Test
    fun `explicit unknown result stays blocked until a new generation syncs`() {
        val session = inputSession(remote = readyInput("keep"))
        session.dispatch(InstallationSessionCommand.RemoteInputConnectionChanged(
            generation = 1L,
            status = RemoteInputStatus.UNKNOWN_RESULT,
        ))
        session.dispatch(InstallationSessionCommand.RemoteInputStateReceived(
            generation = 1L,
            sequence = 2L,
            keyboardOpen = true,
            epoch = 1L,
            targetPackage = "com.example.target",
            targetWindow = 3,
            available = true,
        ))
        assertEquals(RemoteInputStatus.UNKNOWN_RESULT, session.currentSnapshot().remoteInput.status)
        session.dispatch(InstallationSessionCommand.SendRemoteInputText("fixed-id"))
        assertEquals(null, session.currentSnapshot().remoteInput.pendingSend)

        session.dispatch(InstallationSessionCommand.RemoteInputConnectionChanged(
            generation = 2L,
            status = RemoteInputStatus.SYNCING,
        ))
        session.dispatch(InstallationSessionCommand.RemoteInputStateReceived(
            generation = 2L,
            sequence = 0L,
            keyboardOpen = true,
            epoch = 1L,
            targetPackage = "com.example.target",
            targetWindow = 3,
            available = true,
        ))
        assertEquals(RemoteInputStatus.READY, session.currentSnapshot().remoteInput.status)
    }

    @Test
    fun `busy maintenance operation rejects input send without cancelling operation`() {
        val session = inputSession(
            remote = readyInput("blocked"),
            activeAction = MaintenanceActionId.MANAGE_APPS,
        )
        val before = session.currentSnapshot()
        session.dispatch(InstallationSessionCommand.SendRemoteInputText("request-1"))
        val after = session.currentSnapshot()
        assertEquals(MaintenanceActionId.MANAGE_APPS, after.maintenance.activeAction)
        assertEquals(RemoteInputStatus.READY, after.remoteInput.status)
        assertEquals("remote_input_busy", after.remoteInput.notice)
        assertEquals(before.maintenance.activeAction, after.maintenance.activeAction)
    }

    @Test
    fun `vehicle sending shares domain readiness and cannot be cleared by stale state`() {
        val session = inputSession(remote = readyInput("draft"))
        val busy = InstallationSessionCommand.RemoteInputStateReceived(
            generation = 1L, sequence = 2L, keyboardOpen = true, epoch = 1L,
            targetPackage = "com.example.target", targetWindow = 3, available = true, vehicleSending = true,
        )
        session.dispatch(busy)
        assertTrue(!session.currentSnapshot().remoteInput.canSend)
        assertEquals("remote_input_busy", session.currentSnapshot().remoteInput.statusReason)
        session.dispatch(InstallationSessionCommand.SendRemoteInputText("busy-send"))
        assertEquals(null, session.currentSnapshot().remoteInput.pendingSend)
        session.dispatch(busy.copy(sequence = 1L, vehicleSending = false))
        assertTrue(!session.currentSnapshot().remoteInput.canSend)
        session.dispatch(busy.copy(sequence = 3L, vehicleSending = false))
        assertTrue(session.currentSnapshot().remoteInput.canSend)
    }

    @Test
    fun `overlong utf8 draft is rejected without truncating the prior draft`() {
        val prior = "line 1\nline 2"
        val session = inputSession(remote = readyInput(prior))
        session.dispatch(InstallationSessionCommand.UpdateRemoteInputDraft("😀".repeat(RemoteInputSnapshot.MAX_TEXT_BYTES)))
        val input = session.currentSnapshot().remoteInput
        assertEquals(prior, input.draft)
        assertEquals("remote_input_text_too_long", input.notice)
    }

    @Test
    fun `maintenance input and install menu are pure routes`() {
        val session = inputSession()
        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.INSTALL_MENU))
        assertEquals(MaintenanceActionId.INSTALL_MENU, session.currentSnapshot().maintenance.routeAction)
        assertTrue(!MaintenanceActionId.INSTALL_MENU.requiresConnectedDevice)
        session.dispatch(InstallationSessionCommand.MaintenanceAction(MaintenanceActionId.INPUT_TEXT))
        assertEquals(MaintenanceActionId.INPUT_TEXT, session.currentSnapshot().maintenance.routeAction)
        session.dispatch(InstallationSessionCommand.LeaveMaintenanceAction)
        assertEquals(null, session.currentSnapshot().maintenance.routeAction)
    }

    private fun inputSession(
        remote: RemoteInputSnapshot = RemoteInputSnapshot(),
        activeAction: MaintenanceActionId? = null,
    ): InstallationSession = InstallationSession(
        initialSnapshot = InstallationSessionSnapshot(
            state = InstallationSessionState.MAINTENANCE,
            device = DeviceSummary(
                id = "vehicle",
                displayName = "Vehicle",
                connectionStatus = DeviceConnectionStatus.CONFIRMED,
            ),
            remoteInput = remote,
            maintenance = MaintenanceSnapshot(
                routeAction = MaintenanceActionId.INPUT_TEXT,
                activeAction = activeAction,
                lastAction = activeAction?.let {
                    MaintenanceActionRecord(it, MaintenanceActionStatus.RUNNING)
                },
            ),
        ),
    )

    private fun readyInput(text: String): RemoteInputSnapshot = RemoteInputSnapshot(
        connectionGeneration = 1L,
        hasState = true,
        leaseConfirmed = true,
        status = RemoteInputStatus.READY,
        keyboardOpen = true,
        epoch = 1L,
        targetPackage = "com.example.target",
        targetWindow = 3,
        sequence = 1L,
        draft = text,
        available = true,
    )
}
