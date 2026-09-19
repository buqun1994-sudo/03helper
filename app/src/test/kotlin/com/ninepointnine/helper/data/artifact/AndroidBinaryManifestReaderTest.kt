package com.ninepointnine.helper.data.artifact

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidBinaryManifestReaderTest {
    @Test
    fun `raw parser never adds compatibility permissions`() {
        val metadata = AndroidBinaryManifestReader.read(
            Base64.getDecoder().decode(LEGACY_MANIFEST_BASE64),
        )

        requireNotNull(metadata)
        assertEquals("com.example.raw", metadata.packageName)
        assertEquals(
            setOf(
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.CAMERA",
            ),
            metadata.declarations.requestedPermissions,
        )
        assertNull(metadata.declarations.runtimeGrantPermissions)
        assertEquals(
            setOf(
                com.ninepointnine.helper.domain.device.ApkServiceDeclaration(
                    componentName = "com.example.raw/com.example.raw.AccessService",
                    permission = "android.permission.BIND_ACCESSIBILITY_SERVICE",
                ),
            ),
            metadata.declarations.services,
        )
    }

    @Test
    fun `raw parser rejects missing and oversized manifests`() {
        assertNull(AndroidBinaryManifestReader.read(byteArrayOf()))
        assertNull(AndroidBinaryManifestReader.read(ByteArray(MAX_APK_MANIFEST_BYTES + 1)))
    }

    private companion object {
        // Compiled with aapt2 from a targetSdk=3 manifest that declares WRITE_EXTERNAL_STORAGE only.
        // Android 9 PackageManager adds READ_EXTERNAL_STORAGE for compatibility; this raw parser must not.
        const val LEGACY_MANIFEST_BASE64 = "AwAIAMwGAAABABwAGAQAABkAAAAAAAAAAAAAAIAAAAAAAAAAAAAAAA4AAAAaAAAAMgAAAFAAAAB0AAAAmgAAANAAAADwAAAA+AAAAAIBAAAUAQAAcgEAAKgBAAD+AQAAGAIAADoCAACSAgAApgIAALgCAADsAgAAIAMAADIDAABUAwAAhAMAAAUAbABhAGIAZQBsAAAABABuAGEAbQBlAAAACgBwAGUAcgBtAGkAcwBzAGkAbwBuAAAADQBtAGkAbgBTAGQAawBWAGUAcgBzAGkAbwBuAAAAEAB0AGEAcgBnAGUAdABTAGQAawBWAGUAcgBzAGkAbwBuAAAAEQBjAG8AbQBwAGkAbABlAFMAZABrAFYAZQByAHMAaQBvAG4AAAAZAGMAbwBtAHAAaQBsAGUAUwBkAGsAVgBlAHIAcwBpAG8AbgBDAG8AZABlAG4AYQBtAGUAAAAOAC4AQQBjAGMAZQBzAHMAUwBlAHIAdgBpAGMAZQAAAAIAMQA2AAAAAwBSAGEAdwAAAAcAYQBuAGQAcgBvAGkAZAAAAC0AYQBuAGQAcgBvAGkAZAAuAHAAZQByAG0AaQBzAHMAaQBvAG4ALgBCAEkATgBEAF8AQQBDAEMARQBTAFMASQBCAEkATABJAFQAWQBfAFMARQBSAFYASQBDAEUAAAAZAGEAbgBkAHIAbwBpAGQALgBwAGUAcgBtAGkAcwBzAGkAbwBuAC4AQwBBAE0ARQBSAEEAAAApAGEAbgBkAHIAbwBpAGQALgBwAGUAcgBtAGkAcwBzAGkAbwBuAC4AVwBSAEkAVABFAF8ARQBYAFQARQBSAE4AQQBMAF8AUwBUAE8AUgBBAEcARQAAAAsAYQBwAHAAbABpAGMAYQB0AGkAbwBuAAAADwBjAG8AbQAuAGUAeABhAG0AcABsAGUALgByAGEAdwAAACoAaAB0AHQAcAA6AC8ALwBzAGMAaABlAG0AYQBzAC4AYQBuAGQAcgBvAGkAZAAuAGMAbwBtAC8AYQBwAGsALwByAGUAcwAvAGEAbgBkAHIAbwBpAGQAAAAIAG0AYQBuAGkAZgBlAHMAdAAAAAcAcABhAGMAawBhAGcAZQAAABgAcABsAGEAdABmAG8AcgBtAEIAdQBpAGwAZABWAGUAcgBzAGkAbwBuAEMAbwBkAGUAAAAYAHAAbABhAHQAZgBvAHIAbQBCAHUAaQBsAGQAVgBlAHIAcwBpAG8AbgBOAGEAbQBlAAAABwBzAGUAcgB2AGkAYwBlAAAADwB1AHMAZQBzAC0AcABlAHIAbQBpAHMAcwBpAG8AbgAAABYAdQBzAGUAcwAtAHAAZQByAG0AaQBzAHMAaQBvAG4ALQBzAGQAawAtADIAMwAAAAgAdQBzAGUAcwAtAHMAZABrAAAAgAEIACQAAAABAAEBAwABAQYAAQEMAgEBcAIBAXIFAQFzBQEBAAEQABgAAAABAAAA/////woAAAAQAAAAAgEQAIgAAAABAAAA//////////8RAAAAFAAUAAUAAAAAAAAAEAAAAAUAAAD/////CAAAECQAAAAQAAAABgAAAAgAAAAIAAADCAAAAP////8SAAAADwAAAAgAAAMPAAAA/////xMAAAD/////CAAAECQAAAD/////FAAAAP////8IAAAQEAAAAAIBEABMAAAAAQAAAP//////////GAAAABQAFAACAAAAAAAAABAAAAADAAAA/////wgAABABAAAAEAAAAAQAAAD/////CAAAEAMAAAADARAAGAAAAAEAAAD//////////xgAAAACARAAOAAAAAEAAAD//////////xYAAAAUABQAAQAAAAAAAAAQAAAAAQAAAA0AAAAIAAADDQAAAAMBEAAYAAAAAQAAAP//////////FgAAAAIBEAA4AAAAAQAAAP//////////FwAAABQAFAABAAAAAAAAABAAAAABAAAADAAAAAgAAAMMAAAAAwEQABgAAAABAAAA//////////8XAAAAAgEQADgAAAABAAAA//////////8OAAAAFAAUAAEAAAAAAAAAEAAAAAAAAAAJAAAACAAAAwkAAAACARAATAAAAAEAAAD//////////xUAAAAUABQAAgAAAAAAAAAQAAAAAQAAAAcAAAAIAAADBwAAABAAAAACAAAACwAAAAgAAAMLAAAAAwEQABgAAAABAAAA//////////8VAAAAAwEQABgAAAABAAAA//////////8OAAAAAwEQABgAAAABAAAA//////////8RAAAAAQEQABgAAAABAAAA/////woAAAAQAAAA"
    }
}
