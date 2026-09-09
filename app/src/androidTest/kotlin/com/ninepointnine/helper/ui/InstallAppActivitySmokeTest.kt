package com.ninepointnine.helper.ui

import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninepointnine.helper.MainActivity
import com.ninepointnine.helper.R
import com.ninepointnine.helper.debug.DebugScenarioActivity
import com.ninepointnine.helper.domain.session.ComponentDescriptor
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.session.DeviceSummary
import com.ninepointnine.helper.domain.session.FailureCategory
import com.ninepointnine.helper.domain.session.InstallationSession
import com.ninepointnine.helper.domain.session.InstallationSessionCommand
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import com.ninepointnine.helper.domain.session.InstallationSessionSnapshot
import com.ninepointnine.helper.domain.session.InstallationSessionState
import com.ninepointnine.helper.domain.session.MaintenanceSnapshot
import com.ninepointnine.helper.toInstallationSessionCommand
import com.ninepointnine.helper.ui.state.failureReasonToUserMessage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstallAppActivitySmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun maintenanceSingleSelectionFailureShowsOnlySelectedApplication() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apps = listOf(
            ComponentDescriptor(id = "traffic-light", displayName = "红绿灯领航", required = false),
            ComponentDescriptor(id = "bilibili", displayName = "哔哩哔哩三方客户端", required = false),
        )
        val session = InstallationSession(initialSnapshot = InstallationSessionSnapshot(
            state = InstallationSessionState.MAINTENANCE,
            device = DeviceSummary(
                id = "smoke-vehicle", displayName = "Smoke Vehicle",
                connectionStatus = DeviceConnectionStatus.CONFIRMED,
            ),
            components = apps,
            maintenance = MaintenanceSnapshot(availableComponents = apps),
        ))
        val scenario = ActivityScenario.launch<DebugScenarioActivity>(
            Intent(context, DebugScenarioActivity::class.java)
                .putExtra(DebugScenarioActivity.EXTRA_SCENARIO, "maintenance"),
        )
        try {
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContent {
                    InstallApp(session.snapshots.collectAsState().value, onIntent = { intent ->
                        val command = intent.toInstallationSessionCommand()
                        session.dispatch(command)
                        if (command is InstallationSessionCommand.MaintenanceAction) {
                            session.dispatchEvent(InstallationSessionEvent.MaintenanceApplicationsResolved(emptyList()))
                        }
                    })
                }
            }
            compose.onNodeWithText(context.getString(R.string.maintenance_install_file_manager)).performScrollTo()
            clickText(context.getString(R.string.maintenance_install_file_manager))
            compose.onNodeWithText(apps[0].displayName).assertExists()
            clickText(apps[1].displayName)
            assertEquals(setOf("bilibili"), session.currentSnapshot().maintenance.installationSelection?.selectedComponentIds)
            captureScreen("maintenance-selection")
            clickText(context.getString(R.string.maintenance_install_start))
            assertEquals(setOf("bilibili"), session.currentSnapshot().installationBatch?.preparationComponentIds)
            scenario.onActivity {
                session.dispatch(InstallationSessionCommand.BeginPipeline)
                session.dispatchEvent(InstallationSessionEvent.FatalError(
                    category = FailureCategory.DOWNLOAD,
                    componentName = "bilibili",
                    reasonCode = "download_not_zip",
                ))
            }
            compose.onNodeWithText(context.getString(R.string.result_retry)).assertExists()
            assertEquals(listOf("bilibili"), session.currentSnapshot().componentResults.map { it.componentId })
            compose.onNodeWithText(apps[0].displayName).assertDoesNotExist()
            compose.onNodeWithText(apps[1].displayName).assertExists()
            captureScreen("maintenance-single-result")
            scenario.onActivity {
                session.dispatchEvent(InstallationSessionEvent.FatalError(
                    category = FailureCategory.VERIFICATION,
                    reasonCode = "artifact_identity_evidence_missing",
                ))
            }
            compose.onNodeWithText(checkNotNull(failureReasonToUserMessage("artifact_identity_evidence_missing")))
                .assertExists()
            compose.onNodeWithText(apps[0].displayName).assertDoesNotExist()
            captureScreen("maintenance-batch-failure")
            clickText(context.getString(R.string.result_retry))
            compose.onNodeWithText(apps[0].displayName).assertExists()
            assertEquals(InstallationSessionState.MAINTENANCE, session.currentSnapshot().state)
            assertEquals(setOf("bilibili"), session.currentSnapshot().maintenance.installationSelection?.selectedComponentIds)
        } finally {
            scenario.close()
            session.close()
        }
    }

    private fun captureScreen(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            File(instrumentation.targetContext.cacheDir, "$name.png").outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun clickText(text: String) {
        compose.onNodeWithText(text).performClick()
        compose.waitForIdle()
    }

    @Test
    fun productionActivityReachesResumedState() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var resumed = false
        scenario.onActivity { activity ->
            resumed = !activity.isFinishing && !activity.isDestroyed
        }
        assertTrue("MainActivity did not reach a usable state", resumed)
        scenario.close()
    }

    @Test
    fun debugMaintenanceScenarioReachesResumedState() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, DebugScenarioActivity::class.java)
            .putExtra(DebugScenarioActivity.EXTRA_SCENARIO, "maintenance")
        val scenario = ActivityScenario.launch<DebugScenarioActivity>(intent)
        var resumed = false
        scenario.onActivity { activity ->
            resumed = !activity.isFinishing && !activity.isDestroyed
        }
        assertTrue("Debug maintenance scenario did not reach a usable state", resumed)
        scenario.close()
    }
}
