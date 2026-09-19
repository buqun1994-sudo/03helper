package com.ninepointnine.helper.data.artifact

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidApkMetadataReaderTest {
    @Test
    fun rawManifestBytesPreserveDeclarations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manifestBytes = ZipFile(context.applicationInfo.sourceDir).use { source ->
            source.getInputStream(checkNotNull(source.getEntry("AndroidManifest.xml"))).use { it.readBytes() }
        }

        val metadata = AndroidApkMetadataReader(context).readManifest(manifestBytes)

        assertEquals(context.packageName, metadata?.packageName)
        assertTrue(metadata?.declarations?.requestedPermissions?.contains("android.permission.INTERNET") == true)
    }
}
