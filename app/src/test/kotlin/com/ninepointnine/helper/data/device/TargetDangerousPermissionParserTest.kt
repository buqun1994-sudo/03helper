package com.ninepointnine.helper.data.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetDangerousPermissionParserTest {
    @Test
    fun `Android 9 dangerous permission table is parsed as target capability only`() {
        assertEquals(
            setOf(
                "android.permission.READ_PHONE_STATE",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE",
            ),
            TargetDangerousPermissionParser.parse(
                """
                    Dangerous Permissions:

                    group:android.permission-group.PHONE
                      permission:android.permission.READ_PHONE_STATE

                    group:android.permission-group.LOCATION
                      permission:android.permission.ACCESS_FINE_LOCATION
                      permission:android.permission.ACCESS_COARSE_LOCATION

                    group:android.permission-group.STORAGE
                      permission:android.permission.READ_EXTERNAL_STORAGE
                      permission:android.permission.WRITE_EXTERNAL_STORAGE

                    ungrouped:
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `missing capability header is not an empty permission table`() {
        assertNull(TargetDangerousPermissionParser.parse("permission:android.permission.CAMERA"))
    }
}
