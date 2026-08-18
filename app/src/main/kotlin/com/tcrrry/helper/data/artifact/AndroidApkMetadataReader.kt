package com.tcrrry.helper.data.artifact

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.tcrrry.helper.domain.artifact.ArtifactVersion
import java.io.File
import java.security.MessageDigest

class AndroidApkMetadataReader(
    context: Context,
) : ApkMetadataReader {
    private val packageManager = context.applicationContext.packageManager

    @Suppress("DEPRECATION")
    override fun read(apk: File): ApkMetadata? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val packageInfo = packageManager.getPackageArchiveInfo(apk.absolutePath, flags) ?: return null
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            packageInfo.signatures?.toList().orEmpty()
        }
        if (signatures.isEmpty()) return null
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        val certificateDigests = signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }.toSet()
        return ApkMetadata(
            packageName = packageInfo.packageName,
            version = ArtifactVersion(
                name = packageInfo.versionName.orEmpty(),
                code = versionCode,
            ),
            certificateSha256s = certificateDigests,
        )
    }
}
