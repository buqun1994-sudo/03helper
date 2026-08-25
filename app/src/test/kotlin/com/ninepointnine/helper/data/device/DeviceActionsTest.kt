package com.ninepointnine.helper.data.device

import com.ninepointnine.helper.application.artifact.PreparedArtifact
import com.ninepointnine.helper.application.device.DeviceInstallationCoordinator
import com.ninepointnine.helper.application.session.InstallationSessionEventPort
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.ArtifactSource
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.artifact.CompatibilityRange
import com.ninepointnine.helper.domain.device.AdbCommandGateway
import com.ninepointnine.helper.domain.device.ApkDeclarationMetadata
import com.ninepointnine.helper.domain.device.ApkServiceDeclaration
import com.ninepointnine.helper.domain.device.AuthorizationAction
import com.ninepointnine.helper.domain.device.AuthorizationActionEvidence
import com.ninepointnine.helper.domain.device.AuthorizationPlan
import com.ninepointnine.helper.domain.device.AuthorizationPlanBuildResult
import com.ninepointnine.helper.domain.device.AuthorizationPlanFactory
import com.ninepointnine.helper.domain.device.AuthorizationValueState
import com.ninepointnine.helper.domain.device.ConnectedDevice
import com.ninepointnine.helper.domain.device.ManagedComponent
import com.ninepointnine.helper.domain.device.DeviceActionConnectionLease
import com.ninepointnine.helper.domain.device.DeviceAvailabilityEvidence
import com.ninepointnine.helper.domain.device.DeviceCapability
import com.ninepointnine.helper.domain.device.DeviceConnectionCheck
import com.ninepointnine.helper.domain.device.DeviceEndpoint
import com.ninepointnine.helper.domain.device.DeviceIdentity
import com.ninepointnine.helper.domain.device.DeviceInstallResult
import com.ninepointnine.helper.domain.device.DeviceShortcut
import com.ninepointnine.helper.domain.device.DeviceShortcutFailureStage
import com.ninepointnine.helper.domain.device.DeviceShortcutResult
import com.ninepointnine.helper.domain.device.InstalledArtifactEvidence
import com.ninepointnine.helper.domain.session.InstallationSessionEvent
import dadb.AdbShellResponse
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceActionsTest {
    @Test
    fun `desktop is the only core and optional components extend the fixed plan`() {
        val ready = AuthorizationPlanFactory.createForComponents(
            listOf(
                AuthorizationPlanFactory.allManagedComponents().first { it.componentId == "lyrics" },
                AuthorizationPlanFactory.allManagedComponents().first { it.componentId == "desktop" },
                AuthorizationPlanFactory.allManagedComponents().first { it.componentId == "file-manager" },
            ),
        ) as AuthorizationPlanBuildResult.Ready

        assertTrue(AuthorizationPlanFactory.validate(ready.plan))
        assertEquals(listOf("desktop", "lyrics", "file-manager"), ready.plan.components.map { it.componentId })
        assertEquals(
            AuthorizationPlanFactory.DESKTOP_MAIN_ACTIVITY,
            AuthorizationPlanFactory.fixedLaunchComponent(
                ready.plan.components.first { it.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID },
            ),
        )
        assertEquals(
            listOf(
                "desktop-overlay-v1",
                "desktop-install-packages-v1",
                "desktop-accessibility-master-v1",
                "desktop-accessibility-service-v1",
                "lyrics-overlay-v1",
                "lyrics-notification-listener-v1",
                "lyrics-accessibility-service-v1",
                "file-manager-read-permission-v1",
                "file-manager-read-appop-v1",
                "file-manager-write-permission-v1",
                "file-manager-write-appop-v1",
                "file-manager-install-packages-v1",
            ),
            ready.plan.actions.map { it.id },
        )

        val rejected = AuthorizationPlanFactory.createForComponents(
            listOf(AuthorizationPlanFactory.allManagedComponents().first { it.componentId == "lyrics" }),
        ) as AuthorizationPlanBuildResult.Rejected
        assertEquals("authorization_desktop_missing", rejected.reasonCode)
    }

    @Test
    fun `versioned authorization executor is one shell invocation with validated selection arguments`() {
        val command = CombinedAuthorizationCommand.build(setOf("desktop", "lyrics"))

        assertEquals(1, Regex("sh -c").findAll(command).count())
        assertTrue(command.endsWith("03helper desktop lyrics"))
        assertTrue(command.contains("ensure_appop com.tcrrry.desktop SYSTEM_ALERT_WINDOW"))
        assertTrue(command.contains("ensure_notification_listener"))
        assertTrue(command.contains("wait_for_service_bound"))
        assertTrue(command.contains("service_bound"))
        assertTrue(command.contains("while IFS= read -r line"))
        assertTrue(
            command.contains(
                "wait_for_service_bound \"com.tcrrry.desktop/.debug.NavigationDemoAccessibilityService\"",
            ),
        )
        assertFalse(command.contains("awk"))
        assertTrue(command.contains("No operations."))
        assertFalse(
            command.substringAfter("ensure_notification_listener()")
                .substringBefore("ensure_accessibility_service()")
                .contains("wait_for_service_bound"),
        )
        assertFalse(command.contains("query-services"))
        assertTrue(command.contains("am start -n"))
        assertTrue(command.contains("com.tcrrry.desktop/.MainActivity"))
        assertFalse(command.contains("resolve-activity"))
        assertTrue(command.contains("com.tcrrry.desktop/.debug.NavigationDemoAccessibilityService"))
        assertFalse(command.contains("am start -n com.tcrrry.desktoplyrics"))
        assertFalse(command.contains("am start -n org.fossify.filemanager.debug"))
        assertTrue(
            command.contains(
                "emit \"LAUNCH|desktop|OK|${'$'}process_state|${'$'}service_state\"",
            ),
        )
        assertTrue(command.contains("emit \"DONE|OK\""))
        assertFalse(command.contains("emit LAUNCH desktop OK"))
        assertFalse(command.contains("emit DONE OK"))
    }

    @Test
    fun `staging desktop plan carries its verified package into launch probes`() {
        val desktop = AuthorizationPlanFactory.allManagedComponents()
            .first { it.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID }
            .copy(packageName = "com.ninepointnine.desktop")
        val ready = AuthorizationPlanFactory.createForComponents(listOf(desktop)) as AuthorizationPlanBuildResult.Ready

        val command = CombinedAuthorizationCommand.build(ready.plan)

        assertTrue(command.contains("--dynamic"))
        assertTrue(command.contains("--desktop-package=com.ninepointnine.desktop"))
        assertTrue(command.contains("launch_component=\"${'$'}desktop_package/.MainActivity\""))
        assertTrue(command.contains("wait_for_service_bound \"${'$'}desktop_package/.debug.NavigationDemoAccessibilityService\""))
    }

    @Test
    fun `repair command never launches an application and still requires terminal evidence`() {
        val command = CombinedAuthorizationCommand.build(setOf("desktop", "lyrics"), repairOnly = true)

        assertTrue(command.endsWith("03helper --repair desktop lyrics"))
        assertTrue(command.contains("repair_only=0"))
        assertTrue(command.contains("if [ \"${'$'}{1:-}\" = --repair ]"))
        val repairExit = command.indexOf("if [ \"${'$'}repair_only\" -eq 1 ]; then emit \"DONE|OK\"; exit 0; fi")
        val launchPath = command.indexOf("launch_component=")
        assertTrue(repairExit >= 0)
        assertTrue(launchPath > repairExit)
    }

    @Test
    fun `repair response rejects an unexpected launch marker`() {
        val plan = AuthorizationPlanFactory.createForComponents(
            AuthorizationPlanFactory.allManagedComponents(),
        ) as AuthorizationPlanBuildResult.Ready
        val authorizationLines = validAuthorizationEvidence(plan.plan).joinToString("\n") { evidence ->
            listOf(
                CombinedAuthorizationCommand.MARKER,
                "AUTH",
                evidence.componentId,
                evidence.actionId,
                evidence.before.name,
                if (evidence.writeApplied) "1" else "0",
                evidence.after.name,
                evidence.preservedEntryCount?.toString() ?: "-",
            ).joinToString("|")
        }
        val response = CombinedAuthorizationResponseParser.parseRepair(
            AdbShellResponse(
                "${authorizationLines}\n03HELPER|LAUNCH|desktop|OK|RUNNING|BOUND\n03HELPER|DONE|OK",
                "",
                0,
            ),
            plan.plan,
        )

        assertTrue(response is com.ninepointnine.helper.domain.device.MaintenanceDeviceResult.Failed)
        assertEquals("maintenance_unexpected_launch", (response as com.ninepointnine.helper.domain.device.MaintenanceDeviceResult.Failed).failure.reasonCode)
    }

    @Test
    fun `combined response requires pipe-delimited terminal markers`() {
        val plan = AuthorizationPlanFactory.createForComponents(
            AuthorizationPlanFactory.allManagedComponents(),
        ) as AuthorizationPlanBuildResult.Ready
        val authorizationLines = validAuthorizationEvidence(plan.plan).joinToString("\n") { evidence ->
            listOf(
                CombinedAuthorizationCommand.MARKER,
                "AUTH",
                evidence.componentId,
                evidence.actionId,
                evidence.before.name,
                if (evidence.writeApplied) "1" else "0",
                evidence.after.name,
                evidence.preservedEntryCount?.toString() ?: "-",
            ).joinToString("|")
        }
        val output = """
            $authorizationLines
            03HELPER|LAUNCH|desktop|OK|RUNNING|BOUND
            03HELPER|DONE|OK
        """.trimIndent()

        val completed = CombinedAuthorizationResponseParser.parse(
            AdbShellResponse(output, "framework warning", 0),
            plan.plan,
        )
        assertTrue(completed is DeviceShortcutResult.Completed)

        val malformed = output
            .replace("03HELPER|LAUNCH|desktop|OK|RUNNING|BOUND", "03HELPER|LAUNCH desktop OK RUNNING BOUND")
            .replace("03HELPER|DONE|OK", "03HELPER|DONE OK")
        val rejected = CombinedAuthorizationResponseParser.parse(
            AdbShellResponse(malformed, "", 0),
            plan.plan,
        )
        assertTrue(rejected is DeviceShortcutResult.Failed)
        assertEquals(
            "combined_command_result_missing",
            (rejected as DeviceShortcutResult.Failed).failure.reasonCode,
        )
    }

    @Test
    fun `staging package identities use the same bounded authorization contract`() {
        val result = AuthorizationPlanFactory.createForComponents(
            listOf(
                ManagedComponent(
                    componentId = "desktop",
                    packageName = "com.ninepointnine.desktop",
                    order = 0,
                ),
            ),
        ) as AuthorizationPlanBuildResult.Ready

        assertTrue(AuthorizationPlanFactory.validate(result.plan))
        assertEquals(
            "com.ninepointnine.desktop/.MainActivity",
            AuthorizationPlanFactory.fixedLaunchComponent(result.plan.components.single()),
        )
        assertEquals(
            "com.ninepointnine.desktop/com.ninepointnine.desktop.debug.NavigationDemoAccessibilityService",
            AuthorizationPlanFactory.requiredRuntimeService(result.plan.components.single()),
        )
    }

    @Test
    fun `authorization plan result is keyed by exit code and structured markers rather than stderr`() {
        val gatewaySource = java.io.File(
            "src/main/kotlin/com/ninepointnine/helper/data/device/DadbCommandGateway.kt",
        ).readText()
        assertFalse(
            gatewaySource.contains("failure != null || response.exitCode != 0 || response.errorOutput.isNotBlank()"),
        )
        assertTrue(gatewaySource.contains("failure != null || response.exitCode != 0"))
    }

    @Test
    fun `app ops parser treats an untouched operation as default`() {
        assertEquals(
            AuthorizationValueState.DEFAULT,
            AppOpsResponseParser.parse("No operations.", "SYSTEM_ALERT_WINDOW"),
        )
    }

    @Test
    fun `package inventory parser accepts versioned package rows and ignores malformed rows`() {
        val output = """
            package:com.ninepointnine.desktop versionCode:42
            package:org.fossify.filemanager.debug versionCode:14 minSdk:26
            package:bad-package versionCode:9
            package:com.ninepointnine.desktop versionCode:not-a-number
            unrelated output
        """.trimIndent()

        assertEquals(
            listOf(
                PackageInventoryEntry("com.ninepointnine.desktop", 42L),
                PackageInventoryEntry("org.fossify.filemanager.debug", 14L),
            ),
            PackageInventoryParser.parseEntries(output),
        )
        assertEquals(
            setOf("com.ninepointnine.desktop", "org.fossify.filemanager.debug"),
            PackageInventoryParser.parse(output),
        )
    }

    @Test
    fun `uninstall accepts success with framework diagnostics but rejects failure markers`() {
        assertTrue(
            isUninstallAccepted(
                AdbShellResponse(
                    output = "Success\n",
                    errorOutput = "Warning: package manager is still settling",
                    exitCode = 0,
                ),
            ),
        )
        assertFalse(
            isUninstallAccepted(
                AdbShellResponse(
                    output = "Failure [not installed]",
                    errorOutput = "",
                    exitCode = 0,
                ),
            ),
        )
        assertTrue(
            isUninstallAccepted(
                AdbShellResponse(
                    output = "Warning: package manager completed",
                    errorOutput = "",
                    exitCode = 0,
                ),
            ),
        )
        assertTrue(
            isUninstallAccepted(
                AdbShellResponse(
                    output = "",
                    errorOutput = "",
                    exitCode = 0,
                ),
            ),
        )
        assertFalse(
            isUninstallAccepted(
                AdbShellResponse(
                    output = "Error: package manager rejected request",
                    errorOutput = "",
                    exitCode = 0,
                ),
            ),
        )
    }

    @Test
    fun `package presence probe distinguishes empty, exact and malformed inventory`() {
        assertEquals(
            false,
            parsePackagePresence("", "com.ninepointnine.desktop"),
        )
        assertEquals(
            true,
            parsePackagePresence("package:com.ninepointnine.desktop\n", "com.ninepointnine.desktop"),
        )
        assertEquals(
            false,
            parsePackagePresence("package:com.ninepointnine.desktoplyrics\n", "com.ninepointnine.desktop"),
        )
        assertEquals(
            null,
            parsePackagePresence("Warning: package manager busy", "com.ninepointnine.desktop"),
        )
    }

    @Test
    fun `coordinator installs the batch then invokes the versioned authorization plan once and launches only desktop`() {
        val lyricsFile = Files.createTempFile("lyrics", ".apk").toFile().apply { writeBytes(byteArrayOf(1)) }
        val desktopFile = Files.createTempFile("desktop", ".apk").toFile().apply { writeBytes(byteArrayOf(2)) }
        val artifacts = listOf(
            prepared("lyrics", "com.tcrrry.desktoplyrics", lyricsFile),
            prepared("desktop", "com.tcrrry.desktop", desktopFile),
        )
        val commandSelections = mutableListOf<Set<String>>()
        val events = mutableListOf<InstallationSessionEvent>()
        val gateway = object : AdbCommandGateway {
            override suspend fun install(artifacts: List<com.ninepointnine.helper.domain.device.InstallableArtifact>): DeviceInstallResult =
                DeviceInstallResult.Installed(artifacts.map(::installedEvidence))

            override suspend fun runShortcut(
                shortcut: DeviceShortcut,
                selectedComponentIds: Set<String>,
            ): DeviceShortcutResult {
                commandSelections += selectedComponentIds
                val installables = artifacts.map {
                    com.ninepointnine.helper.domain.device.InstallableArtifact(it.manifest, it.finalApk, it.declarations)
                }
                val plan = AuthorizationPlanFactory.create(installables) as AuthorizationPlanBuildResult.Ready
                return DeviceShortcutResult.Completed(
                    configuredComponentIds = selectedComponentIds,
                    skippedComponentIds = emptySet(),
                    authorizationEvidence = validAuthorizationEvidence(plan.plan),
                    availabilityEvidence = listOf(
                        com.ninepointnine.helper.domain.device.ManagedApplicationAvailabilityEvidence(
                            componentId = "desktop",
                            packageName = "com.tcrrry.desktop",
                            launchAttempted = true,
                            launcherResolved = true,
                            processRunning = true,
                            requiredServiceBound = true,
                        ),
                    ),
                )
            }
        }

        val result = kotlinx.coroutines.runBlocking {
            DeviceInstallationCoordinator(InstallationSessionEventPort { events += it })
                .execute(actionLease(gateway), artifacts)
        }

        assertEquals(com.ninepointnine.helper.application.device.DeviceInstallationExecutionResult.Completed, result)
        assertEquals(listOf(setOf("desktop"), setOf("desktop", "lyrics")), commandSelections)
        assertEquals(
            listOf(
                InstallationSessionEvent.InstallationStarted::class,
                InstallationSessionEvent.InstallationCompleted::class,
                InstallationSessionEvent.AuthorizationCompleted::class,
                InstallationSessionEvent.DeviceVerified::class,
            ),
            events.filter {
                it !is InstallationSessionEvent.ComponentProgressUpdated
            }.map { it::class },
        )
        assertTrue(events.any { it is InstallationSessionEvent.ComponentProgressUpdated })
        val availability = (events.filterIsInstance<InstallationSessionEvent.DeviceVerified>().last())
            .evidence.associateBy { it.componentId }
        assertFalse(availability.getValue("lyrics").launchAttempted)
        assertTrue(availability.getValue("desktop").launchAttempted)
        assertTrue(availability.getValue("desktop").processRunning)
        assertTrue(lyricsFile.exists())
        assertTrue(desktopFile.exists())
    }

    @Test
    fun `optional package missing from command result cannot report success`() {
        val lyricsFile = Files.createTempFile("lyrics-missing", ".apk").toFile().apply { writeBytes(byteArrayOf(1)) }
        val desktopFile = Files.createTempFile("desktop-missing", ".apk").toFile().apply { writeBytes(byteArrayOf(2)) }
        val artifacts = listOf(
            prepared("lyrics", "com.tcrrry.desktoplyrics", lyricsFile),
            prepared("desktop", "com.tcrrry.desktop", desktopFile),
        )
        val events = mutableListOf<InstallationSessionEvent>()
        val gateway = object : AdbCommandGateway {
            override suspend fun install(artifacts: List<com.ninepointnine.helper.domain.device.InstallableArtifact>): DeviceInstallResult =
                DeviceInstallResult.Installed(artifacts.map(::installedEvidence))

            override suspend fun runShortcut(
                shortcut: DeviceShortcut,
                selectedComponentIds: Set<String>,
            ): DeviceShortcutResult {
                val installables = artifacts.map {
                    com.ninepointnine.helper.domain.device.InstallableArtifact(it.manifest, it.finalApk, it.declarations)
                }
                val plan = AuthorizationPlanFactory.create(installables) as AuthorizationPlanBuildResult.Ready
                return DeviceShortcutResult.Completed(
                    configuredComponentIds = setOf("desktop"),
                    skippedComponentIds = setOf("lyrics"),
                    authorizationEvidence = validAuthorizationEvidence(plan.plan),
                    availabilityEvidence = listOf(
                        com.ninepointnine.helper.domain.device.ManagedApplicationAvailabilityEvidence(
                            "desktop", "com.tcrrry.desktop", true, true, true, true,
                        ),
                    ),
                )
            }
        }

        val result = kotlinx.coroutines.runBlocking {
            DeviceInstallationCoordinator(InstallationSessionEventPort { events += it })
                .execute(actionLease(gateway), artifacts)
        }

        assertEquals(com.ninepointnine.helper.application.device.DeviceInstallationExecutionResult.Completed, result)
        assertTrue(events.any {
            it is InstallationSessionEvent.ComponentFailed &&
                it.componentId == "lyrics" &&
                it.reasonCode == "shortcut_selected_component_missing"
        })
        assertTrue(lyricsFile.exists())
        assertTrue(desktopFile.exists())
    }

    @Test
    fun `authorization declaration failure is isolated per component`() {
        val lyricsFile = Files.createTempFile("lyrics-declaration", ".apk").toFile().apply { writeBytes(byteArrayOf(1)) }
        val desktopFile = Files.createTempFile("desktop-declaration", ".apk").toFile().apply { writeBytes(byteArrayOf(2)) }
        val artifacts = listOf(
            prepared(
                "lyrics",
                "com.tcrrry.desktoplyrics",
                lyricsFile,
                ApkDeclarationMetadata(requestedPermissions = setOf("android.permission.SYSTEM_ALERT_WINDOW")),
            ),
            prepared("desktop", "com.tcrrry.desktop", desktopFile),
        )
        val shortcutSelections = mutableListOf<Set<String>>()
        val events = mutableListOf<InstallationSessionEvent>()
        val gateway = object : AdbCommandGateway {
            override suspend fun install(artifacts: List<com.ninepointnine.helper.domain.device.InstallableArtifact>): DeviceInstallResult =
                DeviceInstallResult.Installed(artifacts.map(::installedEvidence))

            override suspend fun runShortcut(
                shortcut: DeviceShortcut,
                selectedComponentIds: Set<String>,
            ): DeviceShortcutResult {
                shortcutSelections += selectedComponentIds
                return DeviceShortcutResult.Failed(
                    DeviceShortcutFailureStage.AUTHORIZATION,
                    com.ninepointnine.helper.domain.device.DeviceActionFailure("unexpected", retryable = false),
                )
            }
        }

        val result = kotlinx.coroutines.runBlocking {
            DeviceInstallationCoordinator(InstallationSessionEventPort { events += it })
                .execute(actionLease(gateway), artifacts)
        }

        assertEquals(com.ninepointnine.helper.application.device.DeviceInstallationExecutionResult.Completed, result)
        assertEquals(listOf(setOf("desktop")), shortcutSelections)
        assertTrue(events.any {
            it is InstallationSessionEvent.ComponentFailed &&
                it.componentId == "desktop" &&
                it.reasonCode == "unexpected"
        })
        assertTrue(events.any {
            it is InstallationSessionEvent.ComponentFailed &&
                it.componentId == "lyrics" &&
                it.reasonCode == "desktop_prerequisite_failed"
        })
        assertTrue(lyricsFile.exists())
        assertTrue(desktopFile.exists())
    }

    private fun validAuthorizationEvidence(plan: AuthorizationPlan): List<AuthorizationActionEvidence> =
        plan.actions.map { action ->
            when (action) {
                is AuthorizationAction.EnsureAppOpAllowed -> AuthorizationActionEvidence(
                    componentId = action.componentId,
                    actionId = action.id,
                    before = AuthorizationValueState.DEFAULT,
                    writeApplied = true,
                    after = AuthorizationValueState.ALLOWED,
                )

                is AuthorizationAction.EnsureRuntimePermissionGranted -> AuthorizationActionEvidence(
                    componentId = action.componentId,
                    actionId = action.id,
                    before = AuthorizationValueState.DENIED,
                    writeApplied = true,
                    after = AuthorizationValueState.GRANTED,
                )

                is AuthorizationAction.EnsureSecureSettingEnabled -> AuthorizationActionEvidence(
                    componentId = action.componentId,
                    actionId = action.id,
                    before = AuthorizationValueState.DISABLED,
                    writeApplied = true,
                    after = AuthorizationValueState.ENABLED,
                )

                is AuthorizationAction.AppendSecureComponent -> AuthorizationActionEvidence(
                    componentId = action.componentId,
                    actionId = action.id,
                    before = AuthorizationValueState.COMPONENT_ABSENT,
                    writeApplied = true,
                    after = AuthorizationValueState.COMPONENT_PRESENT,
                    preservedEntryCount = 0,
                )
            }
        }

    private fun prepared(
        componentId: String,
        packageName: String,
        apkFile: java.io.File,
        declarations: ApkDeclarationMetadata = validDeclarations(componentId, packageName),
    ): PreparedArtifact = PreparedArtifact(
        manifest = manifest(componentId, packageName, apkFile.length()),
        sourceKind = ArtifactSourceKind.LANZOU_SHARE,
        finalApk = apkFile,
        declarations = declarations,
    )

    private fun validDeclarations(componentId: String, packageName: String): ApkDeclarationMetadata =
        when (componentId) {
            "lyrics" -> ApkDeclarationMetadata(
                requestedPermissions = setOf("android.permission.SYSTEM_ALERT_WINDOW"),
                services = setOf(
                    ApkServiceDeclaration(
                        "$packageName/com.tcrrry.desktoplyrics.MediaListenerService",
                        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
                    ),
                    ApkServiceDeclaration(
                        "$packageName/com.tcrrry.desktoplyrics.IcarDockAccessibilityService",
                        "android.permission.BIND_ACCESSIBILITY_SERVICE",
                    ),
                ),
            )

            "desktop" -> ApkDeclarationMetadata(
                requestedPermissions = setOf(
                    "android.permission.SYSTEM_ALERT_WINDOW",
                    "android.permission.REQUEST_INSTALL_PACKAGES",
                ),
                services = setOf(
                    ApkServiceDeclaration(
                        "$packageName/com.tcrrry.desktop.debug.NavigationDemoAccessibilityService",
                        "android.permission.BIND_ACCESSIBILITY_SERVICE",
                    ),
                ),
            )

            else -> ApkDeclarationMetadata(
                requestedPermissions = setOf(
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE",
                    "android.permission.REQUEST_INSTALL_PACKAGES",
                ),
            )
        }

    private fun installedEvidence(
        artifact: com.ninepointnine.helper.domain.device.InstallableArtifact,
    ): InstalledArtifactEvidence = InstalledArtifactEvidence(
        componentId = artifact.manifest.componentId,
        packageName = artifact.manifest.packageName,
        version = artifact.manifest.apkVersion,
        apkSizeBytes = artifact.manifest.apkSizeBytes,
        apkSha256 = artifact.manifest.apkSha256,
        certificateSha256 = artifact.manifest.certificateSha256,
    )

    private fun actionLease(gateway: AdbCommandGateway): DeviceActionConnectionLease = object : DeviceActionConnectionLease {
        override val device = ConnectedDevice(
            endpoint = DeviceEndpoint("192.0.2.203"),
            identity = DeviceIdentity("adb:EA33D009", "S56_HQX", 28),
            capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
        )
        override val commandGateway = gateway
        override suspend fun check(): DeviceConnectionCheck = DeviceConnectionCheck(true)
        override fun close() = Unit
    }

    private fun manifest(componentId: String, packageName: String, apkSize: Long): ArtifactManifest = ArtifactManifest(
        schemaVersion = 1,
        componentId = componentId,
        displayName = componentId,
        required = componentId == "desktop",
        version = ArtifactVersion("1.0.0", 1),
        compatibility = CompatibilityRange(26),
        archiveFileName = "$componentId.zip",
        archiveSizeBytes = 10,
        archiveSha256 = "11".repeat(32),
        apkEntryName = "$componentId.apk",
        apkSizeBytes = apkSize,
        apkSha256 = "22".repeat(32),
        packageName = packageName,
        apkVersion = ArtifactVersion("1.0.0", 1),
        certificateSha256 = "33".repeat(32),
        sources = listOf(
            ArtifactSource(ArtifactSourceKind.LANZOU_SHARE, "https://wwatl.lanzouw.com/iabc123"),
            ArtifactSource(ArtifactSourceKind.R2, "https://assets.r2.dev/$componentId.zip"),
            ArtifactSource(ArtifactSourceKind.GITHUB_RELEASES, "https://github.com/a/b/releases/download/v1/$componentId.zip"),
        ),
    )
}
