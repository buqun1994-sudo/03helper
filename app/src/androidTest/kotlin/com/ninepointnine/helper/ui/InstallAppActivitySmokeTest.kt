package com.ninepointnine.helper.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
import com.ninepointnine.helper.domain.session.MaintenanceActionId
import com.ninepointnine.helper.domain.session.MaintenanceApplicationActionId
import com.ninepointnine.helper.domain.session.MaintenanceInventoryState
import com.ninepointnine.helper.domain.session.MaintenanceSnapshot
import com.ninepointnine.helper.domain.session.ManagedApplicationStatus
import com.ninepointnine.helper.domain.device.ApplicationAuthorizationRequirement
import com.ninepointnine.helper.domain.device.ApplicationAuthorizationResultValue
import com.ninepointnine.helper.toInstallationSessionCommand
import com.ninepointnine.helper.ui.state.InstallUiIntent
import com.ninepointnine.helper.ui.state.failureReasonToUserMessage
import java.io.File
import java.util.concurrent.atomic.AtomicReference
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
                        intent.toInstallationSessionCommand()?.let { command ->
                            session.dispatch(command)
                            if (command is InstallationSessionCommand.MaintenanceAction) {
                                session.dispatchEvent(InstallationSessionEvent.MaintenanceApplicationsResolved(emptyList()))
                            }
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

    @Test
    fun authorizationDialogUsesTerminalActionsAndKeepsSuccessNoticeAboveIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentDescriptor(
            id = "browser",
            displayName = "测试浏览器",
            required = false,
        )
        val application = ManagedApplicationStatus(
            componentId = component.id,
            packageName = "com.example.browser",
            installed = true,
            versionLabel = "1.0",
            versionCode = 1L,
        )
        val inspectionRequirements = AtomicReference(emptyList<ApplicationAuthorizationRequirement>())
        val session = InstallationSession(
            initialSnapshot = InstallationSessionSnapshot(
                state = InstallationSessionState.MAINTENANCE,
                device = DeviceSummary(
                    id = "smoke-vehicle",
                    displayName = "Smoke Vehicle",
                    connectionStatus = DeviceConnectionStatus.CONFIRMED,
                ),
                components = listOf(component),
                maintenance = MaintenanceSnapshot(
                    routeAction = MaintenanceActionId.MANAGE_APPS,
                    managedApplicationsState = MaintenanceInventoryState.READY,
                    managedApplications = listOf(application),
                ),
            ),
        )
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
                        if (command is InstallationSessionCommand.MaintenanceApplicationAction) {
                            session.dispatch(command)
                            val requirements = when (command.actionId) {
                                MaintenanceApplicationActionId.INSPECT_AUTHORIZATION ->
                                    inspectionRequirements.get()
                                MaintenanceApplicationActionId.AUTHORIZE ->
                                    inspectionRequirements.get().map {
                                        it.copy(
                                            grantedAfter = true,
                                            reasonCode = null,
                                            authorizationAttempted = true,
                                        )
                                    }
                                else -> emptyList()
                            }
                            if (command.actionId in setOf(
                                    MaintenanceApplicationActionId.INSPECT_AUTHORIZATION,
                                    MaintenanceApplicationActionId.AUTHORIZE,
                                )
                            ) {
                                session.dispatchEvent(
                                    InstallationSessionEvent.MaintenanceApplicationAuthorizationResolved(
                                        actionId = command.actionId,
                                        result = ApplicationAuthorizationResultValue(
                                            componentId = application.componentId,
                                            packageName = application.packageName,
                                            requirements = requirements,
                                        ),
                                    ),
                                )
                            }
                        }
                    })
                }
            }

            clickText(component.displayName)
            clickText(context.getString(R.string.maintenance_authorize))
            dialogText(context.getString(R.string.maintenance_install_done)).assertIsDisplayed()
            dialogText(context.getString(R.string.maintenance_cancel)).assertDoesNotExist()
            dialogText(context.getString(R.string.maintenance_install_done)).performClick()
            compose.waitForIdle()

            inspectionRequirements.set(
                listOf(
                    ApplicationAuthorizationRequirement(
                        permission = "android.permission.CAMERA",
                        grantedBefore = false,
                        grantedAfter = false,
                    ),
                ),
            )
            clickText(context.getString(R.string.maintenance_authorize))
            dialogText(context.getString(R.string.maintenance_authorize)).performClick()
            compose.waitForIdle()

            dialogText(context.getString(R.string.maintenance_finish)).assertIsDisplayed()
            compose.onNodeWithTag("application_action_notice", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.maintenance_authorization_success)).assertIsDisplayed()
            captureDeviceScreen("authorization-finished-top-layer")

            dialogText(context.getString(R.string.maintenance_finish)).performClick()
            compose.waitForIdle()
            compose.onNodeWithText(
                context.getString(R.string.maintenance_authorization_title, component.displayName),
            ).assertDoesNotExist()
        } finally {
            scenario.close()
            session.close()
        }
    }

    @Test
    fun managedApplicationShowsSevenActionsAndDefersExportUntilDestinationSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentDescriptor(
            id = "player",
            displayName = "测试播放器",
            required = false,
        )
        val application = ManagedApplicationStatus(
            componentId = component.id,
            packageName = "com.example.player",
            installed = true,
            versionLabel = "1.0",
            versionCode = 1L,
        )
        val session = InstallationSession(
            initialSnapshot = InstallationSessionSnapshot(
                state = InstallationSessionState.MAINTENANCE,
                device = DeviceSummary(
                    id = "smoke-vehicle",
                    displayName = "Smoke Vehicle",
                    connectionStatus = DeviceConnectionStatus.CONFIRMED,
                ),
                components = listOf(component),
                maintenance = MaintenanceSnapshot(
                    routeAction = MaintenanceActionId.MANAGE_APPS,
                    managedApplicationsState = MaintenanceInventoryState.READY,
                    managedApplications = listOf(application),
                ),
            ),
        )
        val observedIntent = AtomicReference<InstallUiIntent?>()
        val scenario = ActivityScenario.launch<DebugScenarioActivity>(
            Intent(context, DebugScenarioActivity::class.java)
                .putExtra(DebugScenarioActivity.EXTRA_SCENARIO, "maintenance"),
        )
        try {
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContent {
                    InstallApp(
                        snapshot = session.snapshots.collectAsState().value,
                        onIntent = observedIntent::set,
                    )
                }
            }

            clickText(component.displayName)
            listOf(
                R.string.maintenance_start,
                R.string.maintenance_force_stop,
                R.string.maintenance_clear_data,
                R.string.maintenance_authorize,
                R.string.maintenance_uninstall,
                R.string.maintenance_details,
                R.string.maintenance_export_logs,
            ).forEach { label ->
                compose.onNodeWithText(context.getString(label)).assertExists()
            }
            val export = compose.onNodeWithTag("maintenance_application_action_export_diagnostics")
            export.performScrollTo()
            val startWidth = compose.onNodeWithTag("maintenance_application_action_start")
                .fetchSemanticsNode().boundsInRoot.width
            val exportWidth = export.fetchSemanticsNode().boundsInRoot.width
            assertTrue("exportWidth=$exportWidth startWidth=$startWidth", exportWidth > startWidth * 1.8f)
            captureScreen("maintenance-seven-application-actions")

            export.performClick()
            assertEquals(
                InstallUiIntent.PickApplicationDiagnosticsDestination(application.packageName),
                observedIntent.get(),
            )
            assertEquals(null, session.currentSnapshot().maintenance.applicationAction)
        } finally {
            scenario.close()
            session.close()
        }
    }

    @Test
    fun installationProgressShowsAllSixPhasesAndNumericPercentage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = ActivityScenario.launch<DebugScenarioActivity>(
            Intent(context, DebugScenarioActivity::class.java)
                .putExtra(DebugScenarioActivity.EXTRA_SCENARIO, "progress"),
        )
        try {
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            waitForText("25%", timeoutMillis = 5_000L)
            compose.onNodeWithText("25%", useUnmergedTree = true).assertIsDisplayed()

            val labels = listOf(
                R.string.phase_fetch,
                R.string.phase_check,
                R.string.phase_send,
                R.string.phase_install,
                R.string.phase_configure,
                R.string.phase_verify,
            ).map(context::getString)
            labels.forEach { label ->
                compose.onNodeWithTag("install_phases")
                    .performScrollToNode(hasText(label))
                compose.onNodeWithText(label).assertIsDisplayed()
            }
            captureScreen("installation-six-phase-progress")
        } finally {
            scenario.close()
        }
    }

    @Test
    fun successfulInstallReplaysProgressButFailureIsImmediate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val successScenario = ActivityScenario.launch<DebugScenarioActivity>(
            Intent(context, DebugScenarioActivity::class.java)
                .putExtra(DebugScenarioActivity.EXTRA_SCENARIO, "success"),
        )
        try {
            successScenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            waitForText(context.getString(R.string.phase_fetch), timeoutMillis = 1_000L)
            compose.onNodeWithText(context.getString(R.string.result_success_title)).assertDoesNotExist()
            waitForText(context.getString(R.string.result_success_title), timeoutMillis = 5_000L)
            compose.onNodeWithText(context.getString(R.string.result_success_title)).assertIsDisplayed()
        } finally {
            successScenario.close()
        }

        val failureScenario = ActivityScenario.launch<DebugScenarioActivity>(
            Intent(context, DebugScenarioActivity::class.java)
                .putExtra(DebugScenarioActivity.EXTRA_SCENARIO, "failed"),
        )
        try {
            failureScenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            waitForText(context.getString(R.string.result_failure_title), timeoutMillis = 1_000L)
        } finally {
            failureScenario.close()
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

    private fun waitForText(text: String, timeoutMillis: Long) {
        compose.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching {
                compose.onAllNodesWithText(text, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
    }

    private fun captureDeviceScreen(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "screencap -p /data/local/tmp/03helper-$name.png",
            ),
        ).use { output ->
            output.readBytes()
        }
    }

    private fun dialogText(text: String) = compose.onNode(
        hasText(text) and hasAnyAncestor(isDialog()),
        useUnmergedTree = true,
    )

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
