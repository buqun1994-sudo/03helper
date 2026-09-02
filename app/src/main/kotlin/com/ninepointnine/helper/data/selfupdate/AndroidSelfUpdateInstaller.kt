package com.ninepointnine.helper.data.selfupdate

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.ninepointnine.helper.application.SelfUpdateInstaller
import com.ninepointnine.helper.application.SelfUpdateLaunchResult
import com.ninepointnine.helper.application.SelfUpdateStageResult
import com.ninepointnine.helper.application.SelfUpdateVerificationResult
import com.ninepointnine.helper.application.StagedSelfUpdate
import com.ninepointnine.helper.application.artifact.PreparedArtifact
import com.ninepointnine.helper.data.artifact.ApkMetadataReader
import com.ninepointnine.helper.data.artifact.sha256
import com.ninepointnine.helper.domain.artifact.ArtifactManifest
import com.ninepointnine.helper.domain.artifact.InstallerSelfIdentity
import com.ninepointnine.helper.domain.device.InstalledArtifactEvidence
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Android endpoint for the helper update. It owns only staging, intent
 * creation, and package-manager readback; download and artifact validation stay
 * in [com.ninepointnine.helper.application.artifact.ArtifactPreparationCoordinator].
 */
class AndroidSelfUpdateInstaller(
    context: Context,
    private val metadataReader: ApkMetadataReader,
    private val root: File = File(context.applicationContext.filesDir, SELF_UPDATE_DIRECTORY),
) : SelfUpdateInstaller {
    private val applicationContext = context.applicationContext
    private val packageManager: PackageManager = applicationContext.packageManager
    private val authority: String = "${applicationContext.packageName}.fileprovider"

    override fun stage(artifact: PreparedArtifact): SelfUpdateStageResult {
        val manifest = artifact.manifest
        if (manifest.componentId != InstallerSelfIdentity.COMPONENT_ID ||
            manifest.packageName != InstallerSelfIdentity.PACKAGE_NAME
        ) {
            return SelfUpdateStageResult.Failed("self_update_identity_invalid", retryable = false)
        }
        val source = artifact.finalApk
            ?.takeIf { it.isFile && it.length() > 0L }
            ?: return SelfUpdateStageResult.Failed("self_update_apk_missing", retryable = true)
        val installedCode = installedVersionCode()
        if (installedCode != null && manifest.apkVersion.code <= installedCode) {
            return SelfUpdateStageResult.Failed("self_update_version_not_newer", retryable = false)
        }
        if (!runCatching { sha256(source).equals(manifest.apkSha256, ignoreCase = true) }.getOrDefault(false)) {
            return SelfUpdateStageResult.Failed("self_update_apk_hash_mismatch", retryable = false)
        }
        return try {
            require(root.mkdirs() || root.isDirectory)
            val target = root.resolve(PENDING_APK_NAME)
            val temporary = root.resolve(".$PENDING_APK_NAME.part")
            Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
            if (temporary.length() != source.length()) {
                temporary.delete()
                return SelfUpdateStageResult.Failed("self_update_stage_size_mismatch", retryable = true)
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            if (!target.isFile || target.length() != manifest.apkSizeBytes) {
                target.delete()
                return SelfUpdateStageResult.Failed("self_update_stage_size_mismatch", retryable = true)
            }
            SelfUpdateStageResult.Ready(StagedSelfUpdate(manifest, target))
        } catch (_: Exception) {
            SelfUpdateStageResult.Failed("self_update_stage_failed", retryable = true)
        }
    }

    override fun launch(staged: StagedSelfUpdate): SelfUpdateLaunchResult {
        if (!isOwnedPath(staged.apkFile) || !staged.apkFile.isFile) {
            return SelfUpdateLaunchResult.Failed("self_update_apk_missing", retryable = true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${applicationContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                applicationContext.startActivity(settingsIntent)
                SelfUpdateLaunchResult.Failed(
                    "self_update_unknown_sources_permission_required",
                    retryable = true,
                )
            } catch (_: Exception) {
                SelfUpdateLaunchResult.Failed("self_update_unknown_sources_settings_failed", retryable = true)
            }
        }
        val contentUri = try {
            FileProvider.getUriForFile(applicationContext, authority, staged.apkFile)
        } catch (_: Exception) {
            return SelfUpdateLaunchResult.Failed("self_update_uri_unavailable", retryable = false)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, APK_MIME_TYPE)
            // Some Android installer variants only retain the grant from the
            // clip payload when the intent crosses an activity boundary.
            clipData = ClipData.newRawUri("03helper-update", contentUri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            applicationContext.startActivity(intent)
            SelfUpdateLaunchResult.Started
        } catch (_: Exception) {
            SelfUpdateLaunchResult.Failed("self_update_installer_unavailable", retryable = true)
        }
    }

    override fun verifyInstalled(manifest: ArtifactManifest): SelfUpdateVerificationResult {
        val source = runCatching {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            packageManager.getPackageInfo(InstallerSelfIdentity.PACKAGE_NAME, flags)
                .applicationInfo?.sourceDir
                ?.let(::File)
        }.getOrNull()
            ?: return SelfUpdateVerificationResult.Failed("self_update_cancelled", retryable = true)
        val metadata = metadataReader.read(source)
            ?: return SelfUpdateVerificationResult.Failed("self_update_readback_failed", retryable = true)
        if (metadata.packageName != InstallerSelfIdentity.PACKAGE_NAME) {
            return SelfUpdateVerificationResult.Failed("self_update_readback_package_mismatch", retryable = false)
        }
        if (metadata.version != manifest.apkVersion) {
            return SelfUpdateVerificationResult.Failed("self_update_readback_version_mismatch", retryable = true)
        }
        val certificate = metadata.certificateSha256s.firstOrNull()
            ?: return SelfUpdateVerificationResult.Failed("self_update_readback_certificate_missing", retryable = false)
        val digest = runCatching { sha256(source) }.getOrNull()
            ?: return SelfUpdateVerificationResult.Failed("self_update_readback_hash_failed", retryable = true)
        if (!certificate.equals(manifest.certificateSha256, ignoreCase = true)) {
            return SelfUpdateVerificationResult.Failed("self_update_readback_certificate_mismatch", retryable = false)
        }
        if (!digest.equals(manifest.apkSha256, ignoreCase = true)) {
            return SelfUpdateVerificationResult.Failed("self_update_readback_hash_mismatch", retryable = false)
        }
        return SelfUpdateVerificationResult.Confirmed(
            InstalledArtifactEvidence(
                componentId = InstallerSelfIdentity.COMPONENT_ID,
                packageName = metadata.packageName,
                version = metadata.version,
                apkSizeBytes = source.length(),
                apkSha256 = digest,
                certificateSha256 = certificate.lowercase(),
                declarations = metadata.declarations,
            ),
        )
    }

    override fun clear(staged: StagedSelfUpdate) {
        if (isOwnedPath(staged.apkFile)) staged.apkFile.delete()
        root.resolve(".$PENDING_APK_NAME.part").delete()
    }

    private fun installedVersionCode(): Long? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                InstallerSelfIdentity.PACKAGE_NAME,
                PackageManager.PackageInfoFlags.of(0),
            ).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(InstallerSelfIdentity.PACKAGE_NAME, 0).versionCode.toLong()
        }
    }.getOrNull()

    private fun isOwnedPath(file: File): Boolean = runCatching {
        val base = root.canonicalFile.toPath()
        file.canonicalFile.toPath().startsWith(base)
    }.getOrDefault(false)

    private companion object {
        const val SELF_UPDATE_DIRECTORY = "self-update"
        const val PENDING_APK_NAME = "pending-update.apk"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
