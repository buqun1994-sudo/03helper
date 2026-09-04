package com.ninepointnine.helper.data.artifact

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import com.ninepointnine.helper.domain.device.ApkDeclarationMetadata
import com.ninepointnine.helper.domain.device.ApkServiceDeclaration
import java.io.File
import java.security.MessageDigest

class AndroidApkMetadataReader(
    context: Context,
) : ApkMetadataReader {
    private val packageManager = context.applicationContext.packageManager

    @Suppress("DEPRECATION")
    override fun read(apk: File): ApkMetadata? {
        val signingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val packageInfo = packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            signingFlags or PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES,
        ) ?: return null
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
            declarations = ApkDeclarationMetadata(
                requestedPermissions = packageInfo.requestedPermissions?.toSet().orEmpty(),
                runtimeGrantPermissions = packageInfo.requestedPermissions.orEmpty()
                    .filter { permission ->
                        val declaredPermission = packageInfo.permissions.orEmpty()
                            .firstOrNull { it.name == permission }
                        val permissionInfo = declaredPermission ?: runCatching {
                            packageManager.getPermissionInfo(permission, 0)
                        }.getOrNull()
                        permissionInfo != null &&
                            permissionInfo.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE ==
                            PermissionInfo.PROTECTION_DANGEROUS
                    }
                    .toSet(),
                services = packageInfo.services.orEmpty().map { service ->
                    ApkServiceDeclaration(
                        componentName = serviceComponentName(packageInfo.packageName, service.name),
                        permission = service.permission,
                    )
                }.toSet(),
            ),
            minAndroidSdk = packageInfo.applicationInfo?.minSdkVersion?.takeIf { it > 0 },
        )
    }

    private fun serviceComponentName(packageName: String, className: String): String {
        val qualified = when {
            className.startsWith('.') -> packageName + className
            className.contains('.') -> className
            else -> "$packageName.$className"
        }
        return "$packageName/$qualified"
    }
}
