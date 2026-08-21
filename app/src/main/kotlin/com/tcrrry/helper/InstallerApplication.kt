package com.tcrrry.helper

import android.app.Application
import com.tcrrry.helper.application.InstallerRuntime
import com.tcrrry.helper.application.ProductionInstallerRuntimeFactory

/** Process owner for the single installation runtime; activities may be recreated safely. */
class InstallerApplication : Application() {
    val installerRuntime: InstallerRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ProductionInstallerRuntimeFactory.create(this)
    }
}
