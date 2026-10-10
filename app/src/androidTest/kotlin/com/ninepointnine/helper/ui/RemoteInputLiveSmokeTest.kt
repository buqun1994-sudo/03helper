package com.ninepointnine.helper.ui

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninepointnine.helper.InstallerApplication
import com.ninepointnine.helper.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in live test: the operator prepares one vehicle editor; never navigates or replays. */
@RunWith(AndroidJUnit4::class)
class RemoteInputLiveSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun currentVehicleEditorAcceptsOnePhoneSend() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("remoteInputLive") == "true")
        val expectedPackage = requireNotNull(args.getString("remoteInputExpectedPackage"))
        val text = requireNotNull(args.getString("remoteInputTestText")).also { require(it.isNotBlank()) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as InstallerApplication
        val runtime = app.installerRuntime
        ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_INPUT),
        ).use { scenario ->
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            try {
                compose.waitUntil(30_000) {
                    val input = runtime.session.currentSnapshot().remoteInput
                    input.hasState && input.available && input.keyboardOpen && input.targetPackage == expectedPackage && input.targetWindow >= 0
                }
            } finally {
                val state = runtime.session.currentSnapshot()
                val input = state.remoteInput
                Log.i("03InputLiveSmoke", "before_send state=${state.state} device=${state.device?.connectionStatus} input=${input.status} reason=${input.statusReason} keyboard=${input.keyboardOpen} target=${input.targetPackage}:${input.targetWindow}")
            }
            assertTrue("Must not replace an existing user draft", runtime.session.currentSnapshot().remoteInput.draft.isEmpty())
            compose.onNodeWithTag("remote_input_editor").performTextInput(text)
            compose.waitUntil(5_000) { runtime.session.currentSnapshot().remoteInput.canSend }
            val revision = runtime.session.currentSnapshot().remoteInput.noticeRevision
            val start = SystemClock.elapsedRealtime()
            compose.onNodeWithTag("remote_input_send").performClick()
            compose.waitUntil(30_000) {
                val input = runtime.session.currentSnapshot().remoteInput
                input.pendingSend == null && input.noticeRevision > revision
            }
            val elapsed = SystemClock.elapsedRealtime() - start
            val finished = runtime.session.currentSnapshot().remoteInput
            Log.i("03InputLiveSmoke", "phone_send_finished_ms=$elapsed notice=${finished.notice} status=${finished.status} target=${finished.targetPackage}:${finished.targetWindow}")
            assertEquals("Successful notice requires both commit and verified restoration", "remote_input_sent", finished.notice)
            assertEquals("", finished.draft)
        }
    }
}
