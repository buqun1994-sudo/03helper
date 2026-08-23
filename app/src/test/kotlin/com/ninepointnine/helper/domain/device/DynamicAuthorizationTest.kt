package com.ninepointnine.helper.domain.device

import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactSource
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.CompatibilityRange
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicAuthorizationTest {
    @Test
    fun `cloud setup compiles to typed actions and preserves sort order`() {
        val setup = AuthorizationSetupDeclaration(
            appOps = setOf(ManagedAppOp.SYSTEM_ALERT_WINDOW),
            runtimePermissions = setOf(ManagedRuntimePermission.READ_EXTERNAL_STORAGE),
            secureComponents = setOf(
                AuthorizationSetupDeclaration.SecureComponent(
                    setting = ManagedSecureComponentList.ENABLED_ACCESSIBILITY_SERVICES,
                    targetComponent = "com.example.notes/com.example.notes.Service",
                ),
            ),
        )
        val manifests = listOf(
            manifest("notes", "com.example.notes", 20, setup),
            manifest("desktop", AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME, 10, null, required = true),
        )

        val ready = AuthorizationPlanFactory.createForManifests(manifests) as AuthorizationPlanBuildResult.Ready

        assertEquals(listOf("desktop", "notes"), ready.plan.components.map { it.componentId })
        assertEquals(7, ready.plan.actions.size)
        assertTrue(AuthorizationPlanFactory.validate(ready.plan))
    }

    @Test
    fun `server sort order also controls known component ids`() {
        val manifests = listOf(
            manifest("desktop", AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME, 30, null, required = true),
            manifest("lyrics", AuthorizationPlanFactory.LYRICS_PACKAGE_NAME, 10, null),
        )

        val ready = AuthorizationPlanFactory.createForManifests(manifests) as AuthorizationPlanBuildResult.Ready

        assertEquals(listOf("lyrics", "desktop"), ready.plan.components.map { it.componentId })
        assertTrue(AuthorizationPlanFactory.validate(ready.plan))
    }

    @Test
    fun `catalog component gate binds built in package and typed setup to the APK`() {
        val invalidSetup = ManagedComponent(
            componentId = "notes",
            packageName = "com.example.notes",
            setup = AuthorizationSetupDeclaration(
                secureComponents = setOf(
                    AuthorizationSetupDeclaration.SecureComponent(
                        setting = ManagedSecureComponentList.ENABLED_ACCESSIBILITY_SERVICES,
                        targetComponent = "com.attacker.notes/com.attacker.notes.Service",
                    ),
                ),
            ),
        )

        assertTrue(!AuthorizationPlanFactory.validateComponent(invalidSetup))
        assertTrue(
            !AuthorizationPlanFactory.validateComponent(
                ManagedComponent(
                    componentId = AuthorizationPlanFactory.DESKTOP_COMPONENT_ID,
                    packageName = "com.attacker.desktop",
                ),
            ),
        )
    }

    @Test
    fun `android 9 capacity is checked before writes`() {
        val plan = AuthorizationPlanFactory.createForManifests(
            listOf(
                manifest(
                    "desktop",
                    AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME,
                    10,
                    AuthorizationSetupDeclaration(
                        secureComponents = setOf(
                            AuthorizationSetupDeclaration.SecureComponent(
                                ManagedSecureComponentList.ENABLED_ACCESSIBILITY_SERVICES,
                                "com.tcrrry.desktop/com.tcrrry.desktop.Service",
                            ),
                        ),
                    ),
                    required = true,
                ),
            ),
        ) as AuthorizationPlanBuildResult.Ready

        val reason = AuthorizationCapacityPolicy.validate(
            plan = plan.plan,
            existingValues = mapOf(
                ManagedSecureComponentList.ENABLED_ACCESSIBILITY_SERVICES to "a:b:c",
            ),
            // Keep this fixture below the byte ceiling so the assertion isolates
            // the Android 9 entry-count guard.
            limits = AuthorizationCapacityLimits(maxEntriesPerSecureList = 3, maxSerializedBytesPerSecureList = 4 * 1024),
        )

        assertEquals("authorization_capacity_entries_exceeded", reason)
    }

    @Test
    fun `android 9 serialized byte capacity is checked independently`() {
        val plan = AuthorizationPlanFactory.createForManifests(
            listOf(
                manifest(
                    "desktop",
                    AuthorizationPlanFactory.DESKTOP_PACKAGE_NAME,
                    10,
                    AuthorizationSetupDeclaration(
                        secureComponents = setOf(
                            AuthorizationSetupDeclaration.SecureComponent(
                                ManagedSecureComponentList.ENABLED_ACCESSIBILITY_SERVICES,
                                "com.tcrrry.desktop/com.tcrrry.desktop.Service",
                            ),
                        ),
                    ),
                    required = true,
                ),
            ),
        ) as AuthorizationPlanBuildResult.Ready

        val reason = AuthorizationCapacityPolicy.validate(
            plan = plan.plan,
            existingValues = mapOf(
                ManagedSecureComponentList.ENABLED_ACCESSIBILITY_SERVICES to "a",
            ),
            limits = AuthorizationCapacityLimits(maxEntriesPerSecureList = 32, maxSerializedBytesPerSecureList = 8),
        )

        assertEquals("authorization_capacity_bytes_exceeded", reason)
    }

    private fun manifest(
        componentId: String,
        packageName: String,
        sortOrder: Int,
        setup: AuthorizationSetupDeclaration?,
        required: Boolean = false,
    ): ArtifactManifest = ArtifactManifest(
        schemaVersion = 1,
        componentId = componentId,
        displayName = componentId,
        required = required,
        version = ArtifactVersion("1.0", 1),
        compatibility = CompatibilityRange(28),
        archiveFileName = "$componentId.zip",
        archiveSizeBytes = 1,
        archiveSha256 = "aa".repeat(32),
        apkEntryName = "$componentId.apk",
        apkSizeBytes = 1,
        apkSha256 = "bb".repeat(32),
        packageName = packageName,
        apkVersion = ArtifactVersion("1.0", 1),
        certificateSha256 = "cc".repeat(32),
        sources = listOf(
            ArtifactSource(ArtifactSourceKind.LANZOU_SHARE, "https://wwatl.lanzouw.com/i$componentId"),
        ),
        deviceSetup = setup,
        sortOrder = sortOrder,
    )
}
