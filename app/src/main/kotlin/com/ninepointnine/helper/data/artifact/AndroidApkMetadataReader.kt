package com.ninepointnine.helper.data.artifact

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.ninepointnine.helper.domain.artifact.ArtifactVersion
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

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
        val manifest = readRawManifest(apk) ?: return null
        if (manifest.packageName != packageInfo.packageName) return null
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
        val applicationInfo = packageInfo.applicationInfo
        if (applicationInfo != null) {
            applicationInfo.sourceDir = apk.absolutePath
            applicationInfo.publicSourceDir = apk.absolutePath
        }
        return ApkMetadata(
            packageName = packageInfo.packageName,
            version = ArtifactVersion(
                name = packageInfo.versionName.orEmpty(),
                code = versionCode,
            ),
            certificateSha256s = certificateDigests,
            declarations = manifest.declarations,
            minAndroidSdk = applicationInfo?.minSdkVersion?.takeIf { it > 0 },
            displayName = applicationInfo?.let { info ->
                runCatching { packageManager.getApplicationLabel(info).toString().trim() }
                    .getOrNull()
                    ?.takeIf(String::isNotBlank)
            },
            // A package archive that references splits is unsuitable for the
            // one-file installation flow. Android's archive parser reports
            // those split names on PackageInfo for the supported SDK range.
            splitName = packageInfo.splitNames?.firstOrNull(),
        )
    }

    override fun readManifest(manifestBytes: ByteArray): ApkManifestMetadata? =
        AndroidBinaryManifestReader.read(manifestBytes)

    private fun readRawManifest(apk: File): ApkManifestMetadata? = runCatching {
        ZipFile(apk).use { archive ->
            val entry = archive.getEntry(MANIFEST_ENTRY_NAME)
                ?.takeUnless { it.isDirectory }
                ?: return@use null
            if (entry.size > MAX_APK_MANIFEST_BYTES) return@use null
            val bytes = archive.getInputStream(entry).use(::readBoundedManifest)
                ?: return@use null
            AndroidBinaryManifestReader.read(bytes)
        }
    }.getOrNull()

    private fun readBoundedManifest(input: java.io.InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_APK_MANIFEST_BYTES) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray().takeIf(ByteArray::isNotEmpty)
    }

    private companion object {
        const val MANIFEST_ENTRY_NAME = "AndroidManifest.xml"
    }
}
