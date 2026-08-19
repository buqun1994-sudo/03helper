package com.tcrrry.helper.domain.session

import com.tcrrry.helper.domain.artifact.ArchiveDownloadEvidence
import com.tcrrry.helper.domain.artifact.ArchiveVerificationEvidence
import com.tcrrry.helper.domain.artifact.ApkExtractionEvidence
import com.tcrrry.helper.domain.artifact.ArtifactManifest
import com.tcrrry.helper.domain.artifact.ArtifactSource
import com.tcrrry.helper.domain.artifact.ArtifactSourceKind
import com.tcrrry.helper.domain.artifact.ArtifactVerification
import com.tcrrry.helper.domain.artifact.ArtifactVersion
import com.tcrrry.helper.domain.artifact.CompatibilityRange
import com.tcrrry.helper.domain.artifact.SourceSelectionEvidence
import com.tcrrry.helper.domain.device.DeviceCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallationSessionF2Test {
    @Test
    fun `trusted catalog and artifact proof stop exactly at verifying artifacts`() {
        val manifest = manifest()
        val desktop = desktopManifest()
        val manifests = listOf(manifest, desktop)
        val session = InstallationSession()
        session.dispatchEvent(
            InstallationSessionEvent.CatalogResolved(
                catalogVersion = "android-v1",
                keyId = "key-1",
                signatureAlgorithm = "SHA256withECDSA",
                manifests = manifests,
            ),
            sequence = 1L,
        )
        session.dispatch(InstallationSessionCommand.StartDiscovery)
        session.dispatchEvent(InstallationSessionEvent.DeviceDiscovered(confirmedDevice))
        session.dispatch(InstallationSessionCommand.SelectDevice(confirmedDevice.id))
        confirmConnection(session, confirmedDevice)
        session.dispatch(InstallationSessionCommand.ToggleOptionalComponent("lyrics", selected = true))
        session.dispatch(InstallationSessionCommand.StartInstallation)
        session.dispatch(InstallationSessionCommand.BeginPipeline)
        session.dispatchEvent(
            InstallationSessionEvent.SourceResolved(
                sourceId = "fixed-release-source-policy",
                selections = manifests.map { SourceSelectionEvidence(it.componentId, ArtifactSourceKind.R2) },
            ),
        )
        session.dispatchEvent(
            InstallationSessionEvent.ArchiveDownloaded(
                sizeBytes = manifests.sumOf { it.archiveSizeBytes },
                sha256 = manifest.archiveSha256,
                archives = manifests.map {
                    ArchiveDownloadEvidence(it.componentId, it.archiveSizeBytes, it.archiveSha256)
                },
            ),
        )
        session.dispatchEvent(
            InstallationSessionEvent.ArchiveVerified(
                verified = true,
                verifications = manifests.map {
                    ArchiveVerificationEvidence(it.componentId, it.archiveSizeBytes, it.archiveSha256)
                },
            ),
        )
        session.dispatchEvent(
            InstallationSessionEvent.ApkExtracted(
                entryName = "batch.apk",
                sizeBytes = manifests.sumOf { it.apkSizeBytes },
                sha256 = "batch",
                extractions = manifests.map {
                    ApkExtractionEvidence(it.componentId, it.apkEntryName, it.apkSizeBytes, it.apkSha256)
                },
            ),
        )
        session.dispatchEvent(
            InstallationSessionEvent.ArtifactsVerified(
                checks = manifests.map { ComponentCheck(it.componentId, passed = true) },
                verifications = manifests.map {
                    ArtifactVerification(
                        componentId = it.componentId,
                        sourceKind = ArtifactSourceKind.R2,
                        archiveSizeBytes = it.archiveSizeBytes,
                        archiveSha256 = it.archiveSha256,
                        apkSizeBytes = it.apkSizeBytes,
                        apkSha256 = it.apkSha256,
                        packageName = it.packageName,
                        apkVersion = it.apkVersion,
                        certificateSha256 = it.certificateSha256,
                        archiveDeleted = true,
                    )
                },
                archiveDeleted = true,
            ),
        )
        assertEquals(InstallationSessionState.VERIFYING_ARTIFACTS, session.currentSnapshot().state)
        assertTrue(session.currentSnapshot().evidence.artifactVerifications.containsKey("lyrics"))

        session.dispatchEvent(InstallationSessionEvent.InstallationStarted())
        assertEquals(InstallationSessionState.INSTALLING, session.currentSnapshot().state)
    }

    @Test
    fun `invalid artifact proof fails closed and never enters installing`() {
        val manifest = manifest()
        val desktop = desktopManifest()
        val manifests = listOf(manifest, desktop)
        val session = InstallationSession()
        session.dispatchEvent(
            InstallationSessionEvent.CatalogResolved("android-v1", "key-1", "SHA256withECDSA", manifests),
            sequence = 1L,
        )
        session.dispatch(InstallationSessionCommand.StartDiscovery)
        session.dispatchEvent(InstallationSessionEvent.DeviceDiscovered(confirmedDevice))
        session.dispatch(InstallationSessionCommand.SelectDevice(confirmedDevice.id))
        confirmConnection(session, confirmedDevice)
        session.dispatch(InstallationSessionCommand.ToggleOptionalComponent("lyrics", selected = true))
        session.dispatch(InstallationSessionCommand.StartInstallation)
        session.dispatch(InstallationSessionCommand.BeginPipeline)
        session.dispatchEvent(
            InstallationSessionEvent.SourceResolved(
                sourceId = "fixed",
                selections = manifests.map { SourceSelectionEvidence(it.componentId, ArtifactSourceKind.R2) },
            ),
        )
        session.dispatchEvent(
            InstallationSessionEvent.ArchiveDownloaded(
                sizeBytes = manifests.sumOf { it.archiveSizeBytes },
                sha256 = manifest.archiveSha256,
                archives = manifests.map { ArchiveDownloadEvidence(it.componentId, it.archiveSizeBytes, it.archiveSha256) },
            ),
        )
        session.dispatchEvent(InstallationSessionEvent.ArchiveVerified(true, verifications = manifests.map {
            ArchiveVerificationEvidence(it.componentId, it.archiveSizeBytes, it.archiveSha256)
        }))
        session.dispatchEvent(InstallationSessionEvent.ApkExtracted(
            "batch.apk",
            manifests.sumOf { it.apkSizeBytes },
            "batch",
            extractions = listOf(
                ApkExtractionEvidence("lyrics", manifest.apkEntryName, manifest.apkSizeBytes, "00".repeat(32)),
                ApkExtractionEvidence("desktop", desktop.apkEntryName, desktop.apkSizeBytes, desktop.apkSha256),
            ),
        ))
        assertEquals(InstallationSessionState.FAILED, session.currentSnapshot().state)
        assertEquals("apk_extraction_evidence_invalid", session.currentSnapshot().failure?.reasonCode)
    }

    @Test
    fun `source fallback failure is retained as structured evidence`() {
        val session = connectedSession()
        session.dispatch(InstallationSessionCommand.StartInstallation)
        session.dispatch(InstallationSessionCommand.BeginPipeline)
        session.dispatchEvent(
            InstallationSessionEvent.SourceFailed(
                componentId = "lyrics",
                sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                reasonCode = "lanzou_parse_timeout",
                retryable = true,
                terminal = false,
            ),
        )
        assertEquals(InstallationSessionState.RESOLVING_SOURCE, session.currentSnapshot().state)
        assertEquals("lanzou_parse_timeout", session.currentSnapshot().sourceFailures.single().reasonCode)
        session.dispatchEvent(
            InstallationSessionEvent.SourceResolved(
                sourceId = "r2",
                componentId = "lyrics",
                sourceKind = ArtifactSourceKind.R2,
            ),
        )
        assertEquals(ArtifactSourceKind.R2, session.currentSnapshot().selectedSources["lyrics"])
    }

    @Test
    fun `catalog failure is a terminal structured session error`() {
        val session = InstallationSession()
        session.dispatchEvent(InstallationSessionEvent.CatalogFailed("catalog_signature_invalid"), sequence = 1L)
        assertEquals(InstallationSessionState.FAILED, session.currentSnapshot().state)
        assertEquals("catalog_signature_invalid", session.currentSnapshot().failure?.reasonCode)
    }

    @Test
    fun `catalog unavailable after connection keeps connection without resumable installation`() {
        val session = connectedSession()

        session.dispatchEvent(
            InstallationSessionEvent.CatalogFailed("catalog_android_profile_missing"),
        )

        val snapshot = session.currentSnapshot()
        assertEquals(InstallationSessionState.CONNECTED, snapshot.state)
        assertEquals("catalog_android_profile_missing", snapshot.failure?.reasonCode)
        assertEquals(FailureCategory.VERIFICATION, snapshot.failure?.category)
        assertEquals(null, snapshot.checkpoint)
        assertTrue(snapshot.artifactManifests.isEmpty())
        assertTrue(snapshot.evidence.artifactsVerified.isEmpty())
    }

    @Test
    fun `trusted catalog cannot start on an incompatible confirmed device`() {
        val manifests = listOf(
            manifest().copy(compatibility = CompatibilityRange(minAndroidSdk = 29)),
            desktopManifest().copy(compatibility = CompatibilityRange(minAndroidSdk = 29)),
        )
        val session = InstallationSession()
        session.dispatchEvent(
            InstallationSessionEvent.CatalogResolved("android-v1", "key-1", "SHA256withECDSA", manifests),
            sequence = 1L,
        )
        session.dispatch(InstallationSessionCommand.StartDiscovery)
        session.dispatchEvent(InstallationSessionEvent.DeviceDiscovered(confirmedDevice.copy(androidSdk = 28)))
        session.dispatch(InstallationSessionCommand.SelectDevice(confirmedDevice.id))
        confirmConnection(session, confirmedDevice.copy(androidSdk = 28))

        session.dispatch(InstallationSessionCommand.StartInstallation)

        assertEquals(InstallationSessionState.FAILED, session.currentSnapshot().state)
        assertEquals("component_incompatible", session.currentSnapshot().failure?.reasonCode)
    }

    private fun connectedSession(): InstallationSession {
        val session = InstallationSession(componentCatalog = listOf(
            ComponentDescriptor(
                id = "lyrics",
                displayName = "Lyrics",
                required = false,
                versionLabel = "1.0",
                sizeLabel = "1 MB",
                compatibilityLabel = "Android 26+",
            ),
            ComponentDescriptor(
                id = "desktop",
                displayName = "Desktop",
                required = true,
                versionLabel = "1.0",
                sizeLabel = "1 MB",
                compatibilityLabel = "Android 26+",
            ),
        ))
        session.dispatch(InstallationSessionCommand.StartDiscovery)
        session.dispatchEvent(InstallationSessionEvent.DeviceDiscovered(confirmedDevice))
        session.dispatch(InstallationSessionCommand.SelectDevice(confirmedDevice.id))
        val connecting = session.currentSnapshot()
        session.dispatchEvent(
            InstallationSessionEvent.DeviceConnectionConfirmed(confirmedDevice),
            sessionId = connecting.sessionId,
            sequence = connecting.lastEventSequence + 1L,
        )
        session.dispatch(
            InstallationSessionCommand.ToggleOptionalComponent("lyrics", selected = true),
        )
        return session
    }

    private fun confirmConnection(session: InstallationSession, device: DeviceSummary) {
        val connecting = session.currentSnapshot()
        session.dispatchEvent(
            InstallationSessionEvent.DeviceConnectionConfirmed(device),
            sessionId = connecting.sessionId,
            sequence = connecting.lastEventSequence + 1L,
        )
    }

    private fun manifest(): ArtifactManifest = ArtifactManifest(
        schemaVersion = 1,
        componentId = "lyrics",
        displayName = "Lyrics",
        required = false,
        version = ArtifactVersion("1.0.0", 1),
        compatibility = CompatibilityRange(26),
        archiveFileName = "lyrics.zip",
        archiveSizeBytes = 100,
        archiveSha256 = "11".repeat(32),
        apkEntryName = "lyrics.apk",
        apkSizeBytes = 50,
        apkSha256 = "22".repeat(32),
        packageName = "com.example.lyrics",
        apkVersion = ArtifactVersion("1.0.0", 7),
        certificateSha256 = "33".repeat(32),
        sources = listOf(
            ArtifactSource(ArtifactSourceKind.LANZOU_SHARE, "https://wwatl.lanzouw.com/iabc123"),
            ArtifactSource(ArtifactSourceKind.R2, "https://assets.r2.dev/lyrics.zip"),
            ArtifactSource(ArtifactSourceKind.GITHUB_RELEASES, "https://github.com/a/b/releases/download/v1/lyrics.zip"),
        ),
    )

    private fun desktopManifest(): ArtifactManifest = manifest().copy(
        componentId = "desktop",
        displayName = "Desktop",
        required = true,
        archiveFileName = "desktop.zip",
        apkEntryName = "desktop.apk",
        packageName = "com.example.desktop",
    )

    private companion object {
        val confirmedDevice = DeviceSummary(
            id = "phone",
            displayName = "Phone",
            connectionStatus = DeviceConnectionStatus.CONFIRMED,
            androidSdk = 28,
            capabilities = setOf(DeviceCapability.ADB_TCP, DeviceCapability.IDENTITY_READ),
        )
    }
}
