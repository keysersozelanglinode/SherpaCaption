import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

data class AndroidSdkTarget(
    val apiLevel: Int,
    val minorApiLevel: Int?
)

fun localSdkDir(): File {
    val properties = Properties()
    val localProperties = rootProject.file("local.properties")
    if (localProperties.isFile) {
        localProperties.inputStream().use(properties::load)
    }

    val sdkPath = properties.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("Android SDK not found. Set sdk.dir in local.properties or ANDROID_HOME.")

    return File(sdkPath)
}

fun latestInstalledStableSdk(): AndroidSdkTarget {
    val platformsDir = localSdkDir().resolve("platforms")
    val platformPattern = Regex("""android-(\d+)(?:\.(\d+))?""")

    return platformsDir
        .listFiles { file -> file.isDirectory && platformPattern.matches(file.name) }
        .orEmpty()
        .mapNotNull { platformDir ->
            val match = platformPattern.matchEntire(platformDir.name) ?: return@mapNotNull null
            val apiLevel = match.groupValues[1].toInt()
            val minorApiLevel = match.groupValues
                .getOrNull(2)
                ?.takeIf(String::isNotBlank)
                ?.toIntOrNull()

            AndroidSdkTarget(apiLevel, minorApiLevel)
        }
        .maxWithOrNull(
            compareBy<AndroidSdkTarget> { it.apiLevel }
                .thenBy { it.minorApiLevel ?: 0 }
        )
        ?: error("No stable Android SDK platform is installed.")
}

val installedSdk = latestInstalledStableSdk()

android {
    namespace = "com.sherpacaption.app"
    compileSdk {
        version = release(installedSdk.apiLevel) {
            installedSdk.minorApiLevel?.let { minorApiLevel = it }
        }
    }

    defaultConfig {
        applicationId = "com.sherpacaption.app"
        minSdk = 29
        targetSdk = installedSdk.apiLevel
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.3.aar"))
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
