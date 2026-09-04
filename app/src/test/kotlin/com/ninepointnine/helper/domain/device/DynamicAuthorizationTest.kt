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
    fun `installed declarations compile runtime appop and service requirements`() {
        val packageName = "com.example.player"

        val requirements = DeclaredApplicationAuthorizationPlanFactory.create(
            packageName,
            ApkDeclarationMetadata(
                requestedPermissions = setOf(
                    "android.permission.CAMERA",
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.SYSTEM_ALERT_WINDOW",
                    "android.permission.PACKAGE_USAGE_STATS",
                    "android.permission.WRITE_SETTINGS",
                ),
                runtimeGrantPermissions = setOf(
                    "android.permission.CAMERA",
                    "android.permission.READ_EXTERNAL_STORAGE",
                ),
                services = setOf(
                    ApkServiceDeclaration(
                        "$packageName/com.example.player.AccessibilityService",
                        "android.permission.BIND_ACCESSIBILITY_SERVICE",
                    ),
                    ApkServiceDeclaration(
                        "$packageName/com.example.player.NotificationListener",
                        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
                    ),
                ),
            ),
        ).associateBy { it.declaration }

        assertEquals(
            listOf(DeclaredApplicationAuthorizationAction.GrantRuntimePermission("android.permission.CAMERA")),
            requirements.getValue("android.permission.CAMERA").actions,
        )
        assertEquals(
            listOf(
                DeclaredApplicationAuthorizationAction.GrantRuntimePermission(
                    "android.permission.READ_EXTERNAL_STORAGE",
                ),
                DeclaredApplicationAuthorizationAction.AllowAppOp(ManagedAppOp.READ_EXTERNAL_STORAGE),
            ),
            requirements.getValue("android.permission.READ_EXTERNAL_STORAGE").actions,
        )
        assertEquals(
            listOf(DeclaredApplicationAuthorizationAction.AllowAppOp(ManagedAppOp.SYSTEM_ALERT_WINDOW)),
            requirements.getValue("android.permission.SYSTEM_ALERT_WINDOW").actions,
        )
        assertEquals(
            listOf(DeclaredApplicationAuthorizationAction.AllowAppOp(ManagedAppOp.GET_USAGE_STATS)),
            requirements.getValue("android.permission.PACKAGE_USAGE_STATS").actions,
        )
        assertEquals(
            listOf(DeclaredApplicationAuthorizationAction.AllowAppOp(ManagedAppOp.WRITE_SETTINGS)),
            requirements.getValue("android.permission.WRITE_SETTINGS").actions,
        )
        assertEquals(
            listOf(
                DeclaredApplicationAuthorizationAction.AppendSecureComponent(
                    ManagedSecureComponentList.ENABLED_ACCESSIBILITY_SERVICES,
                    "$packageName/com.example.player.AccessibilityService",
                ),
                DeclaredApplicationAuthorizationAction.EnableSecureFlag(ManagedSecureFlag.ACCESSIBILITY_ENABLED),
            ),
            requirements.getValue("$packageName/com.example.player.AccessibilityService").actions,
        )
    }

    @Test
    fun `normal and signature permissions remain visible without generating shell writes`() {
        val requirements = DeclaredApplicationAuthorizationPlanFactory.create(
            "com.example.player",
            ApkDeclarationMetadata(
                requestedPermissions = setOf(
                    "android.permission.INTERNET",
                    "android.permission.FOREGROUND_SERVICE",
                    "com.example.signature.CONTROL",
                    "android.permission.CAMERA",
                ),
                runtimeGrantPermissions = setOf("android.permission.CAMERA"),
            ),
        ).associateBy { it.declaration }

        listOf(
            "android.permission.INTERNET",
            "android.permission.FOREGROUND_SERVICE",
            "com.example.signature.CONTROL",
        ).forEach { permission ->
            val requirement = requirements.getValue(permission)
            assertEquals(ApplicationAuthorizationRequirementKind.DECLARED_PERMISSION, requirement.kind)
            assertEquals(false, requirement.automaticallyActionable)
            assertEquals(
                listOf(DeclaredApplicationAuthorizationAction.InspectPermission(permission)),
                requirement.actions,
            )
        }
        assertEquals(
            listOf(DeclaredApplicationAuthorizationAction.GrantRuntimePermission("android.permission.CAMERA")),
            requirements.getValue("android.permission.CAMERA").actions,
        )
    }

    @Test
    fun `installed declaration authorization ignores services owned by another package`() {
        val requirements = DeclaredApplicationAuthorizationPlanFactory.create(
            "com.example.player",
            ApkDeclarationMetadata(
                services = setOf(
                    ApkServiceDeclaration(
                        "com.attacker.player/com.attacker.player.AccessibilityService",
                        "android.permission.BIND_ACCESSIBILITY_SERVICE",
                    ),
                ),
            ),
        )

        assertTrue(requirements.isEmpty())
    }

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
    fun `declared service uses actual application id while retaining base namespace`() {
        val packageName = "com.ninepointnine.desktop.test"
        val declaredService = ApkServiceDeclaration(
            componentName = "$packageName/com.ninepointnine.desktop.debug.NavigationDemoAccessibilityService",
            permission = "android.permission.BIND_ACCESSIBILITY_SERVICE",
        )

        val ready = AuthorizationPlanFactory.createForComponents(
            components = listOf(
                ManagedComponent(
                    componentId = AuthorizationPlanFactory.DESKTOP_COMPONENT_ID,
                    packageName = packageName,
                    order = 0,
                ),
            ),
            declaredServicesByComponent = mapOf(
                AuthorizationPlanFactory.DESKTOP_COMPONENT_ID to setOf(declaredService),
            ),
        ) as AuthorizationPlanBuildResult.Ready

        val action = ready.plan.actions
            .filterIsInstance<AuthorizationAction.AppendSecureComponent>()
            .single()
        assertEquals(declaredService.componentName, action.targetComponent)
        assertEquals(
            declaredService.componentName,
            AuthorizationPlanFactory.requiredRuntimeService(ready.plan, ready.plan.components.single()),
        )
        assertTrue(AuthorizationPlanFactory.validate(ready.plan))
        assertEquals(
            null,
            AuthorizationDeclarationValidator.validateDeclarations(
                ready.plan,
                mapOf(
                    AuthorizationPlanFactory.DESKTOP_COMPONENT_ID to ApkDeclarationMetadata(
                        requestedPermissions = setOf(
                            "android.permission.SYSTEM_ALERT_WINDOW",
                            "android.permission.REQUEST_INSTALL_PACKAGES",
                        ),
                        services = setOf(declaredService),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `cross package declared service is rejected before an authorization plan is built`() {
        val result = AuthorizationPlanFactory.createForComponents(
            components = listOf(
                ManagedComponent(
                    componentId = AuthorizationPlanFactory.DESKTOP_COMPONENT_ID,
                    packageName = "com.ninepointnine.desktop.test",
                    order = 0,
                ),
            ),
            declaredServicesByComponent = mapOf(
                AuthorizationPlanFactory.DESKTOP_COMPONENT_ID to setOf(
                    ApkServiceDeclaration(
                        componentName = "com.attacker.desktop/com.attacker.desktop.Service",
                        permission = "android.permission.BIND_ACCESSIBILITY_SERVICE",
                    ),
                ),
            ),
        )

        assertEquals(
            AuthorizationPlanBuildResult.Rejected("authorization_service_component_mismatch"),
            result,
        )
    }

    @Test
    fun `multiple same purpose services are rejected instead of selecting one by sort order`() {
        val packageName = "com.ninepointnine.desktop.test"
        val services = setOf(
            ApkServiceDeclaration(
                "$packageName/com.ninepointnine.desktop.debug.NavigationDemoAccessibilityService",
                "android.permission.BIND_ACCESSIBILITY_SERVICE",
            ),
            ApkServiceDeclaration(
                "$packageName/com.ninepointnine.desktop.debug.SecondAccessibilityService",
                "android.permission.BIND_ACCESSIBILITY_SERVICE",
            ),
        )

        val result = AuthorizationPlanFactory.createForComponents(
            components = listOf(
                ManagedComponent(
                    componentId = AuthorizationPlanFactory.DESKTOP_COMPONENT_ID,
                    packageName = packageName,
                    order = 0,
                ),
            ),
            declaredServicesByComponent = mapOf("desktop" to services),
        )

        assertEquals(
            AuthorizationPlanBuildResult.Rejected("authorization_service_ambiguous"),
            result,
        )
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
