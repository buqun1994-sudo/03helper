package com.ninepointnine.helper.data.artifact

import com.ninepointnine.helper.application.artifact.UserSelectedApkPreparationResult
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.device.ApkDeclarationMetadata
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUserSelectedApkPreparerTest {
    @Test
    fun `valid content apk is copied verified and removed only from private cache`() = runBlocking {
        val root = Files.createTempDirectory("local-apk-ready").toFile()
        val workspace = root.resolve("selected")
        val bytes = "verified-apk".toByteArray()
        val certificates = setOf("33".repeat(32), "44".repeat(32))
        val declarations = ApkDeclarationMetadata(
            requestedPermissions = setOf("android.permission.ACCESS_FINE_LOCATION"),
            runtimeGrantPermissions = setOf("android.permission.ACCESS_FINE_LOCATION"),
        )
        val preparer = AndroidUserSelectedApkPreparer(
            source = byteSource(bytes),
            metadataReader = ApkMetadataReader { apk ->
                assertTrue(apk.readBytes().contentEquals(bytes))
                ApkMetadata(
                    packageName = "com.example.maps",
                    version = ArtifactVersion("2.4.1", 24L),
                    certificateSha256s = certificates,
                    declarations = declarations,
                    minAndroidSdk = 26,
                    displayName = "Maps",
                )
            },
            workspace = workspace,
        )

        val result = preparer.prepare("content://phone/maps.apk", targetAndroidSdk = 28)

        assertTrue(result is UserSelectedApkPreparationResult.Ready)
        val ready = result as UserSelectedApkPreparationResult.Ready
        assertEquals(ArtifactSourceKind.USER_SELECTED_APK, ready.artifact.sourceKind)
        assertEquals("com.example.maps", ready.artifact.manifest.packageName)
        assertTrue(ready.artifact.manifest.localOnly)
        assertEquals(certificates, ready.artifact.manifest.certificateSha256s)
        assertEquals(certificates, ready.verification.certificateSha256s)
        assertEquals(declarations, ready.artifact.declarations)
        assertTrue(checkNotNull(ready.artifact.finalApk).isFile)
        assertTrue(ready.verification.userSelected)

        preparer.clear(ready.artifact)

        assertFalse(workspace.exists())
        assertTrue(root.isDirectory)
        root.deleteRecursively()
        Unit
    }

    @Test
    fun `standalone split apk is rejected and its private copy is removed`() = runBlocking {
        val root = Files.createTempDirectory("local-apk-split").toFile()
        val workspace = root.resolve("selected")
        val preparer = AndroidUserSelectedApkPreparer(
            source = byteSource("split-apk".toByteArray()),
            metadataReader = ApkMetadataReader {
                ApkMetadata(
                    packageName = "com.example.maps",
                    version = ArtifactVersion("2.4.1", 24L),
                    certificateSha256s = setOf("33".repeat(32)),
                    minAndroidSdk = 26,
                    splitName = "config.arm64_v8a",
                )
            },
            workspace = workspace,
        )

        val result = preparer.prepare("content://phone/config.apk", targetAndroidSdk = 28)

        assertEquals(UserSelectedApkPreparationResult.Failed("local_apk_split_not_supported"), result)
        assertFalse(workspace.exists())
        root.deleteRecursively()
        Unit
    }

    @Test
    fun `metadata rejection removes the partial private copy`() = runBlocking {
        val root = Files.createTempDirectory("local-apk-invalid").toFile()
        val workspace = root.resolve("selected")
        val preparer = AndroidUserSelectedApkPreparer(
            source = byteSource("not-an-apk".toByteArray()),
            metadataReader = ApkMetadataReader { null },
            workspace = workspace,
        )

        val result = preparer.prepare("content://phone/not-an-apk.bin", targetAndroidSdk = 28)

        assertEquals(
            UserSelectedApkPreparationResult.Failed("local_apk_metadata_unreadable"),
            result,
        )
        assertFalse(workspace.exists())
        root.deleteRecursively()
        Unit
    }

    @Test
    fun `declared file larger than one gibibyte is rejected before it is read`() = runBlocking {
        val root = Files.createTempDirectory("local-apk-too-large").toFile()
        val workspace = root.resolve("selected")
        var opened = false
        val preparer = AndroidUserSelectedApkPreparer(
            source = object : UserSelectedApkSource {
                override fun accepts(uri: String): Boolean = true

                override fun declaredLength(uri: String): Long = (1L shl 30) + 1L

                override fun open(uri: String): InputStream {
                    opened = true
                    return ByteArrayInputStream(byteArrayOf(1))
                }
            },
            metadataReader = ApkMetadataReader { error("metadata must not be read") },
            workspace = workspace,
        )

        val result = preparer.prepare("content://phone/huge.apk", targetAndroidSdk = 28)

        assertEquals(UserSelectedApkPreparationResult.Failed("local_apk_size_invalid"), result)
        assertFalse(opened)
        assertFalse(workspace.exists())
        root.deleteRecursively()
        Unit
    }

    private fun byteSource(bytes: ByteArray): UserSelectedApkSource = object : UserSelectedApkSource {
        override fun accepts(uri: String): Boolean = uri.startsWith("content://")

        override fun declaredLength(uri: String): Long = bytes.size.toLong()

        override fun open(uri: String): InputStream = ByteArrayInputStream(bytes)
    }
}
