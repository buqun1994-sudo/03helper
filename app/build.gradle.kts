import java.io.File
import java.util.Properties

fun Properties.requiredSigningValue(name: String): String =
    getProperty(name)?.trim()?.takeIf(String::isNotEmpty)
        ?: error("Signing property '$name' is required")

fun Properties.requiredReleaseValue(name: String): String =
    getProperty(name)?.trim()?.takeIf(String::isNotEmpty)
        ?: error("Release version property '$name' is required")

fun Properties.loadUtf8(file: File) {
    file.reader(Charsets.UTF_8).use(::load)
}

val releaseVersionPropertiesFile = rootProject.file("release-version.properties")
val releaseVersionProperties = Properties().apply {
    require(releaseVersionPropertiesFile.isFile) {
        "Release version properties file does not exist: $releaseVersionPropertiesFile"
    }
    loadUtf8(releaseVersionPropertiesFile)
}
val releaseVersionName = releaseVersionProperties.requiredReleaseValue("releaseVersionName")
val releaseVersionCode = releaseVersionProperties.requiredReleaseValue("releaseVersionCode").toIntOrNull()
    ?: error("Release version property 'releaseVersionCode' must be a positive integer")
require(releaseVersionCode > 0) {
    "Release version property 'releaseVersionCode' must be a positive integer"
}
require(Regex("\\d+\\.\\d+\\.\\d+").matches(releaseVersionName)) {
    "Release version name must match <major>.<minor>.<patch>"
}

val helperSigningEnvironment = providers.gradleProperty("helperSigningEnvironment")
    .orElse("debug")
    .get()
    .trim()
    .lowercase()
require(helperSigningEnvironment in setOf("debug", "staging")) {
    "helperSigningEnvironment must be debug or staging"
}

val stagingSigningPropertiesFile = providers.gradleProperty("helperStagingSigningPropertiesFile")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.let(rootProject::file)
val stagingSigningProperties = Properties()
val stagingSigningStoreFile = if (helperSigningEnvironment == "staging") {
    val propertiesFile = requireNotNull(stagingSigningPropertiesFile) {
        "Staging APK signing properties file is required"
    }
    require(propertiesFile.isFile) {
        "Staging APK signing properties file does not exist"
    }
    propertiesFile.reader(Charsets.UTF_8).use(stagingSigningProperties::load)
    val configuredStoreFile = stagingSigningProperties.requiredSigningValue("storeFile")
    val candidate = File(configuredStoreFile)
    val resolved = if (candidate.isAbsolute) candidate else propertiesFile.parentFile.resolve(configuredStoreFile)
    require(resolved.isFile) { "Staging APK keystore does not exist" }
    resolved
} else {
    null
}

val productionSigningPropertiesFile = providers.gradleProperty("helperProductionSigningPropertiesFile")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.let(rootProject::file)
val productionSigningProperties = Properties()
val productionSigningStoreFile = productionSigningPropertiesFile?.let { propertiesFile ->
    require(propertiesFile.isFile) {
        "Production APK signing properties file does not exist"
    }
    propertiesFile.reader(Charsets.UTF_8).use(productionSigningProperties::load)
    val configuredStoreFile = productionSigningProperties.requiredSigningValue("storeFile")
    val candidate = File(configuredStoreFile)
    val resolved = if (candidate.isAbsolute) candidate else propertiesFile.parentFile.resolve(configuredStoreFile)
    require(resolved.isFile) { "Production APK keystore does not exist" }
    resolved
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.ninepointnine.helper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ninepointnine.helper"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (productionSigningStoreFile != null) {
                storeFile = productionSigningStoreFile
                storePassword = productionSigningProperties.requiredSigningValue("storePassword")
                keyAlias = productionSigningProperties.requiredSigningValue("keyAlias")
                keyPassword = productionSigningProperties.requiredSigningValue("keyPassword")
            }
        }
        if (stagingSigningStoreFile != null) {
            create("staging") {
                storeFile = stagingSigningStoreFile
                storePassword = stagingSigningProperties.requiredSigningValue("storePassword")
                keyAlias = stagingSigningProperties.requiredSigningValue("keyAlias")
                keyPassword = stagingSigningProperties.requiredSigningValue("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
            signingConfigs.findByName("staging")?.let { signingConfig = it }
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }


    testOptions {
        unitTests.isReturnDefaultValues = true
        animationsDisabled = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
        )
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.versionName.set(releaseVersionName)
            output.versionCode.set(releaseVersionCode)
        }
    }
}

tasks.configureEach {
    if (name == "preReleaseBuild" || name == "validateSigningRelease") {
        doFirst {
            require(productionSigningStoreFile != null) {
                "Production APK signing properties file is required. " +
                    "Pass -PhelperProductionSigningPropertiesFile=<path-to-signing.properties>."
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("dev.mobile:dadb:1.2.9") {
        // dadb publishes its JUnit platform helper as runtime metadata; it is not needed in the APK.
        exclude(group = "org.graalvm.buildtools", module = "junit-platform-native")
    }

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("com.composables:icons-lucide-android:2.2.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
