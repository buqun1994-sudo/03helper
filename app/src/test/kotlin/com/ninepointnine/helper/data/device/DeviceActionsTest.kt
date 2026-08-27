package com.ninepointnine.helper.data.device

import com.ninepointnine.helper.application.artifact.PreparedArtifact
import com.ninepointnine.helper.application.device.DeviceInstallationCoordinator
import com.ninepointnine.helper.application.device.DeviceInstallationExecutionResult
import com.ninepointnine.helper.application.session.InstallationSessionEventPort
import com.ninepointnine.helper.data.artifact.ApkMetadata
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
import com.ninepointnine.helper.domain.session.InstallationBatchPlan
import com.ninepointnine.helper.domain.session.InstallationFlow
import com.ninepointnine.helper.domain.session.InstallPhase
import com.ninepointnine.helper.domain.session.InstallationStrategy
import dadb.AdbShellResponse
import dadb.Dadb
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
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
        assertFalse(command.contains("wait_for_service_bound"))
        assertFalse(command.contains("service_bound()"))
        assertFalse(command.contains("dumpsys activity services"))
        assertFalse(command.contains("awk"))
        assertTrue(command.contains("No operations."))
        assertFalse(
            command.substringAfter("ensure_notification_listener()")
                .substringBefore("ensure_accessibility_service()")
                .contains("dumpsys activity services"),
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
                "emit \"LAUNCH|desktop|OK\"",
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
        val script = extractShellArgument(command)
        assertTrue(script.contains("IFS='|'"))
        assertTrue(script.contains("set -f"))
        assertTrue(script.contains("set -- ${'$'}spec"))
        assertFalse(script.contains("${'$'}{spec%%|*}"))

        assertTrue(command.contains("--dynamic"))
        assertTrue(command.contains("--desktop-package=com.ninepointnine.desktop"))
        assertTrue(command.contains("--desktop-launch=com.ninepointnine.desktop/.MainActivity"))
        assertTrue(command.contains("launch_component=\"${'$'}desktop_launch_component\""))
        assertFalse(command.contains("--desktop-service="))
        assertFalse(command.contains("${'$'}desktop_package/.MainActivity"))
        assertFalse(command.contains("${'$'}desktop_package/.debug.NavigationDemoAccessibilityService"))
    }

    @Test
    fun `Android 9 abbreviated service records satisfy a fully qualified manifest component`() {
        val output = """
            * ServiceRecord{123 u0 com.ninepointnine.desktop/.debug.NavigationDemoAccessibilityService}
              intent={cmp=com.ninepointnine.desktop/.debug.NavigationDemoAccessibilityService}
              requested=true received=true hasBound=true
        """.trimIndent()

        assertTrue(
            BoundServiceEvidenceParser.isBound(
                output,
                "com.ninepointnine.desktop/com.ninepointnine.desktop.debug.NavigationDemoAccessibilityService",
            ),
        )
    }

    @Test
    fun `authorization command leaves service dumpsys interpretation to the Kotlin adapter`() {
        val desktop = AuthorizationPlanFactory.allManagedComponents()
            .first { it.componentId == AuthorizationPlanFactory.DESKTOP_COMPONENT_ID }
            .copy(packageName = "com.ninepointnine.desktop")
        val ready = AuthorizationPlanFactory.createForComponents(listOf(desktop)) as AuthorizationPlanBuildResult.Ready

        val command = CombinedAuthorizationCommand.build(ready.plan)

        assertFalse(command.contains("dumpsys activity services"))
    }

    @Test
    fun `generated authorization script is valid POSIX shell`() {
        val plan = AuthorizationPlanFactory.createForComponents(
            listOf(
                AuthorizationPlanFactory.allManagedComponents().first { it.componentId == "desktop" },
                AuthorizationPlanFactory.allManagedComponents().first { it.componentId == "lyrics" },
            ),
        ) as AuthorizationPlanBuildResult.Ready

        val command = CombinedAuthorizationCommand.build(plan.plan)
        val script = extractShellArgument(command)
        val scriptFile = Files.createTempFile("03helper-authorization", ".sh").toFile()
        try {
            scriptFile.writeText(script)
            val process = ProcessBuilder("sh", "-n", scriptFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.waitFor())
            assertTrue(output.isBlank())
        } finally {
            scriptFile.delete()
        }
    }

    @Test
    fun `repair command never launches an application and still requires terminal evidence`() {
        val command = CombinedAuthorizationCommand.build(setOf("desktop", "lyrics"), repairOnly = true)

        assertTrue(command.endsWith("03helper --repair desktop lyrics"))
        assertTrue(command.contains("repair_only=0"))
        assertTrue(command.contains("if [ \"${'$'}{1:-}\" = --repair ]"))
        val repairExit = command.indexOf("if [ \"${'$'}repair_only\" -eq 1 ]; then emit \"DONE|OK\"; exit 0; fi")
        val launchPath = command.lastIndexOf("launch_component=")
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
    fun `combined response keeps authorization receipts when desktop verification fails`() {
        val plan = AuthorizationPlanFactory.createForComponents(
            listOf(
                AuthorizationPlanFactory.allManagedComponents().first { it.componentId == "desktop" },
                AuthorizationPlanFactory.allManagedComponents().first { it.componentId == "lyrics" },
            ),
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
        val result = CombinedAuthorizationResponseParser.parse(
            AdbShellResponse(
                "$authorizationLines\n03HELPER|FAIL|desktop_service_not_bound|desktop",
                "",
                1,
            ),
            plan.plan,
        )

        assertTrue(result is DeviceShortcutResult.Failed)
        val failed = result as DeviceShortcutResult.Failed
        assertEquals(DeviceShortcutFailureStage.VERIFICATION, failed.stage)
        assertEquals("desktop_service_not_bound", failed.failure.reasonCode)
        assertEquals(setOf("desktop", "lyrics"), failed.configuredComponentIds)
        assertEquals(plan.plan.actions.size, failed.authorizationEvidence.size)
        assertTrue(failed.availabilityEvidence.isEmpty())
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
    fun `missing-only gateway strategy skips installed package but reinstall strategy writes both`() {
        val root = Files.createTempDirectory("gateway-install-strategy").toFile()
        val desktopApk = root.resolve("desktop.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val lyricsApk = root.resolve("lyrics.apk").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val outdatedApk = root.resolve("outdated-desktop.apk").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val desktop = prepared("desktop", "com.tcrrry.desktop", desktopApk)
        val lyrics = prepared("lyrics", "com.tcrrry.desktoplyrics", lyricsApk)
        val metadata = mapOf(
            desktopApk.absolutePath to ApkMetadata(
                packageName = desktop.manifest.packageName,
                version = desktop.manifest.apkVersion,
                certificateSha256s = setOf(desktop.manifest.certificateSha256),
            ),
            lyricsApk.absolutePath to ApkMetadata(
                packageName = lyrics.manifest.packageName,
                version = lyrics.manifest.apkVersion,
                certificateSha256s = setOf(lyrics.manifest.certificateSha256),
            ),
            outdatedApk.absolutePath to ApkMetadata(
                packageName = desktop.manifest.packageName,
                version = ArtifactVersion("2.0.0", 2),
                certificateSha256s = setOf(desktop.manifest.certificateSha256),
            ),
        )
        fun gatewayFixture(
            inventoryFailure: Boolean = false,
            legacyInventory: Boolean = false,
            identityMismatch: Boolean = false,
            pulledDesktopVersion: ArtifactVersion? = null,
        ): Pair<DadbCommandGateway, MutableList<String>> {
            val metadataReader = com.ninepointnine.helper.data.artifact.ApkMetadataReader { apk ->
                if (identityMismatch && apk.name.endsWith(".installed.apk")) {
                    ApkMetadata(
                        packageName = "com.example.untrusted",
                        version = desktop.manifest.apkVersion,
                        certificateSha256s = setOf(desktop.manifest.certificateSha256),
                    )
                } else if (pulledDesktopVersion != null && apk.name.endsWith(".installed.apk")) {
                    ApkMetadata(
                        packageName = desktop.manifest.packageName,
                        version = pulledDesktopVersion,
                        certificateSha256s = setOf(desktop.manifest.certificateSha256),
                    )
                } else {
                    metadata[apk.absolutePath] ?: ApkMetadata(
                        packageName = if (apk.name.contains("desktop")) desktop.manifest.packageName else lyrics.manifest.packageName,
                        version = if (apk.name.contains("desktop")) desktop.manifest.apkVersion else lyrics.manifest.apkVersion,
                        certificateSha256s = setOf(if (apk.name.contains("desktop")) desktop.manifest.certificateSha256 else lyrics.manifest.certificateSha256),
                    )
                }
            }
            val writes = mutableListOf<String>()
            val fakeDadb = Proxy.newProxyInstance(
                Dadb::class.java.classLoader,
                arrayOf(Dadb::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "shell" -> {
                        val command = args?.firstOrNull()?.toString().orEmpty()
                        when {
                            command == "pm list packages --show-versioncode" ->
                                if (inventoryFailure) {
                                    AdbShellResponse("", "inventory_failed", 1)
                                } else if (legacyInventory) {
                                    AdbShellResponse("", "unsupported", 1)
                                } else {
                                    AdbShellResponse("package:com.tcrrry.desktop versionCode:1\n", "", 0)
                                }

                            command == "pm list packages" && inventoryFailure ->
                                AdbShellResponse("not-a-package-row\n", "", 0)

                            command == "pm list packages" && legacyInventory ->
                                AdbShellResponse("package:com.tcrrry.desktop\n", "", 0)

                            command == "pm path com.tcrrry.desktop" ->
                                AdbShellResponse("package:/data/app/com.tcrrry.desktop/base.apk\n", "", 0)

                            command == "pm path com.tcrrry.desktoplyrics" ->
                                AdbShellResponse("package:/data/app/com.tcrrry.desktoplyrics/base.apk\n", "", 0)

                            command.startsWith("pm install -r ") -> {
                                writes += command
                                AdbShellResponse("Success\n", "", 0)
                            }

                            command.startsWith("rm -f ") -> AdbShellResponse("", "", 0)
                            else -> AdbShellResponse("", "", 0)
                        }
                    }

                    "push" -> {
                        writes += "push:${args?.getOrNull(1)}"
                        null
                    }

                    "pull" -> {
                        (args?.getOrNull(0) as? java.io.File)?.writeBytes(byteArrayOf(9, 8, 7))
                        null
                    }

                    "supportsFeature" -> false
                    "close" -> null
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as Dadb
            return DadbCommandGateway(
                adb = fakeDadb,
                closed = AtomicBoolean(false),
                ioMutex = Mutex(),
                installedApkCacheDirectory = root.resolve("verification"),
                installedApkMetadataReader = metadataReader,
            ) to writes
        }

        val (missingOnlyGateway, missingOnlyWrites) = gatewayFixture()
        val missingOnlyResult = runBlocking {
            missingOnlyGateway.install(
                listOf(
                    com.ninepointnine.helper.domain.device.InstallableArtifact(
                        desktop.manifest,
                        desktop.finalApk,
                        desktop.declarations,
                    ),
                    com.ninepointnine.helper.domain.device.InstallableArtifact(
                        lyrics.manifest,
                        lyrics.finalApk,
                        lyrics.declarations,
                    ),
                ),
                InstallationStrategy.INSTALL_MISSING_ONLY,
            )
        }
        assertTrue(missingOnlyResult is DeviceInstallResult.Installed)
        assertEquals(1, missingOnlyWrites.count { it.startsWith("push:") })
        assertEquals(1, missingOnlyWrites.count { it.startsWith("pm install -r ") })

        val (outdatedGateway, outdatedWrites) = gatewayFixture(
            pulledDesktopVersion = ArtifactVersion("2.0.0", 2),
        )
        val outdatedDesktop = desktop.manifest.copy(
            version = ArtifactVersion("2.0.0", 2),
            apkVersion = ArtifactVersion("2.0.0", 2),
        )
        val outdatedResult = runBlocking {
            outdatedGateway.install(
                listOf(
                    com.ninepointnine.helper.domain.device.InstallableArtifact(
                        outdatedDesktop,
                        outdatedApk,
                    ),
                ),
                InstallationStrategy.INSTALL_MISSING_ONLY,
            )
        }
        assertTrue(outdatedResult is DeviceInstallResult.Installed)
        assertEquals(1, outdatedWrites.count { it.startsWith("push:") })
        assertEquals(1, outdatedWrites.count { it.startsWith("pm install -r ") })

        val (inventoryFailureGateway, inventoryFailureWrites) = gatewayFixture(inventoryFailure = true)
        val inventoryFailureResult = runBlocking {
            inventoryFailureGateway.install(
                listOf(
                    com.ninepointnine.helper.domain.device.InstallableArtifact(desktop.manifest, desktop.finalApk),
                    com.ninepointnine.helper.domain.device.InstallableArtifact(lyrics.manifest, lyrics.finalApk),
                ),
                InstallationStrategy.INSTALL_MISSING_ONLY,
            )
        }
        assertTrue(inventoryFailureResult is DeviceInstallResult.Failed)
        assertEquals("installation_package_inventory_failed", (inventoryFailureResult as DeviceInstallResult.Failed).failure.reasonCode)
        assertTrue(inventoryFailureWrites.isEmpty())

        val (legacyGateway, legacyWrites) = gatewayFixture(legacyInventory = true)
        val legacyReusableResult = runBlocking {
            legacyGateway.install(
                listOf(
                    // Reusable prerequisites intentionally have no local APK;
                    // a legacy presence-only inventory must still skip writes.
                    com.ninepointnine.helper.domain.device.InstallableArtifact(desktop.manifest, null),
                ),
                InstallationStrategy.INSTALL_MISSING_ONLY,
            )
        }
        assertTrue(legacyReusableResult is DeviceInstallResult.Installed)
        assertTrue(legacyWrites.none { it.startsWith("push:") })
        assertTrue(legacyWrites.none { it.startsWith("pm install -r ") })

        val (reinstallGateway, reinstallWrites) = gatewayFixture()
        val reinstallResult = runBlocking {
            reinstallGateway.install(
                listOf(
                    com.ninepointnine.helper.domain.device.InstallableArtifact(desktop.manifest, desktop.finalApk),
                    com.ninepointnine.helper.domain.device.InstallableArtifact(lyrics.manifest, lyrics.finalApk),
                ),
                InstallationStrategy.REINSTALL_SELECTED,
            )
        }
        assertTrue(reinstallResult is DeviceInstallResult.Installed)
        assertEquals(2, reinstallWrites.count { it.startsWith("push:") })
        assertEquals(2, reinstallWrites.count { it.startsWith("pm install -r ") })

        val (unverifiedGateway, unverifiedWrites) = gatewayFixture(identityMismatch = true)
        val unverifiedResult = runBlocking {
            unverifiedGateway.install(
                listOf(
                    com.ninepointnine.helper.domain.device.InstallableArtifact(desktop.manifest, desktop.finalApk),
                ),
                InstallationStrategy.REINSTALL_SELECTED,
            )
        }
        assertTrue(unverifiedResult is DeviceInstallResult.WrittenButUnverified)
        val unverified = unverifiedResult as DeviceInstallResult.WrittenButUnverified
        assertEquals(setOf("desktop"), unverified.writeConfirmedComponentIds)
        assertEquals("installation_installed_package_mismatch", unverified.failure.reasonCode)
        assertEquals(1, unverifiedWrites.count { it.startsWith("pm install -r ") })
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
        val installStrategies = mutableListOf<InstallationStrategy>()
        val events = mutableListOf<InstallationSessionEvent>()
        val gateway = object : AdbCommandGateway {
            override suspend fun install(artifacts: List<com.ninepointnine.helper.domain.device.InstallableArtifact>): DeviceInstallResult =
                DeviceInstallResult.Installed(artifacts.map(::installedEvidence))

            override suspend fun install(
                artifacts: List<com.ninepointnine.helper.domain.device.InstallableArtifact>,
                strategy: InstallationStrategy,
            ): DeviceInstallResult {
                installStrategies += strategy
                return DeviceInstallResult.Installed(artifacts.map(::installedEvidence))
            }

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
        assertEquals(
            listOf(InstallationStrategy.INSTALL_MISSING_ONLY, InstallationStrategy.INSTALL_MISSING_ONLY),
            installStrategies,
        )
        assertEquals(listOf(setOf("desktop", "lyrics")), commandSelections)
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
    fun `coordinator consumes the explicit maintenance batch and does not launch desktop twice`() {
        val desktopFile = Files.createTempFile("desktop-reused", ".apk").toFile().apply { writeBytes(byteArrayOf(2)) }
        val lyricsFile = Files.createTempFile("lyrics-fresh", ".apk").toFile().apply { writeBytes(byteArrayOf(1)) }
        val desktop = prepared("desktop", "com.tcrrry.desktop", desktopFile).copy(
            finalApk = null,
            declarations = null,
        )
        val lyrics = prepared("lyrics", "com.tcrrry.desktoplyrics", lyricsFile)
        val artifacts = listOf(desktop, lyrics)
        val plan = InstallationBatchPlan(
            batchId = 9L,
            flow = InstallationFlow.MAINTENANCE_INSTALL,
            strategy = InstallationStrategy.INSTALL_MISSING_ONLY,
            selectedComponentIds = setOf("desktop", "lyrics"),
            reusableComponentIds = setOf("desktop"),
            preparationComponentIds = setOf("lyrics"),
            resultComponentIds = setOf("lyrics"),
        )
        val shortcuts = mutableListOf<DeviceShortcut>()
        val installCalls = mutableListOf<String>()
        val events = mutableListOf<InstallationSessionEvent>()
        val gateway = object : AdbCommandGateway {
            override suspend fun install(artifacts: List<com.ninepointnine.helper.domain.device.InstallableArtifact>): DeviceInstallResult =
                DeviceInstallResult.Installed(artifacts.map {
                    installCalls += it.manifest.componentId
                    installedEvidence(it)
                })

            override suspend fun install(
                artifacts: List<com.ninepointnine.helper.domain.device.InstallableArtifact>,
                strategy: InstallationStrategy,
            ): DeviceInstallResult = install(artifacts)

            override suspend fun runShortcut(
                shortcut: DeviceShortcut,
                selectedComponentIds: Set<String>,
            ): DeviceShortcutResult = error("the plan-aware shortcut overload is required")

            override suspend fun runShortcut(
                shortcut: DeviceShortcut,
                selectedComponentIds: Set<String>,
                authorizationPlan: AuthorizationPlan,
            ): DeviceShortcutResult {
                shortcuts += shortcut
                return DeviceShortcutResult.Completed(
                    configuredComponentIds = selectedComponentIds,
                    skippedComponentIds = emptySet(),
                    authorizationEvidence = validAuthorizationEvidence(authorizationPlan),
                    availabilityEvidence = listOf(
                        com.ninepointnine.helper.domain.device.ManagedApplicationAvailabilityEvidence(
                            componentId = "lyrics",
                            packageName = "com.tcrrry.desktoplyrics",
                            launchAttempted = false,
                            launcherResolved = false,
                            processRunning = false,
                            requiredServiceBound = null,
                        ),
                    ),
                )
            }
        }

        val result = kotlinx.coroutines.runBlocking {
            DeviceInstallationCoordinator(InstallationSessionEventPort { events += it })
                .execute(actionLease(gateway), artifacts, plan)
        }

        assertEquals(DeviceInstallationExecutionResult.Completed, result)
        assertEquals(listOf("desktop", "lyrics"), installCalls)
        assertEquals(listOf(DeviceShortcut.CONFIGURE_SELECTED_APPS), shortcuts)
        val verified = events.filterIsInstance<InstallationSessionEvent.DeviceVerified>().single()
        assertEquals(setOf("lyrics"), verified.checks.map { it.componentId }.toSet())
        assertFalse(verified.evidence.any { it.componentId == "desktop" })
    }

    @Test
    fun `coordinator rejects a batch plan artifact mismatch before writing to the device`() {
        val desktopFile = Files.createTempFile("desktop-plan-mismatch", ".apk").toFile().apply { writeBytes(byteArrayOf(2)) }
        val lyricsFile = Files.createTempFile("lyrics-plan-mismatch", ".apk").toFile().apply { writeBytes(byteArrayOf(1)) }
        val artifacts = listOf(
            prepared("desktop", "com.tcrrry.desktop", desktopFile),
            prepared("lyrics", "com.tcrrry.desktoplyrics", lyricsFile),
        )
        val plan = InstallationBatchPlan(
            batchId = 10L,
            flow = InstallationFlow.MAINTENANCE_INSTALL,
            strategy = InstallationStrategy.INSTALL_MISSING_ONLY,
            selectedComponentIds = setOf("desktop", "lyrics"),
            reusableComponentIds = setOf("desktop"),
            preparationComponentIds = setOf("lyrics"),
            resultComponentIds = setOf("lyrics"),
        )
        var installCalls = 0
        val gateway = object : AdbCommandGateway {
            override suspend fun install(artifacts: List<com.ninepointnine.helper.domain.device.InstallableArtifact>): DeviceInstallResult {
                installCalls += 1
                return DeviceInstallResult.Installed(artifacts.map(::installedEvidence))
            }

            override suspend fun runShortcut(
                shortcut: DeviceShortcut,
                selectedComponentIds: Set<String>,
            ): DeviceShortcutResult = error("shortcut must not run")
        }
        val events = mutableListOf<InstallationSessionEvent>()
        val result = kotlinx.coroutines.runBlocking {
            DeviceInstallationCoordinator(InstallationSessionEventPort { events += it })
                .execute(actionLease(gateway), artifacts, plan)
        }

        assertEquals(DeviceInstallationExecutionResult.Failed, result)
        assertEquals(0, installCalls)
        assertTrue(events.any {
            it is InstallationSessionEvent.FatalError &&
                it.reasonCode == "installation_batch_plan_invalid"
        })
    }

    @Test
    fun `desktop-only reusable maintenance batch completes without authorization or launch`() {
        val desktopFile = Files.createTempFile("desktop-only-reused", ".apk").toFile().apply { writeBytes(byteArrayOf(2)) }
        val artifacts = listOf(
            prepared("desktop", "com.tcrrry.desktop", desktopFile).copy(finalApk = null, declarations = null),
        )
        val plan = InstallationBatchPlan(
            batchId = 11L,
            flow = InstallationFlow.MAINTENANCE_INSTALL,
            strategy = InstallationStrategy.INSTALL_MISSING_ONLY,
            selectedComponentIds = setOf("desktop"),
            reusableComponentIds = setOf("desktop"),
            preparationComponentIds = emptySet(),
            resultComponentIds = emptySet(),
        )
        var shortcutCalls = 0
        val events = mutableListOf<InstallationSessionEvent>()
        val gateway = object : AdbCommandGateway {
            override suspend fun install(artifacts: List<com.ninepointnine.helper.domain.device.InstallableArtifact>): DeviceInstallResult =
                DeviceInstallResult.Installed(artifacts.map(::installedEvidence))

            override suspend fun runShortcut(
                shortcut: DeviceShortcut,
                selectedComponentIds: Set<String>,
            ): DeviceShortcutResult {
                shortcutCalls += 1
                return error("desktop-only batch must not authorize")
            }
        }

        val result = kotlinx.coroutines.runBlocking {
            DeviceInstallationCoordinator(InstallationSessionEventPort { events += it })
                .execute(actionLease(gateway), artifacts, plan)
        }

        assertEquals(DeviceInstallationExecutionResult.Completed, result)
        assertEquals(0, shortcutCalls)
        assertTrue(events.filterIsInstance<InstallationSessionEvent.AuthorizationCompleted>().single().checks.isEmpty())
        assertTrue(events.filterIsInstance<InstallationSessionEvent.DeviceVerified>().single().checks.isEmpty())
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
    fun `verification failure preserves installed and authorized optional components`() {
        val lyricsFile = Files.createTempFile("lyrics-partial", ".apk").toFile().apply { writeBytes(byteArrayOf(1)) }
        val desktopFile = Files.createTempFile("desktop-partial", ".apk").toFile().apply { writeBytes(byteArrayOf(2)) }
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
                val plan = AuthorizationPlanFactory.create(
                    artifacts.map {
                        com.ninepointnine.helper.domain.device.InstallableArtifact(
                            it.manifest,
                            it.finalApk,
                            it.declarations,
                        )
                    },
                ) as AuthorizationPlanBuildResult.Ready
                return DeviceShortcutResult.Failed(
                    stage = DeviceShortcutFailureStage.VERIFICATION,
                    failure = com.ninepointnine.helper.domain.device.DeviceActionFailure(
                        "desktop_service_not_bound",
                        "desktop",
                        retryable = true,
                    ),
                    configuredComponentIds = selectedComponentIds,
                    authorizationEvidence = validAuthorizationEvidence(plan.plan),
                )
            }
        }

        kotlinx.coroutines.runBlocking {
            DeviceInstallationCoordinator(InstallationSessionEventPort { events += it })
                .execute(actionLease(gateway), artifacts)
        }

        val authorization = events.filterIsInstance<InstallationSessionEvent.AuthorizationCompleted>().single()
        assertEquals(setOf("desktop", "lyrics"), authorization.checks.map { it.componentId }.toSet())
        assertTrue(events.any {
            it is InstallationSessionEvent.ComponentFailed &&
                it.componentId == "desktop" &&
                it.phase == InstallPhase.VERIFY &&
                it.reasonCode == "desktop_service_not_bound"
        })
        assertFalse(events.any {
            it is InstallationSessionEvent.ComponentFailed &&
                it.componentId == "lyrics"
        })
        val verified = events.filterIsInstance<InstallationSessionEvent.DeviceVerified>().single()
        assertEquals(setOf("lyrics"), verified.checks.map { it.componentId }.toSet())
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
                it.reasonCode == "authorization_service_not_declared"
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

    /** Decode the single-quoted script argument without evaluating the script. */
    private fun extractShellArgument(command: String): String {
        val prefix = "sh -c "
        val start = command.indexOf(prefix)
        require(start >= 0) { "missing shell command prefix" }
        val quotedStart = start + prefix.length
        require(command.getOrNull(quotedStart) == '\'') { "script is not shell quoted" }
        val quoted = command.substring(quotedStart, command.indexOf(" 03helper ", quotedStart))
        val extraction = ProcessBuilder(
            "sh",
            "-c",
            "printf '%s' ${quoted}",
        ).redirectErrorStream(true).start()
        val script = extraction.inputStream.bufferedReader().use { it.readText() }
        check(extraction.waitFor() == 0) { "could not decode shell argument" }
        return script
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
