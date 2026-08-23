package com.ninepointnine.helper.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ninepointnine.helper.MainActivity
import com.ninepointnine.helper.debug.DebugScenarioActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstallAppActivitySmokeTest {
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
