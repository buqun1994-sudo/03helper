package com.ninepointnine.helper.data.artifact

import com.ninepointnine.helper.domain.device.ApkDeclarationMetadata
import com.ninepointnine.helper.domain.device.ApkServiceDeclaration
import java.nio.ByteBuffer
import net.dongliu.apk.parser.parser.BinaryXmlParser
import net.dongliu.apk.parser.parser.XmlStreamer
import net.dongliu.apk.parser.struct.xml.Attributes
import net.dongliu.apk.parser.struct.xml.XmlCData
import net.dongliu.apk.parser.struct.xml.XmlNamespaceEndTag
import net.dongliu.apk.parser.struct.xml.XmlNamespaceStartTag
import net.dongliu.apk.parser.struct.xml.XmlNodeEndTag
import net.dongliu.apk.parser.struct.xml.XmlNodeStartTag

/** Reads declarations exactly as encoded in AndroidManifest.xml. */
internal object AndroidBinaryManifestReader {
    fun read(manifestBytes: ByteArray): ApkManifestMetadata? {
        if (manifestBytes.size !in 1..MAX_APK_MANIFEST_BYTES) return null
        val collector = DeclarationCollector()
        return runCatching {
            BinaryXmlParser(ByteBuffer.wrap(manifestBytes), null).apply {
                xmlStreamer = collector
            }.parse()
            collector.result()
        }.getOrNull()
    }

    private class DeclarationCollector : XmlStreamer {
        private val tagStack = mutableListOf<String>()
        private val requestedPermissions = linkedSetOf<String>()
        private val servicesByComponent = linkedMapOf<String, String?>()
        private var packageName: String? = null
        private var invalid = false

        override fun onStartTag(tag: XmlNodeStartTag) {
            val parentPath = tagStack.toList()
            when {
                tag.name == MANIFEST_TAG && parentPath.isEmpty() -> {
                    val parsedPackage = tag.attributes.value(null, PACKAGE_ATTRIBUTE)
                    if (parsedPackage == null || !PACKAGE_NAME_PATTERN.matches(parsedPackage)) {
                        invalid = true
                    } else {
                        packageName = parsedPackage
                    }
                }

                tag.name in PERMISSION_TAGS && parentPath == listOf(MANIFEST_TAG) -> {
                    val permission = tag.attributes.value(ANDROID_NAMESPACE, NAME_ATTRIBUTE)
                    if (permission == null || !PERMISSION_NAME_PATTERN.matches(permission)) {
                        invalid = true
                    } else {
                        requestedPermissions += permission
                    }
                }

                tag.name == SERVICE_TAG && parentPath == listOf(MANIFEST_TAG, APPLICATION_TAG) -> {
                    collectService(tag.attributes)
                }
            }
            tagStack += tag.name
        }

        override fun onEndTag(tag: XmlNodeEndTag) {
            if (tagStack.lastOrNull() != tag.name) {
                invalid = true
                return
            }
            tagStack.removeAt(tagStack.lastIndex)
        }

        override fun onCData(data: XmlCData) = Unit

        override fun onNamespaceStart(tag: XmlNamespaceStartTag) = Unit

        override fun onNamespaceEnd(tag: XmlNamespaceEndTag) = Unit

        fun result(): ApkManifestMetadata? {
            val resolvedPackage = packageName
            if (invalid || resolvedPackage == null || tagStack.isNotEmpty()) return null
            return ApkManifestMetadata(
                packageName = resolvedPackage,
                declarations = ApkDeclarationMetadata(
                    requestedPermissions = requestedPermissions,
                    runtimeGrantPermissions = null,
                    services = servicesByComponent.mapTo(linkedSetOf()) { (componentName, permission) ->
                        ApkServiceDeclaration(componentName, permission)
                    },
                ),
            )
        }

        private fun collectService(attributes: Attributes) {
            val resolvedPackage = packageName ?: run {
                invalid = true
                return
            }
            val rawName = attributes.value(ANDROID_NAMESPACE, NAME_ATTRIBUTE)
            if (rawName.isNullOrBlank()) {
                invalid = true
                return
            }
            val componentName = serviceComponentName(resolvedPackage, rawName)
            if (!COMPONENT_NAME_PATTERN.matches(componentName)) {
                invalid = true
                return
            }
            val permission = attributes.value(ANDROID_NAMESPACE, PERMISSION_ATTRIBUTE)
                ?.takeIf(String::isNotBlank)
            if (permission != null && !PERMISSION_NAME_PATTERN.matches(permission)) {
                invalid = true
                return
            }
            if (servicesByComponent.containsKey(componentName) && servicesByComponent[componentName] != permission) {
                invalid = true
                return
            }
            servicesByComponent[componentName] = permission
        }
    }

    private fun Attributes.value(namespace: String?, name: String): String? = values()
        .firstOrNull { attribute -> attribute.name == name && attribute.namespace == namespace }
        ?.value

    private fun serviceComponentName(packageName: String, className: String): String {
        val qualified = when {
            className.startsWith('.') -> packageName + className
            className.contains('.') -> className
            else -> "$packageName.$className"
        }
        return "$packageName/$qualified"
    }

    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    private const val MANIFEST_TAG = "manifest"
    private const val APPLICATION_TAG = "application"
    private const val SERVICE_TAG = "service"
    private const val PACKAGE_ATTRIBUTE = "package"
    private const val NAME_ATTRIBUTE = "name"
    private const val PERMISSION_ATTRIBUTE = "permission"
    private val PERMISSION_TAGS = setOf(
        "uses-permission",
        "uses-permission-sdk-23",
        "uses-permission-sdk-m",
    )
    private val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val PERMISSION_NAME_PATTERN = PACKAGE_NAME_PATTERN
    private val COMPONENT_NAME_PATTERN = Regex(
        "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*/[A-Za-z0-9_.$]+$",
    )
}
