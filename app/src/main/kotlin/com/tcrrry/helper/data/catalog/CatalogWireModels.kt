package com.tcrrry.helper.data.catalog

import com.tcrrry.helper.domain.artifact.ArtifactManifest
import com.tcrrry.helper.domain.artifact.ArtifactSource
import com.tcrrry.helper.domain.artifact.ArtifactSourceKind
import com.tcrrry.helper.domain.artifact.ArtifactVersion
import com.tcrrry.helper.domain.artifact.CompatibilityRange
import com.tcrrry.helper.domain.device.AuthorizationSetupDeclaration
import kotlinx.serialization.Serializable

/** Public wire DTOs keep the signed release format explicit for release tooling. */
@Serializable
data class SignedCatalogEnvelope(
    val schemaVersion: Int,
    val catalogVersion: String,
    val keyId: String,
    val signatureAlgorithm: String,
    val payloadBase64: String,
    val signatureBase64: String,
)

@Serializable
data class ArtifactCatalogPayload(
    val schemaVersion: Int,
    val artifacts: List<ArtifactManifestDocument>,
)

@Serializable
data class ArtifactManifestDocument(
    val schemaVersion: Int,
    val componentId: String,
    val displayName: String,
    val description: String = "",
    val required: Boolean,
    val version: ArtifactVersionDocument,
    val compatibility: CompatibilityRangeDocument,
    val archiveFormat: String,
    val archiveFileName: String,
    val archiveSizeBytes: Long,
    val archiveSha256: String,
    val apkEntryName: String,
    val apkSizeBytes: Long,
    val apkSha256: String,
    val packageName: String,
    val apkVersion: ArtifactVersionDocument,
    val certificateSha256: String,
    val sources: List<ArtifactSourceDocument>,
    val rollbackId: String? = null,
    /** Dynamic setup is optional for legacy signed artifact catalogs. */
    val deviceSetup: DeviceSetupWireDocument? = null,
    val sortOrder: Int = 0,
) {
    fun toDomain(): ArtifactManifest = ArtifactManifest(
        schemaVersion = schemaVersion,
        componentId = componentId,
        displayName = displayName,
        description = description,
        required = required,
        version = version.toDomain(),
        compatibility = compatibility.toDomain(),
        archiveFormat = archiveFormat,
        archiveFileName = archiveFileName,
        archiveSizeBytes = archiveSizeBytes,
        archiveSha256 = archiveSha256,
        apkEntryName = apkEntryName,
        apkSizeBytes = apkSizeBytes,
        apkSha256 = apkSha256,
        packageName = packageName,
        apkVersion = apkVersion.toDomain(),
        certificateSha256 = certificateSha256,
        sources = sources.map { it.toDomain() },
        rollbackId = rollbackId,
        deviceSetup = deviceSetup?.let { setup ->
            setup.toDomainOrNull() ?: throw IllegalArgumentException("device_setup_invalid")
        },
        sortOrder = sortOrder,
    )
}

@Serializable
data class DeviceSetupWireDocument(
    val profileId: String = "",
    val actionIds: List<String> = emptyList(),
    val appOps: List<String> = emptyList(),
    val runtimePermissions: List<String> = emptyList(),
    val secureSettings: List<String> = emptyList(),
    val secureComponents: List<SecureComponentWireDocument> = emptyList(),
    val launchComponent: String? = null,
    val requiredServices: List<String> = emptyList(),
) {
    fun toDomainOrNull(): AuthorizationSetupDeclaration? {
        return try {
            val parsedAppOps = appOps.map { value ->
                com.tcrrry.helper.domain.device.ManagedAppOp.entries.first {
                    it.name == value || it.wireName == value
                }
            }
            val parsedRuntimePermissions = runtimePermissions.map { value ->
                com.tcrrry.helper.domain.device.ManagedRuntimePermission.entries.first {
                    it.name == value || it.wireName == value
                }
            }
            val parsedSecureSettings = secureSettings.map { value ->
                com.tcrrry.helper.domain.device.ManagedSecureFlag.entries.first {
                    it.name == value || it.wireName == value
                }
            }
            val parsedSecureComponents = secureComponents.map {
                AuthorizationSetupDeclaration.SecureComponent(
                    setting = com.tcrrry.helper.domain.device.ManagedSecureComponentList.entries.first { setting ->
                        setting.name == it.setting || setting.wireName == it.setting
                    },
                    targetComponent = it.targetComponent,
                )
            }
            if (parsedAppOps.size != parsedAppOps.toSet().size ||
                parsedRuntimePermissions.size != parsedRuntimePermissions.toSet().size ||
                parsedSecureSettings.size != parsedSecureSettings.toSet().size ||
                requiredServices.size != requiredServices.toSet().size ||
                parsedSecureComponents.size != parsedSecureComponents.toSet().size
            ) {
                return null
            }
            AuthorizationSetupDeclaration(
                appOps = parsedAppOps.toSet(),
                runtimePermissions = parsedRuntimePermissions.toSet(),
                secureSettings = parsedSecureSettings.toSet(),
                secureComponents = parsedSecureComponents.toSet(),
                launchComponent = launchComponent,
                requiredServices = requiredServices.toSet(),
                profileId = profileId,
                actionIds = actionIds.toSet(),
            )
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
data class SecureComponentWireDocument(
    val setting: String,
    val targetComponent: String,
)

@Serializable
data class ArtifactVersionDocument(
    val name: String,
    val code: Long,
) {
    fun toDomain(): ArtifactVersion = ArtifactVersion(name, code)
}

@Serializable
data class CompatibilityRangeDocument(
    val minAndroidSdk: Int,
    val maxAndroidSdk: Int? = null,
    val minInstallerVersion: String? = null,
    val maxInstallerVersion: String? = null,
) {
    fun toDomain(): CompatibilityRange = CompatibilityRange(
        minAndroidSdk = minAndroidSdk,
        maxAndroidSdk = maxAndroidSdk,
        minInstallerVersion = minInstallerVersion,
        maxInstallerVersion = maxInstallerVersion,
    )
}

@Serializable
data class ArtifactSourceDocument(
    val kind: String,
    val url: String,
) {
    fun toDomain(): ArtifactSource = ArtifactSource(
        kind = ArtifactSourceKind.fromWire(kind)
            ?: throw IllegalArgumentException("unknown_source_kind"),
        url = url,
    )
}
